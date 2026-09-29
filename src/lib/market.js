// Симуляція ціни і свічки по 15 секунд.
export const TICK_MS = 280;
export const CANDLE_MS = 15_000;
export const HISTORY_CANDLES = 128;
export const MAX_CANDLES = 140;

export function gauss(rand = Math.random) {
  let u = 0;
  while (u === 0) u = rand();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rand());
}

export const bucketOf = (t) => Math.floor(t / CANDLE_MS) * CANDLE_MS;

// Випадкове блукання з режимами тренду: інколи ціна рухається в один бік кілька хвилин.
export function createWalker(asset, rand = Math.random) {
  return { price: asset.base, drift: 0, regimeLeft: 0, vol: asset.vol, base: asset.base, rand };
}

export function stepWalker(w) {
  const r = w.rand;
  if (w.regimeLeft <= 0) {
    const trending = r() < 0.4;
    const strength = 0.01 + r() * 0.05;
    w.drift = trending ? (r() < 0.5 ? -1 : 1) * strength * w.vol : 0;
    w.regimeLeft = Math.round((60_000 + r() * 360_000) / TICK_MS);
  }
  w.regimeLeft--;
  const pull = -(w.price / w.base - 1) * 0.002; // не дає ціні відпливти далеко
  w.price *= 1 + w.drift + pull * w.vol * 50 + w.vol * gauss(r);
  return w.price;
}

export function pushPrice(candles, t, price) {
  const b = bucketOf(t);
  const last = candles[candles.length - 1];
  if (last && last.t === b) {
    last.c = price;
    if (price > last.h) last.h = price;
    if (price < last.l) last.l = price;
  } else {
    candles.push({ t: b, o: last ? last.c : price, h: price, l: price, c: price });
    if (candles.length > MAX_CANDLES) candles.splice(0, candles.length - MAX_CANDLES);
  }
}

// Історія зі старту — 128 свічок до поточного моменту.
export function buildHistory(walker, now) {
  const candles = [];
  const start = bucketOf(now) - (HISTORY_CANDLES - 1) * CANDLE_MS;
  for (let t = start; t < now; t += TICK_MS) pushPrice(candles, t, stepWalker(walker));
  pushPrice(candles, now, stepWalker(walker));
  return candles;
}
