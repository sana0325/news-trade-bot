// 25 індикаторів, кожен голосує buy / sell / neutral.
// Вхід: масив свічок { t, o, h, l, c }, від старої до нової.

export const BUY = 'buy';
export const SELL = 'sell';
export const NEUTRAL = 'neutral';

const sign = (x) => (x > 0 ? BUY : x < 0 ? SELL : NEUTRAL);

export function sma(values, n) {
  if (values.length < n) return null;
  let s = 0;
  for (let i = values.length - n; i < values.length; i++) s += values[i];
  return s / n;
}

// Повна серія EMA; до n-1 — null, старт від SMA перших n значень.
export function emaSeries(values, n) {
  const out = new Array(values.length).fill(null);
  if (values.length < n) return out;
  const k = 2 / (n + 1);
  let prev = 0;
  for (let i = 0; i < n; i++) prev += values[i];
  prev /= n;
  out[n - 1] = prev;
  for (let i = n; i < values.length; i++) {
    prev = values[i] * k + prev * (1 - k);
    out[i] = prev;
  }
  return out;
}

export function ema(values, n) {
  const s = emaSeries(values, n);
  return s[s.length - 1] ?? null;
}

export function macd(closes, fast = 12, slow = 26, sig = 9) {
  const f = emaSeries(closes, fast);
  const s = emaSeries(closes, slow);
  const line = [];
  for (let i = 0; i < closes.length; i++) {
    if (f[i] != null && s[i] != null) line.push(f[i] - s[i]);
  }
  if (line.length < sig) return null;
  const signal = ema(line, sig);
  const m = line[line.length - 1];
  return { macd: m, signal, hist: m - signal };
}

export function rsi(closes, n = 14) {
  if (closes.length < n + 1) return null;
  let gain = 0;
  let loss = 0;
  for (let i = 1; i <= n; i++) {
    const d = closes[i] - closes[i - 1];
    if (d > 0) gain += d;
    else loss -= d;
  }
  gain /= n;
  loss /= n;
  for (let i = n + 1; i < closes.length; i++) {
    const d = closes[i] - closes[i - 1];
    gain = (gain * (n - 1) + Math.max(d, 0)) / n;
    loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
  }
  if (loss === 0) return gain === 0 ? 50 : 100;
  return 100 - 100 / (1 + gain / loss);
}

function stochKAt(candles, end, n) {
  let hh = -Infinity;
  let ll = Infinity;
  for (let i = end - n + 1; i <= end; i++) {
    hh = Math.max(hh, candles[i].h);
    ll = Math.min(ll, candles[i].l);
  }
  return hh === ll ? 50 : ((candles[end].c - ll) / (hh - ll)) * 100;
}

export function stochastic(candles, n = 14, smooth = 3) {
  if (candles.length < n + smooth - 1) return null;
  const last = candles.length - 1;
  const ks = [];
  for (let i = 0; i < smooth; i++) ks.push(stochKAt(candles, last - i, n));
  return { k: ks[0], d: ks.reduce((a, b) => a + b, 0) / smooth };
}

export function williamsR(candles, n = 14) {
  if (candles.length < n) return null;
  return stochKAt(candles, candles.length - 1, n) - 100;
}

export function cci(candles, n = 20) {
  if (candles.length < n) return null;
  const tp = candles.slice(-n).map((c) => (c.h + c.l + c.c) / 3);
  const mean = tp.reduce((a, b) => a + b, 0) / n;
  const dev = tp.reduce((a, b) => a + Math.abs(b - mean), 0) / n;
  if (dev === 0) return 0;
  return (tp[n - 1] - mean) / (0.015 * dev);
}

export function momentum(closes, n = 10) {
  if (closes.length < n + 1) return null;
  return closes[closes.length - 1] - closes[closes.length - 1 - n];
}

export function roc(closes, n = 12) {
  if (closes.length < n + 1) return null;
  const past = closes[closes.length - 1 - n];
  return past === 0 ? 0 : ((closes[closes.length - 1] - past) / past) * 100;
}

