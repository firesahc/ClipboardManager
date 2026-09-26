package com.clipboard.enhance;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 剪贴板备份恢复领域：DAO ↔ JSON ↔ 文件（SAF 自选位置为主）。
 *
 * 逆向事实：
 * - 表 {@code CLIPBOARD_ITEM} 列：{@code TIME/CONTENT/TIME_ARRAY}（+3 个 extra，
 *   宿主读写只用前四列，备份范围以此为界）。
 * - 旧版 JSON 格式（{@code p.M}）：{@code [{time, content, time_array[]}]}，
 *   导出沿用同键，保证与宿主旧备份互认。
 * - 读链恒 {@code orderDesc(Time)}，恢复后顺序天然正确，无需调序。
 *
 * 职责边界：JSON 编解码为纯函数（单测覆盖）；宿主对象读写走反射桥；
 * 文件 IO 全在后台线程；SAF 无权限需求。导入策略固定合并+bump
 * （重复内容更新时间，不删现有），与 {@code p.H} 去重语义一致。
 */
public final class ClipboardBackupManager {

    /** JSON 键（与宿主 p.M 旧格式一致） */
    static final String KEY_TIME = "time";
    static final String KEY_CONTENT = "content";
    static final String KEY_TIME_ARRAY = "time_array";

    private ClipboardBackupManager() {
    }

    /** 与宿主无关的条目快照（JSON 编解码的唯一载体，可单测） */
    public static final class Entry {
        public final long time;
        public final String content;
        public final List<Long> times;

        public Entry(long time, String content, List<Long> times) {
            this.time = time;
            this.content = content;
            this.times = times == null ? new ArrayList<Long>() : times;
        }
    }

    /* ================= 1. 纯函数：JSON 编解码 =================
       不依赖 org.json（android.jar 单测桩不可用）：格式固定为
       [{time,content,time_array[]}]，手写极简编解码，真机/单测同行为。 */

    static String toJson(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (Entry e : entries) {
            if (e == null || e.content == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"time\":").append(e.time).append(",\"content\":");
            escapeInto(sb, e.content);
            sb.append(",\"time_array\":[");
            boolean tf = true;
            for (Long t : e.times) {
                if (t == null) {
                    continue;
                }
                if (!tf) {
                    sb.append(',');
                }
                tf = false;
                sb.append(t.longValue());
            }
            sb.append("]}");
        }
        sb.append(']');
        return sb.toString();
    }

    static List<Entry> fromJson(String json) throws Exception {
        if (json == null || json.trim().length() == 0) {
            return new ArrayList<>();
        }
        return new JsonParser(json).parseArray();
    }

    private static void escapeInto(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    /** 仅够解析 toJson 产出形状的极简解析器（对象键顺序无关，容忍空白） */
    private static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            this.s = s == null ? "" : s;
        }

