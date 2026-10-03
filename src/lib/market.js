// Свічки по 15 секунд зі справжніх цін.
export const TICK_MS = 280;
export const CANDLE_MS = 15_000;
export const HISTORY_CANDLES = 128;
export const MAX_CANDLES = 140;

export const bucketOf = (t) => Math.floor(t / CANDLE_MS) * CANDLE_MS;

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
