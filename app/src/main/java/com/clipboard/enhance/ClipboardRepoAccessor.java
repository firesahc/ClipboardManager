package com.clipboard.enhance;

import java.util.List;

import de.robv.android.xposed.XposedBridge;
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

    /**
     * 按时间全量查询（asc=true 最旧在前，否则最新在前）。
     *
     * 真机实锤：宿主 greenDAO 的 {@code orderAsc/orderDesc} 是变参
     * {@code (Property...)}，按单参反射必 {@code NoSuchMethodError}
     * （上限绕过自始失效即因此）。此处按单数组参数方法精确查找后传入，
     * 任一步失败返回 null。
     */
    public static List<?> queryAllOrdered(Object dao, boolean asc) {
        if (dao == null) {
            return null;
        }
        try {
            Object qb = XposedHelpers.callMethod(dao, "queryBuilder");
            String name = asc ? "orderAsc" : "orderDesc";
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : qb.getClass().getMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0].isArray()) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                XposedBridge.log(HookUtil.LOG_TAG + "ordered query: no " + name + "(Property[])");
                return null;
            }
            Class<?> props = XposedHelpers.findClass(
                    SogouHostContract.CLIPBOARD_DAO_PROPS, ModuleState.classLoader());
            Object timeProp = XposedHelpers.getStaticObjectField(props, "Time");
            Object arr = java.lang.reflect.Array.newInstance(
                    target.getParameterTypes()[0].getComponentType(), 1);
            java.lang.reflect.Array.set(arr, 0, timeProp);
            Object ordered = target.invoke(qb, arr);
            Object listObj = XposedHelpers.callMethod(ordered, "list");
            return listObj instanceof List ? (List<?>) listObj : null;
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "ordered query failed: " + t);
            return null;
        }
    }
}
