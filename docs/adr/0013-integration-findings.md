# 两个可选集成的实测结论

装上 PlaceholderAPI 2.12.3 与 Floodgate 2.2.5 后，各自暴露了一个不同性质的问题。

## PlaceholderAPI：之前的实现完全无效

旧实现用 `java.lang.reflect.Proxy` 去"实现" `PlaceholderExpansion`，装上 PAPI 后立刻失败：

```
PlaceholderAPI is installed but could not be hooked: me.clip.placeholderapi.expansion.PlaceholderExpansion is not an interface
```

`PlaceholderExpansion` 是**抽象类**，Proxy 只能代理接口。也就是说这个集成从写下第一行起就没工作过——此前只验证过"不装时不影响"，那不构成任何证据。

修法是把 PAPI 作为 `compileOnly` 依赖，写一个真正的子类。两个附带要点：

- **仓库要声明在项目级。** Gradle 默认 `PREFER_PROJECT` 模式，`subprojects { repositories { … } }` 会覆盖 `settings.gradle.kts` 里的 `dependencyResolutionManagement`——只写后者，构建报 `Could not find me.clip:placeholderapi`。
- **注册器必须与实现分离。** `KyokouPlaceholderExpansion` 在类级别引用 PAPI 的抽象类，因此没装 PAPI 的服务器加载它会失败。注册逻辑放在独立的 `PlaceholderRegistrar` 里，只在确认插件存在后才触碰实现类。

实测输出：`[PlaceholderAPI] Successfully registered internal expansion: kyokouauth [0.1.0-SNAPSHOT]`。

## Floodgate：结论曾被归因错误

### 旧结论（已推翻）

装上 Floodgate 2.2.5 后，一个名为 `IntFree` 的探针连接收到了 `Encryption Request`：

```
=== IntFree ===
RESULT=encryption_request
```

当时的推理是：服务端日志里没有我们的拒绝记录 → 这个包不是我们发的 → Floodgate 对所有连接强制发起加密握手 → 两个握手会撞车 → 因此 Floodgate 在场时注入层不安装。

这条推理有三处断裂：

1. **`IntFree` 是真实的正版名。** `api.mojang.com/users/profiles/minecraft/IntFree` 返回 HTTP 200。拿正版名当"非正版"对照，前提就不成立。
2. **"没有拒绝记录"不等于"不是我们发的"。** 我们的 handler 只在握手失败时打日志，发出 `Encryption Request` 时保持静默。
3. **没有人读过 Floodgate 的字节码。** 结论是从一次观察外推的。

### 实际机制（字节码取证）

反编译 `floodgate.jar` 2.2.5，登录阶段只有三条路径，没有一条发 `Encryption Request`：

- `SpigotInjector.inject()` 找到服务端 `ServerConnection` 里的 `List<ChannelFuture>`，换成 `CustomList`；`injectClient()` 对每个新连接 `addFirst("floodgate-init", …)`。不替换任何 Minecraft 类。
- `floodgate-init` 在 `channelActive` 时 `addLast("floodgate-injector", …)` 然后移除自己；真正的处理器由 `SpigotDataAddon.onInject` 用 `addBefore(packetHandler, "floodgate_data_handler", …)` 插入。
- `SpigotDataHandler extends CommonDataHandler`，读 Handshake 的 **hostname** 交给 `FloodgateHandshakeHandler`。没有 BedrockData 时返回 `ResultType.NOT_FLOODGATE_DATA`，原样放行。

`FloodgateCipher` 是 hostname 里那段 BedrockData 的 AES 包装，和登录阶段的 AES/CFB8 是两回事。基岩连接的 UUID 由 `SpigotDataHandler` 在握手阶段直接写进 `LoginListener.gameProfile`——那是身份，不是传输。

### 实测对照

同一个探针、同一个端口，只切换 Floodgate 是否加载：

