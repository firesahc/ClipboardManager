package com.clipboard.enhance;

/**
 * 宿主契约唯一出口：搜狗输入法混淆类名/字段名/方法名集中表。
 *
 * 目标：下次搜狗改版时一次回答“哪些Hook同命运”，不再逐类翻字符串。
 * 本类只放常量与版本注释，不放反射逻辑（反射仍在各领域Hook内）。
 *
 * 契约分组 ↔ 领域类：
 * - CLIPBOARD_* ↔ KeyboardListHooks / CandidateViewHooks / ClipboardLimitBypass
 * - INPUT_* ↔ SearchModeController（Route X缓冲拦截）
 * - CANDIDATE_CONTAINER ↔ InputCandidateHooks
 */
public final class SogouHostContract {

    private SogouHostContract() {
    }

    /* ================= 剪贴板包 ================= */
    public static final String CLIPBOARD_KEYBOARD = "com.sohu.inputmethod.clipboard.ClipboardKeyboard";
    public static final String CLIPBOARD_ADAPTER = "com.sohu.inputmethod.clipboard.b";
    public static final String CLIPBOARD_VIEW_MODEL = "com.sohu.inputmethod.clipboard.ClipboardViewModel";
    public static final String CLIPBOARD_REPO = "com.sohu.inputmethod.clipboard.p";
    public static final String CLIPBOARD_ITEM = "com.sohu.inputmethod.clipboard.c";
    public static final String CLIPBOARD_DB_A = "com.sohu.inputmethod.clipboard.db.a";
    public static final String CLIPBOARD_DAO_PROPS = "com.sohu.inputmethod.clipboard.db.ClipboardItemDao$Properties";
    public static final String CLIPBOARD_CANDIDATE_VIEW = "com.sohu.inputmethod.clipboard.ClipboardCandidateView";

    /** Keyboard数据源/adapter显示列表/条目文本字段 */
    public static final String FIELD_KEYBOARD_LIST = "M";
    public static final String FIELD_ADAPTER_LIST = "j";
    public static final String FIELD_ITEM_TEXT = "d";
    public static final String FIELD_ADAPTER = "N";

    /* ================= 输入链路（Route X） ================= */
    public static final String INPUT_LOGIC = "com.sohu.inputmethod.input.InputLogic";
    public static final String INPUT_IC_U = "com.sogou.bu.basic.ic.u";
    public static final String COMMIT_CLASS = "com.sohu.inputmethod.sogou.u";

    /* ================= 页面路由 ================= */
    public static final String PAGE_CLIPBOARD = "com.sohu.inputmethod.main.page.ClipboardPage";
    public static final String PAGE_BASE = "com.sohu.inputmethod.main.page.base.BaseSPage";
    public static final String IME_SOGOU = "com.sohu.inputmethod.sogou.SogouIME";
    public static final String CANDIDATE_CONTAINER = "com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer";

    /** 启动诊断：逐项探测类存在性，输出能力矩阵（成功≠行为兼容，失败=对应功能禁用） */
    public static String probe(ClassLoader cl) {
        String[] names = {
                CLIPBOARD_KEYBOARD, CLIPBOARD_ADAPTER, CLIPBOARD_VIEW_MODEL,
                CLIPBOARD_REPO, CLIPBOARD_CANDIDATE_VIEW,
                INPUT_LOGIC, INPUT_IC_U, PAGE_CLIPBOARD, CANDIDATE_CONTAINER,
        };
        String[] labels = {
                "Keyboard", "Adapter", "ViewModel",
                "Repo(p.H)", "CandidateView",
                "InputLogic", "ic.u", "ClipboardPage", "InputCandidateContainer",
        };
        StringBuilder sb = new StringBuilder("Host capabilities:");
        for (int i = 0; i < names.length; i++) {
            boolean ok = false;
            try {
                Class.forName(names[i], false, cl);
                ok = true;
            } catch (Throwable ignored) {
            }
            sb.append(' ').append(labels[i]).append(ok ? "=OK" : "=MISS");
        }
        return sb.toString();
    }
}
