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
| Mojang 拥有 | 代理对该连接强制 Mojang 会话验证，正版客户端自动应答，无需设备码 |
| 其它 | 进验证大厅，用微软设备码绑定后放行 |

客户端不需要安装任何模组。

验证大厅使用各协议固定版本的 MCProtocolLib 提供编解码与网络传输；767～777 的 11 个协议实现分别重定位，但不重复打包整套依赖：Netty 4.2 由所用代理提供，NBT/fastutil 等普通库共享一份，文本库按三种格式族共享。构建期从上游完整注册表生成明确 ID 的稀疏 Codec，再仅对 Limbo 图删除未用成员；不手写报文字段，也不运行时下载协议库。各 Engine 使用自己的官方注册表和私有 loopback 端口，代理按客户端协议选择；无需另装协议转换插件。Velocity 使用 modern forwarding HMAC，Bungee 使用绑定当前连接、协议和目标的短时一次性私有票据，绝不信任客户端自报 UUID。两个平台共享认证与验证会话；验证失败或大厅不可用都不会回退真实后端。

标题在验证码准备好后显示一次、持续至当前验证期限，并在结束或重新申请前清理，不再每三秒重启动画。聊天保留完整可点击链接。

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

### 网络与限流

名字查询和正版会话验证使用不同预算：同名查询缓存并合并并发请求；正版会话逐连接校验，同一 IP（IPv6 按 /64）和名字仅允许一个在途握手，整个代理默认每分钟最多启动 60 次（`premium.session-*`）。未完成、断开或超时后默认退避 30 → 60 → 120 秒，拒绝的重试不延长截止时间；真实握手成功清零失败次数。客户端声明的 UUID 只用于提前拒绝明显不匹配的名字，不能作为放行凭证，也不会跨连接复用握手结果。上游或共享出口仍可能返回 429，限流时不降级为离线认证。

名字归属查询优先使用官方 Minecraft Services 接口，以旧 Mojang 接口作为有限备用。每个来源独立遵从 `Retry-After`；一个来源限流不会暂停健康来源，每次实际 HTTP 请求（包括备用请求）都会计入预算。只有所有来源限流才进入整体退避；错误页、空 404、超时或无法验证的资料不会被当成名字可用。`premium.directory-network` 可设 `inherit`（默认，继承 JVM 出站设置）或 `direct`（单独 Java 直连客户端，不使用 JVM HTTP 代理）；只影响名字查询，不影响正版会话或微软请求，也不改变 OS/TUN 路由。

微软设备码申请对瞬时网络失败最多尝试 3 次，等待期间显示进度；429 遵从 `Retry-After`。日志只记录安全错误类别、HTTP 状态与耗时，不记录验证码或令牌。若部署环境需要 HTTP 出站代理，可在 JVM 启动参数设置 `-Dhttps.proxyHost` / `-Dhttps.proxyPort`，插件默认不强制任何本机代理地址。

### 微软身份自动改名（可选）

默认关闭；在插件 `config.yml` 开启并重启代理：

```yaml
verification:
  auto-rename: true
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

产物分别在 `platform-velocity/build/libs/` 与 `platform-bungee/build/libs/`。每个平台包含全部11个协议；当前验收产物 Velocity **9,637,242B（9.19MiB）**、Bungee **9,648,765B（9.20MiB）**，不外置下载协议依赖，也不打包 Mojang 游戏 JAR。模块：

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
