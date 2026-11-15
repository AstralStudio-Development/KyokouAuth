# 开发者 API

两个事件，均在异步线程触发。

## KyokouAuthLoginEvent

在连接被解析为某个身份后、进入世界之前触发。可取消。

```kotlin
@EventHandler
fun onLogin(event: KyokouAuthLoginEvent) {
    if (isBanned(event.uuid)) {
        event.isCancelled = true
    }
}
```

| 属性 | 类型 | 说明 |
|---|---|---|
| `uuid` | `UUID` | 该连接的最终身份 |
| `name` | `GameName` | 名字 |
| `firstBind` | `Boolean` | 本次是否首次建立绑定 |
| `requiresVerification` | `Boolean` | 是否仍需验证（将进入 Limbo） |
| `trusted` | `Boolean` | 是否已由握手或有效会话证明 |
| `record` | `BoundIdentity?` | 已有绑定记录 |

取消会断开该玩家，并显示 `login.refused-by-plugin` 消息。

!!! warning "异步线程"
    该事件在异步线程触发，监听器不得访问世界、实体或调用同步 API。

## KyokouAuthVerifyEvent

在验证成功、身份绑定完成后触发。不可取消。

```kotlin
@EventHandler
fun onVerified(event: KyokouAuthVerifyEvent) {
    logger.info("${event.name} verified as ${if (event.premium) "premium" else "microsoft"}")
}
```

| 属性 | 类型 | 说明 |
|---|---|---|
| `uuid` | `UUID` | 身份 UUID |
| `name` | `GameName` | 名字 |
| `premium` | `Boolean` | 是否通过 Mojang 握手验证 |
| `gamertag` | `String?` | Xbox 玩家代号，正版玩家为 null |
| `record` | `BoundIdentity` | 绑定记录 |

## 依赖

```kotlin
dependencies {
    compileOnly("moe.ryuu:kyokouauth-api:<version>")
}
```

插件未安装时监听器不会注册，事件也不会触发。
