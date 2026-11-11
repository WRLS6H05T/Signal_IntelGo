/*
 * Signal_IntelGo sensor firmware - ESP32-C3 Mini and ESP32-C5 Mini.
 *
 * One sketch for both chips; the board is picked at compile time by the targets in
 * firmware/build.sh. It turns a bare board into the second, independent sensor the app expects:
 * it sweeps Wi-Fi (both bands on a C5) and BLE, reads a GPS module on a pin you assign, and
 * reports all of it down the USB console the app already has open - the cable that identifies
 * the board carries its sweeps as well, so nothing has to be paired or configured.
 *
 * Everything printed here is in a shape app/src/main/java/dev/skyward/rf/scan/Esp32Console.kt
 * reads, because the app parses the transcript rather than a protocol:
 *
 *     APs found: <n>                                  the sweep's own count
 *     AP <bssid> RSSI <dbm> CH <n> SSID=<name>         one network
 *     BLE devices: <n>
 *     BLE <addr> RSSI <dbm> NAME=<name>                one advertiser
 *     GPS: 3D fix, Sats: <n>, HDOP: <x>                a fix
 *     GPS: no fix, Sats: <n>, HDOP: 0.00               no fix yet
 *     Lat: <deg>   Lon: <deg>   Alt: <m>
 *
 * Console, at the speed the app opens the port with (115200 by default):
 *
 *     help                 this list
 *     status               one telemetry block now
 *     scan  / scanap        a Wi-Fi sweep, listed
 *     blescan / bledetect   a BLE sweep, listed
 *     stopscan             acknowledged; this firmware sweeps on demand, so nothing is running
 *     gps                  one fix report
 *     gpspin <n>           which GPIO the GPS module's TX is wired to
 *     gpsbaud <n>          the GPS module's baud rate
 *     save                 write the pin and baud to NVS (they are also saved on change)
 *     reboot               restart the board
 *
 * The help text names `scanap` and `bledetect` on purpose: an app build that predates this
 * firmware's own family reads a board as GhostESP from that vocabulary alone, which is exactly
 * the poll that makes an older install sweep this board too. A build that knows the family
 * recognises the banner first and labels it correctly.
 *
 * Pin and baud are kept in NVS, so a board that is unplugged and moved keeps its wiring.
 */

#include <WiFi.h>
#include <BLEDevice.h>
#include <BLEAdvertising.h>
#include <Preferences.h>
#include <HardwareSerial.h>

static const char *FW_TAG = "Signal IntelGo sensor";
static const char *FW_VERSION = "1.2.0";
static const char *NVS_NAMESPACE = "sig-sensor";

// How many findings one sweep lists. The app reads at most 8 000 characters per poll, roughly a
// hundred of these lines, and the whole sweep is still counted when the listing has to stop -
// so its INTEL row is right even if this cap cuts the tail.
static const int MAX_AP_LINES = 48;
static const int MAX_BLE_LINES = 32;

// A sweep nobody asked for keeps the counts and the BLE advert fresh, and prints nothing but
// its totals: the app's own poll then always has room in the transcript for the sweep it asked
// for. Both are slower than the poll cycle because a board is a sensor, not a spectrum analyser.
static const unsigned long QUIET_WIFI_MS = 30000;
static const unsigned long QUIET_BLE_MS = 30000;
static const unsigned long ADVERT_MS = 2000;
static const unsigned long FIX_PRINT_MS = 15000;

// A module that stops talking has stopped knowing where it is. Twelve seconds is longer than
// one NMEA burst at 1 Hz and shorter than the app's idea of a live fix.
static const unsigned long GPS_STALE_MS = 12000;

// Seconds per passive BLE sweep. Long enough for advertisers on a one-second interval, and
// short enough that a `scanap` and a `blescan` in the same poll both finish inside the app's
// six-second read window.
static const uint32_t BLE_SCAN_SECONDS = 2;

// How long a Wi-Fi sweep may take before it is abandoned. A blocking scan that never returns
// leaves the console deaf, so the sweep is polled with a deadline instead of waited on.
static const unsigned long WIFI_SCAN_DEADLINE_MS = 5000;

