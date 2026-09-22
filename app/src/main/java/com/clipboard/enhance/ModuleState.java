package com.clipboard.enhance;

/**
 * 模块共享状态集中地（Xposed hook 间传递的宿主实例/类加载器/开关）。
 *
 * 拆分前这些静态字段散落在 ClipboardKeyboardInstrument 各处，且设置注入器直接读写
 * 该类；集中后各 hook 领域类（CandidateViewHooks / KeyboardListHooks /
 * SearchModeController / ClipboardLimitBypass）只依赖本类，消除跨类静态耦合。
 *
 * 全部字段为 volatile 静态字段：hook 回调跨线程执行（绘制/触摸/IME 生命周期），
 * 与拆分前语义完全一致。
 */
public final class ModuleState {

    /** 目标进程类加载器（init 时注入，各 hook 反射查找宿主类用） */
    private static volatile ClassLoader sCl;
    /** ClipboardPage 实例（hookPageCreate 记录，搜索时收起面板用） */
    private static volatile Object sPage;
    /** ClipboardKeyboard 实例（onChanged 记录，列表写回/上屏用） */
    private static volatile Object sKeyboard;
    /** ClipboardCandidateView 实例（drawBase 记录，计数刷新用） */
    private static volatile Object sCandidateView;
    /**
     * 搜索模式标志：为 true 时所有面板（拼音/符号/数字/其他输入法）经 InputConnection.commitText
     * 上屏的字符被统一拦截、累积进 sSearchBuffer，不真正上屏，等待「完成」按钮把缓冲区作为关键词应用。
     * 见 SearchModeController.hookCommitBuffer 与 CandidateViewHooks 的「完成」按钮。
     */
    private static volatile boolean sSearchMode = false;
    /**
     * 搜索态输入缓冲区：搜索态下所有 commitText 上屏字符累积于此，点「完成」时作为关键词应用。
     * StringBuilder 非线程安全，IME 提交回调虽多串行于 IME 线程，仍用 synchronized 保护读写以防重入。
     */
    private static final StringBuilder sSearchBuffer = new StringBuilder();
    /**
     * 粘贴后置顶开关（默认开启，扩展设置页可关闭）。
     * 开启时粘贴上屏后调用宿主 ClipboardKeyboard.O(String)（宿主原生插入链路：
     * 按内容去重 + 更新时间戳 + orderDesc(Time) 排序 + LiveData 上报），
     * 让宿主自主把刚粘贴的条目排到最上方；关闭时退化为原生行为（粘贴后列表不动）。
     */
    private static volatile boolean sPinRecentEnabled = true;

    private ModuleState() {
    }

    public static ClassLoader classLoader() {
        return sCl;
    }

    public static void setClassLoader(ClassLoader cl) {
        sCl = cl;
    }

    public static Object page() {
        return sPage;
    }

    public static void setPage(Object page) {
        sPage = page;
    }

    public static Object keyboard() {
        return sKeyboard;
    }

    public static void setKeyboard(Object keyboard) {
        sKeyboard = keyboard;
    }

    public static Object candidateView() {
        return sCandidateView;
    }

    public static void setCandidateView(Object candidateView) {
        sCandidateView = candidateView;
    }

    public static boolean isSearchMode() {
        return sSearchMode;
    }

    public static void setSearchMode(boolean searchMode) {
        sSearchMode = searchMode;
        if (!searchMode) {
            // 退出搜索态：丢弃未应用的缓冲区，避免下次进入时残留旧关键词
            resetSearchBuffer();
        }
    }

    /** 搜索态下追加一段上屏字符到缓冲区（commitText 拦截调用） */
    public static void appendSearchBuffer(CharSequence cs) {
        if (cs == null) {
            return;
        }
        synchronized (sSearchBuffer) {
            sSearchBuffer.append(cs);
        }
    }

    /** 取出并清空缓冲区（「完成」按钮应用关键词时调用） */
    public static String takeSearchBuffer() {
        synchronized (sSearchBuffer) {
            String r = sSearchBuffer.toString();
            sSearchBuffer.setLength(0);
            return r;
        }
    }

    /** 仅清空缓冲区（进入搜索态时调用） */
    public static void resetSearchBuffer() {
        synchronized (sSearchBuffer) {
            sSearchBuffer.setLength(0);
        }
    }

    /** 只读查看当前缓冲区（调试/诊断用） */
    public static String peekSearchBuffer() {
        synchronized (sSearchBuffer) {
            return sSearchBuffer.toString();
        }
    }

    public static boolean isPinRecentEnabled() {
        return sPinRecentEnabled;
    }

    public static void setPinRecentEnabled(boolean enabled) {
        sPinRecentEnabled = enabled;
    }
}