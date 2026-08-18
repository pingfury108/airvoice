模型文件放这里（本目录内容不进入 git，也不被 rdev 同步）。

在目标机器上执行即可自动下载：
    bash scripts/download-deps.sh

需要的文件：
    model.int8.onnx   — SenseVoice-Small int8（ASR 主模型）
    tokens.txt        — 词表
    silero_vad.onnx   — VAD（M2 伪流式使用）
