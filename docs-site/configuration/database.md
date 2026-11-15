# 数据库

支持五种关系型数据库，建表与迁移自动完成。

## SQLite

```yaml
database:
  type: sqlite
  database: kyokouauth
```

文件位于 `plugins/KyokouAuth/kyokouauth`。默认选择，无需额外配置。

## H2

```yaml
database:
  type: h2
  database: data/kyokouauth
```

## MySQL

```yaml
database:
  type: mysql
  host: 127.0.0.1
  port: 3306
  database: kyokouauth
  username: root
  password: secret
```

## MariaDB

```yaml
database:
  type: mariadb
  host: 127.0.0.1
  port: 3306
  database: kyokouauth
  username: root
  password: secret
```

## PostgreSQL

```yaml
database:
  type: postgresql
  host: 127.0.0.1
  port: 5432
  database: kyokouauth
  username: postgres
  password: secret
```

## 连接池

```yaml
database:
  pool-size: 4
```

登录与查询在独立线程池上执行，不占用主线程。该值决定数据库并发连接上限。

## JDBC 驱动

驱动不打在插件里，首次使用时下载到服务端的 `libraries/` 目录（Maven 布局）：

```
libraries/com/mysql/mysql-connector-j/<版本>/mysql-connector-j-<版本>.jar
```

| 键 | 说明 |
|---|---|
| `driver-repository` | 下载源，默认 Maven Central，可指向镜像 |
| `auto-download-driver` | 关闭后驱动必须已存在于 `libraries/` |

Paper 自身已携带 SQLite 与 MySQL 驱动，这两类通常不需要下载。

无外网环境：

```yaml
database:
  auto-download-driver: false
```

然后手动把驱动 jar 放到上述路径。

## 迁移

启动时自动把结构升级到当前版本，日志会显示：

```
[KyokouAuth] 数据库升级到 v2
```

迁移在事务内执行，失败则回滚并拒绝启动。

## 备份

SQLite 与 H2 直接复制 `plugins/KyokouAuth/` 下的数据文件即可，注意连同 `-wal` / `-shm` 一起复制。

网络数据库使用各自的备份工具。
