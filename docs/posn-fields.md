# POSN public 欄位盤點

來源：`javap -p -cp "lib/posn.jar:lib/*" com.ipt.app.posn.ui.POSN`
產生日期：2026-08-27
posn.jar mtime：Nov 25 17:17:18 2025
posn.jar sha256：d6ffd0b6d0572bdecf99fe8d9c73e3b5ae4568733a4fd65d89cf2df35b10f5d9

> 外掛只透過反射讀這些欄位，且一律走 `Safe`：查不到就降級不顯示。
> EPB patch 改版後重跑 `驗證_PosAssist.command` 就能確認欄位是否還在。

## 型別統計

| 型別 | 數量 |
|---|---|
| javax.swing.JLabel | 34 |
| javax.swing.JTextField | 30 |
| javax.swing.JButton | 28 |
| javax.swing.JToggleButton | 15 |
| javax.swing.JPanel | 12 |
| javax.swing.JTable | 1 |
| javax.swing.JScrollPane | 1 |
| java.awt.Container | 1 |

## 外掛實際用到的欄位

| 欄位 | 型別 | 用途 |
|---|---|---|
| `vipIdTextField` | JTextField | 當前交易的會員代碼。掛 DocumentListener 觸發查詢 |
| `vipNameTextField` | JTextField | 會員名稱，保留給後續比對用 |
| `vipDiscTextField` | JTextField | 會員折扣，保留給後續顯示用 |
| `posNoTextField` | JTextField | POS 機號，顯示在面板底部並寫進 log |

## 全部 public Swing / AWT 欄位

| 欄位 | 型別 |
|---|---|
  public java.awt.Container getApplicationView();
| `actionPanel` | JPanel |
| `backSpaceButton` | JToggleButton |
| `cardNoLabel` | JLabel |
| `cardNoText` | JTextField |
| `changeLable` | JLabel |
| `changeTextField` | JTextField |
| `clrButton` | JToggleButton |
| `collectionToggleButton` | JButton |
| `copyToggleButton` | JButton |
| `currentlineNetPriceTextField` | JTextField |
| `dateTextField` | JTextField |
| `dayEndButton` | JButton |
| `deleteButton` | JButton |
| `depositToggleButton` | JButton |
| `descriptionLabel` | JLabel |
| `descriptionTextField` | JTextField |
| `discountButton` | JButton |
| `discountLabel` | JLabel |
| `discountTextField` | JTextField |
| `drawerButton` | JButton |
| `eightButton` | JToggleButton |
| `empId1TextField` | JTextField |
| `empId2Label` | JLabel |
| `empId2TextField` | JTextField |
| `empIdLabel` | JLabel |
| `empName1TextField` | JTextField |
| `empName2TextField` | JTextField |
| `enterButton` | JToggleButton |
| `fiveButton` | JToggleButton |
| `fourButton` | JToggleButton |
| `grossTotalLabel` | JLabel |
| `grossTotalTextField` | JTextField |
| `holdButton` | JButton |
| `iconLabel` | JLabel |
| `invNoLeftTextField` | JTextField |
| `itemScanningPanelBottomDualLabel` | JLabel |
| `itemScanningPanelLeftDualLabel` | JLabel |
| `itemScanningPanelRightDualLabel` | JLabel |
| `itemScanningPanelTopDualLabel` | JLabel |
| `itemScanningPanel` | JPanel |
| `keyCardPanel` | JPanel |
| `leftPanel` | JPanel |
| `mainPanel` | JPanel |
| `minuButton` | JToggleButton |
| `nextInvNoTextField` | JTextField |
| `nineButton` | JToggleButton |
| `noLabel` | JLabel |
| `noTextField` | JTextField |
| `oneButton` | JToggleButton |
| `openCodeButton` | JButton |
| `payButton` | JButton |
| `payLable` | JLabel |
| `payTextField` | JTextField |
| `pbVipButton` | JButton |
| `pluIdLabel` | JLabel |
| `pluIdTextField` | JTextField |
| `pluInputButton` | JButton |
| `pointButton` | JToggleButton |
| `pointsLabel` | JLabel |
| `pointsTextField` | JTextField |
| `posLineScrollPane` | JScrollPane |
| `posLineTable` | JTable |
| `posNoPanelLeftDualLabel` | JLabel |
| `posNoPanelRightDualLabel` | JLabel |
| `posNoPanelTopBottomLabel` | JLabel |
| `posNoPanelTopDualLabel` | JLabel |
| `posNoPanel` | JPanel |
| `posNoTextField` | JTextField |
| `prescriptionButton` | JButton |
| `priceButton` | JButton |
| `priceSummaryPanelBottomDualLabel` | JLabel |
| `priceSummaryPanelLeftDualLabel` | JLabel |
| `priceSummaryPanelRightDualLabel` | JLabel |
| `priceSummaryPanelTopDualLabel` | JLabel |
| `priceSummaryPanel` | JPanel |
| `qtyButton` | JButton |
| `qtyInputPanel` | JPanel |
| `quickPickButton` | JButton |
| `readCardButton` | JButton |
| `refNoButton` | JButton |
| `refNoLabel` | JLabel |
| `refNoTextField` | JTextField |
| `refundToggleButton` | JButton |
| `regInvButton` | JButton |
| `remarkButton` | JButton |
| `resetInvButton` | JButton |
| `returnToggleButton` | JButton |
| `rightPanel` | JPanel |
| `salesInputPanel` | JPanel |
| `salesToggleButton` | JButton |
| `serialNoButton` | JButton |
| `settingButton` | JButton |
| `sevenButton` | JToggleButton |
| `shopIdLable` | JLabel |
| `shopIdTextField` | JTextField |
| `shopNameTextField` | JTextField |
| `sixButton` | JToggleButton |
| `tablePanel` | JPanel |
| `taxInvNoLabel` | JLabel |
| `taxLabel` | JLabel |
| `taxRefNoLabel` | JLabel |
| `taxRefNoTextField` | JTextField |
| `taxTextField` | JTextField |
| `tenderIdLabel` | JLabel |
| `tenderTextField` | JTextField |
| `threeButton` | JToggleButton |
| `topQtyButton` | JButton |
| `totalLabel` | JLabel |
| `totalPointsLabel` | JLabel |
| `totalPointsTextField` | JTextField |
| `totalStkQtyLable` | JLabel |
| `totalStkQtyTextField` | JTextField |
| `totalTextField` | JTextField |
| `transactionStatePanel` | JPanel |
| `twoButton` | JToggleButton |
| `vipDiscTextField` | JTextField |
| `vipIdLable` | JLabel |
| `vipIdLovButton` | JButton |
| `vipIdTextField` | JTextField |
| `vipNameTextField` | JTextField |
| `zeroButton` | JToggleButton |