        List<Entry> parseArray() throws Exception {
            skipWs();
            expect('[');
            List<Entry> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(parseObject());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return out;
                } else {
                    throw new Exception("bad array at " + pos);
                }
            }
        }

        private Entry parseObject() throws Exception {
            skipWs();
            expect('{');
            long time = 0;
            String content = null;
            List<Long> times = new ArrayList<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return new Entry(time, content, times);
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                if (KEY_TIME.equals(key)) {
                    time = parseLong();
                } else if (KEY_CONTENT.equals(key)) {
                    content = parseString();
                } else if (KEY_TIME_ARRAY.equals(key)) {
                    times = parseLongArray();
                } else {
                    skipValue();
                }
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    if (content == null) {
                        throw new Exception("missing content");
                    }
                    return new Entry(time, content, times);
                } else {
                    throw new Exception("bad object at " + pos);
                }
            }
        }

        private List<Long> parseLongArray() throws Exception {
            List<Long> out = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                skipWs();
                out.add(parseLong());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return out;
                } else {
                    throw new Exception("bad array at " + pos);
                }
            }
        }

        private long parseLong() throws Exception {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw new Exception("bad number at " + pos);
            }
            try {
                return Long.parseLong(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new Exception("bad number at " + start);
            }
        }

        private String parseString() throws Exception {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new Exception("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new Exception("bad escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new Exception("bad unicode");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new Exception("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void skipValue() throws Exception {
            skipWs();
            char c = peek();
            if (c == '"') {
                parseString();
            } else if (c == '{') {
                int depth = 0;
                boolean inStr = false;
                while (pos < s.length()) {
                    char x = s.charAt(pos++);
                    if (inStr) {
                        if (x == '\\') {
                            pos++;
                        } else if (x == '"') {
                            inStr = false;
                        }
                    } else if (x == '"') {
                        inStr = true;
                    } else if (x == '{') {
                        depth++;
                    } else if (x == '}') {
                        depth--;
                        if (depth == 0) {
                            return;
                        }
                    }
                }
                throw new Exception("bad value");
            } else if (c == '[') {
                parseLongArray();
            } else {
                while (pos < s.length() && ",}]".indexOf(s.charAt(pos)) < 0) {
                    pos++;
                }
            }
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private char peek() throws Exception {
            if (pos >= s.length()) {
                throw new Exception("unexpected end");
            }
            return s.charAt(pos);
        }

        private void expect(char c) throws Exception {
            skipWs();
            if (peek() != c) {
                throw new Exception("expected " + c + " at " + pos);
            }
            pos++;
        }
    }

    /* ================= 2. 导出 ================= */

    /** SAF 导出：快照全量 → JSON → 指定 Uri（后台线程） */
    public static void exportToUri(final Context ctx, final Uri uri) {
        if (ctx == null || uri == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Entry> all = snapshot();
                    if (all == null) {
                        toastOnMain(ctx, "导出失败：数据库不可用");
                        return;
                    }
                    String json = toJson(all);
                    ContentResolver cr = ctx.getContentResolver();
                    OutputStream os = cr.openOutputStream(uri);
                    if (os == null) {
                        toastOnMain(ctx, "导出失败：无法写入文件");
                        return;
                    }
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    os.write(bytes);
                    os.flush();
                    os.close();
                    toastOnMain(ctx, "已导出 " + all.size() + " 条");
                    XposedBridge.log(HookUtil.LOG_TAG + "exported " + all.size()
                            + " items, bytes=" + bytes.length);
                } catch (Throwable t) {
                    XposedBridge.log(HookUtil.LOG_TAG + "export error: " + t);
                    toastOnMain(ctx, "导出失败");
                }
            }
        }, "clip-export").start();
    }

    /**
     * SAF 不可用时的导出降级：经 MediaStore 落 Download（免权限写自有文件）。
     * 仅导出有兜底；导入必须用户点选，无固定路径兜底。
     */
    public static void exportToDownloadFallback(final Context ctx) {
        if (ctx == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < 29) {
            toastOnMain(ctx, "当前系统无文件选择器，无法导出");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Entry> all = snapshot();
                    if (all == null) {
                        toastOnMain(ctx, "导出失败：数据库不可用");
                        return;
                    }
                    String json = toJson(all);
                    String name = "clipboard-backup-" + System.currentTimeMillis() + ".json";
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    values.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS);
                    ContentResolver cr = ctx.getContentResolver();
                    Uri uri = cr.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        toastOnMain(ctx, "导出失败");
                        return;
                    }
                    OutputStream os = cr.openOutputStream(uri);
                    if (os == null) {
                        toastOnMain(ctx, "导出失败：无法写入文件");
                        return;
                    }
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();
                    toastOnMain(ctx, "已导出 " + all.size() + " 条到 Download/" + name);
                } catch (Throwable t) {
                    XposedBridge.log(HookUtil.LOG_TAG + "export fallback error: " + t);
                    toastOnMain(ctx, "导出失败");
                }
            }
        }, "clip-export").start();
    }

    /** 全量快照：orderDesc(Time)，与宿主 G() 读链同序 */
    private static List<Entry> snapshot() {
        Object dao = ClipboardRepoAccessor.dao();
        if (dao == null) {
            return null;
        }
        try {
            Class<?> props = XposedHelpers.findClass(
                    SogouHostContract.CLIPBOARD_DAO_PROPS, ModuleState.classLoader());
            Object timeProp = XposedHelpers.getStaticObjectField(props, "Time");
            Object qb = XposedHelpers.callMethod(dao, "queryBuilder");
            Object ordered = XposedHelpers.callMethod(qb, "orderDesc", timeProp);
            Object listObj = XposedHelpers.callMethod(ordered, "list");
            if (!(listObj instanceof List)) {
                return new ArrayList<>();
            }
            List<Entry> out = new ArrayList<>(((List<?>) listObj).size());
            for (Object o : (List<?>) listObj) {
                try {
                    Object t = XposedHelpers.getObjectField(
                            o, SogouHostContract.FIELD_ITEM_TEXT);
                    if (t == null) {
                        continue;
                    }
                    long time = XposedHelpers.getLongField(
                            o, SogouHostContract.FIELD_ITEM_TIME);
                    Object e = XposedHelpers.getObjectField(o, "e");
                    List<Long> times = new ArrayList<>();
                    if (e instanceof List) {
                        for (Object v : (List<?>) e) {
                            if (v instanceof Number) {
                                times.add(((Number) v).longValue());
                            }
                        }
                    }
                    out.add(new Entry(time, String.valueOf(t), times));
                } catch (Throwable ignored) {
                }
            }
            return out;
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "snapshot failed: " + t);
            return null;
        }
    }

    /* ================= 3. 导入（合并+bump） ================= */

    /** SAF 导入：解析 JSON → 与现有按内容合并 → 一次批量写 → 原生刷新 */
    public static void importFromUri(final Context ctx, final Uri uri) {
        if (ctx == null || uri == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentResolver cr = ctx.getContentResolver();
                    InputStream is = cr.openInputStream(uri);
                    if (is == null) {
                        toastOnMain(ctx, "导入失败：无法读取文件");
                        return;
                    }
                    byte[] buf = new byte[8192];
                    StringBuilder sb = new StringBuilder();
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                    is.close();
                    List<Entry> imported = fromJson(sb.toString());
                    if (imported.isEmpty()) {
                        toastOnMain(ctx, "文件中没有可导入的条目");
                        return;
                    }
                    int[] result = mergeAll(imported);
                    if (result == null) {
                        toastOnMain(ctx, "导入失败：数据库不可用");
                        return;
                    }
                    refreshList();
                    toastOnMain(ctx, "导入完成：新增 " + result[0] + "，更新 " + result[1]);
                    XposedBridge.log(HookUtil.LOG_TAG + "imported new=" + result[0]
                            + " updated=" + result[1]);
                } catch (Throwable t) {
                    XposedBridge.log(HookUtil.LOG_TAG + "import error: " + t);
                    toastOnMain(ctx, "导入失败：文件格式错误");
                }
            }
        }, "clip-import").start();
    }

    /**
     * 合并写：现有内容→对象建 map；命中则时间取最大+bump now+时间数组并集，
     * 未命中则新建实体；一次 {@code insertOrReplaceInTx} 落盘。
     *
     * @return {新增数, 更新数}，dao 不可用返回 null
     */
    private static int[] mergeAll(List<Entry> imported) {
        Object dao = ClipboardRepoAccessor.dao();
        if (dao == null) {
            return null;
        }
        try {
            Object qb = XposedHelpers.callMethod(dao, "queryBuilder");
            Object listObj = XposedHelpers.callMethod(qb, "list");
            Map<String, Object> existMap = new HashMap<>();
            if (listObj instanceof List) {
                for (Object o : (List<?>) listObj) {
                    try {
                        Object t = XposedHelpers.getObjectField(
                                o, SogouHostContract.FIELD_ITEM_TEXT);
                        if (t != null) {
                            existMap.put(String.valueOf(t), o);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            long now = System.currentTimeMillis();
            List<Object> toSave = new ArrayList<>(imported.size());
            int added = 0;
            int updated = 0;
            for (Entry e : imported) {
                if (e == null || e.content == null || e.content.length() == 0) {
                    continue;
                }
                String content = e.content.length() > SogouHostContract.MAX_CONTENT_LEN
                        ? e.content.substring(0, SogouHostContract.MAX_CONTENT_LEN)
                        : e.content;
                Object exist = existMap.get(content);
                if (exist != null) {
                    try {
                        long oldTime = XposedHelpers.getLongField(
                                exist, SogouHostContract.FIELD_ITEM_TIME);
                        XposedHelpers.setLongField(exist,
                                SogouHostContract.FIELD_ITEM_TIME,
                                Math.max(Math.max(oldTime, e.time), now));
                        unionTimeArray(exist, e.times, now);
                    } catch (Throwable ignored) {
                    }
                    toSave.add(exist);
                    updated++;
                } else {
                    Object created = newItem(content, e.time, e.times);
                    if (created != null) {
                        toSave.add(created);
                        existMap.put(content, created);
                        added++;
                    }
                }
            }
            if (!toSave.isEmpty()) {
                XposedHelpers.callMethod(dao, "insertOrReplaceInTx", toSave);
            }
            return new int[]{added, updated};
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "mergeAll failed: " + t);
            return new int[]{0, 0};
        }
    }

    /** 新建宿主 c 实体：优先 c(String,long,List) 构造，失败逐字段回填 */
    private static Object newItem(String content, long time, List<Long> times) {
        try {
            Class<?> itemCls = XposedHelpers.findClass(
                    SogouHostContract.CLIPBOARD_ITEM, ModuleState.classLoader());
            try {
                return XposedHelpers.newInstance(itemCls, content, time,
                        new ArrayList<>(times));
            } catch (Throwable t1) {
                Object o = XposedHelpers.newInstance(itemCls, content);
                try {
                    XposedHelpers.setLongField(o, SogouHostContract.FIELD_ITEM_TIME, time);
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.setObjectField(o, "e", new ArrayList<>(times));
                } catch (Throwable ignored) {
                }
                return o;
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "newItem failed: " + t);
            return null;
        }
    }

    /** 时间数组并集（上限 32 个防膨胀，失败静默） */
    private static void unionTimeArray(Object item, List<Long> extra, long now) {
        try {
            Object e = XposedHelpers.getObjectField(item, "e");
            List<Long> arr;
            if (e instanceof List) {
                @SuppressWarnings("unchecked")
                List<Long> cast = (List<Long>) e;
                arr = cast;
            } else {
                arr = new ArrayList<>();
                XposedHelpers.setObjectField(item, "e", arr);
            }
            for (Long t : extra) {
                if (t != null && !arr.contains(t) && arr.size() < 32) {
                    arr.add(t);
                }
            }
            if (!arr.contains(now) && arr.size() < 32) {
                arr.add(now);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 导入后刷新：经 ViewModel.j() 走原生重查上报（C→swapList 自动跟上） */
    private static void refreshList() {
        try {
            Object kb = ModuleState.keyboard();
            if (kb == null) {
                return;
            }
            Object vm = XposedHelpers.getObjectField(
                    kb, SogouHostContract.FIELD_VIEW_MODEL);
            XposedHelpers.callMethod(vm, "j");
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "refresh after import failed: " + t);
        }
    }

    private static void toastOnMain(final Context ctx, final String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context c = ctx != null ? ctx : SogouSettingsInjector.globalContext();
                        if (c != null) {
                            android.widget.Toast.makeText(c, msg,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
