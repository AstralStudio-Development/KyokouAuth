# 基岩版与 Floodgate

## 组件分工

```
基岩客户端 → Geyser（协议翻译）→ Paper → Floodgate（身份注入）→ KyokouAuth
```

**Geyser** 把 Bedrock 协议翻译成 Java 协议。

**Floodgate** 在 Geyser 转发的连接中还原基岩玩家的 XUID，并直接写入登录身份。它只处理携带 Bedrock 数据的连接，纯 Java 连接原样放行。

**KyokouAuth** 认领 Java 正版名。基岩玩家不经过名字表。

## 共存

两者可以同时安装，不需要额外配置。

| 连接来源 | 谁处理 |
|---|---|
| 基岩版（有 Bedrock 数据） | Floodgate，KyokouAuth 放行 |
| Java 正版名 | KyokouAuth 握手 |
| Java 其他名字 | KyokouAuth 登录闸门 |

基岩玩家不会占用名字表：Floodgate 给基岩名加前缀（默认 `.`），而 `GameName` 拒绝该字符。

## 关联到 Java 身份

基岩玩家默认使用 `UUID(0, xuid)`，与 Java 版数据不互通。

要让同一个人的基岩版和 Java 版共享数据，需要 Floodgate 的 player-link。本插件自带一个链接扩展，启动时自动写入 Floodgate 目录。

### 配置

Floodgate 的 `plugins/floodgate/config.yml`：

```yaml
player-link:
  enabled: true
  enable-own-linking: true
  type: global
```

`type` 的值必须与 jar 文件名一致 —— Floodgate 找的是 `floodgate-{type}-database.jar`。

改完重启服务器。首次写入扩展后需要重启一次，因为 Floodgate 只在启动时扫描该目录。

### 关联方式

在 https://link.geysermc.org/ 用微软账户完成关联。关联后基岩连接会解析为 Java 账号的 UUID 与名字。

### 为什么需要自带的扩展

Floodgate 的全局链接向 `api.geysermc.org/v2/link/bedrock/<xuid>` 查询，该端点在不带尾斜杠时返回空对象，带尾斜杠才返回数据。Floodgate 发的是不带斜杠的形式，因此始终查不到。

自带扩展使用带尾斜杠的 URL，其余行为一致。详细记录见 [ADR-0013](../../docs/adr/0013-integration-findings.md)。

## 已知限制

**已关联的基岩玩家显示默认皮肤。** Floodgate 在登录阶段写入它自己的默认纹理，`AsyncPlayerPreLoginEvent` 里设置的皮肤会被丢弃。四条注入路径均已实测无效，详见 ADR-0013。

**Geyser 的 remote ping 警告可以忽略。** Geyser 会持续报告 `Failed to ping the remote Java server`，这不影响转发 —— 玩家照常进服。
