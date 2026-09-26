package com.clipboard.enhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ListFilterProxy 纯逻辑单测（无 UI/Xposed 依赖）。
 *
 * 过滤对象模拟 c 类：字段 d（String）为条目文本，与 ListFilterProxy 反射读取的
 * 字段名保持一致。ListFilterProxy 为静态状态，每个用例在 @Before 中重建基线。
 *
 * 置顶功能不在本类测试：已改为调用宿主 ClipboardKeyboard.O(String) 的插入链路
 * （宿主去重 + 更新时间戳 + 排序），由宿主逻辑保证，本类只管搜索过滤。
 */
public class ListFilterProxyTest {

    /** 模拟剪贴板条目（c 对象）：d=文本，c=时间戳，与 ListFilterProxy 反射字段名一致 */
    private static class FakeItem {
        private final String d;
        private final long c;

        FakeItem(String d) {
            this(d, 0L);
        }

        FakeItem(String d, long c) {
            this.d = d;
            this.c = c;
        }
    }

    private static FakeItem item(String text) {
        return new FakeItem(text);
    }

    private static FakeItem item(String text, long time) {
        return new FakeItem(text, time);
    }

    @Before
    public void setUp() {
        // 重置静态状态：清空关键词，注入空列表（等价于无列表基线），复位排序与计数源
        ListFilterProxy.clearKeyword();
        ListFilterProxy.setSortKey(ListFilterProxy.SortKey.TIME);
        ListFilterProxy.setSortAsc(false);
        ListFilterProxy.setCountProvider(null);
        ListFilterProxy.onListChanged(new ArrayList<Object>());
    }

    @Test
    public void onListChanged_setsActiveToFullList() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"));
        ListFilterProxy.onListChanged(full);

