package com.clipboard.enhance;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ClipboardKeyboard / adapter / ViewModel 领域 Hook：列表过滤写回、条目上屏置顶、
 * 全选范围控制、删除范围保护、左滑删除。
 *
 * 逆向事实（com.sohu.inputmethod.clipboard.*，混淆名）：
 * - ClipboardKeyboard：列表面板
 *   - 数据源字段 M（List<c>）、adapter 字段 N（b 类）、adapter 显示列表字段 j
 *   - C(List) = onChanged 上报全量列表
 *   - a(int) 上屏 = u.Z().A(M.get(i).d)（剪贴板上屏，与拼音路径完全分离）
 *   - O(String) = updateClipboardUIWhenAdd（宿主原生插入链路：去重 + 更新时间戳 +
 *     orderDesc(Time) 排序 + LiveData 上报），粘贴置顶复用
 *   - w(List) = 删除确认框弹出
 * - adapter b 类：l(boolean) = 全选遍历 this.j 置勾选位；g() = 勾选列表
 * - ClipboardViewModel.e() = 清空全部（删除确认链的「全删」分支）
 *
 * swapList 为「把当前过滤列表写回原生并刷新」的唯一入口，供 SearchModeController
 * （搜索/清除筛选动作）复用；ListFilterProxy 保持纯逻辑（无回调），调用方在
 * setKeyword/clearKeyword/onListChanged 后显式调 swapList，避免隐式回调环。
 */
public final class KeyboardListHooks {

    /* ================= 混淆类名（字符串引用，防混淆） ================= */
    private static final String CLS_KEYBOARD = "com.sohu.inputmethod.clipboard.ClipboardKeyboard";
    private static final String CLS_ADAPTER = "com.sohu.inputmethod.clipboard.b";
    private static final String CLS_VIEW_MODEL = "com.sohu.inputmethod.clipboard.ClipboardViewModel";

    /** 删除确认框文案模板（与原生 w() 文案一致，筛选态覆写时使用） */
    private static final String DELETE_MSG_TEMPLATE = "您确定删除剪贴板%d条内容吗?";

    private KeyboardListHooks() {
    }

    /** 注册本领域全部 hook（注册顺序与拆分前一致） */
    public static void init(ClassLoader cl) {
        hookKeyboardChanged(cl);
        hookKeyboardItem(cl);
        hookAdapterSelectAll(cl);
        hookDeleteScope(cl);
        hookSwipeDelete(cl);
    }

