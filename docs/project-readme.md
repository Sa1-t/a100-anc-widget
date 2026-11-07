# A100 ANC 切換 — Melomania A100 桌面小工具

Android Kotlin，套件 `com.alpha.ancwidget`，minSdk 26 / targetSdk 34，AGP 8.5.2。
App 上方為「加入到桌面」，下方為耳機控制、圓環色、小尺寸內容、透明度及點擊切換設定。
每次建立 App 頁面（`onCreate`）時，若尚未儲存 MAC，會先取得藍牙權限，再以對話框選已配對耳機，
儲存 MAC 與名稱並清除電量快取；此初始流程只選耳機，不呼叫 `requestPinAppWidget`。
點擊「加入到桌面」共用同一段授權／選取邏輯，完成後再呼叫系統加入小工具；已有權限與 MAC 時直接請系統加入。

## 本次調整（2026-11-07）

- **啟動時選耳機**：沒有 `device_mac`（或值為空）時，由 `onCreate` 自動開始，`onResume` 不重複觸發。
  API 31+ 只請求 `BLUETOOTH_CONNECT`，授權後自動繼續本次流程；拒絕／取消授權只提示，不自動重試。
  藍牙不支援、未開啟、無已配對裝置沿用原提示並結束流程；取消選取後亦不自動重彈。
  尚未選定時，可下次啟動 App 或點「加入到桌面」再試。只讀 `bondedDevices`，A100 排序優先維持原樣。
  等待授權或選取時不重複開啟流程；此時若明確點擊加入按鈕，會沿用進行中的流程，完成後加入小工具。
  本項驗收僅執行 `python3 tools/check_phase2.py`，結果見 [first-launch-check.log](first-launch-check.log)。
  既有藍牙異常檢查只調整前置資料以符合新的啟動時序，原案例與斷言不變，沒有新增測試檔案或案例。
  檢查使用 framework／transport 與 WorkManager API 替身，未跑 Gradle，未做真機／模擬器的首次啟動、權限或 launcher 驗收。
- **只顯示 GAIA 電量**：App 與小工具（含無障礙描述）移除系統 HFP 單一百分比與備援文字。
  `BatteryStore.Snapshot` 只包含左／右／盒與 GAIA 讀取時間，不再讀取或儲存系統百分比。
  三格未有有效 GAIA 值時為 `--`／灰環；離線或讀取失敗仍保留上次成功的 GAIA 快取。
  升級後舊系統 key 不參與顯示，選新耳機時一併清除。
  系統廣播仍驗證 0–100 與已選 MAC，再觸發原本短暫 SPP 純電量讀取；
  前景監聽、30 秒背景嘗試合併、唯一 15 分鐘 Worker、操作守衛均維持原樣。
- **六排自訂圓環色**：保留每排八個預設色塊，再加「自訂」按鈕。
  對話框輸入六位 Hex RGB（可帶 `#`）；無效色碼留在對話框提示，按「套用」後立即保存並更新所有小工具。
  色塊與自訂色共用原六個 Int key，保存為不透明 RGB，重開 App 保留。
  高／中／低電量預設 `#65CC88`／`#E8BC60`／`#EE7B82`；
  一般／ANC／通透預設 `#D3D9E2`／`#8DBBFA`／`#78D2BD`。
  電量門檻仍為 ≥50%、20–49%、<20%，未知固定灰色，0% 底環採所選低電量色。
  模式圓環與圖示共用同一模式色。
- **模式只從小工具切換**：移除 App 內「直接切換模式」標題與三個直選按鈕。
  耳機控制區保留手動電量刷新、三格電量與含日期的讀取時間、上次確認模式與不同步提示、mono 讀取及開關。
  設定頁「點小工具切換模式」仍控制所有格子的點按；關閉後 provider 與 controller 都會阻擋切換。
  真機已驗證的 SWITCH 位元組、正確 ACK 才推進、單次握手 fallback 與單耳切換路徑未變。
  mono GET／必要時 SET／GET 讀回確認未變；指令已由使用者於 2026-11-07 真機驗證，詳見
  [audio-features.md](docs/audio-features.md)。