        assertSame(full, ListFilterProxy.activeList());
        assertEquals(2, ListFilterProxy.totalCount());
        assertEquals(2, ListFilterProxy.filteredCount());
        assertFalse(ListFilterProxy.isFiltering());
    }

    @Test
    public void setKeyword_filtersBySubstring() {
        List<Object> full = Arrays.<Object>asList(item("hello world"), item("goodbye"), item("HELLO again"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setKeyword("hello");

        assertTrue(ListFilterProxy.isFiltering());
        assertEquals(1, ListFilterProxy.filteredCount());
        assertEquals(3, ListFilterProxy.totalCount());
        List<Object> active = ListFilterProxy.activeList();
        assertEquals(1, active.size());
        assertSame(full.get(0), active.get(0)); // 引用不变，与逆向约定一致
    }

    @Test
    public void setKeyword_matchesMiddleOfText() {
        List<Object> full = Arrays.<Object>asList(item("prefix-target-suffix"), item("other"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setKeyword("target");

        assertEquals(1, ListFilterProxy.filteredCount());
        assertSame(full.get(0), ListFilterProxy.activeList().get(0));
    }

    @Test
    public void setKeyword_noMatch_yieldsEmptyActiveList() {
        List<Object> full = Arrays.<Object>asList(item("aaa"), item("bbb"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setKeyword("zzz");

        assertEquals(0, ListFilterProxy.filteredCount());
        assertTrue(ListFilterProxy.activeList().isEmpty());
    }

    @Test
    public void setKeyword_trimsWhitespace() {
        List<Object> full = Arrays.<Object>asList(item("abc"), item("xyz"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setKeyword("  abc  ");

        assertEquals(1, ListFilterProxy.filteredCount());
        assertSame(full.get(0), ListFilterProxy.activeList().get(0));
    }

    @Test
    public void setKeyword_blankKeyword_isNotFiltering() {
        List<Object> full = Arrays.<Object>asList(item("abc"), item("xyz"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setKeyword("   ");

        assertFalse(ListFilterProxy.isFiltering());
        assertSame(full, ListFilterProxy.activeList());
    }

    @Test
    public void setKeyword_nullKeyword_isNotFiltering() {
        List<Object> full = Arrays.<Object>asList(item("abc"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setKeyword(null);

        assertFalse(ListFilterProxy.isFiltering());
        assertEquals(1, ListFilterProxy.filteredCount());
    }

    @Test
    public void clearKeyword_restoresFullList() {
        List<Object> full = Arrays.<Object>asList(item("aaa"), item("bbb"), item("ccc"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setKeyword("b");

        assertEquals(1, ListFilterProxy.filteredCount());

        ListFilterProxy.clearKeyword();

        assertFalse(ListFilterProxy.isFiltering());
        assertSame(full, ListFilterProxy.activeList());
        assertEquals(3, ListFilterProxy.filteredCount());
    }

    @Test
    public void onListChanged_afterFiltering_reappliesKeyword() {
        List<Object> full1 = Arrays.<Object>asList(item("alpha"), item("cut"));
        ListFilterProxy.onListChanged(full1);
        ListFilterProxy.setKeyword("a");
        assertEquals(1, ListFilterProxy.filteredCount());

        // 列表刷新（新引用）后关键词仍生效
        List<Object> full2 = Arrays.<Object>asList(item("alpha"), item("arena"), item("cut"));
        ListFilterProxy.onListChanged(full2);

        assertEquals(2, ListFilterProxy.filteredCount());
        assertFalse(ListFilterProxy.activeList() == full2); // 过滤子集而非全量
    }

    @Test
    public void itemsWithoutTextField_areSkippedDuringFiltering() {
        List<Object> full = new ArrayList<>();
        full.add(item("visible"));
        full.add(new Object()); // 无 d 字段：反射失败应被跳过而非崩溃
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setKeyword("visible");

        assertEquals(1, ListFilterProxy.filteredCount());
        assertSame(full.get(0), ListFilterProxy.activeList().get(0));
    }

    @Test
    public void counts_areZero_beforeAnyList() {
        ListFilterProxy.clearKeyword();
        ListFilterProxy.onListChanged(null);

        assertNull(ListFilterProxy.activeList());
        assertEquals(0, ListFilterProxy.totalCount());
        assertEquals(0, ListFilterProxy.filteredCount());
    }

    /* ================= 宿主自主排序契约（改造后：模块不干预排序） =================
       置顶由宿主 ClipboardKeyboard.O(String) 链路完成（按内容去重 + 更新时间戳 +
       orderDesc(Time) 倒序查询 + LiveData 上报），宿主上报的新列表顺序即最终顺序。
       本类只保证：
       1) 无过滤时 activeList 与宿主列表引用同一、顺序原样（新复制的永远在最上）；
       2) 过滤时遍历顺序与宿主一致（新复制的若命中关键词仍排最前）；
       3) 粘贴后宿主把该条目提到最前的上报结果，模块原样接受、不重排。 */

    @Test
    public void onListChanged_newCopyAtTop_keepsHostOrder() {
        List<Object> before = Arrays.<Object>asList(item("old-1"), item("old-2"));
        ListFilterProxy.onListChanged(before);

        // 新复制后宿主上报新列表：新复制条目时间戳最新，宿主排在最前（index 0）
        List<Object> fresh = new ArrayList<>();
        fresh.add(item("new-copied"));
        fresh.addAll(before);
        ListFilterProxy.onListChanged(fresh);

        List<Object> active = ListFilterProxy.activeList();
        assertEquals(3, active.size());
        assertSame(fresh, active);               // 无过滤：引用同一，模块零干预
        assertSame(fresh.get(0), active.get(0)); // 新复制仍在所有旧条目最上面
        assertSame(fresh.get(1), active.get(1)); // 旧条目相对顺序原样
    }

    @Test
    public void onListChanged_pastedItemRehostedAtTop_keepsHostOrder() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"), item("c"));
        ListFilterProxy.onListChanged(full);

        // 粘贴后模块调宿主 O()：宿主按内容去重命中 b 并更新时间戳，
        // 随后上报新列表把 b 提到最前（index 0）
        List<Object> rehosted = Arrays.<Object>asList(full.get(1), full.get(0), full.get(2));
        ListFilterProxy.onListChanged(rehosted);

        List<Object> active = ListFilterProxy.activeList();
        assertSame(rehosted, active);            // 模块不重排，原样接受宿主排序结果
        assertSame(full.get(1), active.get(0));  // 刚粘贴的 b 由宿主置顶到最上
    }

    @Test
    public void filter_preservesHostOrder_newCopyStaysFirstWhenMatched() {
        List<Object> fresh = new ArrayList<>();
        fresh.add(item("new-match"));   // 新复制的：宿主排最前
        fresh.add(item("old-a"));
        fresh.add(item("old-match"));
        ListFilterProxy.onListChanged(fresh);
        ListFilterProxy.setKeyword("match");

        List<Object> active = ListFilterProxy.activeList();
        assertEquals(2, active.size());
        assertSame(fresh.get(0), active.get(0)); // 新复制的命中关键词仍排最前
        assertSame(fresh.get(2), active.get(1)); // 旧条目保持宿主相对顺序
    }

    /* ================= 排序策略（纯视图层，不写库） =================
       默认时间降序 = 宿主 orderDesc(Time) 顺序，无过滤时保持引用同一；
       其他字段/方向返回排序拷贝，sOriginal 顺序永不被改。 */

    @Test
    public void defaultSort_returnsOriginalReference() {
        List<Object> full = Arrays.<Object>asList(item("a", 3), item("b", 1));
        ListFilterProxy.onListChanged(full);

        assertSame(full, ListFilterProxy.activeList());
    }

    @Test
    public void sortKey_backToTimeDesc_restoresOriginalReference() {
        List<Object> full = Arrays.<Object>asList(item("a", 3), item("b", 1));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.setSortKey(ListFilterProxy.SortKey.COUNT);
        assertFalse(ListFilterProxy.activeList() == full); // 拷贝已产生

        ListFilterProxy.setSortKey(ListFilterProxy.SortKey.TIME);
        assertSame(full, ListFilterProxy.activeList()); // 回到默认即原引用
    }

    @Test
    public void sortByCountDesc_ordersByProvider() {
        FakeItem a = item("aaa");
        FakeItem b = item("bbb");
        FakeItem c = item("ccc");
        List<Object> full = Arrays.<Object>asList(a, b, c);
        ListFilterProxy.onListChanged(full);
        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        counts.put("aaa", 5);
        counts.put("bbb", 1);
        counts.put("ccc", 3);
        ListFilterProxy.setCountProvider(new ListFilterProxy.CountProvider() {
            @Override
            public int getCount(String content) {
                Integer v = counts.get(content);
                return v == null ? 0 : v;
            }
        });
        ListFilterProxy.setSortKey(ListFilterProxy.SortKey.COUNT);

        List<Object> active = ListFilterProxy.activeList();
        assertEquals(3, active.size());
        assertSame(a, active.get(0));
        assertSame(c, active.get(1));
        assertSame(b, active.get(2));
        // 原列表顺序不动（排序只产生拷贝）
        assertEquals("aaa", ((FakeItem) full.get(0)).d);
    }

    @Test
    public void sortByCountAsc_reversesDirection() {
        List<Object> full = Arrays.<Object>asList(item("aaa"), item("bbb"), item("ccc"));
        ListFilterProxy.onListChanged(full);
        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        counts.put("aaa", 5);
        counts.put("bbb", 1);
        counts.put("ccc", 3);
        ListFilterProxy.setCountProvider(new ListFilterProxy.CountProvider() {
            @Override
            public int getCount(String content) {
                Integer v = counts.get(content);
                return v == null ? 0 : v;
            }
        });
        ListFilterProxy.setSortKey(ListFilterProxy.SortKey.COUNT);
        ListFilterProxy.setSortAsc(true);

        List<Object> active = ListFilterProxy.activeList();
        assertEquals("bbb", ((FakeItem) active.get(0)).d);
        assertEquals("ccc", ((FakeItem) active.get(1)).d);
        assertEquals("aaa", ((FakeItem) active.get(2)).d);
    }

    @Test
    public void sortByLengthDesc_ordersByTextLength() {
        FakeItem s = item("x");
        FakeItem m = item("xxx");
        FakeItem l = item("xxxxx");
        ListFilterProxy.onListChanged(Arrays.<Object>asList(s, m, l));
        ListFilterProxy.setSortKey(ListFilterProxy.SortKey.LENGTH);

        List<Object> active = ListFilterProxy.activeList();
        assertSame(l, active.get(0));
        assertSame(m, active.get(1));
        assertSame(s, active.get(2));
    }

    @Test
    public void sortByTimeAsc_ordersByTimestamp() {
        FakeItem t3 = item("t3", 300);
        FakeItem t1 = item("t1", 100);
        FakeItem t2 = item("t2", 200);
        ListFilterProxy.onListChanged(Arrays.<Object>asList(t3, t1, t2));
        ListFilterProxy.setSortAsc(true);

        List<Object> active = ListFilterProxy.activeList();
        assertSame(t1, active.get(0));
        assertSame(t2, active.get(1));
        assertSame(t3, active.get(2));
    }

    @Test
    public void filter_thenSort_appliesBoth() {
        FakeItem a = item("match-aaa", 100);
        FakeItem b = item("other", 300);
        FakeItem c = item("match-c", 200);
        ListFilterProxy.onListChanged(Arrays.<Object>asList(a, b, c));
        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        counts.put("match-aaa", 1);
        counts.put("match-c", 9);
        ListFilterProxy.setCountProvider(new ListFilterProxy.CountProvider() {
            @Override
            public int getCount(String content) {
                Integer v = counts.get(content);
                return v == null ? 0 : v;
            }
        });
        ListFilterProxy.setKeyword("match");
        ListFilterProxy.setSortKey(ListFilterProxy.SortKey.COUNT);

        List<Object> active = ListFilterProxy.activeList();
        assertEquals(2, active.size());
        assertSame(c, active.get(0)); // 过滤命中后按热度排
        assertSame(a, active.get(1));
    }

    @Test
    public void cycleSortKey_rotatesTimeCountLength() {
        assertEquals(ListFilterProxy.SortKey.TIME, ListFilterProxy.getSortKey());
        ListFilterProxy.cycleSortKey();
        assertEquals(ListFilterProxy.SortKey.COUNT, ListFilterProxy.getSortKey());
        ListFilterProxy.cycleSortKey();
        assertEquals(ListFilterProxy.SortKey.LENGTH, ListFilterProxy.getSortKey());
        ListFilterProxy.cycleSortKey();
        assertEquals(ListFilterProxy.SortKey.TIME, ListFilterProxy.getSortKey());
        assertEquals("时间", ListFilterProxy.sortKeyLabel());
        assertEquals("↓", ListFilterProxy.sortDirLabel());
    }

    @Test
    public void toggleSortDirection_flipsAsc() {
        assertFalse(ListFilterProxy.isSortAsc());
        ListFilterProxy.toggleSortDirection();
        assertTrue(ListFilterProxy.isSortAsc());
        assertEquals("↑", ListFilterProxy.sortDirLabel());
    }
}
