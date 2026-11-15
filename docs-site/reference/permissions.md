# 权限

## kyokouauth.admin

```
kyokouauth.admin
```

默认：仅 OP。

控制整个 `/kyokouauth` 命令，包括全部子命令。

## 说明

插件没有为玩家设计权限节点。玩家能否进服由认证结果决定，不由权限决定。

如果使用 LuckPerms 等权限插件，把该节点授予管理组即可：

```
/lp group admin permission set kyokouauth.admin true
```
