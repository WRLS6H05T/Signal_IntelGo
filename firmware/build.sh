#!/usr/bin/env bash
# Builds the two sensor images for a bare C3/C5 board from the ESP-IDF project in
# firmware/SignalSensor. The app does not flash them - a board arrives running Marauder or
# GhostESP - so the output stays out of the app.
#
#   firmware/build.sh            both chips
#   firmware/build.sh c3         one chip
#
# Needs an ESP-IDF checkout on IDF_PATH (v5.5 or newer; the C5 and its band mode arrived there)
# with the toolchain for the target installed:
#
#   . "$IDF_PATH/export.sh"
#   firmware/build.sh
#
# Each image is the merged binary IDF produces (`idf.py merge-bin`), which already places the
# bootloader at the offset the chip wants - 0x0 on the C3, 0x2000 on the C5 - the partition table
# at 0x8000 and the application at 0x10000, so one `esptool write-flash 0x0` brings a bare board
# up. The partition table is firmware/SignalSensor/partitions.csv: one 3 MB factory app, because
# Wi-Fi, BLE and GPS do not fit the default 1.2 MB OTA slot and updates arrive over the cable.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project="$here/SignalSensor"
out="$here/out"
build="$here/build"
mkdir -p "$out" "$build"

# A shell that has not sourced export.sh yet still gets a usable idf.py, from whichever install
# this machine has, instead of a "command not found" that says nothing about what is missing.
if [ -z "${IDF_PATH:-}" ] || ! command -v idf.py >/dev/null 2>&1; then
  for candidate in "$HOME/esp/esp-idf" "/c/esp/esp-idf-v6.1" "/opt/esp-idf" "$HOME/esp-idf"; do
    if [ -f "$candidate/export.sh" ]; then
      # shellcheck disable=SC1091
      . "$candidate/export.sh" >/dev/null
      break
    fi
  done
fi
if ! command -v idf.py >/dev/null 2>&1; then
  echo "idf.py not found: source \$IDF_PATH/export.sh first" >&2
  exit 1
fi

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=(c3 c5)
fi

for chip in "${targets[@]}"; do
  case "$chip" in
    c3) target="esp32c3"; merged="esp32c3.bin" ;;
    c5) target="esp32c5"; merged="esp32c5.bin" ;;
    *) echo "unknown chip: $chip (expected c3 or c5)" >&2; exit 2 ;;
  esac

  echo "== $chip ($target)"
  # A per-target build directory and sdkconfig: the two chips share one project, and letting them
  # share a build tree is how a C3 image ends up linked with a C5 sdkconfig.
  idf.py -C "$project" \
    -B "$build/$chip" \
    -D "IDF_TARGET=$target" \
    -D "SDKCONFIG=$build/$chip/sdkconfig" \
    -D "SDKCONFIG_DEFAULTS=$project/sdkconfig.defaults" \
    build merge-bin

  merged_bin="$build/$chip/merged-binary.bin"
  [ -f "$merged_bin" ] || { echo "missing build output: $merged_bin" >&2; exit 1; }
  cp "$merged_bin" "$out/$merged"
  echo "wrote $out/$merged ($(wc -c < "$out/$merged") bytes)"
done

# A manifest of sizes and hashes, so an image can be checked before it is written.
python - "$out" "$here" <<'PY'
import hashlib, json, os, sys, datetime
out, here = sys.argv[1], sys.argv[2]
manifest = {"version": "1.3.0", "built": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"), "images": {}}
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
