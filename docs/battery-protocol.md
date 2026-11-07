## 2026-11-06 第二階段：電池廣播的 APK 解析證據

來源為使用者提供的 Melomania Connect 2.6.0 APK，非依數值大小推測。
`apk/classes2.dex` SHA-256：
`d0f2e8f3f62573c7d4e83d1778d858fd769e6db7661f3a0da19fd6205761a870`。

`com.cambridgeaudio.melomaniaplus.devices.gaia3.customapi.model.MiscIds` 指定
`GET_BATTERY_STATE = 3`；`customapi.utils.PacketDecode.decode(V3Packet)` 的該分支
直接讀取 9-byte payload，傳給 `customapi.model.BatteryState` 建構子。
建構子的 `iput` 指令明確標出各參數的欄位名稱。

| payload 索引（從 0 起） | 完整封包索引（從 0 起） | APK 欄位 | 意義 |
|---|---|---|---|
| 0 | 8 | upgradesAllowed | 非零表示允許升級，與充電不同 |
| 1 | 9 | leftChargingState | 左耳充電狀態 |
| 2 | 10 | rightChargingState | 右耳充電狀態 |
| 3 | 11 | caseChargingState | 盒充電狀態 |
| **4** | **12** | **leftPercent** | **左耳百分比（第 5 個 payload byte）** |
| **5** | **13** | **rightPercent** | **右耳百分比（第 6 個 payload byte）** |
| **6** | **14** | **casePercent** | **充電盒百分比（第 7 個 payload byte）** |
| 7 | 15 | leftInCase | 非零表示左耳在盒內 |
| 8 | 16 | rightInCase | 非零表示右耳在盒內 |

`ChargingState` 是各佔一個 byte 的列舉，並非百分比高位中的 bit flag：
`00 = NOT_CHARGING`、`01 = CHARGING`、`02 = MIS_CHARGING`、`FF = UNKNOWN`。
`MIS_CHARGING` 為 APK 原始名稱，其實際硬體觸發條件未實測，不擴大解讀。
其他未定義值在本 App 安全轉成 UNKNOWN；不據此猜測充電狀態。

等價解析（用欄位名稱還原）：

```kotlin
BatteryState(
    upgradesAllowed = payload[0] != 0,
    leftChargingState = ChargingState.from(payload[1]),
    rightChargingState = ChargingState.from(payload[2]),
    caseChargingState = ChargingState.from(payload[3]),
    leftPercent = payload[4],
    rightPercent = payload[5],
    casePercent = payload[6],
    leftInCase = payload[7] != 0,
    rightInCase = payload[8] != 0
)
```

因此實測封包：

```
FF 03 00 09 0B 9E 01 03 | 01 00 00 00 62 64 4E 00 00
```

代表左耳 98%、右耳 100%、充電盒 78%；三者充電狀態均為 NOT_CHARGING，左右耳
均不在盒內。稍後的 `61 61 4E` 則是左 97%、右 97%、盒 78%。
百分比對應與充電欄位均有 APK 直接證據；此處未新增真機充電測試。

另追查 `battery.BatteryRepository$batterySubscriber$1.onBatteryLevels(Set)`：它按照 Qualcomm
`Battery.LEFT_DEVICE`、`RIGHT_DEVICE`、`CHARGER_CASE` 選取 generic battery levels，
傳給 `battery.model.BatteryLevels(singleLevel, leftLevel, rightLevel, chargerLevel)`。
**這是 generic battery plugin 路徑，不能拿它的資料排列套用 Cambridge 9-byte payload**；
本次採用的偏移以 `PacketDecode` 的 Cambridge 自訂 command 3 分支為準。

重現分析（在 widget 專案根目錄執行，不改動原 APK）：

```sh
/home/hatch/workspace/melomania-re/venv/bin/python tools/inspect_battery_apk.py > docs/battery-apk-bytecode.txt
```

原始指令證據在 `docs/battery-apk-bytecode.txt`，包含 `BatteryState` 建構子、
`ChargingState` 列舉、`MiscIds`、`PacketDecode.decode` 與 `BatteryRepository`。
