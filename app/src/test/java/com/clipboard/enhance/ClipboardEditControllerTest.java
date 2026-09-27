package com.clipboard.enhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ClipboardEditController 纯逻辑单测（normalize/isBlank，无 Xposed/宿主依赖）。
 */
public class ClipboardEditControllerTest {

    @Test
    public void blankCheck_rejectsNullEmptyWhitespace() {
        assertTrue(ClipboardEditController.isBlankForEdit(null));
        assertTrue(ClipboardEditController.isBlankForEdit(""));
        assertTrue(ClipboardEditController.isBlankForEdit("   "));
        assertFalse(ClipboardEditController.isBlankForEdit("a"));
        assertFalse(ClipboardEditController.isBlankForEdit("  a  "));
    }

    @Test
    public void normalize_truncatesOverlongToHostLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            sb.append('x');
        }
        String out = ClipboardEditController.normalizeForSave(sb.toString());
        assertEquals(ClipboardEditController.MAX_CONTENT_LEN, out.length());
    }

    @Test
    public void normalize_keepsShortContentAsIs() {
        assertEquals("hello", ClipboardEditController.normalizeForSave("hello"));
        assertEquals("", ClipboardEditController.normalizeForSave(null));
    }

    @Test
    public void formulaRowH_matchesHostMath() {
        // 真机：cardH=417、C=69 → i10=116
        assertEquals(116, ClipboardEditController.formulaRowH(417, 69));
    }

    @Test
    public void formulaRowH_rejectsOutOfRange() {
        assertEquals(-1, ClipboardEditController.formulaRowH(0, 69));
        assertEquals(-1, ClipboardEditController.formulaRowH(-5, 69));
        assertEquals(-1, ClipboardEditController.formulaRowH(417, 417));
        assertEquals(-1, ClipboardEditController.formulaRowH(417, -2000));
    }
}
