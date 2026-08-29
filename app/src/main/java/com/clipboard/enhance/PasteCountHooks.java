package com.clipboard.enhance;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 列表项粘贴次数绘制：hook adapter b.getView，将每条内容的粘贴次数以蓝色绘制在
 * 文本 TextView（ViewHolder.c = tv_clipboard_item）的右上角 overlay；仅当该条被粘贴过
 * （次数 > 0）时绘制。
 *
 * 逆向事实（com.sohu.inputmethod.clipboard，混淆名）：
 * - adapter b.getView(int, View, ViewGroup) 返回 item view，view.getTag() = b.e（ViewHolder）
 *   ViewHolder 字段：a = item 容器、b = divider、c = TextView(tv_clipboard_item)、d = 勾选 ImageView
 * - 显示列表为 adapter 字段 j（List<c>），position i 对应 j.get(i).d（content）
 * - 给文本 TextView 添加一次性 ViewOverlay Drawable：绘制时按内容查 PasteCounter 实时取次数，
 *   复用 convertView 时只更新内容附加字段，overlay 不重复挂载
 */
public final class PasteCountHooks {

    /* ================= 混淆类名（字符串引用，防混淆） ================= */
    private static final String CLS_ADAPTER = "com.sohu.inputmethod.clipboard.b";

    /** 蓝色（用户指定，区别于原生灰按钮/勾选态） */
    private static final int COLOR_COUNT = 0xFF2196F3;
    /** 次数文字字号（sp）与右上角内边距（dp） */
    private static final float TEXT_SIZE_SP = 11f;
    private static final float PAD_DP = 6f;

    private PasteCountHooks() {
    }

    /** 注册本领域全部 hook */
    public static void init(ClassLoader cl) {
        hookGetView(cl);
    }

    /* ================= getView 后置：挂载次数 overlay =================
       每次绑定（含 convertView 复用）都把当前 position 对应内容写入文本 TextView
       的附加字段 pcContent；首次挂载一次性 overlay（pcOverlay 标记防重复）。 */
    private static void hookGetView(ClassLoader cl) {
        HookUtil.safeHook("b.getView", () -> XposedHelpers.findAndHookMethod(CLS_ADAPTER, cl, "getView",
                int.class, View.class, ViewGroup.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            View itemView = (View) param.getResult();
                            if (itemView == null) {
                                return;
                            }
                            int pos = (Integer) param.args[0];
                            Object adapter = param.thisObject;
                            Object j = XposedHelpers.getObjectField(adapter, "j");
                            String content = null;
                            if (j instanceof java.util.List) {
                                java.util.List<?> list = (java.util.List<?>) j;
                                if (pos >= 0 && pos < list.size()) {
                                    Object item = list.get(pos);
                                    if (item != null) {
                                        Object d = XposedHelpers.getObjectField(item, "d");
                                        content = d == null ? null : String.valueOf(d);
                                    }
                                }
                            }
                            // ViewHolder（b.e）的 c 字段 = 文本 TextView
                            Object tag = itemView.getTag();
                            Object tv = tag == null ? null : XposedHelpers.getObjectField(tag, "c");
                            if (!(tv instanceof View)) {
                                return;
                            }
                            View textview = (View) tv;
                            XposedHelpers.setAdditionalInstanceField(textview, "pcContent", content);
                            if (XposedHelpers.getAdditionalInstanceField(textview, "pcOverlay") == null) {
                                Object overlay = XposedHelpers.callMethod(textview, "getOverlay");
                                if (overlay != null) {
                                    XposedHelpers.callMethod(overlay, "add", new PasteCountDrawable(textview));
                                    XposedHelpers.setAdditionalInstanceField(textview, "pcOverlay", Boolean.TRUE);
                                    XposedBridge.log(HookUtil.LOG_TAG + "paste-count overlay attached");
                                } else {
                                    XposedBridge.log(HookUtil.LOG_TAG + "paste-count overlay null (tv=" + tv + ")");
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "getView paste-count error: " + t);
                        }
                    }
                }));
    }

    /** 文本 TextView 右上角蓝色次数 overlay */
    private static final class PasteCountDrawable extends Drawable {
        private final View mText;
        private final Paint mPaint;
        private final float mPad;

        PasteCountDrawable(View text) {
            mText = text;
            mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaint.setColor(COLOR_COUNT);
            float density = text.getResources().getDisplayMetrics().density;
            mPaint.setTextSize(TEXT_SIZE_SP * density);
            mPad = PAD_DP * density;
        }

        @Override
        public void draw(Canvas canvas) {
            Object content = XposedHelpers.getAdditionalInstanceField(mText, "pcContent");
            if (content == null) {
                return;
            }
            int cnt = PasteCounter.getCount(String.valueOf(content));
            if (cnt <= 0) {
                return;
            }
            // 不依赖 drawable.getBounds()：ViewOverlay 在 add 时若 TextView 尚未布局会把
            // bounds 锁成 (0,0,0,0) 且不再更新，导致文字被算到负坐标而不可见。
            // 改读 TextView 实时尺寸，随每次重绘取最新宽高。
            int vw = mText.getWidth();
            int vh = mText.getHeight();
            if (vw <= 0 || vh <= 0) {
                return; // 尚未布局，下次重绘再画
            }
            String s = cnt + "次";
            float w = mPaint.measureText(s);
            float x = vw - w - mPad;
            float y = mPad + mPaint.getTextSize();
            canvas.drawText(s, x, y, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            mPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
