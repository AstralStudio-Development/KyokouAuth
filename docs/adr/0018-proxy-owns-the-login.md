# 代理接管登录：双平台代理与库驱动的多版本 Limbo

取代 [ADR-0001](0001-single-jar-java8-baseline.md)、[ADR-0005](0005-limbo-over-kick.md)、[ADR-0009](0009-login-injection-gates-both.md)、[ADR-0014](0014-handshake-verified.md)。

插件从「Paper 后端上的 Bukkit 插件」变成「Velocity / Bungee 代理插件」。`platform-bukkit` 与 `floodgate-link` 整体删除，身份算法与存储留在 `core`；`proxy-common` 共享认证接线、验证会话、配置和命令业务，两个平台只转换原生事件/profile/组件。验证大厅由统一 `limbo` 入口管理按协议隔离的 Engine。

## 为什么必须搬

后端插件在两个地方够不到：

1. **`/server` 不受约束。** 命令由代理注册并消费，`UpstreamBridge.handleChat` 里 `dispatchCommand` 返回 true 后就 `CancelSendSignal`，请求根本不到后端。后端把玩家冻在原地、取消一切事件，也拦不住这一条。
2. **正版握手与代理转发冲突。** 后端插件靠替换 `ServerConnection` 里的 channel 完成登录注入；同一个后端一旦开启 `velocity` 转发，两者对同一条连接的预期不一致，代理直接以 `Backend server is online-mode!` 断开。

第 1 条是设计缺陷，第 2 条是直接动因。

## Limbo 协议实现：撤销手写编解码器

原来的手写协议实现已整体替换为 MCProtocolLib。各协议使用独立固定版本库和命名空间，避免 Gradle 冲突解析把多个版本合并为最新版；原有 26.3-4 属于 777 Engine。LimboAPI 与桌面 Narcissus 只作本地参考，不复制源码、不引入插件依赖。

- Limbo 子模块集中在 `limbo/api`、`limbo/runtime`、`limbo/text/*`、`limbo/protocols/<id>`，共享源码和生成器也留在 `limbo` 内；通过settings的projectDir映射保持逻辑任务名不变，不增加空聚合项目。生成keep按manifest协议重定位，不依赖旧物理目录名。
- 支持 767～777 共 11 个协议，即 1.21～26.3 正式版，不自动支持未来版本/快照。每个 Engine 使用对应官方注册表与来源/校验和；配置阶段协商 known packs 后仍发送完整 registry 与 tags，不跨版本复用 777 数据。
- MCProtocolLib 提供编解码、NBT 文本与网络传输；本项目管理可信代理身份、共享生命周期和空世界。此前把11份整栈逐版本重定位导致每包123MiB，已撤销该打包决策：协议实现仍隔离，普通依赖只一份，Adventure按767～769/770～775/776～777三个文本族共享；Netty 4.2 compileOnly使用已验证平台提供的实现，Engine只拥有/关闭自己的NIO groups。公共 Interface仍只使用JDK/自有模型。
- 构建期以固定上游源码和完整 registry 校验生成明确ID、原厂factory及keep清单；生产只用稀疏Codec，不携带完整MinecraftCodec或客户端认证监听器。不手写framing/NBT/包字段，不运行时下载协议库。
- 必要入站包继续typed；只对清单内已知、非terminal、无需业务处理的PLAY帧消费并忽略当前payload，不承诺验证这些未使用字段的内容。未知ID、状态切换、握手/config、心跳及READY信号不静默吞掉。
- R8仅处理Limbo图的未用成员，名称和类型继承保持；精确保留反射、泛型、sealed文本契约、SPI及DER锚点，core/平台代理代码不重写。正式产物目标10MiB、20MiB硬门槛，默认构建不合格即失败，不能只交未裁剪classes或隐藏到额外下载。
- 26.3-4 的 previous game mode 仍用旧式 BYTE -1 表示缺省，原版要求 OPTIONAL_VAR_INT 0；`Login263` 保留一处版本固定的兼容修正，并由原版 codec 及缺省语义断言独立校验。
- 原版 codec 验包、生命周期/隔离测试、真实原版客户端退出加载界面是不同层次的验收，不能用“构建成功”或手写探针发送 ACK 冒充渲染成功。
- 各 Engine 的 loopback 随机端口只作为平台代理私有连接目标持有：Velocity 用 `createRawRegisteredServer`，Bungee 用未加入公开 servers 表的 `ServerInfo`。不得发布到原生 `/server` 列表、补全或公开服务器发现；不是靠权限过滤来掩盖。共享 Gate 通过 adapter 自有映射按协议选择，并保留 exact 对象、同名冲突和关闭后失效校验。未验证连接只能进入自己的 Engine，在首连无 currentServer、大厅失败、切服或自动 failover 时均拒绝进入真实后端。
- Velocity 保留 modern HMAC。Bungee 的 legacy 握手 UUID/IP/name 不可信，必须附加当前进程签发、绑定 exact 连接/身份/协议/目标、短 TTL 的一次性票据，由 authority 原子兑换；不在 HMAC 失败后降级。Bungee profile 仅通过启动时核对签名的窄 bridge 补齐，UUID 使用公共 setter；真实后端连接前清除票据。
- 标题在有效 challenge 返回后仅发一次，stay 对齐剩余验证 deadline；会话结束或重启前清理，不再周期性重发淡入动画。
- 私有服务器不公开与认证前命令限制是两个边界。`verification.blocked-commands` 默认仅含 `server`，由共享 Gate 按当前 exact 连接的认证状态判断，控制台不限制，认证后不增加或恢复其他插件原先拒绝的权限。Velocity 使用原生执行事件及 mutable available-command tree；legacy TabCompleteEvent 不冒充现代命令树。
- Bungee 2111 没有公开命令树事件，故采用启动签名校验的窄前端通道适配，仅在原生 GAME 状态用 Bungee 自身 Commands codec/encoder 过滤 typed、wrapper 与原始透传路径；不自写节点字段或协议 ID 表，不修改全局 initializer。现代补全在 TabExecutor 前拦截，事务关联仅保留最多64条ID/布尔，不保留参数；未知现代事务在受限时丢弃，关闭时回收。过滤不改原树、不建永久watch或跨连接树缓存，认证后正常新后端树自然恢复。

