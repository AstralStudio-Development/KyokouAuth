# ADR-0018：代理接管登录——双平台代理架构、多版本 Limbo 与基岩选角契约

取代 [ADR-0001](0001-single-jar-java8-baseline.md)、[ADR-0005](0005-limbo-over-kick.md)、[ADR-0009](0009-login-injection-gates-both.md)、[ADR-0014](0014-handshake-verified.md)。

KyokouAuth 从「Paper 后端上的 Bukkit 插件」迁移为「Velocity / BungeeCord 代理插件」。旧 `platform-bukkit` 与 `floodgate-link` 模块移除；身份算法与存储保留在 `core`，`proxy-common` 统一承载连接准入（`LoginGate`）、设备码会话、限流、配置与命令，`platform-velocity` 与 `platform-bungee` 仅做原生事件、Profile、组件与后端连接适配；未认证连接由内置 `limbo` 模块按客户端协议隔离承载。

## 为什么必须迁往代理层

后端插件在代理网络中存在两处无法逾越的结构性盲区：

1. **代理级 `/server` 命令绕过后端冻结**：切服命令由代理直接注册并消费（如 Velocity `UpstreamBridge` 在分发成功后直接拦截），请求根本不会到达后端。无论后端如何冻结移动或取消事件，都无法阻止未认证玩家切往其他子服。
2. **后端登录注入与代理现代转发冲突**：后端插件依赖替换 `ServerConnection` 通道注入正版加密握手；一旦后端同时开启 Velocity `modern` 转发，代理与后端对同一条登录连接的状态预期不一致，代理会直接以 `Backend server is online-mode!` 断开连接。

被否决的替代方案包括：「断线踢出并附带链接」（无法在验证完成后自动放行进入子服）、「仅在代理 `PreLoginEvent` 放行/拒绝而不设隔离空间」（无法承载在线等待与防 `/server` 切服），以及「退回 BungeeCord 旧转发继续留在 Bukkit」（登录注入与代理转发的根本冲突仍在）。

## 多版本内置 Limbo 与私有路由设计

### 依赖隔离与体积控制

早期手写协议编解码器已整体替换为固定版本的上游 **MCProtocolLib**，覆盖协议 **767～777**（Minecraft 1.21～26.3 正式版，不自动承诺快照或未知未来版本）：

- **按协议隔离 Codec，共享基础库**：11 个协议子模块（物理位于 `limbo/protocols/767`～`777`）各自重定位协议类并使用各自官方注册表与配置期数据包，避免多版本冲突合并；NBT/fastutil/Gson 等普通运行库仅保留单份（`limbo/runtime`），Adventure 文本库按 767～769、770～775、776～777 三个格式族共享（`limbo/text/*`），Netty 4.2 直接复用宿主代理提供的运行时。
- **构建期生成稀疏 Codec 与 R8 裁剪**：构建期从固定上游 JAR、源码与官方注册表生成明确包 ID 与原厂构造器清单，仅通过 R8 移除 Limbo 类图中的未用成员（保留反射、泛型、sealed 文本契约、SPI 与物品组件 `CUSTOM_NAME` / `LORE` / `PROFILE` 闭包），不改写 `core` 与平台适配代码。单平台完整 JAR 硬上限 **20 MiB**，优化目标 **10 MiB**。针对 MCProtocolLib 26.3-4 中 `previousGameMode` 旧式缺省编码差异，在 777 登录包保留单点版本固定修正。

### 私有目标、转发凭据与认证前命令封锁

- **私有 Limbo 端点**：各协议 Engine 监听随机本地回环端口，仅通过 Velocity `createRawRegisteredServer` 或 Bungee 未公开 `ServerInfo` 由适配器私有持有，绝不注册进公开服务器表、`/server` 列表或 Tab 补全。
- **转发信任链**：Velocity 使用 modern forwarding HMAC；Bungee 因传统握手字段可被伪造，改为附加由当前进程签发、绑定当前连接/身份/协议/目标的短 TTL 一次性票据，入场原子兑换并在连接真实后端前剥离。
- **认证前命令过滤**：`verification.blocked-commands`（默认 `server`）按连接当前认证状态拦截命令执行并裁剪客户端可见命令树。Velocity 使用原生命令执行与命令树事件；Bungee（构建 2111）因无公开命令树事件，在启动签名自检通过后通过窄前端通道利用 Bungee 自有 `Commands` 编码器过滤下发树与现代补全，认证完成后随下游子服命令树自然恢复。

