# KyokouAuth

一个离线模式（`online-mode=false`）Velocity / Bungee 代理的微软认证插件。普通 Java 正版名字通过 Mojang 会话验证，其余 Java 名字通过设备码证明 XUID；可选 Floodgate 入口验证当前连接的强 XUID 证明。本轮角色选择限基岩：显式开启后可选择本服微软角色及当前官方关联授予的正版角色，两套角色资料保持独立。Java 保留基础微软角色认证与 Mojang 直登，设备码后跨到另一角色的高级流程本轮不支持。

## 当前实现边界

- `core` 保留认证/身份与存储规则；`proxy-common` 共享连接级访问控制、微软验证会话、限流、配置/消息及管理命令。Velocity 与 Bungee 只适配各自事件、profile、组件和后端连接。
- `bedrock.enabled` 与 `role-selection.enabled` 独立且默认 false；关闭角色选择时仍仅支持未 linked、保留非 Java 前缀的 Floodgate 身份。`BedrockProof` 绑定实际 Channel、提供方代次/属性对象及原生 XUID/linked/profile 快照，选中角色另存；不能篡改 linked 字段或用公开 UUID 查询代替当前连接证明。无绑定的基岩显示名不写 Java 名字表、不签发 Java IP 会话。Geyser 保持 `java.auth-type: floodgate`、`advanced.bedrock.validate-bedrock-login: true`；无有效 Xbox 证明在网关拒绝，不回退设备码。本次不增加匿名入口或账号白名单，有效微软账号仍按既有规则准入。
- 基岩角色选择只接受当前 Channel 的可信 Floodgate 强 XUID 证明，再核验官方 global 关联；普通 Mojang 会话不反授 XUID，IP 免验不升级正版权限。U 是本服 XUID 绑定的稳定 UUID/当前离线名，J 是当前关联 Java UUID 的独立角色；不迁移/合并资料，不因 linked 名字制造 U。双角色每次显示三行 27 槽 GUI，零基 12 为 U 的原版 NAME_TAG，14 为 J 的原版 DIAMOND；仅一个时在原生 PreLogin/Player 创建前落定，不先开菜单。首次 J 仅选后建档，不记住上次选择或自动跳过双选。
- U 只按 UUID 读取可选 SR 显式皮肤，否则默认；J 使用官方 UUID 的名字/正版纹理。角色流程仅对成功验证的公开名字/签名纹理元数据按 UUID 最多复用60秒（128项、同UUID单飞），不缓存关联、授权或失败；过期查询失败不回旧值，名字/正版皮肤更新允许此窗口。错误仅保留固定分类、HTTP码及安全Retry-After，不抹成无原因错误，也不保留上游正文/URL/令牌或原始 cause/suppressed。gamertag 不覆盖本服微软名字，名字/关系/皮肤查询都不是认证证明。schema v3 只保存核验元数据，无 token/授权缓存；历史 v2 链接不导入授权。
- 基岩图标不承诺真实 skin 头像，不需要自有资源包；普通 Java 的真实 profile/纹理与底层 PLAYER_HEAD/PROFILE 支持保留，不据此宣称提供 Java 高级双角色 GUI。容器标题继承原版默认色；类别青色、命名 gray 分隔及说明、白色名字。仅强 FG 来源的选角 name/lore 补显式 legacy reset 与基色，普通 Java 仍纯组件，不改全局聊天或普通物品。真实 Geyser 翻译 probe 仅用于隔离测试，不是生产扩展。
- 选中授权是 Gate 私有的 `SelectedRoleAuthorization` / `privateSelectedAuthorization`，区分 PreLogin 单角色、当前连接点击、新登录续接，绑定 exact session、当前强证明、稳定角色引用与当前关联，不伪造同 session handoff，也不只改 access 放行。单角色 0 次选角重连；双选仅当实际 Player UUID/name 与所选角色完全相同时在位授权，0 次选角重连；不同 UUID 或名字必须普通回连，正常成功选角至多 1 次，不包含上游失败或主动重试。已构造 Player 的 UUID/name 不改，原连接仅恢复所选角色的可信属性。
- 双选若原生 UUID/name 已是合法候选可保留该登录槽位，否则使用隔离身份；选择前始终 HELD，不进入真实后端。候选、点击、最终准入均重验当前 FG proof、关联、角色及 UUID/名字占用，原子预约随断线、超时、拒绝、停用清理；旧菜单/异步回调不能授权或清理新 session。已在线角色优先拒绝新 chooser，不能踢掉它或改 UUID 绕过；不写 SDK alias/linked 状态，不关闭官方 `floodgate:*` 消息来源检查。
- 不同身份的服务器端 hint 只保存短时选择意图，不是凭证。点击后提示手动普通回连；只有不同的新 session/Channel、fresh Xbox/Floodgate 证明、相同 XUID 及当前关系/角色重验通过才能消费一次。旧连接、过期、重放、解绑/改绑/改名、占用均拒绝，不回退匿名 Java 或另一角色，不使用 Cookie。`role-selection.hint-ttl-seconds` 默认 30，旧 `transfer-ttl-seconds` 仅为迁移 alias，新键优先；旧 `avatars.*`、`reconnect-host/port` 忽略并提示退休，不再要求回连端点。
- historical：动态头像包、私有 CMD、24682/24683 图片桥、`platform-geyser-avatar` / `platform-geyser-transfer` 生产模块及原生 Transfer/Java Cookie 跨角色流程已退休，本轮不发送 Transfer，也不要求开启 `accepts-transfers`。安装仍须选择一种代理、一个后端，并为基岩正确安装配置 Geyser/Floodgate 本体及可信链路，不称 zero config。本文是候选实现契约，未宣称本轮已部署或原生全通过；自动化 0/1 回连计数与手机/原版客户端人工验收分别记录，运行状态以当次部署报告为准。
- Limbo 按协议 767～777 支持 1.21～26.3 正式版；固定 MCProtocolLib 的协议实现独立重定位，普通依赖共享，文本只分三族，Netty 4.2 由平台代理提供。稀疏 Codec/ID/原厂 factory 在构建期生成；仅对 Limbo 图删除未用成员，不携带完整 SDK 或运行时下载协议库。`limbo-api` 公共模型不携带库类型；完整平台 JAR 的构建硬上限为20MiB，目标10MiB。
- Limbo 子模块物理目录均在 `limbo/` 下：`api`、`runtime`、`text/{legacy,json,nbt}`、`protocols/{767..777}`；共享源码仍为 `engine-src`。Gradle逻辑project ID不变，生成路径取项目layout，裁剪协议取生成manifest而非目录名。
- Velocity 身份使用 modern HMAC 转发；Bungee 使用进程内 authority 原子兑换的短时一次性票据，绑定 exact 连接、身份与目标协议。Limbo 仅由 adapter 私有映射持有（Velocity raw target / Bungee 未公开 ServerInfo），不进入原生服务器列表、补全或公开发现；同名对象不能冒充或替换私有目标。未认证连接的任何失败/fallback/切服都不能进入真实后端。
- 验证标题仅在 challenge ready 后显示一次，结束/重启时清理；点击链接仍为完整富文本。令牌到达后提示正在确认 Xbox 身份；轮询仍保留完整响应后的上游间隔，只扣除其后的本地处理，slow_down 精确每次加5秒而非额外指数等待，429/503 Retry-After 不能压低。阶段计时只含数字与阶段名，不带身份、验证码、令牌或URL。后端实际协议兼容与尚未发布的未来版本不在自动兼容承诺内。
- `verification.blocked-commands` 默认 `[server]`，按当前 exact 连接认证状态限制命令执行与客户端命令树/补全；支持自定义根、大小写/斜线/命名空间归一、`[]` 与 `*`，不影响控制台或放松切服隔离。认证后使用正常的新后端命令树恢复，不缓存跨连接授权。`verification.auto-rename` 默认 true，管理员显式 false 保留。
- 已删除零调用的旧 LoginRegistry/MojangSession/Skin、Velocity 类型别名和九个一次性旧探针；当前 Mojang proof、textures 转发与可维护 e2e/oracle 入口保留。VerificationSession 只管理单次设备码尝试，exact 连接授权仍唯一归 LoginGate。
- 认证 IO 队列有界（默认4 workers/256 queued），pending 默认上限4096；拒绝、异常、取消与关闭不可伪装成成功null，也不在代理事件线程执行阻塞工作。HTTP按原始字节限1MiB、完整body受超时，错误不带敏感正文；显式非法配置拒绝启动。仅准备所选JDBC驱动，任务→池→自有loader按序回收。
- 协议热路径按固定签名缓存构造器、方法、enum及数组类型，启动时校验；预热后不再解析。微基准用于验证减少查找/分配，不等同正式服吞吐承诺。真实认证接口测试须显式 -Dkyokou.test.live=true，普通构建不申请设备码。

