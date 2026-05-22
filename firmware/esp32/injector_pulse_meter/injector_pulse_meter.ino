/*
  302 Engine Monitor - ESP32 injector pulse prototype

  Measures pulse width on one injector input and reports timing over serial.
  Assumes a protected 3.3 V-safe input connected to INJECTOR_PIN.

  If connected to a low-side injector driver through a divider, the signal is
  commonly inverted: HIGH = injector off, LOW = injector on.
*/

#include <Arduino.h>
#include <ArduinoOTA.h>
#include <Preferences.h>
#include <WiFi.h>

#ifndef ENABLE_BLE
#define ENABLE_BLE 1
#endif

#if ENABLE_BLE
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#endif

constexpr uint8_t INJECTOR_PIN = 27;
constexpr bool INJECTOR_ACTIVE_LOW = true;
constexpr uint32_t REPORT_INTERVAL_MS = 500;
constexpr uint32_t STALE_SIGNAL_MS = 2000;
constexpr bool SIMULATED_TELEMETRY_DEFAULT = true;
constexpr float SIMULATED_SPEED_MPH = 55.0f;
constexpr float SIMULATED_MPG = 10.0f;
constexpr float SIMULATED_RPM = 2200.0f;

constexpr char BLE_DEVICE_NAME[] = "302 Fuel Monitor";
constexpr char BLE_SERVICE_UUID[] = "5f6d9f20-6f2d-4f51-a2c7-302000000001";
constexpr char BLE_TELEMETRY_CHAR_UUID[] = "5f6d9f20-6f2d-4f51-a2c7-302000000002";
constexpr char BLE_CONTROL_CHAR_UUID[] = "5f6d9f20-6f2d-4f51-a2c7-302000000003";

constexpr char OTA_HOSTNAME[] = "302-fuel-monitor";
constexpr char OTA_AP_SSID[] = "302FuelMonitor-OTA";
constexpr char OTA_AP_PASSWORD[] = "302fuel302";
constexpr uint32_t WIFI_CONNECT_TIMEOUT_MS = 12000;
constexpr uint32_t WIFI_RETRY_INTERVAL_MS = 30000;
constexpr uint8_t MAX_WIFI_NETWORKS = 6;

constexpr float DEFAULT_INJECTOR_FLOW_LB_PER_HOUR = 19.0f;
constexpr float DEFAULT_FUEL_DENSITY_LB_PER_GALLON = 6.17f;
constexpr uint8_t DEFAULT_INJECTOR_COUNT = 8;

volatile uint32_t lastEdgeMicros = 0;
volatile uint32_t currentPulseStartMicros = 0;
volatile uint32_t completedPulseWidthMicros = 0;
volatile uint32_t pulseCount = 0;
volatile uint64_t injectorOnMicrosAccum = 0;

uint32_t lastReportMillis = 0;
uint32_t lastPulseCount = 0;
uint64_t lastOnMicrosAccum = 0;

float injectorFlowLbPerHour = DEFAULT_INJECTOR_FLOW_LB_PER_HOUR;
float fuelDensityLbPerGallon = DEFAULT_FUEL_DENSITY_LB_PER_GALLON;
uint8_t injectorCount = DEFAULT_INJECTOR_COUNT;
bool simulatedTelemetryEnabled = SIMULATED_TELEMETRY_DEFAULT;
bool wifiServiceEnabled = false;
String wifiSsid;
String wifiPassword;
bool wifiConfigured = false;
String wifiSsids[MAX_WIFI_NETWORKS];
String wifiPasswords[MAX_WIFI_NETWORKS];
uint8_t wifiNetworkCount = 0;
int8_t activeWifiIndex = -1;
uint32_t lastWifiAttemptMillis = 0;
Preferences preferences;

#if ENABLE_BLE
BLECharacteristic *telemetryCharacteristic = nullptr;
BLECharacteristic *controlCharacteristic = nullptr;
bool bleClientConnected = false;
#endif
bool otaStarted = false;

bool isInjectorOn(int level) {
  return INJECTOR_ACTIVE_LOW ? (level == LOW) : (level == HIGH);
}

