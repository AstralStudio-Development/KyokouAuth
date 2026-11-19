<div align="center">

# KyokouAuth

面向 Velocity / BungeeCord 离线模式代理的微软账号与正版混合认证插件

![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)
[![Minecraft 1.21–26.3](https://img.shields.io/badge/Minecraft-1.21%E2%80%9326.3-3C8527.svg)](#兼容性与环境要求)
[![Build JDK 25](https://img.shields.io/badge/Build-JDK%2025-ED8B00.svg)](#构建与开发)

</div>

## 简介

离线模式（`online-mode=false`）代理默认信任客户端自报的游戏名。**KyokouAuth** 部署在代理层，让玩家在进入下游子服之前完成正版会话核验或微软账号认证，无需客户端安装任何模组。

### 登录行为概览

| 入口类型 | 游戏名归属 / 状态 | 登录行为 |
|---|---|---|
| **Java 版** | 已在 Mojang 注册的正版名 | 自动走正版会话握手，正版启动器无感直进，冒名离线连接直接拒绝 |
| **Java 版** | 未被正版占用的名字 | 进入内置隔离验证空间，点击聊天栏链接通过微软设备码绑定 Xbox 身份（XUID）后放行 |
| **基岩版**（可选） | 未关联账号（仅开启 `bedrock`） | 校验同代理 Floodgate 身份，保留隔离前缀（如 `.`）直接进入，无需输设备码 |
| **基岩版**（可选） | 官方 Global 关联账号（同时开启 `role-selection`） | 在本服微软角色与已关联的 Java 正版角色之间独立选择进入 |

- **单代理部署**：只需选择 **Velocity** 或 **BungeeCord** 其中一种代理安装对应 JAR，下游子服无需再装本插件。
- **内置多版本验证空间**：自带 Minecraft 1.21～26.3 正式版（协议 767～777）验证界面，无需额外搭建大厅服或安装跨版本协议转换插件。验证空间仅在插件内部路由，不会出现在 `/server` 列表或命令补全中；任何验证失败或异常都会断开连接，绝不回退到下游子服。

---

## 安装指南

KyokouAuth **不是零配置即插即用插件**。请务必完成代理转发与下游子服防火墙隔离，切勿让玩家绕过代理直连下游子服端口。

### 1. 选择并配置代理

#### 方案 A：Velocity

1. 将 `KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar` 放入代理的 `plugins/` 目录。
2. 编辑 `velocity.toml`，关闭代理全局正版模式并启用 `modern` 转发：
   ```toml
   online-mode = false
   player-info-forwarding-mode = "modern"
   ```
3. 在下游子服（如 Paper）开启对应的 Velocity modern 转发、配置相同的 `forwarding.secret`，并将子服监听在 `127.0.0.1` 或由防火墙限制为仅允许该代理访问。

#### 方案 B：BungeeCord

1. 将 `KyokouAuth-Bungee-0.1.0-SNAPSHOT.jar` 放入代理的 `plugins/` 目录（请勿与 Velocity 版混装）。
2. 编辑 BungeeCord 的 `config.yml`：
   ```yaml
   online_mode: false
   ip_forward: true
   ```
3. 在下游子服开启 BungeeCord 转发（不可混用 Velocity modern 配置），并禁止公网直连下游子服端口。

### 2. 首次启动与目标子服

启动代理后会自动生成 `plugins/kyokouauth/config.yml` 与 `messages/` 语言文件，默认使用开箱即用的 SQLite 存储。

玩家通过验证后会被送往 `post-login.server` 指定的子服；留空则按代理自身的默认服务器顺序连接。

> **数据库升级与备份提醒**
> 新版插件启动时会自动将身份数据库增量升级至 **schema v5**（历史 v1～v4 迁移保持不变；v5 为 claim grant 增加 `proof_method`；即使未开启基岩、选角或旧档认领功能也会执行增量迁移）。升级前请独立备份数据库文件/实例与配置文件，**不能假定仅换回旧版插件 JAR 即可回滚数据库结构**。

---

## 可选：基岩版接入与双角色选择

基岩版相关开关默认全部关闭，纯 Java 服无需安装 Geyser 或 Floodgate。

### 启用基岩版接入（`bedrock.enabled`）

需要由 **Geyser → 同一代理进程内的 Floodgate → KyokouAuth** 组成受控信任链（KyokouAuth 不会自动安装 Geyser/Floodgate 或复制密钥）：

1. 在运行 KyokouAuth 的**同一个 Velocity 或 BungeeCord 代理**上安装对应平台的 Floodgate（固定适配 **2.2.5 b141**），仅在下游子服安装 Floodgate 无法为代理提供身份校验。
2. 将 Geyser（目标版本 **2.12.1 b1303**）的下游地址指向该代理，保持：
   ```yaml
   java:
     auth-type: floodgate
   advanced:
     bedrock:
       validate-bedrock-login: true
   ```
   并将代理 Floodgate 生成的 `key.pem` 安全配对给 Geyser，严禁泄露密钥。未通过 Xbox 正版校验的基岩连接会在 Geyser 层直接拦截，不会回退到 Java 设备码流程。
3. 在 `plugins/kyokouauth/config.yml` 中开启：
   ```yaml
   bedrock:
     enabled: true
   ```

当仅开启 `bedrock.enabled` 而关闭 `role-selection.enabled` 时，**只允许未关联（unlinked）的 Floodgate 玩家进入**，保留非 Java 前缀（如 `.`），不写入 Java 名字绑定表，也不签发 IP 免验记录。

### 启用关联账号双角色选择（`role-selection.enabled`）

当基岩玩家已在 Geyser Global Linking 完成官方账号关联时，可进一步开启双角色选择（需同时开启 `bedrock.enabled: true`，仅支持官方 Global 关联，不支持本地关联模式）：

```yaml
bedrock:
  enabled: true

role-selection:
  enabled: true
  timeout-seconds: 60
  hint-ttl-seconds: 30
```

核心规则与玩家体验：

- **两套角色完全独立**：
  - **微软角色（U）**：使用本服绑定该 XUID 的稳定 UUID（`UUID(0, XUID)`）与本服名字。修改 Xbox 代号（Gamertag）不会自动改动本服名字。
  - **正版角色（J）**：使用官方 Global 关联指向的 Mojang UUID 与正版名字。
  - 两个角色的 UUID、名字、背包、位置、权限和存档彼此独立，**不会合并或迁移数据**。Java 客户端仍走原有的正版直连或微软设备码单角色流程，不提供跨角色选角菜单。
- **原版箱子选角界面**：同时拥有可用 U 与 J 时，进入三行箱子菜单（原版命名牌代表微软角色，钻石代表正版角色），无需安装额外 Geyser 扩展、自定义资源包，也不开放额外 HTTP 端口。若玩家当前仅有一个可用角色，登录时直接落定进入，不会弹出多余菜单。关闭菜单、超时或关系变更均不会放行。
- **0 或 1 次普通手动回连**：不发送原生 Transfer 数据包，无需在代理开启 `accepts-transfers`，也不自动记住上次所选角色。
  - 若所选角色的 UUID 与名字和当前基岩连接一致：**0 次重连**，直接进入下游子服。
  - 若切换到另一套 UUID 或名字：菜单关闭并提示玩家从服务器列表**手动重连一次**（在 `hint-ttl-seconds` 期限内使用同账号重连即可直接进入，该短期意图仅表达选择倾向，每次重连仍重新校验 Floodgate 与官方关联状态）。
- **皮肤隔离规则**：
  - **微软角色（U）**：仅按该角色稳定 UUID 读取可选插件 SkinsRestorer 中显式设置的皮肤；未安装或未设置时使用默认皮肤，不会按离线名字借用同名正版玩家皮肤。
  - **正版角色（J）**：按 Mojang UUID 使用官方正版皮肤纹理，不会套用微软角色的 SkinsRestorer 皮肤。
  - 角色流程对验证成功的官方公开资料（名字与签名纹理）最多复用 **60 秒元数据缓存**，仅用于减少选角与回连期间的重复查询（允许最长 60 秒的正版改名/换肤同步窗口），**绝不作为账号关联或登录权限缓存**；查询失败时拒绝登录，不会降级为默认皮肤放行。

> **从旧版自定义扩展升级**
> 若此前安装过旧测试版 `KyokouAuth-GeyserAvatar` 或 `KyokouAuth-GeyserTransfer` 扩展及配套配置/资源包，请在停服维护窗口卸载并重启 Geyser（保留 Geyser/Floodgate 本体与 `key.pem`）。旧配置项 `role-selection.transfer-ttl-seconds` 仅作为 `hint-ttl-seconds` 的兼容别名读取；已废弃的 `reconnect-host`、`reconnect-port` 与 `avatars.*` 请从 `config.yml` 中移除。

---

## 可选：旧服 AuthMe 离线档案认领

针对从旧离线模式 AuthMe 服务器迁往 Velocity 的场景，KyokouAuth 提供**连接级双重认领**：旧身份由 AuthMe 只读密码核验或管理员独立人工核验来证明，新身份由玩家当场完成全新微软设备码登录来证明。通过双重确认后，新的微软认证主体（XUID）继承预先登记的历史离线 Profile UUID，而不是在后端搬迁存档。默认不开放认领；推荐把旧服材料复制到固定收件目录，通过代理控制台扫描并明确确认后启用本地快照，**不需要填写迁移配置或重启代理**。复制文件或扫描本身不会开放认领。

### 当前支持矩阵与边界

只认领旧 AuthMe 离线档到 Java 微软角色（U），不支持跨到正版角色（J）。

| 迁移路径 / 平台 | 当前状态 | 说明 |
|---|---|---|
| **Velocity：旧 AuthMe 离线档 → Java 微软角色（U）** | **自动识别/批量登记与自助认领已接线** | 读取旧配置、只读检查来源、匹配真实历史 UUID；一次确认后批量登记，再由玩家完成双重认领 |
| **Floodgate 基岩版（FG）映射 / BungeeCord 代理** | **不支持** | 两者均不提供旧档映射。已认领旧档的 XUID 经原生 Floodgate 不选角入口登录时会被拒绝，不会以默认 UUID 进入另一份存档。 |

- **来源限制**：导入支持冻结的 SQLite 一致性快照及 MySQL/MariaDB 只读来源；正常认领仅读本地迁移快照，不再连接旧库。哈希仅支持 AuthMe 官方默认带盐 `SHA256` 的 `$SHA$` 格式（16 位小写十六进制盐、64 位小写十六进制摘要）。TOTP 字段非空、未知哈希或来源不可用时拒绝，不绕过二次认证；需要管理员独立核验时使用下面保留的人工流程。
- **受控候选记录**：推荐入口固定使用 `authme-inbox` 批次；认领必须同时命中当前本地快照的名字/历史 UUID 允许清单与唯一且 `AVAILABLE` 的候选记录。自动识别旧配置和实际玩家档案，排除缺失、歧义、重复与冲突；不修改源库或世界，不按名字推算 UUID，也不把 AuthMe 可选 UUID 列或历史 IP/免验记录当作真实历史对应关系或归属证明。
- **持久性与兼容性**：认领原子提交后，认证主体为 XUID，持久档案保持导入时的历史离线 v3 UUID；使用 `ka migration disable` 停止新认领，不撤销既有映射；该停用状态重启后仍有效。仅保证原版及严格按 `GameProfile` UUID 索引的数据延续，**不承诺按名字或私有账号表存储的任意第三方插件通用兼容**。
- **验收边界**：构造来源账号、模块测试或受控微软响应不等于真实 AuthMe/Microsoft 账号授权验收；当前没有自愿提供的真实测试号，真人授权尚未验收，项目尚未部署生产。

### 推荐：复制材料后命令扫描，无需迁移配置（Velocity）

1. **先停写旧服，复制同一份一致备份中的必要材料**。插件会准备固定收件目录，按下列结构放入文件，不用复制地形区块、填写目录或逐条抄 UUID：
   ```text
   plugins/kyokouauth/migration/inbox/
   ├─ AuthMe/
   │  ├─ config.yml
   │  └─ <旧配置指定的数据库名>.db   # 仅 SQLite，保留原文件名
   ├─ playerdata/
   │  └─ <历史UUID>.dat             # 旧世界的实际玩家文件
   └─ usercache.json               # 可选辅助记录，来自旧服根目录
   ```
   多个世界可保留为 `worlds/<原世界名>/playerdata/`，避免同名文件互相覆盖。历史名字从 NBT 的 `bukkit.lastKnownName` 或被实际存档支持的 `usercache.json` 关系读取；NBT 内 UUID 必须与文件名一致且为 v3。只有 AuthMe 配置/密码库而缺少可靠历史记录时，系统会列出缺失，不猜 UUID。SQLite 不接受符号链接或旁边残留 `-wal`、`-shm`、`-journal` 的文件；不能把正写入主库的简单复制当成一致快照。
2. **执行后台扫描，检查结果**（仅代理控制台）：
   ```text
   ka migration scan
   ka migration progress
   ka migration issues
   ```
   三种来源都读取复制来的 AuthMe 配置，自动识别数据库、表、账号/密码/TOTP 字段和密码算法，再核对实际结构。**MySQL/MariaDB 直接使用旧配置中的连接账号密码，不需要在 KyokouAuth 再填一遍。** 若希望最小权限，可把收件目录中这份配置的凭据换成专用 `SELECT` 账号，这是可选加固；程序始终只读旧库，不能把只读事务误认为原账号权限已被降级。远程连接仍强制证书/主机名验证，不继承旧配置关闭 TLS 校验的设置。

   每次扫描最多一个任务、一个旧来源连接，查询有界；读取期间按 256 行流式/批次写入私有本地 SQLite 临时快照，最多 100000 行，超限整次失败而非静默截断。源快照导出是单事务，失败或取消会回滚并清理自己创建的临时文件。历史文件也有数量、深度及字节上限。扫描使用独立单线程导入执行器，不占登录任务队列；后台操作仍有磁盘/数据库负载，不代表完全没有性能影响。

   `scan` 只写本地暂存文件，不登记身份、不启用新认领、不修改旧库/世界。导出后立即关闭旧来源；随后匹配、确认及玩家认领都使用本地数据。`issues [页码]` 报告缺失、歧义、重复名、TOTP、未知哈希和目标冲突。操作失败时，控制台和 `progress` 会区分收件配置缺失、旧配置无效、来源读取、预检、确认失效及快照切换等阶段，并给出修复动作；只显示固定安全原因，不回显源配置、数据库凭据、原始 JDBC 异常或确认 token。修正后重试成功会清除旧失败原因。
3. **确认来源已停写且账号库/档案同源，再明确启用本地快照**：
   ```text
   ka migration confirm <扫描给出的token>
   ```
   token 在预检完成后 **5 分钟内一次有效**；重扫替换旧 token。过期会撤销预检并由后台回收未启用的临时快照，`progress` 显示过期状态；清理不触碰当前启用快照或收件原文件。确认会重新核对暂存快照、历史记录和目标状态，**不会重新连接旧数据库**。候选登记分 256 条短事务释放目标库连接/锁，全部处理后才原子切换当前快照指针并开放认领。中途失败不替换已启用快照；若留下尚未启用的候选元数据，重新扫描会幂等识别并续做，不必逐条清理。候选登记本身永远不是身份凭证，当前快照的名字/UUID 允许清单仍是必须的额外边界。

   不需要修改任何 `migration.*` 配置或重启代理。启用状态持久保存在插件的 `migration/` 数据中；重启只加载本地快照，不读取旧配置、不连接旧库。确认成功后可以移走收件目录里的复制材料，**不要删除插件生成的 `snapshots/` 或 `active.properties`**。快照仅保存必要密码哈希与候选信息，不含旧数据库连接密码、明文玩家密码或 TOTP 秘密；这些哈希仍是敏感资料，不要提交仓库或公开分享。POSIX 平台新建私有目录/文件限制为 700/600，Windows 继承插件目录 ACL。

   `ka migration cancel` 可取消扫描或未确认预检并撤销其 token，原启用快照不变；正式确认或停用阶段不允许用该命令中断。认领写库不再占用迁移控制锁，慢写库不会连带卡住扫描提交/取消；快照切换或停用仍须与正在签发的授权互斥。`ka migration disable` 持久停止新认领，不撤销已认领身份。正在连接且未安装私密入口的玩家不会被临时开放认领，必须重新连接。
4. **玩家在当前私有 Limbo 提交旧密码证明**。玩家必须使用**全新的、未被 Mojang 正版且未被本服占用的合法名字**首次连接，以临时 Java U 身份进入私有 Limbo；认领提示阶段（`OFFER`）或选择认领后的等待阶段（`WAITING_OLD`）均可使用：
   ```text
   /ka claim authme <旧名字> <旧密码>
   /ka claim status
   /ka claim cancel
   ```
   同样支持 `/kyokouauth claim ...` 与带命名空间的命令根。旧名字后一个分隔空格之后的剩余字符作为密码原样保留，额外首尾空格属于密码；不要额外加引号。此命令族是连接前置私密入口，**不走普通管理命令分发，也不要求 `kyokouauth.admin`**，不能在已准入后端或其他连接代替当前会话提交。
5. **完成双重确认与原子提交**。旧密码核验通过仅给当前连接绑定短期旧身份授权；玩家第一次确认旧档名和新登录名，再当场完成一次全新的 Microsoft/Xbox 设备码登录，最后第二次确认旧档名、新名字与 Xbox 代号。数据库仍为 **schema v5**（保留 v1～v4 历史迁移），来源证明以 `AUTHME_PASSWORD` 记录 `proof_method` 并写入审计；事务消费授权、绑定 XUID 与历史 UUID 后断开，提示玩家**普通重连一次**。认领中取消、超时、来源失败或冲突均拒绝，不静默退回新建空白档案。

### 单条异常处理与管理员人工核验

手动逐条登记不再是正常批量迁移入口，仅用于能够独立确认历史对应关系的异常处理：

```text
ka migration check <batch> <oldName> <actualHistoricalUuid-v3>
ka migration import <batch> <oldName> <actualHistoricalUuid-v3>
```

`check` 只检查目标库兼容性，不读取旧 AuthMe 或磁盘、不写入或预留；`import` 重新检查并登记。实际历史 UUID 必须来自可信旧服记录，不能按名字或 AuthMe 可选 UUID 字段猜测。单条登记不会扩展当前快照允许清单或补齐密码材料；快照入口的异常应修正收件材料后重扫。

旧的显式 `migration.*` 配置仅保留给人工核验/直接来源入口；正常收件快照流程不需要这些配置，且以持久的快照启停状态为准，停用请用 `ka migration disable`。未启用本地快照、只开启旧 `migration.enabled` 且不启用 AuthMe 来源时，独立人工核验路径仍可使用。玩家进入私有 Limbo 并选择认领进入等待；管理员在独立渠道核实旧账号归属后，在代理控制台执行：

```text
ka migration pending
ka migration approve <session> <recordId> verified
```

`verified` 表示管理员已独立核实并承担审计确认责任，不是“替玩家通过旧密码核验”。授权严格绑定指定等待会话，后续仍须同连接全新微软证明、双重确认和普通重连；TOTP 或不支持的哈希不能通过随意审批来绕过归属核验。

### 私密输入的保护范围

- 启用本地快照（或显式开启旧 `migration.enabled`）后，新连接必须成功安装固定且经过校验的 **Velocity 4.3.1** 前置管线，否则拒绝登录；启用之前已建立且未受保护的连接不会临时获得认领入口。敏感命令族在常规 `CommandExecuteEvent`、Brigadier 错误回显、后端转发及公共补全事件之前消费；常规命令/补全事件 spy 收不到旧密码。只处理 dedicated unsigned 或受支持的 Legacy 敏感命令；带签名/LastSeen 的敏感格式保守断开，不剥离或改写签名。
- 正常迁移扫描在独立单线程导入执行器运行；玩家认领的本地密码核验仍经有界 authIO 调度，单连接最多一个进行中、验证器并发 **4**，账号限流 **5 次/10 分钟**、来源地址 **20 次/10 分钟**。错误统一拒绝，不回显旧密码、哈希或账号是否存在等细节；接管后的可变密码 `CharArray` 在成功、失败、取消及执行拒绝等路径清零，不写入密码/哈希日志。
- **这不是端到端加密或运营者绝对不可见承诺**。该功能不为 offline Minecraft 连接增加传输加密，不防更早的原始包记录器、恶意 root/替换插件，也无法清除客户端本地命令历史或截屏。确认这些边界后再让玩家在目标环境使用旧密码入口。

---

## 常用配置与管理命令

配置文件位于 `plugins/kyokouauth/config.yml`（完整默认注释见 [config.yml](proxy-common/src/main/resources/config.yml)）。除语言文件外，修改 `config.yml` 后均需重启代理生效。

### 关键配置项

- **数据库（`database.*`）**：默认 `sqlite`，可选 `h2`、`mysql`、`mariadb`、`postgresql`。缺失的 JDBC 驱动默认在启动时按需下载至 `plugins/kyokouauth/libraries/`。
- **免验时长（`session.*`）**：`hours: 12` 控制微软角色验证通过后的免验小时数（`0` 为每次必验）；`bind-address: true` 限制仅同 IP 生效（所有玩家共用同一 NAT/隧道出口 IP 时可设为 `false`）。
- **微软角色自动改名（`verification.auto-rename`）**：默认 `true`。同一微软账号换用新的空闲非正版名字完成验证后，原绑定会自动迁移到新名字并释放旧名，角色 UUID 与按 UUID 存储的数据保持不变（按名字存储数据的第三方插件不在迁移范围内）。
- **未验证前禁用命令（`verification.blocked-commands`）**：默认拦截并从客户端命令树隐藏 `server`。支持填写不带 `/` 与参数的命令根（独立别名需一并列出），`[]` 为清空列表，`["*"]` 为禁用全部命令；无论如何配置，未完成验证或选角的玩家都无法切换子服。
- **限流与网络（`premium.*` / `safety.*`）**：名字归属查询与正版会话握手分开限流，超限或上游异常时安全拒绝，绝不降级为免验放行。若宿主机需通过 HTTP 代理访问微软/Mojang 接口，可在代理 JVM 启动参数配置 `-Dhttps.proxyHost` / `-Dhttps.proxyPort`；`premium.directory-network` 可单独控制名字查询走 `inherit`（继承 JVM 设置）或 `direct`（直连）。

### 管理命令

下列常规管理子命令使用 `/kyokouauth`（别名 `/ka`），所需权限节点为 `kyokouauth.admin`。上面的私密 `claim` 命令族走独立的连接前置入口，不属于这些管理子命令。

```text
/kyokouauth whois <名字>      查看名字绑定的 UUID、类型与 Xbox 代号
/kyokouauth session <名字>    查看当前生效的免验记录与剩余时间
/kyokouauth verify <玩家>     向正在验证中的在线玩家重发验证码
/kyokouauth unbind <名字>     解除该名字的账号绑定
/kyokouauth rename <旧> <新>  修改已绑定角色的名字
/kyokouauth untrust <名字>    清除该名字的免验记录
/kyokouauth status            查看存储类型、免验配置、在线验证人数与查询配额
/kyokouauth reload            热重载 messages/ 下的语言文件
```

仅限 **Velocity 代理控制台**，游戏内禁止执行。正常收件命令无需迁移配置；单条/人工入口须已启用认领，或显式开启旧 `migration.enabled`：

```text
ka migration scan                                           从固定 inbox 后台生成未启用本地快照
ka migration progress                                       查看进度与当前快照启用状态
ka migration issues [page]                                  分页查看当前预检异常
ka migration cancel                                         取消扫描或未确认预检
ka migration confirm <token>                                确认停写/同源前提并持久启用本地快照
ka migration disable                                        持久停用新认领，不撤销已认领身份
ka migration check <batch> <oldName> <actualHistoricalUuid-v3>   只读预检，不导入或预留档案
ka migration import <batch> <oldName> <actualHistoricalUuid-v3>  重检并登记一条元数据，返回 recordId
ka migration pending                                        列出当前等待旧档认领的连接会话
ka migration approve <session> <recordId> verified          人工核实旧身份后向指定等待会话授权源档案
```

---

## 兼容性与环境要求

| 组件 | 支持范围与要求 |
|---|---|
| **代理服务端** | Velocity **4.3.1**；BungeeCord 官方构建 **2111**（依赖宿主代理提供的 Netty 4.2，启动时自动校验兼容签名） |
| **Java 运行时** | 插件内置验证引擎字节码最低要求 **Java 21**，**实际运行版本还须满足所用 Velocity / BungeeCord 代理自身的最低 Java 要求**（例如使用要求更高 JDK 的代理版本时以代理为准） |
| **客户端版本** | Minecraft **1.21～26.3 正式版**（协议号 **767～777**，覆盖 1.21.x 全系列及 26.1～26.3 正式版；不含快照与尚未发布的未来版本，下游子服本身也须兼容对应客户端） |
| **可选组件** | Floodgate **2.2.5 b141**（代理端）+ Geyser **2.12.1 b1303**；SkinsRestorer（可选，供微软角色读取显式设置的皮肤） |
| **数据库** | SQLite（默认）、H2、MySQL、MariaDB、PostgreSQL |

---

## 构建与开发

从源码构建完整平台产物需要 **JDK 25** 与 **Python 3.11+**：

```bash
./gradlew build
```

构建完成后，两个平台的独立插件 JAR 分别位于：

- `platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar`
- `platform-bungee/build/libs/KyokouAuth-Bungee-0.1.0-SNAPSHOT.jar`

## 文档导航

- [开发、产物审计与本地验收工具](docs/development.md) —— 模块结构、专项测试开关、外部数据库测试注意事项与 `tools/` 脚本说明
- [项目上下文与领域术语](CONTEXT.md) —— 架构边界、安全不变量与统一领域词汇
- [架构决策记录（ADR）](docs/adr/) —— 核心设计取舍与背景论证（最新代理架构见 [ADR-0018](docs/adr/0018-proxy-owns-the-login.md)）

## 许可证说明

本项目采用 Apache-2.0 许可条款（仓库当前未单独附带根 `LICENSE` 文本文件）；随产物重定位打包的 MCProtocolLib 及相关序列化依赖各自保留原有开源许可证与声明。