## Language

### 身份

**Identity**:
玩家本次使用的服务端角色身份，包含当前名字及稳定 UUID；`Microsoft` 与 `Premium` 是两个角色命名空间，不等同于认证主体或可变名字。
_Avoid_: 账号, 账户, 用户

**Authenticated Subject**:
当前连接已经证明的主体：设备码/Xbox 或可信 Floodgate 证明的 XUID，或 Mojang session 证明的 Java UUID。公开标识、名字、IP 和历史行本身不是证明。

**Role**:
可选择的独立服务端资料身份。本轮基岩强 XUID 主体可在当前关系允许时选择 U/J；Java 设备码仅用于基础 U 认证，Mojang 直登仅授予其 J。关联到同一个 J 的多个 XUID 不互授各自 U，也不合并资料。

**Account Link**:
当前官方 global 关联证据，结果为 Linked / NotLinked / Unavailable。只有官方定义的成功无关联形态才是 NotLinked；未知失败关闭，数据库核验元数据不是可复用授权。

**XUID**:
微软 Xbox 账户的稳定 64 位标识，不随 gamertag 改名。必须由当前连接的强证明取得；它决定 U 的 UUID，并可与当前关联一起解析 J，数值本身不是登录凭证。
_Avoid_: Xbox ID, 玩家 ID

