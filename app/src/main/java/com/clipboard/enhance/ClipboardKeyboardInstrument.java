package com.clipboard.enhance;

import de.robv.android.xposed.XposedBridge;

/**
 * 剪贴板增强核心编排入口：按领域注册全部 Hook。
 *
 * 领域划分（原上帝类按职责拆分，行为与注释原样迁移）：
 * - {@link CandidateViewHooks}：ClipboardCandidateView 绘制/触摸/计数/取消退出
 * - {@link KeyboardListHooks}：ClipboardKeyboard 列表写回/上屏置顶/全选范围/删除保护
 * - {@link ClipboardEditController}：长按弹窗编辑行/编辑框/合并写
 * - {@link ClipboardBackupManager}：备份恢复（设置页驱动，无 IME hook，经 SAF/Download 落盘）
 * - {@link PasteCountHooks}：列表项粘贴次数（右上角蓝色）绘制
 * - {@link QuickPasteHooks}：主列表与输入全部之外的整条上屏口计数补齐（首候选+VPA历史）
 * - {@link SearchModeController}：搜索模式拦截/入口劫持完成/页面路由/IME 生命周期清理
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
        // 排序热度数据源：代理保持纯逻辑，次数经此注入（单测注入假数据，见 ListFilterProxyTest）
        ListFilterProxy.setCountProvider(new ListFilterProxy.CountProvider() {
            @Override
            public int getCount(String content) {
                return PasteCounter.getCount(content);
            }
        });
        try {
            CandidateViewHooks.init(cl);
            KeyboardListHooks.init(cl);
            ClipboardEditController.init(cl);
            PasteCountHooks.init(cl);
            QuickPasteHooks.init(cl);
            SearchModeController.init(cl);
            ClipboardLimitBypass.init(cl);
            SogouSettingsInjector.init(cl);
            XposedBridge.log(HookUtil.LOG_TAG + "all hooks installed");
            try {
                XposedBridge.log(HookUtil.LOG_TAG + SogouHostContract.probe(cl));
            } catch (Throwable t) {
                XposedBridge.log(HookUtil.LOG_TAG + "probe error: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "init error: " + t);
        }
    }
}