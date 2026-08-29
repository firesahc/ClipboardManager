package com.clipboard.enhance;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 粘贴次数计数：按剪贴板内容去重累计每条被粘贴的次数，持久化到模块私有
 * SharedPreferences，跨 IME 进程重启不丢。
 *
 * 设计要点：
 * - 条目实体 c 是宿主混淆类（greenDAO 持久化），无法改其 schema；进程重启后
 *   c 实例重建，运行时附加字段会丢失，故计数必须独立持久化。
 * - 以内容字符串（c.d）作唯一键：宿主按内容去重，同内容天然对应同一条记录，
 *   符合「该条被粘贴了几次」语义。
 * - 内存 Map 为热路径（列表项每帧绘制查询），SharedPreferences 仅作异步持久化。
 */
public final class PasteCounter {

    /** 模块私有 SP 文件名（区别于宿主默认 SP，避免污染） */
    private static final String SP_NAME = "clipboard_enhance_paste";

    /** 内容 → 次数（热路径，主线程读写） */
    private static final Map<String, Integer> sCounts = new HashMap<>();
    private static volatile SharedPreferences sSp;
    private static volatile boolean sLoaded = false;

    private PasteCounter() {
    }

    /** 惰性初始化：从 ModuleState 持有的 ClipboardKeyboard（Context）加载持久化计数 */
    public static void ensureInit() {
        if (sLoaded) {
            return;
        }
        Object kb = ModuleState.keyboard();
        if (!(kb instanceof Context)) {
            return; // 宿主实例尚未就绪，下次调用重试
        }
        synchronized (PasteCounter.class) {
            if (sLoaded) {
                return;
            }
            try {
                SharedPreferences sp = ((Context) kb).getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                sSp = sp;
                Map<String, ?> all = sp.getAll();
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Integer) {
                        sCounts.put(e.getKey(), (Integer) v);
                    }
                }
                sLoaded = true;
            } catch (Throwable t) {
                XposedBridge.log(HookUtil.LOG_TAG + "paste counter init error: " + t);
            }
        }
    }

    /** 取某内容的粘贴次数（未记录返回 0） */
    public static int getCount(String content) {
        ensureInit();
        if (TextUtils.isEmpty(content)) {
            return 0;
        }
        Integer c = sCounts.get(content);
        return c == null ? 0 : c;
    }

    /** 递增某内容的粘贴次数并异步持久化 */
    public static void increment(String content) {
        if (TextUtils.isEmpty(content)) {
            return;
        }
        ensureInit();
        int next;
        synchronized (sCounts) {
            next = sCounts.getOrDefault(content, 0) + 1;
            sCounts.put(content, next);
        }
        SharedPreferences sp = sSp;
        if (sp != null) {
            try {
                sp.edit().putInt(content, next).apply();
            } catch (Throwable t) {
                XposedBridge.log(HookUtil.LOG_TAG + "paste counter persist error: " + t);
            }
        }
    }
}
