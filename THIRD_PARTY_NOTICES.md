# 第三方声明（Third-Party Notices）

本文件集中列出 dsh-compose 复制或改写进源码的第三方代码与素材，以及随 App 一起分发的字体、原生库和 Linux 运行时组件。源文件头那一行 `Portions derived from …` 指向这里。

- 原生 App 的「设置 › 关于 › 开源许可」页读取 APK 内的声明与许可证；Gradle 将本文件复制为 `licenses/THIRD_PARTY_NOTICES.md`，将根目录 `LICENSE.md` 复制并重命名为 `licenses/LICENSE`；字体许可来自 `app/src/main/assets/fonts/OFL.txt`。
- Gradle / Maven 依赖按各模块的 `build.gradle.kts` 核对直接与传递依赖，这里不逐包重复；发行前仍须核验实际 APK 的许可资产，不能仅凭依赖声明认定已收录。
- 许可证全文见文末「许可证全文」。
- 本项目软件的许可见 `LICENSE.md`（GPL-3.0）。品牌标识和吉祥物美术不属于第三方，本文件不授予其使用许可。

## 一、复制或改写进源码的代码

### Phase（经作者许可使用）

| 项 | 内容 |
|---|---|
| 项目 | Phase，https://github.com/CikeSeven/phase （基于提交 `c209f6f`） |
| 版权人 | Copyright (c) 2026 CikeSeven |
| 许可 | 经作者许可使用 |
| 用途 | 手机操控：无障碍读屏与操作、控制浮层，以及「不打扰模式」的特权虚拟屏 |

上游路径前缀为 `android/app/src/main/kotlin/app/xiangyue/phase/`；本仓库路径前缀为 `app/src/main/kotlin/dev/fughetta/dshcompose/phone/`。

| 本仓库文件 | 上游来源 |
|---|---|
| `a11y/Actuator.kt` | `accessibility/AccessibilityDriver.kt`、`vision/VisualDriver.kt` |
| `a11y/ControlOverlay.kt`、`a11y/OverlayPermissionCards.kt`、`a11y/OverlayPillContent.kt`、`a11y/OverlayWindowForms.kt` | `accessibility/TaskOverlay.kt` |
| `a11y/MainScreenDriver.kt`、`a11y/MainScreenActions.kt`、`a11y/MainScreenObservation.kt` | `accessibility/AccessibilityDriver.kt` |
| `a11y/PhoneA11yService.kt` | `accessibility/PhaseAccessibilityService.kt` |
| `a11y/ScreenShooter.kt` | `vision/VisualDriver.kt` |
| `background/PrivilegedDisplayCore.kt`、`background/PrivilegedTreeConnection.kt`、`background/PrivilegedTreeRead.kt`、`background/PrivilegedInput.kt` | `shizuku/ShizukuDeviceService.kt`、`DisplayTextConnection.kt`、`DisplayTextInput.kt` |
| `background/PrivilegedDisplayEntry.kt` | `shizuku/ShizukuDeviceService.kt` |
| `background/PrivilegedProtocol.kt` | 失败原因码集合 |
| `background/ShizukuStarter.kt` | `shizuku/ShizukuConnection.kt` |
| `app/src/main/res/xml/phone_accessibility_service.xml` | `accessibility_service.xml` |

声明原文：

```text
Portions of the phone control feature are derived from Phase
(https://github.com/CikeSeven/phase), Copyright (c) 2026 CikeSeven,
used with the author's permission.
```

### PiPilot（MIT）

| 项 | 内容 |
|---|---|
| 项目 | PiPilot，https://github.com/CikeSeven/pi_pilot |
| 版权人 | Copyright (c) 2026 CikeSeven |
| 许可 | MIT |
| 用途 | 聊天界面、通用组件、抽屉外壳与主题的结构和部分实现 |

Flutter 版本中由它改写的聊天界面、通用组件、抽屉外壳与主题已移植为 `ui/` 下的 Compose 实现，上述归属继续适用。

### DeepSeek Harness（MIT）

| 项 | 内容 |
|---|---|
| 项目 | DeepSeek Harness，https://github.com/deepseek-ai/deepseek-harness |
| 版权人 | Copyright (c) 2026 DeepSeek |
| 许可 | MIT |
| 用途 | 移植官方客户端的若干算法与图形；部分组件的结构与行为参照其 Web 客户端 |

