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
        // 重置静态状态：清空关键词、置顶记录并恢复默认开关，注入空列表（等价于无列表基线）
        ListFilterProxy.clearKeyword();
        ListFilterProxy.clearRecentInput();
        ListFilterProxy.setPinRecentEnabled(true);
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

    /* ================= 置顶功能（输入过的条目排最上方） ================= */

    @Test
    public void markInput_movesItemToFront() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"), item("c"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.markInput(full.get(1));

        List<Object> active = ListFilterProxy.activeList();
        assertEquals(3, active.size());
        assertSame(full.get(1), active.get(0)); // b 置顶
        assertSame(full.get(0), active.get(1)); // 其余保持原顺序
        assertSame(full.get(2), active.get(2));
    }

    @Test
    public void markInput_recentOrder_isLifo() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"), item("c"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.markInput(full.get(0));
        ListFilterProxy.markInput(full.get(2));

        List<Object> active = ListFilterProxy.activeList();
        assertSame(full.get(2), active.get(0)); // 最近输入的 c 在最前
        assertSame(full.get(0), active.get(1));
        assertSame(full.get(1), active.get(2));
    }

    @Test
    public void markInput_repeat_doesNotDuplicate() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"), item("c"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.markInput(full.get(1));
        ListFilterProxy.markInput(full.get(1)); // 再次输入同一对象：只提升不重复

        List<Object> active = ListFilterProxy.activeList();
        assertEquals(3, active.size());
        assertSame(full.get(1), active.get(0));
    }

    @Test
    public void markInput_null_isIgnored() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.markInput(null);

        assertSame(full, ListFilterProxy.activeList()); // 无重排，引用同一性保持
    }

    @Test
    public void markInput_capacity_evictsOldest() {
        List<Object> full = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            full.add(item("x" + i));
        }
        ListFilterProxy.onListChanged(full);
        // 输入 x0..x24，共 25 条 > 容量 20 → x0..x4 被挤出
        for (int i = 0; i < 25; i++) {
            ListFilterProxy.markInput(full.get(i));
        }

        List<Object> active = ListFilterProxy.activeList();
        assertEquals(25, active.size());
        assertSame(full.get(24), active.get(0));  // 最近输入在最前
        assertSame(full.get(5), active.get(19));  // 保留的最旧置顶项在置顶区末尾
        // 被挤出的 x0..x4 回到置顶区之后的原始相对位置
        for (int i = 0; i < 5; i++) {
            assertSame(full.get(i), active.get(20 + i));
        }
    }

    @Test
    public void markInput_disabled_keepsOriginalOrder() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"), item("c"));
        ListFilterProxy.setPinRecentEnabled(false);
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.markInput(full.get(1));

        assertSame(full, ListFilterProxy.activeList()); // 关闭时原样返回
    }

    @Test
    public void setPinRecentEnabled_appliesImmediately() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"), item("c"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.markInput(full.get(2));
        ListFilterProxy.setPinRecentEnabled(false);

        assertSame(full, ListFilterProxy.activeList()); // 关闭后立即恢复原始顺序

        ListFilterProxy.setPinRecentEnabled(true);
        assertSame(full.get(2), ListFilterProxy.activeList().get(0)); // 重新开启后置顶恢复
    }

    @Test
    public void markInput_thenFilter_keepsPinOrderWithinFiltered() {
        List<Object> full = Arrays.<Object>asList(item("aa"), item("bb"), item("ab"), item("ba"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.markInput(full.get(2)); // "ab"
        ListFilterProxy.markInput(full.get(0)); // "aa"
        ListFilterProxy.setKeyword("a");

        // 置顶顺序：aa, ab；过滤 "a" 后：aa, ab, ba（bb 不含 a）
        List<Object> active = ListFilterProxy.activeList();
        assertEquals(3, active.size());
        assertSame(full.get(0), active.get(0));
        assertSame(full.get(2), active.get(1));
        assertSame(full.get(3), active.get(2));
    }

    @Test
    public void markInput_itemFromOtherList_isSkipped() {
        List<Object> full = Arrays.<Object>asList(item("a"), item("b"));
        ListFilterProxy.onListChanged(full);
        ListFilterProxy.markInput(item("external")); // 不在当前列表的引用：置顶区无此对象

        assertEquals(2, ListFilterProxy.filteredCount());
        assertEquals(2, ListFilterProxy.activeList().size());
        assertSame(full.get(0), ListFilterProxy.activeList().get(0));
    }
}
