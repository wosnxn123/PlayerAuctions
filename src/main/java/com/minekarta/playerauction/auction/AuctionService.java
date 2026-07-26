package com.minekarta.playerauction.auction;

import com.minekarta.playerauction.auction.model.Auction;
import com.minekarta.playerauction.auction.model.AuctionStatus;
import com.minekarta.playerauction.common.SerializedItem;
import com.minekarta.playerauction.config.ConfigManager;
import com.minekarta.playerauction.economy.EconomyRouter;
import com.minekarta.playerauction.economy.EconomyService;
import com.minekarta.playerauction.notification.NotificationManager;
import com.minekarta.playerauction.storage.AuctionStorage;
import com.folia.compat.FoliaCompat;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class AuctionService {

    private final JavaPlugin plugin;
    private final Executor asyncExecutor;
    private final AuctionStorage auctionStorage;
    private final EconomyRouter economyRouter;
    private final ConfigManager configManager;
    private final NotificationManager notificationManager;
    private final com.minekarta.playerauction.transaction.TransactionLogger transactionLogger;
    private final com.minekarta.playerauction.mailbox.MailboxService mailboxService;

    private final ConcurrentHashMap<UUID, ReentrantLock> auctionLocks = new ConcurrentHashMap<>();

    public AuctionService(JavaPlugin plugin, Executor asyncExecutor, AuctionStorage auctionStorage,
            EconomyRouter economyRouter, ConfigManager configManager, NotificationManager notificationManager,
            com.minekarta.playerauction.transaction.TransactionLogger transactionLogger,
            com.minekarta.playerauction.mailbox.MailboxService mailboxService) {
        this.plugin = plugin;
        this.asyncExecutor = asyncExecutor;
        this.auctionStorage = auctionStorage;
        this.economyRouter = economyRouter;
        this.configManager = configManager;
        this.notificationManager = notificationManager;
        this.transactionLogger = transactionLogger;
        this.mailboxService = mailboxService;
    }

    public CompletableFuture<Boolean> createListing(Player player, ItemStack item, double price, Double buyNowPrice,
            Double reservePrice, long durationMillis) {
        SerializedItem serializedItem = SerializedItem.fromItemStack(item);
        Auction auction = new Auction(
                UUID.randomUUID(),
                player.getUniqueId(),
                serializedItem,
                price,
                buyNowPrice,
                reservePrice,
                System.currentTimeMillis(),
                System.currentTimeMillis() + durationMillis,
                AuctionStatus.ACTIVE,
                1 // Initial version
        );

        return auctionStorage.insertAuction(auction).thenApply(v -> true).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to create auction for " + player.getName());
            ex.printStackTrace();
            return false;
        });
    }

    public CompletableFuture<Boolean> buyItem(Player buyer, UUID auctionId) {
        return executeWithLock(auctionId, () -> {
            EconomyService economy = economyRouter.getService();
            return auctionStorage.findById(auctionId).thenCompose(optAuction -> {
                if (optAuction.isEmpty() || optAuction.get().status() != AuctionStatus.ACTIVE) {
                    buyer.sendMessage(configManager.getPrefixedMessage("errors.auction-not-found"));
                    return CompletableFuture.completedFuture(false);
                }

                Auction auction = optAuction.get();

                if (auction.seller().equals(buyer.getUniqueId())) {
                    buyer.sendMessage(configManager.getPrefixedMessage("errors.cannot-buy-own"));
                    return CompletableFuture.completedFuture(false);
                }

                double buyPrice = auction.price();

                // CRITICAL FIX: Reserve the auction FIRST by marking it as FINISHED
                // This prevents race conditions where multiple buyers can purchase the same
                // item
                Auction reservedAuction = auction.withStatus(AuctionStatus.FINISHED).withIncrementedVersion();
                return auctionStorage.updateAuctionIfVersionMatches(reservedAuction, auction.version())
                        .thenCompose(reserved -> {
                            if (!reserved) {
                                // Another buyer already purchased this, or auction was modified
                                buyer.sendMessage(configManager.getPrefixedMessage("errors.buy-fail"));
                                return CompletableFuture.completedFuture(false);
                            }

                            // Auction is now reserved, proceed with money transfer
                            return economy.withdraw(buyer.getUniqueId(), buyPrice, "Purchase item " + auction.id())
                                    .thenCompose(withdrawn -> {
                                        if (!withdrawn) {
                                            // Rollback: restore auction to ACTIVE
                                            Auction rollbackAuction = auction.withStatus(AuctionStatus.ACTIVE)
                                                    .withIncrementedVersion();
                                            auctionStorage.updateAuctionIfVersionMatches(rollbackAuction,
                                                    reservedAuction.version());
                                            buyer.sendMessage(
                                                    configManager.getPrefixedMessage("errors.not-enough-money"));
                                            return CompletableFuture.completedFuture(false);
                                        }

                                        double tax = configManager.getConfig().getDouble("auction.tax-percentage", 0);
                                        double sellerAmount = buyPrice * (1 - tax / 100.0);

                                        // Deposit to seller
                                        return economy.deposit(auction.seller(), sellerAmount, "Sold item")
                                                .thenCompose(v -> {
                                                    // Give item to buyer on the buyer's entity thread (inventory
                                                    // operations must be sync). Folia uses EntityScheduler;
                                                    // Paper uses main thread. If buyer retires (offline), mark
                                                    // delivery failed.
                                                    CompletableFuture<Boolean> itemDelivery = new CompletableFuture<>();

                                                    FoliaCompat.runEntity(plugin, buyer, () -> {
                                                        try {
                                                            ItemStack itemToGive = auction.item().toItemStack();
                                                            if (itemToGive == null) {
                                                                plugin.getLogger().severe(
                                                                        "Failed to deserialize item for auction "
                                                                                + auction.id());
                                                                itemDelivery.complete(false);
                                                                return;
                                                            }

                                                            if (buyer.getInventory().firstEmpty() == -1) {
                                                                // Inventory full, drop at player location
                                                                buyer.getWorld().dropItem(buyer.getLocation(),
                                                                        itemToGive);
                                                                buyer.sendMessage(configManager
                                                                        .getPrefixedMessage("errors.inventory-full"));
                                                            } else {
                                                                buyer.getInventory().addItem(itemToGive);
                                                            }

                                                            itemDelivery.complete(true);
                                                        } catch (Exception ex) {
                                                            plugin.getLogger().severe(
                                                                    "Error delivering item: " + ex.getMessage());
                                                            ex.printStackTrace();
                                                            itemDelivery.complete(false);
                                                        }
                                                    }, () -> {
                                                        // Retired: the buyer's entity is gone (they disconnected
                                                        // mid-purchase), so the task will never run. This path is
                                                        // fork-introduced - upstream used a plain main-thread runTask
                                                        // with no retired callback - and it is entirely normal, not an
                                                        // error. Completing false here would take the buyer's money,
                                                        // pay the seller, mark the auction FINISHED and then drop the
                                                        // item on the floor of the log, because the failure branch
                                                        // below only warns.
                                                        //
                                                        // Park the item in the buyer's mailbox instead, so the
                                                        // purchase completes and they collect it next login. Only if
                                                        // mailboxing also fails do we unwind the money: a refund plus
                                                        // a delivered item would be worse than either outcome alone.
                                                        plugin.getLogger().info("Buyer " + buyer.getName()
                                                                + " went offline during purchase of auction "
                                                                + auction.id() + "; routing item to their mailbox");
                                                        try {
                                                            mailboxService.addReturnedItem(
                                                                    buyer.getUniqueId(),
                                                                    auction.item(),
                                                                    "purchase-undelivered",
                                                                    auction.id())
                                                                    .whenComplete((ignored, mailEx) -> {
                                                                        if (mailEx != null) {
                                                                            refundFailedPurchase(auction, buyer,
                                                                                    buyPrice, sellerAmount, mailEx);
                                                                            itemDelivery.complete(false);
                                                                        } else {
                                                                            itemDelivery.complete(true);
                                                                        }
                                                                    });
                                                        } catch (Throwable mailEx) {
                                                            refundFailedPurchase(auction, buyer, buyPrice,
                                                                    sellerAmount, mailEx);
                                                            itemDelivery.complete(false);
                                                        }
                                                    });

                                                    return itemDelivery.thenApply(delivered -> {
                                                        if (delivered) {
                                                            // Success! Notify seller and log transaction
                                                            Player seller = Bukkit.getPlayer(auction.seller());
                                                            if (seller != null && seller.isOnline()) {
                                                                notificationManager.sendNotification(seller,
                                                                        "auction.sold", Map.of(
                                                                                "%item%",
                                                                                auction.item().toItemStack() != null
                                                                                        ? auction.item().toItemStack()
                                                                                                .getType().toString()
                                                                                        : "Unknown",
                                                                                "%price%",
                                                                                economy.format(sellerAmount)));
                                                            }

                                                            transactionLogger.log(auction, "SOLD", buyer.getUniqueId(),
                                                                    buyPrice);

                                                            // Broadcast purchase to all players
                                                            com.minekarta.playerauction.notification.BroadcastManager broadcastManager = ((com.minekarta.playerauction.PlayerAuction) plugin)
                                                                    .getBroadcastManager();
                                                            if (broadcastManager != null) {
                                                                String sellerName = seller != null && seller.isOnline()
                                                                        ? seller.getName()
                                                                        : plugin.getServer()
                                                                                .getOfflinePlayer(auction.seller())
                                                                                .getName();
                                                                ItemStack itemStack = auction.item().toItemStack();

                                                                broadcastManager.broadcastPurchase(
                                                                        buyer.getName(),
                                                                        sellerName,
                                                                        itemStack != null
                                                                                ? itemStack.getType().toString()
                                                                                : "Unknown",
                                                                        itemStack != null ? itemStack.getAmount() : 1,
                                                                        economy.format(buyPrice),
                                                                        buyer.getWorld());
                                                            }

                                                            return true;
                                                        } else {
                                                            // Item delivery failed - rare edge case
                                                            plugin.getLogger().warning(
                                                                    "Item delivery failed for auction " + auction.id());
                                                            return false;
                                                        }
                                                    });
                                                })
                                                .exceptionally(ex -> {
                                                    // Seller deposit failed - refund buyer and rollback auction
                                                    plugin.getLogger().severe("Seller deposit failed for auction "
                                                            + auction.id() + ": " + ex.getMessage());
                                                    economy.deposit(buyer.getUniqueId(), buyPrice,
                                                            "Refund - seller deposit failed");

                                                    // Rollback auction to ACTIVE
                                                    Auction rollbackAuction = auction.withStatus(AuctionStatus.ACTIVE)
                                                            .withIncrementedVersion();
                                                    auctionStorage.updateAuctionIfVersionMatches(rollbackAuction,
                                                            reservedAuction.version());

                                                    buyer.sendMessage(
                                                            configManager.getPrefixedMessage("errors.buy-fail"));
                                                    return false;
                                                });
                                    })
                                    .exceptionally(ex -> {
                                        // Withdrawal failed - rollback auction
                                        plugin.getLogger().severe("Withdrawal failed for auction " + auction.id() + ": "
                                                + ex.getMessage());
                                        Auction rollbackAuction = auction.withStatus(AuctionStatus.ACTIVE)
                                                .withIncrementedVersion();
                                        auctionStorage.updateAuctionIfVersionMatches(rollbackAuction,
                                                reservedAuction.version());

                                        buyer.sendMessage(configManager.getPrefixedMessage("errors.buy-fail"));
                                        return false;
                                    });
                        });
            });
        });
    }

    /**
     * Last-resort unwind for a purchase whose item could be neither delivered nor mailboxed.
     *
     * <p>Refunds the buyer what they paid and takes back what the seller was credited, so the money
     * does not stay moved for an item nobody received. Called only after mailbox insertion has also
     * failed - the mailbox is always the preferred outcome, because it completes the purchase instead
     * of undoing it.
     */
    private void refundFailedPurchase(Auction auction, Player buyer, double buyPrice, double sellerAmount,
            Throwable cause) {
        plugin.getLogger().severe("Could not deliver or mailbox item for auction " + auction.id()
                + "; refunding buyer " + buyer.getName() + " and reclaiming the seller payment"
                + (cause == null ? "" : ": " + cause));
        EconomyService economy = economyRouter.getService();
        economy.deposit(buyer.getUniqueId(), buyPrice, "Refund - item could not be delivered");
        economy.withdraw(auction.seller(), sellerAmount, "Reclaim - buyer's item could not be delivered");
        buyer.sendMessage(configManager.getPrefixedMessage("errors.buy-fail"));
    }

    public CompletableFuture<Boolean> cancelAuction(Player player, UUID auctionId) {
        return executeWithLock(auctionId, () -> auctionStorage.findById(auctionId).thenCompose(optAuction -> {
            if (optAuction.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            Auction auction = optAuction.get();
            if (!auction.seller().equals(player.getUniqueId())) {
                player.sendMessage(configManager.getPrefixedMessage("errors.not-your-auction"));
                return CompletableFuture.completedFuture(false);
            }
            if (auction.status() != AuctionStatus.ACTIVE) {
                player.sendMessage(configManager.getPrefixedMessage("errors.auction-not-active"));
                return CompletableFuture.completedFuture(false);
            }

            // Return item directly to seller.
            // 退还物品涉及玩家背包/掉落(实体操作), 必须在 player 实体所在区域线程执行.
            // 用 runEntity 路由并阻塞等待完成, 再更新拍卖状态.
            ItemStack itemToReturn = auction.item().toItemStack();
            java.util.concurrent.CountDownLatch returnLatch = new java.util.concurrent.CountDownLatch(1);
            FoliaCompat.runEntity(plugin, player, () -> {
                try {
                    if (player.getInventory().firstEmpty() == -1) {
                        // Inventory full, drop item at player location
                        player.getWorld().dropItem(player.getLocation(), itemToReturn);
                        player.sendMessage(configManager.getPrefixedMessage("inventory-full",
                                "Inventory full, item dropped on ground"));
                    } else {
                        player.getInventory().addItem(itemToReturn);
                    }
                } catch (Throwable ignored) {
                } finally {
                    returnLatch.countDown();
                }
            }, returnLatch::countDown);
            try {
                returnLatch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            Auction updatedAuction = auction.withStatus(AuctionStatus.CANCELLED).withIncrementedVersion();
            return auctionStorage.updateAuctionIfVersionMatches(updatedAuction, auction.version())
                    .thenApply(updated -> {
                        if (updated)
                            transactionLogger.log(updatedAuction, "CANCELLED", null, null);
                        return updated;
                    });
        }));
    }

    public void processExpiredAuctions() {
        int batchSize = 200;
        auctionStorage.findExpiredUpTo(System.currentTimeMillis(), batchSize).thenAccept(expiredAuctions -> {
            if (!expiredAuctions.isEmpty()) {
                plugin.getLogger().info("Processing " + expiredAuctions.size() + " expired auctions...");
            }
            for (Auction auction : expiredAuctions) {
                executeWithLock(auction.id(), () ->
                // Re-fetch to ensure it's still valid to process
                auctionStorage.findById(auction.id()).thenCompose(optAuction -> {
                    if (optAuction.isEmpty() || optAuction.get().status() != AuctionStatus.ACTIVE) {
                        return CompletableFuture.completedFuture(false); // Already processed
                    }

                    Auction current = optAuction.get();

                    // Expired - return item to seller
                    Player seller = Bukkit.getPlayer(current.seller());
                    if (seller != null && seller.isOnline()) {
                        ItemStack itemToReturn = current.item().toItemStack();
                        // 退还物品涉及 seller 背包/掉落(实体操作), 必须在 seller 区域线程.
                        // 用 runEntity 路由, 阻塞等待完成后再更新拍卖状态.
                        java.util.concurrent.CountDownLatch returnLatch = new java.util.concurrent.CountDownLatch(1);
                        Player sellerFinal = seller;
                        FoliaCompat.runEntity(plugin, seller, () -> {
                            try {
                                if (sellerFinal.getInventory().firstEmpty() == -1) {
                                    // Inventory full, drop item at seller location
                                    sellerFinal.getWorld().dropItem(sellerFinal.getLocation(), itemToReturn);
                                    sellerFinal.sendMessage(configManager.getPrefixedMessage("inventory-full",
                                            "Inventory full, expired item dropped on ground"));
                                } else {
                                    sellerFinal.getInventory().addItem(itemToReturn);
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                returnLatch.countDown();
                            }
                        }, returnLatch::countDown);
                        try {
                            returnLatch.await();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        notificationManager.sendNotification(seller, "auction.expired", Map.of(
                                "%item%", current.item().toItemStack().getType().toString()));
                    }

                    Auction updated = current.withStatus(AuctionStatus.EXPIRED).withIncrementedVersion();
                    return auctionStorage.updateAuctionIfVersionMatches(updated, current.version())
                            .thenApply(isUpdated -> {
                                if (isUpdated) {
                                    transactionLogger.log(updated, "EXPIRED");
                                }
                                return isUpdated;
                            });
                }));
            }
        });
    }

    // Utility methods
    private <T> CompletableFuture<T> executeWithLock(UUID auctionId, Supplier<CompletableFuture<T>> operation) {
        ReentrantLock lock = auctionLocks.computeIfAbsent(auctionId, k -> new ReentrantLock());
        return CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                return operation.get().join();
            } finally {
                lock.unlock();
                auctionLocks.remove(auctionId, lock);
            }
        }, asyncExecutor);
    }

    // Getters
    public CompletableFuture<List<Auction>> getActiveAuctions(int page, int limit,
            com.minekarta.playerauction.gui.model.AuctionCategory category,
            com.minekarta.playerauction.gui.model.SortOrder sortOrder) {
        return auctionStorage.findActiveAuctions(page, limit, category, sortOrder);
    }

    public CompletableFuture<List<Auction>> getPlayerAuctions(UUID playerId, int page, int limit) {
        return auctionStorage.findBySeller(playerId, limit, (page - 1) * limit);
    }

    public CompletableFuture<List<Auction>> getPlayerHistory(UUID playerId, int page, int limit) {
        return auctionStorage.findPlayerHistory(playerId, page, limit);
    }

    public CompletableFuture<Integer> getPlayerActiveAuctionCount(UUID playerId) {
        return auctionStorage.countActiveAuctionsByPlayer(playerId);
    }

    public CompletableFuture<Integer> getTotalActiveAuctionCount() {
        return auctionStorage.countAllActiveAuctions();
    }

    // Public getters for fields needed by other classes
    public ConfigManager getConfigManager() {
        return configManager;
    }

    public EconomyRouter getEconomyRouter() {
        return economyRouter;
    }

    public Executor getAsyncExecutor() {
        return asyncExecutor;
    }

    public AuctionStorage getAuctionStorage() {
        return auctionStorage;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }
}