| 名字 | Mojang 目录 | 没有 Floodgate | 有 Floodgate |
|---|---|---|---|
| `ZzKyokouNoSuchNm` | 404 | `login_success`<br>`8366f82a-0c29-387b-b2bd-0d88a68c2b8e` | `login_success`<br>`8366f82a-0c29-387b-b2bd-0d88a68c2b8e` |
| `IntFree` | 200 | `encryption_request` | `encryption_request` |

两列完全一致：**Floodgate 对 Java 连接是透明的。**

再加一条计数验证（`LoginProbe` 的第 5 个参数就为此而加）。Floodgate 在场时，正版名连接收到的 `Encryption Request` 总数是：

```
ENCRYPTION_REQUESTS=1
TOTAL_ENCRYPTION_REQUESTS=1
```

**一个，不是两个。** 旧结论最担心的失效模式——客户端收到两份挑战、答不了第二份——没有发生。

### 因此：共存，不是让位

[ADR-0016](0016-premium-name-single-door.md) 规定正版名字只有握手一扇门。让位等于把这扇门拆掉，装了 Floodgate 的服务器上正版玩家会永久进不来。

现在两者都装，职责天然正交：

- Floodgate 认领 **Bedrock 连接**——靠 handshake hostname 里的加密 BedrockData
- 我们认领 **Java 正版名**——靠登录阶段自己发起的挑战

一个 Bedrock 连接永远不会以正版名形态到达我们这里：Floodgate 给基岩名加前缀（默认 `.`），而 `GameName` 拒绝 `.`。`LoginInjector` 另外按前缀短路，不再为基岩名消耗 Mojang 目录配额。

启动时两条日志同时出现：

```
检测到 Floodgate 2.2.5-SNAPSHOT (b141-81b65cc)：基岩版玩家免于二次验证，正版握手照常装载，两者互不干扰。
正版握手已装载到登录管线。
```

### 并存之后才暴露的 bug：基岩分支从未可达

让握手与 Floodgate 并存之后，回头读 `LoginListener` 才发现基岩玩家根本进不来。

- Floodgate 给基岩名加前缀（`username-prefix: "."`）
- `GameName` 的正则是 `^[A-Za-z0-9_]{3,16}$`，拒绝 `.`
- `LoginListener` 原先**先** `GameName.parse`，**再**问 Floodgate 这是不是基岩玩家

于是未关联的基岩玩家在第一步就被判「非法用户名」踢出，永远到不了基岩分支。而 Floodgate 的账号关联（`player-link`）只能从 Java 侧在服务器内发起，所以这是个死锁：要进来才能关联，要关联才能进来。

已关联的玩家反而没事——关联后 `getCorrectUsername()` 返回不带前缀的 Java 名，能通过 `parse`。这也解释了为什么「基岩版免二次验证」这条分支此前一直停留在"未实跑"。

修法是把询问 Floodgate 提到名字解析之前，并给基岩前缀名一个受控入口 `GameName.forBedrock`。该入口只在一处调用，`parse` 依旧拒绝前缀，因此普通玩家无法借它把 `.Steve` 之类塞进名字表——实测以普通连接发 `.Steve` 仍被拒（`RESULT=disconnect`，提示"这是一个非法的 Minecraft 用户名"）。

另外，基岩分支对 `profiles.apply` 做了 try-catch：Floodgate 已经设好 profile（这正是它能带着 Bedrock UUID 到达此处的原因），重设只是为了皮肤，一个对名字有自己看法的服务端 API 不该把本来能进的登录变成踢人。

### UUID 不受影响（旧结论中仍然成立的部分）

先向数据库种一条绑定（`FloodTest` → XUID 987654321），再用能完成 Floodgate 加密握手的探针连接：

```
COMPRESSION=256
RESULT=login_success
UUID=00000000-0000-0000-0000-00003ade68b1
NAME=FloodTest
```

`00000000-0000-0000-0000-00003ade68b1` 正是 `UUID(0, 987654321)`。`setPlayerProfile` 在 Floodgate 在场时依然生效——加密是传输层的，profile 是在它之上决定的。

