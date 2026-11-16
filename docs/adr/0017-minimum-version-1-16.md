# 最低支持版本 1.16，字节码基线 Java 17

取代 [ADR-0001](0001-single-jar-java8-baseline.md) 与 [ADR-0012](0012-minimum-version-1-12.md)。

服务端版本范围从 1.12–26.3 收窄到 1.16–26.3，字节码从 Java 8 提高到 Java 17。1.8–1.15 不再支持。

## 为什么是 1.16

1.16 是第一个支持 HEX 颜色的版本（`§x§R§R§G§G§B§B`）。这条线之下，任何富文本最终都要退回到十六色调色板，`RichText` 要么维护两套颜色模型，要么对 hex 做静默降级——后者会让运维在配置里写一个颜色，游戏里看到另一个。

1.16 同时也是 Paper 自带 Adventure 的起点，消息层因此不再需要为「没有 Adventure 的服务器」保留反射分支。

## 直接后果

删掉的都是维护成本高于收益的部分：

| 删除项 | 原用途 |
|---|---|
| `LegacyRenderer` | 自研 `§` 码渲染器，为无 Adventure 的服务器兜底 |
| `BungeeChatMessenger` | Spigot 1.8+ 的反射投递后端 |
| `PlainMessenger` | 两者皆无时的纯文本后端 |
| `Messenger.create()` 类路径探测 | 三选一的运行时判定 |
| `LimboLegacyChatListener` | 1.8–1.18 的 `AsyncPlayerChatEvent` 守卫 |
| `LimboModernListener` | 1.8 上无法加载的换手/拾取守卫（已并入 `LimboListener`） |
| `ProfileApplier.MINIMUM_MENTIONED` | 无引用的死常量 |

`Messenger` 从「接口 + 三个实现」收成一个类。`§` 码仍然产出，但改由 Adventure 的 `LegacyComponentSerializer` 完成——登录阶段的 Disconnect 包文本字段是裸 JSON，装不下 Component，这条路无法省略。

## 为什么字节码是 Java 17 而不是 8

1.16 系列的服务端本身允许 Java 8，所以 8 在技术上仍然可行。选择 17 的理由不是技术约束，是运维现实：

- 1.16 早已停止维护，还在跑它的服务器几乎都已经把 JVM 升到了 17——这是大多数托管面板的默认值，也是 1.18+ 的硬性要求。
- Java 8 分支的依赖（Adventure 4.26、HikariCP 5.1、Caffeine 2.9、H2 1.4）都是为了兼容那个下限才被钉住的。留着基线就留着一份「为什么不能用新版」的解释成本。

`plugin.yml` 因此可以写 `api-version: '1.16'`，插件按现代形式加载，不再走 Paper 的旧版兼容通道。

## 驱动版本没有跟着升

`DriverCatalog` 里的五个 JDBC 驱动仍停在原版本。它们是唯一无法用单元测试替代的依赖——升级一个就要重跑对应后端的实库回归，而这次变更没有实库环境。ADR-0015 中「钉在 Java 8 能跑的最后一代」的表述因此失效，理由改为「测试矩阵覆盖过的版本」。

## 实测

迁移后 `./gradlew build` 通过，`core` 的方言与存储测试全部通过。1.16.5 与 26.3 两个服务端的实跑验证尚未重做——这次改动触及登录、消息与监听器注册三条路径，都在这两个版本上被真实客户端走过。
