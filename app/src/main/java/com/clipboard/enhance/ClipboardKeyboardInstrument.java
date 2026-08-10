package com.clipboard.enhance;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
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

    /** 模块状态 */
    private static volatile boolean sSearchMode = false;
    private static volatile Object sPage;          // ClipboardPage 实例
    private static volatile Object sKeyboard;      // ClipboardKeyboard 实例
    private static volatile ClassLoader sCl;       // 目标进程类加载器

    /** 模块按钮矩形（随 drawBase 每次更新，供 touchInButton 命中） */
    private static final Rect sSearchRect = new Rect();
    private static final Rect sCommitAllRect = new Rect();
    private static volatile boolean sSearchRectValid = false;
    private static volatile boolean sCommitAllRectValid = false;

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
            // ---- 非整理态：「搜索」画在「整理」(getmSelectingBtnRect) 左侧 ----
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
            String label = "搜索";
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
            canvas.drawText(label, left + pad / 2f, baseY, moduleTextPaint(paint, false));

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
            canvas.drawText(label, left + pad / 2f, baseY, moduleTextPaint(paint, true));

            sSearchRectValid = false;
        }
    }

    private static Paint moduleTextPaint(Paint base, boolean selecting) {
        Paint p = new Paint(base);
        p.setStyle(Paint.Style.FILL);
        // 沿用原生画笔风格：仅加深颜色，突出模块按钮（不带背景色）
        p.setColor(0xFF2563EB); // 统一主题蓝
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
                                    onSearchClick();
                                    param.setResult(Integer.MIN_VALUE); // 屏蔽原生命中
                                    return;
                                }
                                if (selecting && sCommitAllRectValid && sCommitAllRect.contains((int) x, (int) y)) {
                                    onCommitAllClick();
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

    /* ================= 模块动作 ================= */

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