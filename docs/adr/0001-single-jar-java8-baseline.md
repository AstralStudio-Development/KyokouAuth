# 单 jar，Java 8 字节码基线

> **已被 [ADR-0017](0017-minimum-version-1-16.md) 取代。** 字节码基线现为 Java 17，最低服务端为 1.16。本文保留为当时决策的记录。

服务端版本从 1.12 到 26.3，对应 JVM 从 Java 8 到 Java 25。插件的字节码只能取交集，即 class file version 52。这不是偏好，是唯一可行解：早期服务端通常跑在 Java 8 上，而 26.x 需要 Java 25，服务端不会为插件换 JVM。

直接后果如下，全部是强制的而非可选的：

- **依赖只能用 Java 8 分支**：Adventure 4.26.x（5.x 要 Java 21）、HikariCP 5.1.0（6/7 要 11/17）、Caffeine 2.9.x（3.x 要 11）、H2 1.4.200（2.x 要 11）、Kotlin `jvmTarget = "1.8"`。
- **`plugin.yml` 不能写 `api-version`**：合法值是 1.13–26.3，写任何值都会让 1.12 拒绝加载。代价是必须按 legacy 插件形式加载，接受 Paper 的旧版兼容通道。
- **新版 API 全部反射或动态注册**：`AsyncPlayerPreLoginEvent#setPlayerProfile`（Paper 1.12+）、配置阶段（`AsyncPlayerConnectionConfigureEvent`，Paper 1.21.7+）、Dialog（1.21.6+）在不同版本上并非都存在。直接 `@EventHandler` 注册一个引用这些类型的监听器会在类加载阶段就抛 `NoClassDefFoundError`——1.8.8 上实测到过。因此现代能力必须放在独立类里，由运行时探测后装载。

代价是放弃现代语言特性和较新库版本；收益是真正的一包通吃。这个权衡一旦选定，后续所有依赖选择都要先问「它支持 Java 8 吗」。
