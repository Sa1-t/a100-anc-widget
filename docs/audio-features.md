# A100：單聲道音訊與自適應降噪研究

本次以 Melomania Connect 2.6.0 的 DEX、既有真機協議紀錄及 Cambridge 官方資料交叉檢查。
**APK 能證明指令如何編碼；本次沒有真機，不能把它當成本機韌體已驗證成功。**
**更新（2026-11-07）：使用者以 Serial Bluetooth Terminal 實機測試 mono 指令，確認有效。mono 指令現為真機已驗證。**

## 單聲道：有官方與 APK 支援證據，加入讀回確認的控制

[官方 A100 mono FAQ](https://www.cambridgeaudio.com/row/en/faqs/what-mono-mode-a100)
明確說明 mono 把音訊混合成單聲道；
[單耳使用 FAQ](https://www.cambridgeaudio.com/usa/en/faqs/can-i-use-one-a100-ear-bud-time)
另外說明單耳可獨立使用。這不是把「另一耳在盒中」誤當成音訊混音設定。

`classes2.dex` SHA-256：`d0f2e8f3f62573c7d4e83d1778d858fd769e6db7661f3a0da19fd6205761a870`。
原始 bytecode 見 [audio-features-apk-bytecode.txt](audio-features-apk-bytecode.txt)，重現：

```sh
/home/hatch/workspace/melomania-re/venv/bin/python tools/inspect_audio_features_apk.py > docs/audio-features-apk-bytecode.txt
```

| 證據 | DEX code offset | 判讀 |
|---|---|---|
| `LocalConfig.<clinit>`、`DeviceConfig` 預設建構子 | `0x2e0858`、`0x2e0594` | A100 mask=9471，mono 的 bit 4 使用預設 true |
| `MonoAudioItem.setValue` → `MelomaniaDevice.setMonoMode` → `Gaia3Device.setMonoMode` | `0x32cba8`、`0x3450ec`、`0x2f4b80` | 使用 `Commands.SetMonoMode(Boolean)`，不經 ANC 路徑 |
| `CambridgeAudioVendor.<init>` / `sendCommand` | `0x2f719c` / `0x2f72a4` | vendor=2974 (`0x0B9E`)，mono 路由到 modesPlugin，feature=1 |
| `ModesIds.<clinit>` | `0x2fd3c8` | SET_MONO_MODE wire value=8，GET_MONO_MODE=9；不是 enum ordinal 7/8 |
| `PacketEncode` mono 分支 | 詳見原始 dump | SET payload 一個 boolean byte；GET 空 payload |
| `PacketDecode.decode` mono 分支 | 詳見原始 dump | GET 回應 payload[0] 為 boolean |

依 GAIA v3 的 `(feature << 9) | (type << 7) | command`：

| 操作 | 封包 |
|---|---|
| 讀取 mono | `FF 03 00 00 0B 9E 02 09` |
| 關閉 mono | `FF 03 00 01 0B 9E 02 08 00` |
| 開啟 mono | `FF 03 00 01 0B 9E 02 08 01` |
| 接受的讀回格式 | `FF 03 00 01 0B 9E 03 09 <00/01>` |

這些是 **APK 推導的 mono 指令**，不是原本真機已驗證的 ANC SWITCH 指令。
2026-11-07 使用者以 Serial Bluetooth Terminal 實機測試，確認三條指令皆有效。
mono 的 SET (`0x0208`) 與已證實失效的 ANC SET (`0x000F`) 是不同 feature/command。

實作在 `MonoProtocol.kt`、`GaiaClient.mono()`、`AncModeController.mono()`：

1. 開 App 不連線。按「讀取單聲道狀態」才以共用操作守衛短暫開 SPP，發 GET。
2. 首次 GET 無有效回應才用原版本握手一次、再 GET 一次；仍失敗則回報未知，不能操作開關。
3. 修改開關時再次先 GET；與目標相同就不寫 SET。否則 SET 一次，再 GET 讀回實際值。
4. 只有完整、正確 vendor/feature/type/command/長度與 00/01 payload 的讀回才顯示確認值。
   ANC ACK、mono SET ACK、錯誤或無回應都不當成功；讀回不同值顯示耳機實際值並提示未改變。
5. 成功、失敗都關 socket。不送 ANC SWITCH、不動三態紀錄；同連線仍可保存原電池廣播。
6. 不把 mono 當成本機偏好儲存或開機重送；離開 App 後失效，返回需重讀，避免舊值看似即時狀態。

## 自適應 ANC：硬體有此能力，沒有本次可採用的獨立開關

[官方 A100 產品頁](https://www.cambridgeaudio.com/usa/en/products/headphones/melomania/melomania-a100)
將其降噪列為 Adaptive Active Noise Cancelling，並以普通 ANC 選項啟用降噪。
**不能因為沒有第四態，就說 A100 硬體不支援自適應。**

APK 也確實有 adaptive UI：

- `LocalConfig.<clinit>` 對 A100 的 `showAdaptiveAncSwitch` 設為 true
  （mask 9471 未遮罩 bit 256，傳入的 v13=1）。
- `NoiseCancellingFragment.onCreateView`（`0x3128cc`）還要求 API level ≥1.12。
- 但開關回呼 `onCreateView$lambda$7`（`0x312d50`）只是把模式經
  `AmbientNoiseMode.getAdaptiveMode(Boolean)` 轉換後呼叫 `setAmbientNoiseMode`，
  走原 ANC SET 路徑；它不是另一條已驗證的 adaptive 專用指令。
  詳見既有 [single-ear-apk-bytecode.txt](single-ear-apk-bytecode.txt)。
- `ANCMode`（`0x2f5ad4`）十個值仍為 0–9；enum 有名字不等於這支韌體接受。
  既有 `GAIA-ANC-protocol.md` 真機紀錄已證明 ANC SET 無回應，只可採用
  SWITCH 明確動作 00/01/02。沒有新證據可以推翻它。

因此本次保留既有 ANC 開啟方式，不新增強度、第四態或獨立 adaptive 開關。
「內建 ANC 會使用其自適應能力」是根據官方描述的判斷，並非本次量測到演算法行為。

## 驗證邊界

`tools/protocol-tests/MonoChecks.kt` 驗證推導封包逐 byte、讀回格式、開／關／純讀取、
相同值不寫、錯誤／遺失讀回、未支援時不 SET、握手 fallback、離線關閉、電池及 ANC 紀錄保留。
它使用正式 client/controller 與模擬 transport，**不代表 A100 mono 真機驗收完成**。

本機安裝後：先關官方 App，讀取 mono，再分別開／關並用左右聲道測試音源確認混音；
另用官方 App 交叉查看。若讀取失敗，維持未知與停用開關，記錄耳機韌體與回應供後續判斷。
