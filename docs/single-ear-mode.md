# 單耳模式切換：Connect 2.6.0 靜態查核

查核日期：2026-11-07。輸入是 `/home/hatch/workspace/melomania-re/apk/classes{,2,3,4}.dex`
（四個 DEX，共 36,529,920 bytes）。以現有 androguard venv 直接解讀指令，不修改 APK，
不建立 analysis database。完整可重跑的掃描程式是
[`tools/inspect_single_ear_apk.py`](../tools/inspect_single_ear_apk.py)，
輸出含每個 DEX 的 SHA-256、方法 `code_off` 和方法內指令 byte offset：
[`single-ear-apk-bytecode.txt`](single-ear-apk-bytecode.txt)。

```sh
/home/hatch/workspace/melomania-re/venv/bin/python tools/inspect_single_ear_apk.py \
  > docs/single-ear-apk-bytecode.txt
```

## 結論與證據範圍

官方 App 主模式卡片的三個選項，共用同一條 SWITCH 路徑；該路徑沒有讀取佩戴狀態、
左右耳連線數量、入盒旗標或電量作為發送條件，也沒有單耳分支／單耳專屬查詢或前置命令。
因此本小工具沿用 `00 0E <00/01/02>`，只需要已選 MAC 的 SPP 可以連線。

這是 DEX 的呼叫路徑與封包編碼證據，配合使用者「官方 App 單耳可切換」的實測回報；
本環境沒有耳機，沒有取得單耳藍牙封包擷取，也未驗證不同韌體的實際 ACK。
不能用觸控手勢的佩戴限制推論 App 指令也有同樣限制。

## 主卡片到 SWITCH 的路徑

以下類名均以 `com.cambridgeaudio.melomaniaplus.` 開頭，位置均在 **classes2.dex**。
`code_off` 為 DEX code_item 檔案偏移，表中的 `+` 為方法內 byte offset。

| 類／方法 | code_off | 關鍵邏輯 |
|---|---|---|
| `home.headphones.ambientnoise.AmbientNoiseCardItem.initItems(Context)` | `0x3111b4` | 建立一般／ANC／通透三個 `AmbientNoiseRadioButtonItem`，動作分別為 OFF、NOISE_CANCELLING、TRANSPARENCY；三者 `ambientNoiseMode` 參數皆為 null。無佩戴／雙耳條件。 |
| `home.headphones.ambientnoise.AmbientNoiseRadioButtonItem.bind(ItemAmbientNoiseRadioButtonBinding,int)` | `0x311a74` | 設定文字、checked、radio listener 與詳情箭頭；沒有依佩戴／左右耳狀態停用選項。 |
| 同類 `bind$lambda$9$lambda$5$lambda$4(item,CompoundButton,boolean)` | `0x3118f4` | 只檢查 radio 已 checked、先前未 checked、action 非 null；`+0x0038` 直接呼叫 `MelomaniaDevice.switchAmbientNoiseMode(action)`。後面的 `ambientNoiseMode != null` 分支屬細部模式設定，主卡片因為 null 不走該分支。 |
| `melomaniadevice.MelomaniaDevice.switchAmbientNoiseMode(AmbientNoiseAction)` | `0x34518c` | 取得 `Gaia3Device` 後在 `+0x0012` 直接轉交相同 action。 |
| `devices.gaia3.Gaia3Device.switchAmbientNoiseMode(AmbientNoiseAction)` | `0x2f4c68` | `+0x000e` 建立 `Commands.SwitchAmbientNoiseMode(action)`，`+0x001c` 呼叫 `customApi.send(command)`；沒有發送其他指令、查詢耳數或佩戴狀態。 |
| `devices.gaia3.customapi.Gaia3CustomApi.send(Commands)` | `0x2f76d0` | 直接呼叫 `vendor.sendCommand(command)`。 |
| `devices.gaia3.customapi.CambridgeAudioVendor.sendCommand(Commands)` | `0x2f72a4` | 依命令型別分配 plugin；SWITCH 屬 misc plugin，沒有單耳／雙耳分支。 |
| `devices.gaia3.customapi.CambridgeAudioPlugin.sendCommand(Commands)` | `0x2f706c` | 以 vendor／feature 呼叫 PacketEncode.encode，記錄 TX，`+0x0076` 送出 packet；沒有佩戴查詢或單耳前置命令。 |

主路徑可摘要為：

```text
radio 新選項
  -> switchAmbientNoiseMode(action)
  -> Gaia3Device
  -> SwitchAmbientNoiseMode(action)
  -> Gaia3CustomApi.send
  -> CambridgeAudioVendor / misc plugin
  -> PacketEncode
```

