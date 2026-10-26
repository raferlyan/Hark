#!/usr/bin/env bash
# 下载 sherpa-onnx 模型并推送到手机 /sdcard/Android/data/<pkg>/files/models/
#
#   tools/push_models.sh            # 推全部模型（约 900MB 解压后）
#   tools/push_models.sh kws asr    # 只推唤醒 + 普通话流式
#
# 可选组：kws asr tele sensevoice vad speaker（声纹，可选）
set -euo pipefail

PKG=com.raferlyan.hark
DL=${MODEL_CACHE:-$HOME/sherpa-dl}
DST=/sdcard/Android/data/$PKG/files/models
B=https://github.com/k2-fsa/sherpa-onnx/releases/download

model_of() {
  case "$1" in
    kws) echo kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile ;;
    asr) echo asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30 ;;
    tele) echo asr-models/sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04 ;;
    sensevoice) echo asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 ;;
    *) echo "unknown group: $1" >&2; exit 1 ;;
  esac
}

groups=("$@")
[ $# -eq 0 ] && groups=(kws asr tele sensevoice vad speaker)

mkdir -p "$DL"
adb shell mkdir -p "$DST"

for g in "${groups[@]}"; do
  if [ "$g" = speaker ]; then
    f=3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
    # release tag 本身就拼错成了 recongition
    [ -f "$DL/$f" ] || curl -fSL -C - -o "$DL/$f" "$B/speaker-recongition-models/$f"
    adb push "$DL/$f" "$DST/"
    continue
  fi
  if [ "$g" = vad ]; then
    [ -f "$DL/silero_vad.onnx" ] || curl -fSL -o "$DL/silero_vad.onnx" "$B/asr-models/silero_vad.onnx"
    adb push "$DL/silero_vad.onnx" "$DST/"
    continue
  fi
  rel=$(model_of "$g")
  dir=$(basename "$rel")
  if [ ! -d "$DL/$dir" ]; then
    curl -fSL -o "$DL/$dir.tar.bz2" "$B/$rel.tar.bz2"
    tar xjf "$DL/$dir.tar.bz2" -C "$DL"
  fi
  # 只推运行需要的文件，test_wavs/README/fp32 encoder 等不推
  adb shell mkdir -p "$DST/$dir"
  for f in "$DL/$dir"/*.onnx "$DL/$dir"/tokens.txt; do
    case "$(basename "$f")" in
      encoder-epoch-12-avg-2-chunk-16-left-64.onnx) continue ;;  # KWS 用 int8 encoder
    esac
    adb push "$f" "$DST/$dir/"
  done
done

adb shell ls -l "$DST" "$DST"/*