// Marker before each blocking call during bring-up: a board that stops answering names the
// sweep it died in instead of just going quiet. Set to 0 once the port is trusted.
#define BOOT_TRACE 1

// The C5 5 GHz band switch is deliberately not used. `WiFi.setBandMode()` followed by a scan
// never returns on this core: the sweep hangs, loop() never runs, the console stops answering
// and a perfectly flashed board looks bricked. One scan of the current band is what the C5 ran
// before, and it still reports 2.4 GHz access points.

static Preferences prefs;
static HardwareSerial GpsSerial(1);

static int gpsPin = 4;
static int gpsBaud = 9600;
static bool gpsReady = false;

static double gpsLat = 0.0;
static double gpsLon = 0.0;
static float gpsAlt = 0.0f;
static int gpsSats = 0;
static float gpsHdop = 0.0f;
static bool gpsFix = false;
static unsigned long gpsLastSentence = 0;

static int wifiCount = 0;
static int bleCount = 0;

static unsigned long lastWifiScan = 0;
static unsigned long lastBleScan = 0;
static unsigned long lastAdvert = 0;
static unsigned long lastFixPrint = 0;

static String deviceName;

// ---------------------------------------------------------------- text

/**
 * One console-safe field.
 *
 * A console line is the only structure the app has, so a network or device name must not carry
 * a newline, a comma or a pipe into it. Anything unprintable becomes a space, and the result is
 * trimmed and capped, which is also what keeps a broken advertiser from printing garbage.
 */
static void copyText(char *out, size_t size, const char *in) {
  size_t j = 0;
  bool space = false;
  if (size == 0) return;
  for (size_t i = 0; in[i] != '\0' && j + 1 < size; i++) {
    char c = in[i];
    if (c < 0x20 || c > 0x7E || c == ',' || c == ';' || c == '|') c = ' ';
    if (c == ' ') {
      if (space || j == 0) continue;  // no leading gap, no runs of them
      space = true;
    } else {
      space = false;
    }
    out[j++] = c;
  }
  out[j] = '\0';
}

// ---------------------------------------------------------------- GPS

static void gpsBegin() {
  if (gpsReady) {
    GpsSerial.end();
    gpsReady = false;
  }
  // RX only: the module's TX goes to gpsPin, and the board never talks to it.
  GpsSerial.begin(gpsBaud, SERIAL_8N1, gpsPin, -1);
  gpsReady = true;
}

/** NMEA coordinate ddmm.mmmm -> degrees, with the hemisphere as a letter. */
static double nmeaDegrees(const String &value, const String &hemisphere) {
  if (value.length() == 0) return 0.0;
  double raw = value.toDouble();
  int whole = (int)(raw / 100.0);
  double out = whole + (raw - whole * 100.0) / 60.0;
  if (hemisphere == "S" || hemisphere == "W") out = -out;
  return out;
}

static void gpsSentence(const String &line) {
  if (!line.startsWith("$")) return;
  int star = line.indexOf('*');
  String body = star > 0 ? line.substring(0, star) : line;
  if (body.length() < 7) return;
  gpsLastSentence = millis();
  String kind = body.substring(3, 6);

  // Split once into a small fixed array: NMEA sentences never exceed 20 fields and a
  // heap-allocating parser on a board that runs for days is how fragmentation starts.
  String f[20];
  int n = 0;
  int start = 0;
  for (int i = 0; i <= (int)body.length() && n < 20; i++) {
    if (i == (int)body.length() || body.charAt(i) == ',') {
      f[n++] = body.substring(start, i);
      start = i + 1;
    }
  }

  if (kind == "GGA" && n >= 10) {
    int quality = f[6].toInt();
    gpsFix = quality > 0;
    gpsSats = f[7].toInt();
    gpsHdop = f[8].toFloat();
    if (gpsFix) {
      gpsLat = nmeaDegrees(f[2], f[3]);
      gpsLon = nmeaDegrees(f[4], f[5]);
      gpsAlt = f[9].toFloat();
    }
  } else if (kind == "RMC" && n >= 7) {
    gpsFix = f[2] == "A";
    if (gpsFix) {
      gpsLat = nmeaDegrees(f[3], f[4]);
      gpsLon = nmeaDegrees(f[5], f[6]);
    }
  }
}

