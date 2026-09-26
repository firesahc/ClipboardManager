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
 * - 置顶功能不在此处实现：粘贴后由 Instrument 调用宿主 ClipboardKeyboard.O(String)
 *   （宿主按内容去重 + 更新时间戳 + orderDesc(Time) 排序 + LiveData 上报），
 *   排序完全由宿主负责，本类只管搜索过滤。
 * - 本类为纯逻辑，不持有写回回调：setKeyword/clearKeyword/onListChanged 只重算
 *   sActive，写回 M/j 由调用方显式调 KeyboardListHooks.swapList()（避免隐式回调环）。
 * - 排序同为纯视图策略：默认时间降序即宿主 orderDesc(Time) 顺序（此时无过滤不复制，
 *   保持引用同一）；热度经 CountProvider 注入（生产接 PasteCounter，单测注入假数据），
 *   长度/时间经反射读 d/c 字段；排序只产生新拷贝，永不改 sOriginal 顺序。
 */
public final class ListFilterProxy {

    /** 排序字段：时间 / 粘贴热度 / 内容长度（与宿主互不干扰的纯视图策略） */
    public enum SortKey {
        TIME, COUNT, LENGTH
    }

    /** 粘贴次数提供方（生产环境接 PasteCounter，单测注入假数据，保持本类纯逻辑） */
    public interface CountProvider {
        int getCount(String content);
    }

    /** 最近一次全量列表（onChanged 上报，恒为宿主时间倒序） */
    private static volatile List<Object> sOriginal;
    /** 当前生效列表（过滤后或全量，可能为排序拷贝） */
    private static volatile List<Object> sActive;
    /** 当前搜索关键词（空 = 不过滤） */
    private static volatile String sKeyword = "";
    /** 当前排序字段（默认时间，即宿主原生顺序） */
    private static volatile SortKey sSortKey = SortKey.TIME;
    /** 排序方向：false=降序（默认），true=升序 */
    private static volatile boolean sSortAsc = false;
    /** 粘贴次数提供方（null=全部按0处理，即不参与排序） */
    private static volatile CountProvider sCountProvider;

    private ListFilterProxy() {
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

    /** 当前排序字段 */
    public static SortKey getSortKey() {
        return sSortKey;
    }

    public static void setSortKey(SortKey key) {
        sSortKey = key == null ? SortKey.TIME : key;
        applyFilter();
    }

    /** 当前是否为升序（false=降序默认） */
    public static boolean isSortAsc() {
        return sSortAsc;
    }

    public static void setSortAsc(boolean asc) {
        sSortAsc = asc;
        applyFilter();
    }

    /** 候选栏点击：字段循环 时间→热度→长度→时间 */
    public static void cycleSortKey() {
        SortKey cur = sSortKey;
        setSortKey(cur == SortKey.TIME ? SortKey.COUNT
                : cur == SortKey.COUNT ? SortKey.LENGTH : SortKey.TIME);
    }

    /** 候选栏点击：翻转正倒序 */
    public static void toggleSortDirection() {
        setSortAsc(!sSortAsc);
    }

    public static void setCountProvider(CountProvider provider) {
        sCountProvider = provider;
    }

    /** 排序字段文案（候选栏按钮用） */
    public static String sortKeyLabel() {
        SortKey k = sSortKey;
        return k == SortKey.COUNT ? "热度" : k == SortKey.LENGTH ? "长度" : "时间";
    }

    /** 排序方向文案（候选栏按钮用）：降序↓ / 升序↑ */
    public static String sortDirLabel() {
        return sSortAsc ? "↑" : "↓";
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
        List<Object> base;
        if (sKeyword.isEmpty()) {
            // 无关键词：宿主顺序即时间倒序，默认排序下保持引用同一（swapList 原地同步安全）
            base = src;
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
            base = out;
        }
        sActive = applySort(base, base == src);
    }

    /**
     * 排序策略（纯视图层，不写库）：
     * 默认（时间降序）且无过滤时返回原引用，保持与宿主上报顺序/引用同一；
     * 其他情况返回排序拷贝，绝不原地重排 sOriginal（恢复全量时仍是宿主顺序）。
     */
    private static List<Object> applySort(List<Object> base, boolean isOriginalRef) {
        if (sSortKey == SortKey.TIME && !sSortAsc) {
            return base; // 宿主 orderDesc(Time) 即时间降序，原样接受
        }
        List<Object> sorted = new ArrayList<>(base);
        final SortKey key = sSortKey;
        final boolean asc = sSortAsc;
        final CountProvider provider = sCountProvider;
        java.util.Collections.sort(sorted, new java.util.Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                int r;
                if (key == SortKey.COUNT) {
                    r = Integer.compare(countOf(a, provider), countOf(b, provider));
                } else if (key == SortKey.LENGTH) {
                    r = Integer.compare(lengthOf(a), lengthOf(b));
                } else {
                    r = Long.compare(timeOf(a), timeOf(b));
                }
                if (r == 0) {
                    // 平局按时间降序兜底（新复制优先），与宿主默认观感一致
                    r = Long.compare(timeOf(b), timeOf(a));
                    return r;
                }
                return asc ? r : -r;
            }
        });
        return sorted;
    }

    private static String textOf(Object item) {
        Object text = readField(item, "d");
        return text == null ? null : String.valueOf(text);
    }

    private static int countOf(Object item, CountProvider provider) {
        if (provider == null) {
            return 0;
        }
        try {
            String t = textOf(item);
            return t == null ? -1 : provider.getCount(t);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int lengthOf(Object item) {
        String t = textOf(item);
        return t == null ? -1 : t.length();
    }

    private static long timeOf(Object item) {
        try {
            java.lang.reflect.Field f = item.getClass().getDeclaredField("c");
            f.setAccessible(true);
            Object v = f.get(item);
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
        } catch (Throwable ignored) {
        }
        return Long.MIN_VALUE; // 无时间字段的沉底
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