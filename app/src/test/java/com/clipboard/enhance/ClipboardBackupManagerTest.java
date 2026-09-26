package com.clipboard.enhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ClipboardBackupManager 纯函数单测（JSON 编解码，无宿主/IO 依赖）。
 */
public class ClipboardBackupManagerTest {

    @Test
    public void roundTrip_preservesEntries() throws Exception {
        List<ClipboardBackupManager.Entry> src = Arrays.asList(
                new ClipboardBackupManager.Entry(100L, "hello", Arrays.asList(100L, 200L)),
                new ClipboardBackupManager.Entry(300L, "world", new ArrayList<Long>()));
        String json = ClipboardBackupManager.toJson(src);
        List<ClipboardBackupManager.Entry> back = ClipboardBackupManager.fromJson(json);

        assertEquals(2, back.size());
        assertEquals(100L, back.get(0).time);
        assertEquals("hello", back.get(0).content);
        assertEquals(Arrays.asList(100L, 200L), back.get(0).times);
        assertEquals(300L, back.get(1).time);
        assertEquals("world", back.get(1).content);
        assertTrue(back.get(1).times.isEmpty());
    }

    @Test
    public void toJson_skipsNullEntries() throws Exception {
        List<ClipboardBackupManager.Entry> src = new ArrayList<>();
        src.add(null);
        src.add(new ClipboardBackupManager.Entry(1L, null, null));
        src.add(new ClipboardBackupManager.Entry(2L, "ok", null));
        List<ClipboardBackupManager.Entry> back =
                ClipboardBackupManager.fromJson(ClipboardBackupManager.toJson(src));

        assertEquals(1, back.size());
        assertEquals("ok", back.get(0).content);
    }

    @Test
    public void fromJson_emptyArray_yieldsEmptyList() throws Exception {
        assertTrue(ClipboardBackupManager.fromJson("[]").isEmpty());
    }

    @Test
    public void roundTrip_preservesSpecialChars() throws Exception {
        String tricky = "引号\"反斜\\换行\n回车\r制表\t表情😀结束";
        List<ClipboardBackupManager.Entry> src = new ArrayList<>();
        src.add(new ClipboardBackupManager.Entry(7L, tricky, new ArrayList<Long>()));
        List<ClipboardBackupManager.Entry> back =
                ClipboardBackupManager.fromJson(ClipboardBackupManager.toJson(src));

        assertEquals(1, back.size());
        assertEquals(tricky, back.get(0).content);
    }

    @Test(expected = Exception.class)
    public void fromJson_malformed_throws() throws Exception {
        ClipboardBackupManager.fromJson("{not json");
    }
}
