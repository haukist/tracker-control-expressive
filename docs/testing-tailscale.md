# Tailscale 実機テスト手順

対象は GitHub debug（`net.kollnig.missioncontrol.test`）。
実装仕様は [design-tailscale-access.md](design-tailscale-access.md) を参照。

## 準備

- 接続先PC・サーバーがTailscaleに接続済みで、利用するSSH/HTTPS等のサービスが動作していること。
- 接続先のOSファイアウォール、Tailscaleの受信設定、Tailnetのアクセス制御で接続が許可されていること。
- Android側では既存のWireGuard・SOCKS5設定を無効にする。
- 同じAndroidユーザーで他のVPNと同時には使用できない。保護開始時にVPNの同意が切り替わる。
- 既存のdebug版を更新する場合は、WireGuard設定・Mullvadログイン等を含む設定を先にバックアップする。
  エージェントが機密設定を扱う場合は事前のユーザー確認が必要。`agents/docs/device-testing.md` に従う。

## インストールとログイン

1. 作成したGitHub debug APKをインストールする。更新は `adb install -r` とし、アンインストールや `pm clear` は行わない。
2. TrackerControlの通常の初期設定を完了し、保護を開始する。
3. 設定 → **Tailscale** → **Enable Tailscale** を有効にする。
4. **Sign in to Tailscale** を押し、ブラウザで自分のアカウントにログインする。
5. 管理者承認が必要なTailnetでは端末を承認する。
6. 設定の接続状態と `100.x.y.z` / `fd7a:…` の割り当てアドレスを確認する。

認証URL、トークン、状態ファイルを共有ログへ貼り付けない。
通常のインターネット通信はExit Nodeを経由しない。

## 確認表

| 確認 | 操作・期待結果 |
|---|---|
| IP指定 | 他のAndroidアプリから接続先のTailscale IPv4でSSH/HTTPS等に接続できる |
| IPv6 | 利用可能なアプリで接続先のTailscale IPv6に接続できる |
| MagicDNS | 同じ接続が端末の完全修飾名で成功する。短い端末名も確認する |
| UDP | 利用するUDPサービスがあれば、そのTailnet宛て通信を確認する |
| ブロック | 事前にブロック動作を確認したアプリで、通常の広告・トラッカーの遮断と履歴が維持される |
| 通常通信 | 一般サイトへの接続が成功し、Tailscale無効時と同じ回線出口を使う |
| Secure DNS | 有効・無効の両方で、通常DNSとMagicDNSが動作する |
| 未知の名前 | 存在しないTailnet名・短い端末名は失敗する。公開DNSへフォールバックさせない |
| 復旧 | Wi-Fi/モバイルの切り替え、機内モードからの復帰後に接続が戻る |
| 再起動 | アプリ/端末の再起動後も既存の認証状態で再接続できる |
| 無効化 | Tailscaleだけを無効にしても通常のブロック・通信が続く。有効化し直すと再接続する |
| ログアウト | ローカル状態を削除して認証待ちになる。Tailnetの管理画面に古い端末登録が残ることはある |
| 切断中 | Tailscaleが未接続の間も通常通信は継続し、Tailnet宛ては直接回線へ流れない |
| 既存IPv6設定 | 通常IPv6を無効にした設定でもTailnet IPv6を利用でき、通常IPv6の通信を有効化しない |
| スリープ | 画面消灯・復帰後の接続と電池消費を、同条件のTailscale無効時と比較する |

MagicDNSがTailnet側で無効ならIP指定で確認する。
VPN対象外アプリはTailnet接続も対象外。
`100.64.0.0/10` を使うキャリア等のサービスと競合する場合はTailscaleを無効にして切り分ける。
サブネットルーター・Exit Node・アプリ独自のDoH/DoTでのMagicDNSは対象外。

## ビルド

Go 1.27.3、固定版gomobile/gobind、NDK r29、Rust/cargo-ndkは事前に用意する。
[Go側の手順](../tailscale-go/README.md) と [既存ビルド手順](../agents/docs/build-and-test.md) を参照。

```sh
./gradlew :app:assembleGithubDebug -q
```

ツールがPATHにない場合は `-PgoBin=... -PgomobileBin=... -PgobindBin=...`、
作業用NDKを使う場合は `-PandroidNdkPath=...` を指定する。
`tailscaleBuild` がソースからAARを生成し、API23・4 ABI・16KB ELFアライメントを検証する。

## 検証記録

2026-11-05の開発検証。実機でのアカウント認証後の項目は、実際に測定してから結果を追記する。

