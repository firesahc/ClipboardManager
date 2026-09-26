package com.clipboard.enhance;

import de.robv.android.xposed.XposedHelpers;

/**
 * 宿主剪贴板仓库访问唯一出口：{@code db.a.b().a().a() → ClipboardItemDao}。
 *
 * 抽取理由：绕上限（ClipboardLimitBypass）、条目编辑（ClipboardEditController）、
 * 备份恢复（ClipboardBackupManager）三处都要同一条 dao 链，各自反射即三份重复；
 * 收敛到一处后宿主改链只改这里。无状态、无缓存，任一步失败返回 null。
 */
public final class ClipboardRepoAccessor {

    private ClipboardRepoAccessor() {
    }

    /** {@code db.a.b().a().a() → ClipboardItemDao}，任一步失败返回 null */
    public static Object dao() {
        try {
            Class<?> dbA = XposedHelpers.findClass(
                    SogouHostContract.CLIPBOARD_DB_A, ModuleState.classLoader());
            Object holder = XposedHelpers.callStaticMethod(dbA, "b");
            Object session = XposedHelpers.callMethod(holder, "a");
            return XposedHelpers.callMethod(session, "a");
        } catch (Throwable t) {
            return null;
        }
    }
}
