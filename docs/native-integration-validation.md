# STORESUM 原生整合驗證

日期：2026-09-06。目標版本：1.6.0-preview.3。

## 已驗證

- 本機既有 PosAssist Java 程序可透過 Java Attach 連接。
- 透過原生 `canViewApp` 檢查後，`ApplicationPool.openApplication` 成功開啟 STORESUM。
- 透過 `EnquiryViewBuilder.getCurrentCriteriaItems` 可讀取目前原生條件；初始狀態為未選取存貨代碼。
- 透過 `installCriteriaComponent(View, JComponent)` 成功掛載「驗證商品帶入（PosAssist）」按鈕，EDT 佈局後 `isShowing=true`。
- 繼承自非 public 父類別的方法，反射呼叫必須 `setAccessible(true)`；直接 `Method.invoke` 曾拋出 `IllegalAccessException`，修正反射存取後條件讀取成功。正式轉接層須涵蓋此相容性案例。

## 登入實機已核對

使用者在原生 STORESUM 勾選後按 OK，再按驗證按鈕，明確回覆「完全一致」。六個代碼為 `09600040`、`50300018`、`09600039`、`50300019`、`09600041`、`50300020`。原生回傳 `keyword=" IN "`、`value=null`、`getValuesCopy()` 含六個獨立 String；未從畫面字串或庫存結果列解析。依核准計畫，原生入口及多選帶入先決條件已通過，之後才開始正式比較介面重構。

新版查詢類別以隔離 ClassLoader 在當時登入程序中唯讀執行；正式 TRANS 精確查詢辨識出 `09600039` 不符合範圍並拒絕整批帶入。Query Kit 回傳同一結果。2026-09-07 另查商品主檔確認該代碼 MODEL 含 Demo、STATUS_FLG=R、CAT1_ID=1002，並非一般正常銷售品。未自動放寬範圍或把缺資料當零。

單選「等於」與多選「包括」另外使用實際 EPB CriteriaItem 類別，在無登入 JVM 中驗證；此項是資料格式相容性測試，不是人工 GUI 驗收。

## 2026-09-07 使用者安裝後確認

使用者確認 preview.3「查詢 ok」，提供 `07310978`、`07312495` 兩項 AirPods 在共同供貨店視窗中的庫存截圖（31 間符合篩選），因此完整 TRANS 查詢正向流程已有現場證據。這是當時快照，並非最新庫存數字，也不是所有門市權限／POSN 回歸均已通過。

preview.4 依這次回饋改成點「庫存工具」才開視窗、原生選取取代比較、商品列／門市欄及庫存單值顯示。此版新 UI 尚待使用者安裝後核對。

## 驗證界線

暫時驗證入口的原生多選已由使用者核對。完整庫存快照正向流程已由 preview.3 截圖確認；preview.4 的點按開啟、取消勾選、POSN、浮動模式、關閉／登出仍須單機回歸。離線、Query Kit 與 SelfTest 結果見 `inventory-validation.md`，不以靜態 API 存在取代現場驗收。

## 診斷工具範圍

暫時診斷曾位於 `build/native-probe/` 及系統暫存目錄，非交付檔案；建置及系統重啟後不保留。已確認的結果記錄於本文件。不寫 ERP、不修改 EPBrowser 原廠檔案、不發布更新。診斷按鈕屬於目前 STORESUM 畫面，關閉該畫面即不保留；重新啟動 EPBrowser 會卸除暫時載入的診斷類別。原生先決驗證當下未替換既有安裝；後續變更僅交付 preview.3 本機試用包。
