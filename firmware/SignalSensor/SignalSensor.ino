/*
 * Signal_IntelGo sensor firmware - ESP32-C3 Mini and ESP32-C5 Mini.
 *
 * One sketch for both chips; the board is picked at compile time by the targets in
 * firmware/build.sh. It turns a bare board into the second, independent sensor the app expects:
 * it sweeps Wi-Fi and BLE, reads a GPS module on a pin you assign, and advertises the result as
 * the manufacturer data the app already decodes (company id 0xFFFF, magic "SG").
 *
 * Console, at the serial speed set in Setup (115200 by default):
 *
 *     help                 this list
 *     status               one telemetry block now
 *     scan                 run a Wi-Fi sweep and print it
 *     blescan              count BLE advertisers for three seconds
 *     gpspin <n>           which GPIO the GPS module's TX is wired to
 *     gpsbaud <n>          the GPS module's baud rate
 *     gps                  one fix report
 *     save                 write the pin and baud to NVS (they are also saved on change)
 *     reboot               restart the board
 *
 * Pin and baud are kept in NVS, so a board that is unplugged and moved keeps its wiring.
 * Nothing here prints "scanap", "bledetect", "karma" or "Marauder": those words are how the app
 * recognises other firmwares, and this one must not be mistaken for them.
 */

#include <WiFi.h>
#include <BLEDevice.h>
#include <BLEAdvertising.h>
#include <Preferences.h>
#include <HardwareSerial.h>

static const char *FW_VERSION = "1.1.0";
static const char *NVS_NAMESPACE = "sig-sensor";

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

static int wifiCount = 0;
static int bleCount = 0;
static volatile bool bleScanWanted = false;
static volatile int bleScanResult = 0;

static unsigned long lastGpsPrint = 0;
static unsigned long lastWifiScan = 0;
static unsigned long lastBleScan = 0;
static unsigned long lastAdvert = 0;

static String consoleLine;

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
    float hdop = f[8].toFloat();
    gpsHdop = hdop;
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
  while (GpsSerial.available() > 0) {
    char c = (char)GpsSerial.read();
    if (c == '\n' || c == '\r') {
      if (consoleLine.length() > 0) {
        gpsSentence(consoleLine);
        consoleLine = "";
      }
    } else if (consoleLine.length() < 120) {
      consoleLine += c;
    }
  }
}

// ---------------------------------------------------------------- radio

/** One band's sweep, printed in the shape the app's console parser reads: MAC, RSSI, channel. */
static int wifiSweepBand(bool print) {
  int found = WiFi.scanNetworks(false, false);
  if (found < 0) found = 0;
  if (print) {
    for (int i = 0; i < found; i++) {
      String ssid = WiFi.SSID(i);
      if (ssid.length() == 0) ssid = "<hidden>";
      Serial.printf("AP %s %s RSSI %d CH %d\r\n",
                    WiFi.BSSIDstr(i).c_str(), ssid.c_str(), WiFi.RSSI(i), WiFi.channel(i));
    }
  }
  WiFi.scanDelete();
  return found;
}

/**
 * A sweep, over both bands when the chip has two.
 *
 * `WiFi.scanNetworks()` scans the band the radio is currently in, and on a C5 that is 2.4 GHz
 * unless the band mode is changed. That is why a C5 board reported 2.4 GHz networks and BLE
 * and nothing else, while the same sketch on a C3 was correct: the C3 has no 5 GHz radio to
 * miss. Both bands are swept here and the counts added, so a 5 GHz access point arrives with
 * its own channel number (36 and up) and the advert carries the combined total.
 */
static int wifiSweep(bool print) {
#if defined(CONFIG_SOC_WIFI_SUPPORT_5G) && defined(ESP_IDF_VERSION) && \
    (ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(5, 4, 2))
  WiFi.setBandMode(WIFI_BAND_MODE_2G_ONLY);
  int found = wifiSweepBand(print);
  WiFi.setBandMode(WIFI_BAND_MODE_5G_ONLY);
  found += wifiSweepBand(print);
  // Back to both, so a later scan or connection is not left pinned to one band.
  WiFi.setBandMode(WIFI_BAND_MODE_AUTO);
#else
  int found = wifiSweepBand(print);
#endif
  wifiCount = found;
  if (print) Serial.printf("APs found: %d\r\n", found);
  return found;
}

/** A three second passive BLE count. Bluetooth has to come back up for the advert afterwards. */
static int bleSweep() {
  BLEScan *scan = BLEDevice::getScan();
  scan->setActiveScan(false);
  scan->setInterval(100);
  scan->setWindow(80);
  BLEScanResults *results = scan->start(3, false);
  int found = results != nullptr ? results->getCount() : 0;
  scan->clearResults();
  return found;
}

// ---------------------------------------------------------------- advert

