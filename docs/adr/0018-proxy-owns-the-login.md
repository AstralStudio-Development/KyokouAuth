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
| 删除 `floodgate-link` | 删除旧 Bukkit 形态及其自动映射假设；代理侧后续分别增加 connection-bound 证明与显式角色选择，见下文，不迁移历史授权 |
| 正版名字由代理完成会话验证 | `platform-bukkit` 自己往 `ServerConnection` 里注入 `PremiumHandshakeHandler` 来接管正版握手；代理层不再需要那段注入，`PreLoginEvent.forceOnlineMode()` 就是同一件事：只对 Mojang 拥有的名字开一次会话验证，正版客户端自动应答，玩家零操作，冒名客户端在这里断掉。绑定在验证通过后的 `LoginEvent` 里补记 |
| 登录判定必须完成后才能继续 | 早期实现将 Velocity PreLogin 不是 AwaitingEvent 视为必须同步返回的限制；后续固定 4.3.1 接线已改用原生 EventTask/continuation 暂停推进，不能再据此宣称 PreLogin 无法异步。Bungee 同步注册 intent 后交共享 IO，恰好完成一次。两者都必须在原生 Player/profile 落定前完成决策；Bungee onlineMode/UUID setter 仍限 PreLogin 的 USERNAME 阶段 |
| 验证大厅按协议隔离 | 767～777 各用固定库、完整注册表与专属目标；未知协议拒绝，不猜包布局或跨 Engine 降级 |

## 被否掉的替代方案

- **断开 + 可点击链接，不做大厅。** 实现最省，但玩家必须在断线界面点完链接、再手动重连，且插件无法在成功后自动放行。体验退步到「踢人式验证」。
- **只在 `PreLoginEvent` 拒/放，不搬后端。** 解决不了 `/server`：验证中的玩家仍会被送到真实后端。
- **保留 Bukkit 插件、改用 BungeeCord 转发。** 换一种转发模式而已，登录注入与转发模式的冲突依旧。

## 代理基岩接入（第一阶段历史基线）

以下保留第一阶段的实现决策：可选逐连接证明 Adapter 与共享 Gate，`bedrock.enabled` 默认 false；当时仅接入未 linked、保留隔离前缀的 Floodgate 身份，不写 Java 名字绑定/IP 会话，也不自动合并 linked 的 Java UUID。后续角色选择是单独的显式扩展，见下节；**关闭 `role-selection.enabled` 时 linked 拒绝仍成立**，不能把新功能倒写成第一阶段已支持。

本节依据官方 Floodgate 2.2.5 b141 及固定源码 `2.2.5-20260917.145236-21`，不是宣称兼容所有 Floodgate/代理版本。真实 Bedrock/Xbox 账号层仍须独立验收。

### 已证实的接口与信任边界

- 两平台均在 **Velocity / Bungee 本进程**部署对应 Floodgate，Geyser 使用 `auth-type: floodgate`。可信来源是受控 Geyser/Floodgate 链路及 Floodgate 对当前连接的处理结果，不是“装了 Geyser”这个事实；`key.pem` 能使基岩连接绕过 Java 认证，必须保密。后端 Floodgate 的数据转发不是本项目代理认证的替代品。[FG-SETUP]
- 官方入口是 `FloodgateApi.getInstance()`；`isFloodgatePlayer(UUID)` / `getPlayer(UUID)` 可在 pre-login 调用。身份字段取 `FloodgatePlayer.getXuid()`（Xbox XUID 字符串）、`getCorrectUniqueId()` / `getCorrectUsername()`（实际服务端 UUID/name），并读取 `isLinked()` / `getLinkedPlayer()`；不能把 linked 玩家仍当作 `getJavaUniqueId()` 所表达的未关联 UUID。[FG-API] [FG-PLAYER]
- **公开 UUID 查询不是 exact-connection 凭证。** 固定源码的 `SimpleFloodgateApi.getPlayer` 会查在线表、linked UUID，甚至查断线后最长 20 秒的 `pendingRemove`。因此 `getPlayer(clientUuid) != null`、`isFloodgatePlayer(clientUuid)` 或 UUID/name 相等均不足以放行当前连接。`isFloodgateId` 仅判 UUID 格式，`createJavaPlayerId`、`getXuidFor(gamertag)` 等也只是转换/公开查询，不是登录证明。[FG-API-JAVADOC] [FG-CORE-SOURCE]
- 官方两平台代理 listener 的身份来源都是**该原生连接 Channel 上由 Floodgate 写入的 player attribute**；共享握手处理完成数据解密/校验、构造身份后写入。还必须保留该连接的 kick/拒绝结果。客户端自报 UUID、名字、前缀、hostname、legacy forwarding 字段或 IP 只能作查找/一致性校验，不能替代这条来源链。[FG-VELOCITY-SOURCE] [FG-BUNGEE-SOURCE] [FG-CORE-SOURCE]