## 直接后果

| 变化 | 说明 |
|---|---|
| 删除 `platform-bukkit` | 整模块。`CachedIdentityRepository` 移入 `core`，消息目录改为 `core` 的 `MessageCatalogue` |
| 删除 `floodgate-link` | 基岩版联动只有 Bukkit 形态；代理侧要重做，见待办 |
| 正版名字由代理完成会话验证 | `platform-bukkit` 自己往 `ServerConnection` 里注入 `PremiumHandshakeHandler` 来接管正版握手；代理层不再需要那段注入，`PreLoginEvent.forceOnlineMode()` 就是同一件事：只对 Mojang 拥有的名字开一次会话验证，正版客户端自动应答，玩家零操作，冒名客户端在这里断掉。绑定在验证通过后的 `LoginEvent` 里补记 |
| 登录判定必须完成后才能继续 | Velocity 4.3.1 的 PreLogin 不是 AwaitingEvent，必须在处理器返回前决定；Bungee 则同步注册 intent 后交共享 IO，恰好完成一次。Bungee onlineMode/UUID setter 必须在 PreLogin 的 USERNAME 阶段调用，不能拖到 Login |
| 验证大厅按协议隔离 | 767～777 各用固定库、完整注册表与专属目标；未知协议拒绝，不猜包布局或跨 Engine 降级 |

## 被否掉的替代方案

- **断开 + 可点击链接，不做大厅。** 实现最省，但玩家必须在断线界面点完链接、再手动重连，且插件无法在成功后自动放行。体验退步到「踢人式验证」。
- **只在 `PreLoginEvent` 拒/放，不搬后端。** 解决不了 `/server`：验证中的玩家仍会被送到真实后端。
- **保留 Bukkit 插件、改用 BungeeCord 转发。** 换一种转发模式而已，登录注入与转发模式的冲突依旧。

## 代理基岩接入预研（尚未实现）

本节仅记录接口与接入边界；**当前没有代理基岩认证能力**，未增加依赖、配置开关或放行分支。以下核对采用官方 API 2.2.5-SNAPSHOT 文档及固定源码构建 `2.2.5-20260917.145236-21`，不是宣称兼容所有 Floodgate/代理版本。

### 已证实的接口与信任边界

