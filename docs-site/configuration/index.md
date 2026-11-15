# config.yml 全解

```yaml
database:
  type: sqlite
  host: 127.0.0.1
  port: 0
  database: kyokouauth
  username: ""
  password: ""
  pool-size: 4
  driver-repository: https://repo1.maven.org/maven2
  auto-download-driver: true

session:
  hours: 12

verification:
  timeout-seconds: 300
  poll-interval-seconds: 5

limbo:
  teleport-to-spawn: false
  show-title: true
  show-bossbar: true
  blind: false

enforce-bound-name: true

premium:
  session-server-url: https://sessionserver.mojang.com/session/minecraft/hasJoined
  directory-limit: 600
  directory-limit-window-minutes: 10

safety:
  require-direct-connections: true

messages:
  locale: zh_CN
```

## database

| 键 | 说明 |
|---|---|
| `type` | `sqlite`、`h2`、`mysql`、`mariadb`、`postgresql` |
| `host` / `port` | 网络数据库地址，`port` 为 0 时使用各类型默认端口 |
| `database` | SQLite / H2 为文件路径（相对插件目录），其余为库名 |
| `pool-size` | 连接池大小 |
| `driver-repository` | JDBC 驱动下载源，可指向镜像 |
| `auto-download-driver` | 关闭时驱动必须已存在于 `libraries/` |

详见 [数据库](database.md)。

## session

`hours`：验证成功后在同一 IP 上的信任时长。`0` 关闭会话复用。

## verification

| 键 | 说明 |
|---|---|
| `timeout-seconds` | 设备码的有效期 |
| `poll-interval-seconds` | 向微软查询结果的间隔 |

## limbo

| 键 | 说明 |
|---|---|
| `teleport-to-spawn` | 把未验证玩家传送到出生点 |
| `show-title` | 显示大字提示 |
| `show-bossbar` | 显示 BossBar |
| `blind` | 致盲 |

未验证玩家无论如何都无法移动、交互、拾取或使用命令。

## enforce-bound-name

为 `true` 时，已绑定身份的玩家不能用另一个名字连接。关闭后允许同一 UUID 更换名字。

## premium

| 键 | 说明 |
|---|---|
| `session-server-url` | 握手复核用的会话服务器地址 |
| `directory-limit` | 每窗口内允许的 Mojang 名字查询次数 |
| `directory-limit-window-minutes` | 上述窗口长度 |

Mojang 大致允许每地址每十分钟 600 次查询。超出后新连接会被拒绝而不是放行。

## safety

`require-direct-connections`：为 `true` 时，检测到服务器位于代理后端会拒绝启动。详见 [安装](../guide/installation.md#代理后端)。

## messages

`locale`：内置 `zh_CN`、`en_US`。详见 [多语言与文案](messages.md)。
