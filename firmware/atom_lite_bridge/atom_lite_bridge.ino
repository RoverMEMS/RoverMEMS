/*
 * RoverMEMS BLE bridge for M5Stack ATOM Lite (ESP32-PICO-D4)
 *
 * スマホ(RoverMEMSアプリ)とECU診断ポートの間で、データをそのまま横流しする
 * だけのプログラム。HM-10の代わりに使う(ATOM Liteは技適取得済み)。
 *
 *   スマホ --BLE(Nordic UART Service)--> ATOM Lite --UART 9600 8N1--> レベル変換 --> ECU
 *
 * アプリ側はNordic UART Service(NUS)に既に対応済み(BleUartProfiles.NORDIC_UART)。
 * スマホ→ECUの特性は「応答なし書き込み」だけを公開する。応答ありだと
 * 1バイトごとに往復が増えてコマンド間隔が延び、ECUが初期化を受け付けなく
 * なる(09-27の知見: CA→75の間隔は約80〜110msが限度)。
 *
 * 配線(ATOM Lite側):
 *   G26 = UART送信 → レベル変換 LV1 → HV1 → ECU緑線(ECU RX)
 *   G32 = UART受信 ← レベル変換 LV2 ← HV2 ← ECU白線(ECU TX)
 *   3V3 → レベル変換 LV / 5V → レベル変換 HV / GND → レベル変換 GND
 *
 * 本体LED:
 *   青の点滅 = スマホからの接続待ち
 *   緑       = スマホと接続中
 *   白く一瞬 = ECUからデータを受信
 *
 * Arduino IDE + "esp32 by Espressif Systems"(2.x/3.x どちらでも可)でビルド。
 * ボードは "M5Atom"、書き込み速度(Upload Speed)は 115200 にする。
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ---- 設定 ----
static const int UART_TX_PIN = 26;   // → ECU緑線(レベル変換経由)
static const int UART_RX_PIN = 32;   // ← ECU白線(レベル変換経由)
static const uint32_t ECU_BAUD = 9600;
static const int LED_PIN = 27;       // ATOM Lite内蔵のRGB LED(SK6812)

// 受信した連続バイトをまとめて1回で通知するための待ち時間。9600bpsでは
// 1バイト約1.04ms。この時間バイトが来なければ区切りとみなして送る。
static const uint32_t RX_GAP_US = 1500;
// 1回の通知の最大バイト数。アプリはMTUを交渉しないので既定の23-3=20。
static const size_t NOTIFY_CHUNK = 20;

#define NUS_SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_RX_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  // スマホ → ATOM
#define NUS_TX_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  // ATOM → スマホ

HardwareSerial EcuSerial(1);
BLEServer *server = nullptr;
BLECharacteristic *txChar = nullptr;

volatile bool deviceConnected = false;
bool wasConnected = false;

uint8_t rxBuf[NOTIFY_CHUNK];
size_t rxLen = 0;
uint32_t lastRxMicros = 0;
uint32_t ledFlashUntil = 0;

// LEDの書き換えは数十µsかかるので、色が変わった時だけ行う(UART受信を遅らせない)。
static void setLed(uint8_t r, uint8_t g, uint8_t b) {
  static uint32_t lastColor = 0xFFFFFFFF;
  uint32_t color = ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
  if (color == lastColor) return;
  lastColor = color;
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  rgbLedWrite(LED_PIN, r, g, b);
#else
  neopixelWrite(LED_PIN, r, g, b);
#endif
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    deviceConnected = true;
  }
  void onDisconnect(BLEServer *s) override {
    deviceConnected = false;
  }
};

// スマホから届いたバイトをそのままECUへ送る。
class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    uint8_t *data = c->getData();
    size_t len = c->getLength();
    if (data != nullptr && len > 0) {
      EcuSerial.write(data, len);
    }
  }
};

static void flushToPhone() {
  if (rxLen == 0) return;
  if (deviceConnected) {
    txChar->setValue(rxBuf, rxLen);
    txChar->notify();
    ledFlashUntil = millis() + 30;
  }
  rxLen = 0;
}

void setup() {
  Serial.begin(115200);
  EcuSerial.begin(ECU_BAUD, SERIAL_8N1, UART_RX_PIN, UART_TX_PIN);

  // 同じ名前のモジュールが複数あっても区別できるよう、MACアドレス末尾を付ける。
  uint64_t mac = ESP.getEfuseMac();
  char name[24];
  snprintf(name, sizeof(name), "RoverMEMS-%02X%02X",
           (uint8_t)(mac >> 32), (uint8_t)(mac >> 40));

  BLEDevice::init(name);
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(NUS_SERVICE_UUID);

  txChar = service->createCharacteristic(NUS_TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());

  BLECharacteristic *rxChar =
      service->createCharacteristic(NUS_RX_UUID, BLECharacteristic::PROPERTY_WRITE_NR);
  rxChar->setCallbacks(new RxCallbacks());

  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.printf("RoverMEMS bridge ready: %s\n", name);
}

void loop() {
  // ECU → スマホ: 受信バイトを溜め、区切り(RX_GAP_US)か満杯で通知する。
  while (EcuSerial.available()) {
    rxBuf[rxLen++] = (uint8_t)EcuSerial.read();
    lastRxMicros = micros();
    if (rxLen >= NOTIFY_CHUNK) flushToPhone();
  }
  if (rxLen > 0 && (micros() - lastRxMicros) >= RX_GAP_US) {
    flushToPhone();
  }

  // 接続状態の変化
  if (!deviceConnected && wasConnected) {
    // 切断されたら少し待って再びアドバタイズ(アプリの自動つなぎ直しに備える)。
    delay(200);
    rxLen = 0;
    while (EcuSerial.available()) EcuSerial.read();
    BLEDevice::startAdvertising();
    Serial.println("disconnected, advertising again");
  }
  if (deviceConnected && !wasConnected) {
    while (EcuSerial.available()) EcuSerial.read();
    Serial.println("connected");
  }
  wasConnected = deviceConnected;

  // LED表示
  uint32_t now = millis();
  if (now < ledFlashUntil) {
    setLed(20, 20, 20);
  } else if (deviceConnected) {
    setLed(0, 20, 0);
  } else {
    setLed(0, 0, ((now / 500) % 2) ? 20 : 0);
  }
}
