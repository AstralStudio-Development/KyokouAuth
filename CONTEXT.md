# KyokouAuth 架构与领域上下文

KyokouAuth 是运行在离线模式（`online-mode=false`）Velocity / BungeeCord 代理上的微软认证插件。Java 正版名字强制通过 Mojang 会话握手验证，其余 Java 名字进入隔离 Limbo 通过微软设备码证明 XUID；可选的 Floodgate 基岩入口校验绑定当前底层网络连接的强 XUID 证明，并在开启角色选择且具备官方 Global 关联时，允许玩家在独立的微软角色（U）与正版角色（J）之间选择进入。

开发构建与本地验证工具见 [docs/development.md](docs/development.md)；代理接管登录与基岩选角的设计背景见 [ADR-0018](docs/adr/0018-proxy-owns-the-login.md)。

## 模块边界

- **`core`**：零平台依赖的认证协调、身份模型、官方关联解析、JDBC 存储（schema v3）与富文本解析。
- **`proxy-common`**：唯一持有连接准入权的状态机 `LoginGate`、单次设备码会话 `VerificationSession`、有界认证 IO 池、限流器、配置校验与管理命令。
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
   - **微软角色（U）**：`UUID(0, XUID)` 与本服当前离线名（独立于 Xbox Gamertag）。
   - **正版角色（J）**：官方关联返回的 Mojang UUID 与按该 UUID 查询的官方名字/正版纹理（首次 J 仅在实际选中准入后建档）。
   - 两套角色资料完全独立，不合并/迁移存档，同一 J 关联多个 XUID 时不互授各自 U，普通 Mojang 登录与 IP 免验也不反向授予另一角色权限。数据库 schema v3 的 `kyokouauth_account_link` 仅记录核验元数据，不含令牌或可复用授权，旧 v2 链接不导入授权。

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

- **Identity（角色身份）**：玩家在服务端使用的稳定角色标识（当前名字 + 稳定 UUID），分为 `Microsoft`（U）与 `Premium`（J）两个独立命名空间。*避免混称：账号、账户、用户。*
- **Authenticated Subject（已认证主体）**：当前连接已通过强证明确认的持有者——由设备码/Xbox 或绑定当前通道的 Floodgate 证明的 **XUID**，或由 Mojang 握手证明的 **Mojang UUID**。
- **Role（可选角色）**：主体获准使用的独立服务端档案（U 或 J）。关联只授予当次选择资格，不改变 UUID 或合并存档。
- **Account Link（官方账户关联）**：向官方 Global 接口实时核验的关联结果（`Linked` / `NotLinked` / `Unavailable`），未知错误一律按 `Unavailable` 拒绝。
- **XUID**：微软 Xbox 账户的稳定 64 位整数标识，不随 Gamertag 改变，派生 U 的 `UUID(0, XUID)`。*避免混称：Xbox ID、玩家 ID。*
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

## 关键实体关系

- 一个 **Identity** 同一时刻最多持有一个 **Name**，一个 **Name** 最多归属一个 **Identity**；原子改名立即释放旧名，不保留历史名字别名。
- 被 Mojang 正版账户持有的 **Name** 优先归属正版，不可被微软角色绑定或强行覆盖。
- **Play Session** 仅服务于既有微软角色 U 免验，不能替代基岩选角所需的强 XUID 证明，普通 **Mojang Handshake** 也不反授 U。
- 选角菜单候选与短期重连意图均不等同于后端准入；唯有当前连接通过证明、关联与角色状态重验，且原生 Profile 与所选角色一致后，`LoginGate` 才授予最终准入。
