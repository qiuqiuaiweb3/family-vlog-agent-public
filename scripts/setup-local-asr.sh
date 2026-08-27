#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
asset_root="$repo_root/.local-asr/assets"
model_name="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
model_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${model_name}.tar.bz2"
vad_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

if [[ -L "$repo_root/.local-asr" || -L "$asset_root" || -L "$asset_root/$model_name" ]]; then
    echo "本地语音模型目录不得为符号链接" >&2
    exit 1
fi

temp_root=$(mktemp -d)
if [[ -z "$temp_root" || "$temp_root" != /tmp/* || ! -d "$temp_root" || -L "$temp_root" ]]; then
    echo "无法建立安全的临时目录" >&2
    exit 1
fi

cleanup() {
    if [[ -n "$temp_root" && "$temp_root" == /tmp/* && -d "$temp_root" && ! -L "$temp_root" ]]; then
        rm -r -- "$temp_root"
    fi
}
trap cleanup EXIT

archive="$temp_root/model.tar.bz2"
curl --fail --location --proto '=https' --tlsv1.2 "$model_url" --output "$archive"
curl --fail --location --proto '=https' --tlsv1.2 "$vad_url" --output "$temp_root/silero_vad.onnx"

tar -xjf "$archive" -C "$temp_root"
extracted="$temp_root/$model_name"
for required in "$extracted/model.int8.onnx" "$extracted/tokens.txt" "$temp_root/silero_vad.onnx"; do
    if [[ ! -f "$required" || -L "$required" || ! -s "$required" ]]; then
        echo "缺少本地语音模型制品：$required" >&2
        exit 1
    fi
done

mkdir -p "$asset_root/$model_name"
install -m 0644 "$extracted/model.int8.onnx" "$asset_root/$model_name/model.int8.onnx"
install -m 0644 "$extracted/tokens.txt" "$asset_root/$model_name/tokens.txt"
install -m 0644 "$temp_root/silero_vad.onnx" "$asset_root/silero_vad.onnx"

echo "本地语音模型已准备到 $asset_root"
