# 设计决策

每个非显然的选择都有一份架构决策记录（ADR），写明了当时的约束、被否决的方案和代价。

| 编号 | 主题 |
|---|---|
| [0001](../../docs/adr/0001-single-jar-java8-baseline.md) | 单个 jar，Java 8 字节码基线 |
| [0002](../../docs/adr/0002-uuid-from-xuid.md) | UUID 由 XUID 派生 |
| [0003](../../docs/adr/0003-device-code-only.md) | 只用设备码，不做密码 |
| [0004](../../docs/adr/0004-login-injection.md) | 登录注入层 |
| [0005](../../docs/adr/0005-limbo-over-kick.md) | Limbo 而非踢出 |
| [0006](../../docs/adr/0006-name-reservation.md) | 名字预留 |
| [0007](../../docs/adr/0007-no-proxy-v1.md) | v1 不做代理模块 |
| [0008](../../docs/adr/0008-no-minimessage.md) | 不用 MiniMessage |
| [0009](../../docs/adr/0009-login-injection-gates-both.md) | （已废弃） |
| [0010](../../docs/adr/0010-handshake-injection-status.md) | 握手注入状态 |
| [0011](../../docs/adr/0011-message-backends.md) | 消息后端 |
| [0012](../../docs/adr/0012-minimum-version-1-12.md) | 最低版本 1.12 |
| [0013](../../docs/adr/0013-integration-findings.md) | 集成实测结论 |
| [0014](../../docs/adr/0014-handshake-verified.md) | 握手已验证 |
| [0015](../../docs/adr/0015-slim-jar-and-i18n.md) | 精简 jar 与国际化 |
| [0016](../../docs/adr/0016-premium-name-single-door.md) | 正版名字只有一扇门 |

## 几条最关键的

**Java 8 字节码** —— 1.12.2 运行在 Java 8，26.3 要求 Java 25。同一个 jar 要同时活在两者之上。

**没有密码** —— 密码是玩家自编的凭据，服务器只能记住它，无法验证它对应谁。

**失败即断开** —— 认证失败不降级为离线 UUID。静默兜底会把一次认证故障变成一次身份绕过。

**宁可拒绝启动** —— 版本不支持、数据库不可达、服务端在代理之后，都直接说明原因并停止。