## 真实基岩客户端验证（Geyser 2.12.1 + Floodgate 2.2.5）

前几次测试都只能停在 Java 探针上，`isFloodgatePlayer` 恒为 false。这一节是第一次用真实基岩客户端把整条链路走通。

拓扑：

```
基岩客户端 --UDP 19132--> Geyser 2.12.1（Standalone，remote=127.0.0.1:25565）--> Paper 26.3
                                                                                 └─ Floodgate 2.2.5 + KyokouAuth
```

Geyser 与 Floodgate 共享同一个 `key.pem`——它是 16 字节 AES 密钥，不是 RSA 密钥对。Geyser 侧配置 `remote.auth-type: floodgate`。

结果：

```
[floodgate] Floodgate 玩家 .Qlickly 加入了 (UUID: 00000000-0000-0000-0009-01f59696f577)
.Qlickly joined the game
.Qlickly[/192.168.110.61:55463] logged in with entity id 58 at ([minecraft:overworld]-3.5, -60.0, 6.5)
```

三件事同时得到证实：

1. **免二次验证成立**：直接落在 `minecraft:overworld`，没有进 Limbo。基岩分支的 `verification` 为 null，`LoginListener.onJoin` 在 `entry.verification ?: return` 处提前返回，因此不会被冻结。
2. **UUID 是 `UUID(0, xuid)`**：`00000000-0000-0000-0009-01f59696f577` 高 64 位全 0，低 64 位即 XUID。
3. **修复前的代码进不来**：这个玩家名字是 `.Qlickly`，旧代码在 `GameName.parse` 处就判「非法用户名」踢掉了。上一节记录的 bug 是真实的，不是理论推演。

## Geyser 的 remote ping 警告不影响转发

Geyser 每 3 秒 ping 一次 Java 服务端，全程报 `Failed to ping the remote Java server`，但玩家照常进服。

查证过程：Paper 的 `enable-status=true`，普通登录完全正常，但标准 status 请求（协议 767 / 776 / 777 / -1 逐一试过）都被服务端直接关闭连接。这是 Paper 26.3 与 Geyser 2.12.1 在 status 交换上的不兼容，只影响 MOTD 与在线人数显示——实际转发走 `UpstreamSession`，与 ping 无关。

## Floodgate 的 player-link：URL 少一个斜杠

### 症状

玩家在 link.geysermc.org 完成关联，`/v2/link/java/{uuid}` 返回完整数据，但 Floodgate 仍认为从未关联，`getCorrectUniqueId()` 回退到 `UUID(0, xuid)`。

### 根因

同一个 API 的两个对称端点，对尾斜杠的处理不一致：

| 端点 | 不带斜杠 | 带斜杠 |
|---|---|---|
| `/v2/link/java/<uuid>` | 有数据 | 有数据 |
| `/v2/link/bedrock/<xuid>` | `{}` | 有数据 |

而 Floodgate 拼的正是后者、不带斜杠。字节码里看得很清楚：

```
11: ldc  "https://api.geysermc.org/v2/link/bedrock/"
13: StringBuilder.append(String)
17: UUID.getLeastSignificantBits()   → long
20: StringBuilder.append(long)
23: toString()
26: httpClient.get(...)
```

中间没有 `.append("/")`。响应是 `{}`，解析不出 `java_name` / `java_id`，于是当作未关联。

**这不是 Floodgate 拼错** —— `/java/` 用同样的写法能正常工作。是 API 两个端点之间的不一致，而 Floodgate 恰好踩在上面。

### 上游状态

- `GeyserMC/api`（后端仓库）`open_issues_count: 0`，不收 issue
- `GeyserMC/Floodgate` 搜索无相关记录
- `#675`「Feature: Allow configuring API URLs」至今 open，没有配置化的绕法

### 解法：自带的 linking 扩展

`floodgate-link` 模块实现 `CommonPlayerLink`，`getLinkedPlayer` 请求**带尾斜杠**的 URL；其余写操作一律拒绝（关联只能在网页上完成，服务端无法验证调用方拥有它声称的 Java 账号）。

