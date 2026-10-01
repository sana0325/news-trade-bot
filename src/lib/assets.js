// Спотові пари — лише коли форекс відкритий, ціни Twelve Data (WebSocket), без симуляції.
const spot = (symbol, digits) => ({
  id: symbol.replace('/', '').toLowerCase(),
  symbol,
  market: 'spot',
  digits,
  feed: 'twelvedata',
  tdSymbol: symbol,
});

// Кандидати для кнопки «Перевірити пари»: які з них дає тариф, каже сам Twelve Data.
export const SPOT_CATALOG = [
  ['EUR/USD', 5],
  ['GBP/USD', 5],
  ['USD/JPY', 3],
  ['USD/CHF', 5],
  ['AUD/USD', 5],
  ['USD/CAD', 5],
  ['NZD/USD', 5],
  ['EUR/GBP', 5],
  ['EUR/JPY', 3],
  ['GBP/JPY', 3],
  ['AUD/JPY', 3],
  ['EUR/AUD', 5],
  ['EUR/CHF', 5],
  ['EUR/CAD', 5],
  ['GBP/CHF', 5],
  ['AUD/CAD', 5],
  ['CAD/JPY', 3],
  ['CHF/JPY', 3],
  ['NZD/JPY', 3],
  ['AUD/NZD', 5],
  ['XAU/USD', 2],
].map(([symbol, digits]) => spot(symbol, digits));

// Пробний WebSocket дає щонайбільше 8 символів на з'єднання.
export const MAX_SPOT_PAIRS = 8;
// EUR/USD тариф точно дає (перевірено на телефоні); решту обирають після перевірки.
export const DEFAULT_SPOT = ['EUR/USD'];

export function spotAssetsFor(symbols) {
  const list = SPOT_CATALOG.filter((a) => symbols.includes(a.symbol)).slice(0, MAX_SPOT_PAIRS);
  return list.length ? list : SPOT_CATALOG.filter((a) => DEFAULT_SPOT.includes(a.symbol));
}

export const SPOT_ASSETS = spotAssetsFor(DEFAULT_SPOT);

// OTC — публічний графік Binarium (id активу, назва у фіді).
export const OTC_ASSETS = [
  { id: 'eurusd-otc', symbol: 'EUR/USD OTC', market: 'otc', binariumId: 43, feedName: 'OTC_EURUSD', base: 1.17, digits: 5, vol: 1.2e-5 },
  { id: 'gbpusd-otc', symbol: 'GBP/USD OTC', market: 'otc', binariumId: 46, feedName: 'OTC_GBPUSD', base: 1.345, digits: 5, vol: 1.3e-5 },
];

export const ALL_ASSETS = [...SPOT_ASSETS, ...OTC_ASSETS];

// «EUR/USD, GBP/CHF і XAU/USD» — для текстів в інтерфейсі.
export function pairNames(symbols) {
  if (symbols.length <= 1) return symbols[0] ?? '';
  return `${symbols.slice(0, -1).join(', ')} і ${symbols[symbols.length - 1]}`;
}