static void gpsPump() {
  if (!gpsReady) return;
  static String line;
  while (GpsSerial.available() > 0) {
    char c = (char)GpsSerial.read();
    if (c == '\n' || c == '\r') {
      if (line.length() > 0) {
        gpsSentence(line);
        line = "";
      }
    } else if (line.length() < 120) {
      line += c;
    }
  }
  // A module unplugged mid-drive must stop placing the board at its last known position.
  if (gpsFix && gpsLastSentence != 0 && millis() - gpsLastSentence > GPS_STALE_MS) {
    gpsFix = false;
    gpsSats = 0;
    gpsHdop = 0.0f;
  }
}

/**
 * The fix in the two shapes the app reads: one summary line for the counts and the fix state,
 * then a labelled position the panel can take as the board's own.
 *
 * "GPS: no fix" rather than "GPS: fix no": the app's first fix pattern looks for `gps:` followed
 * by `3d`/`fix`, so the negative has to be phrased so that it cannot also be read as a yes.
 */
static void printFix() {
  if (gpsFix) {
    Serial.printf("GPS: 3D fix, Sats: %d, HDOP: %.2f\r\n", gpsSats, gpsHdop);
    Serial.printf("Lat: %.6f\r\n", gpsLat);
    Serial.printf("Lon: %.6f\r\n", gpsLon);
    Serial.printf("Alt: %.0f\r\n", gpsAlt);
  } else {
    Serial.printf("GPS: no fix, Sats: %d, HDOP: 0.00\r\n", gpsSats);
  }
}

// ---------------------------------------------------------------- Wi-Fi

struct ApEntry {
  char bssid[18];
  char ssid[33];
  int rssi;
  int ch;
};

static ApEntry apTop[MAX_AP_LINES];
static int apTopCount = 0;

/**
 * Keeps the strongest findings, in order, without an allocation.
 *
 * A dense street hears more networks than the listing cap, and those worth reporting are the
 * loudest ones. Keeping them sorted as they arrive means the cap falls on the tail instead of
 * on whatever happened to be scanned last.
 */
static void apRemember(const char *bssid, const char *ssid, int rssi, int ch) {
  int at = 0;
  while (at < apTopCount && apTop[at].rssi >= rssi) at++;
  if (at >= MAX_AP_LINES) return;
  int last = apTopCount < MAX_AP_LINES ? apTopCount : MAX_AP_LINES - 1;
  for (int i = last; i > at; i--) apTop[i] = apTop[i - 1];
  snprintf(apTop[at].bssid, sizeof(apTop[at].bssid), "%s", bssid);
  copyText(apTop[at].ssid, sizeof(apTop[at].ssid), ssid);
  apTop[at].rssi = rssi;
  apTop[at].ch = ch;
  if (apTopCount < MAX_AP_LINES) apTopCount++;
}

/** One band's sweep. `total` carries across bands; the listing is one shared cap. */
static void wifiBandScan(bool list, int &total) {
#if BOOT_TRACE
  Serial.printf("trace: scan call\r\n");
#endif
  // Async, with a deadline. A blocking `scanNetworks()` never returns on this C5 core, which
  // stops loop() and leaves the board answering nothing: flashed, running, and apparently dead.
  if (WiFi.scanNetworks(true, true) == WIFI_SCAN_FAILED) {
#if BOOT_TRACE
    Serial.printf("trace: scan would not start\r\n");
#endif
    return;
  }
  unsigned long started = millis();
  int found;
  while ((found = WiFi.scanComplete()) == WIFI_SCAN_RUNNING) {
    if (millis() - started > WIFI_SCAN_DEADLINE_MS) {
      WiFi.scanDelete();
#if BOOT_TRACE
      Serial.printf("trace: scan timed out after %lu ms\r\n", millis() - started);
#endif
      return;
    }
    delay(20);
  }
  if (found < 0) found = 0;
#if BOOT_TRACE
  Serial.printf("trace: scan returned %d\r\n", found);
#endif
  total += found;
  if (list) {
    for (int i = 0; i < found; i++) {
      apRemember(WiFi.BSSIDstr(i).c_str(), WiFi.SSID(i).c_str(), (int)WiFi.RSSI(i), (int)WiFi.channel(i));
    }
  }
  WiFi.scanDelete();
}

