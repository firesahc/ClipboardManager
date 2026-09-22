package com.clipboard.enhance;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/**
 * 搜索缓冲状态单测：验证 Route X 缓冲区的累积/取出/丢弃语义。
 * ModuleState 无 Android 依赖，可纯 JVM 测试。
 */
public class ModuleStateTest {

    @Before
    public void setUp() {
        ModuleState.setSearchMode(false);
        ModuleState.resetSearchBuffer();
    }

    @Test
    public void append_take_clearsBuffer() {
        ModuleState.setSearchMode(true);
        ModuleState.appendSearchBuffer("he");
        ModuleState.appendSearchBuffer("llo");
        assertEquals("hello", ModuleState.takeSearchBuffer());
        assertEquals("", ModuleState.takeSearchBuffer());
        ModuleState.setSearchMode(false);
    }

    @Test
    public void exitSearchMode_discardsUnappliedBuffer() {
        ModuleState.setSearchMode(true);
        ModuleState.appendSearchBuffer("abc");
        ModuleState.setSearchMode(false);
        assertEquals("", ModuleState.takeSearchBuffer());
    }

    @Test
    public void enterSearchMode_startsEmpty() {
        ModuleState.resetSearchBuffer();
        ModuleState.setSearchMode(true);
        assertEquals("", ModuleState.peekSearchBuffer());
        ModuleState.setSearchMode(false);
    }
}
