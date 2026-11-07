# A100 ANC 切換

Cambridge Audio Melomania A100 的非官方 Android 桌面小工具，支援 Android 8.0（API 26）以上、目標 API 34。

## 功能與使用

- **切換**：點任一格依「一般 → ANC → 通透 → 一般」循環，可停用點按切換。
- **電量圓環**：顯示左右耳及盒電量；數值、時間見 App 或無障礙描述，小工具無百分比文字。未知為灰環／`--`；顏色分 ≥50%、20–49%、<20%。
- **單耳路徑**：不檢查雙耳連線、佩戴、入盒或電量；已選 MAC 的 RFCOMM/SPP 可連線就嘗試 SWITCH，單左／右耳仍待真機驗證。
- **mono**：App 內先讀取再開關，與「只用一耳」不同。
- **自訂**：三段電量及三種模式共六組顏色，支援預設／六位 Hex RGB；背景不透明度 40%–100%（預設 80%），圓環不透明。

| 版面 | 預設內容 |
| --- | --- |
| 1×1 | 模式 |
| 2×1 | 左耳／右耳電量 |
| 1×2 | 充電盒電量／模式 |
| 2×2 | 固定為左耳／右耳／充電盒電量與模式 |

尺寸依 launcher 回報。前三種可逐格選模式、各電量或「系統電量（取低者）」，可重複；後者為 GAIA 左右耳較低值，須兩值有效，非 Android 電量。小工具共用耳機與設定。

1. 系統配對、開藍牙，再開 App 授權並選已配對裝置。Android 12+ 需 `BLUETOOTH_CONNECT`；清單優先列 A100 名稱，但不驗證型號。
2. 點「加入到桌面」並確認，或從桌面小工具清單加入；首次選取不自動加入。拒絕／取消可重開 App 或再點按重試。
3. 點小工具切換；App 可刷新電量，或先「讀取單聲道狀態」再操作 mono。

連線失敗可能因官方 App 佔用 RFCOMM（可關閉後重試）、離線、藍牙關閉或缺權限。

## 通訊與狀態

依既有 A100 實測與 Melomania Connect 2.6.0 APK 分析，採 **GAIA v3、Bluetooth Classic RFCOMM/SPP**。

以上次確認模式推算目標，無紀錄時首次要求 ANC。連線直接送 `SWITCH_AMBIENT_NOISE_MODE`（`00 0E`）指定目標；**只有完整、逐位元組相符且動作相同的 ACK 才保存並推進模式**。1 秒無相符 ACK，版本握手一次；回應完整相符才重送同一 SWITCH 一次。失敗保留原模式，有效電量仍可更新；無 ACK 不代表耳機未執行。

不送 ANC SET（`0x000F`）、GET（`0x0010`）或 TOGGLE（`0x03`）。SET 未生效、GET 無回應僅限既有 A100 該次實測，不代表所有韌體皆不支援。

**顯示的是 App 上次確認模式**，不持續讀取或同步觸控、其他 App 的變更；無紀錄時顯示一般。

mono 用獨立 GET／SET：首次無讀回則握手一次再讀；設定前先讀，值不同才寫，再讀確認，不憑 SET ACK。無有效讀回則停用；與目標不同則顯示回報值並提示。離開 App 清除確認值，返回須重讀，開機不重送。

## 電量與刷新

電量來自 RFCOMM 收到的 **GAIA 電池狀態幀**，不是 BLE 掃描或即時串流；離線、失敗或無有效幀時保留快取，幀內無效百分比顯示未知。

- **讀取**：短連線等幀後關閉，不送 GAIA 指令；連線上限 2.5 秒、連線後等幀最多 1 秒。切換／mono 也可接收電量。
- **事件**：有小工具、已選 MAC 且有權限時，前景服務啟動讀取並顯示最低優先通知；同 MAC、百分比 0–100 的系統電量事件觸發 GAIA 讀取，不採用系統百分比。
- **15 分鐘週期**：WorkManager 排程受 Android 與省電影響，不保證準時；最後一個小工具移除即取消並停服務。開機／更新後嘗試恢復，背景限制或強制停止可中斷。
- **節流**：同 MAC 自動嘗試在 30 秒內合併，手動除外；所有操作共用守衛，忙碌時略過。

