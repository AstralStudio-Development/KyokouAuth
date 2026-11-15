# 占位符

需要安装 PlaceholderAPI。注册成功时启动日志显示：

```
[KyokouAuth] PlaceholderAPI 变量已注册
```

## 列表

| 占位符 | 返回值 |
|---|---|
| `%kyokouauth_verified%` | `true` / `false` —— 是否已通过验证 |
| `%kyokouauth_pending%` | `true` / `false` —— 是否正在 Limbo 中等待验证 |
| `%kyokouauth_bound%` | `true` / `false` —— 名字是否已绑定身份 |
| `%kyokouauth_kind%` | `premium` / `microsoft` / 空 |
| `%kyokouauth_gamertag%` | Xbox 玩家代号，正版玩家为空 |
| `%kyokouauth_subject%` | 标识：Mojang UUID 或 XUID |

## 示例

TAB 前缀：

```yaml
prefix: "%kyokouauth_kind%"
```

在计分板中显示验证状态：

```
&7状态: &f%kyokouauth_verified%
```

## 读取方式

占位符从内存缓存读取，不查询数据库 —— 计分板与 TAB 插件在主线程高频调用它们。

缓存于玩家登录时填充，一次会话内不会变化。