## 架构迁移的直接后果

| 变更点 | 结果 |
|---|---|
| 移除 `platform-bukkit` / `floodgate-link` | 认证、缓存与国际化收敛至 `core`，不再维护后端通道注入与旧式自动账号映射 |
| 正版名字由代理原生握手验证 | 对 Mojang 持有的名字直接启用代理 `PreLogin` 在线模式校验（Velocity `EventTask` 异步暂停 / Bungee `PreLogin` intent），正版客户端自动完成加密握手，冒名连接在代理入口即断开 |
| 验证提示与轮询收敛 | 设备码就绪后标题仅展示一次并在结束前清理；轮询等待从响应完成时刻计算，严格服从微软 `interval`、`slow_down`（+5s）与 `Retry-After` |

## 代理端基岩身份接入契约

基岩接入固定依据代理端 **Floodgate 2.2.5 b141** 与 **Geyser 2.12.1 b1303** 设计，`bedrock.enabled` 默认 `false`：

1. **同进程受控信任链**：Floodgate 必须安装在运行 KyokouAuth 的同一 Velocity / Bungee 代理进程内，Geyser 配置 `auth-type: floodgate` 与 `validate-bedrock-login: true` 并安全配对 `key.pem`。下游子服的 Floodgate 数据转发不能替代代理层认证。
2. **绑定底层网络通道的强证明（`BedrockProof`）**：Floodgate 的公开查询接口（如 `SimpleFloodgateApi.getPlayer(UUID)`、`isFloodgateId`）会命中在线表、已关联 UUID 乃至断线后短时残留缓存，**不能作为当前连接的登录证明**。适配器必须在 `PreLogin` 阶段从当前原生网络通道读取 Floodgate 写入的 player attribute，并与 `FloodgateApi` 实例、安装代次、XUID、目标 UUID/名字及 `isLinked()` 快照做一致性绑定；后续 Profile 与 Login 阶段逐次复核同一通道仍活跃且未发生篡改。在 Bungee 上兼容 Floodgate `PostLogin` 皮肤写回所需的 `LoginResult` 双 `null` 容器形态，仅受控更新 `properties`。
3. **未开启选角时的隔离边界**：当 `role-selection.enabled: false` 时，仅允许未关联（unlinked）的基岩玩家以非 Java 前缀（如 `.`）进入，不写入 Java 名字绑定表，不签发/复用 Java IP 免验会话，拒绝已关联连接；基岩分支任何校验失败均直接断开，绝不回退到 Java 设备码验证。

## 关联账号双角色选择与低重连契约

`role-selection.enabled` 默认 `false`，与 `bedrock.enabled` 相互独立，仅面向已取得当前连接强 Floodgate XUID 证明的基岩连接开放（Java 客户端保持原有的正版直连与设备码单角色认证，不启用跨角色 GUI）：

### 角色分离与实时关联核验

