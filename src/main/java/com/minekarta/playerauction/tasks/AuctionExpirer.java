package com.minekarta.playerauction.tasks;

import com.folia.compat.FoliaCompat;
import com.folia.compat.FoliaCompat.TaskHandle;
import com.minekarta.playerauction.auction.AuctionService;
import org.bukkit.plugin.Plugin;

/**
 * 过期拍卖处理任务. 原实现继承 BukkitRunnable 并用 runTaskTimerAsynchronously,
 * 在 Folia 下 BukkitRunnable 的同步调度会抛异常, 因此改为异步定时任务并自行持有句柄.
 */
public class AuctionExpirer {
    private final AuctionService auctionService;
    private TaskHandle task;

    public AuctionExpirer(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    public void run() {
        this.auctionService.processExpiredAuctions();
    }

    /** 启动异步定时任务, 替代原 BukkitRunnable.runTaskTimerAsynchronously. 返回句柄以便取消. */
    public TaskHandle runTaskTimerAsynchronously(Plugin plugin, long delay, long period) {
        this.task = FoliaCompat.runAsyncTimer(plugin, () -> this.run(), delay, period);
        return this.task;
    }

    public void cancel() {
        if (this.task != null) {
            this.task.cancel();
        }
    }
}
