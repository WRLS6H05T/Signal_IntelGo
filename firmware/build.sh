#!/usr/bin/env bash
# Builds the two sensor images the app flashes and drops them in the app's assets.
#
#   firmware/build.sh            both chips
#   firmware/build.sh c3         one chip
#
# Needs arduino-cli with the esp32 core (3.3.x) and esptool on PATH:
#
#   arduino-cli core install esp32:esp32 \
#     --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
#   pip install esptool
#
# Each image is merged: bootloader at 0x0, partition table at 0x8000 and the application at
# 0x10000, so the app can write it in one pass at offset 0 and a bare board comes up running it. The partition scheme is Huge APP (3 MB, no OTA) because the sketch with
# Wi-Fi, BLE and GPS does not fit the default 1.2 MB OTA slot, and updates arrive over the cable
# from the phone rather than over the air.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
cli="${ARDUINO_CLI:-arduino-cli}"
esptool_bin="${ESPTOOL:-esptool}"
out="$root/app/src/main/assets/firmware"
build="$here/build"
mkdir -p "$out" "$build"

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=(c3 c5)
fi

for chip in "${targets[@]}"; do
  case "$chip" in
    c3) fqbn="esp32:esp32:esp32c3"; merged="esp32c3.bin" ;;
    c5) fqbn="esp32:esp32:esp32c5"; merged="esp32c5.bin" ;;
    *) echo "unknown chip: $chip (expected c3 or c5)" >&2; exit 2 ;;
  esac

  echo "== $chip ($fqbn)"
  "$cli" compile \
    --fqbn "$fqbn" \
    --board-options PartitionScheme=huge_app,CDCOnBoot=cdc \
    --output-dir "$build/$chip" \
    "$here/SignalSensor"

  part="$build/$chip/SignalSensor.ino"
  for f in ".bootloader.bin" ".partitions.bin" ".bin"; do
    [ -f "$part$f" ] || { echo "missing build output: $part$f" >&2; exit 1; }
  done

  # Huge APP has no OTA slots, so there is no boot_app0 to write: three pieces, one image.
  args=(--chip "esp32$chip" merge-bin -o "$out/$merged" --flash-mode dio --flash-freq 80m --flash-size 4MB)
  args+=(0x0 "$part.bootloader.bin" 0x8000 "$part.partitions.bin" 0x10000 "$part.bin")
  "$esptool_bin" "${args[@]}"

  echo "wrote $out/$merged ($(wc -c < "$out/$merged") bytes)"
done

# A manifest, so the app can show what it is about to write without parsing the image.
python - "$out" "$here" <<'PY'
import hashlib, json, os, sys, datetime
out, here = sys.argv[1], sys.argv[2]
manifest = {"version": "1.0.0", "built": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"), "images": {}}
for chip, name in (("c3", "esp32c3.bin"), ("c5", "esp32c5.bin")):
    path = os.path.join(out, name)
    if not os.path.exists(path):
        continue
    data = open(path, "rb").read()
    manifest["images"][chip] = {
        "file": name,
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
json.dump(manifest, open(os.path.join(out, "firmware.json"), "w"), indent=2)
print("manifest:", json.dumps(manifest["images"]))
PY