- 两平台均在 **Velocity / Bungee 本进程**部署对应 Floodgate，Geyser 使用 `auth-type: floodgate`。可信来源是受控 Geyser/Floodgate 链路及 Floodgate 对当前连接的处理结果，不是“装了 Geyser”这个事实；`key.pem` 能使基岩连接绕过 Java 认证，必须保密。后端 Floodgate 的数据转发不是本项目代理认证的替代品。[FG-SETUP]
- 官方入口是 `FloodgateApi.getInstance()`；`isFloodgatePlayer(UUID)` / `getPlayer(UUID)` 可在 pre-login 调用。身份字段取 `FloodgatePlayer.getXuid()`（Xbox XUID 字符串）、`getCorrectUniqueId()` / `getCorrectUsername()`（实际服务端 UUID/name），并读取 `isLinked()` / `getLinkedPlayer()`；不能把 linked 玩家仍当作 `getJavaUniqueId()` 所表达的未关联 UUID。[FG-API] [FG-PLAYER]
- **公开 UUID 查询不是 exact-connection 凭证。** 固定源码的 `SimpleFloodgateApi.getPlayer` 会查在线表、linked UUID，甚至查断线后最长 20 秒的 `pendingRemove`。因此 `getPlayer(clientUuid) != null`、`isFloodgatePlayer(clientUuid)` 或 UUID/name 相等均不足以放行当前连接。`isFloodgateId` 仅判 UUID 格式，`createJavaPlayerId`、`getXuidFor(gamertag)` 等也只是转换/公开查询，不是登录证明。[FG-API-JAVADOC] [FG-CORE-SOURCE]
- 官方两平台代理 listener 的身份来源都是**该原生连接 Channel 上由 Floodgate 写入的 player attribute**；共享握手处理完成数据解密/校验、构造身份后写入。还必须保留该连接的 kick/拒绝结果。客户端自报 UUID、名字、前缀、hostname、legacy forwarding 字段或 IP 只能作查找/一致性校验，不能替代这条来源链。[FG-VELOCITY-SOURCE] [FG-BUNGEE-SOURCE] [FG-CORE-SOURCE]

### 时序：源码事实与待实施工作分开

| 平台 | 固定官方源码中已证实的时序 | 本项目待实施的接线 |
|---|---|---|
| Velocity | `VelocityListener.onPreLogin` 在 `EARLY` 读取当前 Channel 的身份/kick，缓存到 `InboundConnection`，并为 Floodgate 连接 force-offline；`onGameProfileRequest` 在 `EARLY` 使用这份连接缓存设置实际 UUID/name，linked 皮肤查询后才 resume。[FG-VELOCITY-SOURCE] | 在现有 `LAST` LoginGate 进行 Java 名字解析/认证决策前，取得同一连接的可信证据；profile 阶段不得把 Floodgate 的最终 UUID 覆盖成 Java provisional UUID；`LoginEvent` 再核对同一连接及最终 profile，初服/切服仍只由共享 Gate 放行。不能从 pre-login 客户端 UUID 推导身份。 |
| Bungee | `BungeeListener.onPreLogin` 在 `LOWEST` 从当前 Channel 读取身份/kick，设置 offline、最终 UUID/name；`LoginEvent`/`PostLoginEvent` 的 UUID 查询只供后续处理。[FG-BUNGEE-SOURCE] | 现有 `HIGHEST` PreLogin adapter 须在转异步前捕获 connection-bound 证据，并在其 USERNAME intent 内完成一致的认证模式/profile 决策；不能在 Login 时补改 UUID。Login 后仍复核证据，PostLogin/ServerConnect 交共享 Gate；不得以 Floodgate 设置了 offline 为认证通过。 |

**尚缺的关键能力是 connection-bound bridge，而非几个 UUID API 调用。** 公共 `FloodgateApi` 没有接收原生连接/Channel 的认证查询；候选方案是隔离在两个平台 adapter 的窄桥，针对明确版本核对官方实现，从 exact 原生连接读取 Floodgate 身份及失败状态，以该对象的公共接口读取上述字段；若再调用 `getPlayer`，返回对象必须与当前连接属性为同一实例，不能反向用 UUID 查询结果建立连接信任。该私有实现接缝不是稳定官方 API，必须签名/版本自检，任何不匹配拒绝基岩接入；本次没有实现、依赖或保证该桥。已废弃的 `HandshakeData.getChannel()` 虽暴露连接，但握手 callback 也可能在错误路径执行，不能只看 `isFloodgatePlayer()` 就签发认证。[FG-API-JAVADOC] [FG-HANDSHAKE] [FG-CORE-SOURCE]

### 单一 LoginGate 与主体规则