- **小尺寸內容設定**：每一格可選「模式（ANC 狀態）／左耳電量／右耳電量／充電盒電量」，允許重複。
  全部使用既有圓環＋圖示，沒有可見文字；無障礙描述依實際選定內容提供狀態與讀取時間。
  每種尺寸共用設定，選完立即保存並重建所有既有 widget，不需重加；2×2 四格順序不變。

| 尺寸 | 項目與預設值 |
|---|---|
| 1×1 | 一項，預設模式 |
| 2×1 | 左、右兩項，預設左耳電量／右耳電量 |
| 1×2 | 上、下兩項，預設充電盒電量／模式 |
| 2×2 | 固定左耳／右耳／充電盒／模式 |

尺寸仍依 launcher 回報的實際可用 dp 判定，不將格數當作固定 dp：
寬高皆 ≥110dp 使用 2×2；其餘寬 ≥110dp、高 ≥48dp 使用 2×1；
其餘高 ≥110dp 使用 1×2；更小空間使用 1×1。
橫向矮版面原本的四格單排改為兩格，幾何計算與 XML 權重一併調整。
Android 12+ 仍使用 `OPTION_APPWIDGET_SIZES`，較舊 launcher 仍以方向配對的尺寸 extrema 建立版面。

## 小工具設定與保存

全部共用 `anc_widget_prefs`，沒有每個 widget 獨立設定：

| 設定 | SharedPreferences key |
|---|---|
| 高／中／低電量色 | `battery_high_color`、`battery_medium_color`、`battery_low_color` |
| 一般／ANC／通透色 | `mode_normal_color`、`mode_anc_color`、`mode_transparency_color` |
| 1×1 內容 | `compact_content` |
| 2×1 左／右內容 | `wide_first_content`、`wide_second_content` |
| 1×2 上／下內容 | `tall_first_content`、`tall_second_content` |
| 卡片不透明度百分比 | `card_opacity_percent` |
| 點擊切換模式 | `mode_switch_enabled` |

內容以 enum 名稱 `MODE`／`LEFT`／`RIGHT`／`CASE` 保存，缺少或無效值採各位置預設。
`WidgetSettings.kt` 每次變更先保存再呼叫 `AncWidgetProvider.updateAll()`；相同值不重複寫入或重畫。
`WidgetSettingsUi.kt` 全部使用 framework Views，沒有新增依賴、權限或反射。
「恢復預設外觀」仍只重設六色與 80% 不透明度，保留小尺寸內容、耳機與模式切換設定。

卡片 RGB 固定 `#191C22`，不透明度 40%–100%，預設 80%。獨立背景 ImageView 用
RemoteViews `setImageAlpha` 設定 102–255 alpha，不將 alpha 套到圓環，也不產生背景 Bitmap。
一般 20dp／compact 16dp 圓角與透明模式格維持；支援模糊的 launcher 可在半透明卡片後呈現毛玻璃。
圓環快取上限仍為 24 張，透明度預覽仍每約 100ms 合併，放手或離開頁面會提交最後值。
加入桌面的授權、耳機儲存與系統固定行為維持原樣；另增加上述啟動時只選耳機的流程。

本次驗收只執行 `python3 tools/check_phase2.py`，結果見 [app-adjustments-check.log](app-adjustments-check.log)。
未新增測試檔案、模組或案例；僅將現有因移除系統備援、App 模式按鈕、橫向兩格與任意 RGB 保存而
過時的斷言改為新行為，協議預期值未變。WorkManager 若無 AAR 快取，仍由既有工具明確標示 API 替身邊界。
沒有執行 Gradle、未變更 Gradle home。沒有使用真機／模擬器，**本次視覺驗收未做**；
資源、Kotlin 與 D8 的離線檢查不代表 launcher 外觀或實機藍牙驗收。

以下為既有階段的歷史紀錄；若與上述調整相衝突，以上述現況為準。

