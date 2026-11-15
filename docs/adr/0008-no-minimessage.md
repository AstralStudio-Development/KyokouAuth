# 消息渲染不使用 MiniMessage

所有面向玩家的文本走项目自带的 `RichText` 解析器，再用 Adventure 的核心类型组装成 Component。**不使用 MiniMessage**。

这不是偏好，是实测出来的硬约束。最初的消息层用的就是 MiniMessage，在 Paper 26.3 上一切正常；换到 Paper 1.16.5 实跑时直接 `ClassNotFoundException: net.kyori.adventure.text.minimessage.MiniMessage` —— Paper 从 1.16.5 起内置 Adventure，但**不内置 MiniMessage 解析器**。依赖它等于把插件的地板从 1.16.5 悄悄抬到 1.20.6，丢掉三分之一的版本范围，换来的只是标签语法的书写便利。

`RichText` 支持的子集就是插件消息实际用到的全部：颜色标签、四种装饰、`<newline>`、`<click:open_url:…>`。未知标签按字面文本处理而不是报错——一个翻译笔误不该让插件连踢出原因都打不出来。解析结果是与平台无关的 `Span` 列表，因此可以在没有服务端的情况下完整测试。

## 连带发现的第二个问题

自检还抓到 `NoSuchMethodError: TextComponent$Builder.build()`。Adventure 4.x 声明 `TextComponent build()`，5.x 声明 `Component build()`，JVM 按精确描述符链接，编译于 5.x、运行于 4.x 必然抛错。所以组件构建完全绕开 `Component.text()` 的 builder：用 `Component.empty()` 逐段 `append`，这个签名两代一致。

## 为什么要有启动自检

这两个问题都**不会被类加载发现**——消息类只有在第一次真正渲染时才链接，而那正是有玩家被踢出的时刻。因此插件在 `onEnable` 里渲染一条完整消息（组件形式 + 纯文本形式），失败就禁用自己并打印原因。宁可启动失败并说明原因，也不要带着一个"所有玩家都会被踢出且看不见原因"的状态运行。

启动自检的价值在本次已经被证明：它把两个只在特定版本出现的崩溃，从"玩家第一次进服时才发现"提前到了"服主刚装好就看见"。
