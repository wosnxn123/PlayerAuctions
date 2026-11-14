package com.folia.compat;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * 调度器兼容工具: 同时支持 Paper/Spigot 与 Folia(及其分支如 Canvas).
 *
 * <p>核心思想:Folia 没有单一主线程,不能用 {@code BukkitScheduler.runTask}。
 * 所有"主线程"操作必须按操作对象路由到正确的调度器:
 * <ul>
 *   <li>玩家/实体相关(打开GUI、操作背包、掉落物品) -> EntityScheduler</li>
 *   <li>全局/无特定位置(广播、遍历世界) -> GlobalRegionScheduler</li>
 *   <li>异步任务 -> AsyncScheduler</li>
 *   <li>定时重复任务 -> 对应调度器的 runAtFixedRate</li>
 * </ul>
 *
 * <p>本工具通过反射访问 Folia 专属类({@code io.papermc.paper.threadedregions.scheduler.*}),
 * 因此编译产物可在纯 Paper 环境下加载而不会 NoClassDefFoundError;
 * 运行时检测到 Folia 才使用其调度器,否则回退到 BukkitScheduler。
 */
public final class FoliaCompat {

    /** 运行环境是否为 Folia(或 Canvas 等 Folia 分支). 在类加载时检测一次. */
    public static final boolean FOLIA;

    private static final Method SERVER_GET_GLOBAL;
    private static final Method SERVER_GET_ASYNC;
    private static final Method SERVER_GET_REGION;
    private static final Method ENTITY_GET_SCHEDULER;

    // GlobalRegionScheduler 方法
    private static final Method GLOBAL_RUN;
    private static final Method GLOBAL_RUN_DELAYED;
    private static final Method GLOBAL_RUN_AT_FIXED_RATE;
    private static final Method GLOBAL_EXECUTE;

    // AsyncScheduler 方法
    private static final Method ASYNC_RUN_NOW;
    private static final Method ASYNC_RUN_DELAYED;
    private static final Method ASYNC_RUN_AT_FIXED_RATE;

    // RegionScheduler.run(Location) 方法
    private static final Method REGION_RUN_LOCATION;
    private static final Method REGION_RUN_DELAYED_LOCATION;

    // EntityScheduler 方法
    private static final Method ENTITY_RUN;
    private static final Method ENTITY_RUN_DELAYED;
    private static final Method ENTITY_EXECUTE;

    static {
        boolean folia;
        Method gGlobal = null, gAsync = null, gRegion = null, eSched = null;
        Method glRun = null, glRunDel = null, glRate = null, glExec = null;
        Method asRun = null, asRunDel = null, asRate = null;
        Method rgRunLoc = null, rgRunDelLoc = null;
        Method enRun = null, enRunDel = null, enExec = null;
        try {
            // Folia 检测必须用「只存在于服务端实现」的类。
            //
            // 不要用 io.papermc.paper.threadedregions.scheduler.* —— 那一整个包属于 **paper-api**,
            // 普通 Paper 也自带(Paper 让插件能无条件编译 Folia 调度器代码), 用它检测会把普通 Paper
            // 误判成 Folia, 于是所有 `if (!FOLIA)` 的 Bukkit 回退路径在 Paper 上全是死代码。
            //
            // RegionizedServer 属于服务端, 只有 Folia 及其分支(Canvas 等)才有。
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
            Class<?> globalCls = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> asyncCls = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            Class<?> regionCls = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            Class<?> entitySchedCls = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            Class<?> taskCls = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");

            Method getServer = Bukkit.class.getMethod("getServer");
            Class<?> serverCls = getServer.getReturnType();
            gGlobal = serverCls.getMethod("getGlobalRegionScheduler");
            gAsync = serverCls.getMethod("getAsyncScheduler");
            gRegion = serverCls.getMethod("getRegionScheduler");
            eSched = Entity.class.getMethod("getScheduler");

            // GlobalRegionScheduler
            Class<?> consumerTask = Class.forName("java.util.function.Consumer");
            glRun = globalCls.getMethod("run", Plugin.class, consumerTask);
            glRunDel = globalCls.getMethod("runDelayed", Plugin.class, consumerTask, long.class);
            glRate = globalCls.getMethod("runAtFixedRate", Plugin.class, consumerTask, long.class, long.class);
            glExec = globalCls.getMethod("execute", Plugin.class, Runnable.class);

            // AsyncScheduler
            asRun = asyncCls.getMethod("runNow", Plugin.class, consumerTask);
            asRunDel = asyncCls.getMethod("runDelayed", Plugin.class, consumerTask, long.class, TimeUnit.class);
            asRate = asyncCls.getMethod("runAtFixedRate", Plugin.class, consumerTask, long.class, long.class, TimeUnit.class);

            // RegionScheduler.run(Plugin, Location, Consumer)
            rgRunLoc = regionCls.getMethod("run", Plugin.class, Location.class, consumerTask);
            rgRunDelLoc = regionCls.getMethod("runDelayed", Plugin.class, Location.class, consumerTask, long.class);

            // EntityScheduler
            enRun = entitySchedCls.getMethod("run", Plugin.class, consumerTask, Runnable.class);
            enRunDel = entitySchedCls.getMethod("runDelayed", Plugin.class, consumerTask, Runnable.class, long.class);
            enExec = entitySchedCls.getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        } catch (Throwable t) {
            folia = false;
        }
        FOLIA = folia;
        SERVER_GET_GLOBAL = gGlobal;
        SERVER_GET_ASYNC = gAsync;
        SERVER_GET_REGION = gRegion;
        ENTITY_GET_SCHEDULER = eSched;
        GLOBAL_RUN = glRun;
        GLOBAL_RUN_DELAYED = glRunDel;
        GLOBAL_RUN_AT_FIXED_RATE = glRate;
        GLOBAL_EXECUTE = glExec;
        ASYNC_RUN_NOW = asRun;
        ASYNC_RUN_DELAYED = asRunDel;
        ASYNC_RUN_AT_FIXED_RATE = asRate;
        REGION_RUN_LOCATION = rgRunLoc;
        REGION_RUN_DELAYED_LOCATION = rgRunDelLoc;
        ENTITY_RUN = enRun;
        ENTITY_RUN_DELAYED = enRunDel;
        ENTITY_EXECUTE = enExec;
    }

