# 皮肤

## 来源顺序

插件按以下顺序尝试，取第一个可用结果：

**1. Mojang 皮肤**
玩家拥有 Java 版时自动取得，带签名。

**2. 基岩版皮肤**
玩家在基岩版玩过时，从 Geyser 的注册表取得。

**3. 管理员指定**
`skins.yml` 中配置的 PNG 地址，无签名但客户端会渲染。

## skins.yml

```yaml
Steve: https://example.com/skins/steve.png
Alex: https://example.com/skins/alex.png
```

键为 Minecraft 名字（不区分大小写），值为直接指向 PNG 的 URL。

未在此列出的玩家使用默认皮肤。

## SkinsRestorer

检测到 SkinsRestorer 时，本插件的皮肤逻辑完全让位：

```
[KyokouAuth] SkinsRestorer 接管皮肤
```

两者都写同一个 profile 属性，同时工作只会互相覆盖。

## 已知限制

**已关联到 Java 身份的基岩玩家显示默认皮肤。** Floodgate 在登录阶段写入自己的默认纹理，事件中设置的皮肤会被丢弃。

**Mojang 接口限流。** 皮肤查询遇到 429 后会静默 60 秒，期间的登录不取皮肤。这只影响单个玩家的显示，不影响认证。
