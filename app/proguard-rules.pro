# Xposed 模块不混淆（Hook 类名需与 assets/xposed_init 一致）
-keep class com.clipboard.enhance.XposedInit { *; }
-keep class com.clipboard.enhance.** { *; }