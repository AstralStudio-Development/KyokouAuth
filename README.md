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

**不是零配置插件。** 服务器只需选择一种代理（Velocity 或 BungeeCord）及一个真实后端，不需要同时运行两种代理。玩家不需要模组或逐人头像配置；下列是管理员的部署前提：

| 需要的功能 | 必须配置或安装 |
|---|---|
| Java 微软认证 | 对应平台代理 JAR、代理离线模式、该平台的后端转发配置；插件默认 SQLite |
| 基岩接入 | Geyser 指向代理、同代理内的 Floodgate、受控密钥配对、`auth-type: floodgate`、Xbox 验证，以及 `bedrock.enabled` |
| 基岩关联双角色 | `role-selection.enabled`；原版图标箱子菜单，不需要自有 Geyser 扩展、图片HTTP桥或原生 Transfer |

Geyser/Floodgate 不会由 KyokouAuth 自动安装、复制密钥或改写配置。SR 是可选的角色皮肤来源，不是认证依赖。基岩选角使用原版物品，不需要安装本项目的 Geyser 扩展、自定义头像资源包，也没有需要额外配对或开放的图片HTTP端口。

仓库中的 `run-*`、`.narrafork`、本机 JVM 代理参数和测试证据不是发行插件的安装依赖；用户自行部署不需要复制这些目录。具体配置见以下分层说明。

```text
plugins/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar
```

### Velocity

代理侧至少配置以下两项；真实后端还需开启对应的 Velocity modern 转发、配对转发密钥，并限制为仅代理可访问，不能只把插件 JAR 放进去：

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

### 基岩关联账号与双角色选择（默认关闭）

这是**选择独立角色**，不是把微软角色迁移到正版 UUID，也不是合并背包、位置、权限或存档：

- **微软角色 U**：本服 XUID 绑定中的稳定 UUID（`UUID(0, XUID)`）和当前离线名字。Xbox gamertag 是微软档案资料，不是本服离线名字；改 gamertag 不会替你改本服名字。
- **正版角色 J**：当前核验关联给出的 Mojang UUID；名字与正版纹理按该 UUID 读取官方 profile。角色流程最多复用60秒内成功验证的公开档案，避免选角、点击和回连短时重复查询；此元数据允许最多60秒的名字/皮肤更新窗口，不是关联或授权缓存。当前强证明与官方关联仍每次重验，过期后失败不会回用旧档案；上游错误会记录安全分类与HTTP状态。正版名字、gamertag 和本服微软名字可能完全不同。
- 本轮双角色菜单只面向具有当前可信 Floodgate **强 XUID 证明**的基岩连接，再核验当前官方 global 关联。Java保留普通微软角色设备码认证与正版Mojang直登，暂不提供设备码后跨到另一角色的GUI；不通过IP、名字或服务器hint续用Java正版权限。公开UUID/profile查询和历史关系行都不是授权。

默认配置如下；开启需要同时启用可信基岩入口及兼容的原生前登录/profile桥。不发送原生Transfer，不要求Velocity开启 `accepts-transfers`，没有自动跳转地址或Java Cookie交接。

```yaml
role-selection:
  enabled: false
  timeout-seconds: 60
  hint-ttl-seconds: 30
```

旧 `transfer-ttl-seconds` 仅作为hint期限的迁移别名；新键优先。旧 `reconnect-host/port`、`avatars` 和图片桥配置已经退役，应从配置删除，启动时会提示，不会因为保留旧键重新打开传输或HTTP端点。

有 U 和当前可用 J 时，基岩认证后打开三行、27格箱子菜单：零基槽 **12/14** 分别用原版 **NAME_TAG/DIAMOND** 表示微软角色/正版角色及当前名字，不把图标冒充实时皮肤头像。标题“请选择角色”继承原版GUI颜色，类别青色、命名gray分隔白名，说明简洁、根组件显式非斜体；Geyser-bound物品文案仅在此处补格式重置，不全局修改聊天或普通物品。仅一个合法角色时在原生Player创建前核验并落定，不制造一次无意义的选角回连；首次J仅在实际准入时建档。

若强FG证明及当前关联允许的原生UUID/name就是合法候选，选择器保留该身份，不无条件改成KA别名；选择期间仍为HELD，绝不提前连接真实后端。在线UUID/名字及并发pending预约冲突会拒绝新连接，不踢掉已有角色，不写SDK alias或关闭官方 `floodgate:*` 消息来源校验。不能安全保留对应身份时保持隔离，不能反射修改已构造Player的身份来凑零重连。

