import { BUY, SELL } from './indicators.js';

export const SIGNAL_MS = 300_000; // єдиний час сигналу — 5 хвилин
export const TF_SECONDS = 300;
export const MAX_ACTIVE = 3;
export const PAIR_PAUSE_MS = 240_000; // пауза між сигналами однієї пари
export const DIRECTION_MIN = 8; // |buy − sell| для напрямку
export const SHOW_MIN = 12; // |buy − sell| щоб показати сигнал
export const MIN_CONFIDENCE = 74;

export const FILTERS = [
  { value: 74, label: 'Сильні' },
  { value: 80, label: 'Дуже сильні' },
  { value: 86, label: 'Найсильніші' },
];

const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));

export function tally(votes) {
  const t = { buy: 0, sell: 0, neutral: 0, maBuy: 0, maSell: 0, techBuy: 0, techSell: 0, total: votes.length };
  for (const v of votes) {
    if (v.vote === BUY) {
      t.buy++;
      if (v.group === 'ma') t.maBuy++;
      else t.techBuy++;
    } else if (v.vote === SELL) {
      t.sell++;
      if (v.group === 'ma') t.maSell++;
      else t.techSell++;
    } else t.neutral++;
  }
  return t;
}

export function evaluate(votes) {
  const t = tally(votes);
  const diff = t.buy - t.sell;
  const direction = diff >= DIRECTION_MIN ? 'call' : diff <= -DIRECTION_MIN ? 'put' : null;
  const trendScore = t.buy + t.sell > 0 ? Math.round((t.buy / (t.buy + t.sell)) * 100) : 50;
  const confidence = clamp(
    Math.round(50 + Math.abs(trendScore - 50) * 1.12),
    direction ? 70 : 48,
    90,
  );
  return { ...t, diff, direction, trendScore, confidence };
}

// Чи досить сильний для показу при обраному фільтрі.
export function isStrong(ev, filter = MIN_CONFIDENCE) {
  return (
    ev.direction != null &&
    Math.abs(ev.diff) >= SHOW_MIN &&
    ev.confidence >= Math.max(MIN_CONFIDENCE, filter)
  );
}

const REASON = {
  call: {
    ema50: 'Ціна вище EMA 50 — тренд вгору',
    sma50: 'Ціна вище SMA 50',
    ema9_21: 'EMA 9 перетнула EMA 21 вгору',
    macd: 'MACD: гістограма над нулем',
    adx: 'ADX: рух вгору має силу',
    rsi: 'RSI: покупці сильніші',
    sar: 'Ціна над Parabolic SAR',
    aroon: 'Aroon: нові максимуми',
    mom: 'Імпульс за 10 свічок вгору',
  },
  put: {
    ema50: 'Ціна нижче EMA 50 — тренд вниз',
    sma50: 'Ціна нижче SMA 50',
    ema9_21: 'EMA 9 перетнула EMA 21 вниз',
    macd: 'MACD: гістограма під нулем',
    adx: 'ADX: рух вниз має силу',
    rsi: 'RSI: продавці сильніші',
    sar: 'Ціна під Parabolic SAR',
    aroon: 'Aroon: нові мінімуми',
    mom: 'Імпульс за 10 свічок вниз',
  },
};

export function buildReasons(votes, ev) {
  const want = ev.direction === 'call' ? BUY : SELL;
  const agreeing = new Set(votes.filter((v) => v.vote === want).map((v) => v.key));
  const out = [
    `${ev.direction === 'call' ? ev.buy : ev.sell} з ${ev.total} індикаторів за ${ev.direction === 'call' ? 'ВГОРУ' : 'ВНИЗ'}`,
  ];
  for (const [key, text] of Object.entries(REASON[ev.direction])) {
    if (out.length >= 4) break;
    if (agreeing.has(key)) out.push(text);
  }
  return out;
}

// strategy: 'indicators' | 'level' | 'wedge'. Для патернів напрямок, впевненість,
// причини і геометрія (pattern) приходять із patterns.js, голоси — для «за / проти».
export function createSignal({ asset, votes, ev, price, now, strategy = 'indicators', direction, confidence, reasons, pattern = null }) {
  const dir = direction ?? ev.direction;
  const call = dir === 'call';
  return {
    id: `${asset.id}-${now}`,
    assetId: asset.id,
    symbol: asset.symbol,
    strategy,
    pattern,
    direction: dir,
    tf: TF_SECONDS,
    confidence: confidence ?? ev.confidence,
    agreement: call ? ev.buy : ev.sell,
    total: ev.total,
    maBuy: ev.maBuy,
    maSell: ev.maSell,
    techBuy: ev.techBuy,
    techSell: ev.techSell,
    trendScore: ev.trendScore,
    reasons: reasons ?? buildReasons(votes, ev),
    entry: price,
    createdAt: now,
    expiresAt: now + SIGNAL_MS,
    status: 'active',
    exit: null,
  };
}

// Рух у бік сигналу більше ніж на пів тіка — hit, протилежний — miss, інакше flat.
export function resolveSignal(signal, exit, digits) {
  const halfTick = 0.5 * 10 ** -digits;
  const move = (exit - signal.entry) * (signal.direction === 'call' ? 1 : -1);
  const status = move > halfTick ? 'hit' : move < -halfTick ? 'miss' : 'flat';
  return { ...signal, status, exit };
}

export const RESULT_TEXT = {
  hit: 'Ціна пішла куди сказано',
  miss: 'Не пішла',
  flat: 'Без змін',
};
