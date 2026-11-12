"""Remote ID scanner for the desk.

The phone reads Remote ID inside its own BLE scan, which a JVM cannot do: Java has no BLE
advertisement API on Windows. Python does, through bleak, and this machine already has it, so the
desk spawns this script and keeps the decoder - the same OdidParser the phone uses - on the Kotlin
side. Nothing is parsed here; the script only reports what was on air.

Output, one line per advertisement that carries the ASTM Remote ID service data:

    AA:BB:CC:DD:EE:FF|rssi|service-data-hex

Anything else is ignored, so a busy airport does not flood the console with beacon noise.
"""

import asyncio
import sys

ODID_UUID = "0000fffa-0000-1000-8000-00805f9b34fb"


def payload_of(advertisement):
    data = advertisement.service_data or {}
    for key, value in data.items():
        # bleak reports full 128-bit UUIDs, but short forms appear on some backends.
        if "fffa" in str(key).lower():
            return value
    return None


def main():
    try:
        from bleak import BleakScanner
    except Exception as error:  # noqa: BLE001 - any import failure means "no scanner here"
        print(f"unavailable|{error}", flush=True)
        return 2

    def seen(device, advertisement):
        payload = payload_of(advertisement)
        if not payload:
            return
        print(f"{device.address}|{advertisement.rssi}|{payload.hex()}", flush=True)

    async def run():
        # 0.0 keeps the radio listening continuously; BlueZ/Windows coalesce duplicates for us.
        async with BleakScanner(detection_callback=seen, scanning_mode="active"):
            while True:
                await asyncio.sleep(3600)

    try:
        asyncio.run(run())
    except Exception as error:  # noqa: BLE001 - report, do not crash the desk
        print(f"unavailable|{error}", flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
