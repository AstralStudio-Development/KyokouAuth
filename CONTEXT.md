# KyokouAuth 架构与领域上下文

KyokouAuth 是运行在离线模式（`online-mode=false`）Velocity / BungeeCord 代理上的微软认证插件。Java 正版名字强制通过 Mojang 会话握手验证，其余 Java 名字进入隔离 Limbo 通过微软设备码证明 XUID；可选的 Floodgate 基岩入口校验绑定当前底层网络连接的强 XUID 证明，并在开启角色选择且具备官方 Global 关联时，允许玩家在独立的微软角色（U）与正版角色（J）之间选择进入。

开发构建与本地验证工具见 [docs/development.md](docs/development.md)；代理接管登录与基岩选角的设计背景见 [ADR-0018](docs/adr/0018-proxy-owns-the-login.md)。

## 模块边界

- **`core`**：零平台依赖的认证协调、身份模型、官方关联解析、独立 AuthMe 只读验证、受控旧档认领存储、JDBC 存储（schema v5，向下保留 v1～v4 不可变历史迁移；v5 为 claim grant 增加 `proof_method`）与富文本解析。
- **`proxy-common`**：唯一持有连接准入权的状态机 `LoginGate`、单次设备码会话 `VerificationSession`、连接级旧档认领流程 `ProfileClaimFlow`、有界认证 IO 池、限流器、配置校验与管理命令。
- **`platform-velocity` / `platform-bungee`**：仅适配各自原生事件、GameProfile 属性、组件转换、私有 Limbo 目标与后端转发，不承载独立认证规则。
- **`limbo/`**：物理收拢 `api`（纯 JDK 契约）、`runtime`（单份共享基础库，Netty 4.2 由宿主代理提供）、`text/{legacy,json,nbt}`（三套协议分族文本库）、`protocols/{767..777}`（11 个正式版稀疏 Codec 与官方注册表数据）及 `engine-src` 共享引擎源码；Gradle 逻辑子项目名保持 `:limbo-protocol-767` 等不变，单平台产物硬上限 20 MiB（目标 10 MiB）。

## 核心安全与架构不变量

1. **单一准入入口（Single Gate）**
   所有后端连接放行唯一由 `LoginGate` 决定。未完成验证或选角的连接在首连、切服、异常或后端回退时一律拦截，绝不进入真实后端。Limbo 端点仅保存在平台适配器私有映射中（Velocity raw target / Bungee 未公开 `ServerInfo`），不暴露给 `/server` 列表、命令补全或公开服务器发现。Velocity 使用 modern HMAC 转发；Bungee 使用进程内原子兑换的短时一次性私有票据，并在连接真实后端前剥离票据。

2. **连接级强证明（Connection-Bound Proof）**
   公开 UUID/名字查询、皮肤纹理、IP 地址、客户端自报 UUID 与数据库历史关系行均不是认证凭证。
   - Mojang 名字归属优先查询 Minecraft Services、以旧 Mojang 接口为独立限流备用；任何 429、超时、空 404 或异常响应均按失败关闭处理，绝不当作名字空闲或降级离线放行。正版会话逐连接握手，同源同名最多单飞一个握手并带退避冷却。
   - `BedrockProof` 必须绑定当前原生网络通道对象、Floodgate 安装代次与不可变的 XUID / UUID / 名字 / linked 快照，并与 `FloodgateApi.getPlayer` 实例核对一致；严禁用公开 UUID 查询代替当前连接证明，已进入基岩分支的连接失败时直接拒绝，绝不回退到 Java 设备码流程。

