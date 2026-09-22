package com.clipboard.enhance;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 搜索态「完成」按钮（Route X）：画在正常输入候选条 IMEInputCandidateViewContainer 上。
 *
 * 设计动机：搜狗把「剪贴板面板顶栏 ClipboardCandidateView」与「主键盘输入区」做成互斥视图——
 * 面板展开时主键盘不显示，主键盘显示时顶栏被隐藏。故「完成」按钮必须放在输入界面（主键盘
 * 候选条）上才可见可点。点击搜索后面板收起回主键盘（输入界面），用户用任意面板（拼音/符号/
 * 数字/其他输入法）输入，commitText 经 SearchModeController.hookCommitBuffer 累积进缓冲区；
 * 点「完成」→ SearchModeController.onFinishSearch 应用关键词并退出搜索态、重新打开剪贴板页。
 *
 * 逆向事实：IMEInputCandidateViewContainer 是输入候选条的根 ViewGroup
 * （com.sohu.inputmethod.main.view），dispatchDraw 绘制候选子 View 与原生按钮；
 * dispatchTouchEvent 处理触摸。此处 afterHookedMethod 在候选条最右侧叠加「完成」文字按钮，
 * beforeHookedMethod 命中拦截，复用 SearchModeController.onFinishSearch。
 *
 * 位置策略（不打乱原生工具栏）：优先贴候选条最右；若候选子 View 右缘占满宽度，则左移到子
 * View 右缘右侧，避开候选内容。原生最右按钮（如「更多」）是否仍重叠取决于机型布局，需在
 * 真机验证；若重叠，调小 BTN_GAP_PX 或左移即可（见下方常量）。
 */
public final class InputCandidateHooks {

    private static final String CLS_CONTAINER = "com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer";
    /** 模块按钮与相邻内容的间距、文字内边距系数（相对字号） */
    private static final float BTN_GAP_PX = 8f;
    private static final float BTN_PAD_FACTOR = 0.9f;
    private static final float FALLBACK_TEXT_SIZE_PX = 14f;
    /** 兜底按钮色：原生 TEXT_BTN_TEXT_COLOR（灰），字段反射失败时使用 */
    private static final int FALLBACK_BTN_COLOR = 0xFF9F9B95;
    private static final String LABEL_DONE = "完成";

    /** 完成按钮矩形（随 dispatchDraw 每次更新，供 dispatchTouchEvent 命中） */
    private static final Rect sDoneRect = new Rect();
    private static volatile boolean sDoneRectValid = false;

    private InputCandidateHooks() {
    }

    /** 注册本领域全部 hook */
    public static void init(ClassLoader cl) {
        hookContainerDraw(cl);
        hookContainerTouch(cl);
    }

    /* ================= 1. 候选条最右侧叠加「完成」按钮 ================= */
    private static void hookContainerDraw(ClassLoader cl) {
        HookUtil.safeHook("IMEInputCandidateViewContainer.dispatchDraw", () -> XposedHelpers.findAndHookMethod(CLS_CONTAINER, cl, "dispatchDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!ModuleState.isSearchMode()) {
                                sDoneRectValid = false;
                                return;
                            }
                            View view = (View) param.thisObject;
                            Canvas canvas = (Canvas) param.args[0];
                            drawDoneButton(view, canvas);
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "inputDone draw error: " + t);
                        }
                    }
                }));
    }

    private static void drawDoneButton(View view, Canvas canvas) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            sDoneRectValid = false;
            return;
        }
        Paint paint = modulePaint(view);
        float btnW = textButtonWidth(paint, LABEL_DONE);
        int rightEdge = (int) (w - BTN_GAP_PX);
        int left = (int) (rightEdge - btnW);
        int right = rightEdge;
        // 不打乱原生工具栏：若候选子 View 右缘占满宽度，则左移到子 View 右缘右侧，避开候选内容
        int childMaxRight = maxChildRight(view);
        if (childMaxRight > 0 && left < childMaxRight + BTN_GAP_PX) {
            left = (int) (childMaxRight + BTN_GAP_PX);
            right = (int) (left + btnW);
        }
        // 超界兜底：保持在视图内
        if (right > w - BTN_GAP_PX) {
            right = (int) (w - BTN_GAP_PX);
            left = (int) (right - btnW);
        }
        float baseY = h / 2f - (paint.descent() + paint.ascent()) / 2f;
        float pad = buttonTextSize(paint) * BTN_PAD_FACTOR;
        canvas.drawText(LABEL_DONE, left + pad / 2f, baseY, paint);
        sDoneRect.set(left, 0, right, h);
        sDoneRectValid = true;
    }

    /* ================= 2. 候选条触摸命中「完成」 ================= */
    private static void hookContainerTouch(ClassLoader cl) {
        HookUtil.safeHook("IMEInputCandidateViewContainer.dispatchTouchEvent", () -> XposedHelpers.findAndHookMethod(CLS_CONTAINER, cl, "dispatchTouchEvent", MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!ModuleState.isSearchMode() || !sDoneRectValid) {
                                return;
                            }
                            MotionEvent ev = (MotionEvent) param.args[0];
                            int x = Math.round(ev.getX());
                            int y = Math.round(ev.getY());
                            if (sDoneRect.contains(x, y)) {
                                SearchModeController.onFinishSearch();
                                param.setResult(true); // 消费事件，屏蔽原生处理
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "inputDone touch error: " + t);
                        }
                    }
                }));
    }

    /* ================= 绘制辅助 ================= */

    /** 复用候选条画笔风格：反射取 mPaint；取不到则自建默认画笔（灰色、抗锯齿） */
    private static Paint modulePaint(View view) {
        try {
            Paint p = (Paint) XposedHelpers.getObjectField(view, "mPaint");
            if (p != null) {
                return p;
            }
        } catch (Throwable ignored) {
            // 反射取色/画笔失败（字段改名/版本差异）→ 保持 FALLBACK，按钮仍可用
        }
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setColor(FALLBACK_BTN_COLOR);
        return p;
    }

    private static float buttonTextSize(Paint paint) {
        try {
            return paint.getTextSize();
        } catch (Throwable t) {
            return FALLBACK_TEXT_SIZE_PX;
        }
    }

    private static float textButtonWidth(Paint paint, String label) {
        return paint.measureText(label) + buttonTextSize(paint) * BTN_PAD_FACTOR;
    }

    /** 候选条内最右可见子 View 的右缘（用于避开候选内容，避免重叠候选文字） */
    private static int maxChildRight(View view) {
        try {
            if (!(view instanceof ViewGroup)) {
                return 0;
            }
            ViewGroup vg = (ViewGroup) view;
            int max = 0;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c != null && c.getVisibility() == View.VISIBLE) {
                    max = Math.max(max, c.getRight());
                }
            }
            return max;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
