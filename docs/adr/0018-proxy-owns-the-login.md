# ADR-0018：代理接管登录——双平台代理架构、多版本 Limbo 与基岩选角契约

本文集中记录现行架构、身份模型与安全约束。早期 Bukkit/Paper 后端方案及旧版本取舍已被代理架构替代，历史记录保留在 Git 中。构建与测试方法见 [开发文档](../development.md)，服主操作与兼容范围见 [README](../../README.md)。

KyokouAuth 从「Paper 后端上的 Bukkit 插件」迁移为「Velocity / BungeeCord 代理插件」。旧 `platform-bukkit` 与 `floodgate-link` 模块移除；身份算法与存储保留在 `core`，`proxy-common` 统一承载连接准入（`LoginGate`）、设备码会话、限流、配置与命令，`platform-velocity` 与 `platform-bungee` 仅做原生事件、Profile、组件与后端连接适配；未认证连接由内置 `limbo` 模块按客户端协议隔离承载。

## 身份模型与名字归属

| 术语 | 含义与边界 |
|---|---|
| 认证主体（Authenticated Subject） | 当前连接经强证明确认的 XUID 或 Mojang UUID，回答“谁在登录” |
| 持久档案（Persistent Profile） | 下游用来索引存档与权限的 `GameProfile` UUID，不等同于临时登录身份 |
| 微软角色（U） | 新身份默认使用 `UUID(0, XUID)`；完成旧档认领的 Java U 永久沿用历史 Profile UUID |
| 正版角色（J） | 使用经 Mojang 握手或当前强 Floodgate 证明与官方关联核验确定的 Mojang UUID |
| 绑定记录（Bound Identity） | 主体、持久 UUID 与当前名字的映射；数据库行本身不是登录凭证 |
| 临时身份（Provisional Identity） | 首次设备码验证前进入私有 Limbo 的占位身份，不作为已认证角色放行 |
| 验证会话（Verification Session） | 绑定单条连接的设备码生命周期；断线、取消或超时即失效 |
| 免验会话（Play Session） | 已有 U 角色的短期同 IP 信任记录，不能替代正版握手或授予另一角色权限 |

- XUID 不随游戏名字或 Xbox Gamertag 改变。UUID 默认从 XUID 派生，而非随机或从名字派生，因此普通改名不分裂存档；旧档认领是显式的持久 UUID 映射例外。
- 同一当前名字至多属于一个角色身份；同一身份同一时刻只持有一个名字。改名原子检查占用并释放旧名，不保留旧名别名，也不把权限按名字移交给新持有人。
- Mojang 持有的名字优先，不能被 U 抢占。正版身份跟随经握手证明的新名字；U 自动改名必须重新证明同一 XUID，失败不改绑定。
- 首次 U 在证明完成后按持久 UUID 普通重连一次，不改写已构造 Player 的 UUID。Velocity 的临时原生名字与真实申报名隔离，避免代理权限插件把真实名登记到占位 UUID；注册与改名仍使用会话内的真实申报名。

## Java 认证与故障边界

- 先判定名字归属，再判断已有绑定和 U 免验会话。正版名每条新连接都必须经过代理原生 Mojang 握手，失败不降级到设备码；设备码证明的是 XUID，不能证明客户端持有它声称的 Java 正版名字。
- 非正版 Java 名在 Limbo 通过服务端设备码流程验证，无需客户端模组。玩家在微软页面输入凭据，插件只在内存中使用本次认证令牌，不持久保存令牌或刷新令牌。
- 公开 UUID、名字、皮肤、IP 及历史绑定行均不是强证明。目录查询的限流、超时、空响应或异常不得当作名字空闲；握手使用独立的单飞、预算和失败退避，拒绝并发绕过。
- 所有后端放行由 `LoginGate` 决定，包括首连、切服、异常和回退。认证 IO、挂起连接及 HTTP 响应体均有界，满载或关闭时拒绝，不在代理事件线程阻塞请求。
- 设备码轮询遵守微软间隔、`slow_down` 和 `Retry-After`。日志只保留安全错误分类、状态与耗时，不记录验证码、令牌、完整认证 URL 或上游响应正文。

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
- **认证前命令过滤**：`verification.blocked-commands`（默认 `server`）按连接当前认证状态拦截命令执行并裁剪客户端可见命令树。Velocity 使用原生命令执行与命令树事件；Bungee 因无公开命令树事件，在启动签名自检通过后通过窄前端通道利用 Bungee 自有 `Commands` 编码器过滤下发树与现代补全，认证完成后随下游子服命令树自然恢复。

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

