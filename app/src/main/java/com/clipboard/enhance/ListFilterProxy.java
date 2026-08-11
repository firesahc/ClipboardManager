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

    /** 置顶列表容量：最多保留最近粘贴过的 N 条 */
    private static final int RECENT_CAPACITY = 20;

    /** 最近一次全量列表（onChanged 上报） */
    private static volatile List<Object> sOriginal;
    /** 当前生效列表（过滤后或全量） */
    private static volatile List<Object> sActive;
    /** 当前搜索关键词（空 = 不过滤） */
    private static volatile String sKeyword = "";
    /** 过滤源监听（Instrument 设置，用于写回后通知刷新） */
    private static volatile Runnable sOnSwap;
    /** 置顶开关：粘贴过的条目排到列表最上方（默认开启，设置页可关闭） */
    private static volatile boolean sPinRecentEnabled = true;
    /** 最近粘贴过的条目（LRU，索引 0 为最近一次粘贴；按对象引用去重） */
    private static final java.util.List<Object> sRecentInput = new java.util.ArrayList<>();

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

    /* ================= 置顶功能（粘贴过的条目排最上方） ================= */

    public static boolean isPinRecentEnabled() {
        return sPinRecentEnabled;
    }

    /** 设置置顶开关并立即重算生效列表 */
    public static void setPinRecentEnabled(boolean enabled) {
        sPinRecentEnabled = enabled;
        applyFilter();
    }

    /**
     * 记录一次剪贴板粘贴上屏：条目进入置顶列表头部（LRU），并立即重算生效列表。
     * 重算会触发 sOnSwap（Instrument 的 swapList）写回键盘列表并刷新；
     * 调用方（Instrument a(int) after）无需再手动重算，显式 swapList 仅为
     * sOnSwap 未设置时的兜底，幂等无害。
     * 引用去重：同一对象重复输入只提升到头部，不产生重复项。
     */
    public static void markInput(Object item) {
        if (item == null) {
            return;
        }
        removeByRef(sRecentInput, item);
        sRecentInput.add(0, item);
        while (sRecentInput.size() > RECENT_CAPACITY) {
            sRecentInput.remove(sRecentInput.size() - 1);
        }
        applyFilter();
    }

    /** 清空置顶记录（测试用） */
    public static void clearRecentInput() {
        sRecentInput.clear();
    }

    public static String getKeyword() {
        return sKeyword;
    }

    /** 是否处于筛选态（关键词非空） */
    public static boolean isFiltering() {
        return sKeyword.length() > 0;
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
            sActive = pinRecent(src);
        } else {
            String kw = sKeyword;
            List<Object> out = new ArrayList<>();
            for (Object item : pinRecent(src)) {
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

    /**
     * 置顶重排：开关开启时，把最近粘贴过的条目（LRU 顺序，最近的在最前）移动到列表头部；
     * 其余条目保持原始相对顺序。关闭或记录为空时原样返回（不复制，保持引用同一性，
     * 与「未过滤时 activeList()==original」的既有约定兼容）。
     */
    private static List<Object> pinRecent(List<Object> src) {
        if (!sPinRecentEnabled || sRecentInput.isEmpty()) {
            return src;
        }
        List<Object> out = new ArrayList<>(src.size());
        for (Object recent : sRecentInput) {
            if (containsByRef(src, recent) && !containsByRef(out, recent)) {
                out.add(recent);
            }
        }
        for (Object item : src) {
            if (!containsByRef(out, item)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 按对象引用判断列表中是否包含目标（c 对象未重写 equals，避免内容碰撞误判） */
    private static boolean containsByRef(List<Object> list, Object target) {
        for (Object o : list) {
            if (o == target) {
                return true;
            }
        }
        return false;
    }

    /** 按对象引用从列表中移除首个匹配项 */
    private static void removeByRef(List<Object> list, Object target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                list.remove(i);
                return;
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