没有选择就不能进入后端，关闭菜单不会替玩家决定；选择超时或连接失效会拒绝。候选、点击和新连接交接按 XUID/UUID 重新核对当前关系及角色，不按名字或客户端提交的 UUID 授权。解绑/改绑、名字占用、角色删除或改名后的旧菜单/交接不能沿用旧权限；微软名字被 Mojang 认领也不能强行覆盖。微软自动改名仍受 `verification.auto-rename` 与原子名字冲突规则约束，角色 UUID 和两套资料不变。同一个 J 关联多个 XUID，不会使这些 XUID 互相取得对方的 U。

**皮肤各归各的角色**：U 只按稳定 UUID 读取可选 SkinsRestorer 的显式设置；未安装或未设置时使用默认皮肤/默认头颅，不按离线名借用同名正版皮肤。J 使用官方 UUID 对应的正版纹理，不套用 U 的 SR 皮肤；官方资料/皮肤查询失败不是“默认皮肤”或认证成功。皮肤与公开 profile 查询本身都不是身份凭证。

选中角色与实际Player的UUID及名字完全相同时，经重新核验、绑定与可信属性恢复后原连接进入后端，**0次选角重连**。真正选择另一UUID或名字时，关闭菜单并提示重新连接当前服务器，**一次普通回连**；服务端hint只记短时选择意图，新Channel仍须有新的有效Xbox/Floodgate证明并重验关联/记录才可消费。它不是凭证，不按IP、名字或客户端UUID授权，不使用原生Transfer，不默认记住上次角色。失效、解绑、改绑、改名或占用仍会拒绝；“一次”不包括玩家主动重试或上游持续故障。

#### 从旧自有扩展版本升级

本项目不再提供或要求 Geyser Avatar/Transfer 生产扩展，也不创建图片HTTP端点、私有CMD或动态头像包。请在受控维护窗口备份并卸载旧 `KyokouAuth-GeyserAvatar` / `KyokouAuth-GeyserTransfer` 扩展及其自有配置/pack，再重启Geyser；只删除磁盘文件不能声称已卸载运行中的扩展。不要删除Floodgate密钥、正常Geyser资源或其他插件文件。

Geyser/Floodgate本体与Xbox验证仍保留。基岩NAME_TAG/DIAMOND就是普通物品，不显示任意实时皮肤头颅；Java真实profile/皮肤及角色各自的SR/官方纹理规则不因此混用。不会为头像额外断线或要求客户端接受我们的资源包。

#### 存储升级与验收边界

身份库启动时增量升级至 **schema v3**，新增表只保存 XUID、Java UUID、来源、范围与核验时间；不保存 token，不作为授权缓存。旧 v1/v2 的身份 UUID、名字、会话和 `kyokouauth_bedrock_link` 保留，旧链接**不自动导入授权**。升级前请独立备份数据库、配置与产物；关闭角色选择不等于跳过 schema 迁移，也不能假定换回旧 JAR 即可回滚数据库。

安装能力和验收范围分开看：每个实际候选JAR的两平台基岩在位准入/普通新登录、Java基础登录、私有转发与协议审计，均应以当次隔离结果报告为准；编译或通用单元测试不能外推完整原生链路通过。测试实例按需启动后停止，不要求同时部署两种代理；Java高级跨角色能力也不从基岩结果外推。

静态接口、隔离回归、具体部署记录与真人验收分别记录。以前手工合成文字并直接调用 Geyser 底层转换的样式测试，不能证明真实 GUI 报文经过完整翻译后仍正确，也不能替代手机渲染。Java/Geyser 三行 GUI 的实机显示、基岩原生自动回连、两角色实际存档及真实账号登录仍需现场验收；不得把字段、合成输入或 mock 当作已显示成功。

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

产物分别在 `platform-velocity/build/libs/` 与 `platform-bungee/build/libs/`，每个平台包含全部11个协议。字节数、SHA256 和相对10MiB软目标的差额以当次成品审计输出为准；20MiB硬上限及三组件（`CUSTOM_NAME`、`LORE`、`PROFILE`）闭包检查不因功能增加而放宽。已执行的官方Codec矩阵只证明对应产物的协议编解码，不等于完整原生登录、手机GUI渲染、真实微软/Xbox授权或Paper玩家存档验收；Geyser真实翻译检查也必须区分合成输入与真实GUI报文。不外置下载协议依赖，也不打包Mojang游戏JAR。模块：

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
