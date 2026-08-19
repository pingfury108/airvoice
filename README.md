# AirVoice 空音语音输入法

完全离线、零隐私泄漏、支持中文及方言的 Android 极简语音输入法。

- 100% 端侧推理，不申请 INTERNET 权限，从系统层面保证断网可用
- ASR：sherpa-onnx + SenseVoice-Small (int8)，支持中/英/日/韩/粤语
- 仅申请 RECORD_AUDIO 一个权限

## 开发工作流（rdev 远程构建）

本地只写代码，构建/安装/调试在连接了 Android 手机的服务器上执行：

```bash
# 一次性：服务器初始化（首次会自动探测远端环境）
rdev server add dev <user@host>

# 一次性：远端安装 Android SDK + 下载模型/AAR
rdev bash scripts/setup-android-sdk.sh
rdev bash scripts/download-deps.sh

# 日常：构建并安装到手机
rdev ./gradlew assembleDebug
rdev adb install -r app/build/outputs/apk/debug/app-debug.apk

# 看日志
rdev adb logcat -s airvoice:V
```

> AAR 与模型被 .gitignore 排除，不会随 rdev 同步，需在每台构建机上各下载一次。

## 首次使用（手机上）

1. 打开空音 App → 申请录音权限
2. 启用空音输入法 → 切换为空音输入法
3. 任意输入框呼出键盘，按住说话，松手上屏（上滑取消）

## 里程碑

- [x] M1 最小可用：按住说话 → 松手整段识别上屏
- [x] M2 伪流式：silero-vad 分句，边说边出字
- [x] M3 体验完善：音量条、撤回键、智能回车、十字方向键、语言锁定（auto/zh/yue/en）

## 目录

```
app/src/main/java/com/airvoice/ime/
├── MainActivity.kt        # 引导页（权限/启用/切换）
├── VoiceInputService.kt   # InputMethodService 入口（VAD 流式分句上屏）
├── AsrEngine.kt           # sherpa-onnx 封装（单线程串行推理，支持语言锁定重建）
├── VadProcessor.kt        # silero-vad 端点检测（PCM 流 → 完整句子）
├── AudioRecorder.kt       # AudioRecord 16kHz 单声道采集
└── ModelPaths.kt          # assets 模型路径 + SharedPreferences 约定
```
