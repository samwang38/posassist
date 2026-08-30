# PosAssist

EPB（Enterprise Browser）的 POS 輔助面板外掛。結帳時在 EPB 左側欄顯示會員資訊、
近期預約，以及可一鍵帶入 POS 的自訂結帳代碼。

**不修改 EPB 任何原廠檔案。** 只新增 `<EPB_ROOT>/EPB/PosAssist/` 一個目錄，
出問題改用原本的 EPB 捷徑開就完全正常。

---

## 安裝

到 [Releases](https://github.com/samwang38/posassist/releases/latest) 下載
`PosAssist-x.y.z.zip`，解壓後雙擊 `安裝_PosAssist.command`。

安裝器會自己找到 EPB 與 Java、放好檔案、建立桌面捷徑「EPB（含輔助面板）」，
最後問要不要現在設定預約系統帳密。

macOS 專用。

## 功能

| 功能 | 說明 |
|---|---|
| 會員查詢 | 輸入電話或會員代碼按 Enter，顯示代碼／姓名／電話／Email／等級 |
| 帶入會員 | 點會員代碼直接填進 POS 的會員欄並送出 |
| 近期預約 | 顯示該會員近期的預約，依急迫性排序（已到貨／保留 → 已預約 → 已取貨 → 其他） |
| 帶入預約單號 | 點單號後按 F10，自動填進序號視窗的「預約單號」欄 |
| 結帳代碼 | 自訂的分類九宮格，點一下把代碼帶進 POS，等同自己打代碼按 Enter |
| 自動更新 | 開啟 EPB 時自動更新到最新版，不影響任何個人設定 |

## 自動更新

開啟 EPB 時，啟動器會比對本機 `VERSION` 與 Release 的 `manifest.txt`：

- 更新**只發生在 EPB 啟動之前**，絕不會在結帳中途換程式
- 檢查限時 5 秒、下載限時 20 秒，任何失敗都直接用現有版本開
- 下載後比對 sha256，不符就丟掉不換
- 上一版永遠留在 `posassist.jar.prev`
- `config/posassist.properties` 設 `autoUpdate=false` 就完全不連網

想單獨跑一次更新檢查來診斷：

```bash
<EPB_ROOT>/EPB/PosAssist/PosAssist.command --update-only
```

## 設定

面板右下角的「設定」可以改預約帳密、面板位置、會員建立與建立表單要出現哪些欄位。
存檔後重開 EPB 生效。

側欄要 POS 開著才看得到，而「面板出問題」正好就是進不去側欄的時候，
所以同一個視窗也能單獨叫起來（不需要登入 EPB）：

```bash
<EPB_ROOT>/EPB/PosAssist/PosAssist.command --settings
```

## 個人設定不會被更新覆蓋

`config/` 底下這些是各店自己的東西，更新、重裝、移除都不會被動到：

| 檔案 | 內容 |
|---|---|
| `reservation.properties` | 預約系統帳密（權限 600） |
| `posassist.properties` | 面板模式、自動更新開關 |
| `codes.txt` / `codes.txt.bak` | 自訂結帳代碼（分類欄可寫「主分類/子分類」） |
| `codes.pins.txt` | 釘選置頂的代碼（一行一個代碼） |
| `panel.state` | 面板記住的狀態（上下分隔位置）。程式自己寫的，刪掉就回預設 |

`*.example` 範本則每次更新覆蓋成最新。

---

## 發新版

改完程式後，跑一支就好：

```bash
./發版.command 1.5.0
```

它會改版號、提交、推上去；GitHub Actions 接手編譯、算 sha256、建 Release。
門市下次開 EPB 就會自動更新到這一版。

**版號一定要換。** 門市是比對版號決定要不要更新，同一個版號重發不會觸發任何更新。
`發版.command` 會擋掉重複的版號。

其他指令：

```bash
./build.command          # 只編譯出 posassist.jar
./打包.command           # 建置 + 組安裝包 + 產生 manifest（不發佈）
./發佈.command --release # 從本機直接發佈（平常用不到，push 就會自動發）
```

版本號的唯一來源是 `src/com/posassist/Version.java` 的 `NAME`；
`打包.command` 由本機與 CI 共用，所以兩邊的產出不會走鐘。

### 設計不變量

改動時請維持這幾點，它們都是踩過坑換來的：

- **零編譯期依賴 EPB**：全部走反射與 `java.lang.reflect.Proxy`，
  `build.command` 刻意不帶任何 EPB classpath。EPB 改版少了某個欄位時，
  外掛只會少顯示一項，不會炸掉結帳畫面。
- **不自己寫 EPB 的資料**：查詢只跑 `SELECT`，外掛沒有任何 `INSERT`／`UPDATE`。
  帶入 POS 是填欄位再送 Enter，由 POSN 自己驗證，跟店員手打完全同一條路徑。
  會員建立是叫出 EPB **原生的建立表單**（`CreatorView`），寫入由框架自己的
  `BlockFormPM.commitChanges()` 完成，POSVIP 的驗證器、自動帶值、預設值與權限
  控制全程有效 —— 走的是 `CreatorAction` 這條 EPB 模組之間本來就在用的路。
  但**發動寫入的是外掛**，這點跟純查詢時期不同，改動這一塊要格外小心。
- **不碰原廠檔案**：`Shell/lib/`、`shell.jar`、`appcfg/` 都會被 EPB 的 patch 覆蓋。
- **面板不搶鍵盤焦點**：否則條碼掃描器的輸入會跑進面板而不是 POS。
- **SQL 只用 Postgres 與 Oracle 都有的語法**：`EpbApplicationUtility.getResultList`
  走的是**本機 client 端資料庫**（各店可能不同），不是 AP WebService 後面那台 Oracle。
- **側欄可回復**：只呼叫 `setLeftComponent`，原元件從不銷毀，多重還原觸發點。

### 測試

`SelfTest` 不需登入、不需 POS 權限就能跑，會逐項確認外掛依賴的每個 EPB 掛載點
在該機器上真的存在：

```bash
<EPB_ROOT>/EPB/PosAssist/驗證_PosAssist.command
```

---

## 安全性須知

- 這個 repo 是公開的，**絕對不要把 `config/` 底下任何真實設定檔提交進來**。
  `.gitignore` 以檔名擋掉 `reservation.properties`、`codes.txt`、`codes.pins.txt` 等，但提交前仍請看一眼 `git status`。
- 自動更新意味著能改這個 repo 的人，就能在每一台安裝了 PosAssist 的 POS 上執行程式。
  這是刻意的取捨；不接受的門市可以關掉 `autoUpdate`。
