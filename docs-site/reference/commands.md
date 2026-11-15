# 命令

主命令 `/kyokouauth`，别名 `/ka`、`/auth`。

所有子命令需要权限 `kyokouauth.admin`。

## whois

```
/kyokouauth whois <名字>
```

查看名字的绑定情况：UUID、类型、标识、玩家代号。

```
名字：Steve　UUID：6ec148d8-a1dc-4827-ad4b-409a40ee86d5
类型：MOJANG　标识：6ec148d8-…　玩家代号：
```

`类型` 为 `MOJANG`（正版）或 `XUID`（微软账户）。

## unbind

```
/kyokouauth unbind <名字>
```

解除名字与身份的绑定。该玩家下次进服需要重新验证。

用于玩家换号、名字误绑等情况。

## rename

```
/kyokouauth rename <旧名字> <新名字>
```

把绑定从旧名字移到新名字，UUID 不变。用于名字被正版账号抢占后为微软玩家换名。

新名字必须未被占用。

## untrust

```
/kyokouauth untrust <名字>
```

清除该玩家的可信会话。下次进服需要重新验证，即使仍在同一 IP。

用于怀疑账号被盗、需要立即强制重新验证时。不影响名字绑定。

## status

```
/kyokouauth status
```

显示存储类型、会话时长、当前语言。

## reload

```
/kyokouauth reload
```

重新加载 `config.yml` 与语言文件。数据库连接、会话缓存不受影响。
