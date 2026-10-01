# RoverMEMS

クラシックミニ(ローバーミニ)のMEMS ECU向け、Android用の車両診断アプリです。USBまたはBluetooth経由でECUに接続し、ライブデータの表示・アクチュエータのテスト・データログの記録ができます。

> **開発ステータス: 正式リリース前(v0.1)**
> USB有線・Bluetooth無線(M5Stack ATOM Lite)とも、実車(1996年式ミニSPi / MEMS 1.3)での接続・ライブデータ取得を確認済みです。まだ開発中のバージョンのため、不具合が残っている可能性があります。

*English: RoverMEMS is a free Android diagnostic app for the Rover Mini's MEMS ECU (tested on MEMS 1.3 SPi). Connect over a USB serial cable or a Bluetooth LE module to view live data, run actuator tests and record logs. The UI can be switched to English.*

## 🧰 このページにある3つの道具

| 道具 | できること | 使い方 |
|---|---|---|
| **RoverMEMS アプリ**(Android) | ECUにつないでライブデータ表示・アクチュエータテスト・ログ記録 | 下の「ダウンロード」からAPKを入れる |
| **Bluetoothモジュール**(M5Stack ATOM Lite) | スマホとECUを無線でつなぐ中継役 | [作り方(部品・配線・書き込み)](firmware/atom_lite_bridge/README.md) |
| **ログ解析ツール**(ウェブ) | アプリで記録したログ(CSV)を読み込んで、気になる点を探す | [ブラウザで開く](https://rovermems.github.io/RoverMEMS/)(インストール不要) |

## 📥 ダウンロード

[**最新版をダウンロード(APK)**](https://github.com/RoverMEMS/RoverMEMS/releases/latest/download/app-debug.apk)

スマホでこのリンクを開くとAPKがダウンロードされます。インストール時に「提供元不明のアプリ」の許可を求められることがあります。

インストール時にGoogleの「Playプロテクト」から「アプリをスキャンのために送信しますか？」と表示されることがあります。Google Playストア以外から入れたアプリには毎回出る確認で、**「送信する」「送信しない」のどちらを選んでも問題なく使えます**(送られるのはアプリのファイルだけで、走行データなどは送信されません)。

## ☕ 開発を応援する

無料で使えるアプリですが、開発を継続するための応援(投げ銭)も受け付けています。

[**Ko-fiで応援する**](https://ko-fi.com/rovermems)

## 主な機能

- USBシリアル(PL2303等)経由でのECU接続、ライブデータ取得
- Bluetooth(BLE)モジュール経由でのワイヤレス接続
  - エンジン始動で一瞬切れても、自動でつなぎ直します
- シンプル/詳細/グラフ/アナログの4種類の表示モード
  - アナログモードは、実際の計器のような画像ベースの針メーターUI
- アクチュエータテスト(燃料ポンプ、ファン、インジェクター、O2ヒーター等)
- 走行データの自動記録(接続中は自動で保存、最大50件)・グラフ表示
- 日本語/英語の切り替えに対応

## 接続方法

| 方法 | 必要なもの | 備考 |
|---|---|---|
| USB有線 | USBシリアルケーブル(PL2303等) + USB変換アダプタ(USB-A → スマホの端子) | 一番確実。スマホと車をケーブルでつなぐ |
| Bluetooth無線 | M5Stack ATOM Lite + レベル変換モジュール | スマホと車をケーブルでつながなくてよい。作り方は [firmware/atom_lite_bridge](firmware/atom_lite_bridge/README.md) |

Bluetooth用モジュールは、技適を取得している **M5Stack ATOM Lite** に専用ファームウェアを書き込んで使います(部品・配線・書き込み手順はすべて公開しています)。電源は車のUSBから取ります。

## 対応環境

- Android 8.1(API 26)以降
- USB接続には、ケーブルをスマホの端子(USB-C等)に挿すための変換アダプタが必要
- 対象ECU: Rover MEMS 1.3 / 1.6(ミニSPi)。実車での動作確認はMEMS 1.3搭載車で実施

## ビルド方法

Android Studioで本リポジトリを開き、通常のGradleビルドで動作します。

```bash
./gradlew assembleDebug
```

## プロトコルについて

MEMS ECUとの通信仕様は、公開されているMEMSFCRのドキュメント、および複数の独立した実装を突き合わせて検証しています。単一のソースに依存せず、実車での実測データも参照しながらクロスチェックする方針で開発しています。

## ⚠️ ご利用上の注意

- 本アプリおよび作り方を公開しているハードウェアは、**無保証・自己責任**でご利用ください。ECUや車両の故障・事故について、作者は責任を負いません
- **運転中にスマホを操作しないでください**。表示の確認は同乗者が行うか、停車中に
- アクチュエータテスト(燃料ポンプ・インジェクター等)は、**エンジン停止中・安全な場所で**行ってください
- 配線作業では、ECUの診断ポートに5Vを超える電圧や誤った配線をつながないよう注意してください

## 謝辞

通信仕様の検証にあたり、MEMSFCR(Andrew Jackson氏)のドキュメント、Colin Bourassa氏のlibrosco/MEMSGauge、その他ミニ愛好家の皆さんが公開してくださった情報を参考にしました。先人の皆さんに感謝します。

## ライセンス

[GNU General Public License v3.0](LICENSE)(GPL-3.0、またはそれ以降のバージョン)で公開しています。アプリ本体・Bluetoothモジュール用ファームウェア(`firmware/`)・ドキュメントのすべてが対象です。

自由に使用・改造・再配布(有償を含む)できますが、改造したものを配布する場合は、そのソースコードも同じGPL-3.0で公開する必要があります。

Copyright (C) 2026 RoverMEMS
