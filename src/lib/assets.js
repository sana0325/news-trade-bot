// Спотові пари — лише коли форекс відкритий.
const spot = (symbol, base, digits) => ({
  id: symbol.replace('/', '').toLowerCase(),
  symbol,
  market: 'spot',
  base,
  digits,
  vol: 1.2e-5,
});

// Лише дві пари зі справжніми цінами Twelve Data (WebSocket). Симуляції для них немає.
// GBP/USD прибрано: пробний WebSocket безкоштовного тарифу її не дає.
export const SPOT_ASSETS = [
  { ...spot('EUR/USD', 1.17, 5), feed: 'twelvedata', tdSymbol: 'EUR/USD' },
  { ...spot('USD/JPY', 147.5, 3), feed: 'twelvedata', tdSymbol: 'USD/JPY' },
];

// OTC — публічний графік Binarium (id активу, назва у фіді).
export const OTC_ASSETS = [
  { id: 'eurusd-otc', symbol: 'EUR/USD OTC', market: 'otc', binariumId: 43, feedName: 'OTC_EURUSD', base: 1.17, digits: 5, vol: 1.2e-5 },
  { id: 'gbpusd-otc', symbol: 'GBP/USD OTC', market: 'otc', binariumId: 46, feedName: 'OTC_GBPUSD', base: 1.345, digits: 5, vol: 1.3e-5 },
];

export const ALL_ASSETS = [...SPOT_ASSETS, ...OTC_ASSETS];

// «EUR/USD і USD/JPY» — для текстів в інтерфейсі.
export const SPOT_NAMES = SPOT_ASSETS.map((a) => a.symbol).join(' і ');
