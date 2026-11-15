# KyokouAuth 文档

离线模式服务端的微软账户认证。

## 它做什么

`online-mode=false` 时，服务端信任客户端自称的名字。

KyokouAuth 在登录阶段插入一层验证，每个连接必须先证明拥有一个微软账户：

- 有 Java 版 → Mojang 握手，真实 Mojang UUID
- 无 Java 版 → 微软设备码，`UUID(0, xuid)`

客户端不需要任何模组。

## 从这里开始

| 场景 | 页面 |
|---|---|
| 第一次接触 | [它解决什么](guide/what-is.md) |
| 准备安装 | [安装](guide/installation.md) |
| 已装好，要调行为 | [config.yml 全解](configuration/index.md) |
| 想看玩家体验 | [玩家的登录流程](guide/login-flow.md) |
| 同时跑 Geyser | [基岩版与 Floodgate](guide/bedrock.md) |
| 要写附属插件 | [开发者 API](reference/api.md) |

## 三十秒版本

```text
plugins/KyokouAuth.jar
```

启动服务器，`config.yml` 自动生成，默认 SQLite。

需要 `server.properties` 中 `online-mode=false`。

## 相关

- [架构决策记录](../../docs/adr/) —— 每个非显然选择的论证
- [项目主页](../../README.md)
