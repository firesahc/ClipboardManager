package com.clipboard.enhance;

import android.view.MotionEvent;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 主列表与输入全部之外的整条剪贴板上屏口计数补齐。
 *
 * <p>职责边界：只补 {@link KeyboardListHooks#hookKeyboardItem} 与
 * {@code CandidateViewHooks#onCommitAllClick} 未覆盖的整条上屏口，不碰分词子串、
 * 快捷短语、手柄等非 {@code c.d} 整条来源。只读宿主瞬时字段，不持有列表状态；
 * 写入唯一经 {@link PasteCounter}，刷新复用 {@link KeyboardListHooks#swapList()}。
 *
 * <p>逆向事实：
 * <ul>
 *   <li>复制后首候选：{@code x:onPrimaryClipChanged → q:m → q:l →
 *       ClipboardFirstCandidateView:k5(d,true) 展示}，点击
 *       {@code ClipboardFirstCandidateView:e3(MotionEvent)} 经 {@code s.b(0,m1,...)}
 *       上屏，不走 {@code ClipboardKeyboard.a(int)}。{@code m1} 为全量内容，
 *       {@code n1} 为 64 字截断显示，不可用于计数。</li>
 *   <li>VPA 历史：{@code VpaClipboardHistoryScreen:a(int)} 直接
 *       {@code u.Z().A(b.get(i).d)}，与主列表同漏斗但不同类。</li>
 *   <li>分词 {@code ExplodeKeyboard:h → u.g(setComposingText)} 为子串，需产品另议，
 *       本类明确不计；快捷短语 {@code List<String>} 非剪贴板来源，明确不计。</li>
 * </ul>
 */
public final class QuickPasteHooks {

    /* ================= 混淆类名（字符串引用，防混淆） ================= */
    private static final String CLS_FIRST =
            "com.sohu.inputmethod.sogou.ClipboardFirstCandidateView";
    private static final String CLS_VPA_HISTORY =
            "com.sohu.inputmethod.clipboard.vpaclipboard.VpaClipboardHistoryScreen";

    private QuickPasteHooks() {
    }

    /** 注册本领域全部 hook（每个 hook 独立容错） */
    public static void init(ClassLoader cl) {
        hookFirstCandidate(cl);
        hookVpaHistory(cl);
    }

    /* ================= 1. 首候选快速粘贴 e3 =================
       e3 承载 DOWN/MOVE/UP 全手势，仅 ACTION_UP 真正经 s.b 上屏；
       DOWN/MOVE 必须过滤，否则多计。内容取全量 m1 字段。 */
    private static void hookFirstCandidate(ClassLoader cl) {
        HookUtil.safeHook("firstCandidate.e3", () -> XposedHelpers.findAndHookMethod(
                CLS_FIRST, cl, "e3", MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            MotionEvent ev = (MotionEvent) param.args[0];
                            if (ev == null || ev.getAction() != MotionEvent.ACTION_UP) {
                                return;
                            }
                            Object m1 = XposedHelpers.getObjectField(param.thisObject, "m1");
                            if (m1 == null) {
                                return;
                            }
                            String content = String.valueOf(m1);
                            if (content.length() == 0) {
                                return;
                            }
                            PasteCounter.increment(content);
                            XposedBridge.log(HookUtil.LOG_TAG
                                    + "paste increment(first): content.len=" + content.length()
                                    + " count=" + PasteCounter.getCount(content));
                            KeyboardListHooks.swapList();
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "firstCandidate count error: " + t);
                        }
                    }
                }));
    }

    /* ================= 2. VPA 历史列表粘贴 a(int) =================
       与 KeyboardListHooks.hookKeyboardItem 同 idiom：before 按索引快照对象，
       after 按对象取 d 字段计数，避免 after 时索引已因刷新失效。 */
    private static void hookVpaHistory(ClassLoader cl) {
        HookUtil.safeHook("vpaHistory.a", () -> XposedHelpers.findAndHookMethod(
                CLS_VPA_HISTORY, cl, "a", int.class,
                new XC_MethodHook() {
                    private volatile Object sLastCommitted;

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        sLastCommitted = null;
                        try {
                            Object listObj = XposedHelpers.getObjectField(param.thisObject, "b");
                            int index = (Integer) param.args[0];
                            if (listObj instanceof List) {
                                List<?> items = (List<?>) listObj;
                                if (index >= 0 && index < items.size()) {
                                    sLastCommitted = items.get(index);
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "vpa record error: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object committed = sLastCommitted;
                        sLastCommitted = null;
                        if (committed == null) {
                            return;
                        }
                        try {
                            Object text = XposedHelpers.getObjectField(committed, "d");
                            if (text == null) {
                                return;
                            }
                            String content = String.valueOf(text);
                            PasteCounter.increment(content);
                            XposedBridge.log(HookUtil.LOG_TAG
                                    + "paste increment(vpa): content.len=" + content.length()
                                    + " count=" + PasteCounter.getCount(content));
                            KeyboardListHooks.swapList();
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "vpa count error: " + t);
                        }
                    }
                }));
    }
}