/**
 * A sweep of the band the radio is already in.
 *
 * `WiFi.scanNetworks()` scans the current band, which on a C5 is 2.4 GHz. Sweeping 5 GHz too
 * would need `setBandMode()` first, and that is exactly what hung the board, so 5 GHz access
 * points are not reported until the band switch can be done without a blocking call.
 */
static int wifiSweep(bool list) {
  if (list) apTopCount = 0;
  int total = 0;
  wifiBandScan(list, total);
  wifiCount = total;
  if (!list) return total;

  Serial.printf("APs found: %d\r\n", total);
  for (int i = 0; i < apTopCount; i++) {
    // A hidden network has no name to print; the address and the channel are the finding.
    if (apTop[i].ssid[0] != '\0') {
      Serial.printf("AP %s RSSI %d CH %d SSID=%s\r\n", apTop[i].bssid, apTop[i].rssi, apTop[i].ch,
                    apTop[i].ssid);
    } else {
      Serial.printf("AP %s RSSI %d CH %d\r\n", apTop[i].bssid, apTop[i].rssi, apTop[i].ch);
    }
  }
  if (total > apTopCount) Serial.printf("; %d more not listed\r\n", total - apTopCount);
  return total;
}

// ---------------------------------------------------------------- BLE

struct BleEntry {
  char addr[18];
  char name[33];
  int rssi;
};

static BleEntry bleTop[MAX_BLE_LINES];
static int bleTopCount = 0;

static void bleRemember(const char *addr, const char *name, int rssi) {
  int at = 0;
  while (at < bleTopCount && bleTop[at].rssi >= rssi) at++;
  if (at >= MAX_BLE_LINES) return;
  int last = bleTopCount < MAX_BLE_LINES ? bleTopCount : MAX_BLE_LINES - 1;
  for (int i = last; i > at; i--) bleTop[i] = bleTop[i - 1];
  snprintf(bleTop[at].addr, sizeof(bleTop[at].addr), "%s", addr);
  copyText(bleTop[at].name, sizeof(bleTop[at].name), name);
  bleTop[at].rssi = rssi;
  if (bleTopCount < MAX_BLE_LINES) bleTopCount++;
}

/**
 * A passive BLE sweep.
 *
 * Passive on purpose: sweeping is listening, and answering a scan request would both cost air
 * time and make the board visible as an active prober. Scanning and advertising share the
 * controller, so the advert is left running through it.
 */
static int bleSweep(bool list) {
  BLEScan *scan = BLEDevice::getScan();
  scan->setActiveScan(false);
  scan->setInterval(100);
  scan->setWindow(80);
  BLEScanResults *results = scan->start(BLE_SCAN_SECONDS, false);
  int found = results != nullptr ? (int)results->getCount() : 0;

  if (list) {
    bleTopCount = 0;
    for (int i = 0; i < found; i++) {
      // Core 3.x hands the result back by value, not as a pointer.
      BLEAdvertisedDevice device = results->getDevice(i);
      char name[33];
      name[0] = '\0';
      if (device.haveName()) {
        copyText(name, sizeof(name), device.getName().c_str());
      }
      bleRemember(device.getAddress().toString().c_str(), name, (int)device.getRSSI());
    }
  }
  scan->clearResults();
  bleCount = found;
  if (!list) return found;

  Serial.printf("BLE devices: %d\r\n", found);
  for (int i = 0; i < bleTopCount; i++) {
    // An advertiser with no name in its packet is still a finding; printing an empty NAME= only
    // gives the parser a field with nothing in it.
    if (bleTop[i].name[0] != '\0') {
      Serial.printf("BLE %s RSSI %d NAME=%s\r\n", bleTop[i].addr, bleTop[i].rssi, bleTop[i].name);
    } else {
      Serial.printf("BLE %s RSSI %d\r\n", bleTop[i].addr, bleTop[i].rssi);
    }
  }
  if (found > bleTopCount) Serial.printf("; %d more not listed\r\n", found - bleTopCount);
  return found;
}

