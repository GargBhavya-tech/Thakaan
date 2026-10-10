#!/usr/bin/env bash
# Pushes the large model files to the phone's private app folder (never bundled in the APK, never downloaded by the app).
# Needs the phone connected with USB debugging and the debug build installed (run-as only works on debuggable builds).
#   tools/push_models.sh [gemma|vosk|whisper|all]   (default: all)
# Sources: assets/models/gemma-4-E2B-it-gpu.litertlm and the unzipped Vosk folders under $VOSK_DIR.
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb}"
PKG=com.thakaan
DEST=files/models
WHAT="${1:-all}"
VOSK_DIR="${VOSK_DIR:-$(dirname "$0")/../assets/models/vosk}"
TMP=/data/local/tmp/thakaan_models

"$ADB" get-state >/dev/null
"$ADB" shell "mkdir -p $TMP"
"$ADB" shell "run-as $PKG mkdir -p $DEST"

push_file() {  # local file, name
  echo "pushing $2 ($(du -h "$1" | cut -f1))"
  "$ADB" push "$1" "$TMP/$2" >/dev/null
  "$ADB" shell "run-as $PKG cp $TMP/$2 $DEST/$2 && rm $TMP/$2"
}

push_dir() {  # local dir
  local name; name="$(basename "$1")"
  echo "pushing $name/"
  "$ADB" push "$1" "$TMP/$name" >/dev/null
  "$ADB" shell "run-as $PKG rm -rf $DEST/$name; run-as $PKG cp -r $TMP/$name $DEST/$name && rm -rf $TMP/$name"
}

if [[ "$WHAT" == gemma || "$WHAT" == all ]]; then
  push_file "$(dirname "$0")/../assets/models/gemma-4-E2B-it-gpu.litertlm" gemma-4-E2B-it-gpu.litertlm
fi
if [[ "$WHAT" == vosk || "$WHAT" == all ]]; then
  for d in "$VOSK_DIR"/vosk-model-*; do [[ -d "$d" ]] && push_dir "$d"; done
fi
if [[ "$WHAT" == whisper || "$WHAT" == all ]]; then
  W="$(dirname "$0")/../assets/models/whisper-tiny"
  [[ -d "$W" ]] && push_dir "$W"
fi
echo "on the phone:"; "$ADB" shell "run-as $PKG ls -la $DEST"