| 本仓库文件 | 上游来源 | 用途 |
|---|---|---|
| `domain/src/main/kotlin/dev/fughetta/dshcompose/domain/sessions/search/SessionSearch.kt`、`data/protocol/src/main/kotlin/dev/fughetta/dshcompose/protocol/SessionSearch.kt`、`app/src/main/kotlin/dev/fughetta/dshcompose/ui/search/SessionSearchViewModel.kt` | `@deepseek-ai/dsh-client-ui-workspace`（`deriveSearchResults`） | 对话搜索结果合并 |
| `domain/src/main/kotlin/dev/fughetta/dshcompose/domain/schedules/ScheduleTiming.kt` | `@deepseek-ai/dsh-client-ui-schedule`（`task-cron.js`） | 定时任务的 cron 解析与中文描述 |
| `data/protocol/src/main/kotlin/dev/fughetta/dshcompose/protocol/WorkspaceBinaryApi.kt` | `@deepseek-ai/dsh-client-connection`（`parseBinaryResponse`） | 二进制结果解码：有界 multipart 附件解析与文件字节窗口 |
| 随 APK 分发的 DSH Compose Host 引擎 | 独立仓库 `tsurumi-yizhou/dsh-compose-host` 的 `engine/packages/`，源自 DeepSeek Harness `dsh-v0.2.2-rc.1` | 非官方维护的引擎实现；产物保留各包的 MIT LICENSE |

此外，App 中部分组件的结构与行为参照 DeepSeek Harness 的 Web 客户端（MIT），包括消息操作、提及、输入区 dock、回合过程、产物、工作区、终端和定时任务等，不逐文件列出。

### DS Bot（Apache-2.0，已修改）

| 项 | 内容 |
|---|---|
| 项目 | DS Bot，https://github.com/Fei2411/DeepSeek-Bot （基于提交 `1bebe82`） |
| 版权人 | Copyright 2026 Fei2411 and the DS Bot contributors |
| 许可 | Apache License 2.0 |
| 用途 | 可交互回复（dsh-ui）的规范化、校验、上限、摘要、选择规则与图表刻度；DS Bot 客户端的配色与默认颜色 |

以下文件均已修改，文件头有 `Modified: …` 说明改动：

| 本仓库文件 | 上游来源 |
|---|---|
| `app/src/main/kotlin/dev/fughetta/dshcompose/ui/dynamicui/DynamicUiModel.kt` | `ds-bot/src/shared/views.js`（`normalizeView`、校验与上限、`viewSummary`） |
| `app/src/main/kotlin/dev/fughetta/dshcompose/ui/dynamicui/DynamicUiReply.kt` | `ds-bot/src/shared/views.js`（`validateOptions`） |
| `data/engine/src/main/kotlin/dev/fughetta/dshcompose/engine/runtime/GuestSetup.kt` | `ds-bot/src/host/tools.js`、`prompt.js`（`show_view` 说明与提示规则） |

DS Bot 的 NOTICE 原文附在文末。

### DSHA（MIT）

| 项 | 内容 |
|---|---|
| 项目 | DSHA，https://github.com/DSH-APP/DSHA |
| 版权人 | Copyright (c) 2026 qiannianhuanxiang |
| 许可 | MIT |
| 用途 | 不打扰模式特权进程的 `app_process` 入口 |
| 文件 | `app/src/main/kotlin/dev/fughetta/dshcompose/phone/background/PrivilegedDisplayEntry.kt` |

## 二、素材

### Phosphor Icons（MIT）

| 项 | 内容 |
|---|---|
| 项目 | Phosphor Icons 2.1.1，https://phosphoricons.com （`@phosphor-icons/core`） |
| 版权人 | Copyright (c) 2023 Phosphor Icons |
| 许可 | MIT |
| 用途 | 手机操控浮层收起键的路径 |

- `app/.../phone/a11y/ControlOverlay.kt`：收起键的路径取自 `arrows-in-simple`

### Material Icons（Apache-2.0）

| 项 | 内容 |
|---|---|
| 项目 | Material Icons，https://github.com/google/material-design-icons |
| 版权人 | Google |
| 许可 | Apache License 2.0 |
| 用途 | 桌面快捷方式与快速设置磁贴的图标；按自适应图标画布缩放、居中 |