### 时序：官方处理与本项目接线

| 平台 | 固定官方源码中已证实的时序 | 本项目接线与复核 |
|---|---|---|
| Velocity | `VelocityListener.onPreLogin` 在 `EARLY` 读取当前 Channel 的身份/kick，缓存到 `InboundConnection`，并为 Floodgate 连接 force-offline；`onGameProfileRequest` 在 `EARLY` 使用这份连接缓存设置实际 UUID/name，linked 皮肤查询后才 resume。[FG-VELOCITY-SOURCE] | 在现有 `LAST` LoginGate 进行 Java 名字解析/认证决策前，取得同一连接的可信证据；profile 阶段不得把 Floodgate 的最终 UUID 覆盖成 Java provisional UUID；`LoginEvent` 再核对同一连接及最终 profile，初服/切服仍只由共享 Gate 放行。不能从 pre-login 客户端 UUID 推导身份。 |
| Bungee | `BungeeListener.onPreLogin` 在 `LOWEST` 从当前 Channel 读取身份/kick，设置 offline、最终 UUID/name；`LoginEvent`/`PostLoginEvent` 的 UUID 查询只供后续处理。[FG-BUNGEE-SOURCE] | 现有 `HIGHEST` PreLogin adapter 须在转异步前捕获 connection-bound 证据，并在其 USERNAME intent 内完成一致的认证模式/profile 决策；不能在 Login 时补改 UUID。Login 后仍复核证据，PostLogin/ServerConnect 交共享 Gate；不得以 Floodgate 设置了 offline 为认证通过。 |

**关键能力是 connection-bound bridge，而非几个 UUID API 调用。** `VelocityFloodgate` / `BungeeFloodgate` 只定位当前原生 Channel、提供方安装代次与属性读视图；`FloodgateRuntime` 集中通过提供方 ClassLoader 校验和读取其公开 Interface。`BedrockProof` 持有实际 Channel 的 opaque identity，并重新核对属性对象、kick、提供方代次、API singleton、XUID/UUID/name/linked快照；`getPlayer` 返回对象必须与该 Channel 的属性为同一实例，不能反向用 UUID 查询建立信任。原生接缝必须签名/版本自检，任何不匹配拒绝；SDK不进入本插件产物。已废弃的 `HandshakeData.getChannel()` callback 可能运行在错误路径，不能只看 `isFloodgatePlayer()` 授信。[FG-API-JAVADOC] [FG-HANDSHAKE] [FG-CORE-SOURCE]

### 单一 LoginGate 与主体规则

