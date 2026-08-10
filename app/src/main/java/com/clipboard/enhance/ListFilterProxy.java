package com.clipboard.enhance;

import java.util.ArrayList;
import java.util.List;

/**
 * 剪贴板列表过滤代理（纯逻辑，无 UI 依赖）。
 *
 * 背景（逆向事实）：
 * - ClipboardKeyboard.M 字段（List<c>）为列表数据源；adapter（b 类）私有字段 j 为显示列表；
 * - 过滤只需把「过滤后的 List」同时写回 M 与 adapter.j —— 因为列表内是同一批 c 对象
 *   （引用不变），上屏 ClipboardKeyboard.a(int) 用 M.get(i).d、勾选 b.g() 遍历 j，
 *   删除/上屏按对象引用取内容，天然安全，无需额外索引映射。
 * - 原列表保存在 original，供清除关键词时恢复全量。
 */
public final class ListFilterProxy {

    /** 最近一次全量列表（onChanged 上报） */
    private static volatile List<Object> sOriginal;
    /** 当前生效列表（过滤后或全量） */
    private static volatile List<Object> sActive;
    /** 当前搜索关键词（空 = 不过滤） */
    private static volatile String sKeyword = "";
    /** 过滤源监听（Instrument 设置，用于写回后通知刷新） */
    private static volatile Runnable sOnSwap;

    private ListFilterProxy() {
    }

    public static void setOnSwap(Runnable r) {
        sOnSwap = r;
    }

    /** onChanged 上报新列表（全量） */
    public static void onListChanged(List<Object> list) {
        sOriginal = list;
        sActive = list;
        applyFilter();
    }

    public static String getKeyword() {
        return sKeyword;
    }

    /** 设置关键词并立即重算；空串/空白 → 恢复全量 */
    public static void setKeyword(String kw) {
        sKeyword = kw == null ? "" : kw.trim();
        applyFilter();
    }

    public static void clearKeyword() {
        setKeyword("");
    }

    /** 当前应注入给原生列表的 List（全量原对象或过滤子集） */
    public static List<Object> activeList() {
        return sActive != null ? sActive : sOriginal;
    }

    /** 过滤后的条目数（无列表时为 0） */
    public static int filteredCount() {
        return sActive == null ? 0 : sActive.size();
    }

    /** 全量条目数（最近一次 onChanged 上报；无列表时为 0） */
    public static int totalCount() {
        return sOriginal == null ? 0 : sOriginal.size();
    }

    private static void applyFilter() {
        List<Object> src = sOriginal;
        if (src == null) {
            sActive = null;
            return;
        }
        if (sKeyword.isEmpty()) {
            sActive = src;
        } else {
            String kw = sKeyword;
            List<Object> out = new ArrayList<>();
            for (Object item : src) {
                // c 对象的文本字段：混淆后为字段 d（String）；反射取不到则跳过
                Object text = readField(item, "d");
                if (text != null && String.valueOf(text).contains(kw)) {
                    out.add(item);
                }
            }
            sActive = out;
        }
        Runnable r = sOnSwap;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object readField(Object obj, String name) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }
}