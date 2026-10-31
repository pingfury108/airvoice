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
- [x] M3 体验完善：音量条、撤回键、智能回车、十字方向键、语言锁定（auto/zh/yue/en）
- [x] M4 局域网识别 API：常驻 HTTP 服务，任意程序 POST 音频返回文字
- [-] ~~M2 伪流式~~：VAD 分句在低端机上延迟不稳定，已回退为整段识别（代码见 git 历史）

## 局域网识别 API（v0.3.0+）

App 内打开「局域网识别 API」开关后，同一 WiFi 下任意程序可将音频发到手机识别：

```bash
# WAV（PCM16，任意采样率/单双声道均可）
curl --data-binary @a.wav http://<手机IP>:8787/recognize

# 裸 PCM（s16le 16k mono，适合录音管道）
arecord -f S16_LE -r 16000 -c 1 | curl --data-binary @- "http://<手机IP>:8787/recognize?fmt=pcm"

# → {"text":"今天天气不错。","ms":820}
```

- `GET /health` 探活 → `{"ready":true}`
- 请求头 `X-Token` 鉴权（App 内设置，留空 = 不鉴权）
- 引擎串行：忙时返回 `409`，稍后重试
- `INTERNET` 权限仅用于监听本机端口，代码零出站连接；App 内可一键关闭服务

### 常见问题

| 问题 | 处理 |
|---|---|
| 连不上 | 确认调用方和手机同一网段；开关一次服务；通知栏核对 IP |
| 本机/局域网突然全不通 | 手机开了 VPN：在 VPN 里绕过局域网（192.168.0.0/16）或把空音加入分应用代理排除——VPN 会把 App 流量劫进隧道导致 LAN 不可达 |
| 401 | 请求头 `X-Token` 没带或填错 |
| text 为空 | 音频无声或太短（<0.25s） |
| 手机 IP 变了 | 路由器绑静态 IP，或重新复制通知栏地址 |

## 目录

```
app/src/main/java/com/airvoice/ime/
├── MainActivity.kt        # 引导页（权限/启用/切换/API 设置）
├── VoiceInputService.kt   # InputMethodService 入口（整段识别上屏）
├── AsrEngine.kt           # sherpa-onnx 封装（单线程串行推理，支持语言锁定重建）
├── EnginePool.kt          # 进程级共享引擎（IME 与 API 共用，模型只加载一份）
├── AudioRecorder.kt       # AudioRecord 16kHz 单声道采集
├── AsrServer.kt           # NanoHTTPD：/health /recognize（WAV/PCM 解析）
├── ResidentService.kt     # 常驻前台服务（挂载 API，通知栏显示地址）
├── BootReceiver.kt        # 开机自启（开关打开时）
└── ModelPaths.kt          # assets 模型路径 + SharedPreferences 约定
```
