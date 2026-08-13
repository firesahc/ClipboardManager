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
 * ClipboardCandidateView 领域 Hook：模块按钮绘制、触摸命中、标题计数、取消退出整理态。
 *
 * 逆向事实（com.sohu.inputmethod.clipboard.ClipboardCandidateView，混淆名）：
 * - 顶栏视图，按钮全部 Canvas 自绘
 * - drawBase(Canvas) 绘制入口（afterHookedMethod 追加模块按钮）
 * - touchInButton(x,y) 命中判定：12=整理 13=取消 14=删除 15=全选（MIN_VALUE=未命中）
 * - 非整理态「整理」矩形 = mSelectingBtnRect（getter: getmSelectingBtnRect）
 * - 整理态「删除」矩形 = mClearButtonWholeRect、「取消」= mFinishBtnRect、「全选」= mAllBtnRect
 * - setClipboardCount(int,int)：标题旁数字（Canvas 绘制）：
 *   mCountText = String.format(getString(R$string.clipboard_count_text), mCount)
 *   覆写 mCountText 为「当前显示数量/当前总数量」并 invalidate 即可
 * - 剪贴板上屏落点 u.Z().A(String)（sogou.u，见 onCommitAllClick）
 *
 * 搜索/清除筛选动作由 SearchModeController 提供（本类触摸命中后调用）。
 */
public final class CandidateViewHooks {

    /* ================= 混淆类名（字符串引用，防混淆） ================= */
    private static final String CLS_CANDIDATE = "com.sohu.inputmethod.clipboard.ClipboardCandidateView";
    private static final String CLS_COMMIT = "com.sohu.inputmethod.sogou.u";

    /* ================= 常量 ================= */
    /** touchInButton 未命中返回值（原生命中 MIN_VALUE），模块按钮拦截复用同值屏蔽 */
    private static final int HIT_SUPPRESSED = Integer.MIN_VALUE;
    /** 触摸去抖：同一点 ±TOUCH_DEBOUNCE_DIST_PX 内 TOUCH_DEBOUNCE_MS 只执行第一次动作 */
    private static final long TOUCH_DEBOUNCE_MS = 500L;
    private static final float TOUCH_DEBOUNCE_DIST_PX = 30f;
    /** 模块按钮绘制：与相邻原生按钮的间距、文字内边距系数（相对字号） */
    private static final float BTN_GAP_PX = 8f;
    private static final float BTN_PAD_FACTOR = 0.9f;
    private static final float FALLBACK_TEXT_SIZE_PX = 14f;
    /** 兜底按钮色：原生 TEXT_BTN_TEXT_COLOR（灰），字段反射失败时使用 */
    private static final int FALLBACK_BTN_COLOR = 0xFF9F9B95;
    /** 模块按钮文案（宿主进程无应用资源，硬编码与原生 UI 语言一致） */
    private static final String LABEL_SEARCH = "搜索";
    private static final String LABEL_ALL = "全部";
    private static final String LABEL_COMMIT_ALL = "输入全部";

    /** 模块按钮矩形（随 drawBase 每次更新，供 touchInButton 命中） */
    private static final Rect sSearchRect = new Rect();
    private static final Rect sCommitAllRect = new Rect();
    private static volatile boolean sSearchRectValid = false;
    private static volatile boolean sCommitAllRectValid = false;
    /** 触摸去抖状态：记录上次动作的时间与坐标 */
    private static volatile long sLastTouchTime = 0L;
    private static volatile float sLastTouchX = 0f;
    private static volatile float sLastTouchY = 0f;

    private CandidateViewHooks() {
    }

    /** 注册本领域全部 hook（注册顺序与拆分前一致） */
    public static void init(ClassLoader cl) {
        hookCandidateDraw(cl);
        hookCandidateTouch(cl);
        hookCandidateCount(cl);
        hookExitSelecting(cl);
    }