另外檢查了 `HeadphonesViewModel.handleEvent`（`0x30f9b0`）、
`NoiseCancellingFragment.setMode`、`AudioCurationRepository.setAmbientNoiseMode` 及 coroutine。
官方 App 另有細部／adaptive audio curation 的 set-mode 路徑；
它不是因單耳而挑選的替代命令，主卡片三態仍使用上面的 SWITCH。
不要把 APK 存在 SET 或 Qualcomm audio curation API，誤當成單耳必須送 SET 的證據。

## 命令與 payload

類名前綴：`devices.gaia3.customapi.`，均在 classes2.dex。

- `model.AmbientNoiseAction.<clinit>`，`code_off=0x2f782c`：
  OFF=0、NOISE_CANCELLING=1、TRANSPARENCY=2、TOGGLE=3。
- `model.MiscIds.<clinit>`，`code_off=0x2fcf98`，`+0x0128` 字串
  `SWITCH_AMBIENT_NOISE_MODE`，`+0x012c` 的值為 14（`0x000E`）。
- `utils.PacketEncode.getPayload(int,Commands)`，`code_off=0x3018b8`：
  `+0x025a` 檢查 `SwitchAmbientNoiseMode`，配置 **一個 byte**，內容直接是
  `command.mode.value`（`+0x026a` 到 `+0x027c`）。沒有耳數、wear bit 或單耳參數。
- `PacketEncode.encode`，`code_off=0x301c98`：以相同 command 和 payload 建立 v3 packet。

小工具既有真機驗證 frame（參見 [README](../README.md) 及
[GAIA 協議筆記](../../melomania-re/GAIA-ANC-protocol.md)）：

```text
一般：FF 03 00 01 0B 9E 00 0E 00
ANC ：FF 03 00 01 0B 9E 00 0E 01
通透：FF 03 00 01 0B 9E 00 0E 02
ACK ：FF 03 00 01 0B 9E 01 0E <相同 action>
```

未發現官方 App 因單耳先送另一個查詢再送 SWITCH 的邏輯。
官方 App 自身連線初始化仍可能有一般 GAIA 握手／訂閱；這不代表單耳有專屬初始化要求。
本階段保留小工具既有「直接 SWITCH，逾時才一次握手重送」及完整 ACK 才推進。

## 佩戴、入盒與其他連線狀態

掃描所有四個 DEX 中 Cambridge 類別的 wear／in-ear／both-ear／single-ear／earbud connection，
並追查 `leftInCase`／`rightInCase` 的引用。

- `home.headphones.weardetect.WearDetectItem.valueStateFlow()`，
  classes2.dex `code_off=0x33bd80`，讀的是 `MelomaniaDevice.getProximityMode()`。
- 同類 `setValue(boolean)`，`code_off=0x33bdcc`，設定 `setProximityMode(boolean)`，
  並紀錄 WEAR_DETECT 區塊互動；不是切換模式前的檢查。
- 同類 subtitle 的字串 ID 是 `wear_detect_enable_play_pause`，另有
  `wear_detect_help_text`；這是佩戴自動播放／暫停的設定區塊。
- `BatteryState.getLeftInCase()`／`getRightInCase()` 的外部引用出現在
  `MelomaniaDevice$monitorBatteryState$1$1.emit`，用於轉交電池狀態；
  上述 SWITCH 呼叫鏈完全沒有讀取它們。
- `ConnectedDevices` 的管理區塊與 ambient-noise 主卡片分開；沒有看到先要求兩隻耳機
  連線才准許 SWITCH 的條件。字串掃描本身不能證明不存在所有 gate，這裡的結論以
  主卡片 listener、轉交、命令分流及 payload 的實際指令為依據。

## 本小工具實作與驗收

`AncModeController.changeMode` 原先就沒有以電池、佩戴或耳機數量阻擋切換。
`GaiaClient.session` 只針對已選 MAC 建立一條 RFCOMM/SPP，直接發送目標 SWITCH；
本階段保留相同指令，補上單耳條件的註解與回歸測試。
移除 `cancelDiscovery()`，避免只授予 CONNECT 時因需要 SCAN 的取消探索操作而提早失敗。
SPP 一次只接受一個 RFCOMM 的限制不變。

`tools/protocol-tests/ProtocolChecks.kt` 使用正式 controller/client，模擬：

1. 只有左耳或只有右耳的單一 SPP endpoint，另一耳百分比為未知。
2. 另一耳在盒中／另一耳關機或離線（入盒 flag 為 1／0）。
3. 上述四組條件各切換三個模式，逐 byte 斷言只送同一條 SWITCH、收到相符 ACK 才保存、
   完成後關 socket；測試 adapter 的 `cancelDiscovery()` 會因未授 SCAN 而拋錯，確保不依賴它。
4. 原有缺電池廣播仍可切換、錯誤 ACK 不推進、一次握手 fallback、廣播刷新與 Worker 回歸保留。

執行 `python3 tools/check_phase2.py`。這是可控 transport/framework 替身測試，
不能代替 Redmi / HyperOS 單耳耳機與 launcher 真機驗收。
