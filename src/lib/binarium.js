// Публічний графік Binarium для OTC. Не кабінет і не ставки — лише ціни.
// Браузер не пустить напряму (CORS), тому за замовчуванням іде через проксі /binarium
// (див. vite.config.js). Інший хост можна задати через VITE_BINARIUM_BASE.
import { bucketOf } from './market.js';

export const POLL_MS = 2500;
export const CANDLES_WINDOW_MS = 8 * 60_000; // вікно довше ~10 хв API ріже
export const QUOTES_WINDOW_MS = 90_000;
const TIMEOUT_MS = 2000;

// В APK (Capacitor) запити йдуть нативно через CapacitorHttp, CORS не заважає — ходимо напряму.
const isNative = () => globalThis.Capacitor?.isNativePlatform?.() === true;
const BASE = (
  import.meta.env?.VITE_BINARIUM_BASE ?? (isNative() ? 'https://binarium.com' : '/binarium')
).replace(/\/$/, '');

export function candlesUrl(id, now) {
  const from = Math.floor((now - CANDLES_WINDOW_MS) / 1000);
  const to = Math.floor(now / 1000);
  return `${BASE}/api/v1/assets/${id}/candles?from=${from}&to=${to}`;
}

export function quotesUrl(id, now) {
  const from = Math.floor((now - QUOTES_WINDOW_MS) / 1000);
  const to = Math.floor(now / 1000);
  return `${BASE}/api/v1/assets/${id}/quotes?from=${from}&to=${to}`;
}

// Формат відповіді точно не задокументований — читаємо терпимо.
const listOf = (json) => {
  if (Array.isArray(json)) return json;
  for (const k of ['data', 'candles', 'quotes', 'items', 'result']) {
    if (Array.isArray(json?.[k])) return json[k];
    if (Array.isArray(json?.data?.[k])) return json.data[k];
  }
  return [];
};

const num = (...xs) => {
  for (const x of xs) {
    const n = typeof x === 'string' ? parseFloat(x) : x;
    if (Number.isFinite(n)) return n;
  }
  return null;
};

export function toMs(t) {
  if (typeof t === 'string' && !/^\d+(\.\d+)?$/.test(t)) {
    const p = Date.parse(t);
    return Number.isFinite(p) ? p : null;
  }
  const n = num(t);
  if (n == null) return null;
  return n < 1e12 ? n * 1000 : n; // секунди → мс
}

export function parseCandles(json) {
  const out = [];
  for (const r of listOf(json)) {
    const row = Array.isArray(r) ? { t: r[0], o: r[1], h: r[2], l: r[3], c: r[4] } : r;
    const t = toMs(row.t ?? row.time ?? row.timestamp ?? row.from ?? row.created_at ?? row.date);
    const o = num(row.o, row.open);
    const h = num(row.h, row.high, row.max);
    const l = num(row.l, row.low, row.min);
    const c = num(row.c, row.close);
    if (t == null || o == null || c == null) continue;
    out.push({ t, o, h: h ?? Math.max(o, c), l: l ?? Math.min(o, c), c });
  }
  return out.sort((a, b) => a.t - b.t);
}

export function parseLastQuote(json) {
  let best = null;
  for (const r of listOf(json)) {
    const row = Array.isArray(r) ? { t: r[0], value: r[1] } : r;
    const value = num(row.value, row.price, row.quote, row.v);
    const t = toMs(row.t ?? row.time ?? row.timestamp ?? row.created_at ?? row.date) ?? 0;
    if (value == null) continue;
    if (!best || t >= best.t) best = { t, value };
  }
  return best;
}

// Збирає свічки фіду в бари по 15 секунд.
export function toBars(candles) {
  const bars = [];
  for (const k of candles) {
    const b = bucketOf(k.t);
    const last = bars[bars.length - 1];
    if (last && last.t === b) {
      last.h = Math.max(last.h, k.h);
      last.l = Math.min(last.l, k.l);
      last.c = k.c;
    } else bars.push({ t: b, o: k.o, h: k.h, l: k.l, c: k.c });
  }
  return bars;
}

async function getJson(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, { signal: ctl.signal, headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

// Один запит свічок + котирувань. Помилки не кидає — повертає null.
export async function fetchFeed(binariumId, now = Date.now()) {
  try {
    const [cj, qj] = await Promise.all([
      getJson(candlesUrl(binariumId, now)),
      getJson(quotesUrl(binariumId, now)),
    ]);
    const bars = toBars(parseCandles(cj));
    const quote = parseLastQuote(qj);
    if (!bars.length && !quote) return null;
    return { bars, quote };
  } catch {
    return null;
  }
}