1. 通过现有共享 `LoginGate` 的显式可信 Floodgate 输入准入，不另开“基岩直接连接真实后端”的旁路，不模拟设备码回调或伪造 Java BoundIdentity。输入必须绑定本进程 `sessionId`、原生连接/Channel 对象、提供方生命周期及不可变的 XUID/最终 UUID/name/linked 快照；Login 再核对同一对象仍活跃、证据未替换且无拒绝。异步结果、断线、重连、同名/同 UUID 并发、超时和停用均只能影响原连接，禁止从 UUID/name/IP 缓存继承认证或会话。
2. 名字与主体必须拆开讨论：当前 `GameName.parse` 只接受 Java 格式，而 Floodgate 可添加前缀并替换空格；不能为所有客户端放宽解析或去掉前缀冒用 Java 名字。现有 `Identity.ofXuid` 使用未 linked Floodgate 相同的 XUID→UUID 布局，这只说明**数值映射可对齐**，不等于命名/绑定/资料可自动合并。Floodgate linked 玩家可能直接使用 Java UUID/name；官方 linking 是另一套账户关联，不能当作本次 Mojang session 验证，也不能自动合并为已有 Java 微软主体。首阶段保持提供方最终非 Java 前缀名字，只作显示名而不进入 Java 名字表；不写或复用 Java IP 会话，不自动迁移已有绑定。未 linked 使用现有 XUID→UUID；第一阶段及角色选择关闭时 linked 明确拒绝。下节的后续策略是独立角色选择，仍不允许自动合并，也不能跳过单独验收。[FG-FAQ] [FG-PLAYER] [FG-LINKING]
3. 第一阶段及普通 Java 入口中，可信 XUID 不代表拥有任意 Java 名字：Mojang 名字优先、原生 session 验证、UUID 一致和冲突拒绝规则不降低。本轮基岩选角仅允许“当前强 Floodgate XUID + 当前官方 global 关联”这条独立入口取得 J，不将 linked 记录伪装为 Mojang session；同名、公开 XUID/UUID 查询、历史关系和 offline 模式都不能覆盖普通 `MojangSession` 判定。
4. 缺失/未就绪/不兼容的 Floodgate、缺失 connection-bound 证据、空值/非法 XUID、profile 不一致、校验异常、已有拒绝或 deadline 到期，一律不能获得基岩认证；已按基岩处理的连接直接拒绝，绝不退回未认证 Java/设备码分支。未声明/未证明为基岩的连接仍只走原 Java 规则，失败不会变成离线放行。新入口默认禁用；显式开启却缺失/不兼容代理 Floodgate 时初始化失败，普通 Java-only 默认安装不强加载 SDK。

### 原生形态与分层验收

官方 Bungee Floodgate 的 PostLogin 皮肤流程可以建立 `LoginResult(null, null, properties)`，其中 id/name 本就不使用。Bridge 仅在当前已接纳且仍有效的 exact Bedrock proof、offline 模式及 pending/native UUID/name 全部一致时提供 semantic view；写回仅更新 properties，保留原容器与双 null，不在 Java/半 null/错误非 null 资料上兜底或修补身份。真实后端 ticket stripping 也使用同一受限 seam。

- 单测及原生事件覆盖当前/换 Channel、stale公开API记录、提供方代次、同名/同UUID、linked、profile篡改、已有拒绝和零Java绑定/会话/设备码IO；不能从缓存或前缀继承授权。
- 隔离工具固定官方 Floodgate 2.2.5 b141，使用全新测试密钥、官方 AesCipher/BedrockData、真实代理解密处理器和全量上游客户端。正负例检查最终 Native/后端UUID、零非法握手及拒绝分类，不把手塞属性的mock当密码链集成。测试字段遵守原生 hostname 长度，避免把解码前关闭误当 linked 策略拒绝。
- 官方密码链与受控 Java 传输验证**不等于**真实 Geyser/Bedrock/Xbox 账号端到端验证；该层在无真实账号操作条件时必须明确标为未验收，不读取启动器token或用户key。结果与产物hash按当次报告记录。

## 关联角色选择：基岩低重连契约

本轮按用户同意的范围仅优化基岩双角色。Java 保留基础微软 U 的设备码认证与 Mojang 正版直登；设备码后跨到另一角色的高级流程本轮不支持，不以弱身份续接替代，也不称 Java 完整双角色验收。

### 主体、关系和角色分离

`role-selection.enabled` 默认 false，`bedrock.enabled` 保持独立。基岩入口先取得当前 Channel 的可信 Floodgate 强 XUID 证明，再核验固定官方 global API；仅成功的无关联响应可认定 NotLinked，空体、限流、网络/JSON 错误等均为 Unavailable 并拒绝。原生 Floodgate 关联元数据与新查询矛盾时拒绝，不修改提供方对象或 linked 字段来消除冲突，不将本地关联假装成 global 模式。