    /* ================= 1. onChanged 拦截 → 过滤代理 ================= */
    private static void hookKeyboardChanged(ClassLoader cl) {
        HookUtil.safeHook("C(List)", () -> XposedHelpers.findAndHookMethod(CLS_KEYBOARD, cl, "C", List.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            ModuleState.setKeyboard(param.thisObject);
                            PasteCounter.init();
                            @SuppressWarnings("unchecked")
                            List<Object> full = (List<Object>) param.args[0];
                            ListFilterProxy.onListChanged(full);
                            swapList();
                            pruneOrphanCounts(full);
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "onChanged error: " + t);
                        }
                    }
                }));
    }

    /**
     * 计数对账：onChanged 全量上报后清掉已删条目的孤儿计数，防止 SP 文件无界增长。
     * 条目文本取 c 对象 d 字段（与上屏读取同源），取不到则跳过。
     */
    private static void pruneOrphanCounts(List<Object> full) {
        try {
            if (full == null) {
                return;
            }
            List<String> contents = new ArrayList<>(full.size());
            for (Object item : full) {
                try {
                    Object text = XposedHelpers.getObjectField(item, "d");
                    if (text != null) {
                        contents.add(String.valueOf(text));
                    }
                } catch (Throwable ignored) {
                }
            }
            PasteCounter.prune(contents);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "prune counts error: " + t);
        }
    }

    /** 将过滤结果写回 M 字段 + adapter.j，并尝试刷新 */
    public static void swapList() {
        try {
            List<Object> active = ListFilterProxy.activeList();
            writeBackKeyboard(active);
            writeBackAdapter(active);
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "swapList error: " + t);
        }
        // 过滤/恢复后同步刷新标题旁数字（当前显示/总数）
        CandidateViewHooks.refreshCountText(null);
    }

    /** 把生效列表写回 keyboard 数据源字段 M（索引上屏 a(int) 依赖它）。
        注意：不能替换引用。原生 onChanged（C(List)）是原地 M.clear(); M.addAll(list)，
        单条删除是 M.remove(index)，均假设 M 是「原生自己所有」的列表对象。
        若把 M 替换为模块列表（非筛选态下 sActive == onChanged 参数 == ViewModel
        缓存 this.c），下一次 onChanged(this.c) 的 M.clear() 会把共享列表就地清空，
        表现为「逐条删除后整个剪贴板消失、重开恢复」。故用原地同步保持 M 引用稳定。 */
    private static void writeBackKeyboard(List<Object> active) {
        try {
            Object kb = ModuleState.keyboard();
            if (kb != null) {
                syncListField(kb, "M", active);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "writeBack M failed: " + t);
        }
    }

    /** 把生效列表写回 adapter 显示列表 j，并试探刷新（适配器无该方法则跳过）。
        adapter 的 o(List) 原生实现是 this.j = list（替换引用，由 Q() 触发），
        全选 l(boolean) 只是遍历 this.j 置勾选位、不修改结构；但模块自己写回时
        同样不能把 j 替换为模块列表引用（j == M == this.c 时原生 onChanged 的
        M.clear() 会波及）。与 writeBackKeyboard 一致地原地同步。 */
    private static void writeBackAdapter(List<Object> active) {
        try {
            Object adapter = CandidateViewHooks.clipboardAdapter();
            if (adapter == null) {
                return;
            }
            syncListField(adapter, "j", active);
            try {
                XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
            } catch (Throwable ignored) {
                XposedBridge.log(HookUtil.LOG_TAG + "adapter has no notifyDataSetChanged; native refresh fallback");
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "writeBack adapter.j failed: " + t);
        }
    }

    /** 把生效列表原地同步进原生列表字段：先 clear 再 addAll，保持字段引用不变。
        原生多处假设列表字段是「原生自有的可变对象」（onChanged 的 clear/addAll、
        单条删除的 M.remove、ViewModel postValue 的 this.c 原地删改），
        模块若用 setObjectField 整体替换引用，会让模块列表与原生列表共享同一对象，
        原生对列表的任何就地修改都会反噬模块状态。此方法保证引用隔离。 */
    private static void syncListField(Object owner, String fieldName, List<Object> active) {
        if (active == null) {
            return;
        }
        Object curObj = XposedHelpers.getObjectField(owner, fieldName);
        if (curObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> cur = (List<Object>) curObj;
            if (cur == active) {
                return; // 同一对象：宿主已持有生效列表，无需原地重写，避免自清空
            }
            cur.clear();
            cur.addAll(active);
        } else {
            // 字段尚未初始化（如 adapter.j 首次 null）时兜底：替换为内容拷贝
            XposedHelpers.setObjectField(owner, fieldName, new ArrayList<>(active));
        }
    }

    /* ================= 2. 剪贴板条目粘贴上屏：兜底复位搜索模式 + 置顶 =================
       剪贴板上屏路径 a(int) → u.Z().A(...)，不走拼音候选；
       若搜索模式残留，点击条目时在此复位，防止误拦截。
       after 置顶：开关开启时调用宿主原生入口 O(String)（updateClipboardUIWhenAdd，
       即宿主「外部内容进入剪贴板」的标准链路：p.z → 异步插入 p.H 按内容去重 +
       更新时间戳 → u 注册回调查询 orderDesc(Time) → LiveData 上报 → onChanged 刷新），
       刚粘贴的条目时间戳最新自动排到最上方；之后新复制的内容（时间戳更新）仍压它一头；
       排序/去重/上报/刷新全部复用宿主逻辑，模块不维护置顶列表。 */
    private static void hookKeyboardItem(ClassLoader cl) {
        HookUtil.safeHook("item", () -> XposedHelpers.findAndHookMethod(CLS_KEYBOARD, cl, "a", int.class,
                new XC_MethodHook() {
                    private volatile Object sLastCommitted;

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        sLastCommitted = null;
                        if (ModuleState.isSearchMode()) {
                            ModuleState.setSearchMode(false);
                            XposedBridge.log(HookUtil.LOG_TAG + "item click -> exit search mode");
                        }
                        try {
                            List<?> items = (List<?>) XposedHelpers.getObjectField(param.thisObject, "M");
                            int index = (Integer) param.args[0];
                            if (items != null && index >= 0 && index < items.size()) {
                                sLastCommitted = items.get(index);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "item record error: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object committed = sLastCommitted;
                        sLastCommitted = null;
                        if (committed == null) {
                            return;
                        }
                        try {
                            Object text = XposedHelpers.getObjectField(committed, "d");
                            if (text != null) {
                                String content = String.valueOf(text);
                                // 粘贴计数 +1（持久化），与置顶逻辑互不耦合
                                PasteCounter.increment(content);
                                XposedBridge.log(HookUtil.LOG_TAG + "paste increment: content.len=" + content.length()
                                        + " count=" + PasteCounter.getCount(content));
                                if (ModuleState.isPinRecentEnabled()) {
                                    XposedHelpers.callMethod(param.thisObject, "O", content);
                                    XposedBridge.log(HookUtil.LOG_TAG + "item committed -> host re-pin via O()");
                                } else {
                                    // 无置顶时主动刷新列表，使新计数可见
                                    swapList();
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "pin-after-commit error: " + t);
                            // 宿主 O() 反射失败兜底：仅刷新列表（不置顶），保持显示一致
                            swapList();
                        }
                    }
                }));
    }

    /* ================= 3. 全选 → 只选当前筛选结果 =================
       原生全选链路：全选按钮 → onMenuClick(15) → a(6) → Page.L(6) →
       Keyboard.I(isAllSelected) → adapter.l(boolean) 遍历 this.j 全部置勾选。
       adapter.j 为显示列表（getCount/getView/g() 勾选集合均基于它）；
       进入整理态时 j 可能被原生重置为全量列表（Q() → N.o(this.M)），
       导致全选勾上筛选范围外的条目。此处 before 强制把 j 同步为当前生效过滤列表：
       - 过滤中：j = 过滤子集（只全选筛选出的项）
       - 未过滤：j = 全量（与原生行为一致）
       注意原地同步而非替换引用（与 swapList 写回一致），避免 j 与模块列表共享对象 */
    private static void hookAdapterSelectAll(ClassLoader cl) {
        HookUtil.safeHook("adapter.l", () -> XposedHelpers.findAndHookMethod(CLS_ADAPTER, cl, "l", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            List<Object> active = ListFilterProxy.activeList();
                            syncListField(param.thisObject, "j", active);
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "select-all scope error: " + t);
                        }
                    }
                }));
    }

    /* ================= 4. 删除范围保护（筛选态下全选删除） =================
       原生删除确认链：删除按钮 → L(7) → Keyboard.p() → w(N.g()) 弹确认框
       → ClipboardKeyboard.d.onClick：
           if (勾选集.size() != this.M.size()) → ViewModel.g(list) 逐条删勾选集
           else                                → ViewModel.e() 清空全部
       筛选态下 swapList 把 M 也写成了过滤子集，导致「全选勾选集 == M」
       被原生误判为「清空全部」，实际清空整个数据库。
       修复双保险：
       - hook w(List) after：筛选态强制对话框文案为「删除N条」（避免误导）
       - hook ViewModel.e() before：筛选态屏蔽清空全部，改删当前勾选集 */
    private static void hookDeleteScope(ClassLoader cl) {
        HookUtil.safeHook("Keyboard.w", () -> XposedHelpers.findAndHookMethod(CLS_KEYBOARD, cl, "w", List.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (ListFilterProxy.isFiltering()) {
                                // 原生文案分支取决于 list.size()==M.size()，筛选态恒相等；
                                // 直接覆写确认框文案为「删除N条」
                                Object dialog = XposedHelpers.getObjectField(param.thisObject, "Q");
                                if (dialog != null) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> checked = (List<Object>) param.args[0];
                                    // %d 纯数字占位，固定 Locale.US 避免土耳其等 locale 下的异常行为
                                    String msg = String.format(Locale.US, DELETE_MSG_TEMPLATE, checked.size());
                                    XposedHelpers.callMethod(dialog, "setMessage", (Object) msg);
                                    XposedBridge.log(HookUtil.LOG_TAG + "delete dialog msg: " + msg);
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "delete dialog msg error: " + t);
                        }
                    }
                }));
        HookUtil.safeHook("ViewModel.e", () -> XposedHelpers.findAndHookMethod(CLS_VIEW_MODEL, cl, "e",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (ListFilterProxy.isFiltering()) {
                                // 取当前勾选集（此刻全选勾选尚未清除）
                                List<?> list = CandidateViewHooks.checkedList();
                                if (list != null && !list.isEmpty()) {
                                    // 屏蔽清空全部，重定向为只删勾选集
                                    param.setResult(null);
                                    XposedHelpers.callMethod(param.thisObject, "g", list);
                                    XposedBridge.log(HookUtil.LOG_TAG + "clear-all rerouted to checked-only ("
                                            + list.size() + ")");
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "delete scope error: " + t);
                        }
                    }
                }));
    }

    /* ================= 5. 左滑删除条目 =================
       原生 ListView + BaseAdapter，无 ItemTouchHelper；条目根点按上屏、长按进整理态。
       自写 OnTouchListener 做方向锁定左滑：阈值前全返回 false（点按/长按/纵滑走原生，
       长按靠框架超 slop 自动取消）；超阈值后返回 true 消费后续事件（含 UP），天然压住
       误上屏，无需额外 hook；纵向被 ListView 接管收 CANCEL 回弹；整理态门控关闭。
       删除复用原生链：开关开 → keyboard.w(singleton) 确认框（筛选保护自动生效）；
       开关关 → ViewModel.g(singleton) 直删（LiveData→onChanged→swapList 刷新）。
       position 每次重绑经 WeakHashMap 更新（原生已占 setTag），删除时以
       adapter.f(position) 重新取值防过期；重绑无条件复位 translationX/alpha 防复用残留。 */
    /** 左滑删除阈值：超过条目宽度该比例即触发删除 */
    private static final float SWIPE_DELETE_FRACTION = 0.35f;
    /** 方向锁定系数：横向位移需超过纵向该倍数才认定为滑动 */
    private static final float SWIPE_DIRECTION_FACTOR = 1.5f;
    /** 滑动跟手/回弹动画时长（ms） */
    private static final long SWIPE_ANIM_MS = 200L;

    /** 左滑目标：重绑时更新，删除时重新取值（adapter + 显示位置） */
    private static final class SwipeTarget {
        final Object adapter;
        final int position;

        SwipeTarget(Object adapter, int position) {
            this.adapter = adapter;
            this.position = position;
        }
    }

    /** 条目→滑动目标（弱持有，视图回收自动清理；原生已占 setTag 故不用 tag 传参） */
    private static final WeakHashMap<View, SwipeTarget> sSwipeTargets = new WeakHashMap<>();
    /** 已挂载触摸监听的条目视图（弱集合，视图回收自动清理，监听只挂一次） */
    private static final Set<View> sSwipeAttached =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /** 正拖视图：阈值确认后置位，UP/CANCEL/翻飞结束清零；持有期最长等于一次手势 */
    private static volatile View sActiveView;
    /** 正拖视图当前位移：重绑时重贴，防滚动/刷新闪跳 */
    private static volatile float sActiveTranslation;

    /** 共享滑动监听：单例，所有条目复用（IME 列表以单点触控为主，状态存实例字段） */
    private static final View.OnTouchListener sSwipeTouchListener = new View.OnTouchListener() {
        private float rawDownX;
        private float rawDownY;
        private boolean swiping;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            try {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (isSelectingMode()) {
                            return false;
                        }
                        // Raw 坐标：屏幕绝对，不受视图自身位移影响，断开反馈环
                        rawDownX = ev.getRawX();
                        rawDownY = ev.getRawY();
                        swiping = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - rawDownX;
                        float dy = ev.getRawY() - rawDownY;
                        if (swiping) {
                            float tx = Math.min(0f, dx);
                            v.setTranslationX(tx);
                            sActiveTranslation = tx;
                            return true;
                        }
                        int slop;
                        try {
                            slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                        } catch (Throwable ignored) {
                            slop = 16;
                        }
                        if (dx < 0 && -dx > slop && -dx > Math.abs(dy) * SWIPE_DIRECTION_FACTOR) {
                            // 阈值重查门控：中途已进整理态（慢拖着长按先赢）则不接管，原生长按效果保留
                            if (isSelectingMode()) {
                                swiping = false;
                                return false;
                            }
                            swiping = true;
                            sActiveView = v;
                            sActiveTranslation = dx;
                            // 接管手势后原生 onTouchEvent 收不到后续 MOVE，会饿死其内部取消长按的逻辑；
                            // 此处亲手掐掉 DOWN 时挂起的 CheckForLongPress（业界同解：谁接管谁负责取消）。
                            // 点按/静置长按走不到这里，不受影响；长按已先触发时由上方门控中止，不会到这里。
                            try {
                                v.cancelLongPress();
                            } catch (Throwable ignored) {
                            }
                            try {
                                if (v.getParent() != null) {
                                    v.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                            } catch (Throwable ignored) {
                            }
                            v.setTranslationX(dx);
                            return true;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (!swiping) {
                            return false;
                        }
                        swiping = false;
                        sActiveView = null;
                        // 完成前重查门控：手势中途进整理态则中止，动画回弹，不删除
                        if (isSelectingMode()) {
                            v.animate().translationX(0f).alpha(1f).setDuration(SWIPE_ANIM_MS).start();
                            return true;
                        }
                        float threshold = v.getWidth() * SWIPE_DELETE_FRACTION;
                        if (threshold > 0 && -v.getTranslationX() >= threshold) {
                            flingAwayAndDelete(v);
                        } else {
                            v.animate().translationX(0f).alpha(1f).setDuration(SWIPE_ANIM_MS).start();
                        }
                        return true; // 消费 UP，压住误上屏
                    }
                    case MotionEvent.ACTION_CANCEL:
                        swiping = false;
                        sActiveView = null;
                        v.animate().translationX(0f).alpha(1f).setDuration(SWIPE_ANIM_MS).start();
                        return false;
                    default:
                        return false;
                }
            } catch (Throwable t) {
                XposedBridge.log(HookUtil.LOG_TAG + "swipe touch error: " + t);
                return false;
            }
        }
    };

    private static void hookSwipeDelete(ClassLoader cl) {
        HookUtil.safeHook("swipeDelete", () -> XposedHelpers.findAndHookMethod(CLS_ADAPTER, cl, "getView", int.class, View.class, ViewGroup.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            View itemView = (View) param.getResult();
                            if (itemView == null) {
                                return;
                            }
                            int position = (Integer) param.args[0];
                            // 复用视图残留防御：每次重绑复位；正拖视图跳过复位、重贴当前位移
                            if (itemView == sActiveView) {
                                itemView.setTranslationX(sActiveTranslation);
                            } else {
                                itemView.setTranslationX(0f);
                                itemView.setAlpha(1f);
                            }
                            sSwipeTargets.put(itemView, new SwipeTarget(param.thisObject, position));
                            if (sSwipeAttached.add(itemView)) {
                                itemView.setOnTouchListener(sSwipeTouchListener);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(HookUtil.LOG_TAG + "swipe bind error: " + t);
                        }
                    }
                }));
    }

    /** 整理态门控：整理态下不启用滑动（CandidateViewHooks.isSelecting 为私有，域内联等价判断） */
    private static boolean isSelectingMode() {
        try {
            Object cv = ModuleState.candidateView();
            if (cv == null) {
                return false;
            }
            return Boolean.TRUE.equals(XposedHelpers.callMethod(cv, "isSelecting"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 翻飞离场后执行删除：开关开走确认框，关走直删 */
    private static void flingAwayAndDelete(final View itemView) {
        final SwipeTarget target = sSwipeTargets.get(itemView);
        if (target == null) {
            itemView.animate().translationX(0f).alpha(1f).setDuration(SWIPE_ANIM_MS).start();
            return;
        }
        final int width = itemView.getWidth();
        itemView.animate().translationX(-width).alpha(0f).setDuration(SWIPE_ANIM_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            deleteSwipeItem(target.adapter, target.position);
                        } finally {
                            // 无论删除成败：复位本视图（若仍附着），防复用残留；释放正拖持有
                            sActiveView = null;
                            try {
                                itemView.setTranslationX(0f);
                                itemView.setAlpha(1f);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }).start();
    }

    /** 执行删除：开关开 → keyboard.w(singleton) 确认框；关 → ViewModel.g(singleton) 直删 */
    private static void deleteSwipeItem(Object adapter, int position) {
        try {
            Object kb = ModuleState.keyboard();
            if (kb == null) {
                XposedBridge.log(HookUtil.LOG_TAG + "swipe delete skipped: no keyboard");
                return;
            }
            int count;
            try {
                count = (Integer) XposedHelpers.callMethod(adapter, "getCount");
            } catch (Throwable ignored) {
                return;
            }
            if (position < 0 || position >= count) {
                XposedBridge.log(HookUtil.LOG_TAG + "swipe delete skipped: stale position " + position);
                return;
            }
            Object item = XposedHelpers.callMethod(adapter, "f", position);
            if (item == null) {
                return;
            }
            List<Object> single = new ArrayList<>(Collections.singletonList(item));
            if (ModuleState.isSwipeDeleteConfirm()) {
                XposedHelpers.callMethod(kb, "w", single);
                XposedBridge.log(HookUtil.LOG_TAG + "swipe delete -> confirm dialog");
            } else {
                Object vm = XposedHelpers.getObjectField(kb, "T");
                XposedHelpers.callMethod(vm, "g", single);
                XposedBridge.log(HookUtil.LOG_TAG + "swipe delete -> direct");
            }
        } catch (Throwable t) {
            XposedBridge.log(HookUtil.LOG_TAG + "swipe delete error: " + t);
        }
    }
}