3. **主体、关联与双角色隔离（U / J Separation）**
   `bedrock.enabled` 与 `role-selection.enabled` 独立且默认 `false`；关闭角色选择时仅接纳未关联且保留隔离前缀的 Floodgate 玩家（不写 Java 名字绑定表、不签发 IP 免验）。开启角色选择后仅面向具备当前强 Floodgate XUID 证明的基岩连接核验官方 Global API（空体、限流或矛盾均视为 `Unavailable` 拒绝，不接纳本地关联；Java 客户端不开放跨角色菜单）：
   - **微软角色（U）**：默认派生 `UUID(0, XUID)` 与本服当前离线名（独立于 Xbox Gamertag）；若该 XUID 在受支持的代理上通过连接级认领继承了旧服离线档案，则持久使用已认领的历史离线 `Profile UUID`（仅限 Java `U`，基岩选角不开放跨档案映射）。
   - **正版角色（J）**：官方关联返回的 Mojang UUID 与按该 UUID 查询的官方名字/正版纹理（首次 J 仅在实际选中准入后建档；绝不改写为其他历史 UUID）。
   - 两套角色资料完全独立，不合并/迁移存档，同一 J 关联多个 XUID 时不互授各自 U，普通 Mojang 登录与 IP 免验也不反向授予另一角色权限。数据库 schema v3 的 `kyokouauth_account_link` 仅记录核验元数据，不含令牌或可复用授权，旧 v2 链接不导入授权。

