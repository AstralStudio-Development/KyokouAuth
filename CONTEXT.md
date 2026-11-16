# KyokouAuth

一个 Minecraft Java 版服务端的微软账户认证插件。它解决的场景是：服务器以离线模式运行（`online-mode=false`），但要求每一个进入世界的玩家都必须先证明自己拥有一个微软账户——不论他是否购买过 Java 版。购买过的人以正版身份进入，没购买的人以自己的 Xbox 档案作为身份进入。

## 当前实现边界

- `core` 保留认证/身份与存储规则；`proxy-common` 共享连接级访问控制、微软验证会话、限流、配置/消息及管理命令。Velocity 与 Bungee 只适配各自事件、profile、组件和后端连接。
- Limbo 按协议 767～777 支持 1.21～26.3 正式版；固定 MCProtocolLib 的协议实现独立重定位，普通依赖共享，文本只分三族，Netty 4.2 由平台代理提供。稀疏 Codec/ID/原厂 factory 在构建期生成；仅对 Limbo 图删除未用成员，不携带完整 SDK 或运行时下载协议库。`limbo-api` 公共模型不携带库类型；完整平台 JAR 的构建硬上限为20MiB，目标10MiB。
- Velocity 身份使用 modern HMAC 转发；Bungee 使用进程内 authority 原子兑换的短时一次性票据，绑定 exact 连接、身份与目标协议。未认证连接的任何失败/fallback/切服都不能进入真实后端。
- 验证标题仅在 challenge ready 后显示一次，结束/重启时清理；点击链接仍为完整富文本。后端实际协议兼容与尚未发布的未来版本不在自动兼容承诺内。

## Language

### 身份

**Identity**:
一个玩家在服务器上不可变的主体。它由微软账户决定，而不是由玩家在客户端输入的任何文本决定。
_Avoid_: 账号, 账户, 用户

**XUID**:
微软 Xbox 账户的 64 位数字标识。通过设备码验证从 Xbox Live 取得的唯一凭证，本项目中它是非正版玩家的全部身份来源。
_Avoid_: Xbox ID, 玩家 ID

**Mojang UUID**:
正版玩家在 Mojang 账户系统中的 UUID。通过登录握手向 Mojang 会话服务器验证取得，与 XUID 无关。
_Avoid_: 正版 UUID, 官方 UUID

**Bound Identity**:
已经写入数据库、把「一个名字」与「一个 XUID 或 Mojang UUID」永久绑定的身份记录。本轮登录是否可免验证，取决于它是否存在。
_Avoid_: 已注册, 已登录

**Premium Player**:
拥有 Java 版、且客户端能完成 Mojang 握手的玩家。以 Mojang UUID 作为身份，以 UUID 常规格式出现。
_Avoid_: 正版用户, 在线玩家

**Microsoft Player**:
未购买 Java 版、以微软账户验证取得 XUID 的玩家。其身份 UUID 由 XUID 派生。
_Avoid_: 离线玩家, 盗版玩家, 非正版

**Provisional Identity**:
首次绑定时尚未取得 XUID 之前的临时身份，只用于把玩家送进 Limbo。它在绑定完成后被丢弃。
_Avoid_: 临时 UUID, 临时账号

### 认证

**Auth Channel**:
玩家证明自己身份的具体途径。v1 只有两条：Mojang 握手与微软设备码。
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
玩家已连接进世界、但尚未通过认证时停留的状态。玩家被冻结在原位、失明无关但无法移动/交互/聊天，只能读取提示并点开链接。
_Avoid_: 登录大厅, 等待区, limbo world

**First Bind**:
一个从未出现过的名字第一次完成认证并写入 Bound Identity 的过程。首次确定真实 UUID 后需重连一次；显式开启自动改名时，从新名字的 Provisional Identity 切回原有稳定 UUID 也遵循此规则。
_Avoid_: 首次注册, 首次登录

### 名字

**Name**:
玩家在客户端输入、或由服务端指定的登录名。它是一段可变的文本，本身不证明任何事。
_Avoid_: 用户名, 玩家名

**Name Reservation**:
「一个名字永久属于一个 Identity」这一规则。它让名字从可随意声称的文本变成需要被验证才能使用的资源。
_Avoid_: 名字占用, 抢名

## Relationships

- 一个 **Identity** 最多持有一个 **Name**；一个 **Name** 永久属于至多一个 **Identity**。
- 一个 **Name** 如果被 Mojang 账户持有，则永远不能被任何 **Microsoft Player** 的 **Identity** 绑定。
- **Premium Player** 的 **Identity** 由 **Mojang UUID** 决定；**Microsoft Player** 的 **Identity** 由 **XUID** 决定，其 UUID 由 XUID 派生。
- 一次连接只有在 **Bound Identity** 存在且 **Play Session** 有效时才跳过 **Limbo**。
- 一次 **First Bind** 必然产生一次重连：验证期间的身份是 **Provisional Identity**，它不属于任何 **Identity**。
- 默认由管理员改名；显式开启 `verification.auto-rename` 时，同一 XUID 通过新一轮微软验证可原子迁移到未占用、非正版的新 **Name**，而非增加别名；原 UUID 不变，旧名释放。
