## Fork 修改说明

本 fork 为 PlayerAuctions 添加了 Folia/Canvas 兼容支持。

### 修改内容

- **FoliaCompat 工具类**：运行时检测 Folia，并将调度路由到对应的 Scheduler。
- **GUI 操作路由**：GUI 的打开/关闭/刷新背包操作路由到 `EntityScheduler`。
- **经济操作路由**：查余额/扣款/存款等经济操作路由到 `AsyncScheduler`。
- **物品交付/退还路由**：物品交付与退还路由到 `EntityScheduler`。
- **拍卖过期处理路由**：拍卖过期处理路由到 `EntityScheduler`。
- **升级 cloud 命令框架**：`2.0.0-beta.10` → `2.0.0-beta.16`（修复 `ItemStackParser` 在 MC 26.2 的 NMS 兼容问题）。
- **升级 paper-api 到 26.2**：`1.21.8-R0.1-SNAPSHOT` → `26.2.build.43-alpha`，`api-version` 改为 `26.2`。
- **plugin.yml**：`folia-supported: true`。

### 兼容性

| 服务端 | 兼容性 | 说明 |
|--------|--------|------|
| Folia | ✅ 兼容 | FoliaCompat 检测到 Folia 后路由到对应 Scheduler |
| Canvas | ✅ 兼容 | FoliaCompat 检测到 Folia 后路由到对应 Scheduler |
| Paper/Purpur | ✅ 兼容 | FoliaCompat 检测非 Folia 后走原 `BukkitScheduler` |

> 注：本 fork 基于 paper-api 26.2 构建，需要服务端为 MC 26.2+（Paper/Canvas/Folia 26.2 系列）。

### 构建方式

```bash
mvn package -DskipTests
```

**Java 版本要求**：Java 25

---

<div align="center">
  <h1>💰 PlayerAuctions v3.1.0</h1>
  <p><i>A modern, deeply integrated, and feature-rich Auction House plugin built exclusively for PaperMC 1.21.</i></p>
</div>

---

## ✨ Overview

**PlayerAuctions** provides a robust, intuitive, and scalable platform for players to buy and sell items securely. Fully integrated with Vault-based economies and the latest Paper 1.21 API, PlayerAuctions brings a premium marketplace experience to your server.

**What's new in v3.1.0?**
- **Mailbox Integration**: Canceled auctions and sold item revenues are securely routed to the player's personal mailbox, solving inventory management issues!
- **Cloud Command Framework (CCF)**: Extremely reliable, zero-latency commands with intelligent Tab Completions.
- **Adventure MiniMessage**: 100% native MiniMessage GUI item formatting including Hex, Gradients, and auto-italic removal.
- **Strictly Paper 1.21**: Dropped legacy version support for maximum performance and zero bloat.

## 🚀 Core Features

### 📬 Mailbox System (NEW)
No more dropped items when a player's inventory is full!
- **Canceled Listing Recovery**: Canceling an active auction routes the item safely into the Mailbox.
- **Revenue Deposit Receipts**: Sold items send a "Revenue Notification" to the seller's Mailbox. Claiming it deposits the Vault money—acting as a satisfying transaction receipt.
- **Expiration Protection**: Items automatically expire after a configurable duration (default: 30 days).

### 📢 Global Broadcast Notifications
- Keep the economy active! Broadcasts when items are listed and bought.
- **Clickable Links**: Click "View Auction" right in chat.
- **Configurable Range**: Broadcast to GLOBAL, WORLD, or NONE.

### 🎨 Premium GUI Interface
- **Native MiniMessage**: Fully supports Hex RGB colors and gradients for borders, titles, and formats.
- **Smart Category Filtering**: Quickly jump between categories like Blocks, Tools, Weapons, etc.
- **Dynamic Sorting**: Sort auctions by Price (Ascending/Descending), Time Left, and Newest.
- **Search Engine**: In-game robust search to filter active items.

### 🛡️ Secure Transactions
- **Atomic Locking**: Prevents duplicate purchase exploits (race conditions) using optimistic database locking.
- **Persistent JSON Storage**: Lightweight, concurrent-safe flat-file storage with auto-backups on writes.

---

## ⌨️ Commands & Permissions

> **Command Aliases**: `/ah`, `/auction`, `/auctionhouse`

| Command | Description | Permission | Default |
|---|---|---|---|
| `/ah` | Opens the main Auction House GUI | `playerauctions.use` | `true` |
| `/ah sell <price> [buy_now] [time]` | List the item in your hand on the AH | `playerauctions.sell` | `true` |
| `/ah myauctions` | View and manage your current listings | `playerauctions.use` | `true` |
| `/ah mailbox` | Open your Mailbox to claim money and items | `playerauctions.use` | `true` |
| `/ah search <keyword>` | Search for a specific item | `playerauctions.search` | `true` |
| `/ah categories` | Browse items by predefined categories | `playerauctions.categories` | `true` |
| `/ah history [player]` | View your (or others') transaction history | `playerauctions.history(.others)` | `true` (`op`) |
| `/ah notify <on/off>` | Toggle personal auction notifications | `playerauctions.notify` | `true` |
| `/ah reload` | Reload configuration files | `playerauctions.reload` | `op` |

**Admin Permissions**
- `playerauctions.admin` - Grants all administrative commands and bypasses.

---

## 🔧 Configuration Guide

PlayerAuctions is highly modular. Check your `config.yml` to customize the experience:

```yaml
auction:
  max-auctions-per-player: 5
  tax-percentage: 5.0 # Removes 5% from all successful sales

  broadcast:
    enabled: true
    on-listing: true
    on-purchase: true
    range: GLOBAL # GLOBAL, WORLD, NONE

mailbox:
  enabled: true
  retention-days: 30 # Days before Mailbox items auto-expire
```

### 💬 Placeholders (PlaceholderAPI)
Integrate statistics into your scoreboard or tablist!
- `%playerauctions_total_auctions%` - Tracks globally active auctions.
- `%playerauctions_player_listings%` - Tracks the viewer's active listing count.

---

## 📦 Installation

1. Ensure your server is running **Paper 1.21+**.
2. Install **Vault** and an economy provider (e.g., EssentialsX).
3. Drop `PlayerAuctions-3.1.0.jar` into your `plugins/` folder.
4. Restart the server.
5. *(Optional)* Modify `config.yml` and `messages.yml` using standard `<color>` MiniMessage syntax!

## 🤝 Support & Issues

Developed by **MinekartaStudio**.
Found a bug or need support? Please reach out via our GitHub repository issues tab or our Discord server.