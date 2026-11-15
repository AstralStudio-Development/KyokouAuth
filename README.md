<div align="center">

# KyokouAuth

离线模式服务端的微软账户认证

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.12%20–%2026.3-3C8527.svg)](#兼容性)
[![Java](https://img.shields.io/badge/Java-8%2B-ED8B00.svg)](#兼容性)

</div>

## 是什么

`online-mode=false` 的服务端默认信任客户端自称的名字。KyokouAuth 要求每个进入世界的玩家先证明自己拥有一个微软账户。

| 玩家 | 验证方式 | UUID |
|---|---|---|
| 拥有 Java 版 | Mojang 握手（服务端完成） | 真实 Mojang UUID |
| 无 Java 版 | 微软设备码 | `UUID(0, xuid)` |

客户端不需要安装任何模组。

## 快速开始

```text
plugins/KyokouAuth.jar
```

启动服务器，`config.yml` 自动生成，默认 SQLite。

要求 `server.properties` 中 `online-mode=false`。

## 命令

```
/kyokouauth whois <名字>      查看名字绑定
/kyokouauth unbind <名字>     解除绑定
/kyokouauth rename <旧> <新>  改名
/kyokouauth untrust <名字>    清除可信会话
/kyokouauth status            查看状态
/kyokouauth reload            重载配置
```

权限节点 `kyokouauth.admin`，默认仅 OP。

## 兼容性

| 项目 | 范围 |
|---|---|
| Minecraft | 1.12 – 26.3 |
| 服务端 | Paper、Spigot |
| Java | 8+ |
| 可选集成 | Floodgate、Geyser、PlaceholderAPI、SkinsRestorer |

低于 1.12 拒绝启动。检测到代理转发时拒绝启动。

## 构建

```bash
./gradlew build
```

产物在 `platform-bukkit/build/libs/`。

`tools/floodgate.jar` 是 Floodgate 链接扩展的编译依赖，随仓库提供。

## 文档

- [插件文档](docs-site/) —— 安装、配置、命令、API
- [架构决策记录](docs/adr/) —— 每个非显然选择的论证

## 许可证

[Apache-2.0](LICENSE)