void IRAM_ATTR injectorEdgeIsr() {
  const uint32_t now = micros();
  const int level = digitalRead(INJECTOR_PIN);

  lastEdgeMicros = now;

  if (isInjectorOn(level)) {
    currentPulseStartMicros = now;
  } else if (currentPulseStartMicros != 0) {
    const uint32_t width = now - currentPulseStartMicros;
    completedPulseWidthMicros = width;
    injectorOnMicrosAccum += width;
    pulseCount++;
    currentPulseStartMicros = 0;
  }
}

#if ENABLE_BLE
void updateControlValue() {
  if (controlCharacteristic != nullptr) {
    String value = simulatedTelemetryEnabled ? "sim=1" : "sim=0";
    value += ",wifi_on=";
    value += wifiServiceEnabled ? "1" : "0";
    value += ",wifi=";
    value += WiFi.status() == WL_CONNECTED ? "1" : "0";
    controlCharacteristic->setValue(value.c_str());
  }
}

void applyCommand(String command, bool printUnknown);

class FuelMonitorControlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String command = characteristic->getValue();
    command.trim();
    if (command.length() > 0) {
      applyCommand(command, false);
    }
  }
};

class FuelMonitorServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleClientConnected = true;
  }

  void onDisconnect(BLEServer *server) override {
    bleClientConnected = false;
    server->getAdvertising()->start();
  }
};

void setupBle() {
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new FuelMonitorServerCallbacks());

  BLEService *service = server->createService(BLE_SERVICE_UUID);
  telemetryCharacteristic = service->createCharacteristic(
      BLE_TELEMETRY_CHAR_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);

  telemetryCharacteristic->addDescriptor(new BLE2902());
  telemetryCharacteristic->setValue("status=boot");

  controlCharacteristic = service->createCharacteristic(
      BLE_CONTROL_CHAR_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  controlCharacteristic->setCallbacks(new FuelMonitorControlCallbacks());
  updateControlValue();

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  advertising->start();
}
#endif

void loadDeviceSettings() {
  preferences.begin("fuelmon", false);
  simulatedTelemetryEnabled = preferences.getBool("sim", SIMULATED_TELEMETRY_DEFAULT);
  wifiServiceEnabled = preferences.getBool("wifi_on", false);
  wifiNetworkCount = preferences.getUChar("wifi_count", 0);
  if (wifiNetworkCount > MAX_WIFI_NETWORKS) {
    wifiNetworkCount = MAX_WIFI_NETWORKS;
  }
  for (uint8_t i = 0; i < wifiNetworkCount; i++) {
    wifiSsids[i] = preferences.getString(("ssid" + String(i)).c_str(), "");
    wifiPasswords[i] = preferences.getString(("pass" + String(i)).c_str(), "");
  }

  const String legacySsid = preferences.getString("ssid", "");
  if (wifiNetworkCount == 0 && legacySsid.length() > 0) {
    wifiSsids[0] = legacySsid;
    wifiPasswords[0] = preferences.getString("pass", "");
    wifiNetworkCount = 1;
    preferences.putUChar("wifi_count", wifiNetworkCount);
    preferences.putString("ssid0", wifiSsids[0]);
    preferences.putString("pass0", wifiPasswords[0]);
  }
  wifiConfigured = wifiNetworkCount > 0;
}

void saveWifiList() {
  preferences.putUChar("wifi_count", wifiNetworkCount);
  for (uint8_t i = 0; i < MAX_WIFI_NETWORKS; i++) {
    preferences.putString(("ssid" + String(i)).c_str(), i < wifiNetworkCount ? wifiSsids[i] : "");
    preferences.putString(("pass" + String(i)).c_str(), i < wifiNetworkCount ? wifiPasswords[i] : "");
  }
}

void rememberWifiCredentials(const String &ssid, const String &password) {
  int existingIndex = -1;
  for (uint8_t i = 0; i < wifiNetworkCount; i++) {
    if (wifiSsids[i] == ssid) {
      existingIndex = i;
      break;
    }
  }

  const uint8_t startShift = existingIndex >= 0 ? static_cast<uint8_t>(existingIndex) : (wifiNetworkCount < MAX_WIFI_NETWORKS ? wifiNetworkCount : MAX_WIFI_NETWORKS - 1);
  for (int i = startShift; i > 0; i--) {
    wifiSsids[i] = wifiSsids[i - 1];
    wifiPasswords[i] = wifiPasswords[i - 1];
  }

  wifiSsids[0] = ssid;
  wifiPasswords[0] = password;
  if (wifiNetworkCount < MAX_WIFI_NETWORKS) {
    wifiNetworkCount++;
  }
  wifiConfigured = wifiNetworkCount > 0;
  saveWifiList();
}

