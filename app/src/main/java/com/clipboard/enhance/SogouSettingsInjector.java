package com.clipboard.enhance;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 搜狗输入法设置页注入（扩展设置入口 + 扩展设置页）。
 *
 * 逆向事实（小米版搜狗输入法设置体系）：
 * - 设置主页：com.sohu.inputmethod.sogou.SogouIMESettings（BaseSettingActivity 子类）
 *   - g0() 返回主页 fragment（SogouPreferenceSettingsFragment，miuix PreferenceFragment，
 *     onCreatePreferences → G() 加载 XML prefes_sogouimesetting → H() 初始化字段/监听）
 *   - h0() 返回页面标题（setTitle 消费）
 *   - 基类 com.sogou.lib.preference.AbstractSogouPreferenceActivity.onCreate(Bundle)：
 *     setContentView(d.e) → findViewById(c.o) 赋值 this.e（FrameLayout 容器）→ i0() 挂 fragment
 *     （g0() 返回 null 时 i0() 直接 return，容器留空）→ setTitle(h0())
 * - 设置项：com.sogou.lib.preference.SogouPreference(Context)（androidx.preference.Preference 子类），
 *   PreferenceGroup.addPreference / findPreference(CharSequence) 可动态注入
 *
 * 注入策略（不新增宿主组件，全部复用已注册 Activity）：
 * - 主页 fragment H() after：向 preferenceScreen 注入「扩展设置」入口项
 * - SogouIMESettings：intent 带 EXTRA_EXT_PAGE 标记时，g0() 返回 null 跳过原生 fragment、
 *   h0() 返回「扩展设置」标题、onCreate 后向容器 this.e 注入自绘扩展设置 UI
 * - 开关状态存宿主默认 SharedPreferences（与 IME 同进程，静态状态直接共享），
 *   运行时开关值经 ModuleState 读写（原直接访问 ClipboardKeyboardInstrument）
 */
public final class SogouSettingsInjector {

    /* ================= 混淆类名（字符串引用，防混淆） ================= */
    private static final String CLS_MAIN_FRAGMENT = "com.sohu.inputmethod.settings.preference.SogouPreferenceSettingsFragment";
    private static final String CLS_MAIN_ACTIVITY = "com.sohu.inputmethod.sogou.SogouIMESettings";
    private static final String CLS_ACTIVITY_BASE = "com.sogou.lib.preference.AbstractSogouPreferenceActivity";
    /** 设置基类 Activity（override onNewIntent，singleTop 复用入口） */
    private static final String CLS_SETTING_ACTIVITY = "com.sohu.inputmethod.settings.preference.BaseSettingActivity";
    private static final String CLS_SOGOU_PREF = "com.sogou.lib.preference.SogouPreference";
    private static final String CLS_PREF_LISTENER = "androidx.preference.Preference$OnPreferenceClickListener";
    /** 宿主全局 Context 提供者（InputSettingFragment 等普遍使用） */
    private static final String CLS_GLOBAL_CTX = "com.sogou.lib.common.content.b";