// ---------------------------------------------------------------- advert

static uint8_t advLast[23];
static bool advHave = false;

/**
 * Packs the telemetry exactly as Esp32Codec.decode expects it: company id, "SG", version, then
 * little-endian latitude, longitude, altitude, satellites, HDOP, battery and flags.
 */
static void buildAdvert(uint8_t *b) {
  b[0] = 0xFF;
  b[1] = 0xFF;  // company id 0xFFFF, little-endian
  b[2] = 'S';
  b[3] = 'G';
  b[4] = 0x01;  // protocol version

  int32_t lat = gpsFix ? (int32_t)(gpsLat * 1e7) : 0;
  int32_t lon = gpsFix ? (int32_t)(gpsLon * 1e7) : 0;
  int16_t alt = gpsFix ? (int16_t)gpsAlt : 0;
  memcpy(&b[5], &lat, 4);
  memcpy(&b[9], &lon, 4);
  memcpy(&b[13], &alt, 2);
  b[15] = (uint8_t)constrain(gpsSats, 0, 255);
  b[16] = gpsHdop > 0.0f ? (uint8_t)constrain((int)(gpsHdop * 10.0f), 1, 254) : 0xFF;
  b[17] = 0xFF;  // no battery sense on a mini board

  uint8_t flags = 0;
  if (gpsFix) flags |= 0x01;
  if (wifiCount > 0) flags |= 0x02;
  if (bleCount > 0) flags |= 0x04;
  b[18] = flags;
  b[19] = (uint8_t)(wifiCount & 0xFF);
  b[20] = (uint8_t)((wifiCount >> 8) & 0xFF);
  b[21] = (uint8_t)(bleCount & 0xFF);
  b[22] = (uint8_t)((bleCount >> 8) & 0xFF);
}

/**
 * Refreshes the advertisement, and only when the numbers in it moved.
 *
 * The telemetry goes in the advertisement and the name in the scan response: 23 bytes of
 * manufacturer data plus the flags is 27, which fits a legacy 31-byte advertisement, while both
 * of them together - the previous attempt - does not, and a payload the controller rejects is
 * an advertisement nobody hears.
 */
static void advertise(bool force) {
  uint8_t b[23];
  buildAdvert(b);
  if (!force && advHave && memcmp(b, advLast, sizeof(b)) == 0) return;
  memcpy(advLast, b, sizeof(b));
  advHave = true;

  BLEAdvertisementData adv;
  adv.setManufacturerData(String((char *)b, sizeof(b)));
  BLEAdvertisementData resp;
  resp.setName(deviceName.c_str());

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->stop();
  advertising->setAdvertisementData(adv);
  advertising->setScanResponseData(resp);
  advertising->setScanResponse(true);
  advertising->start();
}

// ---------------------------------------------------------------- console