## 第六階段：加入流程、圓環外觀與單耳切換

- `MainActivity.kt` 與 manifest 只要求 `BLUETOOTH_CONNECT`，移除 SCAN。
  `GaiaClient.kt` 同時移除需要 SCAN 的 `cancelDiscovery()`；App 不進行探索。
  授權後自動繼續；已選耳機直接固定，未選時先選已配對裝置並儲存。
  `requestPinAppWidget` 回傳 true 後立即 Toast 提醒在系統視窗點「新增」；
  false、launcher 不支援、例外皆提示耳機已儲存與長按桌面加入的方法。
  權限拒絕／取消、取消選取、藍牙關閉、無已配對裝置也有可見結果。
- 四種 `widget_anc*.xml` 版面移除電量數字，只留裝置圓環與名稱。
  `WidgetAppearance.kt` 集中門檻：`GREEN_MIN=50`、`AMBER_MIN=20`，可在此修改後重建；
  ≥50 綠、20–49 琥珀、<20 紅、未知灰。`BatteryRingRenderer.kt` 的進度仍是
  `level * 3.6°`，左右耳／盒各自著色；0% 沒有進度弧，使用紅色底環。
  HFP 備援與時間仍保存在原欄位，無障礙描述保留數字；可見備援文字改為「使用中」，
  最小模式膠囊僅保留模式圖示／標籤及無障礙備援資訊。
- 三種模式的圖示、文字色、圓環色與格子背景一起更新，包含最小版面：
  一般＝灰白／喇叭；ANC＝藍／斜線盾牌；通透＝青綠／向外聲波。
  背景資源為 `mode_normal.xml`、`mode_anc.xml`、`mode_transparency.xml`；
  `styles.xml` 將舊數字樣式改為模式標籤樣式，移除預設 `--`。
- [單耳研究與 DEX 證據](docs/single-ear-mode.md)：官方主卡片直接發送同一條
  `SWITCH_AMBIENT_NOISE_MODE`（`00 0E`），未見雙耳／佩戴前置條件或單耳專屬序列。
  小工具既有 `AncModeController.kt` / `GaiaClient.kt` 亦無耳數 gate，本階段加註說明，
  維持選定 MAC 的單一 SPP 連線、原 SWITCH 與 ACK 推進規則。

### 第六階段驗收

`python3 tools/check_phase2.py`：整合原驗收工具，新增 `tools/pin-tests/`，
以正式 `MainActivity.kt` 執行流程；protocol tests 使用正式 controller/client 模擬單耳。
檢查四種權限／選擇狀態，各跑支援／不支援 launcher，合計 **8 條路徑**：

| CONNECT／耳機選擇 | launcher 支援 | launcher 不支援 |
|---|---|---|
| 已授／已選 | 系統固定請求＋新增提示 | 已儲存＋長按桌面提示 |
| 已授／未選 | 選取並儲存後請求＋提示 | 選取並儲存後長按桌面提示 |
| 未授／已選 | CONNECT 授權後自動請求＋提示 | 授權後自動顯示長按桌面提示 |
| 未授／未選 | 授權→選取儲存→請求＋提示 | 授權→選取儲存→長按桌面提示 |

另驗證拒絕／取消授權、取消選取、無裝置、藍牙關閉、API 26、
固定 API 回傳 false／例外都有明確結果；沒有 SCAN／探索呼叫。
單左耳／單右耳 × 另一耳在盒中／離線 × 三個模式的 **12 個切換案例**，
逐 byte 驗證同一 SWITCH、相符 ACK 才儲存與關閉 socket。
保留第五階段的廣播／Worker、電量解析、快取與握手 fallback 回歸。
結果見 [phase6-check.log](phase6-check.log)。測試使用 framework／transport 替身，
WorkManager AAR 在本環境無可讀快取，使用既有 API 替身；不代表真機 launcher 或單耳驗收完成。

