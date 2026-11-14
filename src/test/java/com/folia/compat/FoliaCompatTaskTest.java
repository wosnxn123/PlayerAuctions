package com.folia.compat;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** No server needed: exercise real reflection helpers against the public scheduler API. */
class FoliaCompatTaskTest {
    private static final class HiddenFoliaTask implements ScheduledTask {
        boolean cancelled;
        public Plugin getOwningPlugin() { return null; }
        public boolean isRepeatingTask() { return false; }
        public CancelledState cancel() { cancelled = true; return CancelledState.CANCELLED_BY_CALLER; }
        public ExecutionState getExecutionState() { return null; }
    }

    private static final class HiddenBukkitTask implements BukkitTask {
        boolean cancelled;
        public int getTaskId() { return 1; }
        public Plugin getOwner() { return null; }
        public boolean isSync() { return true; }
        public boolean isCancelled() { return cancelled; }
        public void cancel() { cancelled = true; }
    }

    public static final class SchedulerStub {
        ScheduledTask result;
        Runnable capturedRetired;
        long delay;
        boolean retireDuringSubmission;
        public ScheduledTask run(Plugin plugin, Consumer<?> task, Runnable retired) {
            capturedRetired = retired;
            if (retireDuringSubmission) { retired.run(); }
            return result;
        }
        public ScheduledTask runDelayed(Plugin plugin, Consumer<?> task, Runnable retired, long ticks) {
            delay = ticks;
            return run(plugin, task, retired);
        }
        public ScheduledTask runRegion(Plugin plugin, Location location, Consumer<?> task) {
            return result;
        }
    }

    private Object submit(SchedulerStub scheduler, Runnable retired, boolean delayed) throws Exception {
        Method helper = FoliaCompat.class.getDeclaredMethod("invokeEntity", Plugin.class, Object.class,
                Runnable.class, Runnable.class, Method.class, long.class, boolean.class);
        helper.setAccessible(true);
        Method api = delayed
                ? SchedulerStub.class.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class)
                : SchedulerStub.class.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
        return helper.invoke(null, null, scheduler, (Runnable) () -> {}, retired, api, 12L, false);
    }

    @Test void cancelPrivateFoliaImplementationViaPublicInterface() {
        HiddenFoliaTask task = new HiddenFoliaTask();
        new FoliaCompat.TaskHandle(null, task).cancel();
        assertTrue(task.cancelled);
    }

    @Test void cancelPrivateBukkitImplementationViaPublicInterface() {
        HiddenBukkitTask task = new HiddenBukkitTask();
        new FoliaCompat.TaskHandle(task, null).cancel();
        assertTrue(task.cancelled);
    }

    @Test void entitySubmissionRetainsTask() throws Exception {
        SchedulerStub scheduler = new SchedulerStub();
        scheduler.result = new HiddenFoliaTask();
        assertSame(scheduler.result, submit(scheduler, null, false));
    }

    @Test void delayedEntitySubmissionRetainsTaskAndDelay() throws Exception {
        SchedulerStub scheduler = new SchedulerStub();
        scheduler.result = new HiddenFoliaTask();
        assertSame(scheduler.result, submit(scheduler, null, true));
        assertEquals(12L, scheduler.delay);
    }

    @Test void alreadyRetiredEntityRunsFallbackExactlyOnce() throws Exception {
        SchedulerStub scheduler = new SchedulerStub();
        AtomicInteger fallback = new AtomicInteger();
        assertNull(submit(scheduler, fallback::incrementAndGet, false));
        scheduler.capturedRetired.run();
        assertEquals(1, fallback.get());
    }

    @Test void synchronousRetirementAndNullReturnDoNotDoubleDeliver() throws Exception {
        SchedulerStub scheduler = new SchedulerStub();
        scheduler.retireDuringSubmission = true;
        AtomicInteger fallback = new AtomicInteger();
        assertNull(submit(scheduler, fallback::incrementAndGet, true));
        assertEquals(1, fallback.get());
    }

    @Test void regionSubmissionRetainsTask() throws Exception {
        SchedulerStub scheduler = new SchedulerStub();
        scheduler.result = new HiddenFoliaTask();
        Method helper = FoliaCompat.class.getDeclaredMethod("invokeRegionLocation", Object.class, Plugin.class,
                Location.class, Runnable.class, Method.class, long.class);
        helper.setAccessible(true);
        Method api = SchedulerStub.class.getMethod("runRegion", Plugin.class, Location.class, Consumer.class);
        assertSame(scheduler.result, helper.invoke(null, scheduler, null, null, (Runnable) () -> {}, api, 0L));
    }
}
