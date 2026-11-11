/*
 * Signal_IntelGo sensor firmware - ESP32-C3 Mini and ESP32-C5 Mini, as an ESP-IDF project.
 *
 * One firmware for both chips; the target is picked at build time by firmware/build.sh. It turns
 * a bare board into the second, independent sensor the app expects: it sweeps Wi-Fi (both bands
 * on a C5) and BLE, reads a GPS module on a pin you assign, and reports all of it down the USB
 * console the app already has open - the cable that identifies the board carries its sweeps as
 * well, so nothing has to be paired or configured.
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
 * Console verbs:
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
 * This was an Arduino sketch. The move is about the radio: the sketch could only scan the band
 * the wrapper happened to leave the radio in, so a C5 never reported 5 GHz. Here the driver is
 * ours - `esp_wifi_set_band_mode()` between two scans - and the BLE side is NimBLE, so the
 * advertisement payload and the scan window are the numbers written below instead of whatever a
 * wrapper chose.
 *
 * The wire format, the console verbs and the NVS layout are unchanged: the app cannot tell which
 * of the two firmwares a board is running apart from the version in the banner.
 */

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_event.h"
#include "esp_mac.h"
#include "esp_netif.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_wifi.h"

#include "driver/uart.h"
#include "driver/usb_serial_jtag.h"

#include "nvs.h"
#include "nvs_flash.h"

#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"

#include "sdkconfig.h"

static const char *FW_TAG = "Signal IntelGo sensor";
static const char *FW_VERSION = "1.3.0";
static const char *NVS_NAMESPACE = "sig-sensor";

// The chip names itself from the build target rather than at run time: this is the string the
// app's dual-band detection looks for, and a wrong one silently costs the C5 its 5 GHz choices.
#if CONFIG_IDF_TARGET_ESP32C5
static const char *CHIP_NAME = "ESP32-C5";
#elif CONFIG_IDF_TARGET_ESP32C3
static const char *CHIP_NAME = "ESP32-C3";
#else
static const char *CHIP_NAME = "esp32";
#endif

/** True on silicon with a 5 GHz radio: the C5. The C3 build compiles the band code out. */
#if defined(CONFIG_SOC_WIFI_SUPPORT_5G) && CONFIG_SOC_WIFI_SUPPORT_5G
#define SENSOR_DUAL_BAND 1
#else
#define SENSOR_DUAL_BAND 0
#endif

// How many findings one sweep lists. The app reads at most 8 000 characters per poll, roughly a
// hundred of these lines, and the whole sweep is still counted when the listing has to stop - so
// its INTEL row is right even if this cap cuts the tail. The Wi-Fi cap is shared by both bands
// and the list keeps the loudest; a 5 GHz network usually arrives weaker than the 2.4 GHz ones
// around it, so the cap is sized to leave the second band room rather than crowd it out.
static const int MAX_AP_LINES = 64;
static const int MAX_BLE_LINES = 32;

// A sweep nobody asked for keeps the counts and the BLE advert fresh, and prints nothing but its
// totals: the app's poll then always has room in the transcript for the sweep it asked for. Both
// are slower than the poll cycle because a board is a sensor, not a spectrum analyser.
static const unsigned long QUIET_WIFI_MS = 30000;
static const unsigned long QUIET_BLE_MS = 30000;
static const unsigned long ADVERT_MS = 2000;
static const unsigned long FIX_PRINT_MS = 15000;

// A module that stops talking has stopped knowing where it is. Twelve seconds is longer than one
// NMEA burst at 1 Hz and shorter than the app's idea of a live fix.
static const unsigned long GPS_STALE_MS = 12000;

// Seconds per passive BLE sweep. Long enough for advertisers on a one-second interval, and short
// enough that a `scanap` and a `blescan` in the same poll both finish inside the app's read
// window.
static const int BLE_SCAN_MS = 2000;

// Marker before each blocking call: a board that stops answering names the sweep it died in
// instead of just going quiet. Off by default, because every trace line is a line the app's
// parser has to step over.
#define BOOT_TRACE 0

#if BOOT_TRACE
#define TRACE(...) say(__VA_ARGS__)
#else
#define TRACE(...) ((void)0)
#endif

