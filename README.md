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
> 新版插件启动时会自动将身份数据库增量升级至 **schema v3**（即使未开启基岩或选角功能也会执行迁移）。升级前请独立备份数据库文件/实例与配置文件，**不能假定仅换回旧版插件 JAR 即可回滚数据库结构**。

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

## 常用配置与管理命令

配置文件位于 `plugins/kyokouauth/config.yml`（完整默认注释见 [config.yml](proxy-common/src/main/resources/config.yml)）。除语言文件外，修改 `config.yml` 后均需重启代理生效。

### 关键配置项

- **数据库（`database.*`）**：默认 `sqlite`，可选 `h2`、`mysql`、`mariadb`、`postgresql`。缺失的 JDBC 驱动默认在启动时按需下载至 `plugins/kyokouauth/libraries/`。
- **免验时长（`session.*`）**：`hours: 12` 控制微软角色验证通过后的免验小时数（`0` 为每次必验）；`bind-address: true` 限制仅同 IP 生效（所有玩家共用同一 NAT/隧道出口 IP 时可设为 `false`）。
- **微软角色自动改名（`verification.auto-rename`）**：默认 `true`。同一微软账号换用新的空闲非正版名字完成验证后，原绑定会自动迁移到新名字并释放旧名，角色 UUID 与按 UUID 存储的数据保持不变（按名字存储数据的第三方插件不在迁移范围内）。
- **未验证前禁用命令（`verification.blocked-commands`）**：默认拦截并从客户端命令树隐藏 `server`。支持填写不带 `/` 与参数的命令根（独立别名需一并列出），`[]` 为清空列表，`["*"]` 为禁用全部命令；无论如何配置，未完成验证或选角的玩家都无法切换子服。
- **限流与网络（`premium.*` / `safety.*`）**：名字归属查询与正版会话握手分开限流，超限或上游异常时安全拒绝，绝不降级为免验放行。若宿主机需通过 HTTP 代理访问微软/Mojang 接口，可在代理 JVM 启动参数配置 `-Dhttps.proxyHost` / `-Dhttps.proxyPort`；`premium.directory-network` 可单独控制名字查询走 `inherit`（继承 JVM 设置）或 `direct`（直连）。

### 管理命令

主命令 `/kyokouauth`（别名 `/ka`），所需权限节点：`kyokouauth.admin`。

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
