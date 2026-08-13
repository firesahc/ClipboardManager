package com.clipboard.enhance;

import de.robv.android.xposed.XposedBridge;

/**
 * 剪贴板增强核心编排入口：按领域注册全部 Hook。
 *
 * 领域划分（原上帝类按职责拆分，行为与注释原样迁移）：
 * - {@link CandidateViewHooks}：ClipboardCandidateView 绘制/触摸/计数/取消退出
 * - {@link KeyboardListHooks}：ClipboardKeyboard 列表写回/上屏置顶/全选范围/删除保护
 * - {@link SearchModeController}：搜索模式拦截/页面路由/IME 生命周期清理
 * - {@link ClipboardLimitBypass}：150 条上限绕过
 * - {@link SogouSettingsInjector}：设置页注入
 *
 * 共享状态集中见 {@link ModuleState}；hook 注册样板见 {@link HookUtil}。
 *
 * 逆向事实（com.sohu.inputmethod.clipboard.*，混淆名）详见各领域类头部注释。
 */
public final class ClipboardKeyboardInstrument {

    private ClipboardKeyboardInstrument() {
    }

    /** 注册全部 hook（顺序与拆分前一致，每个 hook 独立容错） */
    public static void init(final ClassLoader cl) {
        ModuleState.setClassLoader(cl);
        try {
            CandidateViewHooks.init(cl);
            KeyboardListHooks.init(cl);
            SearchModeController.init(cl);
            ClipboardLimitBypass.init(cl);
            SogouSettingsInjector.init(cl);
            XposedBridge.log(HookUtil.LOG_TAG + "all hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "init error: " + t);
        }
    }
}