    /* ================= 1. drawBase 后置绘制模块按钮 ================= */
    private static void hookCandidateDraw(ClassLoader cl) {
        HookUtil.safeHook("drawBase", () -> XposedHelpers.findAndHookMethod(CLS_CANDIDATE, cl, "drawBase", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            ModuleState.setCandidateView(param.thisObject);
                            drawModuleButtons((View) param.thisObject, (Canvas) param.args[0]);
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "drawBase after error: " + t);
                        }
                    }
                }));
    }

    private static void drawModuleButtons(View view, Canvas canvas) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Paint paint = modulePaint(view);
        boolean selecting = isSelecting(view);

        if (!selecting) {
            // ---- 非整理态：「搜索/全部」画在「整理」(getmSelectingBtnRect) 左侧 ----
            // 筛选生效时按钮变「全部」，点击清除筛选恢复全量
            Rect nativeBtn = nativeButtonRect(view, "getmSelectingBtnRect", null);
            if (nativeBtn == null) {
                return;
            }
            boolean filtering = ListFilterProxy.isFiltering();
            String label = filtering ? LABEL_ALL : LABEL_SEARCH;
            float btnW = textButtonWidth(paint, label);
            int left = (int) (nativeBtn.left - BTN_GAP_PX - btnW);
            if (overlapsNative(nativeBtn, left, btnW)) {
                sSearchRectValid = false; // 位置异常：宁可隐藏按钮也不与原生文字重叠
                return;
            }
            Rect hit = drawTextButton(view, canvas, paint, label, left, h, false);
            sSearchRect.set(hit);
            sSearchRectValid = true;
            sCommitAllRectValid = false; // 非整理态无此按钮
        } else {
            // ---- 整理态：「输入全部(N)」画在「删除」(mClearButtonWholeRect) 左侧 ----
            Rect deleteRect = nativeButtonRect(view, null, "mClearButtonWholeRect");
            if (deleteRect == null) {
                return;
            }
            int count = selectedCount();
            String label = count > 0 ? LABEL_COMMIT_ALL + "(" + count + ")" : LABEL_COMMIT_ALL;
            float btnW = textButtonWidth(paint, label);
            int right = (int) (deleteRect.left - BTN_GAP_PX);
            int left = (int) (right - btnW);
            // 防御：删除按钮锚定 mMarginRect.right、全选/取消锚定 mMarginRect.left，
            // 确认框弹出/关闭若触发 IME 布局变化，二者漂移不同步会导致模块按钮与原生文字重叠。
            // 重叠时跳过绘制并失效命中（按钮消失优先于文字重叠），并输出诊断日志定位根因。
            Rect allBtn = nativeButtonRect(view, null, "mAllBtnRect");
            Rect finishBtn = nativeButtonRect(view, null, "mFinishBtnRect");
            if (overlapsNative(allBtn, left, btnW) || overlapsNative(finishBtn, left, btnW)) {
                sCommitAllRectValid = false;
                return;
            }
            Rect hit = drawTextButton(view, canvas, paint, label, left, h, true);
            sCommitAllRect.set(hit);
            sCommitAllRectValid = true;
            sSearchRectValid = false;
        }
    }

    /**
     * 模块按钮与原生按钮矩形是否重叠（严格区间相交，不含间距容差）。
     * 正常布局模块按钮与原生按钮间距恰为 BTN_GAP_PX，若带容差会把正常间距误判为重叠。
     * 原生矩形为 null 时不视为重叠（保持原行为，不误伤）。
     */
    private static boolean overlapsNative(Rect nativeRect, int btnLeft, float btnWidth) {
        if (nativeRect == null) {
            return false;
        }
        int btnRight = (int) (btnLeft + btnWidth);
        return btnRight > nativeRect.left && btnLeft < nativeRect.right;
    }

    /** 复用原生画笔风格：反射取 mPaint；取不到则自建默认画笔 */
    private static Paint modulePaint(View view) {
        try {
            return (Paint) XposedHelpers.getObjectField(view, "mPaint");
        } catch (Throwable t) {
            Paint p = new Paint();
            p.setAntiAlias(true);
            return p;
        }
    }

    /** 是否整理态：candidateView.isSelecting()，反射失败视为非整理态 */
    private static boolean isSelecting(View view) {
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(view, "isSelecting"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 取原生按钮矩形：优先 getter 方法，其次实例字段；取不到返回 null */
    private static Rect nativeButtonRect(View view, String getter, String field) {
        try {
            return getter != null
                    ? (Rect) XposedHelpers.callMethod(view, getter)
                    : (Rect) XposedHelpers.getObjectField(view, field);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 模块按钮字号：取原生画笔字号；异常兜底 FALLBACK_TEXT_SIZE_PX（如画笔被篡改） */
    private static float buttonTextSize(Paint paint) {
        try {
            return paint.getTextSize();
        } catch (Throwable t) {
            return FALLBACK_TEXT_SIZE_PX;
        }
    }

    /** 模块按钮宽度 = 文字宽 + 相对字号内边距（与原生按钮观感一致） */
    private static float textButtonWidth(Paint paint, String label) {
        return paint.measureText(label) + buttonTextSize(paint) * BTN_PAD_FACTOR;
    }

    /**
     * 绘制一个模块文字按钮（纯文字，与原生按钮风格一致，不画背景），
     * 返回其命中矩形（自上而下铺满 view 高度）。
     */
    private static Rect drawTextButton(View view, Canvas canvas, Paint paint, String label,
                                       int left, int viewHeight, boolean selecting) {
        float pad = buttonTextSize(paint) * BTN_PAD_FACTOR;
        float width = textButtonWidth(paint, label);
        int right = (int) (left + width);
        float baseY = viewHeight / 2f - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(label, left + pad / 2f, baseY, moduleTextPaint(view, paint, selecting));
        return new Rect(left, 0, right, viewHeight);
    }

    /** 模块按钮画笔：颜色采自原生按钮当前色（已含主题/暗色适配），与原生观感一致 */
    private static Paint moduleTextPaint(View view, Paint base, boolean selecting) {
        Paint p = new Paint(base);
        p.setStyle(Paint.Style.FILL);
        int color = FALLBACK_BTN_COLOR; // 原生 TEXT_BTN_TEXT_COLOR（灰）
        try {
            // 原生颜色字段：非整理态「整理」= mSelectingBtnColor，整理态「全选」= mSelectingAllTextColor
            color = XposedHelpers.getIntField(view, selecting ? "mSelectingAllTextColor" : "mSelectingBtnColor");
        } catch (Throwable ignored) {
            // 反射取色失败（字段改名/版本差异）→ 保持 FALLBACK_BTN_COLOR，按钮仍可用
        }
        p.setColor(color);
        return p;
    }

    /* ================= 2. touchInButton 前置拦截模块按钮 ================= */
    private static void hookCandidateTouch(ClassLoader cl) {
        HookUtil.safeHook("touchInButton", () -> XposedHelpers.findAndHookMethod(CLS_CANDIDATE, cl, "touchInButton", float.class, float.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            View view = (View) param.thisObject;
                            boolean selecting = isSelecting(view);
                            int x = Math.round((Float) param.args[0]);
                            int y = Math.round((Float) param.args[1]);
                            if (!selecting && sSearchRectValid && sSearchRect.contains(x, y)) {
                                // 去抖：DOWN/UP 事件序列会连续两次命中，只执行第一次
                                if (debounceTouch(x, y)) {
                                    // 筛选生效中 → 清除筛选（「全部」）；否则进入搜索模式
                                    if (ListFilterProxy.isFiltering()) {
                                        SearchModeController.onClearFilter();
                                    } else {
                                        SearchModeController.onSearchClick();
                                    }
                                }
                                param.setResult(HIT_SUPPRESSED); // 屏蔽原生命中
                                return;
                            }
                            if (selecting && sCommitAllRectValid && sCommitAllRect.contains(x, y)) {
                                if (debounceTouch(x, y)) {
                                    onCommitAllClick();
                                }
                                param.setResult(HIT_SUPPRESSED);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "touchInButton error: " + t);
                        }
                    }
                }));
    }

    /* ================= 3. 标题数字 → 「当前显示数量/当前总数量」 =================
       原生 setClipboardCount(i,i2)：mCountText = String.format(getString(clipboard_count_text), i)
       格式串内写死了上限 150（如 "(%d/150)"）。此处 after 覆写 mCountText 为纯数字
       「当前显示数/当前总数」（见 refreshCountText），数字实时反映过滤结果 */
    private static void hookCandidateCount(ClassLoader cl) {
        HookUtil.safeHook("setClipboardCount", () -> XposedHelpers.findAndHookMethod(CLS_CANDIDATE, cl, "setClipboardCount", int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            refreshCountText(param.thisObject);
                            // 原生缺陷修复：删除后列表变空时 setClipboardCount 会因 mCount<=0
                            // 调用 setDrawTitle(true) 恢复标题/计数绘制，但不会退出整理态，
                            // 导致「标题+计数」与「全选」重叠。此处主动退出整理态。
                            int count = (Integer) param.args[0];
                            if (count <= 0 && isSelecting((View) param.thisObject)) {
                                XposedHelpers.callMethod(param.thisObject, "setSelecting", false);
                                XposedBridge.log(HookUtil.LOG_TAG + "auto exit selecting: list empty after delete");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "setClipboardCount error: " + t);
                        }
                    }
                }));
    }

    /* ================= 4. 取消按钮强制退出整理态 =================
       原生退出链路「onMenuClick(13) → setDrawTitle(true) + a(2) → Page.L(2) →
       f.l() → keyboard.o() + n(v())」中，n(v()) 依赖 adapter.h()（勾选状态字段 o）：
       删除后勾选集合异步刷新期间 h() 仍为 true → v()=true → n(true) 保持整理态，
       导致「点取消无反应」且标题已恢复（setDrawTitle(true)）与「全选」文字重叠。
       取消按钮语义明确为退出整理态，此处 after 检测原生退出失败时强制退出。 */
    private static void hookExitSelecting(ClassLoader cl) {
        HookUtil.safeHook("onMenuClick", () -> XposedHelpers.findAndHookMethod(CLS_CANDIDATE, cl, "onMenuClick", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int id = (Integer) param.args[0];
                            if (id != 13) { // 13 = 取消按钮
                                return;
                            }
                            Object cv = param.thisObject;
                            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cv, "isSelecting"))) {
                                XposedHelpers.callMethod(cv, "setSelecting", false);
                                XposedBridge.log(HookUtil.LOG_TAG + "cancel: native exit missed, forced setSelecting(false)");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "onMenuClick after err: " + t);
                        }
                    }
                }));
    }

    /* ================= 模块动作 ================= */

    /**
     * 触摸去抖：同一点（±TOUCH_DEBOUNCE_DIST_PX）TOUCH_DEBOUNCE_MS 内的重复事件
     * （DOWN/UP 序列）返回 false，只允许第一次命中执行动作，后续事件仅屏蔽不执行。
     */
    private static boolean debounceTouch(float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastTouchTime < TOUCH_DEBOUNCE_MS
                && Math.abs(x - sLastTouchX) < TOUCH_DEBOUNCE_DIST_PX
                && Math.abs(y - sLastTouchY) < TOUCH_DEBOUNCE_DIST_PX) {
            return false;
        }
        sLastTouchTime = now;
        sLastTouchX = x;
        sLastTouchY = y;
        return true;
    }

    /** 输入全部：勾选内容逐条上屏，保持整理态 */
    private static void onCommitAllClick() {
        try {
            List<?> selected = checkedList();
            if (selected == null || selected.isEmpty()) {
                return;
            }
            Object kb = ModuleState.keyboard();
            if (kb == null) {
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
            XposedBridge.log(HookUtil.LOG_TAG + "committed " + n + " items, stay in manage mode");
            // 保持整理态：不退出；按钮计数由下一次 drawBase 自动刷新
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "commitAll error: " + t);
        }
    }

    /* ================= 原生 adapter 勾选状态读取（KeyboardListHooks 删除保护复用） ================= */

    /** 当前勾选数：keyboard.adapter(N=b) → g() 勾选列表 size */
    static int selectedCount() {
        List<?> checked = checkedList();
        return checked == null ? 0 : checked.size();
    }

    /** 当前勾选列表：keyboard.adapter(N=b) → g()；任一步失败返回 null */
    static List<?> checkedList() {
        try {
            Object adapter = clipboardAdapter();
            if (adapter == null) {
                return null;
            }
            Object list = XposedHelpers.callMethod(adapter, "g");
            return list instanceof List ? (List<?>) list : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 剪贴板适配器实例：keyboard 字段 N（b 类）；无 keyboard 返回 null */
    static Object clipboardAdapter() {
        try {
            Object kb = ModuleState.keyboard();
            return kb == null ? null : XposedHelpers.getObjectField(kb, "N");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 将标题旁数字刷新为「当前显示数量/当前总数量」（KeyboardListHooks.swapList 末尾复用） */
    static void refreshCountText(Object candidateView) {
        if (candidateView == null) {
            candidateView = ModuleState.candidateView();
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
}