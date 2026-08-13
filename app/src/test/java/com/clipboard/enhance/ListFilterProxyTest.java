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

    /** 模拟剪贴板条目（c 对象），仅含文本字段 d */
    private static class FakeItem {
        private final String d;

        FakeItem(String d) {
            this.d = d;
        }
    }

    private static FakeItem item(String text) {
        return new FakeItem(text);
    }

    @Before
    public void setUp() {
        // 重置静态状态：清空关键词，注入空列表（等价于无列表基线）
        ListFilterProxy.clearKeyword();
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
}
