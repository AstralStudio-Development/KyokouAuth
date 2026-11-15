# 登录注入层：按需接管，失败即退回

ADR-0004 决定自研登录注入层，ADR-0009 指出它同时卡住「正版零操作登录」和「1.8–1.12 支持」。本次落地了第一半。

## 实现了什么

在 Paper 的 channel 初始化钩子上，给每个连接插入一个 Netty 处理器，位置在 `splitter` 之后。它把握手流程拆成两条互斥的路：

- **名字被 Mojang 持有**：扣住 `Login Start`，自己发 `Encryption Request`，收到 `Encryption Response` 后解密共享密钥、校验 verify token、算出 session hash、向 `sessionserver.mojang.com` 复核。确认后装上 AES/CFB8 加解密、把验证过的 Mojang 身份交给登录闸门，再把扣住的 `Login Start` 放行给原版。
- **其他名字**：直接放行，走已有的设备码路径。

## 三个踩到的真问题

1. **`Handshake` 与 `Login Start` 的包 id 都是 `0x00`**，而 `Handshake` 先到。把第一个 `0x00` 当成登录包会读出版本号当名字——症状是「服务器无故掉线」，日志里什么都没有。首次实测就是两条路径全挂。
2. **Mojang 目录查询是阻塞 HTTP**，不能跑在 event loop 上——那会拖垮所有其他连接。改成 `autoRead=false` 暂停该连接、异步查完再恢复。
3. **`sharedSecret` 忘了保存**，`installEncryption` 会拿着 null 静默返回，客户端在加载界面永久卡住。

## 已验证的边界

用两个自研探针实测（Paper 26.3）：

| 场景 | 结果 |
|---|---|
| `ProbeFree`（Mojang 无主） | `login_success`，走设备码，**无回归** |
| `notch`（Mojang 持有） | 收到 `encryption_request`，握手被接管 |
| 伪客户端完成 RSA 握手但无真实 Mojang 会话 | 被拒绝，收到**本地化**的中文提示 |

第三条覆盖了拒绝路径的全程：`Encryption Request` 的密钥长度正确（162B = 1024-bit DER）、探针用服务端公钥加密并反转字节、服务端 RSA 解密成功、verify token 校验通过、session hash 计算、`hasJoined` 真实调用、最终断开。

**未验证的只剩成功路径**：Mojang 确认会话后装 AES 加密、把验证过的身份交给登录闸门、绑定 Mojang UUID。这需要一个持有真实微软账户的正版客户端，本环境不具备。因此 `premium.prefer-handshake` 是独立开关，关闭时行为与之前完全一致。

## 一个只有实测才能发现的缺陷

拒绝时最初只是 `ctx.close()`。服务端日志显示 "lost connection: Disconnected"，客户端会看到"连接丢失"——与崩溃无法区分，玩家得不到任何可操作的提示。改为先写 Login Disconnect 包再关闭，并让提示文本走消息目录而非硬编码英文。单元测试无法覆盖这类问题，因为它只在真实线路上表现出来。

## 关于 1.8–1.12

这一层是 1.8 支持的前提（`AsyncPlayerPreLoginEvent#setPlayerProfile` 在 1.8.8 上不存在），但还不够：1.8.8 上 `PlayerSwapHandItemsEvent` 不存在、`AsyncChatEvent` 是 Paper 的、且完全没有 Adventure。监听器需要按能力拆分、消息需要 BungeeChat 分支。本次未做。
