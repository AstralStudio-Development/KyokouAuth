<div align="center">

# KyokouAuth

离线模式代理的微软账户认证

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%E2%80%9326.3-3C8527.svg)](#兼容性)
[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00.svg)](#兼容性)

</div>

## 是什么

`online-mode=false` 的代理默认信任客户端自称的名字。KyokouAuth 让每个玩家在进入真实后端之前先证明自己拥有一个微软账户。

名字是否属于正版由 Mojang 目录决定：

| 名字 | 处理 |
|---|---|
| Mojang 拥有 | 普通 Java 入口强制 Mojang 会话验证，正版客户端自动应答，无需设备码 |
| 其它 | 进验证大厅，用微软设备码绑定后放行 |

客户端不需要安装任何模组。

验证大厅使用各协议固定版本的 MCProtocolLib 提供编解码与网络传输；767～777 的 11 个协议实现分别重定位，但不重复打包整套依赖：Netty 4.2 由所用代理提供，NBT/fastutil 等普通库共享一份，文本库按三种格式族共享。构建期从上游完整注册表生成明确 ID 的稀疏 Codec，再仅对 Limbo 图删除未用成员；不手写报文字段，也不运行时下载协议库。各 Engine 使用自己的官方注册表和私有 loopback 端口，代理按客户端协议选择；无需另装协议转换插件。Velocity 使用 modern forwarding HMAC，Bungee 使用绑定当前连接、协议和目标的短时一次性私有票据，绝不信任客户端自报 UUID。两个平台共享认证与验证会话；验证失败或大厅不可用都不会回退真实后端。

各 Limbo 端点仅保存在 adapter 的私有连接目录，不加入原生 `/server` 列表、补全或公开服务器发现；仍由共享 Gate 按当前连接与协议选择，不能通过手输内部名称进入。

标题在验证码准备好后显示一次、持续至当前验证期限，并在结束或重新申请前清理，不再每三秒重启动画。聊天保留完整可点击链接。取得微软令牌后立即提示正在确认 Xbox 身份；轮询从响应完成时计算剩余等待，不把本地提示/日志耗时再叠加一次。`slow_down` 每次增加五秒，429/503 的 Retry-After 与微软返回的更长间隔仍优先；不承诺网页完成后零延迟，也不省略必要的 Xbox 两跳。完成令牌尝试时记录仅含阶段耗时和计数的安全诊断日志。

## 快速开始

```text
plugins/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar
```

### Velocity

代理配置需要两件事：

```toml
# velocity.toml
online-mode = false
player-info-forwarding-mode = "modern"
```

启动代理，`plugins/kyokouauth/config.yml` 会自动生成，默认 SQLite。验证完成后玩家会被送到 `post-login.server`，留空则用 `velocity.toml` 里尝试顺序的第一个后端。

### BungeeCord

改用 `KyokouAuth-Bungee-0.1.0-SNAPSHOT.jar`，不要在同一个代理同时安装两种平台产物。已选定官方成功构建 **2111**；其 API 坐标虽叫 26.1，实际覆盖 767～777。`config.yml`：

```yaml
online_mode: false
ip_forward: true
```

真实后端应配置为 Bungee 转发模式（例如 Paper 的 Bungee 设置），不能沿用 Velocity modern 配置。不要将真实后端或私有 Limbo 端口暴露给玩家。Bungee profile bridge 在启动时检查必要签名，不兼容时拒绝登录，不静默放行。插件配置、消息、管理命令和认证语义与 Velocity 相同；不会自动迁移另一代理目录中的身份库。

### 基岩身份接入（可选）

`bedrock.enabled` 默认关闭，与下述角色选择开关相互独立。**角色选择关闭时保留第一阶段限制：仅接入未 linked 的 Floodgate 玩家。** 开启角色选择后才增加当前官方 global 关联的选角路径，不自动接纳本地关联或不能解释的提供方模式。

已选定代理 Floodgate **2.2.5 b141**，网关目标为 Geyser **2.12.1 b1303**。在同一个 Velocity/Bungee 进程安装对应平台的 Floodgate，让 Geyser 的 Java 目标指向该代理、认证类型使用 `floodgate`，并通过受控链路使用该代理的 Floodgate 密钥。仅在后端安装 Floodgate 不足以给代理授信；不要公开密钥或绕代理直连后端。

```yaml
bedrock:
  enabled: true
```

开启却缺少或不兼容代理 Floodgate 会明确拒绝初始化。每次连接都从当前 Channel 的真实 Floodgate 证明验证，首次进入无需再申请设备码。没有本服微软角色的未关联基岩玩家仍保留非 Java 前缀（例如 `.`）；显示名不写 Java 名字绑定表，也不签发/复用 Java IP 会话。不去前缀、不根据名字生成 UUID、不把普通客户端自报 UUID 当身份。

现有 Java 微软路径与未关联基岩路径沿用同一 XUID→UUID 规则，但**不自动合并名字或 linked 的 Mojang UUID**。按名字存储数据的第三方插件不在迁移范围内；`verification.auto-rename` 不会擅自改写 Floodgate 显示名。真实 Bedrock/Xbox 账号登录与受控官方 Floodgate 密码处理链是不同验收层次，不以测试握手冒充真人 Xbox 登录。

网关始终保留 `java.auth-type: floodgate` 与 `advanced.bedrock.validate-bedrock-login: true`。没有有效 Xbox 签名/证明的离线基岩连接在 Geyser 建立 Java 下游连接前被拒绝，不回退到 Java 设备码流程；修改版启动器若仍提供有效 Xbox 登录，仍属于有证明的入口，不能按启动器名称判断。当前认证不是账号白名单，其他有效微软账号仍可按既有规则进入；本次不增加白名单或匿名基岩入口。

### 关联账号与双角色选择（默认关闭）

这是**选择独立角色**，不是把微软角色迁移到正版 UUID，也不是合并背包、位置、权限或存档：

- **微软角色 U**：本服 XUID 绑定中的稳定 UUID（`UUID(0, XUID)`）和当前离线名字。Xbox gamertag 是微软档案资料，不是本服离线名字；改 gamertag 不会替你改本服名字。
- **正版角色 J**：当前核验关联给出的 Mojang UUID；名字与正版纹理按该 UUID 读取官方 profile。正版名字、gamertag 和本服微软名字可能完全不同。
- 只有当前连接完成设备码/Xbox 验证或取得可信 Floodgate **强 XUID 证明**，再核验当前官方 global 关联，才有资格解析这两种角色。普通 Mojang 会话只证明其 Java 角色，不反向授予某个 XUID 的角色；IP 免验、公开 UUID/profile 查询和历史关系行也不能升级出正版选角权限。

默认配置如下；开启前将 `reconnect-host` 改为管理员控制、玩家可访问的 Java 代理地址，再将 `enabled` 设为 `true` 并重启代理。不能使用真实后端、私有 Limbo 地址或客户端自报地址作为回连目标。原生前登录/profile 桥不兼容时拒绝开启，而不是在 Player 登录后改显示名伪装换身份。

```yaml
role-selection:
  enabled: false
  timeout-seconds: 60
  transfer-ttl-seconds: 30
  reconnect-host: ""
  reconnect-port: 25577
```

**Velocity 部署要求**：接收选角回连的目标代理必须在 `velocity.toml` 的 `[advanced]` 段设置 `accepts-transfers = true` 并重启；不要放在 TOML 根级。否则原生 Java Transfer 握手会被 Velocity 以 `multiplayer.disconnect.transfers_disabled` 拒绝，不能改用普通登录来绕过该要求。插件不会自动改写运行服配置。

有 U 和当前可用 J 时，认证后打开三行、27 格的原生头颅 GUI，第二行第 4/6 列（零基槽 **12/14**）分别显示“微软角色”和“正版角色”及当前名字。默认标题为“请选择角色”，两个类别统一品牌青色 `#22D3EE`，弱灰 `»` 分隔白色名字，说明仅为“点击进入服务器”；选角物品的名称/说明根组件显式非斜体，不继承原版自定义物品的斜体/紫色默认，也不全局修改聊天样式。仅一个合法角色时直接选择它；首次 J 可以展示，但仅选择后按正常规则建档。不因为 linked 的 Java 名字制造一个新的微软角色；没有已有 U 的未关联基岩首次登录仍使用隔离前缀策略。

Java 选择器使用连接级随机临时身份；基岩选择器必须保留当前 Channel 的 Floodgate 原生注册 UUID，使真实 Geyser 的 `floodgate:*` 内部消息仍经过官方来源校验。两种选择器的显示名均为合法的随机隔离别名，提供方原始名字独立保存在当前连接证明内，不放宽私有 Limbo 的名字校验。保留原生消息身份不等于后端准入：选角阶段仍为 HELD。若该原生身份已经在线，拒绝本次新连接，不用临时 UUID 绕过 Floodgate 限制或挤掉旧角色。

没有选择就不能进入后端，关闭菜单不会替玩家决定；选择超时或连接失效会拒绝。候选、点击和新连接交接按 XUID/UUID 重新核对当前关系及角色，不按名字或客户端提交的 UUID 授权。解绑/改绑、名字占用、角色删除或改名后的旧菜单/交接不能沿用旧权限；微软名字被 Mojang 认领也不能强行覆盖。微软自动改名仍受 `verification.auto-rename` 与原子名字冲突规则约束，角色 UUID 和两套资料不变。同一个 J 关联多个 XUID，不会使这些 XUID 互相取得对方的 U。

**皮肤各归各的角色**：U 只按稳定 UUID 读取可选 SkinsRestorer 的显式设置；未安装或未设置时使用默认皮肤/默认头颅，不按离线名借用同名正版皮肤。J 使用官方 UUID 对应的正版纹理，不套用 U 的 SR 皮肤；官方资料/皮肤查询失败不是“默认皮肤”或认证成功。皮肤与公开 profile 查询本身都不是身份凭证。

最终 UUID/name/profile 必须在新 Player 落定前设置。Java 使用短 TTL、一次性不透明 Cookie 交接；基岩只保存短期选择意图，新连接仍须取得新的 exact-channel Floodgate 证明并重验关联。没有长期默认角色，不把交接写成数据库登录凭证；这是一次真正的回连与加载，不是无缝切服。

#### 基岩原生自动回连的可选 Geyser 扩展

[`platform-geyser-transfer`](platform-geyser-transfer/build.gradle.kts) 只做地址白名单转换，不参与认证。将 `platform-geyser-transfer/build/libs/KyokouAuth-GeyserTransfer-0.1.0-SNAPSHOT.jar` 安装到 **Geyser 的 `extensions/`**（不是代理 `plugins/`），启动后编辑该扩展数据目录生成的 `transfer-routes.properties`，替换以下示例地址并重启 Geyser：

```properties
enabled=true
route.local.java-host=proxy.example.com
route.local.java-port=25577
route.local.bedrock-host=bedrock.example.com
route.local.bedrock-port=19132
```

Java host/port 必须精确匹配上面代理的 `reconnect-host` / `reconnect-port`（DNS 大小写除外）；Bedrock host/port 必须能被**基岩客户端**访问，不能填 `0.0.0.0`、后端地址或仅服务器可达的内部地址。白名单仅接受显式 DNS/IPv4 与端口，不支持通配符、URL、SRV 推导或客户端 hostname；扩展默认 `enabled=false`，只针对固定 Geyser **2.12.1 b1303**，不兼容构建拒绝启用。

扩展将匹配的原生 `ServerTransferEvent` 转换为 Bedrock 目的地址，再由 Geyser 发送原生 Transfer；它不读取密钥、不搬 Cookie、不替代强 XUID 证明。**未安装/未配置白名单或客户端不支持 Transfer 时须手动重新连接**。即使安装，也仍有新连接与真实加载过程，不能承诺无缝回连。

#### 基岩库存头像：独立 Avatar 扩展（默认关闭）

Java 的真实 `PLAYER_HEAD/PROFILE` 纹理不等于基岩库存能够显示任意皮肤。基岩头像适配采用**两个精确 CMD 标记的自定义物品图标＋每会话专用资源覆盖包**：仅当前可信基岩选角物品携带 `0x4B5901` / `0x4B5902`，Geyser 只精确匹配这两个值，不使用“CMD 大于阈值”的范围匹配。它不注册 custom skull、不覆盖真实 skin hash 或普通玩家头颅；普通 Java GUI 仍保留真实 PROFILE、UUID、名字、纹理及签名。CMD 和图像都不是登录凭证。

另装 [`platform-geyser-avatar`](platform-geyser-avatar/build.gradle.kts) 的 `KyokouAuth-GeyserAvatar-<版本>.jar` 到 **Geyser 的 `extensions/`**，不要替换只负责路由的 Transfer 扩展。固定入口为 `KyokouAuthAvatar`（id `kyokouauthavatar`），只适配 Geyser **2.12.1 b1303**。启动后编辑 Geyser 为该扩展生成的数据目录中的 [`avatar.properties`](platform-geyser-avatar/src/main/resources/avatar.properties)，以实际 SDK `dataFolder()` 为准，不猜目录名大小写。

Proxy 端新增默认值如下，将 `avatars` 合并到现有 `role-selection` 段，不要重复创建顶级键；开启 `role-selection.avatars.enabled` 还必须已有 `role-selection.enabled: true`、`bedrock.enabled: true` 和原有可信回连配置。头像不会替代角色选择或 Floodgate 认证：

```yaml
role-selection:
  avatars:
    enabled: false
    bridge-port: 24682
    status-endpoint: "http://127.0.0.1:24683/v1/packs/status"
```

Geyser 扩展与 Proxy 配对后，将其生成配置的 `enabled` 设为 `true`；其余默认属性如下：

```properties
enabled=false
proxy-endpoint=http://127.0.0.1:24682/v1/avatars
status-host=127.0.0.1
status-port=24683
snapshot-timeout-ms=200
max-packs=64
max-sessions=1024
```

两端只监听字面量 `127.0.0.1`，端口须不同且无冲突；Proxy/Geyser 需要共享可达的受控本机 loopback 环境。不要为跨机部署改成 `0.0.0.0`、公网、任意 hostname 或开放转发。Proxy 的 `GET /v1/avatars?xuid=...` 只读已准备的 PNG 快照，MISS 为 `202/not-ready`；Geyser 的 `GET /v1/packs/status?xuid=...` 只读本次资源阶段的 `nonce`、`fingerprint` 与 `fallback`。`offered` 只是“已提供的 presentation”，**不是客户端已下载/已渲染的 ACK**。GET 不触发外网下载、角色创建或授权，也不返回 Cookie、token、名字、数据库凭据或玩家存档。

客户端必须接受所需资源包；固定 Geyser schema12 的 `config.yml` 需要以下前提（不是 Proxy 配置），在现有 `gameplay` 段修改而非重复创建顶级键，然后重启 Geyser：

```yaml
gameplay:
  enable-custom-content: true
  force-resource-packs: true
  enable-integrated-pack: true
```

关闭自定义内容会禁用 Geyser 的物品映射；关闭强制包会允许客户端拒绝包继续连接，不能在这两种情况下声称图标已显示。保留 integrated 包及其强制加载要求；头像覆盖只写自有 `kyokouauth` 图像路径，不改 integrated 包本身、普通头颅、其他插件纹理或游戏存档。

资源阶段不能在异步完成后给同一会话热换包：

- 命中当前实际图像指纹时，**头像视觉重连为 0 次**；单角色不为头像新增 GUI 或视觉重连。
- 首次 MISS 时先提供明确占位，Proxy 在当前强 XUID/角色核验后有界准备两张透明三面头像；如果本次 offered 指纹不同，**最多一次真正的视觉重连**，新连接仍重新验证 Xbox/Floodgate、关联及角色。它不签发/消费选角权限，不替玩家选择角色，也不包含原有最终角色身份落定可能需要的交接重连。
- 新用户和 SR 换肤可在下一次连接按实际图像内容刷新，无须每次重启 Geyser；已打开的活动会话不承诺热换资源包。名字/档案时间戳不单独改变头像内容指纹，不按同名跨角色串图；活动包文件保持不可变。
- 超时、失败、缓存 MISS 或刷新预算用尽时，显示明确的问号占位及“头像暂不可用”提示，不把 fallback 冒充真实皮肤显示成功，也不会放松认证或无限重连。

微软 U 没有 SR 显式设置时使用明确的默认 Steve 头像，已有设置则按稳定 U UUID 读取；正版 J 使用其官方 profile 皮肤。无设置与上游失败分开，头像准备、公开皮肤及任何 presentation 端点都没有授权能力。资源包 PNG/指纹、官方翻译路径和原生事件可自动验证，**真正手机上的包下载与头像显示、换肤和重连体验仍须操作者验收**；本节说明不代表已经部署或真人渲染通过。

#### 存储升级与验收边界

身份库启动时增量升级至 **schema v3**，新增表只保存 XUID、Java UUID、来源、范围与核验时间；不保存 token，不作为授权缓存。旧 v1/v2 的身份 UUID、名字、会话和 `kyokouauth_bedrock_link` 保留，旧链接**不自动导入授权**。升级前请独立备份数据库、配置与产物；关闭角色选择不等于跳过 schema 迁移，也不能假定换回旧 JAR 即可回滚数据库。

本次未部署到运行服，未迁移真实数据库、密钥或玩家存档。静态接口、公共 seam/隔离回归和真人验收分别记录；Java/Geyser 三行 GUI 与头颅渲染、基岩原生自动回连、两角色实际存档及 SR 共存仍需操作者用真实客户端验收，不能提前把计划或 mock 写成已通过。

### 网络与限流

名字查询和正版会话验证使用不同预算：同名查询缓存并合并并发请求；正版会话逐连接校验，同一 IP（IPv6 按 /64）和名字仅允许一个在途握手，整个代理默认每分钟最多启动 60 次（`premium.session-*`）。未完成、断开或超时后默认退避 30 → 60 → 120 秒，拒绝的重试不延长截止时间；真实握手成功清零失败次数。客户端声明的 UUID 只用于提前拒绝明显不匹配的名字，不能作为放行凭证，也不会跨连接复用握手结果。上游或共享出口仍可能返回 429，限流时不降级为离线认证。

名字归属查询优先使用官方 Minecraft Services 接口，以旧 Mojang 接口作为有限备用。每个来源独立遵从 `Retry-After`；一个来源限流不会暂停健康来源，每次实际 HTTP 请求（包括备用请求）都会计入预算。只有所有来源限流才进入整体退避；错误页、空 404、超时或无法验证的资料不会被当成名字可用。`premium.directory-network` 可设 `inherit`（默认，继承 JVM 出站设置）或 `direct`（单独 Java 直连客户端，不使用 JVM HTTP 代理）；只影响名字查询，不影响正版会话或微软请求，也不改变 OS/TUN 路由。

微软设备码申请对瞬时网络失败最多尝试 3 次，等待期间显示进度；429 遵从 `Retry-After`。日志只记录安全错误类别、HTTP 状态与耗时，不记录验证码或令牌。若部署环境需要 HTTP 出站代理，可在 JVM 启动参数设置 `-Dhttps.proxyHost` / `-Dhttps.proxyPort`，插件默认不强制任何本机代理地址。

认证 HTTP 响应上限为 **1MiB 原始字节**，覆盖声明长度与 chunked 响应；超限取消订阅，整个响应体仍受超时约束。配置缺键继续使用默认值，但显式错误类型、越界和非有限坐标会拒绝初始化。`safety.pending-login-limit`、`io-workers`、`io-queue-capacity` 默认分别为 4096、4、256；满载或关闭时拒绝新任务，不将阻塞认证工作转移到代理事件线程。`verification.poll-interval-seconds` 是最小轮询间隔，绝不会压低微软返回的 interval 或 Retry-After。JDBC 只准备选中的数据库驱动；关闭按任务、连接池、自有驱动 loader 的顺序回收。

### 认证前禁用命令

在插件 `config.yml` 的 `verification.blocked-commands` 添加命令根（不带参数），重启代理生效。默认仅禁用 `server`；例如同时限制玩家列表和私聊：

```yaml
verification:
  blocked-commands:
    - server
    - glist
    - msg
```

限制只作用于当前未认证的玩家：执行会被拒绝，命令树与补全隐藏，认证后随正常后端命令树恢复。控制台不受此列表限制，其他插件已拒绝的操作不会被重新允许。大小写、前导 `/` 和命名空间统一处理；独立别名也应列入配置。`[]` 关闭此列表，`["*"]` 禁用认证前所有命令；无论列表如何配置，未认证切服隔离都不能关闭。`/ka reload` 只重载消息，不重载该列表。

### 微软身份自动改名

**默认开启**，新生成配置及缺失键均采用 `true`；升级不会覆盖管理员显式设置的 `false`。需要关闭时配置并重启代理：

```yaml
verification:
  auto-rename: false
```

用新离线名字完成同一微软账号的验证后，原绑定会原子迁移到新名字并释放旧名字，XUID/UUID 不变。新名字若属于正版账号、已被其它身份占用或无法确认归属，则拒绝迁移。旧在线连接会被要求使用新名字重连；新名字首次使用临时 UUID 时也需重连一次，随后按原有会话设置进服，不再循环绑定。基于名字而非 UUID 存数据的第三方插件，不在本功能迁移范围内。

关闭时不会自动改名，提示会明确给出已绑定的旧名字，而不是声称“已改名、重新连接”。`/ka reload` 仅重载消息，不重载此开关。

## 命令

```
/kyokouauth whois <名字>      查看名字绑定
/kyokouauth session <名字>    查看可信会话
/kyokouauth verify <玩家>     给验证中的玩家重发验证码
/kyokouauth unbind <名字>     解除绑定
/kyokouauth rename <旧> <新>  改名
/kyokouauth untrust <名字>    清除可信会话
/kyokouauth status            查看状态
/kyokouauth reload            重载语言文件
```

别名 `/ka`，权限节点 `kyokouauth.admin`。

## 兼容性

| 项目 | 范围 |
|---|---|
| 代理 | Velocity 4.3.1；BungeeCord 官方构建 2111；使用平台提供的 Netty 4.2，启动时检查链接能力 |
| Java | Limbo/Engine 最低 21；还需满足所用代理的运行要求；构建/验收使用 JDK 25 |
| 验证大厅 | Minecraft **1.21～26.3 正式版**（协议 767～777） |
| 存储 | SQLite、H2、MySQL、MariaDB、PostgreSQL |

版本族：1.21/1.21.1、1.21.2/1.21.3、1.21.4、1.21.5、1.21.6、1.21.7/1.21.8、1.21.9/1.21.10、1.21.11、26.1/26.1.1/26.1.2、26.2、26.3。不是所有大于 1.21 的未知未来版本或快照都自动兼容；需要大厅但不在目录内的客户端会收到明确提示，不会回退后端。真实后端仍须与所用代理及客户端兼容，本插件不承担真实后端的协议转换。

新配置 `limbo.biome` 使用资源 key（默认 `minecraft:plains`），每个协议独立解析 ID；旧 `limbo.biome-id` 按原 26.3 注册表解释再映射，不能把一个数值直接套用所有版本。

## 构建

需要 JDK 25 和 Python 3.11+。生成器只使用固定 Maven 上游依赖及 source JAR；R8 9.5.32 仅为校验 SHA256 的构建工具，不随插件分发。普通 `shadowJar`、`check`、`build` 都执行产物审计：**每个平台完整 JAR 硬上限 20MiB，优化目标 10MiB**，超限构建失败，不转移为额外依赖下载。

```bash
./gradlew build
```

普通测试仅使用本地受控服务，不申请真实设备码或查询真实 Mojang 档案。确需联网验收时显式运行 `./gradlew -Dkyokou.test.live=true :core:test --tests '*DeviceCodeFlowLiveTest'`；它仍不能替代真实账号完成授权的验收。外部数据库测试需要独立测试库（会清空插件测试表），配置方式见 [ServerDialectTest](core/src/test/kotlin/moe/ryuu/kyokouauth/core/storage/ServerDialectTest.kt)。此前基线在全新 loopback 隔离实例验证了 MySQL 8.4.4 与 MariaDB 11.4.4（各两项通过），实例已停止；该记录不代表本次新增 schema/角色选择已完成这些服务器的回归。PostgreSQL 未配置，未声称实测通过。

产物分别在 `platform-velocity/build/libs/` 与 `platform-bungee/build/libs/`。每个平台包含全部11个协议；本次含 Avatar 适配的成品为 Velocity **10,587,876B（10MiB软目标多102,116B）**、Bungee **10,615,614B（软目标多129,854B）**，均通过未放宽的20MiB硬上限及四组件闭包审计。独立官方Codec对最终Velocity成品的767～777协议全部通过，真实Geyser生命周期也验证了自定义物品翻译和最终文字样式；这不代表手机GUI渲染、真实微软/Xbox授权或Paper玩家存档已实测。不外置下载协议依赖，也不打包Mojang游戏JAR。模块：

| 模块 | 内容 |
|---|---|
| `core` | 认证规则、存储、文本、HTTP。零平台依赖 |
| `limbo-api` | JDK-only 玩家、文本、身份/转发与 Engine 契约 |
| `limbo` | 版本目录、Engine 装配、仅删除未用成员的裁剪及统一入口 |
| `limbo-protocol-767`～`777` | 构建期生成的稀疏 Codec、对应原厂 serializer、各版官方配置数据 |
| `limbo-runtime` | 单份私有 NBT/math/fastutil/Gson/SLF4J，不复制 Netty |
| `limbo-text-legacy/json/nbt` | 三文本格式族，保留泛型和 sealed 类型契约 |
| `proxy-common` | 共享认证接线、验证会话、配置/消息和管理命令业务 |
| `platform-velocity` | Velocity 原生事件、profile、组件和命令适配 |
| `platform-bungee` | Bungee 原生事件、profile bridge、私有入场票据与命令适配 |
| `platform-geyser-transfer` | 固定 Geyser 的可选原生 Transfer 地址白名单扩展，不参与认证 |
| `platform-geyser-avatar` | 固定 Geyser 的可选库存头像定义、自有资源覆盖包及只读 offered 状态，不参与认证 |

Limbo 的物理目录统一收拢，Gradle 逻辑 project ID 保持兼容（例如 `:limbo-protocol-767:test`），不额外创建空的聚合项目：

```text
limbo/
  api/                    # limbo-api
  runtime/                # limbo-runtime
  text/{legacy,json,nbt}/  # 三文本族
  protocols/{767..777}/    # 十一个版本实现及fixture
  engine-src/             # 共享协议源码与测试
  codec-generator/        # 构建期生成器
  src/                    # 统一入口
```

## 文档

- [架构决策记录](docs/adr/) —— 每个非显然选择的论证

### 协议验包与产物检查

以下命令从仓库根目录执行；工具默认使用 `C:/Program Files/Zulu/zulu-25`，下载通过本机 `127.0.0.1:7890` 代理。官方服务端、映射、日志及临时文件均保存在工作区 `.narrafork/multiversion/`，不打包 Mojang JAR：

```bash
# 首次准备：取得固定 MCProtocolLib 与对应官方服务端；旧版用各自官方映射重命名。
python tools/prepare-limbo.py
python tools/remap-limbo-vanilla.py

# 对两个实际最终插件产物分别验证全部 11 个协议；串行运行以限制内存。
python tools/verify-limbo-matrix.py --jar platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar --workers 1
python tools/verify-limbo-matrix.py --jar platform-bungee/build/libs/KyokouAuth-Bungee-0.1.0-SNAPSHOT.jar --workers 1

# 独立检查依赖重定位、ServiceLoader、fixture hash、字节码目标与产物 SHA256。
python tools/audit-limbo-artifact.py platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar
```

[单协议入口](tools/verify-limbo.py)支持 `--protocol 767`～`777`，不指定时只验 777；`--vanilla-dir` 可覆盖该协议的官方运行时目录。它检查实际产物导出的包是否被对应官方 codec 完整消费，以及注册表、世界、光照、传送和富文本语义；**通过不代表真实客户端已经渲染**。[完整矩阵](tools/verify-limbo-matrix.py)会记录产物 hash 和逐协议结果，并拒绝运行途中发生变化的 JAR。

### 隔离原版客户端验收

[Velocity 入口](tools/velocity-e2e.py)与 [Bungee 入口](tools/bungee-e2e.py)可运行受控 Services 的实际平台 Gate；测试 bootstrap 不进入生产插件。代表版本分别使用 `1.21.1`、`1.21.4`、`1.21.5`、`26.3`，两平台代理的客户端/JVM 验收应串行执行，不修改正在使用的代理：

```bash
python tools/velocity-e2e.py --scenario held --version 1.21.1
python tools/bungee-e2e.py network --scenario held --client --version 1.21.1 --observer
```

[共用客户端入口](tools/limbo-e2e.py)也可以连接已经运行的工作区隔离实例：

```bash
python tools/limbo-e2e.py --external-proxy --version 1.21.1 --port 25679 --name OracleLocal --proxy-log .narrafork/bungee-e2e/runtime/held/console.log --ready-marker "BUNGEE_E2E_READY {name}" --observer --timeout 120
```

端口、日志路径和可信 `onJoin` marker 必须与该测试实例一致；占位符 `{name}` 由工具展开。工具自动调用[客户端准备入口](tools/prepare-limbo-client.py)，只读复用本机 `.minecraft` 的公开游戏文件，缺失版本/库/资产下载到工作区；不读取启动器账户、玩家凭证或身份库。

`--observer` 默认开启：纯 JDK observer 只读原版客户端的世界、玩家和当前界面状态，不改客户端类、不注入网络 ACK。767/768 没有 player-loaded，不能仅以代理连接成功或库客户端 ACK 宣称渲染完成。普通共用客户端验收在可信入场和 observer 条件满足后保持连接 12 秒；`--expect-verification` 默认保留旧的链接初始化快速探针语义，看到链接即退出，**不能替代 12 秒验收**；受控 Services 矩阵应同时传入 `--expect-verification --hold-seconds 12`，在可信入场、当前 observer 状态和测试链接都满足后再保持 12 秒。`--hold-seconds` 只接受非负整数，不传时保留原有等待语义。受控 Services 的测试链接不会请求真实微软验证码。

## 许可证

[Apache-2.0](LICENSE)。打包的 MCProtocolLib 及其 NBT 文本序列化依赖保留各自许可证与声明。