1. 未来只能向现有共享 `LoginGate` 增加显式的可信 Floodgate 身份输入，不另开“基岩直接连接真实后端”的旁路，不模拟设备码回调。输入必须绑定本进程 `sessionId`、原生连接/Channel 对象、提供方生命周期及不可变的 XUID/最终 UUID/name/linked 快照；Login 再核对同一对象仍活跃、证据未替换且无拒绝。异步结果、断线、重连、同名/同 UUID 并发、超时和停用均只能影响原连接，禁止从 UUID/name/IP 缓存继承认证或会话。
2. 名字与主体必须拆开讨论：当前 `GameName.parse` 只接受 Java 格式，而 Floodgate 可添加前缀并替换空格；不能为所有客户端放宽解析或去掉前缀冒用 Java 名字。现有 `Identity.ofXuid` 使用未 linked Floodgate 相同的 XUID→UUID 布局，这只说明**数值映射可对齐**，不等于命名/绑定/资料可自动合并。Floodgate linked 玩家可能直接使用 Java UUID/name；官方 linking 是另一套账户关联，不能当作本次 Mojang session 验证，也不能自动合并为已有 Java 微软主体。命名、linked、双端数据归属与持久绑定迁移均尚未决定；在策略与验收完成前拒绝这类接入。[FG-FAQ] [FG-PLAYER] [FG-LINKING]
3. 可信 XUID 只表示基岩来源证明，不代表拥有任意 Java 名字：现有 Mojang 名字优先、该连接必须完成 Mojang session、UUID 一致、冲突拒绝规则不降低。不得用 Floodgate linked 记录、同名/XUID 或 offline 模式覆盖 `MojangSession` 判定。
4. 缺失/未就绪/不兼容的 Floodgate、缺失 connection-bound 证据、空值/非法 XUID、profile 不一致、校验异常、已有拒绝或 deadline 到期，一律不能获得基岩认证；已按基岩处理的连接直接拒绝，绝不退回未认证 Java/设备码分支。未声明/未证明为基岩的连接仍只走原 Java 规则，失败不会变成离线放行。当前代码本就没有本节所述新入口。

### 实施前必要验收（本次未执行）

- 两平台代理分别覆盖：真实 Geyser+Floodgate 未 linked 登录的 XUID/最终 profile/事件顺序；缺插件、错误 key、失败状态和窄桥版本不符拒绝；保留其他插件已作出的拒绝。
- Java 攻击连接仿造 XUID UUID、前缀/name/hostname/转发字段时不能取走其他连接身份；覆盖同名/同 UUID 并发、`pendingRemove` 残留、旧异步回调、断线重连/停用，证明认证不跨连接继承。
- 未 linked/linked、前缀/空格、已有 Java 微软绑定与 Mojang 名字冲突分别有明确预期；未授权连接在初服、`/server`、插件切服及 failover 均不能触达真实后端。兼容性与真实双端验收通过前不得宣布基岩支持。

官方来源：

- [FG-SETUP]：代理部署、`auth-type`、密钥与后端转发边界。
- [FG-API]、[FG-PLAYER]、[FG-HANDSHAKE]：公开接口、字段及废弃握手接口契约。
- [FG-CORE-SOURCE]：源码包内 `SimpleFloodgateApi`、`FloodgateHandshakeHandler`、`CommonDataHandler`；[FG-VELOCITY-SOURCE] / [FG-BUNGEE-SOURCE]：对应 `*Listener`、`*ProxyDataHandler`。源码仅在内存读取，未加入工程。
- [FG-FAQ]、[FG-LINKING]：名字/UUID 格式与官方账户关联语义；不构成本项目自动合并的授权。

[FG-SETUP]: https://geysermc.org/wiki/floodgate/setup/
[FG-API]: https://geysermc.org/wiki/floodgate/api/
[FG-API-JAVADOC]: https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/FloodgateApi.html
[FG-PLAYER]: https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/player/FloodgatePlayer.html
[FG-HANDSHAKE]: https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/handshake/HandshakeData.html
[FG-CORE-SOURCE]: https://repo.opencollab.dev/maven-snapshots/org/geysermc/floodgate/core/2.2.5-SNAPSHOT/core-2.2.5-20260917.145236-21-sources.jar
[FG-VELOCITY-SOURCE]: https://repo.opencollab.dev/maven-snapshots/org/geysermc/floodgate/velocity/2.2.5-SNAPSHOT/velocity-2.2.5-20260917.145236-21-sources.jar
[FG-BUNGEE-SOURCE]: https://repo.opencollab.dev/maven-snapshots/org/geysermc/floodgate/bungee/2.2.5-SNAPSHOT/bungee-2.2.5-20260917.145236-21-sources.jar
[FG-FAQ]: https://geysermc.org/wiki/floodgate/faq/
[FG-LINKING]: https://geysermc.org/wiki/floodgate/linking/