- U 使用本服 XUID 绑定中的稳定 UUID 和当前离线名字；J 使用当前关系中的 Java UUID 及按 UUID 查询的官方当前名字/正版纹理。Xbox gamertag、本服微软离线名与正版名字是三个不同事实，gamertag 改名不覆盖本服名字。
- 两个角色有独立资料，关联不迁移/合并它们，也不改变原 UUID。有 U 与可用 J 时双选；仅一个时直接选择。首次 J 可作为候选，但仅选中后建档；无 U 不因 linkedJava 名字生成一个 U，未关联基岩首次登录保持隔离前缀。
- 普通 Mojang session 只授予其 J，不根据公开反查或关系表反授 XUID 角色。IP 免验不升级为 J 权限；同一个 J 对应多个 XUID 也不互授各自 U。目录/profile/皮肤查询与核验关系行都不是认证凭证。保留已验证的公开档案边界：角色流程按 UUID 最多复用60秒内成功验证的公开名字/签名纹理元数据（128项、同UUID单飞）；不缓存关联、授权或失败，过期失败不回旧值。`lookup`仍为强制fresh接口，只有显式`lookupRecent`有此窗口。失败仅保留固定分类、HTTP码和安全Retry-After，不保留正文、URL、令牌或原始 cause/suppressed；仍拒绝登录并提示重试。
- 按 XUID/UUID 读取当前记录，在候选、点击与新连接交接重验关系和名字。名字占用、U 名字被 Mojang 认领、subject/kind/UUID 矛盾、改名、删除、解绑或改绑时拒绝旧菜单/交接，不强行覆盖别的角色。

### 选择、皮肤与低重连落定

共享 Gate 在认证与最终授权之间增加选角阶段。基岩原生菜单为三行 27 槽，零基 12 使用 U 的原版 NAME_TAG，14 使用 J 的原版 DIAMOND，均带当前角色名；图标不承诺真实 skin 头像，不附加自有资源包。默认标题“请选择角色”不额外加色；类别青色、命名灰色 `»`＋白色名字，说明仅“点击进入服务器”，name/lore 根显式 `italic=false`。服务端维护选项与菜单代次，不信任客户端自报身份或物品；关闭、未选择、过期或断线不能放行。双角色每次由玩家选择，不新增“记住上次选择”。

U 的可选 SR 皮肤仅按稳定角色 UUID 读取显式设置；缺设置/缺插件则默认，不借同名正版皮肤。J 使用官方 Java UUID 纹理，不套用 U 的设置；皮肤加载失败与无设置分开，不能当认证成功。Floodgate 原生 profile 与所选角色分开持有，最终纹理不能沿用另一角色的旧属性。普通 Java 真实 profile/纹理及底层 PLAYER_HEAD/PROFILE 支持保留，不被基岩图标替代，也不代表本轮启用 Java 高级双角色 GUI。

最终授权使用 Gate 私有的 `SelectedRoleAuthorization` / `privateSelectedAuthorization`，显式区分 PreLogin 单角色、当前连接点击、普通新登录续接；均绑定 exact session、当前强证明、稳定角色引用及当前关联，不伪造同 session handoff，也不只改 access 绕过授权。正常成功的一次基岩选角按以下契约计数，不包含主动重试或上游持续失败：

| 路径 | 选角回连次数与身份落定 |
|---|---|
| 仅一个可用角色 | 0；强 FG 证明、当前关联/记录核验后在原生 PreLogin、Player 创建前落定，不先制造 chooser |
| 双角色选择与实际 Player 完全相同的 UUID **和** name | 0；点击时重验并在原连接授权，只恢复可信 profile 属性 |
| 选择不同 UUID 或 name | 至多 1 次手动普通回连；新证明通过后在新 Player 创建前落定 |

双选仅在原生 UUID/name 已是合法候选时保留该登录槽位，否则使用隔离身份；选择前仍为 HELD，不授予后端准入。原子预约 pending UUID/名字并核对在线占用，不能踢掉已有在线角色或换 UUID 绕过冲突。候选、点击与最终准入重验当前 FG proof、官方关联、角色记录和菜单代次；改名、删除、解绑、改绑或占用拒绝旧结果。断线、超时、拒绝、停用回收本 session 的预约并取消旧回调，不能晚到授权或清理另一个 session。

不修改已构造 Player 的 UUID/name，不写 SDK alias/linked 状态，不关闭官方 `floodgate:*` 消息来源检查。在位授权只更新同一真实身份的属性：Velocity 使用公开 `setGameProfileProperties`，Bungee 使用签名校验的窄 profile bridge；保留当前连接标记及非管理属性。原生 PreLogin 的有界 IO、exact-channel 关闭观察、取消/超时及 continuation 恰好恢复一次继续保留，Java 基础 Mojang 会话/profile 安全规则不变。