static void printStatus() {
  Serial.printf("%s v%s esp32 (%s)\r\n", FW_TAG, FW_VERSION, ESP.getChipModel());
  Serial.printf("APs found: %d\r\n", wifiCount);
  Serial.printf("BLE devices: %d\r\n", bleCount);
  printFix();
  Serial.printf("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
}

static void printHelp() {
  Serial.printf("%s v%s esp32 (%s)\r\n", FW_TAG, FW_VERSION, ESP.getChipModel());
  Serial.printf("commands: help status gps scan scanap blescan bledetect stopscan gpspin <n> gpsbaud <n> save reboot\r\n");
}

static void saveConfig() {
  prefs.putInt("gpspin", gpsPin);
  prefs.putInt("gpsbaud", gpsBaud);
}

static void handleConsole(String line) {
  line.trim();
  if (line.length() == 0) {
    // A panel that has just opened is talking to a board that booted long ago, so one blank
    // line is treated as "who are you": banner, command list and the current GPS setting.
    printHelp();
    Serial.printf("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
    return;
  }
  String verb = line;
  String arg = "";
  int space = line.indexOf(' ');
  if (space > 0) {
    verb = line.substring(0, space);
    arg = line.substring(space + 1);
  }
  verb.toLowerCase();

  if (verb == "help" || verb == "?") {
    printHelp();
  } else if (verb == "version" || verb == "chipinfo") {
    Serial.printf("%s v%s esp32 (%s) %uMHz\r\n", FW_TAG, FW_VERSION, ESP.getChipModel(),
                  (unsigned)getCpuFrequencyMhz());
  } else if (verb == "status") {
    printStatus();
  } else if (verb == "scan" || verb == "scanap") {
    wifiSweep(true);
  } else if (verb == "blescan" || verb == "bledetect") {
    bleSweep(true);
  } else if (verb == "stopscan") {
    // Nothing is ever left running: every sweep above is one blocking pass that has already
    // finished printing by the time the next command is read.
    Serial.printf("no sweep running\r\n");
  } else if (verb == "gps") {
    printFix();
  } else if (verb == "gpspin") {
    int pin = arg.toInt();
    // 0 is a real pin; the check is on the parse, not the value. Beyond 48 nothing on either
    // chip can drive a UART, so a typo is rejected rather than saved.
    if (arg.length() == 0 || pin < 0 || pin > 48) {
      Serial.printf("GPS pin: %d (give gpspin <0-48>)\r\n", gpsPin);
      return;
    }
    if (pin == 20 || pin == 21) {
      // These pads are spoken for on a mini board; using one kills the console.
      Serial.printf("GPS pin: %d rejected, reserved on a mini board\r\n", pin);
      return;
    }
    gpsPin = pin;
    gpsBegin();
    saveConfig();
    Serial.printf("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
  } else if (verb == "gpsbaud") {
    int baud = arg.toInt();
    if (baud < 1200 || baud > 921600) {
      Serial.printf("GPS baud: %d (give gpsbaud <1200-921600>)\r\n", gpsBaud);
      return;
    }
    gpsBaud = baud;
    gpsBegin();
    saveConfig();
    Serial.printf("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
  } else if (verb == "save") {
    saveConfig();
    Serial.printf("saved: pin %d baud %d\r\n", gpsPin, gpsBaud);
  } else if (verb == "reboot") {
    Serial.printf("rebooting\r\n");
    Serial.flush();
    delay(100);
    ESP.restart();
  } else {
    Serial.printf("unknown command: %s\r\n", verb.c_str());
  }
}

static String serialLine;
static bool lastWasCr = false;

static void consolePump() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\r' || c == '\n') {
      // CRLF is one line ending, not two: the second half is swallowed so a blank line
      // printed its banner once.
      if (c == '\n' && lastWasCr) {
        lastWasCr = false;
        continue;
      }
      lastWasCr = (c == '\r');
      handleConsole(serialLine);
      serialLine = "";
    } else {
      lastWasCr = false;
      if (serialLine.length() < 96) serialLine += c;
    }
  }
}

// ---------------------------------------------------------------- lifecycle

/**
 * Why the board started this time.
 *
 * A board that resets over and over is usually not the sketch: a brown-out is the supply or the
 * cable, and a panic is where in setup it died. Naming the reason on the first line of the boot
 * log is the difference between "it reboots" and a diagnosis.
 */
static const char *resetReasonName() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON: return "power-on";
    case ESP_RST_EXT: return "external pin";
    case ESP_RST_SW: return "software restart";
    case ESP_RST_PANIC: return "panic (crash)";
    case ESP_RST_INT_WDT: return "interrupt watchdog";
    case ESP_RST_TASK_WDT: return "task watchdog";
    case ESP_RST_WDT: return "other watchdog";
    case ESP_RST_BROWNOUT: return "brown-out, check the supply";
    case ESP_RST_SDIO: return "sdio";
    default: return "unknown";
  }
}

