// Свічкові патерни: відбиття від рівня і пробій клина.
// Працюють лише на закритих свічках — сформована свічка ще може передумати.

const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));

// Середній істинний діапазон — мірило «нормального» руху для допусків.
export function atr(candles, n = 14) {
  if (candles.length < n + 1) return null;
  let s = 0;
  for (let i = candles.length - n; i < candles.length; i++) {
    const c = candles[i];
    const pc = candles[i - 1].c;
    s += Math.max(c.h - c.l, Math.abs(c.h - pc), Math.abs(c.l - pc));
  }
  return s / n;
}

// Локальні максимуми / мінімуми: екстремум серед k свічок з кожного боку.
export function findPivots(candles, k = 3, from = 0, to = candles.length - 1) {
  const highs = [];
  const lows = [];
  for (let i = Math.max(from, k); i <= Math.min(to, candles.length - 1 - k); i++) {
    let isHigh = true;
    let isLow = true;
    for (let j = i - k; j <= i + k; j++) {
      if (j === i) continue;
      if (candles[j].h > candles[i].h) isHigh = false;
      if (candles[j].l < candles[i].l) isLow = false;
    }
    if (isHigh) highs.push({ i, p: candles[i].h });
    if (isLow) lows.push({ i, p: candles[i].l });
  }
  return { highs, lows };
}

// Групує точки, що лежать у межах tol, у рівні. Повертає { price, touches }.
export function clusterLevels(points, tol) {
  const sorted = [...points].sort((a, b) => a.p - b.p);
  const levels = [];
  let group = [];
  const flush = () => {
    if (group.length) {
      levels.push({ price: group.reduce((a, b) => a + b.p, 0) / group.length, touches: group.length });
    }
    group = [];
  };
  for (const pt of sorted) {
    if (group.length && pt.p - group[0].p > tol) flush();
    group.push(pt);
  }
  flush();
  return levels;
}

// Відбиття від рівня. Остання закрита свічка торкнулась рівня підтримки (опору),
// який ціна вже щонайменше двічі тримала, і закрилась від нього в протилежний бік.
export function detectLevelBounce(candles) {
  const n = candles.length;
  const a = atr(candles);
  if (!a || n < 40) return null;
  const last = candles[n - 1];
  const tol = 0.3 * a;
  const { highs, lows } = findPivots(candles, 3, n - 120, n - 2);

  const tryLevel = (direction, points) => {
    const levels = clusterLevels(points, tol).filter((l) => l.touches >= 2);
    const range = last.h - last.l || a;
    let best = null;
    for (const lv of levels) {
      const L = lv.price;
      const prev = candles.slice(n - 4, n - 1);
      const before = candles.slice(n - 8, n - 2);
      const avgBefore = before.reduce((s, c) => s + c.c, 0) / before.length;
      let wick;
      if (direction === 'call') {
        if (!(last.l <= L + tol && last.l >= L - 0.6 * a)) continue; // дотик знизу
        if (!(last.c > L + 0.1 * a && last.c > last.o)) continue; // закрилась вище, бичача
        if (prev.some((c) => c.c < L - 0.2 * a)) continue; // рівень не пробитий раніше
        if (!(avgBefore > L)) continue; // ціна підходила зверху
        wick = (Math.min(last.o, last.c) - last.l) / range;
      } else {
        if (!(last.h >= L - tol && last.h <= L + 0.6 * a)) continue;
        if (!(last.c < L - 0.1 * a && last.c < last.o)) continue;
        if (prev.some((c) => c.c > L + 0.2 * a)) continue;
        if (!(avgBefore < L)) continue;
        wick = (last.h - Math.max(last.o, last.c)) / range;
      }
      if (wick < 0.3) continue; // без тіні це не відбиття, а просто дотик
      if (!best || lv.touches > best.touches) best = { ...lv, wick };
    }
    return best && { strategy: 'level', direction, level: best.price, touches: best.touches, wick: best.wick, atr: a };
  };

  return tryLevel('call', lows) ?? tryLevel('put', highs);
}

function fitLine(points) {
  const m = points.length;
  const mx = points.reduce((s, p) => s + p.i, 0) / m;
  const my = points.reduce((s, p) => s + p.p, 0) / m;
  let num = 0;
  let den = 0;
  for (const p of points) {
    num += (p.i - mx) * (p.p - my);
    den += (p.i - mx) ** 2;
  }
  const slope = den === 0 ? 0 : num / den;
  return { slope, at: (i) => my + slope * (i - mx) };
}