- 每次连接向官方 Global Linking 接口实时核验当前 XUID：仅明确的无关联成功响应记为 `NotLinked`；空响应、限流、网络异常或与原生 Floodgate 快照矛盾一律记为 `Unavailable` 并拒绝登录，不接纳本地关联模式。
- **微软角色（U）** 使用 `UUID(0, XUID)` 与本服离线名字；**正版角色（J）** 使用当前官方关联给出的 Mojang UUID 与按该 UUID 查询的官方名字/正版纹理（首次 J 仅在玩家实际选中准入时建档）。两套角色存档、权限与背包完全独立，不自动合并，同一 J 关联多个 XUID 时不互授各自 U。
- **皮肤与 60 秒元数据窗口**：U 仅按稳定 UUID 读取可选 SkinsRestorer 显式设置的皮肤（未设则默认皮肤，不借用同名正版皮肤）；J 使用官方正版纹理（不套用 U 的 SR 皮肤）。角色流程按 UUID 最多复用 **60 秒**内成功验证的官方公开名字/签名纹理元数据（128 项上限、同 UUID 单飞），不缓存关联关系、授权或失败状态。
- **数据库 Schema v3**：启动时增量迁移新增 `kyokouauth_account_link`，仅保存 `xuid`、`java_uuid`、`source`、`scope`、`verified_at` 核验元数据，不含令牌或免检授权；旧 v1/v2 数据保留但历史关联不自动转为授权，回退插件版本前必须依赖独立数据库备份。

### 原版箱子菜单、样式兼容与 0/1 次普通回连

早期探索过的自定义 Geyser 头像/Transfer 扩展（`platform-geyser-avatar` / `platform-geyser-transfer`）、动态头像资源包、私有图片 HTTP 桥以及原生 Transfer / Java Cookie 跳转方案均已废弃退役。当前方案仅依赖原版容器与普通重连：

- **三行 27 格原版菜单**：槽位 12 使用原版 `NAME_TAG` 展示 U，槽位 14 使用原版 `DIAMOND` 展示 J。容器标题继承原版默认 GUI 颜色；针对 Geyser `ItemTranslator` 对扩展十六进制灰色的近似偏色（会映射为紫色 `§u`）及空根 `italic=false` 缺少初始重置的问题，选角分隔符与说明统一使用命名 `gray`，并仅在基岩选角物品文本装配处补显式重置前缀，不影响全局聊天或普通物品。
- **回连次数与状态机落定**：不反射篡改已构造原生 `Player` 的 UUID/名字，不改写 Floodgate SDK 内部状态，也不关闭 `floodgate:*` 插件消息来源检查：
  - **单角色可用**：在原生 `PreLogin`、`Player` 创建前直接核验落定，**0 次重连**。
  - **双角色且选中角色与当前原生 `Player` 的 UUID 和名字完全一致**：点击时重验并在原连接更新可信纹理属性放行，**0 次重连**。
  - **双角色且选中不同 UUID 或名字**：记录有界短时（`role-selection.hint-ttl-seconds`，默认 30 秒；兼容旧键 `transfer-ttl-seconds`）的一次性服务端选择意图，关闭菜单提示玩家手动重连一次（**至多 1 次普通回连**）。该意图不是登录凭证，新连接仍须携带全新的有效 Floodgate 证明并通过关联、角色与在线占用重验后方可消费；已在线角色优先受保护，绝不被新连接顶掉。

## 外部参考来源

- **[FG-SETUP]**：Floodgate 代理部署、`auth-type: floodgate`、`key.pem` 信任链与后端转发边界 — <https://geysermc.org/wiki/floodgate/setup/>
- **[FG-API] / [FG-API-JAVADOC] / [FG-PLAYER] / [FG-HANDSHAKE]**：Floodgate 2.2.5 公开接口、玩家字段语义与废弃握手回调说明 — <https://geysermc.org/wiki/floodgate/api/>、<https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/FloodgateApi.html>、<https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/player/FloodgatePlayer.html>、<https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/handshake/HandshakeData.html>
- **[FG-CORE-SOURCE] / [FG-VELOCITY-SOURCE] / [FG-BUNGEE-SOURCE]**：固定版本 `2.2.5-20260917.145236-21` 源码包中 `SimpleFloodgateApi`、通道握手属性写入及 Velocity/Bungee 事件优先级时序 — `repo.opencollab.dev` 对应 `floodgate-{core,velocity,bungee}` sources 制品
- **[FG-FAQ] / [FG-LINKING]**：Floodgate 前缀/UUID 规则与 Global Linking 官方关联语义 — <https://geysermc.org/wiki/floodgate/faq/>、<https://geysermc.org/wiki/floodgate/linking/>