int findBestKnownWifi() {
  if (!wifiConfigured) {
    return -1;
  }

  int bestIndex = -1;
  int32_t bestRssi = -1000;
  const int scanCount = WiFi.scanNetworks(false, true);
  for (int found = 0; found < scanCount; found++) {
    const String foundSsid = WiFi.SSID(found);
    for (uint8_t known = 0; known < wifiNetworkCount; known++) {
      if (wifiSsids[known] == foundSsid && WiFi.RSSI(found) > bestRssi) {
        bestIndex = known;
        bestRssi = WiFi.RSSI(found);
      }
    }
  }
  WiFi.scanDelete();
  return bestIndex;
}

void connectKnownWifi(uint8_t index, bool waitForConnection) {
  if (!wifiServiceEnabled) {
    return;
  }
  if (index >= wifiNetworkCount) {
    return;
  }

  activeWifiIndex = index;
  wifiSsid = wifiSsids[index];
  wifiPassword = wifiPasswords[index];
  Serial.print("Connecting OTA STA WiFi to ");
  Serial.println(wifiSsid);
  WiFi.disconnect(false);
  WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
  lastWifiAttemptMillis = millis();

  if (!waitForConnection) {
    return;
  }

  const uint32_t startMs = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startMs < WIFI_CONNECT_TIMEOUT_MS) {
    delay(250);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("OTA STA WiFi ready. IP=");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("OTA STA WiFi not connected yet; AP fallback remains available.");
  }
#if ENABLE_BLE
  updateControlValue();
#endif
}

void connectBestKnownWifi(bool waitForConnection) {
  if (!wifiServiceEnabled) {
    return;
  }
  if (!wifiConfigured) {
    Serial.println("OTA STA WiFi not configured; AP fallback is available.");
    return;
  }

  const int bestIndex = findBestKnownWifi();
  if (bestIndex < 0) {
    Serial.println("No remembered OTA WiFi network visible; AP fallback is available.");
    lastWifiAttemptMillis = millis();
    return;
  }

  connectKnownWifi(static_cast<uint8_t>(bestIndex), waitForConnection);
}

void serviceWifiReconnect() {
  if (!wifiServiceEnabled || !wifiConfigured || WiFi.status() == WL_CONNECTED) {
    return;
  }
  if (millis() - lastWifiAttemptMillis >= WIFI_RETRY_INTERVAL_MS) {
    connectBestKnownWifi(false);
  }
}

void startWifiService() {
  wifiServiceEnabled = true;
  preferences.putBool("wifi_on", true);
  WiFi.mode(WIFI_AP_STA);
  const bool apStarted = WiFi.softAP(OTA_AP_SSID, OTA_AP_PASSWORD);
  if (wifiConfigured) {
    connectBestKnownWifi(true);
  }
  ArduinoOTA.setHostname(OTA_HOSTNAME);
  if (!otaStarted) {
    ArduinoOTA
        .onStart([]() {
          Serial.println("OTA update started");
        })
        .onEnd([]() {
          Serial.println("OTA update finished");
        })
        .onError([](ota_error_t error) {
          Serial.print("OTA error ");
          Serial.println(static_cast<unsigned int>(error));
        });
    ArduinoOTA.begin();
    otaStarted = true;
  }

  Serial.print("OTA AP ");
  Serial.print(apStarted ? "ready: " : "failed: ");
  Serial.print(OTA_AP_SSID);
  Serial.print(" IP=");
  Serial.println(WiFi.softAPIP());
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("OTA station hostname=");
    Serial.print(OTA_HOSTNAME);
    Serial.print(" IP=");
    Serial.println(WiFi.localIP());
  }
#if ENABLE_BLE
  updateControlValue();
