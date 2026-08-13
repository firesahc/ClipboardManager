package com.clipboard.enhance;

import de.robv.android.xposed.XposedBridge;

/**
 * Hook 基础设施：统一日志前缀 + 消除「try-catch + findAndHookMethod + 日志」样板。
 *
 * 原 ClipboardKeyboardInstrument / SogouSettingsInjector 中每个 hook 注册都是同一套
 * try/catch 结构（成功打 installed 日志、失败打 failed 日志），此处收敛为一次包装，
 * 各 hook 只需声明注册动作，异常处理与日志保持一致。
 */
public final class HookUtil {

    /** 统一日志前缀：LSPosed 日志面板按前缀过滤 */
    public static final String LOG_TAG = "[ClipboardEnhance] ";

    private HookUtil() {
    }

    /**
     * 安全注册一个 hook：执行注册动作并记录成功日志；任何异常记录失败日志，不向外抛。
     *
     * @param tag    hook 标识（如 "drawBase"、"p.H"），用于日志：成功 "{tag} hook installed"、
     *               失败 "hook {tag} failed: ..."，与拆分前日志格式保持一致
     * @param action hook 注册动作（findAndHookMethod 等）
     */
    public static void safeHook(String tag, HookAction action) {
        try {
            action.apply();
            XposedBridge.log(LOG_TAG + tag + " hook installed");
        } catch (Throwable t) {
            XposedBridge.log(LOG_TAG + "hook " + tag + " failed: " + t);
        }
    }

    /** hook 注册动作（可抛异常，由 safeHook 统一兜底） */
    @FunctionalInterface
    public interface HookAction {
        void apply() throws Throwable;
    }
}