// Wilder ADX з +DI / −DI.
export function adx(candles, n = 14) {
  if (candles.length < 2 * n + 1) return null;
  const tr = [];
  const pdm = [];
  const mdm = [];
  for (let i = 1; i < candles.length; i++) {
    const c = candles[i];
    const p = candles[i - 1];
    const up = c.h - p.h;
    const down = p.l - c.l;
    pdm.push(up > down && up > 0 ? up : 0);
    mdm.push(down > up && down > 0 ? down : 0);
    tr.push(Math.max(c.h - c.l, Math.abs(c.h - p.c), Math.abs(c.l - p.c)));
  }
  let str = 0;
  let sp = 0;
  let sm = 0;
  for (let i = 0; i < n; i++) {
    str += tr[i];
    sp += pdm[i];
    sm += mdm[i];
  }
  const dxs = [];
  let pdi = 0;
  let mdi = 0;
  for (let i = n - 1; i < tr.length; i++) {
    if (i >= n) {
      str = str - str / n + tr[i];
      sp = sp - sp / n + pdm[i];
      sm = sm - sm / n + mdm[i];
    }
    pdi = str === 0 ? 0 : (100 * sp) / str;
    mdi = str === 0 ? 0 : (100 * sm) / str;
    const sum = pdi + mdi;
    dxs.push(sum === 0 ? 0 : (100 * Math.abs(pdi - mdi)) / sum);
  }
  if (dxs.length < n) return null;
  let a = 0;
  for (let i = 0; i < n; i++) a += dxs[i];
  a /= n;
  for (let i = n; i < dxs.length; i++) a = (a * (n - 1) + dxs[i]) / n;
  return { adx: a, pdi, mdi };
}

export function parabolicSar(candles, step = 0.02, max = 0.2) {
  if (candles.length < 3) return null;
  let up = candles[1].c >= candles[0].c;
  let sar = up ? candles[0].l : candles[0].h;
  let ep = up ? candles[1].h : candles[1].l;
  let af = step;
  for (let i = 2; i < candles.length; i++) {
    const c = candles[i];
    const p1 = candles[i - 1];
    const p2 = candles[i - 2];
    sar = sar + af * (ep - sar);
    if (up) {
      sar = Math.min(sar, p1.l, p2.l);
      if (c.l < sar) {
        up = false;
        sar = ep;
        ep = c.l;
        af = step;
      } else if (c.h > ep) {
        ep = c.h;
        af = Math.min(af + step, max);
      }
    } else {
      sar = Math.max(sar, p1.h, p2.h);
      if (c.h > sar) {
        up = true;
        sar = ep;
        ep = c.h;
        af = step;
      } else if (c.l < ep) {
        ep = c.l;
        af = Math.min(af + step, max);
      }
    }
  }
  return sar;
}

export function ultimateOscillator(candles, a = 7, b = 14, c = 28) {
  if (candles.length < c + 1) return null;
  const bp = [];
  const tr = [];
  for (let i = 1; i < candles.length; i++) {
    const k = candles[i];
    const pc = candles[i - 1].c;
    const lo = Math.min(k.l, pc);
    bp.push(k.c - lo);
    tr.push(Math.max(k.h, pc) - lo);
  }
  const avg = (n) => {
    let s1 = 0;
    let s2 = 0;
    for (let i = bp.length - n; i < bp.length; i++) {
      s1 += bp[i];
      s2 += tr[i];
    }
    return s2 === 0 ? 0.5 : s1 / s2;
  };
  return (100 * (4 * avg(a) + 2 * avg(b) + avg(c))) / 7;
}

export function aroon(candles, n = 14) {
  if (candles.length < n + 1) return null;
  const w = candles.slice(-(n + 1));
  let hi = 0;
  let lo = 0;
  for (let i = 1; i < w.length; i++) {
    if (w[i].h >= w[hi].h) hi = i;
    if (w[i].l <= w[lo].l) lo = i;
  }
  return { up: (100 * hi) / n, down: (100 * lo) / n };
}

export function bollinger(closes, n = 20, k = 2) {
  if (closes.length < n) return null;
  const w = closes.slice(-n);
  const mid = w.reduce((a, b) => a + b, 0) / n;
  const sd = Math.sqrt(w.reduce((a, b) => a + (b - mid) ** 2, 0) / n);
  const upper = mid + k * sd;
  const lower = mid - k * sd;
  const c = closes[closes.length - 1];
  const pb = upper === lower ? 0.5 : (c - lower) / (upper - lower);
  return { mid, upper, lower, pb };
}

// Awesome Oscillator: SMA 5 − SMA 34 середньої ціни (h+l)/2.
export function awesome(candles) {
  if (candles.length < 34) return null;
  const med = candles.map((c) => (c.h + c.l) / 2);
  return sma(med, 5) - sma(med, 34);
}

const fmt = (x, d = 1) => (x == null ? '—' : x.toFixed(d));
const above = (price, ref) => (ref == null ? NEUTRAL : sign(price - ref));

