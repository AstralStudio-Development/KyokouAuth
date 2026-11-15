# 消息投递与服务器能力的解耦

消息层拆成两半：**解析**在 `core`（`RichText` + `LegacyRenderer`，零依赖、可单测），**投递**在平台层（`Messenger`，三套实现按类路径探测选择）。

## 为什么必须拆

ADR-0008 已经把 MiniMessage 换成自研解析器，但那还不够。1.8.8 上一次真实启动暴露了残留耦合：

```
Plugin KyokouAuth has failed to register events for class ...LoginListener
because net/kyori/adventure/text/Component does not exist.
```

只要任何一处直接引用 Adventure 类型，那个类在加载时就失败——而 `LoginListener` 是整个插件的心脏。所以不是「渲染时降级」，而是**类加载期就不能有该引用**。

## 三套投递后端

| 后端 | 条件 | 点击链接 | 标题 |
|---|---|---|---|
| Adventure | Paper 1.16.5+ | 支持 | 支持 |
| BungeeChat | Spigot 1.8+（1.8.8 实测命中） | 支持 | 无 |
| Plain | 两者皆无 | 不支持 | 无 |

BungeeChat 全部走反射：那些类不在 `paper-api` 上，也不该为一个可选集成再引入一套编译期 Bukkit API。

## 监听器按能力拆分

`PlayerSwapHandItemsEvent`（1.9+）和 `AsyncChatEvent`（Paper 专有）原先和其余限制写在一个类里，导致 1.8 上整个监听器注册失败——**一条限制的缺失拖垮了全部限制**。现在拆成独立的类，启动时按类路径探测分别注册，并记一行日志说明跳过了什么。

## 1.8 的真实结论：拒绝启动

拆分后 1.8.8 能加载到 `LoginListener`，随即撞上第二个硬约束：

- `AsyncPlayerPreLoginEvent` 在 1.8.8 上**没有 `setPlayerProfile`**（已用 javap 核实）。
- 没有它就无法把连接的 UUID 指定为 `UUID(0, xuid)`。
- 结果会是每个玩家保留客户端自报的离线 UUID——这正是插件要堵的冒充漏洞。

所以现在检测到该能力缺失时**拒绝启动**并打印原因，而不是带着错误 UUID 运行。这比"能加载但行为错误"诚实。

真正支持 1.8–1.12 需要在注入层补一条 Spigot 版的 channel 钩子（反射进 `vX_RX.ServerConnection`）并在出站 `Login Success` 上改写 UUID。`ChannelHook` 目前只走 Paper 的 `ChannelInitializeListenerHolder`，所以 1.8 上注入层也没装起来。这一步未做。

## 实测

| 服务器 | 后端 | 结果 |
|---|---|---|
| Paper 26.3 | adventure | 启用、握手装载、两条登录路径均正常 |
| Paper 1.8.8 | bungeechat | 后端正确选中、建表成功、能力门控生效，**拒绝启动**并说明原因 |

重构后 26.3 的两条路径复测通过：非正版名 `login_success`，正版名 `encryption_request` → 被拒绝并收到 `§`-编码的中文提示。