**Mojang UUID**:
Java 角色的稳定标识。普通 Java 登录由 Mojang session 证明；本轮基岩显式角色选择也可由当前强 Floodgate XUID 加当前官方关联授予使用资格。公开 profile 只提供当前名字/皮肤，不证明持有者。
_Avoid_: 正版 UUID, 官方 UUID

**Bound Identity**:
数据库中一个稳定主体/UUID 与当前本服名字的绑定记录，不是认证凭证。首次 J 候选可以尚未有记录，只有选中后才建档。
_Avoid_: 已注册, 已登录

**Premium Player**:
以 Java UUID 使用正版角色 J 的玩家。普通入口依赖 Mojang session；本轮基岩选角入口依赖当前强 Floodgate XUID 及有效 global 关联，两条证明路径均不授权任意 XUID 角色。
_Avoid_: 正版用户, 在线玩家

**Microsoft Player**:
使用本服微软角色 U 的玩家。XUID 来自设备码/Xbox 或当前 Channel 可信 Floodgate 证明；U 的稳定 UUID 由 XUID 派生，当前离线名字独立于 gamertag、正版名字及 J 的资料。
_Avoid_: 离线玩家, 盗版玩家, 非正版

**Provisional Identity**:
首次绑定时尚未取得 XUID 之前的临时身份，只用于把玩家送进 Limbo。它在绑定完成后被丢弃。
_Avoid_: 临时 UUID, 临时账号

### 认证

