// Справжні ціни спот-пар із Twelve Data через WebSocket.
// Ключі вводяться в застосунку і зберігаються лише на пристрої — у коді їх немає.
// Працює один ключ за раз; наступний береться, лише коли поточний відмовив.
const WS_URL = 'wss://ws.twelvedata.com/v1/quotes/price';
const HEARTBEAT_MS = 10_000;
const RETRY_MIN_MS = 2_000;
const RETRY_MAX_MS = 60_000;

// status: 'nokey' | 'connecting' | 'live' | 'error'
export class TwelveDataFeed {
  constructor({ symbols, onPrice, onStatus, onUnavailable, WebSocketImpl = globalThis.WebSocket }) {
    this.symbols = symbols;
    this.onPrice = onPrice;
    this.onStatus = onStatus;
    this.onUnavailable = onUnavailable;
    this.note = ''; // напр. «тариф не дає: USD/JPY», коли решта пар працює
    this.WS = WebSocketImpl;
    this.keys = [];
    this.keyIndex = 0;
    this.ws = null;
    this.retryMs = RETRY_MIN_MS;
    this.wanted = false;
  }

  // Інший набір пар — перепідключаємось із новою підпискою.
  setSymbols(symbols) {
    this.symbols = symbols;
    if (this.wanted) this.connect();
  }

  setStatus(status, message = '') {
    this.status = status;
    this.onStatus?.(status, message);
  }

  // Статус з номером ключа і приміткою про недоступні пари.
  info(...parts) {
    return [...parts, this.keyLabel(), this.note].filter(Boolean).join(' · ');
  }

  setUnavailable(symbols) {
    this.note = symbols.length ? `тариф не дає: ${symbols.join(', ')}` : '';
    this.onUnavailable?.(symbols);
  }

  get key() {
    return this.keys[this.keyIndex] ?? '';
  }

  // Один або кілька ключів через пробіл, кому чи з нового рядка.
  setKey(text) {
    this.keys = parseKeys(text);
    this.keyIndex = 0;
    if (this.wanted) this.connect();
  }

  keyLabel() {
    return this.keys.length > 1 ? `ключ ${this.keyIndex + 1} з ${this.keys.length}` : '';
  }

  // Поточний ключ відмовив — пробуємо наступний; після останнього знову перший, але з паузою.
  failKey(message) {
    const last = this.keyIndex >= this.keys.length - 1;
    this.setStatus('error', [this.keyLabel(), message].filter(Boolean).join(' · '));
    this.close();
    if (!this.wanted) return;
    if (last) {
      this.keyIndex = 0;
      this.retry = setTimeout(() => this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, RETRY_MAX_MS);
    } else {
      this.keyIndex++;
      this.connect();
    }
  }

  start() {
    this.wanted = true;
    this.connect();
  }

  stop() {
    this.wanted = false;
    this.close();
  }

  close() {
    clearInterval(this.heartbeat);
    clearTimeout(this.retry);
    if (this.ws) {
      this.ws.onclose = null;
      this.ws.close();
      this.ws = null;
    }
  }

  connect() {
    this.close();
    if (!this.key) return this.setStatus('nokey');
    if (!this.WS) return this.setStatus('error', 'WebSocket недоступний');
    this.setUnavailable([]);
    this.setStatus('connecting', this.info());
    const ws = new this.WS(`${WS_URL}?apikey=${encodeURIComponent(this.key)}`);
    this.ws = ws;
    ws.onopen = () => {
      ws.send(JSON.stringify({ action: 'subscribe', params: { symbols: this.symbols.join(',') } }));
      this.heartbeat = setInterval(() => ws.send(JSON.stringify({ action: 'heartbeat' })), HEARTBEAT_MS);
    };
    ws.onmessage = (e) => this.handle(e.data);
    ws.onclose = () => {
      clearInterval(this.heartbeat);
      if (!this.wanted) return;
      if (this.status !== 'error') this.setStatus('connecting', this.info('перепідключення…'));
      this.retry = setTimeout(() => this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, RETRY_MAX_MS);
    };
  }

