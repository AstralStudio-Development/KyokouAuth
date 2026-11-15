# 握手成功路径已验证

之前说「握手成功路径无法验证」是过早放弃。它需要三样东西，其中只有一样真的不可得：

| 条件 | 是否可得 |
|---|---|
| 真实的微软账户 + 正版客户端 | **不可得** |
| Mojang 的 `hasJoined` 确认 | 可用本地桩代替 |
| 其余全部（RSA、token 校验、会话哈希、装加密、放行、绑定） | 普通代码，可测 |

所以把「向 Mojang 确认」这一步做成可配置的地址（`premium.session-server-url`，默认仍是真 Mojang），配一个返回固定 JSON 的本地桩，就只剩那一个 HTTP GET 的**内容**是模拟的——它的请求形状完全一致，日志里能看到实际发出的参数。

## 实测

`HandshakeProbe` 连接 → 服务端发 `Encryption Request` → 探针用服务端公钥 RSA 加密共享密钥与 token 并反转字节 → 服务端解密、校验 token、算会话哈希 → 查询本地桩 → 确认 → 装 AES/CFB8 → 放行被扣住的 `Login Start`：

```
ENCRYPTION_REQUEST received
  serverId="" publicKey=162B verifyToken=4B
ENCRYPTION_RESPONSE sent
ENCRYPTION enabled
COMPRESSION=256
RESULT=login_success
UUID=00000000-0000-0000-0000-00000000beef
NAME=notch
```

桩收到的请求：

```
HAS_JOINED username=notch serverId=-66e6204a02e17d5cc4c3cc9aa00434ac53aad1c0
```

`serverId` 开头的 **`-`** 是关键证据——它证明会话哈希用的是有符号十六进制。这正是 `MojangSessionTest` 里专门测的那个格式，现在在真实线路上得到印证：用无符号实现的话，存量约一半的会话会得到一个不带负号的哈希，与客户端算的不一致。

`UUID` 是桩返回的值，说明：profile 被存入 → 在 pre-login 被消费 → `bindPremium` 绑定 → `setPlayerProfile` 写入 → 出现在 `Login Success` 的线路上。整条链闭合。

## 仍然不可验证的唯一一点

真实 Mojang 对真实客户端返回真实 profile。这一步没有可替代的做法，需要：

1. 一台装着本插件的服务器（`premium.session-server-url` 用默认值）
2. 官方启动器登录一个拥有 Java 版的正版账户
3. 直接进服，不该出现任何提示，且 `/kyokouauth whois <你的名字>` 显示的 UUID 应等于 `https://api.mojang.com/users/profiles/minecraft/<你的名字>` 返回的 id

这条验证只能在有账户的机器上做。