    /* ================= 常量 ================= */
    /** 扩展设置页标记：SogouIMESettings intent extra，置 true 时展示扩展页而非原生主页 */
    private static final String EXTRA_EXT_PAGE = "clipboard_enhance_ext_page";
    /** 设置主页注入入口项的 key（findPreference 防重复注入） */
    private static final String PREF_KEY_ENTRY = "clipboard_enhance_ext_entry";
    /** 置顶开关持久化 key（宿主默认 SharedPreferences） */
    private static final String SP_KEY_PIN_RECENT = "clipboard_enhance_pin_recent";
    /** 置顶开关默认值：功能默认开启，用户可在扩展设置页关闭 */
    private static final boolean SP_DEFAULT_PIN_RECENT = true;
    /** 滑动删除确认开关持久化 key（宿主默认 SharedPreferences） */
    private static final String SP_KEY_SWIPE_CONFIRM = "clipboard_enhance_swipe_confirm";
    /** 滑动删除确认默认值：默认弹窗确认，关闭后左滑直接删除 */
    private static final boolean SP_DEFAULT_SWIPE_CONFIRM = true;
    /** 排序字段持久化 key（存 SortKey name，宿主默认 SharedPreferences） */
    private static final String SP_KEY_SORT_KEY = "clipboard_enhance_sort_key";
    /** 排序字段默认值：时间（即宿主原生顺序） */
    private static final String SP_DEFAULT_SORT_KEY = "TIME";
    /** 排序方向持久化 key（true=升序，false=降序） */
    private static final String SP_KEY_SORT_ASC = "clipboard_enhance_sort_asc";
    /** 排序方向默认值：降序（新内容/高热度在前） */
    private static final boolean SP_DEFAULT_SORT_ASC = false;
    /** 自绘 UI 文案（宿主进程无应用资源，硬编码与原生 UI 语言一致） */
    private static final String LABEL_ENTRY_TITLE = "扩展设置";
    private static final String LABEL_ENTRY_SUMMARY = "剪贴板增强扩展功能";
    private static final String LABEL_PAGE_TITLE = "扩展设置";
    private static final String LABEL_BACK = "‹ 返回";
    private static final String LABEL_PIN_TITLE = "粘贴后置顶";
    private static final String LABEL_PIN_SUMMARY = "粘贴过的内容将排在列表最上方";
    private static final String LABEL_SWIPE_TITLE = "滑动删除需确认";
    private static final String LABEL_SWIPE_SUMMARY = "关闭后左滑条目直接删除";
    private static final String LABEL_SORT_KEY_TITLE = "排序字段";
    private static final String LABEL_SORT_KEY_SUMMARY_PREFIX = "当前：";
    private static final String LABEL_SORT_ASC_TITLE = "排序升序";
    private static final String LABEL_SORT_ASC_SUMMARY = "关闭为降序（新的/热度高的在前）";
    private static final String LABEL_EXPORT_TITLE = "导出剪贴板";
    private static final String LABEL_EXPORT_SUMMARY = "保存为 JSON 文件（自选位置）";
    private static final String LABEL_IMPORT_TITLE = "导入剪贴板";
    private static final String LABEL_IMPORT_SUMMARY = "合并导入，重复内容更新时间";
    /** SAF 回执请求码（宿主无关的高位段，避开原生请求码） */
    private static final int REQ_EXPORT = 0xE401;
    private static final int REQ_IMPORT = 0xE402;
    /** 自绘 UI 颜色（贴近原生设置项视觉） */
    private static final int COLOR_PAGE_BG = 0xFFF2F3F5;      // 页面背景（浅灰）
    private static final int COLOR_BACK_TEXT = 0xFF1677FF;    // 返回链接（蓝）
    private static final int COLOR_TITLE_TEXT = 0xFF1F1F1F;   // 设置项标题（近黑）
    private static final int COLOR_DESC_TEXT = 0xFF8A8A8A;    // 设置项说明（灰）

    private static volatile ClassLoader sCl;

    private SogouSettingsInjector() {
    }

