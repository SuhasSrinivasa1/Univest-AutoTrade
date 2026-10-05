from pathlib import Path

root=Path(__file__).resolve().parents[1]
j=root/'app/src/main/java/com/suhas/multyfideliverybuy'
read=lambda n:(j/n).read_text()
groww=read('GrowwClient.java'); service=read('MultyfiNotificationService.java'); main=read('MainActivity.java'); prefs=read('AppPrefs.java')
univest=read('UnivestManager.java'); uparser=read('UnivestParser.java'); ustate=read('UnivestStateStore.java')
mexit=read('MultyfiExitParser.java'); mstate=read('MultyfiTradeStateStore.java'); mmanager=read('MultyfiStrategyManager.java')
charges=read('IntradayChargeTarget.java'); delivery=read('DeliveryNetTarget.java'); network=read('NetworkCheck.java'); instruments=read('InstrumentRepository.java')
manifest=(root/'app/src/main/AndroidManifest.xml').read_text(); gradle=(root/'app/build.gradle').read_text()
checks={
 'same package':"applicationId 'com.suhas.multyfideliverybuy'" in gradle,
 'version 1.4.6':"versionName '1.4.6'" in gradle and 'versionCode 146' in gradle,
 'Univest toggle':'Enable Univest equity automation' in main and 'isUnivestEnabled' in prefs,
 'UI delivery wording':'Delivery Trade Hub' in main and 'NEVER MIS / INTRADAY' in main and '+0.5% estimated NET-profit GTT target' in main,
 'Univest budget slider 0..100k':'univestBudgetBar.setMax(10)' in main and 'progress * 10000' in main and 'SAVE UNIVEST ENTRY BUDGET' in main,
 'Univest budget persisted':'getUnivestBudget' in prefs and 'univest_budget' in prefs,
 'Univest entry uses saved budget':'AppPrefs.getUnivestBudget' in univest and 'placeUnivestCncMarketBuy' in univest,
 'Univest averaging fixed 5000':'AVERAGE_BUDGET = 5000' in univest,
 'Univest averaging 2 4 6 8':'{0.02, 0.04, 0.06, 0.08}' in univest and 'four ₹5,000 adds' in univest,
 'Univest net profit 2500':'NET_PROFIT_TARGET = 2500.0' in univest,
 'Univest protective stop 20 percent':'PROTECTIVE_STOP_DROP = 0.20' in univest and 'createUnivestCncStopGtt' in groww and 'modifyUnivestCncStopGtt' in groww and 'protectiveStopGttId' in ustate and '"MARKET"' in groww,
  'Univest BE series supported':'symbol.endsWith(\"-BE\")' in uparser and '!\"BE\".equalsIgnoreCase(series)' in instruments,
'Univest equity only filters':'"options"' in uparser and '"futures"' in uparser and '"commodity"' in uparser and '"mcx"' in uparser,
 'Univest duration 1-3 months':'DURATION_MONTHS' in uparser and 'isEligibleEntryDuration' in uparser and 'end <= 3.0' in uparser and 'start >= 1.0' in uparser,
 'Univest cycle-aware reentry':'ENTRY_PENDING' in ustate and 'reserveNewEntry' in ustate and 'canReservePhase' in ustate and 'canExecuteReservedPhase' in ustate and 'UNIVEST NEW RECOMMENDATION ACCEPTED' in service,
 'Univest no raw 24h signal dedupe':'sha256("UNIVEST|" + signal.rawText)' not in service,
 'Univest always CNC delivery':'placeUnivestCncMarketBuy' in groww and 'submitMarketOrder(context, symbol, quantity, "CNC", "BUY"' in groww and 'placeUnivestCncMarketSell' in groww,
 'Univest regular sell conflict reconciliation':'/v1/order/list' in groww and '/v1/order/cancel' in groww and 'cancelOpenCncSellOrdersForSymbol' in groww and 'shouldCancelCncSellOrder' in groww and 'Math.min(state.quantity, Math.max(0, broker.quantity))' in univest,
 'Univest state persistence':'univest_equity_state' in ustate and 'states_json' in ustate,
 'Multyfi intraday budget retained':'getIntradayBudget' in service and 'intradayBudgetBar.setMax(9)' in main,
 'Multyfi intraday remains CNC delivery':'placeMultyfiDeliveryBuyWithHalfPercentNetGtt' in service and 'submitMarketOrder(context, symbol, quantity, "CNC", "BUY"' in groww,
 'Multyfi half percent net budget target':'savedBudget * 0.005' in groww and 'targetPriceForNetProfit' in groww,
 'Multyfi no pre-buy LTP sizing':'placeMultyfiDeliveryBuyWithHalfPercentNetGtt' in groww and 'submitMarketOrder(context, symbol, quantity, "CNC", "BUY"' in groww,
 'Multyfi first spike monitor':'SHARP_PULLBACK = 0.002' in mmanager and 'modifyCashGttSellTarget' in mmanager and 'multyfiMonitor' in service,
 'Multyfi close parser':'MultyfiExitParser.parse' in service and 'closing early' in mexit and 'sell immediately' in mexit,
 'Multyfi close dedicated executor':'multyfiExitExecutor' in service,
 'Multyfi close cleans delivery before short':'cancelCashGtt' in mmanager and 'placeCncMarketSell' in mmanager and 'SHORT NOT started' in mmanager,
 'Multyfi short is MIS':'placeMultyfiMisShortWithOco' in groww and 'submitMarketOrder(context, symbol, qty, "MIS", "SELL"' in groww,
 'Multyfi short target half percent':'budget * 0.005' in groww and 'IntradayChargeTarget.targetPriceForNetProfit' in groww,
 'Multyfi short stop one percent':'budget * 0.01' in groww and 'stopTriggerForMaxLoss' in groww,
 'Multyfi short OCO':'smart_order_type", "OCO"' in groww and 'product_type", "MIS"' in groww and 'SL_M' in groww,
 'Groww smart order modify':'/v1/order-advance/modify/' in groww and 'setRequestMethod("PUT")' in groww,
 'Groww smart order create':'/v1/order-advance/create' in groww,
 'Groww GTT cancel':'/v1/order-advance/cancel/CASH/GTT/' in groww,
 'Groww LTP':'/v1/live-data/ltp' in groww,
 'Groww order detail':'/v1/order/detail/' in groww,
 'Manual long retained':'executeManualLong' in groww and 'BUY LONG + SET 1% GTT' in main,
 'Manual short retained':'executeManualShort' in groww and 'SHORT — INTRADAY' in main,
 'Official instrument master':'growwapi-assets.groww.in/instruments/instrument.csv' in instruments,
 'Notification listener':'BIND_NOTIFICATION_LISTENER_SERVICE' in manifest,
 'Static IP retained':'api4.ipify.org' in network and 'checkip.amazonaws.com' in network,
 'Entry-pending race guard':'entryPending' in mstate and 'entry is still unresolved' in mmanager,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(('PASS' if v else 'FAIL')+' - '+k)
if failed: raise SystemExit('Contract validation failed: '+', '.join(failed))
