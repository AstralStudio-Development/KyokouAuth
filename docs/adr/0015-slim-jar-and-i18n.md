# 驱动不打包，运行时按需下载

插件 jar 从 **27.8 MB 降到 3.8 MB**。省下的部分几乎全是 JDBC 驱动。

## 数字

| 组件 | 大小 |
|---|---|
| sqlite-jdbc | 13.5 MB（带全平台本地库） |
| h2 | 2.3 MB |
| mysql-connector-j | 2.5 MB |
| mariadb-java-client | 0.7 MB |
| postgresql | 1.1 MB |
| **合计** | **约 20 MB** |

服务器只会用其中一种。把五种都塞进 jar，等于让每个服主为四个用不到的数据库付费下载。

## 做法

驱动在首次使用时下载到 `plugins/KyokouAuth/libraries/`，然后由单独的 `URLClassLoader` 加载。

三个附带决定：

- **手动放置优先。** 那个目录里已有的 jar 不会被网络覆盖。离线服务器把驱动丢进去就行。
- **不用 HikariCP 的 `driverClassName`。** 它是用 `Class.forName` 在自己的类加载器里找驱动的，看不到运行时下载的 jar。改成一个极小的 `DataSource` 适配器直接持有 `Driver` 实例（`DriverBackedDataSource`）。
- **先写 `.part` 再改名。** 否则下载中断留下的半截 jar 会在下次启动时被当成「已下载」，然后以谁也看不懂的方式失败。另外检查了文件大小——有些镜像会用 200 返回一个 HTML 错误页。

## 版本全部钉在 Java 8 能跑的最后一代

这是 ADR-0001 的直接后果，也是为什么 H2 是 1.4.200 而不是 2.x（2.x 要 Java 11）。一台上 1.12 的服务器几乎必然跑在 Java 8 上。

## 配置升级会被强制刷新

`saveDefaultConfig` 从不覆盖已有文件——这意味着老服务器会永远保留旧的 `config.yml`，包括里面的旧注释。这正是「服主照着已不存在的选项调参」的成因。

加了 `config-version`：磁盘上的版本低于插件期望时，旧文件备份成 `config.old.yml`，写入新的默认配置。

**选择备份而不是合并**：半合并的配置比一份清楚备份过的旧配置更难排查，而这里总共也就十几个选项。

## 控制台输出走语言文件

之前 `KyokouAuth enabled (...)`、`premium handshake installed...` 这些是硬编码英文。现在统一走 `console.*` 消息键，跟随 `messages.locale`。

理由不是翻译好看，而是：一个看不懂英文的服主，在插件拒绝启动时读不到原因。拒绝启动的信息恰恰是最需要被读懂的那条。
