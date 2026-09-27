package com.clipboard.enhance;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 条目编辑领域：长按弹窗加「编辑」行 + 编辑框 + 仓库合并写。
 *
 * 逆向事实（com.sohu.inputmethod.clipboard.*，混淆名）：
 * - 长按链：adapter {@code b.getView} → 长按监听 → {@code m.b(pos)} →
 *   {@code ClipboardKeyboard.b(int)} 弹菜单（三行：删除 / 转快捷 / 分词，
 *   {@code f.onClick} 按 id 分支）；菜单窗口经包装类 {@code popupwindow.c#M} 展示。
 * - 白卡 {@code li_all}（{@code RoundRelativeLayout}，定高）只做圆角蒙皮，
 *   无自定义测量；三行定高来自 {@code setHeight(i10)}，{@code i10=(i6-C)/3}。
 * - 实体 {@code c}：{@code d} 内容、{@code c} 时间、{@code e} 时间数组；
 *   表 {@code CLIPBOARD_ITEM} 对 {@code CONTENT} 建 UNIQUE。
 * - 写入语义照抄 {@code p.H}：按内容去重命中则刷新时间+合并时间数组，不命中则原地改；
 *   刷新走 {@code ViewModel.j()}（重查→上报→{@code C()}→{@code swapList} 自动生效，
 *   孤儿计数由既有对账清理）。
 *
 * 只 hook 展示方法 {@code M()} 的 before（签名稳定），{@code $f} 内部类不碰；
 * 任一步失败直接返回，原生三行完整，不影响其他功能。
 */
public final class ClipboardEditController {

    /** 已注入编辑行的标记（findViewWithTag 去重：弹窗 content 只 inflate 一次） */
    private static final String TAG_EDIT_ROW = "clipboard_enhance_edit_row";
    /** 宿主 5000 截断（见契约，与 p.G 读链一致） */
    static final int MAX_CONTENT_LEN = SogouHostContract.MAX_CONTENT_LEN;

    /** R.id 按名独立缓存（解析结果进程内确定，失败也缓存避免日志刷屏；0=不存在） */
    private static final Map<String, Integer> sRidCache = new HashMap<>();
    /** 跨 افتتاح记忆的行高（verify 实测回填；首 افتتاح公式失效时兜底） */
    private static volatile int sRowH = 0;

    private ClipboardEditController() {
    }

    /** 注册本领域 hook：弹窗展示方法 M() 的 before——show 之前注入，无事后补救 */
    public static void init(ClassLoader cl) {
        HookUtil.safeHook("popupEdit", () -> XposedHelpers.findAndHookMethod(
                SogouHostContract.CLS_POPUP, cl, "M",
                View.class, int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            onPopupBeforeShow(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "popup edit error: " + t);
                        }
                    }
                }));
    }

    /* ================= 1. 弹窗第四行注入（show 之前） =================
       新行挂到白卡所在父级（框架 RelativeLayout）与白卡做兄弟，BELOW 白卡 id；
       行高由宿主公式直解，宽／列／顶部由 show 后活体值一次对齐；
       白卡仅动两处像素级属性（下边距搬家、底圆角拉直），其余零写入。
       M() 亮相时即四行尺寸。任一步失败直接返回，原生三行完整。 */
    private static void onPopupBeforeShow(final Object popup) {
        Object content;
        try {
            content = XposedHelpers.callMethod(popup, "getContentView");
        } catch (Throwable ignored) {
            return;
        }
        if (!(content instanceof View)) {
            return;
        }
        final View contentView = (View) content;
        if (contentView.findViewWithTag(TAG_EDIT_ROW) != null) {
            return; // 已注入（窗口尺寸已是四行，无需重做）
        }
        int idDelete = resolveRid(SogouHostContract.RID_DELETE_WORDS);
        int idMove = resolveRid(SogouHostContract.RID_MOVE_SHORTCUT);
        int idSplit = resolveRid(SogouHostContract.RID_SPLIT_WORD);
        if (idDelete == 0 || idMove == 0 || idSplit == 0) {
            return;
        }
        View vDelete = contentView.findViewById(idDelete);
        View vMove = contentView.findViewById(idMove);
        View vSplit = contentView.findViewById(idSplit);
        if (vDelete == null && vMove == null && vSplit == null) {
            return; // 非剪贴板菜单（如其他 popup 共用包装类），放行
        }
        final Object kb = ModuleState.keyboard();
        if (kb == null) {
            XposedBridge.log(HookUtil.LOG_TAG + "edit skipped: no keyboard");
            return;
        }
        // li_all 定高卡先行确认（树取证：RoundRelativeLayout lpH=417，三行容器）：
        // 找不到或非定高则放弃，不猜、不撑错地方
        int idCard = resolveRid(SogouHostContract.RID_LI_ALL);
        final View card = idCard == 0 ? null : contentView.findViewById(idCard);
        ViewGroup.LayoutParams cardLp = card == null ? null : card.getLayoutParams();
        if (cardLp == null || cardLp.height <= 0) {
            XposedBridge.log(HookUtil.LOG_TAG + "edit skipped: no fixed card");
            return;
        }
        // 行高公式直解：宿主 b() 内 i10=(i6-C)/3，i6 即白卡定高，C 即 keyboard.C 维度；
        // 全静态值，无需测量（手动 measure 会冻结半成品几何，反为祸源，已删除）。
        final int rowH = computeRowH(kb, cardLp.height);
        if (rowH <= 0) {
            XposedBridge.log(HookUtil.LOG_TAG + "edit skipped: bad rowH");
            return;
        }
        // 选锚：首个可见行即可（仅取样式：字号/颜色/padding 三行一致，无需位置）
        View anchor = null;
        View[] views = {vDelete, vMove, vSplit};
        for (View v : views) {
            if (v instanceof TextView && v.getVisibility() == View.VISIBLE) {
                anchor = v;
                break;
            }
        }
        if (anchor == null) {
            XposedBridge.log(HookUtil.LOG_TAG + "inject aborted: no visible row");
            return;
        }
        final TextView anchorTv = (TextView) anchor;
        final TextView edit = new TextView(anchor.getContext());
        edit.setTag(TAG_EDIT_ROW);
        edit.setText("编辑");
        // 列对齐：文本 x＝行左沿＋padding，原生文本还含锚行自身 leftMargin，一并补上
        int anchorLM = 0;
        try {
            ViewGroup.LayoutParams alp0 = anchor.getLayoutParams();
            if (alp0 instanceof RelativeLayout.LayoutParams) {
                anchorLM = ((RelativeLayout.LayoutParams) alp0).leftMargin;
            }
        } catch (Throwable ignored) {
        }
        try {
            edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, anchorTv.getTextSize());
            edit.setTextColor(anchorTv.getTextColors());
            edit.setGravity(anchorTv.getGravity());
            edit.setBackground(buildFusedBackground(kb, card, anchorTv));
            // 文本 x ＝ 行左沿 ＋ 白卡内边距 ＋ 锚行自身边距 ＋ 锚行内边距：
            // 前三项都是活体实测，未知项为 0 即无害
            int cardPadL = 0;
            try {
                cardPadL = card.getPaddingLeft();
            } catch (Throwable ignored) {
            }
            edit.setPadding(anchor.getPaddingLeft() + anchorLM + cardPadL,
                    anchor.getPaddingTop(),
                    anchor.getPaddingRight(), anchor.getPaddingBottom());
            edit.setHeight(rowH); // 与原生三行一致
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "edit style failed, row may mismatch: " + t);
        }
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    XposedHelpers.callMethod(kb, "r"); // 收起菜单（私有 dismiss 封装）
                } catch (Throwable ignored) {
                }
                try {
                    Object handler = XposedHelpers.getObjectField(
                            kb, SogouHostContract.FIELD_MENU_HANDLER);
                    int idx = XposedHelpers.getIntField(
                            handler, SogouHostContract.FIELD_MENU_INDEX);
                    Object listObj = XposedHelpers.getObjectField(
                            kb, SogouHostContract.FIELD_KEYBOARD_LIST);
                    if (listObj instanceof List) {
                        List<?> items = (List<?>) listObj;
                        if (idx >= 0 && idx < items.size()) {
                            openEdit(kb, items.get(idx));
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(HookUtil.LOG_TAG + "edit entry error: " + t);
                }
            }
        });
        try {
            // 新行挂到白卡所在父级（框架行为可预期），与白卡做兄弟。
            if (!(card.getParent() instanceof RelativeLayout)) {
                XposedBridge.log(HookUtil.LOG_TAG
                        + "edit skipped: card parent unsupported");
                return;
            }
            // 卡底圆角拉直：白卡底部两圆角在 junction 处形成透明缺口（所谓“圆角割裂”），
            // 把底部两角置零（顶部保留原值）， junction 即直线对直线。仅改本实例，
            // 日志留证；失败则退化为圆角原样，不影响功能。
            squareCardBottomCorners(card);
            RelativeLayout parent = (RelativeLayout) card.getParent();
            edit.setId(View.generateViewId());
            // 与白卡严格对齐：宽取卡定宽，左沿＝父 padding＋卡 margin
            // （卡的可视缩进是两者之和，只抄锚行 margin 必错位）。
            // 高仍用实测 rowH（唯一可信的数）。
            ViewGroup.LayoutParams cardLpRef = card.getLayoutParams();
            int w = (cardLpRef != null && cardLpRef.width > 0)
                    ? cardLpRef.width : ViewGroup.LayoutParams.MATCH_PARENT;
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(w, rowH);
            int padL = 0;
            int padR = 0;
            try {
                padL = parent.getPaddingLeft();
                padR = parent.getPaddingRight();
            } catch (Throwable ignored) {
            }
            if (cardLpRef instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams cardLpR =
                        (RelativeLayout.LayoutParams) cardLpRef;
                int cardBottomMargin = cardLpR.bottomMargin;
                // 顶部初值 0（BELOW 贴卡底）；真正的 junction 对齐由 show 后的
                // syncJunctionTop 按“末行底”活体坐标一次到位
                lp.setMargins(padL + cardLpR.leftMargin, 0,
                        padR + cardLpR.rightMargin, cardBottomMargin);
                if (cardBottomMargin != 0) {
                    // 行间空隙即卡片下边距：移到本行行底（总量不变），顶部贴合卡底
                    cardLpR.bottomMargin = 0;
                    card.setLayoutParams(cardLpR);
                }
            } else {
                lp.setMargins(padL, 0, padR, 0);
            }
            lp.addRule(RelativeLayout.BELOW, idCard);
            parent.addView(edit, lp);
            android.widget.PopupWindow pw = pickPopupWindow(popup);
            if (pw != null) {
                try {
                    int curH = pw.getHeight();
                    if (curH > 0) {
                        pw.setHeight(curH + rowH); // show 前置高，无需 update()
                    }
                } catch (Throwable ignored) {
                }
            } else {
                XposedBridge.log(HookUtil.LOG_TAG + "popup preset skipped: no instance");
            }
            scheduleVerify(contentView, edit);
            XposedBridge.log(HookUtil.LOG_TAG + "edit row injected rowH=" + rowH);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "edit row addView failed: " + t);
        }
    }

    /**
     * 白卡底部两圆角拉直（junction 融合用）：读统一圆角值，置顶部两角保留、
     * 底部两角归零，经 calculateRadiiAndRectF 重算。仅改本弹窗实例，
     * 任一步失败即放弃（圆角原样，功能不受影响）。
     */
    private static void squareCardBottomCorners(View card) {
        try {
            int uniform = XposedHelpers.getIntField(card, "cornerRadius");
            XposedHelpers.setIntField(card, "cornerRadius", 0);
            XposedHelpers.setIntField(card, "cornerTopLeftRadius", uniform);
            XposedHelpers.setIntField(card, "cornerTopRightRadius", uniform);
            XposedHelpers.setIntField(card, "cornerBottomLeftRadius", 0);
            XposedHelpers.setIntField(card, "cornerBottomRightRadius", 0);
            XposedHelpers.callMethod(card, "calculateRadiiAndRectF", false);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "square corners failed: " + t);
        }
    }

    /**
     * 融合背景：白卡色打底（与卡片连为一体），底部同圆角，顶部直角贴住卡底。
     */
    private static android.graphics.drawable.Drawable buildFusedBackground(
            Object kb, View card, TextView anchorTv) {
        int cardColor = 0xFFFFFFFF;
        try {
            cardColor = XposedHelpers.getIntField(kb, "o"); // 白卡色（随夜间主题）
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "card color fallback: " + t);
        }
        float radius = 16f;
        try {
            // 优先白卡实测半径（公开 getSrcRadii，精确像素，杜绝 dp/px 单位猜测）
            Object radii = XposedHelpers.callMethod(card, "getSrcRadii");
            if (radii instanceof float[]) {
                float[] arr = (float[]) radii;
                if (arr.length >= 8) {
                    radius = Math.max(arr[4], arr[6]);
                }
            }
        } catch (Throwable ignored) {
        }
        android.graphics.drawable.GradientDrawable base =
                new android.graphics.drawable.GradientDrawable();
        // 卡色强制不透明（隔离透明色猜测；随夜间主题取自白卡字段）
        base.setColor(cardColor | 0xFF000000);
        base.setCornerRadii(new float[]{0, 0, 0, 0, radius, radius, radius, radius});
        return base;
    }

    /**
     * 文本列对齐（show 后执行一次）：原生文本 x＝卡左＋锚左＋锚内边距
     * （三者必须同一坐标系：锚左相对白卡，需叠加卡左换算到容器坐标；
     * 之前漏叠卡左，反把对齐改坏）本行 paddingLeft 直接补到同一 x。
     */
    private static boolean syncTextX(View contentView, View edit) {
        try {
            int idDelete = resolveRid(SogouHostContract.RID_DELETE_WORDS);
            int idMove = resolveRid(SogouHostContract.RID_MOVE_SHORTCUT);
            int idSplit = resolveRid(SogouHostContract.RID_SPLIT_WORD);
            View anchor = null;
            for (int id : new int[]{idDelete, idMove, idSplit}) {
                if (id == 0) {
                    continue;
                }
                View v = contentView.findViewById(id);
                if (v instanceof TextView && v.getVisibility() == View.VISIBLE
                        && v.getWidth() > 0) {
                    anchor = v;
                    break;
                }
            }
            int idCard = resolveRid(SogouHostContract.RID_LI_ALL);
            View card = idCard == 0 ? null : contentView.findViewById(idCard);
            if (anchor == null || card == null || !(edit instanceof TextView)) {
                return false;
            }
            int nativeX = card.getLeft() + anchor.getLeft()
                    + ((TextView) anchor).getPaddingLeft();
            int ourX = edit.getLeft() + ((TextView) edit).getPaddingLeft();
            int delta = nativeX - ourX;
            if (delta == 0) {
                return false;
            }
            TextView tv = (TextView) edit;
            int next = tv.getPaddingLeft() + delta;
            if (next < 0) {
                return false;
            }
            tv.setPadding(next, tv.getPaddingTop(),
                    tv.getPaddingRight(), tv.getPaddingBottom());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "textX sync failed: " + t);
            return false;
        }
    }

    /**
     * junction 对齐（show 后执行一次）：本行顶必须＝末可见行底（活体值），
     * 而不是卡底——卡片在末行之下还有约 35px bottom inset，
     * 锚定卡底等于把这段死白也继承下来，这正是“缝偏宽”的根源。
     * 本行盖住的只是卡片空白内边距（同为白色），零原生像素损失。
     * 返回是否发生改动。
     */
    private static boolean syncJunctionTop(View contentView, View edit) {
        try {
            int idDelete = resolveRid(SogouHostContract.RID_DELETE_WORDS);
            int idMove = resolveRid(SogouHostContract.RID_MOVE_SHORTCUT);
            int idSplit = resolveRid(SogouHostContract.RID_SPLIT_WORD);
            View lastRow = null;
            int maxBottom = -1;
            for (int id : new int[]{idDelete, idMove, idSplit}) {
                if (id == 0) {
                    continue;
                }
                View v = contentView.findViewById(id);
                if (!(v instanceof TextView) || v.getVisibility() != View.VISIBLE) {
                    continue;
                }
                if (v.getBottom() > maxBottom && v.getHeight() > 0) {
                    maxBottom = v.getBottom();
                    lastRow = v;
                }
            }
            int idCard = resolveRid(SogouHostContract.RID_LI_ALL);
            View card = idCard == 0 ? null : contentView.findViewById(idCard);
            if (lastRow == null || card == null) {
                return false;
            }
            ViewGroup.LayoutParams elp = edit.getLayoutParams();
            if (!(elp instanceof RelativeLayout.LayoutParams)) {
                return false;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) elp;
            int wantTop = card.getTop() + lastRow.getBottom();
            int ourTop = edit.getTop();
            int delta = wantTop - ourTop;
            if (delta == 0) {
                return false;
            }
            lp.topMargin += delta;
            edit.setLayoutParams(lp);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "junction sync failed: " + t);
            return false;
        }
    }

    /**
     * 活体几何对齐（show 后执行一次）：show 前所有宽度都是手动测量的假数，
     * 唯有此时卡片位置尺寸为真。把本行左沿/宽与白卡锁死一致，
     * 与 show 前的初值无关。返回是否发生改动。
     */
    private static boolean syncRowGeometry(View contentView, View edit) {
        try {
            int idCard = resolveRid(SogouHostContract.RID_LI_ALL);
            View card = idCard == 0 ? null : contentView.findViewById(idCard);
            if (card == null || card.getMeasuredWidth() <= 0) {
                return false;
            }
            ViewGroup.LayoutParams elp = edit.getLayoutParams();
            if (!(elp instanceof RelativeLayout.LayoutParams)) {
                return false;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) elp;
            boolean changed = false;
            if (lp.width != card.getMeasuredWidth()) {
                lp.width = card.getMeasuredWidth();
                changed = true;
            }
            if (lp.leftMargin != card.getLeft()) {
                lp.leftMargin = card.getLeft();
                changed = true;
            }
            if (lp.rightMargin != 0) {
                lp.rightMargin = 0;
                changed = true;
            }
            if (changed) {
                edit.setLayoutParams(lp);
            }
            return changed;
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "row sync failed: " + t);
            return false;
        }
    }

    /**
     * 验后确认（单次）：show 后首帧用活体值做三同步（宽／列／junction），
     * 随后检查行高回填记忆；完全不可见则摘除自愈。诊断期逐轮追排已证实
     * 无增益，不再盲重试。
     */
    private static void scheduleVerify(final View contentView, final View edit) {
        contentView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (edit.getParent() == null) {
                        return;
                    }
                    // 活体三同步（setLayoutParams/setPadding 自带重排）
                    syncRowGeometry(contentView, edit);
                    syncTextX(contentView, edit);
                    syncJunctionTop(contentView, edit);
                    if (!edit.isShown() || edit.getHeight() <= 0) {
                        ViewGroup p = (ViewGroup) edit.getParent();
                        if (p != null) {
                            p.removeView(edit);
                        }
                        XposedBridge.log(HookUtil.LOG_TAG + "edit row self-healed (removed)");
                        return;
                    }
                    int h = edit.getHeight();
                    if (h >= 20 && h <= 1000) {
                        sRowH = h; // 回填跨 افتتاح记忆：公式失效时兜底
                    }
                } catch (Throwable t) {
                    XposedBridge.log(HookUtil.LOG_TAG + "verify error: " + t);
                }
            }
        });
    }

    /** 收集底层 PopupWindow（自身继承＋组合字段全扫描，不只取第一个） */
    private static java.util.List<android.widget.PopupWindow> collectPopupWindows(Object popup) {
        java.util.List<android.widget.PopupWindow> out = new java.util.ArrayList<>();
        try {
            if (popup instanceof android.widget.PopupWindow) {
                out.add((android.widget.PopupWindow) popup);
            }
            for (Class<?> k = popup.getClass();
                 k != null && k != Object.class;
                 k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    try {
                        if (!android.widget.PopupWindow.class.isAssignableFrom(f.getType())) {
                            continue;
                        }
                        f.setAccessible(true);
                        Object v = f.get(popup);
                        if (v instanceof android.widget.PopupWindow && !out.contains(v)) {
                            out.add((android.widget.PopupWindow) v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "collect popup failed: " + t);
        }
        return out;
    }

    /** 选取底层 PopupWindow：优先正 showing 的，否则取第一个；供 show 前置高 */
    private static android.widget.PopupWindow pickPopupWindow(Object popup) {
        try {
            java.util.List<android.widget.PopupWindow> all = collectPopupWindows(popup);
            if (all.isEmpty()) {
                return null;
            }
            android.widget.PopupWindow first = all.get(0);
            for (android.widget.PopupWindow c : all) {
                try {
                    if (c.isShowing()) {
                        return c;
                    }
                } catch (Throwable ignored) {
                }
            }
            return first;
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "pick popup failed: " + t);
            return null;
        }
    }
    /**
     * 行高公式直解：宿主 b() 内 i10=(i6-C)/3——i6 即白卡定高（静态 LP），
     * C 即 keyboard.C 维度字段（反射直读）。全静态，无需测量。
     * 降级链：公式（ sanity 20..500 ）→ 跨 افتتاح记忆 sRowH → 卡高/3 近似。
     *
     * @return 行高 px，<=0 表示不可用（调用方放弃注入）
     */
    static int computeRowH(Object kb, int cardH) {
        if (cardH > 0) {
            try {
                int c = XposedHelpers.getIntField(kb, "C");
                int rowH = formulaRowH(cardH, c);
                if (rowH > 0) {
                    return rowH;
                }
                XposedBridge.log(HookUtil.LOG_TAG + "rowH formula out of range: cardH="
                        + cardH + " C=" + c);
            } catch (Throwable t) {
                XposedBridge.log(HookUtil.LOG_TAG + "rowH formula failed: " + t);
            }
        }
        int cached = sRowH;
        if (cached > 0) {
            XposedBridge.log(HookUtil.LOG_TAG + "rowH cached: " + cached);
            return cached;
        }
        if (cardH > 0) {
            int approx = Math.round(cardH / 3f);
            XposedBridge.log(HookUtil.LOG_TAG + "rowH approx: " + approx);
            return approx;
        }
        return -1;
    }

    /** 纯公式（单测覆盖，无 Xposed/宿主依赖）：i10=(i6-C)/3，越界返回 -1 */
    static int formulaRowH(int cardH, int c) {
        if (cardH <= 0) {
            return -1;
        }
        int rowH = (cardH - c) / 3;
        return (rowH >= 20 && rowH <= 500) ? rowH : -1;
    }

    /** R.id 按名解析（结果进程内确定，失败也缓存避免日志刷屏；0=不存在） */
    private static int resolveRid(String name) {
        synchronized (sRidCache) {
            Integer cached = sRidCache.get(name);
            if (cached != null) {
                return cached;
            }
        }
        int id = 0;
        try {
            Class<?> rid = XposedHelpers.findClass(
                    SogouHostContract.CLS_R_ID, ModuleState.classLoader());
            id = XposedHelpers.getStaticIntField(rid, name);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "resolve R.id " + name + " failed: " + t);
        }
        synchronized (sRidCache) {
            sRidCache.put(name, id);
        }
        return id;
    }

    /* ================= 2. 编辑框 =================
       上下文取键盘根视图（g 字段 View）的 Context；IME 进程弹框需 OVERLAY 类型，
       无权限/失败则 toast 提示，不崩。 */
    static void openEdit(final Object kb, final Object item) {
        try {
            Object textObj = XposedHelpers.getObjectField(
                    item, SogouHostContract.FIELD_ITEM_TEXT);
            String old = textObj == null ? "" : String.valueOf(textObj);
            Context ctx = keyboardViewContext(kb);
            if (ctx == null) {
                toastOnMain(ctx, "无法打开编辑框");
                return;
            }
            final EditText et = new EditText(ctx);
            et.setText(old);
            try {
                et.setSelection(et.getText().length());
            } catch (Throwable ignored) {
            }
            et.setMinLines(3);
            et.setGravity(Gravity.TOP | Gravity.START);
            float density = ctx.getResources().getDisplayMetrics().density;
            int pad = (int) (16 * density);
            et.setPadding(pad, pad, pad, pad);
            AlertDialog dlg = new AlertDialog.Builder(ctx)
                    .setTitle("编辑剪贴板")
                    .setView(et)
                    .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            applyEditAsync(kb, item, et.getText().toString());
                        }
                    })
                    .setNegativeButton("取消", null)
                    .create();
            try {
                if (dlg.getWindow() != null) {
                    dlg.getWindow().setType(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
            } catch (Throwable ignored) {
            }
            dlg.show();
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "openEdit error: " + t);
        }
    }

    private static Context keyboardViewContext(Object kb) {
        try {
            if (kb != null) {
                Object root = XposedHelpers.getObjectField(kb, "g");
                if (root instanceof View) {
                    Context c = ((View) root).getContext();
                    if (c != null) {
                        return c;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return SogouSettingsInjector.globalContext();
    }

    /* ================= 3. 合并写（后台线程） =================
       唯一写入口：撞已有内容→删旧+bump既有（H 语义）；否则原地改→insertOrReplace。
       写完调 ViewModel.j() 走原生重查上报，swapList/计数对账自动跟上。 */
    private static void applyEditAsync(final Object kb, final Object item, final String input) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doApplyEdit(kb, item, input);
                } catch (Throwable t) {
                    XposedBridge.log(HookUtil.LOG_TAG + "applyEdit error: " + t);
                    toastOnMain(keyboardViewContext(kb), "保存失败");
                }
            }
        }, "clip-edit").start();
    }

    private static void doApplyEdit(Object kb, Object item, String input) {
        Context ctx = keyboardViewContext(kb);
        if (isBlankForEdit(input)) {
            toastOnMain(ctx, "内容不能为空");
            return;
        }
        String nn = normalizeForSave(input);
        String old;
        try {
            Object t = XposedHelpers.getObjectField(item, SogouHostContract.FIELD_ITEM_TEXT);
            old = t == null ? "" : String.valueOf(t);
        } catch (Throwable t) {
            toastOnMain(ctx, "读取条目失败");
            return;
        }
        if (nn.equals(old)) {
            toastOnMain(ctx, "无改动");
            return;
        }
        Object dao = ClipboardRepoAccessor.dao();
        if (dao == null) {
            toastOnMain(ctx, "保存失败：数据库不可用");
            return;
        }
        Object existing = findByContent(dao, nn, item);
        long now = System.currentTimeMillis();
        try {
            if (existing != null) {
                XposedHelpers.callMethod(dao, "delete", item);
                XposedHelpers.setLongField(existing, SogouHostContract.FIELD_ITEM_TIME, now);
                mergeTimeArray(existing, now);
                XposedHelpers.callMethod(dao, "insertOrReplace", existing);
            } else {
                XposedHelpers.setObjectField(item, SogouHostContract.FIELD_ITEM_TEXT, nn);
                XposedHelpers.setLongField(item, SogouHostContract.FIELD_ITEM_TIME, now);
                mergeTimeArray(item, now);
                XposedHelpers.callMethod(dao, "insertOrReplace", item);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "dao write failed: " + t);
            toastOnMain(ctx, "保存失败");
            return;
        }
        try {
            Object vm = XposedHelpers.getObjectField(kb, SogouHostContract.FIELD_VIEW_MODEL);
            XposedHelpers.callMethod(vm, "j"); // 原生重查上报：C()→swapList 自动刷新
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "refresh after edit failed: " + t);
        }
        toastOnMain(ctx, existing != null ? "已保存（与已有条目合并置顶）" : "已保存");
    }

    /** 全量扫找同内容条目（编辑低频操作，一次 list 可接受；避开 where 变参反射） */
    private static Object findByContent(Object dao, String content, Object self) {
        try {
            Object qb = XposedHelpers.callMethod(dao, "queryBuilder");
            Object listObj = XposedHelpers.callMethod(qb, "list");
            if (!(listObj instanceof List)) {
                return null;
            }
            for (Object o : (List<?>) listObj) {
                if (o == self) {
                    continue;
                }
                try {
                    Object t = XposedHelpers.getObjectField(
                            o, SogouHostContract.FIELD_ITEM_TEXT);
                    if (t != null && content.equals(String.valueOf(t))) {
                        return o;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "findByContent failed: " + t);
        }
        return null;
    }

    /** 时间数组合并：优先宿主 VpaClipboardManager.addTimeArray，失败则直接追加 */
    private static void mergeTimeArray(Object item, long now) {
        try {
            Object e = XposedHelpers.getObjectField(item, "e");
            List<Long> arr;
            if (e instanceof List) {
                @SuppressWarnings("unchecked")
                List<Long> cast = (List<Long>) e;
                arr = cast;
            } else {
                arr = new ArrayList<>();
                XposedHelpers.setObjectField(item, "e", arr);
            }
            try {
                Class<?> vpa = XposedHelpers.findClass(
                        SogouHostContract.CLS_VPA_MANAGER, ModuleState.classLoader());
                XposedHelpers.callStaticMethod(
                        vpa, SogouHostContract.METHOD_ADD_TIME_ARRAY, arr, now);
            } catch (Throwable t2) {
                arr.add(now);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "mergeTimeArray failed: " + t);
        }
    }

    /* ================= 4. 纯逻辑（单测覆盖，无 Xposed/宿主依赖） ================= */

    /** 空判断：null/空白视为不可保存 */
    static boolean isBlankForEdit(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** 规范化：超长按宿主 5000 截断 */
    static String normalizeForSave(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() > MAX_CONTENT_LEN) {
            return s.substring(0, MAX_CONTENT_LEN);
        }
        return s;
    }

    private static void toastOnMain(final Context ctx, final String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context c = ctx != null ? ctx : SogouSettingsInjector.globalContext();
                        if (c != null) {
                            Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
