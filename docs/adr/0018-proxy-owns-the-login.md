# 代理接管登录：双平台代理与库驱动的多版本 Limbo

取代 [ADR-0001](0001-single-jar-java8-baseline.md)、[ADR-0005](0005-limbo-over-kick.md)、[ADR-0009](0009-login-injection-gates-both.md)、[ADR-0014](0014-handshake-verified.md)。

插件从「Paper 后端上的 Bukkit 插件」变成「Velocity / Bungee 代理插件」。`platform-bukkit` 与 `floodgate-link` 整体删除，身份算法与存储留在 `core`；`proxy-common` 共享认证接线、验证会话、配置和命令业务，两个平台只转换原生事件/profile/组件。验证大厅由统一 `limbo` 入口管理按协议隔离的 Engine。

## 为什么必须搬

后端插件在两个地方够不到：

1. **`/server` 不受约束。** 命令由代理注册并消费，`UpstreamBridge.handleChat` 里 `dispatchCommand` 返回 true 后就 `CancelSendSignal`，请求根本不到后端。后端把玩家冻在原地、取消一切事件，也拦不住这一条。
2. **正版握手与代理转发冲突。** 后端插件靠替换 `ServerConnection` 里的 channel 完成登录注入；同一个后端一旦开启 `velocity` 转发，两者对同一条连接的预期不一致，代理直接以 `Backend server is online-mode!` 断开。

第 1 条是设计缺陷，第 2 条是直接动因。

## Limbo 协议实现：撤销手写编解码器

原来的手写协议实现已整体替换为 MCProtocolLib。各协议使用独立固定版本库和命名空间，避免 Gradle 冲突解析把多个版本合并为最新版；原有 26.3-4 属于 777 Engine。LimboAPI 与桌面 Narcissus 只作本地参考，不复制源码、不引入插件依赖。

- 支持 767～777 共 11 个协议，即 1.21～26.3 正式版，不自动支持未来版本/快照。每个 Engine 使用对应官方注册表与来源/校验和；配置阶段协商 known packs 后仍发送完整 registry 与 tags，不跨版本复用 777 数据。
- MCProtocolLib 提供编解码、NBT 文本与网络传输；本项目管理可信代理身份、共享生命周期和空世界。此前把11份整栈逐版本重定位导致每包123MiB，已撤销该打包决策：协议实现仍隔离，普通依赖只一份，Adventure按767～769/770～775/776～777三个文本族共享；Netty 4.2 compileOnly使用已验证平台提供的实现，Engine只拥有/关闭自己的NIO groups。公共 Interface仍只使用JDK/自有模型。
- 构建期以固定上游源码和完整 registry 校验生成明确ID、原厂factory及keep清单；生产只用稀疏Codec，不携带完整MinecraftCodec或客户端认证监听器。不手写framing/NBT/包字段，不运行时下载协议库。
- 必要入站包继续typed；只对清单内已知、非terminal、无需业务处理的PLAY帧消费并忽略当前payload，不承诺验证这些未使用字段的内容。未知ID、状态切换、握手/config、心跳及READY信号不静默吞掉。
- R8仅处理Limbo图的未用成员，名称和类型继承保持；精确保留反射、泛型、sealed文本契约、SPI及DER锚点，core/平台代理代码不重写。正式产物目标10MiB、20MiB硬门槛，默认构建不合格即失败，不能只交未裁剪classes或隐藏到额外下载。
- 26.3-4 的 previous game mode 仍用旧式 BYTE -1 表示缺省，原版要求 OPTIONAL_VAR_INT 0；`Login263` 保留一处版本固定的兼容修正，并由原版 codec 及缺省语义断言独立校验。
- 原版 codec 验包、生命周期/隔离测试、真实原版客户端退出加载界面是不同层次的验收，不能用“构建成功”或手写探针发送 ACK 冒充渲染成功。
- 各 Engine 的 loopback 随机端口作为私有普通后端注册，代理按实际客户端协议选择；未验证连接只能进入自己的 Engine，在首连无 currentServer、大厅失败、切服或自动 failover 时均拒绝进入真实后端。
- Velocity 保留 modern HMAC。Bungee 的 legacy 握手 UUID/IP/name 不可信，必须附加当前进程签发、绑定 exact 连接/身份/协议/目标、短 TTL 的一次性票据，由 authority 原子兑换；不在 HMAC 失败后降级。Bungee profile 仅通过启动时核对签名的窄 bridge 补齐，UUID 使用公共 setter；真实后端连接前清除票据。
- 标题在有效 challenge 返回后仅发一次，stay 对齐剩余验证 deadline；会话结束或重启前清理，不再周期性重发淡入动画。

## 直接后果

| 变化 | 说明 |
|---|---|
| 删除 `platform-bukkit` | 整模块。`CachedIdentityRepository` 移入 `core`，消息目录改为 `core` 的 `MessageCatalogue` |
| 删除 `floodgate-link` | 基岩版联动只有 Bukkit 形态；代理侧要重做，见待办 |
| 正版名字由代理完成会话验证 | `platform-bukkit` 自己往 `ServerConnection` 里注入 `PremiumHandshakeHandler` 来接管正版握手；代理层不再需要那段注入，`PreLoginEvent.forceOnlineMode()` 就是同一件事：只对 Mojang 拥有的名字开一次会话验证，正版客户端自动应答，玩家零操作，冒名客户端在这里断掉。绑定在验证通过后的 `LoginEvent` 里补记 |
| 登录判定必须完成后才能继续 | Velocity 4.3.1 的 PreLogin 不是 AwaitingEvent，必须在处理器返回前决定；Bungee 则同步注册 intent 后交共享 IO，恰好完成一次。Bungee onlineMode/UUID setter 必须在 PreLogin 的 USERNAME 阶段调用，不能拖到 Login |
| 验证大厅按协议隔离 | 767～777 各用固定库、完整注册表与专属目标；未知协议拒绝，不猜包布局或跨 Engine 降级 |

## 被否掉的替代方案

- **断开 + 可点击链接，不做大厅。** 实现最省，但玩家必须在断线界面点完链接、再手动重连，且插件无法在成功后自动放行。体验退步到「踢人式验证」。
- **只在 `PreLoginEvent` 拒/放，不搬后端。** 解决不了 `/server`：验证中的玩家仍会被送到真实后端。
- **保留 Bukkit 插件、改用 BungeeCord 转发。** 换一种转发模式而已，登录注入与转发模式的冲突依旧。