## 權限與隱私

依主 Manifest、現有 debug 合併 Manifest 及報告核對；前六項由主程式宣告，發布前須查實際 APK。

| 權限 | 來源／用途 |
| --- | --- |
| `BLUETOOTH_CONNECT` | Android 12+ 已配對裝置與連線，唯一執行階段請求 |
| `BLUETOOTH` | 舊版基本權限，未設 `maxSdkVersion` |
| `BLUETOOTH_ADMIN` | 舊版管理權限至 API 30，不探索 |
| `FOREGROUND_SERVICE` | 電量監聽服務 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | `connectedDevice` 服務 |
| `RECEIVE_BOOT_COMPLETED` | 依賴亦使用；開機恢復監聽／排程 |
| `WAKE_LOCK` | WorkManager 帶入；背景工作使用，不保證定時喚醒 |
| `com.alpha.ancwidget.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX Core 帶入的 `signature` 自訂權限；非匯出動態接收器，無須使用者授權 |

沒有 `INTERNET`、位置權限、`BLUETOOTH_SCAN`、`POST_NOTIFICATIONS`；主 Manifest 移除 `ACCESS_NETWORK_STATE`。通知呈現受系統與設定影響。

- 未實作上傳、追蹤或統計，無網路權限。MAC、名稱、電量快取、確認模式、外觀存於本機 SharedPreferences；但 `allowBackup="true"`，可能進入系統備份／移轉，依 Android 與使用者設定而定。
- 只連已選 MAC，不掃描或嘗試其他 MAC，不驗證是否 A100；此限制不描述耳機間通訊。
- 未實作韌體更新／寫入；送出的指令為 SWITCH、必要握手、mono GET／SET，**ANC 與 mono 仍會改耳機狀態／設定**。

## 自行編譯

需 JDK 17、Gradle 8.7、SDK Platform 34、Build Tools 34.0.0；AGP 8.5.2、Kotlin 1.9.24。

`gradlew` 為本機精簡啟動器，直接用 `gradle-dist/`、`jdk17/`、`android-sdk/`，非標準 Wrapper。現有 JDK 為 Linux x86_64；公開版未必附工具，缺少／跨平台須自備、調整路徑。`local.properties` 的 `sdk.dir` 須改為自己的 SDK 路徑。

另檢查 `http_proxy`、`settings.gradle.kts` 套件來源；未快取依賴仍需下載。根目錄執行：

```sh
./gradlew assembleDebug
```

預期產物：`app/build/outputs/apk/debug/app-debug.apk`。本次未跑 Gradle、未驗證全新建置；既有 APK 不作為建置成功或發布一致性證據。

## 研究與免責聲明

- [電池協定](docs/battery-protocol.md)
- [單耳路徑](docs/single-ear-mode.md)
- [mono 與自適應 ANC](docs/audio-features.md)
- [GaiaClient.kt](app/src/main/java/com/alpha/ancwidget/GaiaClient.kt)

研究含靜態分析、有限實測與模擬，不等同 App 全面驗收。`docs/` 僅有 `GAIA-ANC-protocol.md` 增補 patch，外部引用未必隨附。三態及失敗狀態、單耳、mono 混音／讀回、電量、背景／開機、權限通知、launcher 新增／縮放仍待真機驗收。

非官方，與 Cambridge Audio、Qualcomm 無隸屬或背書關係；有限驗證不保證相容性或安全性，系統、韌體、官方 App 更新可影響功能。使用者自行評估風險；法律允許範圍內，作者不保證功能、相容性、適用性，亦不承擔使用損害責任。

Cambridge Audio、Melomania、Qualcomm 名稱與商標屬各權利人，僅用於識別產品與技術。