    /* ================= 入口 ================= */
    public static void init(ClassLoader cl) {
        sCl = cl;
        try {
            hookSettingsEntry(cl);
            hookExtPage(cl);
            XposedBridge.log(HookUtil.LOG_TAG + "settings injector installed");
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "settings injector error: " + t);
        }
    }

    /**
     * 恢复置顶开关（IME 服务重启时调用，SearchModeController.hookImeRestart 挂钩）。
     * 设置页与 IME 同进程，开关切换后静态状态已同步；此方法保证进程冷启动后
     * 从 SharedPreferences 恢复持久化值。
     */
    public static void restorePinRecentSetting() {
        try {
            Context ctx = globalContext();
            if (ctx == null) {
                return;
            }
            boolean enabled = PreferenceManager.getDefaultSharedPreferences(ctx)
                    .getBoolean(SP_KEY_PIN_RECENT, SP_DEFAULT_PIN_RECENT);
            ModuleState.setPinRecentEnabled(enabled);
            XposedBridge.log(HookUtil.LOG_TAG + "pin recent restored: " + enabled);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "restore pin setting error: " + t);
        }
    }

    /**
     * 恢复滑动删除确认开关（IME 服务重启时调用，与 restorePinRecentSetting 并列，
     * 见 SearchModeController.hookImeRestart）。设置页与 IME 同进程，切换后静态
     * 状态已同步；此方法保证进程冷启动后从 SharedPreferences 恢复持久化值。
     */
    public static void restoreSwipeDeleteConfirmSetting() {
        try {
            Context ctx = globalContext();
            if (ctx == null) {
                return;
            }
            boolean enabled = PreferenceManager.getDefaultSharedPreferences(ctx)
                    .getBoolean(SP_KEY_SWIPE_CONFIRM, SP_DEFAULT_SWIPE_CONFIRM);
            ModuleState.setSwipeDeleteConfirm(enabled);
            XposedBridge.log(HookUtil.LOG_TAG + "swipe confirm restored: " + enabled);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "restore swipe setting error: " + t);
        }
    }

    /**
     * 恢复排序设置（IME 服务重启时调用，与置顶/滑动开关并列）。
     * 排序状态唯一归属 ListFilterProxy，此处仅从 SP 恢复后写入代理。
     */
    public static void restoreSortSetting() {
        try {
            Context ctx = globalContext();
            if (ctx == null) {
                return;
            }
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
            String keyName = sp.getString(SP_KEY_SORT_KEY, SP_DEFAULT_SORT_KEY);
            boolean asc = sp.getBoolean(SP_KEY_SORT_ASC, SP_DEFAULT_SORT_ASC);
            ListFilterProxy.setSortKey(parseSortKey(keyName));
            ListFilterProxy.setSortAsc(asc);
            XposedBridge.log(HookUtil.LOG_TAG + "sort restored: " + keyName + (asc ? "↑" : "↓"));
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "restore sort setting error: " + t);
        }
    }

    /**
     * 持久化当前排序设置（候选栏排序键/设置页切换后调用）。
     * 只写 SP，不改代理状态（调用方已先改代理并 swapList）。
     */
    public static void saveSortSetting() {
        try {
            Context ctx = globalContext();
            if (ctx == null) {
                return;
            }
            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                    .putString(SP_KEY_SORT_KEY, ListFilterProxy.getSortKey().name())
                    .putBoolean(SP_KEY_SORT_ASC, ListFilterProxy.isSortAsc())
                    .apply();
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "save sort setting error: " + t);
        }
    }

    private static ListFilterProxy.SortKey parseSortKey(String name) {
        if ("COUNT".equals(name)) {
            return ListFilterProxy.SortKey.COUNT;
        }
        if ("LENGTH".equals(name)) {
            return ListFilterProxy.SortKey.LENGTH;
        }
        return ListFilterProxy.SortKey.TIME;
    }

    /* ================= 1. 设置主页注入「扩展设置」入口 =================
       主页 fragment H() 在 onCreatePreferences（G() 加载 XML 后）被调用；
       after 向 preferenceScreen 注入入口项。findPreference 防重复注入。 */
    private static void hookSettingsEntry(ClassLoader cl) {
        HookUtil.safeHook("settings entry", () -> XposedHelpers.findAndHookMethod(CLS_MAIN_FRAGMENT, cl, "H",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object fragment = param.thisObject;
                            // AbstractSogouPreferenceFragment.v = 关联 Activity
                            Context ctx = (Context) XposedHelpers.getObjectField(fragment, "v");
                            Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
                            if (ctx == null || screen == null) {
                                return;
                            }
                            if (XposedHelpers.callMethod(screen, "findPreference",
                                    (Object) PREF_KEY_ENTRY) != null) {
                                return; // 已注入过（fragment 可能重建）
                            }
                            Object pref = XposedHelpers.newInstance(
                                    XposedHelpers.findClass(CLS_SOGOU_PREF, sCl), ctx);
                            XposedHelpers.callMethod(pref, "setKey", (Object) PREF_KEY_ENTRY);
                            XposedHelpers.callMethod(pref, "setTitle", (Object) LABEL_ENTRY_TITLE);
                            XposedHelpers.callMethod(pref, "setSummary", (Object) LABEL_ENTRY_SUMMARY);
                            Class<?> listenerCls = XposedHelpers.findClass(CLS_PREF_LISTENER, sCl);
                            Object listener = Proxy.newProxyInstance(sCl, new Class<?>[]{listenerCls},
                                    new InvocationHandler() {
                                        @Override
                                        public Object invoke(Object proxy, Method method, Object[] args) {
                                            if ("onPreferenceClick".equals(method.getName())) {
                                                openExtPage(ctx);
                                                return Boolean.TRUE;
                                            }
                                            return null;
                                        }
                                    });
                            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", listener);
                            XposedHelpers.callMethod(screen, "addPreference", pref);
                            XposedBridge.log(HookUtil.LOG_TAG + "ext entry injected");
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "inject entry error: " + t);
                        }
                    }
                }));
    }

    /** 打开扩展设置页：复用宿主已注册的 SogouIMESettings，intent 携带扩展页标记 */
    private static void openExtPage(Context context) {
        try {
            Intent intent = new Intent();
            // 包名不能写死：小米版宿主包名为 com.sohu.inputmethod.sogou.xiaomi，
            // 标准版为 com.sohu.inputmethod.sogou，运行时取宿主包名保证组件可解析
            intent.setClassName(context.getPackageName(), CLS_MAIN_ACTIVITY);
            intent.putExtra(EXTRA_EXT_PAGE, true);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "open ext page error: " + t);
        }
    }

    /* ================= 2. 扩展设置页 =================
       SogouIMESettings 复用为扩展设置页：
       - g0() before：扩展标记时返回 null → i0() 跳过原生 fragment，容器留空
       - h0() before：扩展标记时返回「扩展设置」→ setTitle 生效
       - onCreate after：扩展标记时向 this.e 注入自绘 UI
       - onNewIntent after：singleTop 复用（设置主页点击入口时实例已在栈顶，
         不重新走 onCreate）→ 同样注入扩展 UI 并设标题 */
    private static void hookExtPage(ClassLoader cl) {
        HookUtil.safeHook("g0", () -> XposedHelpers.findAndHookMethod(CLS_MAIN_ACTIVITY, cl, "g0",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (isExtPage((Activity) param.thisObject)) {
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "g0 ext error: " + t);
                        }
                    }
                }));
        HookUtil.safeHook("h0", () -> XposedHelpers.findAndHookMethod(CLS_MAIN_ACTIVITY, cl, "h0",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (isExtPage((Activity) param.thisObject)) {
                                param.setResult(LABEL_PAGE_TITLE);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "h0 ext error: " + t);
                        }
                    }
                }));
        HookUtil.safeHook("ext page", () -> XposedHelpers.findAndHookMethod(CLS_ACTIVITY_BASE, cl, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        injectExtPageIfNeeded((Activity) param.thisObject);
                    }
                }));
        HookUtil.safeHook("ext page onNewIntent", () -> XposedHelpers.findAndHookMethod(CLS_SETTING_ACTIVITY, cl, "onNewIntent", Intent.class,                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            // getIntent() 在 onNewIntent 时仍返回旧 intent（singleTop 复用场景），
                            // 需手动 setIntent 更新，否则 isExtPage 读不到扩展标记
                            Activity activity = (Activity) param.thisObject;
                            activity.setIntent((Intent) param.args[0]);
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "onNewIntent setIntent error: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        injectExtPageIfNeeded((Activity) param.thisObject);
                    }
                }));
        hookActivityResult();
    }

    /* ================= 2b. SAF 回执 =================
       导出/导入经系统文件选择器自选位置（零权限），回执在宿主设置 Activity 上：
       BaseSettingActivity 未声明 onActivityResult，改 hook 基类 android.app.Activity，
       以高位请求码过滤，与宿主原生回执互不干扰。 */
    private static void hookActivityResult() {
        HookUtil.safeHook("activityResult", () -> XposedHelpers.findAndHookMethod(
                Activity.class, "onActivityResult", int.class, int.class, Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int requestCode = (Integer) param.args[0];
                            if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) {
                                return;
                            }
                            int resultCode = (Integer) param.args[1];
                            if (resultCode != Activity.RESULT_OK) {
                                return;
                            }
                            Intent data = (Intent) param.args[2];
                            if (data == null || data.getData() == null) {
                                return;
                            }
                            Uri uri = data.getData();
                            Context ctx = (Context) param.thisObject;
                            if (requestCode == REQ_EXPORT) {
                                ClipboardBackupManager.exportToUri(ctx, uri);
                            } else {
                                ClipboardBackupManager.importFromUri(ctx, uri);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "activityResult error: " + t);
                        }
                    }
                }));
    }

    /** 扩展标记时注入扩展页 UI；非扩展页直接忽略。onCreate 与 onNewIntent 共用 */
    private static void injectExtPageIfNeeded(Activity activity) {
        try {
            if (!isExtPage(activity)) {
                return;
            }
            FrameLayout container = (FrameLayout) XposedHelpers.getObjectField(activity, "e");
            if (container == null) {
                return;
            }
            container.removeAllViews();
            container.addView(buildExtPage(activity));
            activity.setTitle(LABEL_PAGE_TITLE);
            XposedBridge.log(HookUtil.LOG_TAG + "ext page ui injected");
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "ext page inject error: " + t);
        }
    }

    private static boolean isExtPage(Activity activity) {
        if (activity == null || activity.getIntent() == null) {
            return false;
        }
        return activity.getIntent().getBooleanExtra(EXTRA_EXT_PAGE, false);
    }

    /* ================= 3. 自绘扩展设置页 UI =================
       纯 Android framework 视图（模块无宿主资源依赖），风格贴近原生设置项：
       白底条目 + 左侧标题/说明 + 右侧 Switch。 */
    private static View buildExtPage(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        int dp8 = (int) (8 * density);
        int dp16 = (int) (16 * density);
        int dp24 = (int) (24 * density);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_PAGE_BG);

        // ---- 返回栏 ----
        TextView back = new TextView(activity);
        back.setText(LABEL_BACK);
        back.setTextSize(16);
        back.setTextColor(COLOR_BACK_TEXT);
        back.setPadding(dp24, dp16, dp24, dp16);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.finish();
            }
        });
        root.addView(back);

        // ---- 设置项卡片：粘贴后置顶 / 滑动删除确认（同一样式，见 addSwitchCard） ----
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        addSwitchCard(activity, root, dp8, dp16, LABEL_PIN_TITLE, LABEL_PIN_SUMMARY,
                sp.getBoolean(SP_KEY_PIN_RECENT, SP_DEFAULT_PIN_RECENT),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        sp.edit().putBoolean(SP_KEY_PIN_RECENT, isChecked).apply();
                        ModuleState.setPinRecentEnabled(isChecked);
                        XposedBridge.log(HookUtil.LOG_TAG + "pin recent switched: " + isChecked);
                    }
                });
        addSwitchCard(activity, root, dp8, dp16, LABEL_SWIPE_TITLE, LABEL_SWIPE_SUMMARY,
                sp.getBoolean(SP_KEY_SWIPE_CONFIRM, SP_DEFAULT_SWIPE_CONFIRM),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        sp.edit().putBoolean(SP_KEY_SWIPE_CONFIRM, isChecked).apply();
                        ModuleState.setSwipeDeleteConfirm(isChecked);
                        XposedBridge.log(HookUtil.LOG_TAG + "swipe confirm switched: " + isChecked);
                    }
                });

        // ---- 排序：字段行（点击循环 时间→热度→长度）+ 方向开关（升序/降序） ----
        final TextView sortKeySummary = new TextView(activity);
        addActionCard(activity, root, dp8, dp16, LABEL_SORT_KEY_TITLE, sortKeySummary,
                sortSummaryText(sp),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ListFilterProxy.SortKey next = nextSortKey(
                                sp.getString(SP_KEY_SORT_KEY, SP_DEFAULT_SORT_KEY));
                        sp.edit().putString(SP_KEY_SORT_KEY, next.name()).apply();
                        ListFilterProxy.setSortKey(next);
                        sortKeySummary.setText(sortSummaryText(sp));
                        KeyboardListHooks.swapList();
                        XposedBridge.log(HookUtil.LOG_TAG + "sort key switched: " + next.name());
                    }
                });
        addSwitchCard(activity, root, dp8, dp16, LABEL_SORT_ASC_TITLE, LABEL_SORT_ASC_SUMMARY,
                sp.getBoolean(SP_KEY_SORT_ASC, SP_DEFAULT_SORT_ASC),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        sp.edit().putBoolean(SP_KEY_SORT_ASC, isChecked).apply();
                        ListFilterProxy.setSortAsc(isChecked);
                        KeyboardListHooks.swapList();
                        XposedBridge.log(HookUtil.LOG_TAG + "sort asc switched: " + isChecked);
                    }
                });

        // ---- 备份恢复：SAF 自选位置导出/合并导入（后台线程，无权限需求） ----
        final Activity hostActivity = activity;
        addActionCard(activity, root, dp8, dp16, LABEL_EXPORT_TITLE, new TextView(activity),
                LABEL_EXPORT_SUMMARY,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                                    java.util.Locale.US).format(new java.util.Date());
                            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            it.addCategory(Intent.CATEGORY_OPENABLE);
                            it.setType("application/json");
                            it.putExtra(Intent.EXTRA_TITLE, "clipboard-backup-" + stamp + ".json");
                            hostActivity.startActivityForResult(it, REQ_EXPORT);
                        } catch (ActivityNotFoundException e) {
                            // 无文件选择器的降级：经 MediaStore 落 Download
                            ClipboardBackupManager.exportToDownloadFallback(hostActivity);
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "export intent error: " + t);
                        }
                    }
                });
        addActionCard(activity, root, dp8, dp16, LABEL_IMPORT_TITLE, new TextView(activity),
                LABEL_IMPORT_SUMMARY,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            it.addCategory(Intent.CATEGORY_OPENABLE);
                            it.setType("application/json");
                            hostActivity.startActivityForResult(it, REQ_IMPORT);
                        } catch (ActivityNotFoundException e) {
                            android.widget.Toast.makeText(hostActivity, "无文件选择器，无法导入",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "import intent error: " + t);
                        }
                    }
                });

        return root;
    }

    /** 排序字段行摘要：当前字段文案（读 SP，与代理状态同源） */
    private static String sortSummaryText(SharedPreferences sp) {
        ListFilterProxy.SortKey k = parseSortKey(sp.getString(SP_KEY_SORT_KEY, SP_DEFAULT_SORT_KEY));
        String label = k == ListFilterProxy.SortKey.COUNT ? "热度"
                : k == ListFilterProxy.SortKey.LENGTH ? "长度" : "时间";
        return LABEL_SORT_KEY_SUMMARY_PREFIX + label + "（点击切换）";
    }

    private static ListFilterProxy.SortKey nextSortKey(String cur) {
        ListFilterProxy.SortKey k = parseSortKey(cur);
        return k == ListFilterProxy.SortKey.TIME ? ListFilterProxy.SortKey.COUNT
                : k == ListFilterProxy.SortKey.COUNT ? ListFilterProxy.SortKey.LENGTH
                : ListFilterProxy.SortKey.TIME;
    }

    /**
     * 可点击行 builder（白底条目 + 左侧标题/说明 + 右侧 › 指示，与开关卡片同视觉）。
     * 点击行为由调用方传入；说明行由调用方持有引用自行更新。
     */
    private static void addActionCard(Activity activity, LinearLayout root, int dp8, int dp16,
                                      String title, final TextView summaryView, String summary,
                                      View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp16, dp16, dp16, dp16);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(card, cardLp);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(texts, textsLp);

        TextView name = new TextView(activity);
        name.setText(title);
        name.setTextSize(16);
        name.setTextColor(COLOR_TITLE_TEXT);
        texts.addView(name);

        summaryView.setText(summary);
        summaryView.setTextSize(12);
        summaryView.setTextColor(COLOR_DESC_TEXT);
        summaryView.setPadding(0, dp8, 0, 0);
        texts.addView(summaryView);

        TextView arrow = new TextView(activity);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(COLOR_DESC_TEXT);
        card.addView(arrow);

        card.setOnClickListener(listener);
    }

    /**
     * 开关卡片 builder（白底条目 + 左侧标题/说明 + 右侧 Switch，与原生设置项视觉一致）。
     * 置顶/滑动删除确认共用，避免两套同构 UI 代码。
     */
    private static void addSwitchCard(Activity activity, LinearLayout root, int dp8, int dp16,
                                      String title, String summary, boolean checked,
                                      CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp16, dp16, dp16, dp16);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(card, cardLp);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(texts, textsLp);

        TextView name = new TextView(activity);
        name.setText(title);
        name.setTextSize(16);
        name.setTextColor(COLOR_TITLE_TEXT);
        texts.addView(name);

        TextView desc = new TextView(activity);
        desc.setText(summary);
        desc.setTextSize(12);
        desc.setTextColor(COLOR_DESC_TEXT);
        desc.setPadding(0, dp8, 0, 0);
        texts.addView(desc);

        Switch sw = new Switch(activity);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        card.addView(sw);
    }

    /** 宿主全局 Context：com.sogou.lib.common.content.b.a() */
    /** 宿主全局 Context（同包复用：PasteCounter 持久化初始化同源） */
    static Context globalContext() {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_GLOBAL_CTX, sCl);
            Object ctx = XposedHelpers.callStaticMethod(cls, "a");
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable t) {
            return null;
        }
    }
}