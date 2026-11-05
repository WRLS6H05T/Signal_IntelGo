#!/usr/bin/env python3
"""BLE bridge for the Signal_IntelGo desktop host.

Windows has no BLE API the JVM can call, so the app starts this process and talks to it in JSON
lines: commands on stdin, events on stdout. `bleak` drives the same WinRT stack the system
settings app uses, which is what makes this work on a normal Windows machine with no drivers to
install beyond the ones the adapter already has.

    {"cmd": "scan", "on": true}
    {"cmd": "connect", "address": "..."}
    {"cmd": "disconnect", "address": "..."}
    {"cmd": "write", "address": "...", "characteristic": "<uuid>", "data": "<base64>"}
    {"cmd": "quit"}

Events are `ready`, `advert`, `connected`, `disconnected`, `notify`, `log` and `error`. Nothing
in here knows what the payloads mean: the host decodes them with the same code the phone uses.

Run it by itself with `python ble_bridge.py --verbose` to see the raw traffic.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import sys
import time

try:
    from bleak import BleakClient, BleakScanner

    try:
        from importlib.metadata import version as _pkg_version

        BLEAK_VERSION = _pkg_version("bleak")
    except Exception:  # pragma: no cover - only for very old installs
        BLEAK_VERSION = "unknown"
except Exception as exc:  # pragma: no cover - reported to the app, not raised
    print(
        json.dumps(
            {
                "ev": "error",
                "message": "bleak is not available (%s). Install it with: python -m pip install bleak"
                % exc,
            }
        ),
        flush=True,
    )
    sys.exit(2)

# The two-phone link's own service and characteristics, so the desktop speaks the same GATT.
PEER_SERVICE = "7a1f5e10-3c6b-4a2e-9f1d-5b8c2d4e6f70"
UP = "7a1f5e11-3c6b-4a2e-9f1d-5b8c2d4e6f70"
DOWN = "7a1f5e12-3c6b-4a2e-9f1d-5b8c2d4e6f70"

# A legacy advertisement carries little; anything past these is a stack artefact.
COMPANY_ID = 0xFFFF


def emit(event: dict) -> None:
    """One event per line. Flushed every time: a buffered event is an event the app never sees."""
    print(json.dumps(event), flush=True)


def b64(data) -> str:
    return base64.b64encode(bytes(data)).decode("ascii")


class Bridge:
    def __init__(self, verbose: bool = False, throttled_ms: int = 900) -> None:
        self.verbose = verbose
        self.throttled_ms = throttled_ms
        self.scanner = None
        self.clients: dict[str, BleakClient] = {}
        self.last_emit: dict[str, float] = {}
        self.scanning = False

    # ---------------- scanning ----------------

    async def start(self) -> None:
        try:
            self.scanner = BleakScanner(detection_callback=self.on_detection)
            await self.scanner.start()
            self.scanning = True
            emit({"ev": "ready", "adapter": "winrt" if sys.platform == "win32" else sys.platform})
            if self.verbose:
                emit({"ev": "log", "message": "bleak %s, scanning" % BLEAK_VERSION})
        except Exception as exc:
            emit({"ev": "error", "message": "Bluetooth scan failed: %s: %s" % (type(exc).__name__, exc)})

    def on_detection(self, device, adv) -> None:
        # Called on the scanner's own thread for every advertisement the adapter hears.
        try:
            address = getattr(device, "address", "") or ""
            if not address:
                return
            manufacturer = getattr(adv, "manufacturer_data", None) or {}
            service_data = getattr(adv, "service_data", None) or {}
            uuids = getattr(adv, "service_uuids", None) or []
            if not manufacturer and not service_data:
                return
            now = time.monotonic() * 1000.0
            previous = self.last_emit.get(address, 0.0)
            if now - previous < self.throttled_ms:
                return
            self.last_emit[address] = now
            rssi = getattr(adv, "rssi", None)
            if rssi is None:
                rssi = getattr(device, "rssi", 0) or 0
            emit(
                {
                    "ev": "advert",
                    "address": address,
                    "name": getattr(adv, "local_name", None) or getattr(device, "name", "") or "",
                    "rssi": int(rssi),
                    "service_uuids": [u.lower() for u in uuids],
                    "manufacturer": {str(k): b64(v) for k, v in manufacturer.items()},
                    "service_data": {str(k).lower(): b64(v) for k, v in service_data.items()},
                }
            )
            if self.verbose and COMPANY_ID in manufacturer:
                emit({"ev": "log", "message": "board advert %s %s" % (address, b64(manufacturer[COMPANY_ID]))})
        except Exception as exc:  # never let a callback kill the scanner
            emit({"ev": "log", "message": "detection callback: %s" % exc})

    async def stop_scan(self) -> None:
        if not self.scanning or self.scanner is None:
            return
        try:
            await self.scanner.stop()
        except Exception as exc:
            emit({"ev": "log", "message": "scan stop: %s" % exc})
        self.scanning = False

    # ---------------- connections ----------------

    async def connect(self, address: str) -> None:
        if address in self.clients:
            return
        try:
            client = BleakClient(address)
            await client.connect()
            self.clients[address] = client
            mtu = 185
            try:
                # Windows negotiates 247 on most adapters, but the attribute is not on every
                # bleak version, and guessing high would overflow a write.
                mtu = int(getattr(client, "mtu_size", 185) or 185)
            except Exception:
                pass
            emit({"ev": "connected", "address": address, "mtu": mtu})
            await self.subscribe(address, client)
        except Exception as exc:
            emit({"ev": "error", "message": "connect %s: %s: %s" % (address, type(exc).__name__, exc)})

    async def subscribe(self, address: str, client: BleakClient) -> None:
        def on_notify(_characteristic, data: bytearray) -> None:
            emit({"ev": "notify", "address": address, "uuid": UP, "data": b64(data)})

        try:
            await client.start_notify(UP, on_notify)
            emit({"ev": "log", "message": "subscribed to %s on %s" % (UP, address)})
        except Exception as exc:
            # Not fatal: the link stays up, the phone just cannot be heard on it yet.
            emit({"ev": "log", "message": "subscribe %s: %s" % (address, exc)})

    async def disconnect(self, address: str) -> None:
        client = self.clients.pop(address, None)
        if client is None:
            return
        try:
            await client.disconnect()
        except Exception as exc:
            emit({"ev": "log", "message": "disconnect %s: %s" % (address, exc)})
        emit({"ev": "disconnected", "address": address})

    async def write(self, address: str, characteristic: str, data: bytes) -> None:
        client = self.clients.get(address)
        if client is None:
            emit({"ev": "log", "message": "write to %s: not connected" % address})
            return
        try:
            # The phone's DOWN characteristic takes a write with response, which is also what
            # makes a lost chunk visible instead of silent.
            await client.write_gatt_char(characteristic or DOWN, data, response=True)
        except Exception as exc:
            emit({"ev": "log", "message": "write %s: %s" % (address, exc)})

    async def close(self) -> None:
        for address in list(self.clients):
            await self.disconnect(address)
        await self.stop_scan()


async def read_commands(queue: asyncio.Queue) -> None:
    loop = asyncio.get_running_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            await queue.put({"cmd": "quit"})
            return
        text = line.strip()
        if not text:
            continue
        try:
            await queue.put(json.loads(text))
        except Exception:
            # A half-written command is not a reason to drop the link.
            emit({"ev": "log", "message": "unreadable command: %s" % text[:120]})


async def run(args) -> int:
    bridge = Bridge(verbose=args.verbose, throttled_ms=args.throttle)
    queue: asyncio.Queue = asyncio.Queue()
    asyncio.create_task(read_commands(queue))
    await bridge.start()

    while True:
        command = await queue.get()
        kind = command.get("cmd", "")
        try:
            if kind == "quit":
                break
            if kind == "scan":
                if command.get("on", True):
                    if not bridge.scanning:
                        await bridge.start()
                else:
                    await bridge.stop_scan()
            elif kind == "connect":
                asyncio.create_task(bridge.connect(command.get("address", "")))
            elif kind == "disconnect":
                await bridge.disconnect(command.get("address", ""))
            elif kind == "write":
                await bridge.write(
                    command.get("address", ""),
                    command.get("characteristic", DOWN),
                    base64.b64decode(command.get("data", "")),
                )
            else:
                emit({"ev": "log", "message": "unknown command: %s" % kind})
        except Exception as exc:
            emit({"ev": "error", "message": "%s: %s" % (kind or "command", exc)})

    await bridge.close()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="BLE bridge for the Signal_IntelGo desktop host")
    parser.add_argument("--verbose", action="store_true", help="log raw traffic to stdout")
    parser.add_argument("--throttle", type=int, default=900, help="ms between adverts per address")
    args = parser.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