void setup() {
  Serial.begin(115200);
  delay(300);
#if defined(ARDUINO_USB_CDC_ON_BOOT) && ARDUINO_USB_CDC_ON_BOOT
  // A console with nobody reading it must not stall a sweep: writes give up instead of blocking
  // the loop until a host drains the FIFO, which is what a board on a charger would otherwise do.
  Serial.setTxTimeoutMs(50);
#endif

  // Before any radio: a board that resets while Wi-Fi or BLE is coming up has to have said
  // something by then, or a boot loop reads as a port that never opens.
  Serial.printf("\r\n%s v%s esp32 (%s)\r\n", FW_TAG, FW_VERSION, ESP.getChipModel());
  Serial.printf("boot: %s\r\n", resetReasonName());

  prefs.begin(NVS_NAMESPACE, false);
  gpsPin = prefs.getInt("gpspin", gpsPin);
  gpsBaud = prefs.getInt("gpsbaud", gpsBaud);
  // A pin saved by an older build, or for a different board, must not be trusted at boot: a
  // UART started on a USB or flash pad leaves the console deaf, which looks the same as a board
  // that cannot stay up. Out-of-range values fall back instead of being applied.
  if (gpsPin < 0 || gpsPin > 48 || gpsPin == 20 || gpsPin == 21) gpsPin = 4;
  if (gpsBaud < 1200 || gpsBaud > 921600) gpsBaud = 9600;
  gpsBegin();

  // A C5 comes up pinned to one band, and a scan taken in that default band can hang forever.
  // The core's own WiFiScan example sets the band to AUTO before scanning on a 5 GHz chip, so
  // the same is done here; without it the first sweep never returns and loop() never starts.
  WiFi.STA.begin();
  WiFi.mode(WIFI_STA);
#if defined(ESP_IDF_VERSION) && (ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(5, 4, 2))
  WiFi.setBandMode(WIFI_BAND_MODE_AUTO);
#endif
  WiFi.disconnect();
  delay(100);

  deviceName = String("IntelGo-") + String((uint32_t)(ESP.getEfuseMac() & 0xFFFF), HEX);
  BLEDevice::init(deviceName.c_str());
  advertise(true);

  Serial.printf("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
  Serial.printf("type help for commands\r\n");

  // The first sweep runs in loop() instead. A scan that never returns here would keep the
  // console deaf, and a board that answers no command cannot be diagnosed over the cable.
  Serial.printf("ready\r\n");
}

void loop() {
  consolePump();
  gpsPump();

  unsigned long now = millis();

  // Quiet sweeps: counts and advert only, so a poll transcript is never filled with a sweep
  // nobody asked for.
  if (lastWifiScan == 0 || now - lastWifiScan > QUIET_WIFI_MS) {
    lastWifiScan = now;
#if BOOT_TRACE
    Serial.printf("trace: wifi scan\r\n");
#endif
    wifiSweep(false);
#if BOOT_TRACE
    Serial.printf("trace: wifi scan done\r\n");
#endif
  }
  if (lastBleScan == 0 || now - lastBleScan > QUIET_BLE_MS) {
    lastBleScan = now;
#if BOOT_TRACE
    Serial.printf("trace: ble scan\r\n");
#endif
    bleSweep(false);
#if BOOT_TRACE
    Serial.printf("trace: ble scan done\r\n");
#endif
    // A scan can leave the controller's advertising state changed. Restart it only when it has
    // actually stopped: stopping and restarting a live advertisement is a needless trip through
    // the controller on every sweep.
    if (!BLEDevice::getAdvertising()->isAdvertising()) advertise(true);
  }
  if (lastAdvert == 0 || now - lastAdvert > ADVERT_MS) {
    lastAdvert = now;
    advertise(false);
  }

  // A fix is repeated between polls so a phone without one of its own can still place the map,
  // and nothing is printed while there is no fix to repeat.
  if (gpsFix && (lastFixPrint == 0 || now - lastFixPrint > FIX_PRINT_MS)) {
    lastFixPrint = now;
    printFix();
  }

  delay(10);
}