/**
 * Packs the telemetry exactly as Esp32Codec.decode expects it: company id, "SG", version, then
 * little-endian latitude, longitude, altitude, satellites, HDOP, battery and flags.
 */
static void advertise() {
  uint8_t b[23];
  b[0] = 0xFF;
  b[1] = 0xFF;  // company id 0xFFFF, little-endian
  b[2] = 'S';
  b[3] = 'G';
  b[4] = 0x01;  // protocol version

  int32_t lat = (int32_t)(gpsLat * 1e7);
  int32_t lon = (int32_t)(gpsLon * 1e7);
  int16_t alt = (int16_t)gpsAlt;
  if (!gpsFix) {
    lat = 0;
    lon = 0;
    alt = 0;
  }
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

  String name = String("IntelGo-") + String((uint32_t)(ESP.getEfuseMac() & 0xFFFF), HEX);
  BLEAdvertisementData data;
  data.setName(name.c_str());
  data.setManufacturerData(String((char *)b, sizeof(b)));
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->setAdvertisementData(data);
  adv->setScanResponse(false);
  BLEDevice::startAdvertising();
}

// ---------------------------------------------------------------- console

static void printStatus() {
  Serial.printf("Signal IntelGo sensor v%s esp32 (%s)\r\n", FW_VERSION, ESP.getChipModel());
  Serial.printf("APs found: %d\r\n", wifiCount);
  Serial.printf("BLE devices: %d\r\n", bleCount);
  Serial.printf("GPS: fix %s, Sats: %d, HDOP: %.2f\r\n", gpsFix ? "3D" : "no", gpsSats, gpsHdop);
  if (gpsFix) {
    Serial.printf("Lat: %.6f\r\n", gpsLat);
    Serial.printf("Lon: %.6f\r\n", gpsLon);
    Serial.printf("Alt: %.0f\r\n", gpsAlt);
  }
  Serial.printf("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
}

static void printHelp() {
  Serial.printf("Signal IntelGo sensor v%s\r\n", FW_VERSION);
  Serial.printf("commands: help status scan blescan gps gpspin <n> gpsbaud <n> save reboot\r\n");
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
  } else if (verb == "status") {
    printStatus();
  } else if (verb == "scan") {
    wifiSweep(true);
  } else if (verb == "blescan") {
    Serial.printf("BLE devices: %d\r\n", bleSweep());
  } else if (verb == "gps") {
    Serial.printf("GPS: fix %s, Sats: %d, HDOP: %.2f\r\n", gpsFix ? "3D" : "no", gpsSats, gpsHdop);
    if (gpsFix) {
      Serial.printf("Lat: %.6f\r\nLon: %.6f\r\nAlt: %.0f\r\n", gpsLat, gpsLon, gpsAlt);
    }
  } else if (verb == "gpspin") {
    int pin = arg.toInt();
    // 0 is a real pin; the check is on the parse, not the value. Beyond 48 nothing on either
    // chip can drive a UART, so a typo is rejected rather than saved.
    if (arg.length() == 0 || pin < 0 || pin > 48) {
      Serial.printf("GPS pin: %d (give gpspin <0-48>)\r\n", gpsPin);
      return;
    }
    if (pin == 20 || pin == 21) {
      // These are the USB D-/D+ pads on the mini boards; using one kills the console.
      Serial.printf("GPS pin: %d rejected, USB pins on a mini board\r\n", pin);
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

void setup() {
  Serial.begin(115200);
  delay(300);

  prefs.begin(NVS_NAMESPACE, false);
  gpsPin = prefs.getInt("gpspin", gpsPin);
  gpsBaud = prefs.getInt("gpsbaud", gpsBaud);
  gpsBegin();

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  delay(100);

  String name = String("IntelGo-") + String((uint32_t)(ESP.getEfuseMac() & 0xFFFF), HEX);
  BLEDevice::init(name.c_str());
  advertise();

  Serial.printf("\r\nSignal IntelGo sensor v%s esp32 (%s)\r\n", FW_VERSION, ESP.getChipModel());
  Serial.printf("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
  Serial.printf("type help for commands\r\n");

  wifiCount = wifiSweep(false);
}

void loop() {
  consolePump();
  gpsPump();

  unsigned long now = millis();

  if (now - lastWifiScan > 15000 || lastWifiScan == 0) {
    lastWifiScan = now;
    wifiSweep(false);
  }

  // A scan stops advertising, so it is rare and the advert is restarted straight after.
  if (now - lastBleScan > 20000 || lastBleScan == 0) {
    lastBleScan = now;
    bleCount = bleSweep();
    advertise();
  }

  if (now - lastAdvert > 2000 || lastAdvert == 0) {
    lastAdvert = now;
    advertise();
  }

  if (now - lastGpsPrint > 10000) {
    lastGpsPrint = now;
    printStatus();
  }

  delay(20);
}
