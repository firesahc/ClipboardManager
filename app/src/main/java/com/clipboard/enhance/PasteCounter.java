package com.clipboard.enhance;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 内容 → 次数（热路径，绘制线程读/IME线程写，用并发Map保证可见性与原子性） */
    private static final Map<String, Integer> sCounts = new ConcurrentHashMap<>();
    /** SP key前缀（新写入带前缀；老版本无前缀的原始内容key兼容读取） */
    private static final String KEY_PREFIX = "pc_";
    /** 内存/SP key截断上限：防止超长剪贴板撑大SP XML；超长以后缀hash区分 */
    private static final int MAX_KEY_LEN = 200;
    private static volatile SharedPreferences sSp;
    private static volatile boolean sLoaded = false;

    private PasteCounter() {
    }

    /** 显式初始化：由宿主实例就绪方传入Context，避免经全局状态暗取（首选入口） */
    public static void init() {
        if (sLoaded) {
            return;
        }
        synchronized (PasteCounter.class) {
            if (sLoaded) {
                return;
            }
            Context ctx = SogouSettingsInjector.globalContext();
            if (ctx == null) {
                XposedBridge.log(HookUtil.LOG_TAG + "paste counter init: no host context, retry later");
                return;
            }
            loadFrom(ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE));
        }
    }

    /** 惰性初始化（兼容入口）：宿主实例未就绪时下次重试；已显式init后直接返回 */
    public static void ensureInit() {
        if (sLoaded) {
            return;
        }
        init();
    }

    private static void loadFrom(SharedPreferences sp) {
        if (sp == null) {
            return;
        }
        try {
            sSp = sp;
            Map<String, ?> all = sp.getAll();
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Integer) {
                        String k = e.getKey();
                        // 新key带前缀，老key为原始内容：统一还原为内存key
                        String contentKey = k != null && k.startsWith(KEY_PREFIX)
                                ? k.substring(KEY_PREFIX.length()) : k;
                        if (contentKey != null) {
                            sCounts.putIfAbsent(contentKey, (Integer) v);
                        }
                    }
                }
                sLoaded = true;
            } catch (Throwable t) {
                XposedBridge.log(HookUtil.LOG_TAG + "paste counter init error: " + t);
            }
    }

    /** 取某内容的粘贴次数（未记录返回 0，无锁读） */
    /**
     * 对账剪除：只保留现存条目的计数，已删条目的孤儿记录从内存和 SP 一并清除。
     * 由 onChanged 全量上报后调用（唯一漏斗）；筛选态下传全量，不误删被过滤隐藏的项。
     * 老版本无前缀 key 一并清除（迁移残留）。
     */
    public static void prune(java.util.Collection<String> aliveContents) {
        ensureInit();
        if (aliveContents == null) {
            return;
        }
        java.util.Set<String> alive = new java.util.HashSet<>(aliveContents.size() * 2 + 1);
        for (String c : aliveContents) {
            if (c != null) {
                alive.add(mapKey(c));
            }
        }
        java.util.List<String> orphans = new java.util.ArrayList<>();
        for (String k : sCounts.keySet()) {
            if (!alive.contains(k)) {
                orphans.add(k);
            }
        }
        if (orphans.isEmpty()) {
            return;
        }
        for (String k : orphans) {
            sCounts.remove(k);
        }
        SharedPreferences sp = sSp;
        if (sp != null) {
            try {
                SharedPreferences.Editor ed = sp.edit();
                for (String k : orphans) {
                    ed.remove(KEY_PREFIX + k);
                    ed.remove(k);
                }
                ed.apply();
            } catch (Throwable t) {
                XposedBridge.log(HookUtil.LOG_TAG + "paste counter prune error: " + t);
            }
        }
    }

    public static int getCount(String content) {
        ensureInit();
        if (TextUtils.isEmpty(content)) {
            return 0;
        }
        Integer c = sCounts.get(mapKey(content));
        return c == null ? 0 : c;
    }

    /** 递增某内容的粘贴次数并异步持久化 */
    public static void increment(String content) {
        if (TextUtils.isEmpty(content)) {
            return;
        }
        ensureInit();
        String k = mapKey(content);
        int next = sCounts.merge(k, 1, Integer::sum);
        SharedPreferences sp = sSp;
        if (sp != null) {
            try {
                sp.edit().putInt(KEY_PREFIX + k, next).apply();
            } catch (Throwable t) {
                XposedBridge.log(HookUtil.LOG_TAG + "paste counter persist error: " + t);
            }
        }
    }

    /** 内存/SP统一key：截断超长内容并以后缀hash区分，避免SP XML无限膨胀 */
    private static String mapKey(String content) {
        if (content.length() <= MAX_KEY_LEN) {
            return content;
        }
        return content.substring(0, 180) + "...#" + Integer.toHexString(content.hashCode());
    }
}