打成 `floodgate-global-database.jar`，由主插件在启动时自动写入 Floodgate 数据目录，`player-link.type: global` 选中它。

两个必须知道的细节：

1. **文件名就是 type。** Floodgate 找的是 `floodgate-{type}-database.jar`，不是任意 `floodgate-*-database.jar`。名字对不上时的报错是 `Failed to find an implementation for type: <type>`，极易误判成"jar 没被加载"——这个错误我们犯过一次。
2. **jar 内需要 `init.json`**：`{"mainClass": "..."}`。

唯一的人工步骤是**重启一次**，因为 Floodgate 只在启动时扫描该目录。

### 被否掉的两条路

- **自研关联表**（`/kyokouauth link` + `kyokouauth_bedrock_link` 表）：能建立关联，但关联之后 UUID 仍然改不掉——Floodgate 在 NMS 层写完 `UUID(0, xuid)` 后绕过 `setPlayerProfile`。整套代码已删除。
- **反射替换 `PlayerLink` 实例**：不需要额外 jar，但依赖 `PlayerLinkHolder.instance` 的私有字段，Floodgate 换版本即断。

## 顺带修复：迁移版本号的主键冲突

`Database.migrate` 原本每步迁移都无条件 `INSERT` `schema_version`，而它是 `kyokouauth_meta` 里的一行共享记录，第二步必然撞主键。schema 只有一步时永远触发不到。已改为先 `UPDATE`、影响行数为 0 时才 `INSERT`。

## 基岩版皮肤：四条路都走过，都不通

结论：**已关联的基岩玩家看不到正版皮肤，接受默认皮肤。**

### 为什么 Bukkit 侧改不动

Floodgate 用反射构造登录 handler 并直接写入 profile，然后才 fire `AsyncPlayerPreLoginEvent`。事件里设置的 profile 会被丢弃——实测中事件携带的是 Java UUID，而进服的玩家仍是基岩 UUID。

### 四条路

| # | 方案 | 结果 |
|---|---|---|
| 1 | `AsyncPlayerPreLoginEvent#setPlayerProfile` | 被绕过，连 UUID 都没采纳 |
| 2 | SkinsRestorer 15.12.6 | 皮肤取到了（`plugins/SkinsRestorer/skins/*.playerskin` 里有完整 value + signature），但推不到基岩客户端 |
| 3 | 直接写 `Connection.spoofedProfile` | 写入成功，但进服时 `[probe]` 实测为 `0 entries`——被清空 |
| 4 | 同 3，但延迟一个事件循环轮次 | 仍是 0 |

第 4 条排除了"写入太早"这个解释：即使晚一轮写，最终值依然是空的。Floodgate 在完成自己的流程后会把该字段重置。

### 顺带查清的两个事实

- Paper 26.3 的 `packet_handler` 是 `net.minecraft.network.Connection`，不是 `LoginListener`。它的字段里有 `spoofedUUID: UUID` 和 `spoofedProfile: Property[]`——后者是 Paper 给代理用的字段，也是唯一合理的注入点。这个结构记录在 `BedrockProfilePatcher` 的注释里，代码保留但不再调用。
- `MojangSkinSource` 有一个真实缺陷：429 时不留退避，限流期间每次登录都会再打一次，只会延长限流窗口。已改为 429 后静默 60 秒。

### 再往下需要什么

只剩逆向 Geyser 送给基岩客户端的 `SkinData` 包。那是猜测性改动，且一处错误就会让基岩登录整体崩溃，因此没有做。

## 仍然没有验证的

- **MySQL / MariaDB 真实库**：五方言的建表语句只在 SQLite / H2 / PostgreSQL 上跑过。
- **1.9–1.11 的 `setPlayerProfile` 引入版本**：ADR-0004 记录的是"1.12 有、1.8.8 没有"，中间三档没有逐个核实。
- **占位符的实际取值**：只验证了注册成功，没有在游戏内用 `/papi parse` 读取过。
