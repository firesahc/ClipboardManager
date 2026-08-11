package com.clipboard.enhance;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 剪贴板增强核心 Hook。
 *
 * 逆向事实（com.sohu.inputmethod.clipboard.*，混淆名）：
 * - ClipboardCandidateView：顶栏视图，按钮全部 Canvas 自绘
 *   - drawBase(Canvas) 绘制入口（afterHookedMethod 追加模块按钮）
 *   - touchInButton(x,y) 命中判定：12=整理 13=取消 14=删除 15=全选（MIN_VALUE=未命中）
 *   - 非整理态「整理」矩形 = mSelectingBtnRect（getter: getmSelectingBtnRect）
 *   - 整理态「删除」矩形 = mClearButtonWholeRect、「取消」= mFinishBtnRect、「全选」= mAllBtnRect
 * - ClipboardKeyboard：列表面板
 *   - 数据源字段 M（List<c>）、adapter 字段 N（b 类）、adapter 显示列表字段 j
 *   - C(List) = onChanged 上报全量列表
 *   - a(int) 上屏 = u.Z().A(M.get(i).d)（剪贴板上屏，与拼音路径完全分离）
 * - com.sohu.inputmethod.input.InputLogic
 *   - pickSuggestion(CharSequence) = 拼音候选选词上屏入口（搜索模式拦截点）
 *   - 注意：u.A(String)（sogou.u）只是剪贴板/快捷短语上屏落点，拦截它会误伤条目上屏
 * - ClipboardPage：页面，M() 创建视图（afterHookedMethod 记录实例）、w() 私有=收起面板回主键盘
 * - p（clipboard 数据仓库，静态单例 t()，单线程池串行）：
 *   - H(c)（static synthetic，插入上限裁剪点）：去重(Content unique) → insertOrReplace →
 *     orderAsc(Time) 查询，size>150 则 delete(list.get(0)) 删除最旧 —— 上限 150 条
 *     绕过：before 备份最旧条目，after 无条件 insertOrReplace 插回（未超限时幂等无害）
 *   - db 链：db.a.b().a().a() → ClipboardItemDao（主键自增 _id，实体带 id 插回安全）
 * - ClipboardCandidateView.setClipboardCount(int,int)：标题旁数字（Canvas 绘制）：
 *   mCountText = String.format(getString(R$string.clipboard_count_text), mCount)
 *   覆写 mCountText 为「当前显示数量/当前总数量」并 invalidate 即可
 */
public final class ClipboardKeyboardInstrument {

    /* ================= 混淆类名（字符串引用，防混淆） ================= */
    private static final String CLS_CANDIDATE = "com.sohu.inputmethod.clipboard.ClipboardCandidateView";
    private static final String CLS_KEYBOARD = "com.sohu.inputmethod.clipboard.ClipboardKeyboard";
    private static final String CLS_ADAPTER = "com.sohu.inputmethod.clipboard.b";
    private static final String CLS_COMMIT = "com.sohu.inputmethod.sogou.u";
    private static final String CLS_PAGE = "com.sohu.inputmethod.main.page.ClipboardPage";
    private static final String CLS_PAGE_BASE = "com.sohu.inputmethod.main.page.base.BaseSPage";
    /** 拼音候选选词入口（路径与剪贴板上屏 u.A() 完全分离） */
    private static final String CLS_INPUT_LOGIC = "com.sohu.inputmethod.input.InputLogic";

    /** 剪贴板数据仓库（150 条上限裁剪点 p.H，static synthetic，lambda 桥接名保留） */
    private static final String CLS_CLIP_REPO = "com.sohu.inputmethod.clipboard.p";
    /** 剪贴板条目实体（db.c 实体的混淆名，字段 d=content、c=time） */
    private static final String CLS_CLIP_ITEM = "com.sohu.inputmethod.clipboard.c";
    private static final String CLS_VIEW_MODEL = "com.sohu.inputmethod.clipboard.ClipboardViewModel";
    /** 数据库门面 → session → dao 链：db.a.b().a().a() */
    private static final String CLS_DB_A = "com.sohu.inputmethod.clipboard.db.a";