**Auth Channel**:
玩家证明当前主体的具体途径：Java 使用 Mojang 握手或微软设备码/Xbox；可选基岩路径使用同进程 Floodgate 的 exact-channel XUID 证明。关闭角色选择仍拒绝 linked，开启后按当前 global 关系独立解析角色。
_Avoid_: 登录方式, 认证方式

**Mojang Handshake**:
服务端向客户端发起加密请求、客户端用其会话令牌向 Mojang 确认、服务端再向会话服务器复核的完整流程。它同时证明「这个客户端拥有某个 Mojang 账户」和「这个连接属于该账户」。
_Avoid_: 正版验证, 正版检测

**Device Code**:
微软 OAuth 的受限输入授权流程。服务端取得一枚短码，玩家在任意设备上打开 `microsoft.com/link?otc=<码>` 并在微软页面完成登录，服务端轮询取得令牌。
_Avoid_: 验证码, 授权码

**Verification Session**:
一次设备码流程的完整生命周期：生成、等待、成功或过期。它与连接绑定，连接断开即作废，不可转让、不可复用。
_Avoid_: 设备码缓存, code session

**Login Injection**:
在服务端默认的登录状态机之前接管 `Login Start` 的一层网络处理。它让插件能在玩家被原版逻辑认领之前决定这个连接是谁。
_Avoid_: 包拦截, 登录拦截

### 会话

**Play Session**:
一次已验证通过的登录在当前 IP 上留下的信任记录。它只对同一 IP 生效，跨 IP 失效，并可配置为「永远不生效」。
_Avoid_: 记住登录, session, 免验证

**Limbo**:
尚未获准进入真实后端的隔离连接：完成设备码验证或打开基岩角色菜单。认证/选角期间仍限制聊天、命令和切服；基岩选择器可能预约已证明的原生候选槽位，但始终 HELD，不能挤掉已有在线角色。
_Avoid_: 登录大厅, 等待区, limbo world

**First Bind**:
首次将已认证、已选中的角色写入 Bound Identity。基岩单角色可在原生 Player 创建前落定；Player 一旦创建，若仍是 Provisional Identity 或与所选 UUID/name 不同，必须通过真正的新连接落定，不能只改显示名；首次 J 候选不提前建档。
_Avoid_: 首次注册, 首次登录

### 名字

**Name**:
玩家在客户端输入、或由服务端指定的登录名。它是一段可变的文本，本身不证明任何事。
_Avoid_: 用户名, 玩家名

**Name Reservation**:
一个当前名字至多属于一个角色，改名须原子释放旧名并检查占用与 Mojang 优先规则；名字是受约束的资源，不是身份凭证。
_Avoid_: 名字占用, 抢名

### 呈现

**Role Icon**:
服务端指定的基岩原版选项图标：U 为 NAME_TAG，J 为 DIAMOND；只是选项呈现，不是实时皮肤或认证证明。Java 真实 profile/纹理保持独立。

## Relationships

- 一个 **Identity** 最多持有一个当前 **Name**；一个当前 **Name** 至多属于一个 **Identity**，改名不增加别名。
- 一个 **Name** 如果被 Mojang 账户持有，就不能被 **Microsoft Player** 的 U 强行绑定或继续覆盖。
- J 由 **Mojang UUID** 决定，U 由 **XUID** 派生的 UUID 决定；关联只授予本次选择资格，不改变任一 UUID 或迁移资料。
- **Play Session** 仅供原有微软角色免验，不升级 J 的权限；角色选择所需的强 XUID 必须重新证明。普通 Mojang 登录不反授 U。
- 选角候选不等于进入后端；仅当前连接通过关系/角色重验且真实 profile 落定后，单一 Gate 才授权所选角色。
- `verification.auto-rename` 默认 true，管理员显式 false 保留；启用时同一 XUID 通过新一轮微软验证可原子移到未占用、非正版的新 **Name**，原 UUID 不变。gamertag 变化不自动改本服名字，J 名字/皮肤按稳定 Java UUID 查官方 profile。
