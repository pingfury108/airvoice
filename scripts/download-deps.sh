#!/usr/bin/env bash
# 下载大体积依赖：sherpa-onnx AAR + SenseVoice-Small int8 模型 + silero-vad
# 在本地或远端项目根目录执行均可（幂等，已存在则跳过）
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VER=1.13.6
AAR="app/libs/sherpa-onnx-${SHERPA_VER}.aar"
MODEL_DIR="app/src/main/assets/models"
SENSE_VOICE_PKG="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
RELEASE_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p app/libs "$MODEL_DIR"

if [ ! -f "$AAR" ]; then
    echo "==> 下载 sherpa-onnx AAR v${SHERPA_VER} (~47MB)"
    curl -fL --retry 3 -o "$AAR" "${RELEASE_BASE}/v${SHERPA_VER}/sherpa-onnx-${SHERPA_VER}.aar"
else
    echo "==> AAR 已存在，跳过"
fi

if [ ! -f "$MODEL_DIR/model.int8.onnx" ]; then
    echo "==> 下载 SenseVoice-Small int8 (~156MB)"
    curl -fL --retry 3 -o /tmp/sense-voice.tar.bz2 \
        "${RELEASE_BASE}/asr-models/${SENSE_VOICE_PKG}.tar.bz2"
    tar -xjf /tmp/sense-voice.tar.bz2 -C /tmp
    cp "/tmp/${SENSE_VOICE_PKG}/model.int8.onnx" "/tmp/${SENSE_VOICE_PKG}/tokens.txt" "$MODEL_DIR/"
    rm -rf /tmp/sense-voice.tar.bz2 "/tmp/${SENSE_VOICE_PKG}"
else
    echo "==> SenseVoice 模型已存在，跳过"
fi

if [ ! -f "$MODEL_DIR/silero_vad.onnx" ]; then
    echo "==> 下载 silero-vad (~2MB)"
    curl -fL --retry 3 -o "$MODEL_DIR/silero_vad.onnx" \
        "${RELEASE_BASE}/asr-models/silero_vad.onnx"
else
    echo "==> silero-vad 已存在，跳过"
fi

echo "==> 完成"
ls -lh "$AAR" "$MODEL_DIR"/*.onnx "$MODEL_DIR"/tokens.txt
