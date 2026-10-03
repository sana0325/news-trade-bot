// OTC-ціни Binarium — ті самі запити, що робить їхній термінал (assets/terminal/app/base/app-*.js):
//   GET https://api.binarium.com/api/v1/assets/{id}/candles?from=<ISO>&to=<ISO>&detalization=15s
//   GET https://api.binarium.com/api/v1/assets/{id}/quotes?from=<ISO>&to=<ISO>&detalization=1s
// Не кабінет і не ставки — лише ціни. Браузер не пустить напряму (CORS), тому у вебі запити йдуть
// через проксі /binarium (див. vite.config.js). Інший хост можна задати через VITE_BINARIUM_BASE.
import { bucketOf, CANDLE_MS } from './market.js';

export const POLL_MS = 2500;
export const CHUNK_MS = 8 * 60_000; // вікно довше ~10 хв API ріже — довгу історію беремо шматками
export const QUOTES_WINDOW_MS = 90_000;
const TIMEOUT_MS = 5000;
const STALE_MS = 3 * 60_000; // найсвіжіші дані старші — це вже не живі ціни

// В APK (Capacitor) запити йдуть нативно через CapacitorHttp, CORS не заважає — ходимо напряму.
const isNative = () => globalThis.Capacitor?.isNativePlatform?.() === true;
const BASE = (
  import.meta.env?.VITE_BINARIUM_BASE ?? (isNative() ? 'https://api.binarium.com' : '/binarium')
).replace(/\/$/, '');

// Помилка фіду з коротким поясненням для банера.
export class FeedError extends Error {}

// Як у терміналі: from/to — ISO-час, detalization — крок (15s — свічки по 15 с, 1s — котирування).
function rangeQuery(from, to, detalization) {
  const q = new URLSearchParams();
  q.append('from', new Date(from).toISOString());
  q.append('to', new Date(to).toISOString());
  q.append('detalization', detalization);
  return q.toString();
}

export const candlesUrl = (id, from, to) => `${BASE}/api/v1/assets/${id}/candles?${rangeQuery(from, to, '15s')}`;
export const quotesUrl = (id, from, to) => `${BASE}/api/v1/assets/${id}/quotes?${rangeQuery(from, to, '1s')}`;

// Відповідь — { data: [...] }. Поля рядків термінал не розбирає (їх читає бібліотека графіка),
// тож читаємо терпимо.
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
    // Час без поясу ('2026-10-03 07:40:00') — це UTC, а не місцевий час телефона.
    const iso = /^\d{4}-\d\d-\d\d[ T]\d\d:\d\d(:\d\d(\.\d+)?)?$/.test(t) ? `${t.replace(' ', 'T')}Z` : t;
    const p = Date.parse(iso);
    return Number.isFinite(p) ? p : null;
  }
  const n = num(t);
  if (n == null) return null;
  return n < 1e12 ? n * 1000 : n; // секунди → мс
}

const timeOf = (row) =>
  toMs(
    row.t ?? row.time ?? row.timestamp ?? row.from ?? row.createdAt ?? row.created_at ?? row.date ?? row.datetime ?? row.x,
  );

export function parseCandles(json) {
  const out = [];
  for (const r of listOf(json)) {
    const row = Array.isArray(r) ? { t: r[0], o: r[1], h: r[2], l: r[3], c: r[4] } : r;
    if (!row || typeof row !== 'object') continue;
    const t = timeOf(row);
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
    if (!row || typeof row !== 'object') continue;
    const value = num(row.value, row.price, row.quote, row.v, row.y);
    const t = timeOf(row) ?? 0;
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

// Що прийшло, коли формат не впізнали: ключі першого рядка — щоб було видно в банері.
function shapeOf(json) {
  const r = listOf(json)[0];
  if (r == null) return null;
  return Array.isArray(r) ? `масив з ${r.length}` : `поля ${Object.keys(r).slice(0, 8).join(', ')}`;
}

async function request(url, signal) {
  let res;
  try {
    res = await fetch(url, { signal, headers: { Accept: 'application/json' } });
  } catch {
    throw new FeedError('немає з’єднання');
  }
  if (!res.ok) throw new FeedError(`HTTP ${res.status}`);
  try {
    return await res.json();
  } catch {
    throw new FeedError('відповідь не JSON');
  }
}

async function getJson(url) {
  const ctl = new AbortController();
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      ctl.abort();
      reject(new FeedError('сервер не відповідає'));
    }, TIMEOUT_MS);
  });
  try {
    return await Promise.race([request(url, ctl.signal), timeout]);
  } finally {
    clearTimeout(timer);
  }
}

// Свічки за [from, to] шматками по 8 хв, від найсвіжіших. Свіжий шматок обов’язковий,
// а старшої історії сервер може й не дати — тоді беремо, що є.
async function fetchCandles(id, from, to) {
  const rows = [];
  let newest; // відповідь на найсвіжіший шматок — щоб показати формат, якщо його не впізнали
  for (let b = to; b > from; b -= CHUNK_MS) {
    let json;
    try {
      json = await getJson(candlesUrl(id, Math.max(from, b - CHUNK_MS), b));
    } catch (e) {
      if (b === to) throw e;
      break;
    }
    if (b === to) newest = json;
    const part = parseCandles(json);
    if (!part.length) break; // далі в минуле історії немає
    rows.push(...part);
  }
  rows.sort((x, y) => x.t - y.t);
  return { bars: toBars(rows), shape: rows.length ? null : shapeOf(newest) };
}

const reasonOf = (e) => (e instanceof FeedError ? e.message : 'помилка фіду');

// Свічки за [from, now] і останнє котирування. Коли не вийшло нічого — кидає FeedError
// з поясненням; коли вийшла лише частина — повертає її з приміткою (note) для банера.
export async function fetchFeed(id, from, now) {
  const [cr, qr] = await Promise.allSettled([
    fetchCandles(id, from, now),
    getJson(quotesUrl(id, now - QUOTES_WINDOW_MS, now)),
  ]);
  const bars = cr.status === 'fulfilled' ? cr.value.bars : [];
  const quote = qr.status === 'fulfilled' ? parseLastQuote(qr.value) : null;
  const notes = [];
  if (cr.status === 'rejected') notes.push(`свічки: ${reasonOf(cr.reason)}`);
  else if (cr.value.shape) notes.push(`свічки: не впізнав формат (${cr.value.shape})`);
  if (qr.status === 'rejected') notes.push(`котирування: ${reasonOf(qr.reason)}`);
  else if (!quote && shapeOf(qr.value)) notes.push(`котирування: не впізнав формат (${shapeOf(qr.value)})`);
  if (!bars.length && !quote) {
    // Обидва запити впали з однієї причини (401, немає мережі) — показуємо її один раз.
    const same = cr.status === 'rejected' && qr.status === 'rejected' && reasonOf(cr.reason) === reasonOf(qr.reason);
    throw new FeedError(same ? reasonOf(cr.reason) : notes.join('; ') || 'порожня відповідь');
  }
  // Час котирування може бути невідомим (0) — тоді перевіряємо лише за свічками.
  const newest = Math.max(quote?.t || 0, bars.length ? bars[bars.length - 1].t + CANDLE_MS : 0);
  if (newest && now - newest > STALE_MS) throw new FeedError('ціни не оновлюються');
  return { bars, quote, note: notes.join('; ') };
}