    /** 模块状态 */
    private static volatile boolean sSearchMode = false;
    private static volatile Object sPage;          // ClipboardPage 实例
    private static volatile Object sKeyboard;      // ClipboardKeyboard 实例
    private static volatile ClassLoader sCl;       // 目标进程类加载器
    private static volatile Object sCandidateView; // ClipboardCandidateView 实例（drawBase 记录）

    /** 模块按钮矩形（随 drawBase 每次更新，供 touchInButton 命中） */
    private static final Rect sSearchRect = new Rect();
    private static final Rect sCommitAllRect = new Rect();
    private static volatile boolean sSearchRectValid = false;
    private static volatile boolean sCommitAllRectValid = false;
    // 触摸去抖：DOWN/UP 事件序列会连续两次命中 touchInButton，
    // 去抖后只处理第一次（否则「全部」清除后 UP 再次命中会误触「搜索」）
    private static volatile long sLastTouchTime = 0L;
    private static volatile float sLastTouchX = 0f;
    private static volatile float sLastTouchY = 0f;
    private static final long TOUCH_DEBOUNCE_MS = 500L;

    private ClipboardKeyboardInstrument() {
    }

    /* ================= 入口 ================= */
    public static void init(final ClassLoader cl) {
        sCl = cl;
        try {
            hookCandidateDraw(cl);
            hookCandidateTouch(cl);
            hookKeyboardChanged(cl);
            hookKeyboardItem(cl);
            hookCommit(cl);
            hookPageCreate(cl);
            hookClipboardLimit(cl);
            hookCandidateCount(cl);
            hookAdapterSelectAll(cl);
            hookDeleteScope(cl);
            hookImeRestart(cl);
            XposedBridge.log("[ClipboardEnhance] all hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] init error: " + t);
        }
    }

