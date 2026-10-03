// OTC-ціни Binarium — ті самі запити, що робить їхній термінал (assets/terminal/app/base/app-*.js):
//   GET https://api.binarium.com/api/v1/assets/{id}/candles?from=<ISO>&to=<ISO>&detalization=15s
//   GET https://api.binarium.com/api/v1/assets/{id}/quotes?from=<ISO>&to=<ISO>&detalization=1s
// Не кабінет і не ставки — лише ціни. Браузер не пустить напряму (CORS), тому у вебі запити йдуть
// через проксі /binarium (див. vite.config.js). Інший хост можна задати через VITE_BINARIUM_BASE.
import { bucketOf, CANDLE_MS } from './market.js';
import { otcSymbol, DEFAULT_OTC } from './assets.js';

export const POLL_MS = 2500;
export const CHUNK_MS = 8 * 60_000; // вікно довше ~10 хв API ріже — довгу історію беремо шматками
export const QUOTES_WINDOW_MS = 90_000;
const TIMEOUT_MS = 5000;
const STALE_MS = 3 * 60_000; // найсвіжіші дані старші — це вже не живі ціни

// В APK (Capacitor) запити йдуть нативно через CapacitorHttp, CORS не заважає — ходимо напряму.
const isNative = () => globalThis.Capacitor?.isNativePlatform?.() === true;
// У Node (скрипт збору статистики) хост задає змінна BINARIUM_BASE.
const BASE = (
  import.meta.env?.VITE_BINARIUM_BASE ??
  globalThis.process?.env?.BINARIUM_BASE ??
  (isNative() ? 'https://api.binarium.com' : '/binarium')
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
// а старшої історії сервер може й не дати — тоді беремо, що є. Повертає відповіді сервера
// (від свіжих до старих) — так само їх віддає й нативна служба.
async function fetchCandleChunks(id, from, to) {
  const chunks = [];
  for (let b = to; b > from; b -= CHUNK_MS) {
    let json;
    try {
      json = await getJson(candlesUrl(id, Math.max(from, b - CHUNK_MS), b));
    } catch (e) {
      if (b === to) throw e;
      break;
    }
    chunks.push(json);
    if (!listOf(json).length) break; // далі в минуле історії немає
  }
  return chunks;
}

const reasonOf = (e) => (e instanceof FeedError ? e.message : 'помилка фіду');

// Відповіді сервера → { bars, quote, note }. Спільне для браузера і нативної служби.
// Коли не вийшло нічого — кидає FeedError з поясненням; коли лише частина — повертає її
// з приміткою (note) для банера.
export function buildFeed({ candles = [], candleError = null, quotes = null, quoteError = null }, now) {
  const rows = candles.flatMap((json) => parseCandles(json));
  rows.sort((x, y) => x.t - y.t);
  const bars = toBars(rows);
  const quote = quoteError ? null : parseLastQuote(quotes);
  const notes = [];
  if (candleError) notes.push(`свічки: ${candleError}`);
  else if (!bars.length && shapeOf(candles[0])) notes.push(`свічки: не впізнав формат (${shapeOf(candles[0])})`);
  if (quoteError) notes.push(`котирування: ${quoteError}`);
  else if (!quote && shapeOf(quotes)) notes.push(`котирування: не впізнав формат (${shapeOf(quotes)})`);
  if (!bars.length && !quote) {
    // Обидва запити впали з однієї причини (401, немає мережі) — показуємо її один раз.
    throw new FeedError(candleError && candleError === quoteError ? candleError : notes.join('; ') || 'порожня відповідь');
  }
  // Час котирування може бути невідомим (0) — тоді перевіряємо лише за свічками.
  const newest = Math.max(quote?.t || 0, bars.length ? bars[bars.length - 1].t + CANDLE_MS : 0);
  if (newest && now - newest > STALE_MS) throw new FeedError('ціни не оновлюються');
  return { bars, quote, note: notes.join('; ') };
}

// Свічки за [from, now] і останнє котирування — запитами з браузера.
export async function fetchFeed(id, from, now) {
  const [cr, qr] = await Promise.allSettled([
    fetchCandleChunks(id, from, now),
    getJson(quotesUrl(id, now - QUOTES_WINDOW_MS, now)),
  ]);
  return buildFeed(
    {
      candles: cr.status === 'fulfilled' ? cr.value : [],
      candleError: cr.status === 'rejected' ? reasonOf(cr.reason) : null,
      quotes: qr.status === 'fulfilled' ? qr.value : null,
      quoteError: qr.status === 'rejected' ? reasonOf(qr.reason) : null,
    },
    now,
  );
}

// Коли брати всю історію заново, а коли лише нове. Те саме правило — у нативній службі.
export const FULL_HISTORY_MS = 128 * CANDLE_MS; // 32 хв — уся історія рушія
export const FULL_AFTER_MS = 30_000; // так давно не було відповіді — беремо історію заново
export const OVERLAP_MS = 30_000; // нове — з запасом на останні свічки

export function feedRange(okTo, now) {
  const full = okTo == null || now - okTo > FULL_AFTER_MS;
  return { full, from: full ? now - FULL_HISTORY_MS : okTo - OVERLAP_MS };
}

// Опитувач Binarium у браузері: раз на 2.5 с по кожній обраній OTC-парі. В APK замість
// нього працює нативна служба (native.js) — з тим самим інтерфейсом.
export class BinariumPoller {
  constructor({ onData, onError, now = Date.now, fetchImpl = fetchFeed }) {
    this.onData = onData;
    this.onError = onError;
    this.now = now;
    this.fetchImpl = fetchImpl;
    this.ids = [];
    this.okTo = new Map();
    this.busy = new Set();
    this.timer = null;
  }

  setIds(ids) {
    this.ids = [...ids];
  }

  start() {
    if (this.timer) return;
    this.timer = setInterval(() => this.poll(), POLL_MS);
    this.poll();
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
  }

  // Наступне опитування — з усією історією (після повернення в застосунок або дірки).
  refresh(id) {
    if (id == null) this.okTo.clear();
    else this.okTo.delete(id);
  }

  poll() {
    const now = this.now();
    for (const id of this.ids) {
      if (this.busy.has(id)) continue;
      this.busy.add(id);
      const { full, from } = feedRange(this.okTo.get(id), now);
      this.fetchImpl(id, from, now)
        .then((data) => {
          this.okTo.set(id, now);
          this.onData(id, data, full);
        })
        .catch((e) => this.onError(id, reasonOf(e)))
        .finally(() => this.busy.delete(id));
    }
  }
}

// ---------- думка більшості ----------

// Частка трейдерів, що ставлять ВГОРУ (0…1). Публічний запит терміналу:
// GET /rpc/v1.Opinion.GetRatio?asset=43 → { data: { value: 0.742 }, error: null }.
export const OPINION_MS = 10_000;
export const opinionUrl = (id) => `${BASE}/rpc/v1.Opinion.GetRatio?asset=${id}`;

export async function fetchOpinion(id) {
  const json = await getJson(opinionUrl(id));
  const v = num(json?.data?.value, json?.value);
  if (v == null || v < 0 || v > 1) throw new FeedError(json?.error?.message || 'немає думки більшості');
  return v;
}

// ---------- які OTC-пари є ----------

// Список активів, як у терміналі: сторінками по 250. Беремо ті, що з позначкою OTC.
export async function fetchOtcCatalog() {
  const out = [];
  for (let offset = 0; offset < 2000; offset += 250) {
    const q = new URLSearchParams();
    q.append('pagination[limit]', '250');
    q.append('pagination[offset]', String(offset));
    const page = listOf(await getJson(`${BASE}/api/v1/assets?${q.toString()}`));
    for (const a of page) {
      const id = num(a?.id);
      const name = typeof a?.name === 'string' ? a.name : '';
      if (Number.isInteger(id) && /\bOTC\b/i.test(name)) out.push({ binariumId: id, name });
    }
    if (page.length < 250) break;
  }
  return out;
}

// Скільки знаків після коми — за самими котируваннями.
export function digitsOf(values) {
  let d = 0;
  for (const v of values) {
    const m = String(v).match(/\.(\d+)$/);
    if (m) d = Math.max(d, m[1].length);
  }
  return Math.min(Math.max(d, 2), 6);
}

// Перевірка однієї пари: свіжі котирування є — пара працює; знаки — з цих котирувань.
async function probeOne(id, now) {
  const json = await getJson(quotesUrl(id, now - QUOTES_WINDOW_MS, now));
  const quote = parseLastQuote(json);
  if (!quote) return null;
  if (quote.t && now - quote.t > STALE_MS) return null;
  const values = listOf(json)
    .map((r) => (Array.isArray(r) ? r[1] : (r?.value ?? r?.price ?? r?.quote)))
    .filter((v) => v != null);
  return { digits: digitsOf(values.length ? values : [quote.value]) };
}

// Кнопка «Перевірити»: які OTC-пари Binarium зараз віддає з цінами.
// candidates — [{ binariumId, symbol }]; якщо список активів не прийшов — перевіряємо відомі.
export async function probeOtc({
  onProgress,
  now = Date.now,
  catalog = fetchOtcCatalog,
  fallback = DEFAULT_OTC,
} = {}) {
  let candidates;
  let error = '';
  try {
    candidates = (await catalog()).map((a) => ({ binariumId: a.binariumId, symbol: otcSymbol(a.name) }));
    if (!candidates.length) throw new FeedError('у списку немає OTC');
  } catch (e) {
    error = `список активів: ${reasonOf(e)}, перевіряю відомі пари`;
    candidates = fallback;
  }
  const ok = [];
  const fail = [];
  let done = 0;
  const queue = [...candidates];
  const worker = async () => {
    while (queue.length) {
      const c = queue.shift();
      try {
        const r = await probeOne(c.binariumId, now());
        // Нулі в кінці ціни не пишуться (1.17120 → 1.1712): відомі знаки не зменшуємо.
        if (r) ok.push({ binariumId: c.binariumId, symbol: c.symbol, digits: Math.max(r.digits, c.digits ?? 0) });
        else fail.push(c.symbol);
      } catch {
        fail.push(c.symbol);
      }
      onProgress?.(++done, candidates.length);
    }
  };
  await Promise.all([worker(), worker(), worker()]);
  const order = (x) => candidates.findIndex((c) => c.binariumId === x.binariumId);
  ok.sort((a, b) => order(a) - order(b));
  return { ok, fail, error };
}
