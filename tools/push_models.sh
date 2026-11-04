#!/usr/bin/env bash
# 下载 sherpa-onnx 模型并推送到手机 /sdcard/Android/data/<pkg>/files/models/
#
#   tools/push_models.sh            # 推全部模型（约 900MB 解压后）
#   tools/push_models.sh kws asr    # 只推唤醒 + 普通话流式
#   tools/push_models.sh asr-small  # 弱 CPU 用的小一号普通话流式模型
#
# 可选组：kws asr tele sensevoice vad speaker（声纹，可选）
# 备用普通话流式模型（设置里「普通话流式模型」切换，不在默认全推里）：asr-small asr-tiny asr-ctc
#
# 预置进系统镜像时不推手机，拷到本地目录（aosp/hark.mk 从这里打包到 /product/etc/hark/models）：
#   OUT=aosp/models tools/push_models.sh kws asr
set -euo pipefail

PKG=com.raferlyan.hark
DL=${MODEL_CACHE:-$HOME/sherpa-dl}
DST=/sdcard/Android/data/$PKG/files/models
B=https://github.com/k2-fsa/sherpa-onnx/releases/download

model_of() {
  case "$1" in
    kws) echo kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile ;;
    asr) echo asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30 ;;
    asr-small) echo asr-models/sherpa-onnx-streaming-zipformer-multi-zh-hans-int8-2023-12-13 ;;
    asr-tiny) echo asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile ;;
    asr-ctc) echo asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01 ;;
    tele) echo asr-models/sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04 ;;
    sensevoice) echo asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 ;;
    *) echo "unknown group: $1" >&2; exit 1 ;;
  esac
}

groups=("$@")
[ $# -eq 0 ] && groups=(kws asr tele sensevoice vad speaker)

mkdir -p "$DL"

# OUT 设置了就拷到本地目录，否则 adb push 到手机
if [ -n "${OUT:-}" ]; then
  DST=$OUT
  mk() { mkdir -p "$1"; }
  put() { cp "$1" "$2"; }
else
  mk() { adb shell mkdir -p "$1"; }
  put() { adb push "$1" "$2"; }
fi
mk "$DST"

for g in "${groups[@]}"; do
  if [ "$g" = speaker ]; then
    f=3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
    # release tag 本身就拼错成了 recongition
    [ -f "$DL/$f" ] || curl -fSL -C - -o "$DL/$f" "$B/speaker-recongition-models/$f"
    put "$DL/$f" "$DST/"
    continue
  fi
  if [ "$g" = vad ]; then
    [ -f "$DL/silero_vad.onnx" ] || curl -fSL -o "$DL/silero_vad.onnx" "$B/asr-models/silero_vad.onnx"
    put "$DL/silero_vad.onnx" "$DST/"
    continue
  fi
  rel=$(model_of "$g")
  dir=$(basename "$rel")
  if [ ! -d "$DL/$dir" ]; then
    curl -fSL -o "$DL/$dir.tar.bz2" "$B/$rel.tar.bz2"
    tar xjf "$DL/$dir.tar.bz2" -C "$DL"
  fi
  # 只推运行需要的文件，test_wavs/README/fp32 encoder 等不推
  mk "$DST/$dir"
  for f in "$DL/$dir"/*.onnx "$DL/$dir"/tokens.txt; do
    case "$(basename "$f")" in
      encoder-epoch-12-avg-2-chunk-16-left-64.onnx) continue ;;  # KWS 用 int8 encoder
      encoder-epoch-99-avg-1.onnx) continue ;;  # zh-14M 用 int8 encoder
    esac
    put "$f" "$DST/$dir/"
  done
done

if [ -n "${OUT:-}" ]; then ls -l "$DST" "$DST"/*; else adb shell ls -l "$DST" "$DST"/*; fi
