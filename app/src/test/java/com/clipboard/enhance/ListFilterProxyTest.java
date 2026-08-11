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
        // 重置静态状态：清空关键词并注入空列表（等价于无列表基线）
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
}
