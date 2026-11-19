# KyokouAuth

**让正版玩家直接进服，让其他 Java 玩家用微软账号验证。**

适用于 Velocity 和 BungeeCord 的离线模式代理。只装在代理端，无需客户端模组或独立登录服。

[安装](#安装指南)　[基岩版](#可选基岩版接入与双角色选择)　[验证大厅](#自定义-limbo-地图与计分板)　[AuthMe 迁移](#旧服-authme-离线档案认领)　[管理命令](#常用管理命令)

## 登录流程概览

| 客户端与入口 | 游戏名状态 / 账号归属 | 登录行为 |
|---|---|---|
| **Java 版** | 已在 Mojang 注册的正版名 | 自动走正版会话握手直连，冒名离线连接直接拒绝 |
| **Java 版** | 未被正版占用的名字 | 进入内置 Limbo 验证空间，在聊天栏打开微软设备码链接绑定 Xbox 身份（XUID）后放行 |
| **基岩版**（可选） | 未关联账号（仅启用 `bedrock`） | 校验同代理 Floodgate 身份，保留隔离前缀（如 `.`）直接进入，无需设备码 |
| **基岩版**（可选） | 官方 Global 关联账号（同时启用 `role-selection`） | 弹出原版箱子菜单，在微软角色与正版角色间独立选择进入 |

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

启动代理后会自动生成 `plugins/kyokouauth/config.yml` 与 `messages/` 语言文件，默认使用开箱即用的 SQLite 存储。

玩家通过验证后会被送往 `post-login.server` 指定的子服；留空则按代理自身的默认服务器顺序连接。

升级前请备份数据库和配置。仅换回旧版 JAR 不会回滚数据库结构。

## 可选：基岩版接入与双角色选择

基岩版配置默认全部关闭，纯 Java 服无需配置。

### 基础基岩接入（`bedrock.enabled`）

需要由 **Geyser → 同一代理进程内的 Floodgate → KyokouAuth** 组成信任链：

1. 在运行 KyokouAuth 的**同一个代理**上安装对应平台的 Floodgate（适配 2.2.5 b141），下游子服无需安装。
2. 将 Geyser（目标版本 2.12.1 b1303）指向该代理，配置 `auth-type: floodgate` 并开启 `validate-bedrock-login: true`，配对代理 Floodgate 的 `key.pem`。
3. 在 `plugins/kyokouauth/config.yml` 中开启：
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
- **原版箱子菜单**：同时具备两种可用角色时弹出原版菜单（命名牌代表微软角色，钻石代表正版角色）。
- **重连机制**：若所选角色与当前基岩连接的 UUID 和名字一致则直接进入（0 次重连）；若切换到另一套角色，系统会提示玩家从服务器列表手动重连一次即可进入（无需额外传输插件或端口）。

## 自定义 Limbo 地图与计分板

将 WorldEdit 导出的 Sponge v2/v3 `.schem` 放置在 `plugins/kyokouauth/` 目录下（与 `config.yml` 同级）。

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

## 配置与语言升级

插件在升级后启动时，会自动补齐 `config.yml` 中缺失的新配置项，并补齐 `messages/` 内置语言文件缺失的键位。
- 补齐前会自动在同目录下保存 `.pre-upgrade.bak` 备份文件。
- 原有配置值、注释以及服主自定义的语言翻译不会被覆盖。
- 也可以通过 `/ka reload` 命令热重载语言文件并自动补齐内置词条。

## 旧服 AuthMe 离线档案认领

**Velocity 和 BungeeCord 均支持 AuthMe 旧档认领。** 玩家可将旧档认领到新的 Java 微软角色，不能认领到基岩版或 Java 正版角色。认领后沿用旧存档 UUID，但不会自动把存档文件搬到新子服。

自助认领支持 AuthMe 默认带盐 SHA256（`$SHA$` 格式）；启用了 TOTP 的账号不能走旧密码自助认领。

<a name="推荐复制材料后命令扫描无需迁移配置velocity"></a>

### 迁移步骤

1. **备份旧服并准备文件**：停止旧服写入，将所需文件复制到代理的 `plugins/kyokouauth/migration/inbox/` 目录：
   ```text
   plugins/kyokouauth/migration/inbox/
   ├─ AuthMe/
   │  ├─ config.yml
   │  └─ <数据库名>.db          # 仅 SQLite，如使用 MySQL/MariaDB 则无需复制 .db
   ├─ playerdata/
   │  └─ <历史UUID>.dat         # 旧世界玩家存档文件
   └─ usercache.json            # 可选辅助记录
   ```
   若旧服用 MySQL/MariaDB，无需重复填写连接凭据。数据库和当前代理在同一运行环境时，用 `--local-source` 明确确认；旧配置仍指向旧机的 `localhost` 时，改用 `--source-host`（需要时加 `--source-port`）指定实际地址。
2. **控制台后台扫描**：在代理控制台执行：
   ```text
   ka migration scan
   ka migration progress
   ka migration issues
   ```
3. **确认并启用本地快照**：检查异常无误后，使用扫描给出的 token 启用快照（5 分钟内有效）：
   ```text
   ka migration confirm <token>
   ```
   启用后认领只读本地快照，不再依赖旧数据库。如需停止新认领，可执行 `ka migration disable`。
4. **玩家在 Limbo 验证旧密码**：玩家以**未被占用的全新名字**进入验证空间，执行私密命令：
   ```text
   /ka claim authme <旧名字> <旧密码>
   ```
5. **双重确认与绑定**：旧密码核验通过后，玩家当场完成一次微软设备码登录以证明新身份，经确认后系统原子提交绑定，断开后重新连接即可继承历史离线档案。

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
ka migration pending                                        查看等待人工核验的会话
ka migration approve <session> <recordId> verified          人工核验通过授权
```

## 兼容性与环境要求

| 组件 | 支持范围与要求 |
|---|---|
| **代理服务端** | Velocity **4.3.1**；BungeeCord 官方构建 **2111**（依赖 Netty 4.2） |
| **Java 运行时** | 插件最低要求 **Java 21**（实际需满足所用代理服务端的运行环境要求） |
| **客户端版本** | Minecraft **1.21～26.3 正式版**（协议号 767～777） |
| **可选组件** | Floodgate 2.2.5 b141 + Geyser 2.12.1 b1303；SkinsRestorer（供微软角色显示皮肤） |
| **数据库** | SQLite（默认开箱即用）、H2、MySQL、MariaDB、PostgreSQL |

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