    private FoliaCompat() {
    }

    // ============================ 任务句柄 ============================

    /** 可取消的任务句柄,同时兼容 BukkitTask(Folia 上无统一类型时也用反射取消). */
    public static final class TaskHandle {
        private final Object bukkitTask; // org.bukkit.scheduler.BukkitTask (Paper) 或 null
        private final Object foliaTask;  // io.papermc.paper.threadedregions.scheduler.ScheduledTask

        TaskHandle(Object bukkitTask, Object foliaTask) {
            this.bukkitTask = bukkitTask;
            this.foliaTask = foliaTask;
        }

        public void cancel() {
            try {
                if (bukkitTask != null) {
                    org.bukkit.scheduler.BukkitTask.class.getMethod("cancel").invoke(bukkitTask);
                } else if (foliaTask != null) {
                    // Invoke the PUBLIC API, not a potentially private scheduler implementation.
                    Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask")
                            .getMethod("cancel").invoke(foliaTask);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================ 同步主线程(全局区域) ============================
    // 用于:广播消息、遍历世界实体等无特定实体绑定的操作.

    /** 立即在主线程(全局区域)执行一次. */
    public static TaskHandle runTask(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable), null);
        }
        // 保留 scheduler 返回的 ScheduledTask, 否则 TaskHandle#cancel 在 Folia 上是静默空操作
        return new TaskHandle(null, invokeGlobal(plugin, runnable, GLOBAL_RUN, 0, 0, false, false));
    }