7. **认证主体与持久档案分离及旧档认领边界（Schema v5）**
   - **已接线的玩家流程**：在 **Velocity 和 BungeeCord** 上支持 **旧 AuthMe 离线档 → Java 微软角色（U）**；Floodgate 不开放旧档认领。正常入口无需 `migration.*` 配置：复制同源停写备份的 `AuthMe/config.yml`、SQLite 文件（如适用）及实际 `playerdata` 到插件 `migration/inbox/`，`migration scan` 在独立单线程执行器只读旧来源并写本地暂存快照，`progress/cancel` 查看与取消，`issues [page]` 查看异常，一次性 `confirm <token>` 后启用持久本地快照并批量登记真实历史 v3 UUID；`check` / `import <batch> <oldName> <actualHistoricalUuid-v3>` 仅保留为单条异常入口，不能按名字或 AuthMe 可选 UUID 字段猜测。新且空闲非正版名的临时 Java U 在当前私有 Limbo 的 `OFFER` / `WAITING_OLD` 阶段，可用 `/ka claim authme <旧名字> <旧密码>` 提交旧证明（同 `/kyokouauth`，支持命名空间；另有 `claim status` / `claim cancel`）。正常快照流程固定使用 `authme-inbox` 批次，必须命中当前快照名字/UUID 允许清单及唯一 `AVAILABLE` 候选；导入本身不是身份凭证；历史匹配仅读取收件目录中的实际玩家 NBT 与受存档支持的缓存关系，存档内 UUID 必须与文件名相同，多重对应全部排除，不改写世界或源库。原人工独立核验后控制台 `approve <session> <recordId> verified` 路径保留。两种旧证明都严格绑定当前会话，后续仍为首次确认、同连接全新 Microsoft/Xbox 设备码证明、二次确认与原子提交，再提示普通重连一次。映射永久生效；`migration disable` 持久停止新认领但不撤销既有历史 Profile UUID。旧 `migration.*` 静态配置只保留为显式人工/直接来源入口，默认关闭。
   - **只读来源与审计**：`AuthService` 已接入 `core` AuthMe 验证器；收件流程自动读取旧配置的连接凭据和库表字段，不强制重复输入 SQL 账号；源端只读事务不等于账号权限被降级。类型必须由旧配置明确的 DataSource.backend 指定，缺失/未知拒绝，不按残留 .db 猜测，也不在 MySQL/MariaDB 失败时回退 SQLite。localhost/回环源在驱动准备与连接前须由 scan --local-source 显式确认当前运行环境；跨机用 --source-host/--source-port 仅覆盖本次端点，其余凭据字段与严格 TLS 保持。选择阶段不做 DNS/网段探测，不保证 DNS 别名对应原机器；来源摘要仅类型/主机/端口/库名，SQLite仅文件名，无SQL凭据或完整URL。每次 scan 仅建立一次旧来源连接，有界流式读取/256 行批写到本地 SQLite，最多 100000 行，导出单事务失败清理；只保留必要受支持哈希，不保留 SQL 凭据/TOTP 秘密/明文密码。导出后关闭旧库，确认与玩家核验只访问本地，重启也不重连来源。扫描不启用或登记候选；一次确认在预检完成后 5 分钟内有效，核对暂存快照/历史记录/目标状态变化后才继续。候选按 256 条短事务写入，避免长期占用目标库；全部完成才原子切换快照指针，失败不替换当前快照，已写但未启用的候选可重扫幂等续做且不能越过当前快照允许清单。冻结/同源备份前提由控制台确认明确承担，不会自动停服锁库。来源只支持 SQLite 一致性快照、MySQL/MariaDB 只读访问；仅支持官方默认 16 位小写十六进制盐的 `$SHA$` / `SHA256`，TOTP 非空或未知哈希拒绝，需管理员独立核验。远程来源强制数据库 TLS 校验与有界超时，连接凭证不是玩家旧密码；具体默认配置及操作步骤见 [README](README.md#推荐复制材料后命令扫描无需迁移配置velocity)。数据库仍是 schema v5，保留 v1～v4，来源授权的 `proof_method` 为 `AUTHME_PASSWORD` 并记录审计。没有 HTTP 认领监听、网页或证书配置。
   - **私密入口与边界**：本地快照支持运行时启用；只有当前连接成功安装受支持代理（Velocity 4.3.1 或官方 BungeeCord 2111）的前置 channel guard 后，原生适配器才向公共 gate 标记精确 session 受保护。未受保护的旧连接不会临时得到认领入口，新连接安装失败则拒绝登录；私密命令不经过普通管理 dispatcher、`CommandExecuteEvent`、Brigadier 回显、后端转发或公共补全事件，也不要求管理权限。只处理 dedicated unsigned / 受支持 Legacy 敏感命令；签名/LastSeen 格式保守断开，不改写或剥离签名。轻量解析后进入有界 authIO，单连接一个进行中、账号 5 次/10min、来源地址 20 次/10min、本地验密并发 4；接管的密码 CharArray 各路径清零，不记录或回显密码/哈希，错误不披露账号存在性细节。关闭模块先关闭绑定原通道，关闭完成前 guard 仍拒绝入站。此功能不增加 offline Minecraft 传输加密，不承诺端到端或运营者绝对不可见，也不防更早原始包记录器、恶意 root/替换插件或客户端本地历史/截屏。
   - **原生 Floodgate 不选角入口**：若当前 XUID 已映射到历史档案，拒绝这次登录，不回退到默认 `UUID(0, XUID)`；此检查不授予基岩连接使用历史档案的权限。
   - **迁移边界**：只认领旧 AuthMe 离线档到 Java 微软角色（U），不支持跨到正版角色（J）。
   - **验收与发布边界**：构造来源账号、微软响应及模块/隔离测试不是真实 AuthMe/Microsoft 账号授权验收；尚无自愿提供的真实测试号，真人授权未验收，项目尚未部署生产。不承诺按名字或私有表存储的任意第三方插件通用兼容。

4. **低重连落定与短期选择意图（0/1 Ordinary Reconnect）**
   基岩选角不发送原生 Transfer 包、不使用 Cookie、不修改已构造原生 `Player` 的 UUID/名字，也不篡改 Floodgate SDK 内部状态或关闭 `floodgate:*` 消息来源检查：
   - 仅有一个合法角色时在原生 `Player` 创建前直接核验落定（**0 次重连**）。
   - 双角色展示三行 27 格箱子菜单（槽位 12 为原版 `NAME_TAG` 表示 U，槽位 14 为原版 `DIAMOND` 表示 J；容器标题继承原版默认色，仅对基岩选角物品名称/说明补显式格式重置与命名 `gray`）。选中角色与当前原生 `Player` 的 UUID 及名字完全一致时原连接直接恢复可信属性放行（**0 次重连**）。
   - 选中不同 UUID 或名字时保存短 TTL（`role-selection.hint-ttl-seconds`，默认 30 秒）、一次性服务端选择意图，断开并提示玩家手动普通重连一次（**1 次重连**）；新连接必须携带全新的有效 Floodgate 强证明并通过关系、角色与在线/预约冲突重验后方可消费，已在线角色绝不被新连接顶下线。

5. **皮肤与 60 秒公开资料窗口**
   U 仅按稳定角色 UUID 读取可选 SkinsRestorer 的显式皮肤设置，缺省回退默认皮肤，绝不借用同名正版皮肤；J 使用官方 Mojang UUID 正版纹理，绝不套用 U 的 SR 皮肤。角色流程仅对成功验证的官方公开名字/签名纹理按 UUID 最多复用 **60 秒**（128 项上限、同 UUID 单飞），仅作为公开资料元数据缓存，不缓存关联、授权或失败结果。

6. **有界执行、命令隔离与安全日志**
   认证 IO 线程池（默认 4 线程 / 256 队列）、挂起登录数（默认 4096）与 HTTP 原始响应体（上限 1 MiB）严格有界，满载或关闭时显式拒绝，不在代理事件线程执行阻塞 IO。`verification.blocked-commands` 默认隐藏并拦截未认证连接的 `server` 命令。验证标题在设备码就绪后只发一次并在结束前清理；轮询严格遵守微软间隔、`slow_down`（+5s）与 `Retry-After`；日志与异常脱敏，仅保留安全错误分类、HTTP 状态码与阶段耗时，绝不记录验证码、令牌、完整 URL 或上游响应正文。

## 领域术语（Ubiquitous Language）

### 身份与角色

- **Identity（角色身份）**：玩家在服务端使用的稳定角色标识（当前名字 + 持久 **Profile UUID**），分为 `Microsoft`（U）与 `Premium`（J）两个独立命名空间。*避免混称：账号、账户、用户。*
- **Authenticated Subject（认证主体）**：当前连接已通过强证明确认的持有者——由设备码/Xbox 或绑定当前通道的 Floodgate 证明的 **XUID**，或由 Mojang 握手证明的 **Mojang UUID**。认证主体回答“当前是谁在登录”，与下游存档使用的 **Persistent Profile（持久档案）** 概念分离。
- **Persistent Profile / Profile UUID（持久档案标识）**：下游服务端用于索引原版玩家存档的稳定 `GameProfile` UUID。对于 `J` 恒等于其 **Mojang UUID**；对于新建 `U` 默认为 `UUID(0, XUID)`，而对于在受支持代理上完成旧档认领的 Java `U`，持久保持为所认领的历史离线 v3 UUID（关闭 `migration.enabled` 后亦不回退）。
- **Role（可选角色）**：主体获准使用的独立服务端档案（U 或 J）。关联只授予当次选择资格，不改变 UUID 或合并存档。
- **Account Link（官方账户关联）**：向官方 Global 接口实时核验的关联结果（`Linked` / `NotLinked` / `Unavailable`），未知错误一律按 `Unavailable` 拒绝。
- **XUID**：微软 Xbox 账户的稳定 64 位整数标识，不随 Gamertag 改变，新建 U 时默认派生 `UUID(0, XUID)`。*避免混称：Xbox ID、玩家 ID。*
- **Mojang UUID**：Java 正版角色的稳定 UUID。公开查询只能获取名字与纹理，不能证明连接持有者。*避免混称：正版 UUID、官方 UUID。*
- **Bound Identity（绑定记录）**：数据库中稳定主体/UUID 与本服当前名字的持久映射，本身不是登录凭证。*避免混称：已注册、已登录。*
- **Provisional Identity（临时身份）**：首次设备码验证尚未取得 XUID 前，仅用于将连接送入 Limbo 的一次性身份，验证结束后即丢弃。*避免混称：临时 UUID、临时账号。*

### 认证与会话

- **Auth Channel（认证通道）**：当前连接证明主体的途径——Java 使用 **Mojang Handshake** 或 **Device Code**；基岩使用同进程 Floodgate 绑定当前通道的强证明。*避免混称：登录方式、认证方式。*
- **Mojang Handshake（Mojang 会话握手）**：代理发起加密挑战、客户端向 Mojang 会话服确认、代理复核会话的完整正版验证流程。*避免混称：正版验证、正版检测。*
- **Device Code（设备码流程）**：微软 OAuth 受限输入授权流程，玩家通过聊天链接或 `microsoft.com/link` 输入短码完成登录，代理轮询换取令牌并核验 Xbox 身份。*避免混称：验证码、授权码。*
- **Verification Session（验证会话）**：绑定单条连接的一次设备码生命周期，断线或超时即作废，不可跨连接转让。
- **Play Session（免验会话）**：微软角色验证通过后在配置时长内留下的同 IP 信任记录，仅用于原微软角色免验，不升级 J 权限。*避免混称：记住登录、免验证。*
- **Limbo（隔离验证空间）**：获准进入真实后端前的私有隔离连接状态，用于完成设备码验证或打开基岩选角菜单，期间禁止切服并限制受控命令。*避免混称：登录大厅、等待区。*
- **First Bind（首次建档）**：已验证角色首次写入 **Bound Identity**。若原生 `Player` 创建时仍为临时身份或与所选角色 UUID/名字不同，须通过一次新连接以稳定身份落定。
- **Name / Name Reservation（名字与名字独占）**：可变的游戏登录名。同一当前名字至多归属一个 **Identity**；Mojang 正版持有的名字不可被微软角色占用或覆盖；`verification.auto-rename` 开启时同一 XUID 可通过新验证原子迁移到空闲非正版名字。
- **Role Icon（角色图标）**：基岩选角菜单使用的原版物品图标（U 为 `NAME_TAG`，J 为 `DIAMOND`），仅作选项展示，不代表实时皮肤头像。
- **Legacy Profile Record（受控旧档记录）**：`migration check` 只读核对批次、旧名及实际历史 v3 `Profile UUID` 与当前库的兼容性，不导入、不预留，也不查 AuthMe 或磁盘。`migration import` 在写入时重检；两者都拒绝同一旧名（忽略大小写、跨批次）对应不同 UUID。Schema v4 的旧名 `name_key` 唯一约束阻止并发登记绕过检查。导入记录只是候选源档案，不能代替旧身份强证明（只读 AuthMe 密码核验或管理员独立人工核验）。
- **Claim（连接级双重认领）**：在 Velocity 和 BungeeCord 上仅面向 `AuthMe -> Java U`：新且空闲非正版名的临时 Java U 在当前私有 Limbo 提交旧身份强证明，来源为已启用的私密 AuthMe 密码入口，或保留的控制台人工独立核验授权。两者都签发绑定精确会话的短期授权；玩家首次确认源档案后，仍须当场完成全新 Microsoft/Xbox 设备码证明并二次确认，再在数据库原子绑定 `XUID`（认证主体）与历史 `Profile UUID`（持久档案），提示普通重连一次生效。认领中取消、超时或冲突均失败断开，绝不降级原地新建。
- **Data Continuity（原版 UUID 资料连续性）**：已认领的 Java `U` 继续以同一历史 `Profile UUID` 进入下游子服，从而延续原版及严格按 `GameProfile` UUID 索引的玩家数据（不承诺按名字或私有表索引的任意第三方插件通用兼容）。发生 UUID 或主体冲突时立即拒绝并保持人工处理。

## 关键实体关系

- 一个 **Identity** 同一时刻最多持有一个 **Name**，一个 **Name** 最多归属一个 **Identity**；原子改名立即释放旧名，不保留历史名字别名。
- 被 Mojang 正版账户持有的 **Name** 优先归属正版，不可被微软角色绑定或强行覆盖。
- **Play Session** 仅服务于既有微软角色 U 免验，不能替代基岩选角所需的强 XUID 证明，普通 **Mojang Handshake** 也不反授 U。
- 选角菜单候选与短期重连意图均不等同于后端准入；唯有当前连接通过证明、关联与角色状态重验，且原生 Profile 与所选角色一致后，`LoginGate` 才授予最终准入。