  handle(raw) {
    let m;
    try {
      m = JSON.parse(raw);
    } catch {
      return;
    }
    if (m.event === 'price') {
      const price = Number(m.price);
      if (!Number.isFinite(price)) return;
      if (this.status !== 'live') this.setStatus('live', this.info());
      this.retryMs = RETRY_MIN_MS;
      const t = Number(m.timestamp);
      this.onPrice(m.symbol, price, Number.isFinite(t) ? t * 1000 : null);
    } else if (m.event === 'subscribe-status') {
      const fails = (m.fails || []).map((f) => f.symbol ?? f).filter(Boolean);
      const ok = (m.success || []).map((f) => f.symbol ?? f).filter(Boolean);
      // Ключ відмовив, лише якщо не дав жодної пари. Частина пар — працюємо з тим, що є.
      if (!ok.length && (m.status !== 'ok' || fails.length)) {
        this.failKey(fails.length ? `тариф не дає: ${fails.join(', ')}` : m.message || 'підписка не вдалась');
        return;
      }
      this.setUnavailable(fails);
      this.setStatus(this.status === 'live' ? 'live' : 'connecting', this.status === 'live' ? this.info() : this.info('чекаю першу ціну…'));
    } else if (m.status === 'error' || m.event === 'error') {
      this.failKey(m.message || 'помилка Twelve Data');
    }
  }
}

export function parseKeys(text) {
  const seen = new Set();
  return String(text || '')
    .split(/[\s,;"']+/)
    .map((k) => k.trim())
    .filter((k) => k && !seen.has(k) && seen.add(k));
}

// Перевірка, які пари дає тариф: окреме тимчасове з'єднання, підписка пачками по 8,
// Twelve Data відповідає subscribe-status зі списками success / fails.
export async function probePairs({ key, symbols, WebSocketImpl = globalThis.WebSocket, batch = 8, timeoutMs = 8000, onProgress }) {
  const ok = [];
  const fail = [];
  let error = '';
  for (let i = 0; i < symbols.length; i += batch) {
    const part = symbols.slice(i, i + batch);
    const res = await probeBatch({ key, symbols: part, WebSocketImpl, timeoutMs });
    if (res.error && !res.ok.length && !res.fail.length) {
      error = res.error;
      break;
    }
    ok.push(...res.ok);
    // Що не прийшло ні в success, ні в fails — вважаємо недоступним.
    fail.push(...part.filter((s) => !res.ok.includes(s)));
    onProgress?.(Math.min(i + batch, symbols.length), symbols.length);
  }
  return { ok, fail, error };
}

function probeBatch({ key, symbols, WebSocketImpl, timeoutMs }) {
  return new Promise((resolve) => {
    let done = false;
    let ws;
    const finish = (r) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try {
        ws.onclose = null;
        ws.close();
      } catch {
        /* вже закрите */
      }
      resolve(r);
    };
    const timer = setTimeout(() => finish({ ok: [], fail: [], error: 'Twelve Data не відповів' }), timeoutMs);
    try {
      ws = new WebSocketImpl(`${WS_URL}?apikey=${encodeURIComponent(key)}`);
    } catch {
      return finish({ ok: [], fail: [], error: 'WebSocket недоступний' });
    }
    ws.onopen = () => ws.send(JSON.stringify({ action: 'subscribe', params: { symbols: symbols.join(',') } }));
    ws.onclose = () => finish({ ok: [], fail: [], error: "з'єднання закрилось" });
    ws.onmessage = (e) => {
      let m;
      try {
        m = JSON.parse(e.data);
      } catch {
        return;
      }
      const sym = (list) => (list || []).map((f) => f.symbol ?? f).filter(Boolean);
      if (m.event === 'subscribe-status') finish({ ok: sym(m.success), fail: sym(m.fails), error: '' });
      else if (m.status === 'error' || m.event === 'error') finish({ ok: [], fail: [], error: m.message || 'помилка Twelve Data' });
    };
  });
}
