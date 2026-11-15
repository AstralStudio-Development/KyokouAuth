# 兼容性

## 版本

| Minecraft | 状态 |
|---|---|
| 26.3 | 已验证 |
| 1.16.5 | 已验证 |
| 1.12.2 | 已验证 |
| 1.8.8 | 拒绝启动 |

最低支持 1.12。原因是 `AsyncPlayerPreLoginEvent#setPlayerProfile` 从该版本起提供，指定玩家 UUID 依赖它。低于 1.12 时插件拒绝启动并说明原因。

插件本身编译为 Java 8 字节码，可运行于 Java 8 至 Java 25。

## 服务端

| 服务端 | 状态 |
|---|---|
| Paper | 支持 |
| Spigot | 支持 |
| Folia | 未测试 |

通道钩子优先使用 Paper 的 `ChannelInitializeListenerHolder`，不存在时回退到反射包装 Netty 的 `ChannelInitializer`。

## 集成

| 插件 | 状态 | 说明 |
|---|---|---|
| Floodgate | 已验证 | 基岩玩家免验证；自带链接扩展 |
| Geyser | 已验证 | 通过 Floodgate 接入 |
| PlaceholderAPI | 已验证 | 注册 `%kyokouauth_*%` |
| SkinsRestorer | 已验证 | 检测到时皮肤完全让位 |

## 代理

不支持。检测到服务器位于 BungeeCord 或 Velocity 后端时拒绝启动，因为代理接管了登录握手，插件看不到它，认证会静默失效。

## 已知限制

| 限制 | 原因 |
|---|---|
| 已关联的基岩玩家无正版皮肤 | Floodgate 覆盖登录阶段的 profile |
| Mojang 查询有速率上限 | 默认 600 次 / 10 分钟，超出后拒绝新连接 |
