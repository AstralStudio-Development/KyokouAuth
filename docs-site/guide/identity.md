# 身份模型

## UUID

| 身份类型 | UUID |
|---|---|
| 正版玩家 | 真实的 Mojang UUID |
| 微软玩家 | `UUID(0, xuid)` |

`UUID(0, xuid)` 与 Floodgate 生成基岩版玩家 UUID 的规则相同，因此同一个微软账户从 Geyser 进入和从离线 Java 客户端进入，会落到同一个 UUID 上。

## 名字

- 一个名字永久属于至多一个身份
- 一个身份只持有一个名字
- 被 Mojang 持有的名字只能由握手进入
- 名字比较不区分大小写

当一个已绑定的名字后来被 Mojang 账号注册，原持有者会被拒绝，并在控制台留下提示，管理员可用 `/kyokouauth rename` 为他换名。存档不受影响 —— 数据绑定在 UUID 上。

## 会话

一次验证成功会在当前 IP 上留下一条信任记录：

```yaml
session:
  hours: 12
```

- 同 IP 的后续连接免验证
- 换 IP 需要重新验证
- 设为 `0` 关闭会话复用，每次连接都需验证

## 三种玩家

**正版玩家**：握手成功即绑定，零操作。改名后自动跟随新名字，旧名字释放。

**微软玩家**：首次连接进入 Limbo 完成设备码，成功后绑定，需要重连一次。

**基岩版玩家**：由 Floodgate 直接放行，不占用名字表。详见 [基岩版与 Floodgate](bedrock.md)。

## 数据表

| 表 | 内容 |
|---|---|
| `kyokouauth_identity` | UUID、类型、标识（Mojang UUID 或 XUID）、玩家代号 |
| `kyokouauth_name` | 名字与 UUID 的绑定 |
| `kyokouauth_session` | 可信会话 |
| `kyokouauth_meta` | 结构版本 |