均在 `app/src/main/res/drawable/` 下：`ic_shortcut_chat_glyph.xml`（`chat_bubble_outline_rounded`）、`ic_shortcut_new_chat_glyph.xml`（`add_comment_outlined`）、`ic_shortcut_voice_glyph.xml`（`mic_none_rounded`）、`ic_tile_new_chat.xml`（`add_comment_outlined`）。

### discourse-emojis（MIT）

| 项 | 内容 |
|---|---|
| 项目 | discourse-emojis，https://github.com/discourse/discourse-emojis |
| 版权人 | Discourse（gemspec 作者 Joffrey Jaffeux） |
| 许可 | MIT（见其 `discourse-emojis.gemspec`） |
| 用途 | 论坛表情的名称与别名表，只用数据，不含图片 |
| 文件 | `app/src/main/resources/forum/emoji.txt` |

### JetBrains Mono（SIL OFL 1.1）

| 项 | 内容 |
|---|---|
| 项目 | JetBrains Mono，https://github.com/JetBrains/JetBrainsMono |
| 版权人 | Copyright 2020 The JetBrains Mono Project Authors |
| 许可 | SIL Open Font License 1.1，全文见 `app/src/main/assets/fonts/OFL.txt` |
| 用途 | 代码字体，随 App 分发，也注入网页版 |
| 文件 | `app/src/main/assets/fonts/JetBrainsMono-*.ttf` |

## 三、随 App 分发的原生库

以下库作为 Gradle 依赖打包进 APK，未复制进源码。

