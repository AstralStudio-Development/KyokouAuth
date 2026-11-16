# 最低支持版本 1.12，低于它拒绝启动

> **已被 [ADR-0017](0017-minimum-version-1-16.md) 取代。** 最低版本现为 1.16。本文保留为当时决策的记录。

这个插件在所有支持的版本上都用 `UUID(0, xuid)` 作为玩家的 UUID。低于 1.12 的服务端做不到，因此不支持。

## 为什么 1.8–1.11 做不到

指定 UUID 只有一条受支持的途径：`AsyncPlayerPreLoginEvent#setPlayerProfile`。它存在于 Paper 1.12 及以后，**不存在于 1.8–1.11**（用 `javap` 核实：1.8.8 没有，1.12.2 有）。

在那些版本上唯一能做的替代是 Bukkit 自己的名字派生 UUID，而它会破坏两件本插件赖以存在的事：

1. **改名即换号。** `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` 是名字的函数，管理员改名后玩家的数据留在旧名字下。
2. **Geyser 互通失效。** Floodgate 用 `UUID(0, xuid)` 表示基岩版玩家。Java 玩家如果走名字派生，同一个微软账户从两个门进来会是两个不同的玩家——这恰好否定了 [ADR-0002](0002-uuid-from-xuid.md) 采用 `UUID(0, xuid)` 的初衷。

（认证本身仍是微软的：名字被绑定到 XUID，只有能证明该 XUID 的人能用这个名字。所以名字派生不是冒充漏洞——它是身份模型错误。）

## 被否决的替代方案

**反射改写 `LoginListener.GameProfile`。** 技术上可行，但字段名每代不同（1.8 是 `i`），访问器是编译器合成的 `access$N`，而且 1.8 的离线 UUID 是在**服务端 tick 内**写入的，不在 `channelRead` 的同步路径上——注入要跨两个时点，且必须同时改出站 `Login Success` 包，否则会造成客户端与服务端身份分裂。

风险与收益不成比例：这条路径无法在没有真实客户端的情况下充分验证，而对 1.8–1.11 这几代（2014–2016）的服务器来说，维护一套 NMS 反射的收益远低于它的脆弱性成本。

**用名字派生 UUID 但"能跑"。** 见上，身份模型错误，且会静默破坏 Geyser 互通。

## 实现

`ProfileApplier.create` 在缺少 `setPlayerProfile` 时返回 null，`onEnable` 据此禁用插件并打印具体的版本、原因与替代后果。`LegacyProfileApplier` 已删除——不存在"降级路径"。

## 实测版本矩阵

| 版本 | 消息后端 | UUID | 结果 |
|---|---|---|---|
| Paper 1.8.8 | BungeeChat | — | 拒绝启动（低于 1.12） |
| Paper 1.12.2 | BungeeChat | `UUID(0, xuid)` | 正常 |
| Paper 1.16.5 | Adventure 4.x | `UUID(0, xuid)` | 正常 |
| Paper 26.3 | Adventure 5.x | `UUID(0, xuid)` | 正常 |

四条路径都用自研协议探针验证到「非法名被拒绝、合法名放行」。
