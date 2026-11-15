# 安装

## 要求

| 项目 | 要求 |
|---|---|
| Minecraft | 1.12 或更高 |
| 服务端 | Paper 或 Spigot |
| Java | 8 或更高 |
| online-mode | **必须为 `false`** |

## 步骤

**1. 放入插件**

把 `KyokouAuth.jar` 放进 `plugins/`。

**2. 确认离线模式**

`server.properties`：

```properties
online-mode=false
```

插件在 `online-mode=true` 下不会生效 —— 它存在的前提就是离线模式。

**3. 启动服务器**

首次启动会生成：

```
plugins/KyokouAuth/
├── config.yml
├── skins.yml
└── messages/
    ├── messages_zh_CN.yml
    └── messages_en_US.yml
```

默认使用 SQLite，无需额外配置。

## 确认工作正常

启动日志应包含：

```
[KyokouAuth] KyokouAuth 已启用（SQLite）
[KyokouAuth] 正版握手已装载
[KyokouAuth] 消息渲染正常（zh_CN）
```

如果出现 `没有可用的通道钩子`，说明服务端版本不支持所需的事件，正版玩家将无法进服，需要更换 Paper 版本。

## 代理后端

如果服务器位于 BungeeCord 或 Velocity 之后，插件会拒绝启动：

```
服务器处于代理后端，拒绝启动。
```

代理接管了登录握手，插件看不到它，所有玩家都会被当作未验证。认证会静默失效，因此选择直接拒绝。

要跳过该检查（并接受认证失效）：

```yaml
safety:
  require-direct-connections: false
```

## 切换数据库

见 [数据库](../configuration/database.md)。切换前请备份 `plugins/KyokouAuth/kyokouauth` 文件（SQLite）。

## 下一步

- [玩家的登录流程](login-flow.md)
- [config.yml 全解](../configuration/index.md)