// ---------------------------------------------------------------- text

static void say(const char *fmt, ...) {
  char line[224];
  va_list ap;
  va_start(ap, fmt);
  int n = vsnprintf(line, sizeof(line), fmt, ap);
  va_end(ap);
  if (n <= 0) return;
  size_t len = (size_t)n < sizeof(line) ? (size_t)n : sizeof(line) - 1;
  // A console with nobody reading it must not stall a sweep: writes give up after 50 ms instead
  // of blocking the loop until a host drains the FIFO, which is what a board on a charger did.
  usb_serial_jtag_write_bytes(line, len, pdMS_TO_TICKS(50));
}

/**
 * One console-safe field.
 *
 * A console line is the only structure the app has, so a network or device name must not carry a
 * newline, a comma or a pipe into it. Anything unprintable becomes a space, and the result is
 * trimmed and capped, which is also what keeps a broken advertiser from printing garbage.
 */
static void copyText(char *out, size_t size, const char *in, size_t len) {
  if (size == 0) return;
  size_t j = 0;
  bool space = false;
  for (size_t i = 0; i < len && in[i] != '\0' && j + 1 < size; i++) {
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

static unsigned long nowMs() { return (unsigned long)(esp_timer_get_time() / 1000LL); }

// ---------------------------------------------------------------- settings (NVS)

static nvs_handle_t prefs;
static bool prefsReady = false;

static void prefsOpen() {
  if (prefsReady) return;
  prefsReady = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &prefs) == ESP_OK;
}

static int prefsGet(const char *key, int fallback) {
  if (!prefsReady) return fallback;
  int32_t value = 0;
  if (nvs_get_i32(prefs, key, &value) != ESP_OK) return fallback;
  return (int)value;
}

static void prefsPut(const char *key, int value) {
  if (!prefsReady) return;
  nvs_set_i32(prefs, key, (int32_t)value);
  nvs_commit(prefs);
}

// ---------------------------------------------------------------- GPS

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

static char deviceName[24];

static void gpsBegin() {
  if (gpsReady) {
    uart_driver_delete(UART_NUM_1);
    gpsReady = false;
  }
  uart_config_t cfg = {};
  cfg.baud_rate = gpsBaud;
  cfg.data_bits = UART_DATA_8_BITS;
  cfg.parity = UART_PARITY_DISABLE;
  cfg.stop_bits = UART_STOP_BITS_1;
  cfg.flow_ctrl = UART_HW_FLOWCTRL_DISABLE;
  cfg.source_clk = UART_SCLK_DEFAULT;
  if (uart_driver_install(UART_NUM_1, 2048, 0, 0, nullptr, 0) != ESP_OK) return;
  if (uart_param_config(UART_NUM_1, &cfg) != ESP_OK) return;
  // RX only: the module's TX goes to gpsPin, and the board never talks to it.
  if (uart_set_pin(UART_NUM_1, UART_PIN_NO_CHANGE, gpsPin, UART_PIN_NO_CHANGE,
                   UART_PIN_NO_CHANGE) != ESP_OK) {
    return;
  }
  gpsReady = true;
}

/** NMEA coordinate ddmm.mmmm -> degrees, with the hemisphere as a letter. */
static double nmeaDegrees(const char *value, const char *hemisphere) {
  if (value[0] == '\0') return 0.0;
  double raw = strtod(value, nullptr);
  int whole = (int)(raw / 100.0);
  double out = whole + (raw - whole * 100.0) / 60.0;
  if (hemisphere[0] == 'S' || hemisphere[0] == 'W') out = -out;
  return out;
}

/**
 * One NMEA sentence into the fix.
 *
 * GGA carries everything at once - fix quality, satellites, HDOP and altitude - so it is read in
 * preference to RMC, which only states the position and whether the fix is valid.
 */
static void gpsSentence(const char *line) {
  if (line[0] != '$') return;
  const char *star = strchr(line, '*');
  size_t bodyLen = star ? (size_t)(star - line) : strlen(line);
  if (bodyLen < 7) return;
  gpsLastSentence = nowMs();
  // Fields split into a fixed array: NMEA sentences never exceed 20 fields, and a heap
  // allocation per sentence on a board that runs for days is how fragmentation starts.
  const char *field[20] = {};
  int n = 0;
  size_t start = 0;
  for (size_t i = 0; i <= bodyLen && n < 20; i++) {
    if (i == bodyLen || line[i] == ',') {
      field[n++] = line + start;
      start = i + 1;
    }
  }
  const char *kind = line + 3;
  if (strncmp(kind, "GGA", 3) == 0 && n >= 10) {
    int quality = atoi(field[6]);
    gpsFix = quality > 0;
    gpsSats = atoi(field[7]);
    gpsHdop = (float)atof(field[8]);
    if (gpsFix) {
      gpsLat = nmeaDegrees(field[2], field[3]);
      gpsLon = nmeaDegrees(field[4], field[5]);
      gpsAlt = (float)atof(field[9]);
    }
  } else if (strncmp(kind, "RMC", 3) == 0 && n >= 7) {
    gpsFix = field[2][0] == 'A';
    if (gpsFix) {
      gpsLat = nmeaDegrees(field[3], field[4]);
      gpsLon = nmeaDegrees(field[5], field[6]);
    }
  }
}

static void gpsPump() {
  if (!gpsReady) return;
  static char line[120];
  static size_t len = 0;
  uint8_t buf[64];
  int n;
  while ((n = uart_read_bytes(UART_NUM_1, buf, sizeof(buf), 0)) > 0) {
    for (int i = 0; i < n; i++) {
      char c = (char)buf[i];
      if (c == '\n' || c == '\r') {
        if (len > 0) {
          line[len] = '\0';
          gpsSentence(line);
          len = 0;
        }
      } else if (len < sizeof(line) - 1) {
        line[len++] = c;
      }
    }
  }
  // A module unplugged mid-drive must stop placing the board at its last known position.
  if (gpsFix && gpsLastSentence != 0 && nowMs() - gpsLastSentence > GPS_STALE_MS) {
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
    say("GPS: 3D fix, Sats: %d, HDOP: %.2f\r\n", gpsSats, gpsHdop);
    say("Lat: %.6f\r\n", gpsLat);
    say("Lon: %.6f\r\n", gpsLon);
    say("Alt: %.0f\r\n", gpsAlt);
  } else {
    say("GPS: no fix, Sats: %d, HDOP: 0.00\r\n", gpsSats);
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
 * loudest ones. Keeping them sorted as they arrive means the cap falls on the tail instead of on
 * whatever happened to be scanned last.
 */
static void apRemember(const char *bssid, const char *ssid, size_t ssidLen, int rssi, int ch) {
  int at = 0;
  while (at < apTopCount && apTop[at].rssi >= rssi) at++;
  if (at >= MAX_AP_LINES) return;
  int last = apTopCount < MAX_AP_LINES ? apTopCount : MAX_AP_LINES - 1;
  for (int i = last; i > at; i--) apTop[i] = apTop[i - 1];
  snprintf(apTop[at].bssid, sizeof(apTop[at].bssid), "%s", bssid);
  copyText(apTop[at].ssid, sizeof(apTop[at].ssid), ssid, ssidLen);
  apTop[at].rssi = rssi;
  apTop[at].ch = ch;
  if (apTopCount < MAX_AP_LINES) apTopCount++;
}

#if SENSOR_DUAL_BAND
/**
 * Puts the radio in one band before a pass.
 *
 * The call only works once Wi-Fi is started, and a refusal - which is what a 5 GHz request gets
 * while the BLE controller is advertising on this chip - leaves the radio in the band it was
 * already in. The pass then still runs and still reports what it hears, it is just not the band
 * that was asked for, which is why the caller asks again next sweep instead of trusting this.
 */
static bool bandSelect(wifi_band_mode_t mode) {
  esp_err_t err = esp_wifi_set_band_mode(mode);
  if (err != ESP_OK) {
    TRACE("trace: band mode %d refused (%d)\r\n", (int)mode, (int)err);
    return false;
  }
  return true;
}
#endif

/** One pass over the band the radio is in. `total` carries across bands; the listing is shared. */
static bool wifiBandScan(bool list, int &total) {
  TRACE("trace: scan call\r\n");
  wifi_scan_config_t scan = {};
  scan.channel = 0;  // every channel of the current band
  scan.show_hidden = true;
  scan.scan_type = WIFI_SCAN_TYPE_ACTIVE;
  scan.scan_time.active.min = 80;
  scan.scan_time.active.max = 240;
  // The driver's own blocking call, with a per-channel ceiling: a scan that never returns would
  // leave the board deaf, which on this hardware looks exactly like a board that cannot boot.
  if (esp_wifi_scan_start(&scan, true) != ESP_OK) {
    TRACE("trace: scan would not start\r\n");
    return false;
  }
  uint16_t found = 0;
  if (esp_wifi_scan_get_ap_num(&found) != ESP_OK) return false;
  TRACE("trace: scan returned %d\r\n", (int)found);
  total += found;
  if (found > 0) {
    wifi_ap_record_t *records =
        (wifi_ap_record_t *)malloc(sizeof(wifi_ap_record_t) * (size_t)found);
    if (records != nullptr) {
      uint16_t got = found;
      if (esp_wifi_scan_get_ap_records(&got, records) == ESP_OK && list) {
        for (int i = 0; i < got; i++) {
          char bssid[18];
          snprintf(bssid, sizeof(bssid), "%02X:%02X:%02X:%02X:%02X:%02X", records[i].bssid[0],
                   records[i].bssid[1], records[i].bssid[2], records[i].bssid[3],
                   records[i].bssid[4], records[i].bssid[5]);
          apRemember(bssid, (const char *)records[i].ssid, strnlen((const char *)records[i].ssid,
                                                                     sizeof(records[i].ssid)),
                     records[i].rssi, records[i].primary);
        }
      }
      free(records);
    }
  }
  // Frees whatever the driver still holds for this scan, so the next band starts clean.
  esp_wifi_clear_ap_list();
  return true;
}

static bool advActive();
static void advRestart(bool force);

/**
 * A sweep, and on a C5 one pass per band.
 *
 * `esp_wifi_scan_start()` only covers the band the radio is currently in, so both bands means
 * switching between passes. The advertisement shares the radio with the scan, and a 5 GHz scan
 * start is refused outright while the BLE controller is advertising (espressif/esp-idf#16059),
 * so it is parked for the two passes and restarted as soon as the sweep is over.
 */
static int wifiSweep(bool list) {
  if (list) apTopCount = 0;
  int total = 0;
#if SENSOR_DUAL_BAND
  if (list) {
    bool advertising = advActive();
    if (advertising) ble_gap_adv_stop();
    bandSelect(WIFI_BAND_MODE_2G_ONLY);
    wifiBandScan(list, total);
    bandSelect(WIFI_BAND_MODE_5G_ONLY);
    wifiBandScan(list, total);
    // Back to 2.4 GHz, which is where the quiet sweeps and the advertisement expect the radio.
    bandSelect(WIFI_BAND_MODE_2G_ONLY);
    if (advertising) advRestart(true);
  } else {
    // Nobody asked for this pass: one band keeps the counts and the advert fresh without
    // holding the console for two sweeps, which is what a poll's verbs need.
    bandSelect(WIFI_BAND_MODE_2G_ONLY);
    wifiBandScan(list, total);
  }
#else
  wifiBandScan(list, total);
#endif
  wifiCount = total;
  if (!list) return total;

  say("APs found: %d\r\n", total);
  for (int i = 0; i < apTopCount; i++) {
    // A hidden network has no name to print; the address and the channel are the finding.
    if (apTop[i].ssid[0] != '\0') {
      say("AP %s RSSI %d CH %d SSID=%s\r\n", apTop[i].bssid, apTop[i].rssi, apTop[i].ch,
          apTop[i].ssid);
    } else {
      say("AP %s RSSI %d CH %d\r\n", apTop[i].bssid, apTop[i].rssi, apTop[i].ch);
    }
  }
  if (total > apTopCount) say("; %d more not listed\r\n", total - apTopCount);
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
static int bleSeen = 0;

static bool bleSynced = false;
static uint8_t ownAddrType = BLE_OWN_ADDR_PUBLIC;
static SemaphoreHandle_t discDone = nullptr;

static void addrText(const ble_addr_t &addr, char *out, size_t size) {
  // NimBLE keeps the bytes least significant first; the canonical form is the reverse.
  snprintf(out, size, "%02X:%02X:%02X:%02X:%02X:%02X", addr.val[5], addr.val[4], addr.val[3],
           addr.val[2], addr.val[1], addr.val[0]);
}

static void bleRemember(const char *addr, const char *name, size_t nameLen, int rssi) {
  int at = 0;
  while (at < bleTopCount && bleTop[at].rssi >= rssi) at++;
  if (at >= MAX_BLE_LINES) return;
  int last = bleTopCount < MAX_BLE_LINES ? bleTopCount : MAX_BLE_LINES - 1;
  for (int i = last; i > at; i--) bleTop[i] = bleTop[i - 1];
  snprintf(bleTop[at].addr, sizeof(bleTop[at].addr), "%s", addr);
  copyText(bleTop[at].name, sizeof(bleTop[at].name), name, nameLen);
  bleTop[at].rssi = rssi;
  if (bleTopCount < MAX_BLE_LINES) bleTopCount++;
}

/** The local name out of one advertising payload, if it carries one. */
static void nameFromAdv(const uint8_t *data, size_t len, char *out, size_t size) {
  out[0] = '\0';
  size_t i = 0;
  while (i + 1 < len) {
    uint8_t fieldLen = data[i];
    if (fieldLen == 0) break;
    uint8_t type = data[i + 1];
    if ((type == 0x08 || type == 0x09) && fieldLen > 1) {
      size_t nameLen = fieldLen - 1;
      if (i + 2 + nameLen > len) nameLen = len - i - 2;
      copyText(out, size, (const char *)&data[i + 2], nameLen);
      return;
    }
    i += (size_t)fieldLen + 1;
  }
}

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
  b[15] = (uint8_t)(gpsSats < 0 ? 0 : (gpsSats > 255 ? 255 : gpsSats));
  b[16] = gpsHdop > 0.0f
              ? (uint8_t)((int)(gpsHdop * 10.0f) < 1 ? 1 : ((int)(gpsHdop * 10.0f) > 254
                                                               ? 254
                                                               : (int)(gpsHdop * 10.0f)))
              : 0xFF;
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

static uint8_t advLast[23];
static bool advHave = false;

static bool advActive() { return bleSynced && ble_gap_adv_active() != 0; }

static int onAdvEvent(struct ble_gap_event *event, void *arg) {
  (void)event;
  (void)arg;
  return 0;
}

/**
 * (Re)starts the advertisement with the current telemetry.
 *
 * The telemetry goes in the advertisement and the name in the scan response: 23 bytes of
 * manufacturer data plus the flags is 27, which fits a legacy 31-byte advertisement, while both
 * of them together does not, and a payload the controller rejects is an advertisement nobody
 * hears.
 */
static void advRestart(bool force) {
  if (!bleSynced) return;
  uint8_t b[23];
  buildAdvert(b);
  if (!force && advHave && memcmp(b, advLast, sizeof(b)) == 0 && ble_gap_adv_active()) return;
  memcpy(advLast, b, sizeof(b));
  advHave = true;

  ble_gap_adv_stop();

  struct ble_hs_adv_fields fields = {};
  fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
  fields.mfg_data = b;
  fields.mfg_data_len = sizeof(b);
  if (ble_gap_adv_set_fields(&fields) != 0) return;

  struct ble_hs_adv_fields rsp = {};
  rsp.name = (const uint8_t *)deviceName;
  rsp.name_len = (uint8_t)strlen(deviceName);
  rsp.name_is_complete = 1;
  if (ble_gap_adv_rsp_set_fields(&rsp) != 0) return;

  struct ble_gap_adv_params params = {};
  params.conn_mode = BLE_GAP_CONN_MODE_NON;
  params.disc_mode = BLE_GAP_DISC_MODE_GEN;
  ble_gap_adv_start(ownAddrType, nullptr, BLE_HS_FOREVER, &params, onAdvEvent, nullptr);
}

static int onDisc(struct ble_gap_event *event, void *arg) {
  (void)arg;
  switch (event->type) {
    case BLE_GAP_EVENT_DISC: {
      char addr[18];
      char name[33];
      addrText(event->disc.addr, addr, sizeof(addr));
      nameFromAdv(event->disc.data, event->disc.data_len, name, sizeof(name));
      bleSeen++;
      bleRemember(addr, name, strlen(name), event->disc.rssi);
      return 0;
    }
    case BLE_GAP_EVENT_DISC_COMPLETE:
      if (discDone != nullptr) xSemaphoreGive(discDone);
      return 0;
    default:
      return 0;
  }
}

/**
 * A passive BLE sweep.
 *
 * Passive on purpose: sweeping is listening, and answering a scan request would both cost air
 * time and make the board visible as an active prober. The scan is bounded by the controller's
 * own timer, so a controller that stops reporting still returns here and the board keeps
 * answering its console.
 */
static int bleSweep(bool list) {
  if (!bleSynced) return 0;
  if (list) {
    bleTopCount = 0;
    bleSeen = 0;
  }
  struct ble_gap_disc_params params = {};
  params.itvl = 100;
  params.window = 80;
  params.passive = 1;
  params.filter_duplicates = 1;
  if (discDone != nullptr) xSemaphoreTake(discDone, 0);
  if (ble_gap_disc(ownAddrType, BLE_SCAN_MS, &params, onDisc, nullptr) != 0) return 0;
  if (discDone != nullptr) xSemaphoreTake(discDone, pdMS_TO_TICKS(BLE_SCAN_MS + 1000));

  bleCount = bleSeen;
  // A scan can leave the controller's advertising state changed: restart it only when it has
  // actually stopped.
  if (!ble_gap_adv_active()) advRestart(true);
  if (!list) return bleCount;

  say("BLE devices: %d\r\n", bleCount);
  for (int i = 0; i < bleTopCount; i++) {
    // An advertiser with no name in its packet is still a finding; printing an empty NAME= only
    // gives the parser a field with nothing in it.
    if (bleTop[i].name[0] != '\0') {
      say("BLE %s RSSI %d NAME=%s\r\n", bleTop[i].addr, bleTop[i].rssi, bleTop[i].name);
    } else {
      say("BLE %s RSSI %d\r\n", bleTop[i].addr, bleTop[i].rssi);
    }
  }
  if (bleCount > bleTopCount) say("; %d more not listed\r\n", bleCount - bleTopCount);
  return bleCount;
}

static void onBleSync() {
  ble_hs_util_ensure_addr(0);
  int rc = ble_hs_id_infer_auto(0, &ownAddrType);
  if (rc != 0) ownAddrType = BLE_OWN_ADDR_PUBLIC;
  bleSynced = true;
  advRestart(true);
}

static void onBleReset(int reason) {
  (void)reason;
  bleSynced = false;
}

static void bleHostTask(void *param) {
  (void)param;
  nimble_port_run();
  nimble_port_freertos_deinit();
}

// ---------------------------------------------------------------- console

static void printStatus() {
  say("%s v%s esp32 (%s)\r\n", FW_TAG, FW_VERSION, CHIP_NAME);
  say("APs found: %d\r\n", wifiCount);
  say("BLE devices: %d\r\n", bleCount);
  printFix();
  say("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
}

static void printHelp() {
  say("%s v%s esp32 (%s)\r\n", FW_TAG, FW_VERSION, CHIP_NAME);
  say("commands: help status gps scan scanap blescan bledetect stopscan gpspin <n> gpsbaud <n> "
      "save reboot\r\n");
}

static void saveConfig() {
  prefsPut("gpspin", gpsPin);
  prefsPut("gpsbaud", gpsBaud);
}

static bool equalsIgnoreCase(const char *a, const char *b) {
  while (*a != '\0' && *b != '\0') {
    char ca = (*a >= 'A' && *a <= 'Z') ? (char)(*a + 32) : *a;
    char cb = (*b >= 'A' && *b <= 'Z') ? (char)(*b + 32) : *b;
    if (ca != cb) return false;
    a++;
    b++;
  }
  return *a == '\0' && *b == '\0';
}

static void handleConsole(char *line) {
  // Trim both ends: a console that sends "scan " means the same as "scan".
  while (*line == ' ') line++;
  size_t len = strlen(line);
  while (len > 0 && (line[len - 1] == ' ' || line[len - 1] == '\t')) line[--len] = '\0';

  if (len == 0) {
    // A panel that has just opened is talking to a board that booted long ago, so one blank line
    // is treated as "who are you": banner, command list and the current GPS setting.
    printHelp();
    say("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
    return;
  }

  char *arg = strchr(line, ' ');
  if (arg != nullptr) {
    *arg = '\0';
    arg++;
    while (*arg == ' ') arg++;
  }
  const char *verb = line;

  if (equalsIgnoreCase(verb, "help") || equalsIgnoreCase(verb, "?")) {
    printHelp();
  } else if (equalsIgnoreCase(verb, "version") || equalsIgnoreCase(verb, "chipinfo")) {
    say("%s v%s esp32 (%s)\r\n", FW_TAG, FW_VERSION, CHIP_NAME);
  } else if (equalsIgnoreCase(verb, "status")) {
    printStatus();
  } else if (equalsIgnoreCase(verb, "scan") || equalsIgnoreCase(verb, "scanap")) {
    wifiSweep(true);
  } else if (equalsIgnoreCase(verb, "blescan") || equalsIgnoreCase(verb, "bledetect")) {
    bleSweep(true);
  } else if (equalsIgnoreCase(verb, "stopscan")) {
    // Nothing is ever left running: every sweep above is one blocking pass that has already
    // finished printing by the time the next command is read.
    say("no sweep running\r\n");
  } else if (equalsIgnoreCase(verb, "gps")) {
    printFix();
  } else if (equalsIgnoreCase(verb, "gpspin")) {
    int pin = arg != nullptr ? atoi(arg) : -1;
    // 0 is a real pin; the check is on the parse, not the value. Beyond 48 nothing on either chip
    // can drive a UART, so a typo is rejected rather than saved.
    if (arg == nullptr || pin < 0 || pin > 48) {
      say("GPS pin: %d (give gpspin <0-48>)\r\n", gpsPin);
      return;
    }
    if (pin == 20 || pin == 21) {
      // These pads are spoken for on a mini board; using one kills the console.
      say("GPS pin: %d rejected, reserved on a mini board\r\n", pin);
      return;
    }
    gpsPin = pin;
    gpsBegin();
    saveConfig();
    say("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
  } else if (equalsIgnoreCase(verb, "gpsbaud")) {
    int rate = arg != nullptr ? atoi(arg) : 0;
    if (rate < 1200 || rate > 921600) {
      say("GPS baud: %d (give gpsbaud <1200-921600>)\r\n", gpsBaud);
      return;
    }
    gpsBaud = rate;
    gpsBegin();
    saveConfig();
    say("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
  } else if (equalsIgnoreCase(verb, "save")) {
    saveConfig();
    say("saved: pin %d baud %d\r\n", gpsPin, gpsBaud);
  } else if (equalsIgnoreCase(verb, "reboot")) {
    say("rebooting\r\n");
    vTaskDelay(pdMS_TO_TICKS(100));
    esp_restart();
  } else {
    say("unknown command: %s\r\n", verb);
  }
}

static char serialLine[96];
static size_t serialLen = 0;
static bool lastWasCr = false;

static void consolePump() {
  uint8_t buf[64];
  int n;
  while ((n = usb_serial_jtag_read_bytes(buf, sizeof(buf), 0)) > 0) {
    for (int i = 0; i < n; i++) {
      char c = (char)buf[i];
      if (c == '\r' || c == '\n') {
        // CRLF is one line ending, not two: the second half is swallowed so a blank line prints
        // its banner once.
        if (c == '\n' && lastWasCr) {
          lastWasCr = false;
          continue;
        }
        lastWasCr = (c == '\r');
        serialLine[serialLen] = '\0';
        handleConsole(serialLine);
        serialLen = 0;
      } else {
        lastWasCr = false;
        if (serialLen < sizeof(serialLine) - 1) serialLine[serialLen++] = c;
      }
    }
  }
}

// ---------------------------------------------------------------- lifecycle

/**
 * Why the board started this time.
 *
 * A board that resets over and over is usually not the firmware: a brown-out is the supply or the
 * cable, and a panic is where in startup it died. Naming the reason on the first line of the boot
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

static void wifiBegin() {
  ESP_ERROR_CHECK(esp_netif_init());
  ESP_ERROR_CHECK(esp_event_loop_create_default());
  esp_netif_create_default_wifi_sta();
  wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
  ESP_ERROR_CHECK(esp_wifi_init(&init));
  // Never connected: the sensor only listens, so nothing has to survive a reboot in NVS.
  ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
  ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
  ESP_ERROR_CHECK(esp_wifi_start());
  esp_wifi_set_ps(WIFI_PS_NONE);
#if SENSOR_DUAL_BAND
  // A C5 rests on 2.4 GHz. The band can only be selected once the driver is started, which is
  // exactly what the sketch could not do: it asked before the wrapper had started Wi-Fi, and the
  // call answered ESP_ERR_WIFI_NOT_STARTED.
  bandSelect(WIFI_BAND_MODE_2G_ONLY);
#endif
}

static void bleBegin() {
  if (nimble_port_init() != ESP_OK) return;
  ble_hs_cfg.sync_cb = onBleSync;
  ble_hs_cfg.reset_cb = onBleReset;
  ble_svc_gap_init();
  ble_svc_gap_device_name_set(deviceName);
  nimble_port_freertos_init(bleHostTask);
}

extern "C" void app_main(void) {
  // The app reads this console as a transcript, so it is opened before anything else: a board
  // that dies bringing up a radio has to have said something by then.
  usb_serial_jtag_driver_config_t consoleCfg = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
  usb_serial_jtag_driver_install(&consoleCfg);
  vTaskDelay(pdMS_TO_TICKS(200));

  say("\r\n%s v%s esp32 (%s)\r\n", FW_TAG, FW_VERSION, CHIP_NAME);
  say("boot: %s\r\n", resetReasonName());

  esp_err_t nvs = nvs_flash_init();
  if (nvs == ESP_ERR_NVS_NO_FREE_PAGES || nvs == ESP_ERR_NVS_NEW_VERSION_FOUND) {
    nvs_flash_erase();
    nvs_flash_init();
  }
  prefsOpen();
  gpsPin = prefsGet("gpspin", gpsPin);
  gpsBaud = prefsGet("gpsbaud", gpsBaud);
  // A pin saved by an older build, or for a different board, must not be trusted at boot: a UART
  // started on a USB or flash pad leaves the console deaf, which looks the same as a board that
  // cannot stay up. Out-of-range values fall back instead of being applied.
  if (gpsPin < 0 || gpsPin > 48 || gpsPin == 20 || gpsPin == 21) gpsPin = 4;
  if (gpsBaud < 1200 || gpsBaud > 921600) gpsBaud = 9600;
  gpsBegin();

  uint8_t mac[6] = {};
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  snprintf(deviceName, sizeof(deviceName), "IntelGo-%04X",
           (unsigned)((mac[4] << 8) | mac[5]));

  wifiBegin();
  discDone = xSemaphoreCreateBinary();
  bleBegin();

  say("GPS pin: %d baud: %d\r\n", gpsPin, gpsBaud);
  say("type help for commands\r\n");
  say("ready\r\n");

  while (true) {
    consolePump();
    gpsPump();

    unsigned long now = nowMs();

    // Quiet sweeps: counts and advert only, so a poll transcript is never filled with a sweep
    // nobody asked for.
    if (lastWifiScan == 0 || now - lastWifiScan > QUIET_WIFI_MS) {
      lastWifiScan = now;
      wifiSweep(false);
    }
    if (lastBleScan == 0 || now - lastBleScan > QUIET_BLE_MS) {
      lastBleScan = now;
      bleSweep(false);
    }
    if (lastAdvert == 0 || now - lastAdvert > ADVERT_MS) {
      lastAdvert = now;
      advRestart(false);
    }

    // A fix is repeated between polls so a phone without one of its own can still place the map,
    // and nothing is printed while there is no fix to repeat.
    if (gpsFix && (lastFixPrint == 0 || now - lastFixPrint > FIX_PRINT_MS)) {
      lastFixPrint = now;
      printFix();
    }

    vTaskDelay(pdMS_TO_TICKS(10));
  }
}
