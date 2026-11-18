# 开发、产物审计与本地验收工具

本文面向插件开发者与维护者，说明仓库构建、模块结构及 `tools/` 下本地验证工具的职责与操作风险。服务器安装与配置请看 [README](../README.md)。

## 构建要求与模块结构

构建需要 **JDK 25** 与 **Python 3.11+**。

```bash
./gradlew build
```

- 协议稀疏编解码器在构建期根据固定版本的上游 MCProtocolLib JAR、源码 JAR 与官方注册表生成；R8 9.5.32 仅在构建期做固定 SHA-256 校验与未用成员裁剪，不随插件分发。
- `shadowJar`、`check`、`build` 自动执行产物审计：单个平台完整 JAR 硬上限 **20 MiB**（优化目标 **10 MiB**），并校验 `CUSTOM_NAME`、`LORE`、`PROFILE` 组件闭包、依赖重定位与字节码版本。
- 最终产物输出至 `platform-velocity/build/libs/` 与 `platform-bungee/build/libs/`，每个 JAR 均内置 767～777 全部 11 个协议实现。

### 模块划分

| 模块 | 职责 |
|---|---|
| `core` | 认证决策、身份模型、关系核验、存储与富文本，无代理平台依赖 |
| `proxy-common` | 连接准入控制（`LoginGate`）、设备码会话、限流、配置加载与管理命令 |
| `platform-velocity` | Velocity 事件、Profile、组件与命令适配 |
| `platform-bungee` | BungeeCord 事件、Profile 窄桥接、一次性私有入场票据与命令适配 |
| `limbo`（`limbo/src`） | 多协议验证空间统一入口、生命周期装配与裁剪打包 |
| `limbo-api`（`limbo/api`） | 纯 JDK 的 Limbo 玩家、文本、身份转发与引擎接口 |
| `limbo-runtime`（`limbo/runtime`） | 共享 NBT / math / fastutil / Gson / SLF4J 运行库（Netty 4.2 由宿主代理提供） |
| `limbo-text-{legacy,json,nbt}`（`limbo/text/*`） | 按协议分族共享的三套文本序列化器 |
| `limbo-protocol-767`～`777`（`limbo/protocols/*`） | 各协议生成的稀疏 Codec 与对应官方配置数据 |

## 测试分类与操作风险

常规 `./gradlew test` 全部使用本地受控桩服务，不会向微软或 Mojang 发起真实网络请求。以下专项测试具有外部副作用或环境前提：

- **真实微软设备码握手测试**：显式传入 `-Dkyokou.test.live=true` 才会联网向微软申请真实设备码（仍不含真人浏览器点击授权）：
  ```bash
  ./gradlew -Dkyokou.test.live=true :core:test --tests '*DeviceCodeFlowLiveTest'
  ```
- **外部数据库方言测试**（[ServerDialectTest](../core/src/test/kotlin/moe/ryuu/kyokouauth/core/storage/ServerDialectTest.kt)）：通过环境变量 `KYOKOU_TEST_MYSQL`、`KYOKOU_TEST_MARIADB`、`KYOKOU_TEST_POSTGRESQL`（格式 `host:port/database`）及 `KYOKOU_TEST_USER`、`KYOKOU_TEST_PASSWORD` 启用。**注意：测试启动时会主动清空目标库中的插件表，严禁指向生产或有数据的数据库。**
- **本地隔离目录**：所有 Python 验证脚本的下载缓存、临时服务端、隔离密钥与日志统一写入 `.narrafork/`，不会打包进发行产物，也不读取启动器登录令牌或生产服 `key.pem`。部分脚本默认读取固定 JDK 路径（可通过 `--jdk` 覆盖）或本机 HTTP 代理（如 `127.0.0.1:7890`，支持传 `--http-proxy none` 切换直连的工具以参数为准）。多套启动代理/客户端 JVM 的脚本应串行运行，避免端口冲突与内存峰值叠加。

