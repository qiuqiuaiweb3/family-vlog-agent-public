# 第三方软件、模型与字体声明

本项目的可选本地字幕功能使用以下第三方制品。语音模型与字体由本机准备脚本下载到 Git 已忽略的目录；当前已识别并列出的许可文本作为仓库受控资产保存在 `third_party/` 并随本机 APK 打包。

当前 APK 只允许本人在自己的手机上私用侧载，不得分享、上传测试分发渠道或公开发布。现用 `sherpa-onnx 1.13.5` 预构建 AAR 静态包含 eSpeak NG/Piper 相关代码，还可识别出尚未逐项闭合许可来源的传递依赖；现用 `ass-kt 0.5.1` 的原生包静态包含 FriBidi 等组件。保存下列许可文本不表示已经解决 GPL 或 LGPL 静态组合、重新链接及全部传递依赖声明义务。公开分发前必须改用关闭 TTS 等无关功能的自构建 sherpa AAR，解决 libass 原生依赖义务，并重新做完整许可和安全审计。

## 本地语音识别

- `sherpa-onnx 1.13.5`：Apache License 2.0；项目为 https://github.com/k2-fsa/sherpa-onnx 。
- Silero VAD：MIT License；项目为 https://github.com/snakers4/silero-vad 。
- SenseVoiceSmall INT8 2024-07-17：FunASR Model Open Source License Agreement 1.1；冻结制品为 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`。
- ONNX Runtime 1.27.1：MIT License，并保留该版本完整 `ThirdPartyNotices.txt`。
- eSpeak NG：GPL-3.0-or-later。当前预构建 sherpa AAR 中存在其代码；仅保留许可文本不表示已经解决公开分发义务。
- Piper phonemize：MIT License。当前预构建 sherpa AAR 中存在相关代码。

## ASS 硬字幕与字体

- `ass-kt 0.5.1`／libass-android：MIT License。
- libass 0.17.4：ISC License。该版本只解析应用严格生成的 ASS；公开分发前仍须评估升级到修复已知问题的版本。
- libunibreak 6.1：Zlib License。
- HarfBuzz 11.3.3：Old MIT License。
- FriBidi 1.0.16：LGPL-2.1-or-later；当前 AAR 的静态组合尚未解决公开分发的重新链接义务。
- FreeType 2.13.3：FreeType Project License；本产品使用了 FreeType Project 的软件。
- fontconfig：保存其上游 `COPYING`；当前声明不把该文本冒充为预构建包精确源修订的证明。
- Expat 2.7.1：MIT License。
- LLVM libc++：Apache License 2.0 with LLVM Exception；当前声明不把仓库保存的通用许可文本冒充为预构建二进制精确版本的证明。
- Noto Sans CJK SC 2.004 Bold：SIL Open Font License 1.1；冻结字体为 `NotoSansCJKsc-Bold.otf`。

## Google ML Kit Translation

Google ML Kit Translation `17.0.3` 及其动态模型受 [Google ML Kit 条款与隐私要求](https://developers.google.com/ml-kit/terms)、[设备端翻译使用准则](https://developers.google.com/ml-kit/language/translation/translation-terms)和 [Google Translate 归属要求](https://docs.cloud.google.com/translate/attribution)管理，不是由本项目再分发的开源翻译模型。翻译模型只在手机运行时按需下载，不进入 APK。本人已选择只在应用字幕入口及结果附近显示官方 Google Translate 归属图形、链接和免责声明，不把归属烧录进字幕版 MP4，并接受独立 MP4 的品牌归属合规性仍不明确；本项目不得声称该选择已经获得 Google 的合规确认。

SenseVoice/FunASR 模型协议含有与标准宽松开源许可不同的条款；扩大用途、分发范围或版本前必须重新核对当时有效的全部协议。
