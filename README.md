# KyokouAuth

**让正版玩家直接进服，让其他 Java 玩家用微软账号验证。**

适用于 Velocity 和 BungeeCord 的离线模式代理。只装在代理端，无需客户端模组或独立登录服。

[安装](#安装指南)　[基岩版](#可选基岩版接入与双角色选择)　[验证大厅](#自定义-limbo-地图与计分板)　[AuthMe 迁移](#旧服-authme-离线档案认领)　[管理命令](#常用管理命令)　[常见问题](#常见问题)

## 登录流程概览

| 客户端与入口 | 游戏名状态 / 账号归属 | 登录行为 |
|---|---|---|
| **Java 版** | 已在 Mojang 注册的正版名 | 自动走正版会话握手直连，冒名离线连接直接拒绝 |
| **Java 版** | 未被正版占用的名字 | 进入内置 Limbo 验证空间，在聊天栏打开微软设备码链接绑定 Xbox 身份（XUID）后放行 |
| **基岩版**（可选） | 未关联账号（仅启用 `bedrock`） | 校验同代理 Floodgate 身份，保留隔离前缀（如 `.`）直接进入，无需设备码 |
| **基岩版**（可选） | 官方 Global 关联账号（同时启用 `role-selection`） | 有两个可用角色时弹出原版箱子菜单；只有一个可用角色时直接进入 |

首次完成微软绑定后，按提示从服务器列表重连一次，新连接会使用绑定后的身份。已有绑定的玩家按[免验设置](#常用配置)决定是否要重新验证。正版名只接受正版会话验证，不会降级到设备码登录。

## 安装指南

KyokouAuth 必须配合代理转发和下游子服防火墙隔离使用，**严禁玩家绕过代理直连子服端口**。

### 1. 选择并配置代理

#### 方案 A：Velocity

1. 将 `KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar` 放入代理 `plugins/` 目录。
2. 编辑 `velocity.toml`，关闭代理全局正版模式并启用 `modern` 转发：
   ```toml
   online-mode = false
   player-info-forwarding-mode = "modern"
   ```
3. 在下游子服（如 Paper）启用 Velocity modern 转发并配置相同的 `forwarding.secret`，将子服绑定到 `127.0.0.1` 或通过防火墙限制仅允许代理访问。

#### 方案 B：BungeeCord

1. 将 `KyokouAuth-Bungee-0.1.0-SNAPSHOT.jar` 放入代理 `plugins/` 目录（切勿与 Velocity 版混用）。
2. 编辑 BungeeCord 的 `config.yml`：
   ```yaml
   online_mode: false
   ip_forward: true
   ```
3. 在下游子服启用 BungeeCord 转发（不可与 Velocity modern 混用），并通过防火墙禁止公网直连子服端口。

### 2. 首次启动与目标子服

首次启动后，插件会在自己的数据目录生成 `config.yml` 和 `messages/`。Velocity 通常使用 `plugins/kyokouauth/`，BungeeCord 使用 `plugins/KyokouAuth/`。下文的相对路径都以这个数据目录为准。默认用本地 SQLite，不需要另建数据库。

如果希望玩家**完成 Limbo 验证后**统一进入大厅，在插件的 `config.yml` 中设置子服在代理里注册的名称：

```yaml
post-login:
  server: "lobby"
```

留空则使用代理的默认服务器顺序。能够直接登录、不经过 Limbo 的玩家仍走代理的首服路由。

首次开放前，建议用一个正版名和一个未注册的 Java 名分别登录，确认正版能直进、待验证的玩家不能切服。也要从代理以外的网络确认无法直连后端端口。升级前请备份数据库和配置；只换回旧 JAR 不会回滚数据库结构。

## 可选：基岩版接入与双角色选择

基岩版配置默认全部关闭，纯 Java 服无需配置。

### 基础基岩接入（`bedrock.enabled`）

需要由 **Geyser → 同一代理进程内的 Floodgate → KyokouAuth** 组成信任链：

1. 在运行 KyokouAuth 的**同一个代理**上安装对应平台的 Floodgate（适配 2.2.5 b141），下游子服无需安装。
2. 将 Geyser（目标版本 2.12.1 b1303）指向该代理，配对代理上 Floodgate 生成的 `key.pem`。在 **Geyser 的配置文件**中设置：
   ```yaml
   java:
     auth-type: floodgate
   advanced:
     bedrock:
       validate-bedrock-login: true
   ```
   请勿公开或提交 `key.pem`。
3. 在 **KyokouAuth 的配置文件**中开启：
   ```yaml
   bedrock:
     enabled: true
   ```

仅开启 `bedrock.enabled` 时，只允许未关联的 Floodgate 玩家进入，保留前缀（如 `.`），不占用 Java 名字表。

### 官方关联账号双角色选择（`role-selection.enabled`）

当基岩玩家在 Geyser 官方 Global Linking 关联了 Java 正版账号时，可开启双角色选择：

```yaml
bedrock:
  enabled: true

role-selection:
  enabled: true
  timeout-seconds: 60
  hint-ttl-seconds: 30
```

- **两套角色完全独立**：微软角色（U）使用本服绑定的稳定 UUID；正版角色（J）使用 Mojang 官方 UUID。两个角色的背包、权限、存档互不干扰，不合并数据。
- **原版箱子菜单**：命名牌代表微软角色，钻石代表正版角色。关闭菜单不算选择，超时也不会放行。只支持官方 Global 关联；仅在本服做的本地关联不能用于选角。
- **重连机制**：所选角色与当前连接的 UUID 和名字一致时直接进入；否则按提示在 `hint-ttl-seconds` 内用同一个基岩账号从服务器列表重连一次。默认等待 30 秒，不需要 Transfer 扩展或额外端口。
- **皮肤**：正版角色使用官方皮肤；微软角色可使用 SkinsRestorer 中为该角色设置的皮肤，未设置时使用默认皮肤。

## 自定义 Limbo 地图与计分板

将 WorldEdit 导出的 Sponge v2/v3 `.schem` 放到插件数据目录（与 `config.yml` 同级）。地图和计分板可以分别启用：只改出生点就填 `spawn`，只加计分板就开启 `sidebar.enabled`。

在 `config.yml` 的 `limbo:` 节点配置地图位置与固定出生点坐标（x/y/z/yaw/pitch），固定玩家每次进入验证空间的位置：

```yaml
limbo:
  motd: "<#55DDF7>KyokouAuth</#55DDF7>"
  view-distance: 2
  spawn:
    x: 0.5
    y: 64.0
    z: 0.5
    yaw: 0.0
    pitch: 0.0
  map:
    file: "lobby.schem"
    x: -16
    y: 63
    z: -16
    unknown-blocks-as-air: false
  sidebar:
    enabled: true
    title: "<#55DDF7>登录验证</#55DDF7>"
    lines:
      - "<gray>请按聊天栏提示完成验证</gray>"
      - "<#FFCC66>验证后即可进入服务器</#FFCC66>"
```

- **地图**：`map.x/y/z` 填 WorldEdit 选区的最小角，地图须完整落在 `view-distance` 覆盖的区块内。只加载静态方块（最多 1,048,576 格），不支持旧版 `.schematic`。`unknown-blocks-as-air: true` 可将缺失方块显示为空气，`file: ""` 保持空地图。
- **出生点**：在制图世界站到想要的位置，用 F3 记录坐标和朝向。`yaw` 控制左右朝向，`pitch` 为负时抬头、为正时低头。每次进入 Limbo 都用这组坐标和朝向。
- **计分板**：仅在 Limbo 显示，最多 15 行静态文字。标题、行和 Limbo MOTD 支持 `<#RRGGBB>`。修改后重启代理。

## 常用配置

首次生成时，简体中文 JVM 环境使用[简体中文注释](proxy-common/src/main/resources/config.zh_CN.yml)，其他语言使用[英文注释](proxy-common/src/main/resources/config.yml)。两份模板的配置键和默认值相同；`messages.locale` 只控制玩家提示的默认语言。下面是开服后最常调整的几项：

| 想调整什么 | 配置项 | 默认行为 |
|---|---|---|
| 微软账号多久重验 | `session.hours`、`session.bind-address` | 验证后 12 小时内同 IP 免验；`hours: 0` 表示每次验证。若代理只能看到多个玩家共用的出口 IP，又希望强制验证，应设为 `0`。 |
| 允许玩家更换登录名 | `verification.auto-rename` | 同一个微软账号重新验证后，可以绑定到空闲的非正版名；UUID 不变，按 UUID 存储的资料继续可用。 |
| 设备码登录超时 | `verification.timeout-seconds` | 默认 300 秒；超时后玩家需重新连接。 |
| 验证期间隐藏、拦截命令 | `verification.blocked-commands` | 默认拦截 `server`。填写不带 `/` 和参数的命令名；`[]` 清空列表，`["*"]` 拦截全部。清空也不会让未验证玩家切服。 |
| 更换存储 | `database.type` | 默认 SQLite。使用 MySQL、MariaDB 等服务型数据库时，另填地址、库名和凭据。 |
| 修改玩家提示 | `messages.locale`、`messages/` | 优先匹配玩家客户端语言，找不到时使用配置的默认语言。内置简中和英文，支持 `<#RRGGBB>`、`<newline>` 等标记。 |

### 升级时保留原配置

无需删除 `config.yml` 或语言文件。升级后首次启动会补齐缺失字段，不覆盖已有值、注释和自定义文案。实际修改前，会为对应文件保存同目录的 `.pre-upgrade.bak`。自建语言没有同名内置模板时，缺失文案由内置语言兜底。

修改 `config.yml` 后重启代理；只改 `messages/` 时可执行 `/ka reload`，不需要重启。

## 旧服 AuthMe 离线档案认领

**Velocity 和 BungeeCord 均支持 AuthMe 旧档认领。** 玩家证明旧账号归自己所有，再完成新的微软验证，即可让新 Java 微软角色沿用旧存档 UUID。基岩版和 Java 正版角色不参与认领。

认领不搬运世界或存档文件。新子服仍需由服主恢复旧存档；原版及按 UUID 识别玩家的插件才能沿用原数据，按名字或私有账号表保存的插件需单独核对。旧密码自助认领支持 AuthMe 默认带盐 SHA256（`$SHA$` 格式），不适用于启用了 TOTP 或使用其他哈希格式的账号。

<a name="推荐复制材料后命令扫描无需迁移配置velocity"></a>

### 迁移步骤

默认不开启认领。按下面步骤导入即可，**无需修改 `migration.*` 配置或重启代理**。

1. **停写、备份、复制。** 使用同一份一致备份中的 AuthMe 账号库和玩家档案。将下列材料放进插件数据目录：
   ```text
   migration/inbox/
   ├─ AuthMe/
   │  ├─ config.yml
   │  └─ <数据库名>.db          # 仅 SQLite，保留旧配置指定的文件名
   ├─ playerdata/
   │  └─ <历史UUID>.dat         # 旧世界的实际玩家存档，不是空文件
   └─ usercache.json            # 可选，取自同一旧服，不能代替 playerdata
   ```
   多个世界可分别放在 `worlds/<原世界名>/playerdata/` 下，避免互相覆盖。SQLite 必须是一致性快照，不要直接复制正在写入的数据库。
2. **选择来源并扫描。** 旧 AuthMe 配置中的 `DataSource.backend` 必须明确为 SQLite、MySQL 或 MariaDB。插件不会按目录里的 `.db` 猜来源。MySQL/MariaDB 使用复制来的配置，不用重填凭据；远程库须通过 TLS 校验。按实际来源选择一条命令：
   ```text
   ka migration scan
   ka migration scan --local-source
   ka migration scan --source-host old-db.example --source-port 3306
   ```
   第一条适用于 SQLite 或已配置远程地址的数据库。第二条只用于确认数据库与当前代理处于同一运行环境；旧机配置里的 `localhost` 不会自动指向旧机。第三条仅覆盖本次连接地址。用 `ka migration progress` 查看进度，`ka migration issues [页码]` 检查缺失档案、重名和冲突。修正材料后可重新扫描；扫描本身不开放认领。
3. **确认来源并启用。** 确认旧库已停写、账号与档案确实同源后，在 5 分钟内输入 `ka migration confirm <token>`。启用后，认领和重启都只使用本地快照，不再连接旧库。可移走收件材料，但要保留并保护插件生成的 `migration/` 快照；其中含密码哈希，不要公开分享。
4. **玩家本人认领。** 用一个从未绑定且未被正版占用的 Java 名字进入 Limbo，按提示提交旧身份：
   ```text
   /ka claim authme <旧名字> <旧密码>
   ```
   先确认旧档和新名字，再完成一次新的微软设备码验证，最后确认绑定并重连一次。用 `/ka claim status` 查看状态，或用 `/ka claim cancel` 取消。只在 Limbo 的认领入口输入旧密码；不要发到普通聊天或交给管理员。私密命令不会为离线模式连接增加加密，也无法替玩家清除客户端命令历史。

### 暂停认领与处理异常

`ka migration cancel` 取消扫描或未确认的预检，不影响已启用的快照。`ka migration disable` 持久停止新认领，**不会撤销已认领角色的 UUID**。

缺少可信旧档、出现多重对应或哈希不受支持时，不要按名字推算 UUID，也不要用审批代替所有权核验。确需人工处理的可信个案，可在控制台用 `migration check` / `migration import` 检查并登记，在独立核实账号归属后再用 `migration pending` / `migration approve` 授权当前等待会话。玩家仍须完成自己的微软验证与确认。

## 常用管理命令

常规管理命令以 `/kyokouauth`（别名 `/ka`）执行，需要 `kyokouauth.admin` 权限。

```text
/kyokouauth whois <名字>       查看名字绑定的 UUID、类型与 Xbox 代号
/kyokouauth session <名字>     查看当前生效的免验记录与剩余时间
/kyokouauth verify <玩家>      向正在验证中的在线玩家重发验证码
/kyokouauth unbind <名字>      解除该名字的账号绑定
/kyokouauth rename <旧名> <新名> 修改已绑定角色的名字
/kyokouauth untrust <名字>     清除该名字的免验记录
/kyokouauth status             查看存储类型、免验配置与运行状态
/kyokouauth reload             热重载 messages/ 语言文件并补齐词条
```

以下迁移管理子命令仅限 **代理控制台**执行：

```text
ka migration scan [--source-host <host>] [--source-port <port>] [--local-source]  扫描收件箱材料
ka migration progress                                       查看迁移进度与快照状态
ka migration issues [page]                                  分页查看预检异常
ka migration cancel                                         取消扫描或未确认的预检
ka migration confirm <token>                                确认预检并正式启用快照
ka migration disable                                        停用新认领（保留已有绑定）
ka migration check <batch> <旧名> <实际历史UUID>               只读检查单条旧档
ka migration import <batch> <旧名> <实际历史UUID>              登记核实过的单条旧档
ka migration pending                                        查看等待人工核验的会话
ka migration approve <session> <recordId> verified          为已独立核验的会话授权
```

## 常见问题

**验证通过却没进大厅。** 先看客户端是否提示重连；首次绑定或认领需要按提示重连一次。若验证后仍进不了子服，检查 `post-login.server` 是否为代理已注册的名称，以及代理能否连到该子服。不要开放子服公网端口来绕过验证。

**一直收不到微软设备码。** 先看对应的断开或等待提示，再检查代理到微软服务的网络；若提示 Mojang 名字查询失败，检查代理到 Mojang 的网络。`premium.directory-network` 只控制正版名字查询走 JVM 代理还是直连，不影响微软验证。

**放了地图却启动失败。** 确认 `.schem` 和插件配置同级、文件名正确，地图整体位于出生点附近的可视区块内。如果错误指出某个版本缺少方块，可在 WorldEdit 中换成兼容方块，或启用 `unknown-blocks-as-air` 后检查替换效果。

**扫描到了账号，为什么不能认领？** 扫描成功不等于确认启用。先用 `ka migration progress` 查看状态，用 `ka migration issues` 检查档案冲突。已在旧连接里的玩家先重连，再用一个空闲且未被正版占用的 Java 名字进入 Limbo。

## 兼容性与环境要求

| 组件 | 支持范围与要求 |
|---|---|
| **代理服务端** | Velocity **4.3.1**；满足运行时接口检查的 BungeeCord 系代理（已实测官方 **2110、2111**，依赖 Netty 4.2） |
| **Java 运行时** | 插件最低要求 **Java 21**（实际需满足所用代理服务端的运行环境要求） |
| **客户端版本** | Minecraft **1.21～26.3 正式版**（协议号 767～777，同时受所用代理支持范围限制） |
| **可选组件** | Floodgate 2.2.5 b141 + Geyser 2.12.1 b1303；SkinsRestorer（供微软角色显示皮肤） |
| **数据库** | SQLite（默认开箱即用）、H2、MySQL、MariaDB、PostgreSQL |

BungeeCord 版不按构建号限制运行，而是检查必要接口并保留连接级安全校验。Waterfall、XCord 等分支尚未专项验收；通过启动检查不代表所有功能均已验证。

## 构建与开发

从源码构建产物需要 **JDK 25** 与 **Python 3.11+**：

```bash
./gradlew build
```

构建生成的插件产物：
- Velocity 版：`platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar`
- BungeeCord 版：`platform-bungee/build/libs/KyokouAuth-Bungee-0.1.0-SNAPSHOT.jar`

## 开发与参考文档

- [开发、产物审计与本地验收工具](docs/development.md) —— 模块结构、测试开关与开发脚本说明
- [项目上下文与领域术语](CONTEXT.md) —— 架构设计、安全边界与核心词汇
- [架构决策记录（ADR）](docs/adr/) —— 核心设计取舍论证（代理接管登录见 [ADR-0018](docs/adr/0018-proxy-owns-the-login.md)）

## 许可证说明

本项目采用 Apache-2.0 许可条款。依赖项各自保留原有开源许可证与声明。