## 旧服离线档案认领桥接（Schema v5）

在代理接管认证后，旧离线模式（AuthMe）服务器迁入时面临“新认证主体（XUID）如何延续旧离线 v3 `Profile UUID` 下游原版存档”的问题。本架构将**认证主体（Authenticated Subject）**与**持久档案（Persistent Profile UUID）**显式解耦。只认领旧 AuthMe 离线档到 Java 微软角色（U），不支持跨到正版角色（J）。

### 已实现路径：Velocity / BungeeCord `AuthMe -> Java U` 连接级双重认领

- **显式受控边界**：在 **Velocity 和 BungeeCord** 上仅对临时 **Java 微软验证角色（U）** 开放；Floodgate 与正版角色（J）不开放旧档认领。默认不启用，管理员把同源停写备份中的 AuthMe 配置、SQLite 文件（如适用）与实际玩家 NBT/可选名字缓存复制到 `migration/inbox/`，无需填写 `migration.*`。`scan` 写本地未启用快照，`confirm` 才持久启用；缺失或多重对应排除并报告。不按名字/AuthMe 可选 UUID 列猜测，不修改世界/源库，不信任旧 IP/免验记录；旧静态配置与单条命令仅留作显式人工/直接来源入口。
- **来源证明已接玩家入口**：私有 Limbo 内的 `/ka claim authme <旧名字> <旧密码>`（以及 `status/cancel`、别名与命名空间形式）仍走前置 channel，不要求管理权限。正常入口从 `authme-inbox` 批次中唯一 `AVAILABLE` 记录及当前本地快照名字/UUID 允许清单共同选取旧档。每次认领仅读本地快照；输入密码不持久化，快照不保存旧 SQL 凭据或 TOTP 秘密。暂停新认领使用持久的 `migration disable`，不撤销旧映射。
- **一次性导入与本地持久化**：导入来源限 SQLite 一致性快照、MySQL/MariaDB；连接信息/表列直接读取复制来的 AuthMe 配置并核对实际结构，不强制重复输入 SQL 账号。源端始终只读、远程 TLS 校验身份、connect/socket 超时有界；专用 SELECT 账号可选加固，但只读事务不等于账号被降权。一个独立后台任务建立一个旧来源连接，按 256 行流式批写本地 SQLite，最多 100000 行，导出事务失败回滚清理。导出后关闭来源，后续确认/认领/重启不访问旧库。候选登记按 256 条短事务释放目标库连接/锁，全部处理成功后才原子替换快照指针；失败保留旧启用状态，尚未启用的元数据可重扫幂等续做且不能越过当前允许清单。仅支持官方默认 16 位小写十六进制盐的 `$SHA$` / `SHA256`；TOTP、未知哈希和冲突仍拒绝。没有 HTTP 认领服务。完整操作见 [README](../../README.md#推荐复制材料后命令扫描无需迁移配置velocity)。
- **双凭证同会话绑定与双重确认**：
  1. 管理员复制已停写的同源材料，`ka migration scan` 后台准备，`progress/cancel` 查看或取消，`issues [page]` 查看异常。确认停写/同源前提后，预检完成起 5 分钟内一次 `confirm <token>` 重新核对本地快照、历史记录和目标状态并启用；不重连旧库，不自动锁库，不把候选登记当作账号归属证明；
  2. 玩家以全新的空闲非正版名首次连接，持临时 Java U 身份进入私有 Limbo，认领提示 `OFFER` 或选择认领后的 `WAITING_OLD` 均允许提交自助来源证明；
  3. 自助来源核验通过后，仅向当前精确连接签发短期源授权，以 `AUTHME_PASSWORD` 写入证明方法与审计；原人工独立核验路径继续保留：管理员通过 `ka migration pending` 找到等待会话，独立核实旧号归属后执行 `ka migration approve <session> <recordId> verified`，承担人工核验审计责任；
  4. 玩家经既有原版 `ConfirmationMenu` 首次确认旧档名与新登录名，随后在同一连接完成全新的 Microsoft/Xbox 设备码证明取得正 XUID，再第二次确认旧档名、新名字与 Xbox 代号；
  5. 数据库仍为 **schema v5**（历史 v1～v4 迁移保持不变；v4 新增 `kyokouauth_legacy_profile`、`kyokouauth_profile_owner`、`kyokouauth_claim_grant`、`kyokouauth_profile_claim_audit`，v5 为 claim grant 增加 `proof_method`）。同一事务消费短期授权并持久化 `XUID -> 历史 Profile UUID` 归属与名字绑定，断开提示普通重连一次。认领中取消、超时、来源失败或冲突均失败关闭，不静默新建默认 `UUID(0, XUID)` 角色。
- **输入隐私与有界执行**：支持本地快照运行时启用。新连接只有在 Velocity 4.3.1 或通过必要接口检查的 BungeeCord 系代理上成功安装前置管线后，原生适配器才向公共 gate 标记精确 session 已受保护；旧的未保护连接不会临时收到认领入口，安装失败即拒绝登录。敏感命令在 `CommandExecuteEvent`、Brigadier 错误回显、后端转发和公共补全事件前消费，只交轻量解析与有界 authIO。仅接纳 dedicated unsigned / 受支持 Legacy；签名/LastSeen 敏感格式保守断开，不改写或剥离签名。关闭模块先关闭原通道，在实际关闭前保留 guard。每连接一个进行中，账号 5 次/10min、来源地址 20 次/10min，本地验密并发 4，扫描使用独立单线程且不占登录队列；接管的密码 CharArray 各路径清零，不记录/回显密码或哈希，错误不披露账号存在性细节。
- **隐私承诺的界限**：常规命令/补全事件 spy 收不到旧密码；不承诺防更早原始包记录器、恶意 root/替换插件、客户端本地历史或截屏。该入口不为 offline Minecraft 连接增加传输加密，不是端到端或运营者绝对不可见方案。
- **持久性与插件范围**：认领提交后，XUID 对应的持久 `Profile UUID` 永久保持为所认领的历史 UUID；`migration disable` 持久停止新认领但不撤销既有映射。仅保证原版及严格以 `GameProfile` UUID 为主键的数据延续，不承诺按名字或私有账号表存储的任意第三方插件通用兼容。
- **验收边界**：构造的来源账号、微软响应及测试 bootstrap 只验证其声明的接缝，不替代真实 AuthMe/Microsoft 账号授权验收。真实授权需由自愿测试账号的持有者完成，生产迁移、重启和部署应作为独立操作处理。

## 文本、配置与存储取舍

- `core` 的 `RichText` 负责平台无关解析，平台层只做组件转换与投递，不依赖 MiniMessage。未知标签按字面文本处理；解析结果可以脱离代理运行单元测试，组件转换由各平台适配器负责。
- JDBC 驱动按选用的数据库下载到插件自己的 `libraries/`，由独立类加载器加载；人工预置优先，下载先写临时文件再安装。`DriverBackedDataSource` 直接持有驱动，避免连接池通过错误类加载器找不到动态驱动；不把全部数据库驱动塞入每个发行 JAR。
- YAML 升级只补缺失字段，保留服主自定义值，修改前备份；不恢复早期“版本变化即覆盖整份配置”的方案。首次配置注释可随 JVM 语言选择，消息语言仍由配置决定。
- Schema 历史迁移保留，不因角色与认领扩展重写旧迁移。v2 历史关联行不作为当前授权；v3 关联元数据不保存可复用令牌，v4/v5 的档案认领与审计边界见上文。

## 外部参考来源

- **[FG-SETUP]**：Floodgate 代理部署、`auth-type: floodgate`、`key.pem` 信任链与后端转发边界 — <https://geysermc.org/wiki/floodgate/setup/>
- **[FG-API] / [FG-API-JAVADOC] / [FG-PLAYER] / [FG-HANDSHAKE]**：Floodgate 2.2.5 公开接口、玩家字段语义与废弃握手回调说明 — <https://geysermc.org/wiki/floodgate/api/>、<https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/FloodgateApi.html>、<https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/player/FloodgatePlayer.html>、<https://repo.opencollab.dev/javadoc/maven-snapshots/org/geysermc/floodgate/api/2.2.5-SNAPSHOT/raw/org/geysermc/floodgate/api/handshake/HandshakeData.html>
- **[FG-CORE-SOURCE] / [FG-VELOCITY-SOURCE] / [FG-BUNGEE-SOURCE]**：固定版本 `2.2.5-20260917.145236-21` 源码包中 `SimpleFloodgateApi`、通道握手属性写入及 Velocity/Bungee 事件优先级时序 — `repo.opencollab.dev` 对应 `floodgate-{core,velocity,bungee}` sources 制品
- **[FG-FAQ] / [FG-LINKING]**：Floodgate 前缀/UUID 规则与 Global Linking 官方关联语义 — <https://geysermc.org/wiki/floodgate/faq/>、<https://geysermc.org/wiki/floodgate/linking/>
