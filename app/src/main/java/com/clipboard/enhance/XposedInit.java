package com.clipboard.enhance;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。
 * 目标包：com.sohu.inputmethod.sogou（搜狗输入法及其渠道包）。
 */
public class XposedInit implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 匹配搜狗输入法主包 + 渠道包（xiaomi 等）
        if (lpparam.packageName == null
                || !lpparam.packageName.startsWith("com.sohu.inputmethod.sogou")) {
            return;
        }
        ClipboardKeyboardInstrument.init(lpparam.classLoader);
    }
}