- Goの競合検出付きテストでMagicDNS・境界判定・未接続時の失敗応答を確認。
- 実際の制御サーバーから認証URLを取得。アカウントへのログインは行っていない。
- GitHub debug APKを生成。API23、armeabi-v7a / arm64-v8a / x86 / x86_64の
  Goバインディングと16KB ELFアライメントをビルド時に検査。
- `:app:testGithubDebugUnitTest` は510件、失敗・エラーともに0。
- `:app:lintGithubDebug` はエラー0（警告68件）。
- `app/src/test/native/run_defensive_tests.sh` はWSL Ubuntu上で成功。
  アドレス範囲、ポリシー変更後のTCP再判定、ブロックしたTCP DNSの後続パケット、
  DNS中継のTCP/UDPセッション維持、通常IPv6無効時の分離を含む。
- 専用Android 16 / x86_64エミュレーターにインストールして通常の初期設定を完了。
  Tailscale有効化、認証待ち表示、ブラウザでのTailscaleログイン画面の表示を確認。
  アカウント情報の入力・端末登録は行っていない。
- 同エミュレーターで、Tailscale有効中にVPN対象のChromeから一般サイトを表示。
  TailnetのIPv4/IPv6ルートとMTU1280をOS側で確認。
- Tailscaleのみの無効化でTailnet専用ルートが外れ、通常VPNが接続を維持することを確認。
  再有効化、保護全体の停止・再開、停止完了後の専用Javaスレッド解放も確認。
- 起動時に上流のローカルログ保存先初期化でGoが終了する不具合を修正。
  アプリ専用のバックアップ対象外領域を指定し、実エンジン起動テストで回帰確認。

APKの出力先は `app/build/outputs/apk/github/debug/TrackerControl-githubDebug-latest.apk`。
以下は初回検証版のSHA-256（最新ファイルは次節の修正版で上書き）:
`73227d9ecca159844cde48d072d0cbe133af5325215e3468cf135842061a231e`。
開発用署名の評価版であり、公開版の受け入れ条件A01〜A11がすべて合格したことは意味しない。
自宅PC等との認証後の疎通、IPv6/UDP/DERP、ACL、実回線での漏出観測、
画面消灯時の電池消費、API23・実機ARMでの起動は未確認。

## 排他制御・ビルド更新検知の修正版（2026-11-05）

- VPNタブのスイッチ、カスタムプロファイル、Mullvad/IVPN生成の開始・完了時にも
  Tailscaleとの競合を拒否する。生成中にTailscaleが有効になった場合は、
  生成済みのプロファイル・認証情報を保存するが、WireGuardを有効化せず既存接続を維持する。
  設定の確認ダイアログ確定時にも再検証する。
- サービスの開始・再読み込みでは、既存のTUNや転送処理を変更する前に競合を検証する。
- GradleはPATH・相対パスを実際の実行ファイルへ解決し、Goのコンパイラ群も含めて
  内容の変更を追跡する。同じツールであればAARを再利用する。
- JVMテスト515件、失敗・エラー0。追加した5件では実際のVPNスイッチ、
  プロファイル操作、非同期生成後の競合、サービス側の既存VPN維持を確認した。
- `python scripts/test_tailscale_build_inputs.py` の2件が成功。
  実際のGradleでツール3種の同一パス上の置換、Goコンパイラのみの変更、
  リポジトリ基準の相対パス、変更のない場合の再利用を検証する。
- `:app:lintGithubDebug` はエラー0、`:app:assembleGithubDebug` が成功。
  Go・ネイティブ本体は今回変更しておらず、その回帰テストは初回記録を参照。
- 上記のエミュレーター操作は初回版の結果。修正版APKでの実機操作は未確認。

修正版APKのSHA-256:
`dfe15807d5bbb0daaed9ca13f207f4c5cf55886a54d97d3e50cd016fc13904b5`。

## コードレビューの記録

`gemini-code-review` のGemini 3.8 Flash（medium）で差分を分割し、
未追跡の新規ソースも含めてGo、DNS、設定、Android管理、サービス、ビルド、
IP経路とネイティブテストを確認した。指摘を実装と照合し、確認できた
TCP DNSのブロック迂回、TUN書き込みのブロッキング、開始失敗時の解放、
ビルド入力条件、テスト用関数宣言を修正して対応する検証を行った。

初回のGeminiレビューでは、`app/src/main/jni/netguard/netguard.c` / `netguard.h` の
JNI連携部分がCLIの300秒制限で未完了だった。
2026-11-05のClaude Sonnet 5.5（medium）による12分割レビューではこの範囲も確認した。
今回の排他制御・ビルド入力追跡の修正も、Gemini 3.8 Flash / Claude Sonnet 5.5
（ともにmedium）で差分を再確認した。追加した非アクティブ保存は範囲を絞って再レビューした。