// Усі голоси. Кожен: { key, group: 'ma'|'osc', name, value, vote }.
export function computeVotes(candles, digits = 5) {
  const closes = candles.map((c) => c.c);
  const price = closes[closes.length - 1];
  const pf = (x) => (x == null ? '—' : x.toFixed(digits));
  const votes = [];
  const add = (group, key, name, value, vote) =>
    votes.push({ group, key, name, value, vote });

  for (const n of [5, 10, 20, 50]) {
    const v = sma(closes, n);
    add('ma', `sma${n}`, `SMA ${n}`, pf(v), above(price, v));
  }
  for (const n of [5, 10, 20, 50]) {
    const v = ema(closes, n);
    add('ma', `ema${n}`, `EMA ${n}`, pf(v), above(price, v));
  }
  const e9 = ema(closes, 9);
  const e21 = ema(closes, 21);
  add('ma', 'ema9_21', 'EMA 9 / 21', e9 == null || e21 == null ? '—' : e9 > e21 ? '9 > 21' : e9 < e21 ? '9 < 21' : '9 = 21',
    e9 == null || e21 == null ? NEUTRAL : sign(e9 - e21));
  const m = macd(closes);
  add('ma', 'macd', 'MACD 12/26/9', m ? `гіст. ${m.hist > 0 ? '+' : m.hist < 0 ? '−' : ''}` : '—', m ? sign(m.hist) : NEUTRAL);
  add('ma', 'macd_sig', 'MACD сигнальна', m ? (m.macd > m.signal ? 'лінія вище' : m.macd < m.signal ? 'лінія нижче' : 'рівно') : '—',
    m ? sign(m.macd - m.signal) : NEUTRAL);
  const d = adx(candles);
  add('ma', 'adx', 'ADX / DI 14', d ? `ADX ${fmt(d.adx, 0)}` : '—',
    d && d.adx > 12 ? sign(d.pdi - d.mdi) : NEUTRAL);

  const r = rsi(closes);
  add('osc', 'rsi', 'RSI 14', fmt(r), r == null ? NEUTRAL : r > 52 ? BUY : r < 48 ? SELL : NEUTRAL);
  const st = stochastic(candles);
  const band = (x) => (x == null ? NEUTRAL : x > 55 ? BUY : x < 45 ? SELL : NEUTRAL);
  add('osc', 'stoch_k', 'Stoch %K 14', fmt(st?.k), band(st?.k));
  add('osc', 'stoch_d', 'Stoch %D 3', fmt(st?.d), band(st?.d));
  const wr = williamsR(candles);
  add('osc', 'wr', 'Williams %R 14', fmt(wr), wr == null ? NEUTRAL : wr > -45 ? BUY : wr < -55 ? SELL : NEUTRAL);
  const cc = cci(candles);
  add('osc', 'cci', 'CCI 20', fmt(cc, 0), cc == null ? NEUTRAL : sign(cc));
  const mo = momentum(closes);
  add('osc', 'mom', 'Momentum 10', mo == null ? '—' : mo > 0 ? '+' : mo < 0 ? '−' : '0', mo == null ? NEUTRAL : sign(mo));
  const sar = parabolicSar(candles);
  add('osc', 'sar', 'Parabolic SAR', pf(sar), above(price, sar));
  const uo = ultimateOscillator(candles);
  add('osc', 'uo', 'Ultimate Osc.', fmt(uo), uo == null ? NEUTRAL : sign(uo - 50));
  const rc = roc(closes);
  add('osc', 'roc', 'ROC 12', rc == null ? '—' : `${fmt(rc, 3)}%`, rc == null ? NEUTRAL : sign(rc));
  const ar = aroon(candles);
  add('osc', 'aroon', 'Aroon 14', ar ? `${fmt(ar.up, 0)} / ${fmt(ar.down, 0)}` : '—', ar ? sign(ar.up - ar.down) : NEUTRAL);
  const bb = bollinger(closes);
  add('osc', 'bb', 'Bollinger 20', bb ? (price <= bb.lower ? 'на нижній' : price >= bb.upper ? 'на верхній' : 'всередині') : '—',
    !bb ? NEUTRAL : price <= bb.lower ? BUY : price >= bb.upper ? SELL : NEUTRAL);
  add('osc', 'pb', 'Bollinger %B', bb ? fmt(bb.pb, 2) : '—', bb ? sign(bb.pb - 0.5) : NEUTRAL);
  const ao = awesome(candles);
  add('osc', 'ao', 'Awesome Osc.', ao == null ? '—' : ao > 0 ? '+' : ao < 0 ? '−' : '0', ao == null ? NEUTRAL : sign(ao));

  return votes;
}