## `tools/` 工具速查

### 构建与注册表夹具

- [tools/generate-limbo-codec.py](../tools/generate-limbo-codec.py) / [tools/shrink-limbo.py](../tools/shrink-limbo.py)：由 Gradle 构建任务自动调用，负责生成稀疏协议编解码器与执行 R8 裁剪。
- [tools/prepare-limbo.py](../tools/prepare-limbo.py) / [tools/remap-limbo-vanilla.py](../tools/remap-limbo-vanilla.py)：准备固定版本的上游协议包与官方服务端 JAR，并对 767～774 应用官方混淆映射生成仅用于离线校验的命名 JAR。
- [tools/extract-limbo-fixtures.py](../tools/extract-limbo-fixtures.py) / [tools/extract-role-selector-fixtures.py](../tools/extract-role-selector-fixtures.py) / [tools/prepare-limbo-oracle.py](../tools/prepare-limbo-oracle.py)：从固定官方服务端提取配置期数据包、选角菜单物品 ID 与协议校验夹具。
- [tools/audit-limbo-artifact.py](../tools/audit-limbo-artifact.py)：独立检查最终插件 JAR 的体积上限、重定位包名、ServiceLoader、夹具哈希与字节码目标：
  ```bash
  python tools/audit-limbo-artifact.py platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar
  ```

### 协议验包与端到端隔离回归

- [tools/verify-limbo.py](../tools/verify-limbo.py) / [tools/verify-limbo-matrix.py](../tools/verify-limbo-matrix.py) / [tools/limbo-assembly-smoke.py](../tools/limbo-assembly-smoke.py)：导出插件实际发出的协议包，交由对应版本的官方服务端 Codec 解码核验注册表、区块、光照、传送与富文本：
  ```bash
  python tools/verify-limbo-matrix.py --jar platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar --workers 1
  python tools/verify-limbo-matrix.py --jar platform-bungee/build/libs/KyokouAuth-Bungee-0.1.0-SNAPSHOT.jar --workers 1
  ```
- [tools/prepare-limbo-client.py](../tools/prepare-limbo-client.py) / [tools/limbo-e2e.py](../tools/limbo-e2e.py) / [tools/velocity-e2e.py](../tools/velocity-e2e.py) / [tools/bungee-e2e.py](../tools/bungee-e2e.py)：启动受控隔离代理与无账号原版客户端（只读复用本机 `.minecraft` 公开资源文件），通过只读 JDK Observer 检查客户端世界加载、界面状态与留存连接：
  ```bash
  python tools/velocity-e2e.py --scenario held --version 1.21.1
  python tools/bungee-e2e.py network --scenario held --client --version 1.21.1 --observer
  ```
- [tools/floodgate-e2e.py](../tools/floodgate-e2e.py) / [tools/role-selection-e2e.py](../tools/role-selection-e2e.py) / [tools/geyser-gui-smoke.py](../tools/geyser-gui-smoke.py) / [tools/skinsrestorer-e2e.py](../tools/skinsrestorer-e2e.py)：分别在隔离环境验证官方 Floodgate 2.2.5 加密握手链、基岩单/双角色选择与 0/1 次普通回连计数、Geyser `ItemTranslator` 三行箱子菜单翻译，以及 SkinsRestorer 15.12.6 与微软/基岩角色的纹理边界。

## 验证边界说明

各工具只证明其所在层面的正确性，互不替代：

1. **单元测试与官方 Codec 验包**：证明状态机规则与数据包可被官方协议解码，不代表真实客户端已完成画面渲染。
2. **受控隔离端到端脚本**：证明真实代理事件链、Floodgate 加密器与转发票据行为，其中的微软令牌、官方关联与后端存档为受控桩数据。
3. **实机人工验收**：基岩客户端与手机端 GUI 显示效果、真实微软/Xbox 网页授权、手动回连体验及下游子服玩家存档隔离，仍需在目标环境实机确认。
