# 常见问题

## 正版玩家进不来，提示需要正版启动器

该名字属于 Mojang 账号。只有真实的正版客户端能通过握手，没有其他入口。

如果玩家确实拥有正版，检查：

- 客户端是否用官方启动器登录
- 服务端是否能访问 `sessionserver.mojang.com`

## 控制台说「没有可用的通道钩子」

服务端版本不支持所需的管线钩子。正版玩家将无法进服。更换受支持的 Paper 版本。

## 玩家卡在 Limbo 出不来

设备码未完成。让他点击聊天栏中的链接，或访问 `microsoft.com/link` 输入短码。

链接有效期默认 300 秒，可在 `verification.timeout-seconds` 调整。

## 验证成功后为什么还要重连一次

验证期间玩家使用的是临时 UUID，绑定完成后才确定最终身份。这是首次绑定的固有代价。

之后同一 IP 的登录在会话有效期内免验证。

## 服务器在 BungeeCord / Velocity 后面

不支持。代理接管了登录握手，插件看不到它，认证会静默失效 —— 因此拒绝启动。

## 提示「这个名字已被正版账号注册」

玩家原先绑定了一个名字，之后有人用该名字注册了 Mojang 账号。

存档没丢（数据绑定在 UUID 上）。管理员执行 `/kyokouauth rename <旧名> <新名>` 即可。

## 基岩版玩家和 Java 版数据能互通吗

默认不能。基岩玩家使用 `UUID(0, xuid)`，与 Java 版身份独立。

要互通需启用 Floodgate 的 player-link，详见 [基岩版与 Floodgate](guide/bedrock.md)。

## 基岩版玩家没有皮肤

已知限制。Floodgate 在登录阶段写入自己的默认纹理，插件的设置会被丢弃。详见 [ADR-0013](../../docs/adr/0013-integration-findings.md)。

## 修改文案后没生效

执行 `/kyokouauth reload`。注意外部语言文件按顶层段覆盖，只写一条键会回退到内置值 —— 详见 [多语言与文案](configuration/messages.md)。

## 数据库能换吗

可以，修改 `database.type` 后重启。插件不会迁移数据，换库等于从空库开始。