    /* ================= 1. drawBase 后置绘制模块按钮 ================= */
    private static void hookCandidateDraw(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_CANDIDATE, cl, "drawBase", Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                sCandidateView = param.thisObject;
                                drawModuleButtons((View) param.thisObject, (Canvas) param.args[0]);
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] drawBase after error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook drawBase failed: " + t);
        }
    }

    private static void drawModuleButtons(View view, Canvas canvas) {
        boolean selecting = false;
        try {
            Object v = XposedHelpers.callMethod(view, "isSelecting");
            selecting = Boolean.TRUE.equals(v);
        } catch (Throwable ignored) {
        }
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // 复用原生画笔风格：反射取 mPaint；取不到则自建
        Paint paint;
        try {
            paint = (Paint) XposedHelpers.getObjectField(view, "mPaint");
        } catch (Throwable t) {
            paint = new Paint();
            paint.setAntiAlias(true);
        }

        if (!selecting) {
            // ---- 非整理态：「搜索/全部」画在「整理」(getmSelectingBtnRect) 左侧 ----
            // 筛选生效时按钮变「全部」，点击清除筛选恢复全量
            boolean filtering = ListFilterProxy.getKeyword().length() > 0;
            Rect nativeBtn;
            try {
                nativeBtn = (Rect) XposedHelpers.callMethod(view, "getmSelectingBtnRect");
            } catch (Throwable t) {
                nativeBtn = null;
            }
            if (nativeBtn == null) {
                return;
            }
            float gap = 8f;
            float textSize;
            try {
                textSize = paint.getTextSize();
            } catch (Throwable t) {
                textSize = 14f;
            }
            String label = filtering ? "全部" : "搜索";
            float textW = paint.measureText(label);
            // 按钮 = 文字 + 内边距
            float pad = textSize * 0.9f;
            float btnW = textW + pad;
            int left = (int) (nativeBtn.left - gap - btnW);
            sSearchRect.set(left, 0, (int) (left + btnW), h);
            sSearchRectValid = true;

            // 纯文字绘制，与原生按钮风格一致，不画背景
            float baseY = (sSearchRect.top + sSearchRect.bottom) / 2f
                    - (paint.descent() + paint.ascent()) / 2f;
            canvas.drawText(label, left + pad / 2f, baseY, moduleTextPaint(view, paint, false));

            sCommitAllRectValid = false; // 非整理态无此按钮
        } else {
            // ---- 整理态：「输入全部(N)」画在「删除」(mClearButtonWholeRect) 左侧 ----
            Rect deleteRect;
            try {
                deleteRect = (Rect) XposedHelpers.getObjectField(view, "mClearButtonWholeRect");
            } catch (Throwable t) {
                deleteRect = null;
            }
            if (deleteRect == null) {
                return;
            }
            int count = selectedCount();
            String label = count > 0 ? "输入全部(" + count + ")" : "输入全部";
            float textW = paint.measureText(label);
            float pad = paint.getTextSize() * 0.9f;
            float btnW = textW + pad;
            float gap = 8f;
            int right = (int) (deleteRect.left - gap);
            int left = (int) (right - btnW);
            sCommitAllRect.set(left, 0, right, view.getHeight());
            sCommitAllRectValid = true;

            // 纯文字绘制，与原生按钮风格一致，不画背景
            float baseY = (sCommitAllRect.top + sCommitAllRect.bottom) / 2f
                    - (paint.descent() + paint.ascent()) / 2f;
            canvas.drawText(label, left + pad / 2f, baseY, moduleTextPaint(view, paint, true));

            sSearchRectValid = false;
        }
    }

    /** 模块按钮画笔：颜色采自原生按钮当前色（已含主题/暗色适配），与原生观感一致 */
    private static Paint moduleTextPaint(View view, Paint base, boolean selecting) {
        Paint p = new Paint(base);
        p.setStyle(Paint.Style.FILL);
        int color = 0xFF9F9B95; // 兜底：原生 TEXT_BTN_TEXT_COLOR（灰）
        try {
            // 原生颜色字段：非整理态「整理」= mSelectingBtnColor，整理态「全选」= mSelectingAllTextColor
            color = XposedHelpers.getIntField(view, selecting ? "mSelectingAllTextColor" : "mSelectingBtnColor");
        } catch (Throwable ignored) {
        }
        p.setColor(color);
        return p;
    }

    /** 当前勾选数：keyboard.adapter(N=b) → g() 勾选列表 size */
    private static int selectedCount() {
        try {
            Object kb = sKeyboard;
            if (kb == null) {
                return 0;
            }
            Object adapter = XposedHelpers.getObjectField(kb, "N");
            if (adapter == null) {
                return 0;
            }
            Object list = XposedHelpers.callMethod(adapter, "g");
            return list instanceof List ? ((List<?>) list).size() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ================= 2. touchInButton 前置拦截模块按钮 ================= */
    private static void hookCandidateTouch(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_CANDIDATE, cl, "touchInButton", float.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                View view = (View) param.thisObject;
                                boolean selecting = Boolean.TRUE.equals(
                                        XposedHelpers.callMethod(view, "isSelecting"));
                                float x = (Float) param.args[0];
                                float y = (Float) param.args[1];
                                if (!selecting && sSearchRectValid && sSearchRect.contains((int) x, (int) y)) {
                                    // 去抖：DOWN/UP 事件序列会连续两次命中，只执行第一次
                                    if (debounceTouch(x, y)) {
                                        // 筛选生效中 → 清除筛选（「全部」）；否则进入搜索模式
                                        if (ListFilterProxy.getKeyword().length() > 0) {
                                            onClearFilter();
                                        } else {
                                            onSearchClick();
                                        }
                                    }
                                    param.setResult(Integer.MIN_VALUE); // 屏蔽原生命中
                                    return;
                                }
                                if (selecting && sCommitAllRectValid && sCommitAllRect.contains((int) x, (int) y)) {
                                    if (debounceTouch(x, y)) {
                                        onCommitAllClick();
                                    }
                                    param.setResult(Integer.MIN_VALUE);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] touchInButton error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook touchInButton failed: " + t);
        }
    }

    /* ================= 3. onChanged 拦截 → 过滤代理 ================= */
    private static void hookKeyboardChanged(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_KEYBOARD, cl, "C", List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                sKeyboard = param.thisObject;
                                @SuppressWarnings("unchecked")
                                List<Object> full = (List<Object>) param.args[0];
                                ListFilterProxy.onListChanged(full);
                                swapList();
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] onChanged error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook C(List) failed: " + t);
        }
    }

    /** 将过滤结果写回 M 字段 + adapter.j，并尝试刷新 */
    private static void swapList() {
        try {
            Object kb = sKeyboard;
            if (kb == null) {
                return;
            }
            List<Object> active = ListFilterProxy.activeList();
            XposedHelpers.setObjectField(kb, "M", active);
            try {
                Object adapter = XposedHelpers.getObjectField(kb, "N");
                if (adapter != null) {
                    XposedHelpers.setObjectField(adapter, "j", active);
                    try {
                        XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                    } catch (Throwable ignored) {
                        // adapter 可能无此方法；原生会用内部机制刷新
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] swapList error: " + t);
        }
        // 过滤/恢复后同步刷新标题旁数字（当前显示/总数）
        refreshCountText(null);
    }

    /* ================= 4a. 剪贴板条目上屏：兜底复位搜索模式 =================
       剪贴板上屏路径 a(int) → u.Z().A(...)，不走拼音候选；
       若搜索模式残留，点击条目时在此复位，防止误拦截 */
    private static void hookKeyboardItem(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_KEYBOARD, cl, "a", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (sSearchMode) {
                                sSearchMode = false;
                                XposedBridge.log("[ClipboardEnhance] item click -> exit search mode");
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook item failed: " + t);
        }
    }

    /* ================= 4b. 拼音候选选词拦截（搜索模式） =================
       InputLogic.pickSuggestion(CharSequence) = 拼音候选选词上屏入口；
       剪贴板上屏走 u.A() 与 pickSuggestion 完全分离，互不干扰 */
    private static void hookCommit(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_INPUT_LOGIC, cl, "pickSuggestion", CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!sSearchMode) {
                                    return; // 正常输入放行
                                }
                                CharSequence cs = (CharSequence) param.args[0];
                                String keyword = cs == null ? null : cs.toString();
                                if (keyword == null || keyword.length() == 0) {
                                    return;
                                }
                                // 搜索模式：拦截为关键词，不真正上屏
                                sSearchMode = false;
                                param.setResult(null);
                                XposedBridge.log("[ClipboardEnhance] pickSuggestion(\"" + keyword + "\") intercepted -> keyword");
                                ListFilterProxy.setKeyword(keyword);
                                reopenClipboardPage();
                                swapList();
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] pickSuggestion error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook pickSuggestion failed: " + t);
        }
    }

    /* ================= 5. 页面实例记录 ================= */
    private static void hookPageCreate(ClassLoader cl) {
        try {
            // ClipboardPage.M() 创建视图 → 记录 page 实例
            XposedHelpers.findAndHookMethod(CLS_PAGE, cl, "M",
                    android.view.LayoutInflater.class, android.view.ViewGroup.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sPage = param.thisObject;
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook page.M failed: " + t);
        }
    }

    /* ================= 6. 去除 150 条上限 =================
       插入入口 H(c)：去重 → insertOrReplace → 超过 150 条删除最旧。
       before 备份最旧条目，after 无条件重新插入 —— 原逻辑删除的正是它，
       插回后等效「无上限」；未超限时插回是幂等更新（主键自增 _id，实体带 id），无副作用 */
    private static void hookClipboardLimit(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_CLIP_REPO, cl, "H", CLS_CLIP_ITEM,
                    new XC_MethodHook() {
                        private volatile Object sOldestBackup; // 单线程池串行，volatile 仅防御

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            sOldestBackup = null;
                            try {
                                Object dao = getClipboardDao();
                                if (dao == null) {
                                    return;
                                }
                                // 按时间升序取最旧一条（与原逻辑 orderAsc(Time).list().get(0) 一致）
                                Object timeProp = XposedHelpers.getStaticObjectField(
                                        XposedHelpers.findClass(
                                                "com.sohu.inputmethod.clipboard.db.ClipboardItemDao$Properties", cl),
                                        "Time");
                                Object qb = XposedHelpers.callMethod(dao, "queryBuilder");
                                Object qbAsc = XposedHelpers.callMethod(qb, "orderAsc", timeProp);
                                List<?> all = (List<?>) XposedHelpers.callMethod(qbAsc, "list");
                                if (all != null && !all.isEmpty()) {
                                    sOldestBackup = all.get(0);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] backup oldest failed: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object backup = sOldestBackup;
                            sOldestBackup = null;
                            if (backup == null) {
                                return;
                            }
                            try {
                                Object dao = getClipboardDao();
                                if (dao != null) {
                                    XposedHelpers.callMethod(dao, "insertOrReplace", backup);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] restore oldest failed: " + t);
                            }
                        }
                    });
            XposedBridge.log("[ClipboardEnhance] 150-limit bypass installed");
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook p.H failed: " + t);
        }
    }

    /** db.a.b().a().a() → ClipboardItemDao（链式反射，任一步失败返回 null） */
    private static Object getClipboardDao() {
        try {
            Class<?> dbA = XposedHelpers.findClass(CLS_DB_A, sCl);
            Object holder = XposedHelpers.callStaticMethod(dbA, "b");
            Object session = XposedHelpers.callMethod(holder, "a");
            return XposedHelpers.callMethod(session, "a");
        } catch (Throwable t) {
            return null;
        }
    }

    /* ================= 7. 标题数字 → 「当前显示数量/当前总数量」 =================
       原生 setClipboardCount(i,i2)：mCountText = String.format(getString(clipboard_count_text), i)
       after 覆写 mCountText：保留原生格式串（如 "(%d)" → "5/20"），数字为 显示数/总数 */
    private static void hookCandidateCount(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_CANDIDATE, cl, "setClipboardCount", int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                refreshCountText(param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] setClipboardCount error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[ClipboardEnhance] count text hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook setClipboardCount failed: " + t);
        }
    }

    /* ================= 8. 全选 → 只选当前筛选结果 =================
       原生全选链路：全选按钮 → onMenuClick(15) → a(6) → Page.L(6) →
       Keyboard.I(isAllSelected) → adapter.l(boolean) 遍历 this.j 全部置勾选。
       adapter.j 为显示列表（getCount/getView/g() 勾选集合均基于它）；
       进入整理态时 j 可能被原生重置为全量列表（Q() → N.o(this.M)），
       导致全选勾上筛选范围外的条目。此处 before 强制把 j 换回当前生效过滤列表：
       - 过滤中：j = 过滤子集（只全选筛选出的项）
       - 未过滤：j = 全量（与原生行为一致）
       由 swapList() 写回的 j 本来就是这个值，重复赋值幂等无害 */
    private static void hookAdapterSelectAll(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_ADAPTER, cl, "l", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                List<Object> active = ListFilterProxy.activeList();
                                if (active != null) {
                                    XposedHelpers.setObjectField(param.thisObject, "j", active);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] select-all scope error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[ClipboardEnhance] select-all scope hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook adapter.l failed: " + t);
        }
    }

    /** 将标题旁数字刷新为「当前显示数量/当前总数量」 */
    private static void refreshCountText(Object candidateView) {
        if (candidateView == null) {
            candidateView = sCandidateView;
        }
        if (candidateView == null) {
            return;
        }
        int total = ListFilterProxy.totalCount();
        int shown = ListFilterProxy.filteredCount();
        if (total <= 0) {
            return; // 列表尚未加载，保持原生行为（原方法已设置 mCountText）
        }
        // 原生格式串 clipboard_count_text 内写死了上限 150（如 "(%d/150)"），
        // 既然已去除上限，不再沿用格式串，直接输出纯「当前显示/总数」数字
        String text = shown + "/" + total;
        try {
            XposedHelpers.setObjectField(candidateView, "mCountText", text);
        } catch (Throwable t) {
            return;
        }
        ((View) candidateView).invalidate();
    }

    /* ================= 9. 删除范围保护（筛选态下全选删除） =================
       原生删除确认链：删除按钮 → L(7) → Keyboard.p() → w(N.g()) 弹确认框
       → ClipboardKeyboard.d.onClick：
           if (勾选集.size() != this.M.size()) → ViewModel.g(list) 逐条删勾选集
           else                                → ViewModel.e() 清空全部
       筛选态下 swapList 把 M 也写成了过滤子集，导致「全选勾选集 == M」
       被原生误判为「清空全部」，实际清空整个数据库。
       修复双保险：
       - hook w(List) after：筛选态强制对话框文案为「删除N条」（避免误导）
       - hook ViewModel.e() before：筛选态屏蔽清空全部，改删当前勾选集 */
    private static void hookDeleteScope(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_KEYBOARD, cl, "w", List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (ListFilterProxy.getKeyword().length() > 0) {
                                    // 原生文案分支取决于 list.size()==M.size()，筛选态恒相等；
                                    // 直接覆写确认框文案为「删除N条」
                                    Object dialog = XposedHelpers.getObjectField(param.thisObject, "Q");
                                    if (dialog != null) {
                                        @SuppressWarnings("unchecked")
                                        List<Object> checked = (List<Object>) param.args[0];
                                        String msg = "您确定删除剪贴板" + checked.size() + "条内容吗?";
                                        XposedHelpers.callMethod(dialog, "setMessage", (Object) msg);
                                        XposedBridge.log("[ClipboardEnhance] delete dialog msg: " + msg);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] delete dialog msg error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[ClipboardEnhance] delete dialog hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook Keyboard.w failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(CLS_VIEW_MODEL, cl, "e",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (ListFilterProxy.getKeyword().length() > 0) {
                                    // 取当前勾选集（此刻全选勾选尚未清除）
                                    Object kb = sKeyboard;
                                    if (kb != null) {
                                        Object adapter = XposedHelpers.getObjectField(kb, "N");
                                        Object list = adapter == null ? null : XposedHelpers.callMethod(adapter, "g");
                                        if (list instanceof List && !((List<?>) list).isEmpty()) {
                                            // 屏蔽清空全部，重定向为只删勾选集
                                            param.setResult(null);
                                            XposedHelpers.callMethod(param.thisObject, "g", list);
                                            XposedBridge.log("[ClipboardEnhance] clear-all rerouted to checked-only ("
                                                    + ((List<?>) list).size() + ")");
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] delete scope error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[ClipboardEnhance] delete-scope hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook ViewModel.e failed: " + t);
        }
    }

    /* ================= 10. IME 重启（关闭再打开）时清空筛选 =================
       筛选关键词是静态状态；输入法服务重建（SogouIME.onCreate）后旧筛选
       不应残留 —— 关闭再打开应恢复全量。hook 基类 SogouIME（xiaomi.SogouIME
       为空子类）的 onCreate，after 清空关键词并复位搜索模式。
       注意：搜索流程只切页面（reopenClipboardPage）不重建 IME，不受影响 */
    private static void hookImeRestart(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.sohu.inputmethod.sogou.SogouIME", cl, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                sSearchMode = false;
                                ListFilterProxy.clearKeyword();
                                XposedBridge.log("[ClipboardEnhance] IME recreated, filter cleared");
                            } catch (Throwable t) {
                                XposedBridge.log("[ClipboardEnhance] ime onCreate error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[ClipboardEnhance] ime-restart hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] hook SogouIME.onCreate failed: " + t);
        }
    }

    /* ================= 模块动作 ================= */

    /**
     * 触摸去抖：同一点（±30px）TOUCH_DEBOUNCE_MS 内的重复事件（DOWN/UP 序列）
     * 返回 false，只允许第一次命中执行动作，后续事件仅屏蔽不执行。
     */
    private static boolean debounceTouch(float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastTouchTime < TOUCH_DEBOUNCE_MS
                && Math.abs(x - sLastTouchX) < 30f
                && Math.abs(y - sLastTouchY) < 30f) {
            return false;
        }
        sLastTouchTime = now;
        sLastTouchX = x;
        sLastTouchY = y;
        return true;
    }

    /** 清除筛选：恢复全量列表（按钮「全部」） */
    private static void onClearFilter() {
        XposedBridge.log("[ClipboardEnhance] clear filter");
        sSearchMode = false;
        ListFilterProxy.clearKeyword();
        swapList();
    }

    /** 🔍搜索：进入搜索模式 → 收起面板（露出主键盘拼音输入） */
    private static void onSearchClick() {
        sSearchMode = true;
        XposedBridge.log("[ClipboardEnhance] 🔍 enter search mode");
        // 收起剪贴板面板回主键盘（原版行为 w() 私有 → 反射调用）
        try {
            Object page = sPage;
            if (page != null) {
                XposedHelpers.callMethod(page, "w");
            }
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] collapse panel error: " + t);
        }
        // 清空旧关键词，列表恢复
        ListFilterProxy.clearKeyword();
        swapList();
    }

    /** 输入全部：勾选内容逐条上屏，保持整理态 */
    private static void onCommitAllClick() {
        try {
            Object kb = sKeyboard;
            if (kb == null) {
                return;
            }
            Object adapter = XposedHelpers.getObjectField(kb, "N");
            if (adapter == null) {
                return;
            }
            Object list = XposedHelpers.callMethod(adapter, "g");
            if (!(list instanceof List)) {
                return;
            }
            List<?> selected = (List<?>) list;
            if (selected.isEmpty()) {
                return;
            }
            // 逐条上屏：u.Z().A(item.d)；保持整理态不退出
            Class<?> commitClass = XposedHelpers.findClass(CLS_COMMIT, kb.getClass().getClassLoader());
            Object commit = XposedHelpers.callStaticMethod(commitClass, "Z");
            int n = 0;
            for (Object item : selected) {
                Object text = XposedHelpers.getObjectField(item, "d");
                if (text == null) {
                    continue;
                }
                XposedHelpers.callMethod(commit, "A", String.valueOf(text));
                n++;
            }
            XposedBridge.log("[ClipboardEnhance] committed " + n + " items, stay in manage mode");
            // 保持整理态：不退出；按钮计数由下一次 drawBase 自动刷新
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] commitAll error: " + t);
        }
    }

    /** 关键词确认后重新打开剪贴板页（路由方式，失败则用户手动打开，不影响功能） */
    private static void reopenClipboardPage() {
        try {
            Class<?> base = XposedHelpers.findClass(CLS_PAGE_BASE, sCl);
            XposedHelpers.callStaticMethod(base, "F",
                    "/app/ClipboardPage", (Object) null);
        } catch (Throwable t) {
            XposedBridge.log("[ClipboardEnhance] reopen page failed (manual reopen ok): " + t);
        }
    }

    /** 供 ListFilterProxy 回调：写回后刷新列表 */
    static {
        ListFilterProxy.setOnSwap(ClipboardKeyboardInstrument::swapList);
    }
}