// Пробій клина. За останні ~36 свічок максимуми й мінімуми лягають на дві лінії,
// що сходяться; остання закрита свічка закрилась за межею, а попередня — ще всередині.
export function detectWedgeBreakout(candles, window = 36) {
  const n = candles.length;
  const a = atr(candles);
  if (!a || n < window + 4) return null;
  const end = n - 2; // остання свічка всередині клина
  const start = end - window + 1;
  const { highs, lows } = findPivots(candles, 2, start, end);
  if (highs.length < 2 || lows.length < 2) return null;
  const spanH = highs[highs.length - 1].i - highs[0].i;
  const spanL = lows[lows.length - 1].i - lows[0].i;
  if (spanH < 12 || spanL < 12) return null;

  const upper = fitLine(highs);
  const lower = fitLine(lows);
  const w0 = upper.at(start) - lower.at(start);
  const w1 = upper.at(end) - lower.at(end);
  if (!(w0 > 2 * a && w1 > 0 && w1 < 0.65 * w0)) return null; // має звужуватись

  // Свічки клина мають лишатися між лініями (допускаємо дві-три шпильки).
  let outside = 0;
  for (let i = start; i <= end; i++) {
    const c = candles[i].c;
    if (c > upper.at(i) + 0.3 * a || c < lower.at(i) - 0.3 * a) outside++;
  }
  if (outside > 3) return null;

  const last = candles[n - 1];
  const prev = candles[end];
  const up = upper.at(n - 1);
  const lo = lower.at(n - 1);
  if (prev.c > upper.at(end) || prev.c < lower.at(end)) return null;

  let direction = null;
  if (last.c > up + 0.15 * a) direction = 'call';
  else if (last.c < lo - 0.15 * a) direction = 'put';
  if (!direction) return null;

  const kind =
    upper.slope > 0 && lower.slope > 0 ? 'rising' : upper.slope < 0 && lower.slope < 0 ? 'falling' : 'triangle';
  // Класика: спадний клин пробивають угору, висхідний — униз.
  const classic = (kind === 'falling' && direction === 'call') || (kind === 'rising' && direction === 'put');
  const t = (i) => candles[i].t;
  return {
    strategy: 'wedge',
    direction,
    kind,
    classic,
    squeeze: 1 - w1 / w0,
    impulse: (last.h - last.l) / a,
    atr: a,
    lines: {
      upper: { t0: t(start), p0: upper.at(start), t1: last.t, p1: up },
      lower: { t0: t(start), p0: lower.at(start), t1: last.t, p1: lo },
    },
  };
}

// Впевненість патерну: база + підтвердження. Стеля 90, як і в індикаторів.
// ev — підсумок голосування індикаторів на тій самій закритій свічці.
export function patternConfidence(p, ev) {
  const call = p.direction === 'call';
  const oscFor = call ? ev.techBuy - ev.techSell : ev.techSell - ev.techBuy;
  const diff = call ? ev.buy - ev.sell : ev.sell - ev.buy;
  let c;
  if (p.strategy === 'level') {
    c = 70 + 4 * Math.min(p.touches - 2, 3);
    if (p.wick >= 0.5) c += 3; // дуже довга тінь — різко відкинули від рівня
    if (oscFor >= 3) c += 4;
  } else {
    c = 72;
    if (p.classic) c += 6;
    if (p.impulse >= 1.2) c += 4; // пробій сильною свічкою
    if (diff >= 8) c += 4;
  }
  return clamp(Math.round(c), 0, 90);
}

const fmt = (x, d) => x.toFixed(d);

export function patternReasons(p, ev, digits) {
  const call = p.direction === 'call';
  if (p.strategy === 'level') {
    const out = [
      call
        ? `Відбиття від підтримки ${fmt(p.level, digits)} (${p.touches} дотики)`
        : `Відбиття від опору ${fmt(p.level, digits)} (${p.touches} дотики)`,
      call ? 'Свічка закрилась над рівнем' : 'Свічка закрилась під рівнем',
    ];
    out.push(call ? 'Нижня тінь — ціну відкинули від рівня' : 'Верхня тінь — ціну відкинули від рівня');
    const oscFor = call ? ev.techBuy - ev.techSell : ev.techSell - ev.techBuy;
    if (oscFor >= 3) out.push(`Осцилятори за ${call ? 'ВГОРУ' : 'ВНИЗ'}`);
    return out;
  }
  const name = { rising: 'висхідного клина', falling: 'спадного клина', triangle: 'звуження (трикутника)' }[p.kind];
  const out = [
    `Пробій ${name} ${call ? 'вгору' : 'вниз'}`,
    call ? 'Закриття над верхньою межею' : 'Закриття під нижньою межею',
    `Діапазон звузився на ${Math.round(p.squeeze * 100)}%`,
  ];
  if (p.impulse >= 1.2) out.push('Пробій сильною свічкою');
  return out;
}

export const STRATEGY_LABEL = {
  indicators: 'Індикатори',
  level: 'Відбиття від рівня',
  wedge: 'Пробій клина',
};