#endif
}

void stopWifiService() {
  wifiServiceEnabled = false;
  preferences.putBool("wifi_on", false);
  if (otaStarted) {
    ArduinoOTA.end();
    otaStarted = false;
  }
  WiFi.softAPdisconnect(true);
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  activeWifiIndex = -1;
  lastWifiAttemptMillis = millis();
  Serial.println("OTA WiFi service stopped.");
#if ENABLE_BLE
  updateControlValue();
#endif
}

void setupOta() {
  if (wifiServiceEnabled) {
    startWifiService();
  } else {
    WiFi.mode(WIFI_OFF);
    Serial.println("OTA WiFi service off. Enable from BLE/app before OTA updates.");
  }
}

void printHelp() {
  Serial.println("Commands:");
  Serial.println("  flow=<lb_per_hr>    set injector size, e.g. flow=19");
  Serial.println("  injectors=<count>   set injector count, e.g. injectors=8");
  Serial.println("  density=<lb_per_gal> set fuel density, e.g. density=6.17");
  Serial.println("  sim=1               use bench simulator telemetry");
  Serial.println("  sim=0               use real injector input");
  Serial.println("  wifi_on=1           enable OTA WiFi service");
  Serial.println("  wifi_on=0           disable OTA WiFi service");
  Serial.println("  wifi=<ssid>|<pass>  remember OTA WiFi and connect");
  Serial.println("  cal                 print calibration");
}

void printCalibration() {
  Serial.print("cal flow_lb_hr=");
  Serial.print(injectorFlowLbPerHour, 2);
  Serial.print(" injectors=");
  Serial.print(injectorCount);
  Serial.print(" density_lb_gal=");
  Serial.print(fuelDensityLbPerGallon, 2);
  Serial.print(" sim=");
  Serial.print(simulatedTelemetryEnabled ? 1 : 0);
  Serial.print(" known_wifi=");
  Serial.print(wifiNetworkCount);
  Serial.print(" wifi_on=");
  Serial.print(wifiServiceEnabled ? 1 : 0);
  Serial.print(" wifi=");
  Serial.print(WiFi.status() == WL_CONNECTED ? 1 : 0);
  Serial.print(" ip=");
  Serial.println(WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : "0.0.0.0");
}

void applyCommand(String command, bool printUnknown) {
  command.trim();
  const String rawCommand = command;
  String commandLower = command;
  commandLower.toLowerCase();

  if (commandLower == "help" || commandLower == "?") {
    printHelp();
  } else if (commandLower == "cal") {
    printCalibration();
  } else if (commandLower.startsWith("flow=")) {
    const float value = commandLower.substring(5).toFloat();
    if (value > 0.0f && value < 200.0f) {
      injectorFlowLbPerHour = value;
      printCalibration();
    }
  } else if (commandLower.startsWith("injectors=")) {
    const int value = commandLower.substring(10).toInt();
    if (value > 0 && value <= 16) {
      injectorCount = static_cast<uint8_t>(value);
      printCalibration();
    }
  } else if (commandLower.startsWith("density=")) {
    const float value = commandLower.substring(8).toFloat();
    if (value > 4.0f && value < 9.0f) {
      fuelDensityLbPerGallon = value;
      printCalibration();
    }
  } else if (commandLower.startsWith("sim=")) {
    const int value = commandLower.substring(4).toInt();
    simulatedTelemetryEnabled = value != 0;
    preferences.putBool("sim", simulatedTelemetryEnabled);
#if ENABLE_BLE
    updateControlValue();
#endif
    printCalibration();
  } else if (commandLower.startsWith("wifi_on=")) {
    const int value = commandLower.substring(8).toInt();
    if (value != 0) {
      startWifiService();
    } else {
      stopWifiService();
    }
    printCalibration();
  } else if (commandLower.startsWith("wifi=")) {
    const int separator = rawCommand.indexOf('|', 5);
    if (separator > 5) {
      const String ssid = rawCommand.substring(5, separator);
      const String password = rawCommand.substring(separator + 1);
      rememberWifiCredentials(ssid, password);
      if (!wifiServiceEnabled) {
        startWifiService();
      }
      connectKnownWifi(0, false);
      printCalibration();
    }
  } else if (printUnknown && commandLower.length() > 0) {
    Serial.println("Unknown command. Type help.");
  }
}

