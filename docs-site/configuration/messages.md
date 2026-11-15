# 多语言与文案

## 内置语言

| 代码 | 语言 |
|---|---|
| `zh_CN` | 简体中文 |
| `en_US` | 英语 |

切换：

```yaml
messages:
  locale: zh_CN
```

## 文件位置

```
plugins/KyokouAuth/messages/
├── messages_zh_CN.yml
└── messages_en_US.yml
```

内置文案在 jar 内。要修改，在数据目录放置同名文件即可，改完 `/kyokouauth reload`。

## 覆盖规则

外部文件按**顶层段**覆盖，不按单个键：

```yaml
# plugins/KyokouAuth/messages/messages_zh_CN.yml
login:
  invalid-name: "<red>用户名不合法。"
  name-taken: "<red>这个名字不属于你的微软账户。"
  premium-name: "<red>这个名字属于正版账号。"
  # 其余 login.* 键也必须写全，否则会回退到内置值
```

要只改一条消息，复制内置文件中整个顶层段，改其中一行。

## 新增语言

放置 `messages_<代码>.yml` 并把 `locale` 设为该代码。缺失的键会回退到 `en_US`。

## 格式

支持 MiniMessage 标签和传统 `§` 颜色码，两种可混用：

```yaml
example: "<red>红色 <white>白色</white></red>"
legacy: "§c红色"
```

可用的占位符由各条消息自身定义，例如 `<name>`、`<uuid>`、`<reason>`。

## 消息后端

插件在启动时按类路径自动选择：

| 后端 | 条件 |
|---|---|
| Adventure | 服务端内置 Adventure（大部分现代版本） |
| BungeeChat | 存在 BungeeCord Chat API |
| Plain | 以上都没有，降级为纯文本 |

启动日志会显示当前后端：

```
[KyokouAuth] 消息后端：adventure
```

## 自检

启动时会渲染一条消息验证链路：

```
[KyokouAuth] 消息渲染正常（zh_CN）
```

失败则拒绝启动 —— 否则每个玩家都会被踢出且看不到原因。
