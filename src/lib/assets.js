// Спотові пари — симуляція, не біржа. Лише коли форекс відкритий.
const spot = (symbol, base, digits) => ({
  id: symbol.replace('/', '').toLowerCase(),
  symbol,
  market: 'spot',
  base,
  digits,
  vol: 1.2e-5,
});

export const SPOT_ASSETS = [
  spot('EUR/USD', 1.17, 5),
  spot('GBP/USD', 1.345, 5),
  spot('USD/JPY', 147.5, 3),
  spot('USD/CHF', 0.795, 5),
  spot('AUD/USD', 0.66, 5),
  spot('USD/CAD', 1.385, 5),
  spot('NZD/USD', 0.585, 5),
  spot('EUR/GBP', 0.87, 5),
  spot('EUR/JPY', 172.5, 3),
  spot('GBP/JPY', 198.4, 3),
  spot('AUD/JPY', 97.3, 3),
  spot('EUR/AUD', 1.773, 5),
];

// OTC — публічний графік Binarium (id активу, назва у фіді).
export const OTC_ASSETS = [
  { id: 'eurusd-otc', symbol: 'EUR/USD OTC', market: 'otc', binariumId: 43, feedName: 'OTC_EURUSD', base: 1.17, digits: 5, vol: 1.2e-5 },
  { id: 'gbpusd-otc', symbol: 'GBP/USD OTC', market: 'otc', binariumId: 46, feedName: 'OTC_GBPUSD', base: 1.345, digits: 5, vol: 1.3e-5 },
  { id: 'usdjpy-otc', symbol: 'USD/JPY OTC', market: 'otc', binariumId: 47, feedName: 'OTC_USDJPY', base: 147.5, digits: 3, vol: 1.2e-5 },
  { id: 'xauusd-otc', symbol: 'XAU/USD OTC', market: 'otc', binariumId: 48, feedName: 'OTC_XAUUSD', base: 3700, digits: 2, vol: 2e-5, note: 'золото' },
];

export const ALL_ASSETS = [...SPOT_ASSETS, ...OTC_ASSETS];