已執行 `./gradlew assembleDebug`，但初始化失敗，完整錯誤見
[build-phase6.log](build-phase6.log)，完整 stack trace 見
[build-phase6-stacktrace.log](build-phase6-stacktrace.log)。原因是預設
`/root/.gradle/native/…/libnative-platform.so.lock` 位於唯讀檔案系統，尚未進入 AGP 建置。
沒有更動 Gradle home 或設定 `GRADLE_USER_HOME`。
獨立資源編譯／連結、正式 Kotlin 與 D8 檢查不等同 `assembleDebug` 成功，舊 APK 也不是本階段產物。

## 第五階段：系統電量廣播觸發 GAIA 刷新

有至少一個桌面小工具、已選 MAC 且已授予 `BLUETOOTH_CONNECT` 時，
`BatteryMonitorService` 以 `connectedDevice` foreground service 常駐一個動態 receiver；
它不持有 RFCOMM socket，也不輪詢。通知 channel 使用 `IMPORTANCE_MIN`、
通知 `PRIORITY_MIN`，只顯示「正在監聽耳機電量變化」。
manifest 宣告 `FOREGROUND_SERVICE` 與 `FOREGROUND_SERVICE_CONNECTED_DEVICE`；
API 34 的連線權限先決條件在 `startForeground()` 前檢查。
不新增網路權限、依賴或 hidden API 反射。

1. 第一個小工具加入時，與 15 分鐘 Worker 一起啟動監聽；最後一個移除時停止 service、
   解除 receiver 並移除通知，同時取消 Worker。App 回到前景、完成授權或點小工具也會補啟動。
   `START_STICKY` 處理程序回收，開機／App 更新 receiver 依現存 widget 與權限恢復監聽。
2. 動態監聽字面 action `android.bluetooth.device.action.BATTERY_LEVEL_CHANGED`，
   API 33+ 明確傳 `Context.RECEIVER_EXPORTED`。電量讀字面 extra
   `android.bluetooth.device.extra.BATTERY_LEVEL`，只接受 0–100；
   `BluetoothDevice.EXTRA_DEVICE` 的 MAC 必須等於已選耳機，忽略其他裝置／缺失資料。
3. 此廣播不是 sticky；service 每次建立後先走一次 SPP 純電量讀取作初始值。
   之後每次有效廣播都先保存獨立的 HFP 單一百分比，再共用 `AncModeController.launch`
   與 `refreshBattery()`：連線最多 2.5 秒、聽電池幀最多 1 秒、更新 widget、關閉 socket。
   不握手、不送模式或其他協議封包、不重試；若點擊／Worker 已佔用守衛就跳過，不排隊。
4. GAIA 失敗時保留原本左／右／盒快取；第六階段模式格下方顯示「使用中」，
   系統百分比與時間保留在無障礙描述（最小膠囊亦同）。系統值永不填入左／右／盒欄位。
   無障礙描述帶系統值最後回報時間；成功取得三格 GAIA 電量後恢復「模式」標籤。
   選新耳機會清除兩種電量快取。
5. 藍牙關閉、耳機離線、官方 App 佔用 SPP、缺權限均靜默跳過。
   Android 12+ 若拒絕從背景啟動 service，不重試、不彈訊息；15 分鐘 Worker 仍保留，
   下次 App 前景／小工具互動再補啟動。強制停止 App 或系統限制仍可能中斷監聽。

