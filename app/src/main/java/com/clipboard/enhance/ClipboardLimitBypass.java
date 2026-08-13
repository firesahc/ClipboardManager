package com.clipboard.enhance;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 剪贴板 150 条上限绕过：Hook 数据仓库插入入口 p.H，删除最旧条目前备份、之后插回。
 *
 * 逆向事实（com.sohu.inputmethod.clipboard.*，混淆名）：
 * - p（clipboard 数据仓库，静态单例 t()，单线程池串行）：
 *   - H(c)（static synthetic，插入上限裁剪点）：去重(Content unique) → insertOrReplace →
 *     orderAsc(Time) 查询，size>150 则 delete(list.get(0)) 删除最旧 —— 上限 150 条
 *     绕过：before 备份最旧条目，after 无条件 insertOrReplace 插回（未超限时幂等无害）
 * - db 链：db.a.b().a().a() → ClipboardItemDao（主键自增 _id，实体带 id 插回安全）
 * - 最旧条目查询：ClipboardItemDao$Properties.Time → queryBuilder().orderAsc(Time).list().get(0)
 */
public final class ClipboardLimitBypass {

    /* ================= 混淆类名（字符串引用，防混淆） ================= */
    /** 剪贴板数据仓库（150 条上限裁剪点 p.H，static synthetic，lambda 桥接名保留） */
    private static final String CLS_CLIP_REPO = "com.sohu.inputmethod.clipboard.p";
    /** 剪贴板条目实体（db.c 实体的混淆名，字段 d=content、c=time） */
    private static final String CLS_CLIP_ITEM = "com.sohu.inputmethod.clipboard.c";
    /** 数据库门面 → session → dao 链：db.a.b().a().a() */
    private static final String CLS_DB_A = "com.sohu.inputmethod.clipboard.db.a";

    private ClipboardLimitBypass() {
    }

    /** 注册本领域全部 hook（注册顺序与拆分前一致） */
    public static void init(ClassLoader cl) {
        hookClipboardLimit(cl);
    }

    /* ================= 去除 150 条上限 =================
       插入入口 H(c)：去重 → insertOrReplace → 超过 150 条删除最旧。
       before 备份最旧条目，after 无条件重新插入 —— 原逻辑删除的正是它，
       插回后等效「无上限」；未超限时插回是幂等更新（主键自增 _id，实体带 id），无副作用 */
    private static void hookClipboardLimit(ClassLoader cl) {
        HookUtil.safeHook("p.H", () -> XposedHelpers.findAndHookMethod(CLS_CLIP_REPO, cl, "H", CLS_CLIP_ITEM,
                new XC_MethodHook() {
                    private volatile Object sOldestBackup; // 单线程池串行，volatile 仅防御

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        sOldestBackup = null;
                        try {
                            Object dao = getClipboardDao();
                            if (dao == null) {
                                return;
                            }
                            // 按时间升序取最旧一条（与原逻辑 orderAsc(Time).list().get(0) 一致）
                            Object timeProp = XposedHelpers.getStaticObjectField(
                                    XposedHelpers.findClass(
                                            "com.sohu.inputmethod.clipboard.db.ClipboardItemDao$Properties", cl),
                                    "Time");
                            Object qb = XposedHelpers.callMethod(dao, "queryBuilder");
                            Object qbAsc = XposedHelpers.callMethod(qb, "orderAsc", timeProp);
                            List<?> all = (List<?>) XposedHelpers.callMethod(qbAsc, "list");
                            if (all != null && !all.isEmpty()) {
                                sOldestBackup = all.get(0);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "backup oldest failed: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object backup = sOldestBackup;
                        sOldestBackup = null;
                        if (backup == null) {
                            return;
                        }
                        try {
                            Object dao = getClipboardDao();
                            if (dao != null) {
                                XposedHelpers.callMethod(dao, "insertOrReplace", backup);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "restore oldest failed: " + t);
                        }
                    }
                }));
    }

    /** db.a.b().a().a() → ClipboardItemDao（链式反射，任一步失败返回 null） */
    private static Object getClipboardDao() {
        try {
            Class<?> dbA = XposedHelpers.findClass(CLS_DB_A, ModuleState.classLoader());
            Object holder = XposedHelpers.callStaticMethod(dbA, "b");
            Object session = XposedHelpers.callMethod(holder, "a");
            return XposedHelpers.callMethod(session, "a");
        } catch (Throwable t) {
            return null;
        }
    }
}