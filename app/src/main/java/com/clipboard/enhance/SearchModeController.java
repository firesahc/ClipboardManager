package com.clipboard.enhance;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 搜索模式领域：模式状态动作、拼音候选拦截（pickSuggestion）、页面路由、IME 生命周期清理。
 *
 * 逆向事实：
 * - com.sohu.inputmethod.input.InputLogic.pickSuggestion(CharSequence) =
 *   拼音候选选词上屏入口（搜索模式拦截点）。注意：u.A(String)（sogou.u）
 *   只是剪贴板/快捷短语上屏落点，拦截它会误伤条目上屏 —— 剪贴板上屏路径
 *   ClipboardKeyboard.a(int) 与 pickSuggestion 完全分离，互不干扰。
 * - ClipboardPage：页面，M() 创建视图（afterHookedMethod 记录实例到 ModuleState）、
 *   w() 私有 = 收起面板回主键盘
 * - BaseSPage.F(String,Object) = 静态页面路由（reopenClipboardPage 复用）
 * - SogouIME.onCreate：IME 服务重建（关闭再打开）时清空筛选并恢复置顶开关持久化状态
 * - SogouIME.onFinishInputView(boolean)：输入会话结束（收起再展开）时清空筛选
 *
 * 搜索模式状态（sSearchMode）存于 ModuleState：KeyboardListHooks 点击条目时
 * 需要读取并复位，故不私有于本类。
 */
public final class SearchModeController {

    /* ================= 混淆类名（字符串引用，防混淆） ================= */
    private static final String CLS_INPUT_LOGIC = "com.sohu.inputmethod.input.InputLogic";
    private static final String CLS_PAGE = "com.sohu.inputmethod.main.page.ClipboardPage";
    private static final String CLS_PAGE_BASE = "com.sohu.inputmethod.main.page.base.BaseSPage";
    /**
     * 上屏落点实现类：getSogouInputConnection() 返回 com.sogou.bu.basic.ic.f 单例持有的
     * com.sogou.bu.basic.ic.u（implements ic.g）。符号/数字/其他输入法均经其 commitText
     * 上屏，是 Route X 搜索态缓冲拦截的统一入口（覆盖拼音 pickSuggestion 之外的所有面板）。
     */
    private static final String CLS_IC_U = "com.sogou.bu.basic.ic.u";

    private SearchModeController() {
    }

    /** 注册本领域全部 hook（注册顺序与拆分前一致） */
    public static void init(ClassLoader cl) {
        hookCommit(cl);
        hookCommitBuffer(cl);
        hookPageCreate(cl);
        hookImeRestart(cl);
        hookImeCollapse(cl);
    }