| 库 | 版本 | 许可 | 版权人 | 用途 |
|---|---|---|---|---|
| jsoup | 1.24.1 | MIT | Copyright (c) 2009-2026 Jonathan Hedley | 在后台将回复中的 HTML 转为原生文档节点，不使用其网络接口；https://jsoup.org/ |
| latex（renderer、parser、base、fonts-katex） | 1.7.3 | MIT | Copyright (c) 2026 huarangmeng | 原生 Compose 数学公式解析、测量与绘制；https://github.com/huarangmeng/latex |
| KaTeX fonts | 0.16.11（由 latex-fonts-katex 分发） | MIT | Copyright (c) 2013-2020 Khan Academy and other contributors；Copyright (c) 2018 Khan Academy | 数学字形与度量；https://github.com/KaTeX/katex-fonts |
| Coil（core、Compose core、SVG） | 3.6.3 | Apache-2.0 | Coil Contributors | 回复图片异步加载、采样解码与内存缓存；https://github.com/coil-kt/coil |
| AndroidSVG（Coil SVG 的传递依赖） | 1.4 | Apache-2.0 | Paul LeBeau / AndroidSVG contributors | SVG 解码；https://github.com/BigBadaboom/androidsvg |
| Okio（Coil 的传递依赖） | 3.18.1 | Apache-2.0 | Square, Inc. | 图片文件源与资源关闭；https://github.com/square/okio |
| highlight.js | 11.12.0 | BSD-3-Clause | Copyright (c) 2006, Ivan Sagalaev | 原生代码块词法标记；上游源码、别名与词法桥在 `app/src/main/syntax/`，由 `:app:generateSyntax` 合并到 `app/build/generated/syntaxResources/syntax/highlight.js`；https://github.com/highlightjs/highlight.js |
| quickjs-kt | 1.0.18 | Apache-2.0 | Copyright 2024 dokar3 | Kotlin 异步词法解析运行时，随包 `libquickjs.so`；https://github.com/dokar3/quickjs-kt |
| QuickJS | quickjs-kt 1.0.18 随附源码 | MIT | Copyright (c) 2017-2021 Fabrice Bellard；Copyright (c) 2017-2021 Charlie Gordon | 仅运行应用自带词法器；https://github.com/bellard/quickjs |
| c-vector | quickjs-kt 1.0.18 随附源码 | MIT | Copyright (c) 2015 Evan Teran | QuickJS JNI 绑定使用的向量实现；https://github.com/eteran/c-vector |
| commonmark-java（core、GFM tables、strikethrough、task-list-items） | 0.32.0 | BSD-2-Clause | Copyright (c) 2015, Robin Stocker | 原生聊天 Markdown 解析；https://github.com/commonmark/commonmark-java |
| Ktor（Client CIO、WebSockets、HTTP CIO、ContentEncoding） | 3.6.1 | Apache-2.0 | JetBrains s.r.o. 及贡献者 | HTTP 认证、RPC、控制与中继 WebSocket、流式文件上传及 multipart 下载；https://github.com/ktorio/ktor |
| SSHJ | 0.41.2 | Apache-2.0；上游 NOTICE 含 JCraft BSD-3-Clause 告知 | Copyright 2010-2012 sshj contributors | SSH 握手、主机公钥验证、内存凭据认证、命令通道与 direct-tcpip 转发；https://github.com/hierynomus/sshj；[LICENSE](https://github.com/hierynomus/sshj/blob/v0.41.2/LICENSE)、[NOTICE](https://github.com/hierynomus/sshj/blob/v0.41.2/NOTICE) |
| Animation Android awebp / frameanimation | 3.0.5 | Apache-2.0 | Copyright 2019 Zhou Pengfei | API 26–27 启动画面动态 WebP 单次播放；纯 Java 模块，不包含 GIF/AVIF native 解码库；[LICENSE](https://github.com/penfeizhou/APNG4Android/blob/v3.0.5/LICENSE) |
| ConnectBot termlib | 0.3.15 | Apache-2.0 | Copyright 2025 Kenny Root | Compose 终端绘制、IME、选区和有界屏幕状态；AAR 分发 `libjni_cb_term.so`；https://github.com/connectbot/termlib；[LICENSE](https://github.com/connectbot/termlib/blob/0.3.15/LICENSE) |
| libvterm（termlib 内静态链接） | termlib 0.3.15 随附源码 | MIT | Copyright (c) 2008 Paul Evans &lt;leonerd@leonerd.org.uk&gt; | VT220/xterm 屏幕、ANSI 控制序列与键盘编码；[上游随附许可](https://github.com/connectbot/termlib/blob/0.3.15/lib/src/main/cpp/libvterm/LICENSE)；[链接清单](https://github.com/connectbot/termlib/blob/0.3.15/lib/src/main/cpp/CMakeLists.txt) |
| asn-one（SSHJ 传递依赖） | 0.6.0 | Apache-2.0 | asn-one contributors | SSH 密钥与证书的 ASN.1 解析；https://github.com/hierynomus/asn-one；[LICENSE](https://github.com/hierynomus/asn-one/blob/v0.6.0/LICENSE) |
| SLF4J API（SSHJ、termlib 传递依赖） | 2.0.17 | MIT | Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland) | 库的日志接口，不增加日志外发或凭据持久化；[LICENSE](https://github.com/qos-ch/slf4j/blob/v_2.0.17/LICENSE.txt) |
| kotlinx.collections.immutable | 0.5.2 | Apache-2.0 | JetBrains s.r.o. 及贡献者 | 对话历史与应用状态的结构共享集合；https://github.com/Kotlin/kotlinx.collections.immutable |
| Kotlin 标准库 | 2.4.21 | Apache-2.0 | JetBrains s.r.o. 及贡献者 | Kotlin 应用与客户端；https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | JetBrains s.r.o. 及贡献者 | 应用状态、异步 I/O 与取消；https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | 1.13.0 | Apache-2.0 | JetBrains s.r.o. 及贡献者 | JSON 协议与导航键；https://github.com/Kotlin/kotlinx.serialization |
| AndroidX Compose UI / Foundation | Compose BOM 2026.09.00 所选版本 | Apache-2.0 | The Android Open Source Project | 原生界面；https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Material 3 | 1.5.0-rc01 | Apache-2.0 | The Android Open Source Project | Material 3 Expressive 主题、控件与自适应导航 |
| AndroidX Material 3 Adaptive | 1.4.0-alpha03 | Apache-2.0 | The Android Open Source Project | 列表—详情场景与自适应布局 |
| AndroidX Navigation 3 | 1.3.0-alpha04 | Apache-2.0 | The Android Open Source Project | 原生导航与返回栈 |
| AndroidX Activity / Lifecycle | 1.13.0 / 2.11.0 | Apache-2.0 | The Android Open Source Project | Activity、ViewModel 与生命周期状态收集 |
| AndroidX DataStore Preferences | 1.3.0 | Apache-2.0 | The Android Open Source Project | 原生设置、开关与界面偏好的事务式持久化 |
| AndroidX Room | 2.8.5 | Apache-2.0 | The Android Open Source Project | 草稿、SSH 主机、媒体索引与文档缓存；https://developer.android.com/jetpack/androidx/releases/room |
| Shizuku-API（`dev.rikka.shizuku:api`、`provider`） | 13.1.5 | MIT | Copyright (c) 2021 RikkaW | 不打扰模式经 Shizuku 获得 shell 权限 |
| libadb-android | 3.1.1 | Apache-2.0（GPL-3.0-or-later 与 Apache-2.0 双许可，按 Apache-2.0 使用） | Copyright 2021 Muntashir Al-Islam | 无线调试配对、ADB 协议与 mDNS |
| spake2-android（libadb-android 的依赖） | 2.2.1 | LGPL-3.0 | Copyright 2021 Muntashir Al-Islam | 无线调试配对的 SPAKE2 |
| Conscrypt（`org.conscrypt:conscrypt-android`） | 2.9.0 | Apache-2.0 | Copyright 2016 The Android Open Source Project | libadb 的 TLS；NOTICE 原文见文末 |
| SnakeYAML Engine | 3.2 | Apache-2.0 | Copyright (c) 2018, SnakeYAML；内含转义工具 Copyright (c) 2008 Google Inc. | Agent 预设 YAML 数据读取；`!!js` 只保留为文本；https://codeberg.org/snakeyaml/snakeyaml-engine |
| ZXing Core | 3.5.4 | Apache-2.0 | Copyright 2008 ZXing authors 及各源码文件列出的贡献者 | 在设备本地编码账户登录二维码；NOTICE 见文末；https://github.com/zxing/zxing |
| Bouncy Castle（`bcprov-jdk15to18`、`bcpkix-jdk15to18`、`bcutil-jdk15to18`） | 1.87 | Bouncy Castle Licence（MIT 条款） | Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. | 自签名 ADB 客户端证书、SSH 私钥和证书解析 |
| XZ for Java（`org.tukaani:xz`） | 1.12 | 0BSD | Copyright (C) The XZ for Java authors and contributors | 解压 `.tar.xz` 运行时包 |

## 四、Linux 运行时组件

APK 内置的 Linux 运行时由 `data/engine/runtime/build-runtime.sh` 构建，以下组件来自上游二进制或软件包，各自保留许可证；构建会裁剪文件，并为 APK 命名规则修改 proot 的 DT_NEEDED 和 libtalloc 的 DT_SONAME，具体修改记录在构建报告中：原生部分的许可证文本在 APK 的 `assets/runtime/licenses/` 下，Ubuntu 软件包的版权文件保留在运行时的 `/usr/share/doc/`。

| 组件 | 版本 | 来源 | 许可 |
|---|---|---|---|
| proot | 5.1.107.102 | Termux（源码 https://github.com/termux/proot） | GPL-2.0（`licenses/proot-COPYING.txt`） |
| libtalloc | 2.5.0 | Termux | LGPL-3.0-or-later（`licenses/libtalloc-notice.txt`） |
| libandroid-shmem | 0.7 | Termux | BSD-3-Clause（`licenses/libandroid-shmem-copyright.txt`） |
| Ubuntu Base 与软件包（tzdata、ca-certificates、git 及依赖） | 24.04.5 | Canonical / Ubuntu | 各包自带，见 `/usr/share/doc/` |
| Node.js | 26.13.1 | https://nodejs.org | MIT 及其捆绑组件的许可 |
| CPython | 3.12.15 | astral-sh/python-build-standalone | PSF-2.0 及其捆绑组件的许可 |
| pnpm | 12.18.0 | npm | MIT |
| DeepSeek Harness 主包（`@deepseek-ai/dsh`） | 0.2.2-rc.3 | npm + 独立仓库 `dsh-compose-host/engine/packages/` | MIT；随 Host 产物分发 |

GPL / LGPL 组件的对应源码：proot、libtalloc、libandroid-shmem 的二进制取自 Termux 官方 apt 仓库（https://packages.termux.dev/apt/termux-main ），`data/engine/runtime/build-runtime.sh` 固定了每个 .deb 的 SHA-256。源码分别在 https://github.com/termux/proot （tag `v5.1.107.102`，构建时一并下载并核对哈希）、https://www.samba.org/ftp/talloc/ （2.5.0）、https://github.com/termux/libandroid-shmem （0.7）；Termux 的构建配方在 https://github.com/termux/termux-packages 。本项目对这三个二进制的修改只有上面说的 DT_NEEDED / DT_SONAME 改名。

### npm 传递依赖与发行核验

主包的 MIT 许可不覆盖所有传递依赖。当前默认 `data/engine/runtime/locks/dsh-0.2.2-rc.3/package-lock.json` 还锁定了以下需要单独核验的组件：

| 组件 | 固定版本 | 包声明的许可 | 发行边界 |
|---|---|---|---|
| `@deepseek-ai/libreoffice-kit` 及对应平台组件 | 0.1.5 | MPL-2.0 | 保留实际分发组件的 LICENSE/NOTICE；不能将整个 `@deepseek-ai/*` 范围归为 MIT |
| `@img/sharp-libvips-linux-arm64`、`@img/sharp-libvips-linux-x64` | 1.3.4 | LGPL-3.0-or-later | 按目标 ABI 的实际安装结果核对 libvips 及其捆绑组件许可与对应源码 |
| 其他 npm 直接及传递依赖 | 见 DSH 与 pnpm 的固定 lock | 各包各自许可 | lock 包含可选平台包，不能把全部条目当成每个 APK 都已打包；以实际 rootfs 文件清单为准 |

`data/engine/runtime/lib/rtbuild.py` 保留 Node 发行包的 `LICENSE` 为 `/opt/node/LICENSE`，保留 Ubuntu `/usr/share/doc/*/copyright`，npm 的许可文件不属于常规开发元数据裁剪项。仍须对最终归档检查许可文件和 symlink 目标是否可用，特别是 npm 包未附许可正文的情形。保留许可文本与提供 GPL/LGPL 等组件适用的对应源码是不同检查项；应为具体发行物记录版本、构建配方、修改和可获取的对应源码，不能只用项目主页或一份声明代替。

## 五、致谢（未复制代码）

- **DS Bot**：dsh-ui 组件的名称与 DS Bot 一致。

SSHJ 的完整 NOTICE 见文末；SSHJ、termlib 使用下列 Apache License 2.0 全文，libvterm 使用下列 MIT License 全文及上述版权告知。

## 许可证全文

### NOTICE：SSHJ 0.41.2（原文照录）

```text
sshj - SSHv2 library for Java
Copyright 2010-2012 sshj contributors

This product includes code derived from software developed at
The Apache Software Foundation (http://www.apache.org/):

- Apache MINA SSHD
- Apache Commons-Net


// Apache Mina SSHD notice

   =========================================================================
   ==  NOTICE file for use with the Apache License, Version 2.0,          ==
   ==  in this case for the SSHD distribution.                            ==
   =========================================================================

   This product contains software developed by JCraft,Inc. and subject to
   the following license:

Copyright (c) 2002,2003,2004,2005,2006,2007,2008 Atsuhiko Yamanaka, JCraft,Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

  1. Redistributions of source code must retain the above copyright notice,
     this list of conditions and the following disclaimer.

  2. Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in
     the documentation and/or other materials provided with the distribution.

  3. The names of the authors may not be used to endorse or promote products
     derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JCRAFT,
INC. OR ANY CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
--------------------------------------------------------------------------------
Copyright (c) 2000 - 2006 The Legion Of The Bouncy Castle (http://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in the
Software without restriction, including without limitation the rights to use, copy,
modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
and to permit persons to whom the Software is furnished to do so, subject to the
following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### MIT License

适用于以下版权声明：

```text
Copyright (c) 2026 CikeSeven                                   (PiPilot)
Copyright (c) 2026 DeepSeek                                    (DeepSeek Harness)
Copyright (c) 2026 qiannianhuanxiang                           (DSHA)
Copyright (c) 2018 Civilized Discourse Construction Kit, Inc.  (discourse_docker)
Copyright (c) 2023 Phosphor Icons                              (Phosphor Icons)
Copyright (c) 2021 RikkaW                                      (Shizuku-API)
Copyright (c) 2017-2021 Fabrice Bellard                        (QuickJS)
Copyright (c) 2017-2021 Charlie Gordon                         (QuickJS)
Copyright (c) 2015 Evan Teran                                  (c-vector)
Copyright (c) 2026 huarangmeng                                 (latex)
Copyright (c) 2009-2026 Jonathan Hedley                        (jsoup)
Copyright (c) 2013-2020 Khan Academy and other contributors     (KaTeX fonts)
Copyright (c) 2018 Khan Academy                                (KaTeX font sources)
Copyright (c) 2008 Paul Evans <leonerd@leonerd.org.uk>          (libvterm)
Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)              (SLF4J API)
```

discourse-emojis 上游未附 LICENSE 文件与版权行，按其 gemspec 声明的 MIT 使用。

```text
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Apache License 2.0

适用于 DS Bot、Material Icons、libadb-android、Conscrypt、quickjs-kt（Copyright 2024 dokar3）、Coil、AndroidSVG、Okio、Ktor、Kotlin、kotlinx.coroutines、kotlinx.serialization，以及 AndroidX 依赖。

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
```

### NOTICE：DS Bot（提交 `1bebe82`，原文照录）

```text
DS Bot
Copyright 2026 Fei2411 and the DS Bot contributors

This product is licensed under the Apache License, Version 2.0 (see LICENSE).

==============================================================================
Third-party code in this repository
==============================================================================

DeepSeek Harness (https://github.com/deepseek-ai/deepseek-harness)
MIT License, Copyright (c) 2026 DeepSeek

  - ds-bot/dev/desktop-linux/launch.mts follows the steps of
    apps/desktop/scripts/dev.ts in DeepSeek Harness and reuses a few of its
    lines (the release record and the Electron launch arguments). It is a
    developer script for screenshots on Linux and is not in the npm package.
  - app/src/host.mjs and app/src/profile.mjs follow
    apps/desktop-host/src/index.ts: the profile boot and the ready, fatal and
    shutdown messages.
  - app/src/host-process.mjs follows apps/desktop/src/host-process.ts: the
    Host in Electron's Node mode and the shutdown escalation.
  - app/src/main.mjs follows apps/desktop/src/main.ts for the Windows window
    options (the caption-button overlay, its colors and the native theme). app/src/preload.cjs adapts
    apps/desktop/src/preload-windows.ts, apps/desktop/src/preload-platform.ts
    and apps/desktop/src/preload-theme.ts: the caption-band marks, the
    fullscreen mark, the caption-color measure and the theme sync.
  - app/src/bot-browser.mjs adapts apps/desktop/src/browser-guests.ts, and
    the browser part of app/src/preload.cjs follows
    apps/desktop/src/preload-browser.ts: the webview leases, the attachment
    checks and fixed guest preferences, the session policy and the new-window
    requests.
  - dsh-compose-host/bundles/dsh-compose-app/cordis.patch.yml and its presets are
    derived from @deepseek-ai/dsh-web-app 0.2.2-rc.1 (MIT), with the official
    browser frontend and dsh-client-ui-* rows removed. The upstream MIT
    copyright and license below apply to these derived files.
    dsh-compose-host/bundles/dsh-compose-app/lib/default-model.js adapts
    @deepseek-ai/dsh-agent-default-model 0.2.2-rc.1 (MIT) and adds the
    `rememberLastSelection` mode.
  - app/engine/cordis.patch.yml follows the bundle patch of
    @deepseek-ai/dsh-web-app (packages/bundle/web-app/cordis.patch.yml): the
    transport and host-service rows and the agent-plane rows it turns off.
    app/engine/preset-standard.patch.yml copies its standard preset
    (presets/standard.patch.yml), including the plan-mode prompt text,
    without the goal rows.
  - .agents/skills/record-browser-gif/ comes from the skill of the same name
    in DeepSeek Harness (.agents/skills/record-browser-gif/): SKILL.md is
    adapted and translated for this repository; scripts/encode_gif.py and
    scripts/test_encode_gif.py are copied unchanged.

  Full license text:

  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE.

No other third-party code is copied into this repository. At run time the
plugin uses the @deepseek-ai/* packages of the DeepSeek Harness install it
runs in; they are not bundled. esbuild is a build tool only. The desktop app
in app/ installs the pinned @deepseek-ai/* packages, Electron and pnpm from
npm; they keep their own licenses. The build of its window (ui) bundles,
from npm, React and React DOM (MIT, Copyright (c) Meta Platforms, Inc. and
affiliates), and react-markdown and remark-gfm with the packages they use
(MIT, most of them Copyright (c) Titus Wormer; @ungap/structured-clone is ISC,
Copyright (c) Andrea Giammarchi). Vite is a build tool only.

==============================================================================
Projects that gave us ideas (no code copied)
==============================================================================

We thank these projects for the designs they published. DS Bot learned from
their ideas; it does not contain their code.

  - AgentMemoryRepo (https://github.com/AgentMemoryRepo/agentmemoryrepo), MIT:
    the Markdown format of Bot memory (one entry per line with [key: value]
    metadata, a MEMORY.md index that links topic files).
  - OpenAI Codex (https://github.com/openai/codex), Apache-2.0:
    keeping the user's messages next to the summary in a checkpoint, and
    message anchors for searching earlier chat.
  - OpenMausBot (https://github.com/milind-soni/OpenMausBot), Apache-2.0:
    group chat rounds and @mention relays between Bots.
  - Hermes Agent (https://github.com/NousResearch/hermes-agent) and
    Hermes Bot Mode (https://github.com/NousResearch/Hermes-Bot-Mode), MIT:
    one Session per member per group chat, and the list of earlier user
    messages that the plugin, not the model, writes into the handoff.
  - OpenCode (https://github.com/sst/opencode), MIT;
    goose (https://github.com/block/goose), Apache-2.0;
    pi (https://github.com/badlogic/pi-mono), MIT;
    Gemini CLI (https://github.com/google-gemini/gemini-cli), Apache-2.0:
    when to condense a long conversation, and what a handoff note holds.
  - obelisk (https://github.com/tommy0103/obelisk), AGPL-3.0:
    searching a Bot's own chat with a SQLite FTS5 index ranked by BM25.
  - DeepSeek Harness Voice Input plugin
    (packages/experimental/client-ui-voice-input), MIT: the voice chat
    records with the same steps (record, resample to 16 kHz mono, PCM16 WAV)
    before it calls the plugin's speech service.
```

### NOTICE：Conscrypt 2.8.0（原文照录）

```text
Copyright 2016 The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

-----------------------------------------------------------------------
This product contains a modified portion of `Netty`, a configurable network
stack in Java, which can be obtained at:

  * LICENSE:
    * licenses/LICENSE.netty.txt (Apache License 2.0)
  * HOMEPAGE:
    * http://netty.io/

This product contains a modified portion of `Apache Harmony`, modular Java runtime,
which can be obtained at:

  * LICENSE:
    * licenses/LICENSE.harmony.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://harmony.apache.org/
```

### Bouncy Castle Licence

```text
Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### BSD Zero Clause License（XZ for Java）

```text
Copyright (C) The XZ for Java authors and contributors

Permission to use, copy, modify, and/or distribute this
software for any purpose with or without fee is hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL
WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL
THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR
CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

### 其他许可证

- SIL Open Font License 1.1：`app/src/main/assets/fonts/OFL.txt`
- GNU LGPL-3.0（spake2-android、libtalloc）：https://www.gnu.org/licenses/lgpl-3.0.txt ；配套 GNU GPL-3.0 全文：https://www.gnu.org/licenses/gpl-3.0.txt 。当前 App 许可页显示本声明、项目 GPL-3.0 许可证和字体 OFL；LGPL-3.0 全文通过上述链接提供。
- GNU GPL-2.0（proot）：随 APK 附带于 `assets/runtime/licenses/proot-COPYING.txt`

### BSD 2-Clause License（commonmark-java）

Copyright (c) 2015, Robin Stocker
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

### BSD 3-Clause License（highlight.js）

BSD 3-Clause License

Copyright (c) 2006, Ivan Sagalaev.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

### ZXing 3.5.4 NOTICE

```text
--------------------------------------------------------------------------------
NOTICES FOR BARCODE4J
--------------------------------------------------------------------------------

Barcode4J
Copyright 2002-2010 Jeremias Märki
Copyright 2005-2006 Dietmar Bürkle

Portions of this software were contributed under section 5 of the
Apache License. Contributors are listed under:
http://barcode4j.sourceforge.net/contributors.html
--------------------------------------------------------------------------------
NOTICES FOR JCOMMANDER
--------------------------------------------------------------------------------

Copyright 2010 Cedric Beust cedric@beust.com
```