系統 HFP 百分比與 GAIA 左／右／盒不同，廣播頻率由耳機決定；不保證每秒更新。
SWITCH 封包、ACK、一次握手 fallback、三態循環、圓環點擊及單一加入按鈕均沿用第四階段。
Android 規則參考：[connectedDevice 類型與權限](https://developer.android.com/about/versions/14/changes/fgs-types-required)、
[背景啟動與小工具互動／開機例外](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。

## 第四階段：四格互動、尺寸修正與定時刷新

### 尺寸 bug 原因與修法

舊版把完整格最低尺寸設為 **220 × 300dp**，同時 provider 宣告預設 **2 × 1 格**
（`minWidth=110dp`、`minHeight=48dp`），初始版面也只有一顆按鈕。
launcher 的「2 × 2 格」不是固定 220 × 300dp；舊門檻把較小的 2 × 2 選成收合版面。

新版 `anc_widget_info.xml` 預設 `targetCellWidth=2` / `targetCellHeight=2`、
`minWidth=minHeight=110dp`，初始版面為完整格；保留橫直向縮放，最小可縮至 48 × 48dp。
110dp 沿用 Android 舊版兩格估算式 `70 × 2 − 30`，亦小於現行官方 Pixel 4 範例中
2 × 2 的最小橫向／縱向可用空間（直向 130 × 220dp，橫向 269 × 117dp）。
這些是有來源的相容基準，不是這支手機的實測數值。

- Android 12+：優先讀 `OPTION_APPWIDGET_SIZES`，對每個實際可用尺寸產生 RemoteViews，
  使用 `onAppWidgetOptionsChanged` 傳入的新 options。不要從格數猜 dp，也不依候選版面面積競爭。
- API 26–30／未提供尺寸列表的 launcher：以 `MIN_WIDTH + MAX_HEIGHT` 對應直向、
  `MAX_WIDTH + MIN_HEIGHT` 對應橫向；沒有有效 options 時預設完整格。
- 完整格只保留四格、6dp 內距及可伸縮圓環。110dp 高度的預算為
  `12 + 2 × (約 36dp 圓環 + 約 13dp 標籤)`（第六階段移除數字後）；大尺寸圓環隨空間放大。
- 每次更新從 SharedPreferences 重讀同一份電量／確認模式，縮放不清除狀態。

| 可用尺寸（dp；依優先順序） | 版面 |
|---|---|
| 寬 ≥110、高 ≥110 | 完整 2×2：左耳／右耳／充電盒／模式 |
| 寬 ≥220、高 ≥64，未達完整格 | 同四格單排 |
| 高 ≥110，寬 <110 | 充電盒圓環＋模式格 |
| 其餘較小空間 | 模式膠囊 |

來源：[Android widget 尺寸與 exact layouts](https://developer.android.com/develop/ui/views/appwidgets/layouts)、
[Google 舊版兩格尺寸公式](https://google-developer-training.github.io/android-developer-advanced-course-practicals/android-developer-advanced-course-practicals.pdf)。
不同 launcher 的網格／邊距仍由主機決定；此 VM 未連接手機，未宣稱已完成真機視覺驗收。

### 互動與協議

- 完整版面只有四格，沒有底部三模式按鈕、額外模式按鈕列或刷新列。
  點任一電池格（圓環與名稱）或模式格都走同一個循環事件。
- 一般 → ANC → 通透 → 一般；沒有確認紀錄時以一般為循環起點，首次點擊設定 ANC。
- 連線後直接送 SWITCH `FF 03 00 01 0B 9E 00 0E <00/01/02>`，不先握手。
  **只有完整 ACK `FF 03 00 01 0B 9E 01 0E <相同動作>` 才推進並儲存模式。**
  一秒沒有正確 ACK 才握手一次，確認完整版本回應後重送同一 SWITCH 一次。
  不使用 SET `0x000F`、GET 或韌體 TOGGLE。
- SWITCH 期間同時收電池廣播；電池缺失不否定已確認的 SWITCH。
  Cambridge 9-byte payload 索引 **4/5/6 = 左耳/右耳/充電盒**；充電狀態 1–3、入盒狀態 7–8。
  協議與真機驗證來源見 [電池筆記](docs/battery-protocol.md)。
- `BatteryStore` 保存上次電量和時間。無廣播、離線、藍牙關閉或權限不足時保留原數值；
  從未讀取／百分比無效時顯示灰色圓環（無障礙描述為 `--`）。升級亦恢復第三階段曾隱藏的有效快取。
  新選耳機會清除電量；每格的無障礙描述含上次讀取時間與模式確認狀態。

### 背景刷新

新增唯一依賴 `androidx.work:work-runtime:2.9.1`（符合既有 compileSdk 34），
並在 `gradle.properties` 啟用 `android.useAndroidX=true`。
有桌面小工具時，以唯一名稱登記 **15 分鐘** PeriodicWorkRequest，KEEP 避免重複排程；
移除最後一個小工具時取消。現有小工具收到更新、或開啟 App 時也會補齊排程。
WorkManager 保存任務並由系統恢復，實際執行時間受 Doze／省電影響，不保證整點。

Worker 共用點擊操作的原子守衛：已忙碌就跳過，不排隊搶連線。
每次只短暫連線、等一幀電池廣播、更新小工具並關閉 socket，**不送任何協議封包**。
藍牙未開、無權限、耳機不在附近或 RFCOMM 被官方 App 佔用時靜默完成該週期，
不顯示 Toast、不呼叫 `Result.retry()`；連線最長 2.5 秒、收廣播最長 1 秒。

沒有聯網權限／網路條件；manifest 排除 WorkManager 的網路狀態權限。
第五階段的監聽 service 使用前景服務權限，Worker 本身仍是一般週期任務。
WorkManager 所需的喚醒鎖與開機恢復由其 manifest 合併。
[WorkManager 週期最小間隔與系統延遲](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest)。

## 程式位置

- `AncWidgetProvider.kt`、`WidgetLayoutPolicy.kt`：尺寸／版面、四格點擊、preferences 重建。
- `BatteryRefreshWorker.kt`：週期排程、移除後取消、背景純電量刷新與 service 生命週期同步。
- `BatteryMonitorService.kt`：動態廣播監聽、foreground 通知、純電量觸發、重開機／更新恢復。
- `AncModeController.kt`：三態循環、ACK 才儲存、前景／背景共用忙碌守衛。
- `GaiaClient.kt`、`BatteryState.kt`：既有真機驗證過的協議與解析，本階段未改封包。
- `BatteryStore.kt`：持久電量快取與時間，失敗不隱藏舊值。
- `MainActivity.kt`、`activity_main.xml`：加入按鈕、首次裝置選擇對話框與同頁設定。
- `WidgetSettings.kt`、`WidgetSettingsUi.kt`：共用設定儲存、六色選擇、透明度與切換開關。
- `BatteryRingRenderer.kt`、`WidgetAppearance.kt`：Canvas Bitmap 灰軌、分段電量色與三態模式圖示，參考 `docs/batteries-reference.jpg`。

## 第五階段本環境驗證

已執行 `./gradlew assembleDebug`，但 Gradle 在初始化時因
`/root/.gradle/native/…/libnative-platform.so.lock` 為唯讀而失敗，未進入 Android 建置。
沒有更動 Gradle home，也沒有設定 `GRADLE_USER_HOME`。
完整原始錯誤見 [build-phase5.log](build-phase5.log)，另一次含完整 stack trace 的結果見
[build-phase5-stacktrace.log](build-phase5-stacktrace.log)。既有 APK 不代表本階段成功產物。

`python3 tools/check_phase2.py` 已通過獨立資源編譯／連結、正式 Kotlin 原始碼與 D8，
以及協議／快取／排程／監聽 service 回歸；結果見 [phase5-check.log](phase5-check.log)。
WorkManager AAR 在此執行環境無可讀快取，檢查會明確使用 `tools/work-tests` 的 API 替身；
service 測試也使用 `tools/protocol-tests` 的 framework 替身。
這些替身不進正式 Gradle 原始碼，不能驗證真實 manifest 合併、Android 背景政策或實機廣播，
也不等同 `assembleDebug` 成功。實機仍需驗收 API 33/34、HFP 電量變化與通知行為。

## 重新建置

```sh
./gradlew assembleDebug
# APK：app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew` 是精簡啟動器，直接用專案內附的 Gradle 8.7（`gradle-dist/`）
與 JDK 17（`jdk17/`），不下載 wrapper。建置時會從當下環境的
`$http_proxy` 取 proxy 帳密；JDK 的 cacerts 已匯入 Hatch CA。

## 這台建置機的已知坑（2026-11-06 實測）

1. VM 記憶體被 balloon 壓到只剩約 0.5–2 GB 可用：`gradle.properties`
   已把 Gradle heap 壓到 1 GB、Kotlin 改 in-process 編譯。
2. `localhost` 會先解析到 `::1`，Gradle daemon 只聽 IPv4，
   導致 daemon 連不上：已加 `-Djava.net.preferIPv4Stack=true`。
3. Gradle 的 Java HTTP client 連 `dl.google.com` 會在 TLS 握手後被斷線
   （curl 正常），所以 `settings.gradle.kts` 把 Google Maven 換成
   Aliyun 鏡像（`https://maven.aliyun.com/repository/google`）。
   Maven Central 與 Gradle Plugin Portal 不受影響。
4. Android SDK 是用 curl 直接下載套件 zip 解開的（sdkmanager 的
   HttpURLConnection 會被同一個 proxy 問題打死），`package.xml` 為
   手寫補上，`sdkmanager --list_installed` 可認得三個套件。
5. **Gradle home 絕對不要放 /tmp**：`/tmp` 是 512M tmpfs，依賴快取
   塞滿它之後，Gradle 下載的檔案會變成 0-byte 寫進快取（`modules-2`
   裡一堆空 .pom），transforms 的 metadata 也會損壞，建置就報
   `Content is not allowed in prolog` 或 `Could not read workspace
   metadata`。2026-11-06 已把 Gradle home 搬到預設的 `~/.gradle`
   （家目錄磁碟 146G），`./gradlew` 不用再設 `GRADLE_USER_HOME`。
   萬一快取又壞掉：刪 `~/.gradle/caches/modules-2/files-2.1` 底下
   所有 0-byte 檔案、或整個 `transforms-4` 目錄，再重建。

## 實機驗收

1. 安裝新建置後開 App，上方為「加入到桌面」，下方為設定。尚未選耳機時自動完成授權、選已配對 A100，
   不出現系統固定視窗；之後點擊加入按鈕才進系統固定流程。拒絕授權或取消選取後，返回前景不應重彈。
2. 新增 2×2，應顯示左耳／右耳／充電盒／模式四格。分別點每個圓環與模式格，
   確認 ACK 成功才按一般→ANC→通透循環，並更新連線時讀到的電量。
3. 橫直向、縮小與放大後確認四格／收合對應，電量與模式不消失。
4. 等候系統允許的週期刷新，確認只更新電量、不切模式。關閉藍牙、耳機離線、
   或官方 App 佔住連線時保留電量且不彈訊息；移除最後一個 widget 後停止排程。

5. 第五階段：加入首個 widget 後應見低優先監聽通知；重新連線後／電量變化時，
   確認系統廣播只讓已選 A100 觸發短暫 SPP，左／右／盒更新而模式不變。
   以官方 App 佔住 SPP 時保留三格舊值，另見「使用中」系統值；釋放後下次有效廣播
   或 Worker 成功讀取應恢復「模式」標籤。其他藍牙裝置電量變動應忽略。
6. 多個 widget 僅維持一個 service／排程；移除最後一個後通知與監聽停止。
   重開機、App 更新、撤回／重新授予 CONNECT 後，確認只在有 widget 與權限時恢復監聽。

7. 第六階段：逐項檢查上表 8 條加入路徑，以及拒絕 CONNECT／launcher 不接受請求的提示。
   四種尺寸皆不得出現電量數字；左／右／盒各自確認未知、19/20、49/50 與 0/100 的圓環。
   一般／ANC／通透的格子顏色與圖示須明顯不同，縮放後仍依已確認模式重建。
8. 單耳真機：分別只留左耳／右耳連手機，另一耳在盒中或關機；先關官方 App 釋放 RFCOMM。
   點圓環逐次確認三態循環，有正確 ACK 才變更外觀；離線／佔用 SPP 時不得假裝成功。
