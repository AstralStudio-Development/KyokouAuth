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
| `core` | 认证决策、身份模型、关系核验、独立 AuthMe 只读验证、受控旧档认领存储（schema v5，保留 v1～v4 不变；v5 为 claim grant 增加 `proof_method`）与富文本，无代理平台依赖 |
| `proxy-common` | 连接准入控制（`LoginGate`）、设备码会话、连接级旧档认领流程（`ProfileClaimFlow`）、限流、配置加载与管理命令 |
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

### AuthMe 只读来源与私密认领接线

只认领旧 AuthMe 离线档到 Java 微软角色（U），不支持跨到正版角色（J）。启用步骤及完整配置以 [README 收件快照步骤](../README.md#推荐复制材料后命令扫描无需迁移配置velocity) 与 [默认 config.yml](../proxy-common/src/main/resources/config.yml) 为准。

- `AuthService` 已将 `core` AuthMe 只读验证器接入当前私有 Limbo 的 `/ka claim authme <旧名字> <旧密码>`；`/kyokouauth` 与命名空间形式同样受保护，另有 `claim status` / `claim cancel`。只接受新且空闲非正版名的临时 Java U，在 `OFFER` / `WAITING_OLD` 可提交，不能使用普通管理 dispatcher 或借助管理权限代交。
- 正常迁移无需任何 `migration.*` 配置：把 `AuthMe/config.yml`、SQLite 文件（如适用）与实际 `playerdata` 复制到 `migration/inbox/`，`migration scan` 用独立单线程读取一次来源并写未启用本地快照；`progress/cancel` 查看/取消，`issues [page]` 分页报告，一次性 `confirm <token>` 在预检完成起 5 分钟内核对本地快照/历史记录/目标并持久启用。确认即管理员对停写、同源备份的明确确认，不会自动锁库。候选按 256 条短事务登记，全部完成后才原子切换快照；失败不替换旧启用状态，已写候选可重扫幂等续做。正常批次固定 `authme-inbox`，且必须命中当前快照名字/UUID 允许清单及唯一 `AVAILABLE` 记录；单条手动登记不能绕过允许清单。
- `AuthMeLegacyVerifier.exportSnapshot` 单旧源连接、有界流式读取、256 行批写、单 SQLite 导出事务、取消/超限清理及 LOWER 名字索引。MySQL/MariaDB 直接读取复制配置中的凭据，不强制重填；可在复制件中改为专用 SELECT 账号，旧账号权限不会因只读事务自动降级。导出只保留受支持哈希，不保留源 SQL 凭据/TOTP 秘密/明文密码。确认、认领与重启只使用本地快照，`migration disable` 持久停止新认领而不撤销既有归属。旧静态 `migration.*` 与人工核验命令仅留作显式异常/直接来源入口。
- `AuthMeConfigReader` 必须读取显式 `DataSource.backend`，没有类型不猜 SQLite；配置为 MYSQL/MARIADB 时残留 .db 从不作为回退。`AuthMeSourceSelection` 在驱动准备前校验端点：本地源需每次 `--local-source` 确认，`--source-host`/`--source-port` 仅覆盖本次主机/端口；保留其余字段与强 TLS，拒绝 wildcard、歧义数字 IP 和凭据型 URL，不做 DNS/网络探测。预检/progress 的来源摘要不含账号密码/JDBC URL。
- 来源支持 SQLite 一致性快照及 MySQL/MariaDB 只读访问；仅支持官方默认 16 位小写十六进制盐的 `$SHA$` / `SHA256`，TOTP 非空或未知哈希拒绝。远程数据库 TLS 必须校验身份，超时有界；数据库连接凭证不是玩家旧密码。没有 HTTP 认领监听、网页或证书配置，数据库 TLS 不是 HTTP 功能。
- 运行时启用本地快照时，只有固定 **Velocity 4.3.1** 元数据校验及当前连接前置 guard 安装成功，原生适配器才通知公共 gate 标记受保护 session。新连接安装失败即拒绝；已有未保护连接不会收到认领入口，须重新连接。dedicated unsigned / 受支持 Legacy 敏感命令在常规命令事件、Brigadier 回显、后端转发和公共补全事件前被消费；签名/LastSeen 敏感格式保守断开，不剥离或改写签名。关闭模块先关闭原通道，实际关闭前 guard 继续拒绝入站。
- 轻量解析后进入有界 authIO：单连接一个进行中，账号 5 次/10min、来源地址 20 次/10min，本地验密并发 4；导入器单独执行，不占登录工作队列。接管的密码 CharArray 在所有退出路径清零；不记录/回显密码或哈希，错误不披露账号存在性细节。该保护不覆盖更早原始包记录器、恶意 root/替换插件、客户端历史/截屏，也不为 offline Minecraft 连接增加传输加密；不得表述为端到端或运营者绝对不可见。
- 两证、双确认、原子提交和一次普通重连的流程不变；schema 仍为 v5，v1～v4 保持不变，来源授权以 `AUTHME_PASSWORD` 写入 `proof_method` 和审计。构造账号/微软响应与模块全绿都不等于真人账号授权验收。

自动识别、批量登记与控制台闭环可单独运行（来源为构造快照，不连接真实账号或远程数据库）：

```bash
./gradlew :core:test --tests '*AuthMe*Test' --tests '*ProfileClaimBatchImportTest' :proxy-common:test --tests '*AuthMe*Test' --tests '*LoginGateContractTest'
```

固定 runtime 与 EmbeddedChannel 回归可单独运行：

```bash
./gradlew :platform-velocity:test --tests '*VelocityClaimCommands*Test'
```

真实 native 测试使用仅加入 testRuntimeClasspath 的 `run-proxy/velocity.jar`，严格校验 4.3.1；全新 checkout 缺少该缓存时仅以显式 assumption 跳过 native 用例，无 JAR 的 EmbeddedChannel 基础用例仍执行。已缓存但不在运行 classpath 或未知版本不是可接受的跳过理由。

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

- [tools/profile-claim-e2e.py](../tools/profile-claim-e2e.py)：用构造的旧号证明与微软响应，驱动真实 Velocity 私有 Limbo 的三次菜单点击、数据库认领和普通重连。`claim` 会完整停止并重启隔离代理，在迁移开关关闭时通过实际 `AuthCoordinator` 重新验证微软身份，然后核对 Paper 旧 UUID 的背包、末影箱、经验与成就；可用固定版本 LuckPerms 验证权限连续性。`cancel`、`disconnect`、`expired` 分别检查取消源档确认、最终确认前断线、授权过期时不提交归属、不进入 Paper。默认 `mapping` 只覆盖存储接缝和原生转发，不代表菜单交互：
  ```bash
  ./gradlew :platform-velocity:compileTestKotlin :platform-velocity:shadowJar
  python -B tools/profile-claim-e2e.py run --case claim
  python -B tools/profile-claim-e2e.py run --case authme
  python -B tools/profile-claim-e2e.py run --case cancel
  python -B tools/profile-claim-e2e.py run --case disconnect
  python -B tools/profile-claim-e2e.py run --case expired
  python -B -m unittest discover -s tools -p 'test_*.py'
  ```
  `authme` 是新增自助来源路径的隔离复现命令，使用构造的 AuthMe 来源账号和微软响应；上面列出命令不表示本轮已经执行或取得 PASS，须以实际运行输出和留存证据为准。工具通过测试 bootstrap 启动并调用实际生产类，但这种测试装配不是玩家的生产授权，也不代表真人浏览器已经完成微软登录。

  工具只写入全新 `.narrafork/profile-claim-e2e` 子目录，仅从已有缓存复用公开 JAR；不读取真实账户、生产配置或玩家数据。证据中的 `syntheticProofs` 与 `clientRendering` 必须分别解读，不能把构造密码证明、菜单协议交互或普通重连通过写成真实 AuthMe/Microsoft 账号验收或画面渲染通过。

## 验证边界说明

各工具只证明其所在层面的正确性，互不替代：

1. **单元测试与官方 Codec 验包**：证明状态机规则与数据包可被官方协议解码，不代表真实客户端已完成画面渲染。
2. **受控隔离端到端脚本**：证明真实代理事件链、Floodgate 加密器与转发票据行为；微软令牌、官方关联与旧号证明为构造输入。原生选角脚本的后端为记录夹具，旧档认领脚本则实际启动全新 Paper，可验证原版玩家资料与指定版本真实插件的 UUID 连续性；两者不能混称为相同层级的后端验收。
3. **实机人工验收与生产变更禁令**：基岩客户端与手机端 GUI、真实 AuthMe 旧密码和账号归属核验、微软/Xbox 真人网页授权、手动回连体验、Velocity `AuthMe -> Java U` 自助与人工路径，以及下游子服按 UUID 存储的玩家存档连续性，仍需在目标隔离环境确认。当前没有自愿提供的真实测试号，真实 AuthMe/Microsoft 账号授权尚未验收。项目尚未部署生产；代码评审或本地构建全绿不自动授权对生产库执行 schema 迁移、重启、部署或提交。仅保证原版及严格按 `GameProfile` UUID 索引的数据延续，不承诺任意第三方插件通用兼容。