    /* ================= 1. 拼音候选选词（搜索关键词拦截已迁移至 commitText 缓冲，Route X） =================
       InputLogic.pickSuggestion(CharSequence) 原为拼音搜索关键词拦截点。Route X 改为统一
       拦截 InputConnection.commitText（覆盖拼音/符号/数字/其他输入法），见 hookCommitBuffer。
       本 hook 不再拦截候选（搜索态提交由缓冲 Hook 统一累积），保留注册仅为向后兼容。 */
    private static void hookCommit(ClassLoader cl) {
        HookUtil.safeHook("pickSuggestion", () -> XposedHelpers.findAndHookMethod(CLS_INPUT_LOGIC, cl, "pickSuggestion", CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // Route X：搜索态提交统一由 hookCommitBuffer 处理，此处放行不拦截
                    }
                }));
    }

    /* ================= 1b. 全面板提交缓冲拦截（搜索模式，Route X） =================
       符号面板/数字/其他输入法经 InputConnection.commitText 上屏，绕开了拼音 pickSuggestion
       拦截点；故 Hook 提交落点 com.sogou.bu.basic.ic.u 的 commitText(CharSequence,int) 与
       ic.g 接口同名方法 c(CharSequence,int)（二者最终都抵达真实 InputConnection）。
       beforeHookedMethod 中处于搜索态则把字符累积进缓冲区并 setResult(true) 吞掉上屏，
       使任意面板输入都能作为搜索关键词累积，等待「完成」按钮（CandidateViewHooks）应用。
       非搜索态完全放行（不影响正常上屏）。 */
    private static void hookCommitBuffer(ClassLoader cl) {
        XC_MethodHook bufferHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!ModuleState.isSearchMode()) {
                        return; // 正常输入放行
                    }
                    CharSequence cs = (CharSequence) param.args[0];
                    if (cs != null && cs.length() > 0) {
                        ModuleState.appendSearchBuffer(cs);
                    }
                    // 吞掉上屏：字符留在缓冲区，不进入编辑器
                    param.setResult(true);
                } catch (Throwable t) {
                    XposedBridge.log(HookUtil.LOG_TAG + "commitBuffer error: " + t);
                }
            }
        };
        HookUtil.safeHook("ic.u.commitText", () -> XposedHelpers.findAndHookMethod(CLS_IC_U, cl, "commitText", CharSequence.class, int.class, bufferHook));
        HookUtil.safeHook("ic.u.c", () -> XposedHelpers.findAndHookMethod(CLS_IC_U, cl, "c", CharSequence.class, int.class, bufferHook));
    }

    /* ================= 2. 页面实例记录 ================= */
    private static void hookPageCreate(ClassLoader cl) {
        HookUtil.safeHook("page.M", () -> XposedHelpers.findAndHookMethod(CLS_PAGE, cl, "M",
                android.view.LayoutInflater.class, android.view.ViewGroup.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ModuleState.setPage(param.thisObject);
                    }
                }));
    }

    /* ================= 3. IME 重启（关闭再打开）时清空筛选 =================
       筛选关键词是静态状态；输入法服务重建（SogouIME.onCreate）后旧筛选
       不应残留 —— 关闭再打开应恢复全量。hook 基类 SogouIME（xiaomi.SogouIME
       为空子类）的 onCreate，after 清空关键词并复位搜索模式。
       同时恢复置顶开关的持久化状态（进程冷启动后 SharedPreferences 值）。
       注意：搜索流程只切页面（reopenClipboardPage）不重建 IME，不受影响 */
    private static void hookImeRestart(ClassLoader cl) {
        HookUtil.safeHook("SogouIME.onCreate", () -> XposedHelpers.findAndHookMethod("com.sohu.inputmethod.sogou.SogouIME", cl, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            resetFilterState();
                            SogouSettingsInjector.restorePinRecentSetting();
                            XposedBridge.log(HookUtil.LOG_TAG + "IME recreated, filter cleared");
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "ime onCreate error: " + t);
                        }
                    }
                }));
    }

    /* ================= 3b. IME 收起（输入会话结束）时清空筛选 =================
       onCreate 只在服务重建时触发；「收起再展开」仅隐藏/显示输入窗口，
       走 InputMethodService 生命周期（onFinishInputView/onStartInputView），
       静态筛选状态会残留。onFinishInputView(boolean) 是收起/会话结束的
       可靠回调（返回键、点击输入框外部、切换应用均会触发），after 清空
       筛选，保证收起再展开后剪贴板恢复全量。
       注意：搜索流程只收起面板页（page.w()）不结束输入会话，不受影响。 */
    private static void hookImeCollapse(ClassLoader cl) {
        HookUtil.safeHook("SogouIME.onFinishInputView", () -> XposedHelpers.findAndHookMethod("com.sohu.inputmethod.sogou.SogouIME", cl, "onFinishInputView",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!ListFilterProxy.isFiltering() && !ModuleState.isSearchMode()) {
                                return; // 无筛选态，无需复位
                            }
                            resetFilterState();
                            XposedBridge.log(HookUtil.LOG_TAG + "IME input view finished, filter cleared");
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "onFinishInputView error: " + t);
                        }
                    }
                }));
    }

    /* ================= 模块动作 ================= */

    /** 清空筛选状态：复位搜索模式 + 清空关键词（触发 swapList 写回全量） */
    private static void resetFilterState() {
        ModuleState.setSearchMode(false);
        ListFilterProxy.clearKeyword();
    }

    /** 清除筛选：恢复全量列表（按钮「全部」） */
    public static void onClearFilter() {
        XposedBridge.log(HookUtil.LOG_TAG + "clear filter");
        ModuleState.setSearchMode(false);
        ListFilterProxy.clearKeyword();
        KeyboardListHooks.swapList();
    }

    /**
     * 🔍搜索：进入搜索模式（Route X）。
     * 收起剪贴板面板回主键盘（输入界面），用户用任意面板（拼音/符号/数字/其他输入法）输入；
     * commitText 经 hookCommitBuffer 累积进缓冲区，「完成」按钮（画在主键盘候选条
     * IMEInputCandidateViewContainer 上，见 InputCandidateHooks）应用关键词并退出搜索态。
     */
    public static void onSearchClick() {
        try {
            ModuleState.resetSearchBuffer();
            ModuleState.setSearchMode(true);
            // 收起剪贴板面板回主键盘（输入界面），原版行为 w() 私有 → 反射调用
            Object page = ModuleState.page();
            if (page != null) {
                XposedHelpers.callMethod(page, "w");
            }
            // 清空旧关键词，列表恢复全量，等待输入累积
            ListFilterProxy.clearKeyword();
            KeyboardListHooks.swapList();
            XposedBridge.log(HookUtil.LOG_TAG + "enter search mode (buffer intercept)");
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "onSearchClick error: " + t);
        }
    }

    /**
     * 完成：把缓冲区作为关键词应用并退出搜索态（由 CandidateViewHooks「完成」按钮命中调用）。
     * 关键词空则恢复全量列表。
     */
    public static void onFinishSearch() {
        try {
            String keyword = ModuleState.takeSearchBuffer();
            ModuleState.setSearchMode(false);
            ListFilterProxy.setKeyword(keyword);
            KeyboardListHooks.swapList();
            reopenClipboardPage(); // 重新打开剪贴板页展示过滤结果
            XposedBridge.log(HookUtil.LOG_TAG + "finish search: kw.len=" + keyword.length()
                    + " filtered=" + ListFilterProxy.filteredCount());
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "onFinishSearch error: " + t);
        }
    }

    /** 关键词确认后重新打开剪贴板页（路由方式，失败则用户手动打开，不影响功能） */
    private static void reopenClipboardPage() {
        try {
            Class<?> base = XposedHelpers.findClass(CLS_PAGE_BASE, ModuleState.classLoader());
            XposedHelpers.callStaticMethod(base, "F",
                    "/app/ClipboardPage", (Object) null);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "reopen page failed (manual reopen ok): " + t);
        }
    }
}