不同身份点击后仅保存有界、短 TTL、一次性的服务器端选择 hint，关闭菜单并明确提示普通回连；不发原生 Transfer，不使用 Cookie。hint 只表达意图，只有不同的新 session/Channel、fresh Xbox/Floodgate 证明、相同 XUID、当前关联及角色记录均匹配才能消费。旧 session、缺失/stale proof、超时、重放或关系/角色变化均拒绝，不按 IP/名字续用权限，也不回退匿名 Java 或另一角色。

菜单期限 `role-selection.timeout-seconds` 默认 60；选择意图期限改为 `role-selection.hint-ttl-seconds`，默认 30。旧 `role-selection.transfer-ttl-seconds` 仅为迁移 alias，新键优先，不启用 Transfer/Cookie。旧 `role-selection.avatars.*`、`reconnect-host/port` 忽略并给出退休提示，不再要求回连端点或引入替代框架。

### 退休分支与安装边界

**historical**：此前的动态头像包、私有 CMD、24682/24683 图片桥、`platform-geyser-avatar` / `platform-geyser-transfer` 生产模块，以及原生 Transfer、Java Cookie 和 Java 高级跨角色流程均已退休，不是默认关闭后仍需安装的能力。当前基岩选角没有头像首 MISS、额外资源包或视觉重连阶段；本插件不要求开启 Velocity `accepts-transfers`。

部署仍只选择一种代理加一个后端；基岩必须正常安装配置 Geyser/Floodgate 本体及可信链路，不称 zero config。保留 `java.auth-type: floodgate`、`advanced.bedrock.validate-bedrock-login: true` 与 FG 密钥边界；无有效 Xbox 证明在网关拒绝，不回退设备码，不增加账号白名单或匿名基岩入口。真实 GUI 翻译 probe 仅运行于隔离测试进程，不是发行插件或生产安装依赖。

### 选角样式的客户端边界

容器标题不使用聊天品牌色，默认无颜色，让原版 GUI 决定标题颜色。选角说明与分隔符使用命名 `gray`：固定 Geyser 的扩展 HSV 调色会把 `#8B8B94` / `#D4D4D8` 近似成紫色 `§u`，不能用同一个近似算法计算测试期待值来证明正确，也不在通用编码器中加入业务 HEX 特判。

Geyser 的 raw 物品转换不会因为空根的 `italic=false` 自动生成初始 legacy reset。仅强 Floodgate 来源的选角 name/lore 在装配处添加显式 reset 和基础色；普通 Java 仍为纯组件，普通物品和全局聊天序列化不受影响。此兼容处理不是身份判断，也不改变 PROFILE 或准入规则。

样式回归重放实际 Gate 库存报文并调用完整 Geyser `ItemTranslator`，检查原版图标及可见字符的颜色/斜体；手工合成组件或仅验证 Java PROFILE 不能替代完整翻译接缝。各协议图标来自对应 SHA 固定官方注册表，不凭猜测填物品 ID；测试通过不等于手机已人工验收。

### Schema v3、升级和验收

新增 `kyokouauth_account_link` 仅保存 XUID、Java UUID、source、scope、verifiedAt 核验元数据，没有 token/永久授权或可变 Java 名字。较旧的核验/撤销响应不能覆盖/删除更新关系；关系行存在也不允许跳过当前连接重验。v1/v2 历史迁移不改，旧身份 UUID/名字/会话与 `kyokouauth_bedrock_link` 保留，旧链接不导入授权。

首次启动新版本即执行增量 schema 迁移，关闭角色选择不等于免迁移。部署前独立备份数据库、配置和产物；不假设只回退 JAR 即可回滚数据库。具体运行服的授权、备份、部署与库迁移结果另记，不从本契约推断任何实例已经部署。

本节记录候选实现契约，不宣称本轮已部署、原生全通过或完成端到端验收。自动化分别记录单角色 0、同 UUID/name 0、不同身份 1 次普通回连在首连之外的真实回连计数、最终 UUID/name、官方 FG 消息检查、HMAC/authority 及真实后端 canary；不等待/发送 Transfer，不携带 Java Cookie，也不以伪同连接替代。基础 Java 的 Mojang 握手、微软认证、首次绑定/改名及 session 语义单独回归，不冒充高级双角色验收。核心 seam、隔离密码/事件链、实际裁剪产物协议验包与手机/原版客户端人工验收分层记录；人工仍须验证基岩原版图标、普通回连体验、两角色独立存档与 SR 共存。没有真人结果就保留“未真人验收”，不以 mock、静态源码、计划或构建成功替代。

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