    /** 延迟 delayTicks 后在主线程(全局区域)执行一次. */
    public static TaskHandle runTaskLater(Plugin plugin, Runnable runnable, long delayTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks), null);
        }
        return new TaskHandle(null, invokeGlobal(plugin, runnable, GLOBAL_RUN_DELAYED, delayTicks, 0, false, false));
    }

    /**
     * 定时重复在主线程(全局区域)执行.
     * Folia 用 GlobalRegionScheduler.runAtFixedRate, 注意其 period 单位为 tick.
     */
    public static TaskHandle runTaskTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks), null);
        }
        Object task = invokeGlobal(plugin, runnable, GLOBAL_RUN_AT_FIXED_RATE, delayTicks, periodTicks, true, false);
        return new TaskHandle(null, task);
    }

    /**
     * 在全局区域主线程执行一次. 语义别名,专门用于世界管理等全局操作(createWorld/unloadWorldAsync 等).
     * Folia/Canvas 用 GlobalRegionScheduler.execute; Paper 用主线程 runTask.
     */
    public static TaskHandle runGlobal(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            // Paper: 若已在主线程直接执行,否则切主线程
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
                return new TaskHandle(null, null);
            }
            return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable), null);
        }
        // Folia/Canvas: GlobalRegionScheduler.execute
        try {
            Object server = Bukkit.getServer();
            Object global = SERVER_GET_GLOBAL.invoke(server);
            GLOBAL_EXECUTE.invoke(global, plugin, runnable);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return new TaskHandle(null, null);
    }

    // ============================ 实体调度 ============================
    // 用于:打开/关闭背包、操作玩家背包、掉落物品、传送等绑定到某实体的操作.
    // 这些在 Folia 必须经 EntityScheduler, 否则会抛 IllegalStateException.

    /** 在该实体所在区域的主线程执行一次(实体退役时执行 retired). */
    public static TaskHandle runEntity(Plugin plugin, Entity entity, Runnable runnable, Runnable retired) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable), null);
        }
        Object scheduler = getEntityScheduler(entity);
        return new TaskHandle(null, invokeEntity(plugin, scheduler, runnable, retired, ENTITY_RUN, 0, false));
    }

    /** 在该实体所在区域的主线程延迟 delayTicks 后执行一次. */
    public static TaskHandle runEntityLater(Plugin plugin, Entity entity, Runnable runnable, Runnable retired, long delayTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks), null);
        }
        Object scheduler = getEntityScheduler(entity);
        return new TaskHandle(null, invokeEntity(plugin, scheduler, runnable, retired, ENTITY_RUN_DELAYED, delayTicks, false));
    }

    // ============================ 区域调度 ============================
    // 用于:操作某个位置所在区域(掉落方块、读区块等). 插件较少直接用.

    /** 在 location 所在区域主线程执行一次. */
    public static TaskHandle runRegion(Plugin plugin, Location location, Runnable runnable) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable), null);
        }
        Object region = getServerRegion();
        return new TaskHandle(null, invokeRegionLocation(region, plugin, location, runnable, REGION_RUN_LOCATION, 0));
    }

    // ============================ 异步 ============================

    /** 立即在异步线程执行一次. */
    public static TaskHandle runAsync(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable), null);
        }
        return new TaskHandle(null, invokeAsync(plugin, runnable, ASYNC_RUN_NOW, 0, 0, false));
    }

    /** 延迟 delayTicks(换算为 ms)后在异步线程执行一次. */
    public static TaskHandle runAsyncLater(Plugin plugin, Runnable runnable, long delayTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayTicks), null);
        }
        return new TaskHandle(null, invokeAsync(plugin, runnable, ASYNC_RUN_DELAYED, delayTicks, 0, false));
    }

    /** 异步定时重复, periodTicks 为周期. */
    public static TaskHandle runAsyncTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks), null);
        }
        Object task = invokeAsync(plugin, runnable, ASYNC_RUN_AT_FIXED_RATE, delayTicks, periodTicks, true);
        return new TaskHandle(null, task);
    }

    // ============================ 反射调用内部实现 ============================

    private static Consumer<Object> wrap(final Runnable r) {
        return task -> {
            try {
                r.run();
            } catch (Throwable t) {
                // 防止单次异常中断 Folia 的 Consumer
                t.printStackTrace();
            }
        };
    }

    private static Object getGlobal() {
        try {
            Object server = Bukkit.getServer();
            return SERVER_GET_GLOBAL.invoke(server);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object getServerRegion() {
        try {
            Object server = Bukkit.getServer();
            return SERVER_GET_REGION.invoke(server);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object getEntityScheduler(Entity entity) {
        try {
            return ENTITY_GET_SCHEDULER.invoke(entity);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object invokeGlobal(Plugin plugin, Runnable runnable, Method method, long delay, long period, boolean isRate, boolean isAsyncFallback) {
        try {
            Object global = getGlobal();
            Consumer<Object> c = wrap(runnable);
            if (isRate) {
                return method.invoke(global, plugin, c, delay, period);
            }
            if (method.getParameterTypes().length == 3) {
                return method.invoke(global, plugin, c, delay);
            }
            return method.invoke(global, plugin, c);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object invokeEntity(Plugin plugin, Object scheduler, Runnable runnable, Runnable retired, Method method, long delay, boolean isRate) {
        try {
            Consumer<Object> c = wrap(runnable);
            AtomicBoolean retirementHandled = new AtomicBoolean();
            Runnable ret = () -> {
                if (retirementHandled.compareAndSet(false, true) && retired != null) {
                    retired.run();
                }
            };
            // EntityScheduler.run 是 3 参数，runDelayed 是 4 参数。
            Object task = method.getParameterCount() == 4
                    ? method.invoke(scheduler, plugin, c, ret, delay)
                    : method.invoke(scheduler, plugin, c, ret);
            // 已退役实体返回 null 时，调度器不会调用 retired。
            // 必须走离线投递/邮箱回退，否则异步领取可能丢失物品。
            if (task == null) {
                ret.run();
            }
            return task;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object invokeRegionLocation(Object region, Plugin plugin, Location location, Runnable runnable, Method method, long delay) {
        try {
            Consumer<Object> c = wrap(runnable);
            if (method.getParameterTypes().length == 4) {
                return method.invoke(region, plugin, location, c, delay);
            } else {
                return method.invoke(region, plugin, location, c);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object invokeAsync(Plugin plugin, Runnable runnable, Method method, long delay, long period, boolean isRate) {
        try {
            Object server = Bukkit.getServer();
            Object async = SERVER_GET_ASYNC.invoke(server);
            Consumer<Object> c = wrap(runnable);
            if (isRate) {
                // runAtFixedRate(Plugin, Consumer, long delayMs, long periodMs, TimeUnit)
                return method.invoke(async, plugin, c, delay * 50L, period * 50L, TimeUnit.MILLISECONDS);
            }
            if (method.getParameterTypes().length == 4) {
                // runDelayed(Plugin, Consumer, long delay, TimeUnit)
                return method.invoke(async, plugin, c, delay * 50L, TimeUnit.MILLISECONDS);
            }
            // runNow
            return method.invoke(async, plugin, c);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
