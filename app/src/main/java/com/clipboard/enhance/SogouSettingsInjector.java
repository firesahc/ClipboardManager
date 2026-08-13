package com.clipboard.enhance;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
    /** 自绘 UI 文案（宿主进程无应用资源，硬编码与原生 UI 语言一致） */
    private static final String LABEL_ENTRY_TITLE = "扩展设置";
    private static final String LABEL_ENTRY_SUMMARY = "剪贴板增强扩展功能";
    private static final String LABEL_PAGE_TITLE = "扩展设置";
    private static final String LABEL_BACK = "‹ 返回";
    private static final String LABEL_PIN_TITLE = "粘贴后置顶";
    private static final String LABEL_PIN_SUMMARY = "粘贴过的内容将排在列表最上方";
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
        HookUtil.safeHook("ext page onNewIntent", () -> XposedHelpers.findAndHookMethod(CLS_SETTING_ACTIVITY, cl, "onNewIntent", Intent.class,
                new XC_MethodHook() {
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

        // ---- 设置项卡片：粘贴后置顶 ----
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
        name.setText(LABEL_PIN_TITLE);
        name.setTextSize(16);
        name.setTextColor(COLOR_TITLE_TEXT);
        texts.addView(name);

        TextView desc = new TextView(activity);
        desc.setText(LABEL_PIN_SUMMARY);
        desc.setTextSize(12);
        desc.setTextColor(COLOR_DESC_TEXT);
        desc.setPadding(0, dp8, 0, 0);
        texts.addView(desc);

        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        Switch sw = new Switch(activity);
        sw.setChecked(sp.getBoolean(SP_KEY_PIN_RECENT, SP_DEFAULT_PIN_RECENT));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(SP_KEY_PIN_RECENT, isChecked).apply();
                ModuleState.setPinRecentEnabled(isChecked);
                XposedBridge.log(HookUtil.LOG_TAG + "pin recent switched: " + isChecked);
            }
        });
        card.addView(sw);

        return root;
    }

    /** 宿主全局 Context：com.sogou.lib.common.content.b.a() */
    private static Context globalContext() {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_GLOBAL_CTX, sCl);
            Object ctx = XposedHelpers.callStaticMethod(cls, "a");
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable t) {
            return null;
        }
    }
}