void handleSerialCommands() {
  if (!Serial.available()) {
    return;
  }

  applyCommand(Serial.readStringUntil('\n'), true);
}

void setup() {
  Serial.begin(115200);
  delay(500);

  pinMode(INJECTOR_PIN, INPUT);
  attachInterrupt(digitalPinToInterrupt(INJECTOR_PIN), injectorEdgeIsr, CHANGE);
#if ENABLE_BLE
  loadDeviceSettings();
  setupBle();
#else
  loadDeviceSettings();
#endif
  setupOta();

  Serial.println("302 Engine Monitor ESP32 prototype");
#if ENABLE_BLE
  Serial.println("Reporting injector pulse timing over serial and BLE.");
#else
  Serial.println("Reporting injector pulse timing over serial. BLE disabled for this build.");
#endif
  printCalibration();
  printHelp();
}

void loop() {
  if (wifiServiceEnabled && otaStarted) {
    ArduinoOTA.handle();
  }
  serviceWifiReconnect();
  handleSerialCommands();

  const uint32_t nowMs = millis();
  if (nowMs - lastReportMillis < REPORT_INTERVAL_MS) {
    return;
  }

  noInterrupts();
  const uint32_t pulseWidth = completedPulseWidthMicros;
  const uint32_t pulses = pulseCount;
  const uint64_t onAccum = injectorOnMicrosAccum;
  const uint32_t lastEdge = lastEdgeMicros;
  interrupts();

  const uint32_t deltaPulses = pulses - lastPulseCount;
  const uint64_t deltaOnMicros = onAccum - lastOnMicrosAccum;
  const float windowSeconds = (nowMs - lastReportMillis) / 1000.0f;
  float duty = windowSeconds > 0.0f ? (deltaOnMicros / 1000000.0f) / windowSeconds : 0.0f;
  float pulsesPerSecond = windowSeconds > 0.0f ? deltaPulses / windowSeconds : 0.0f;
  bool signalActive = lastEdge != 0 && ((micros() - lastEdge) / 1000UL) < STALE_SIGNAL_MS;
  float gallonsPerHour = (injectorFlowLbPerHour * injectorCount * duty) / fuelDensityLbPerGallon;
  uint32_t reportedPulseWidth = deltaPulses > 0 ? pulseWidth : 0;

  if (simulatedTelemetryEnabled) {
    gallonsPerHour = SIMULATED_SPEED_MPH / SIMULATED_MPG;
    duty = (gallonsPerHour * fuelDensityLbPerGallon) / (injectorFlowLbPerHour * injectorCount);
    pulsesPerSecond = SIMULATED_RPM / 120.0f;
    reportedPulseWidth = static_cast<uint32_t>((duty / pulsesPerSecond) * 1000000.0f);
    signalActive = true;
  }

  lastReportMillis = nowMs;
  lastPulseCount = pulses;
  lastOnMicrosAccum = onAccum;

  const bool wifiConnected = WiFi.status() == WL_CONNECTED;
  const String otaIp = wifiConnected ? WiFi.localIP().toString() : "0.0.0.0";
  char packet[220];
  snprintf(packet, sizeof(packet),
           "width_us=%lu,pps=%.2f,duty=%.4f,gph=%.3f,flow_lb_hr=%.2f,injectors=%u,signal=%u,sim=%u,wifi_on=%u,wifi=%u,ip=%s",
           static_cast<unsigned long>(reportedPulseWidth),
           pulsesPerSecond,
           duty,
           gallonsPerHour,
           injectorFlowLbPerHour,
           injectorCount,
           signalActive ? 1 : 0,
           simulatedTelemetryEnabled ? 1 : 0,
           wifiServiceEnabled ? 1 : 0,
           wifiConnected ? 1 : 0,
           otaIp.c_str());

  Serial.println(packet);

#if ENABLE_BLE
  if (bleClientConnected && telemetryCharacteristic != nullptr) {
    telemetryCharacteristic->setValue(packet);
    telemetryCharacteristic->notify();
  }
#endif
}
