// Справжні ціни спот-пар із Twelve Data через WebSocket.
// Ключ вводиться в застосунку і зберігається лише на пристрої — у коді його немає.
const WS_URL = 'wss://ws.twelvedata.com/v1/quotes/price';
const HEARTBEAT_MS = 10_000;
const RETRY_MIN_MS = 2_000;
const RETRY_MAX_MS = 60_000;

// status: 'nokey' | 'connecting' | 'live' | 'error'
export class TwelveDataFeed {
  constructor({ symbols, onPrice, onStatus, WebSocketImpl = globalThis.WebSocket }) {
    this.symbols = symbols;
    this.onPrice = onPrice;
    this.onStatus = onStatus;
    this.WS = WebSocketImpl;
    this.key = '';
    this.ws = null;
    this.retryMs = RETRY_MIN_MS;
    this.wanted = false;
  }

  setStatus(status, message = '') {
    this.status = status;
    this.onStatus?.(status, message);
  }

  setKey(key) {
    this.key = (key || '').trim();
    if (this.wanted) this.connect();
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
    this.setStatus('connecting');
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
      if (this.status !== 'error') this.setStatus('connecting', 'перепідключення…');
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
      if (this.status !== 'live') this.setStatus('live');
      this.retryMs = RETRY_MIN_MS;
      const t = Number(m.timestamp);
      this.onPrice(m.symbol, price, Number.isFinite(t) ? t * 1000 : null);
    } else if (m.event === 'subscribe-status') {
      const fails = (m.fails || []).map((f) => f.symbol ?? f).filter(Boolean);
      if (m.status !== 'ok' || fails.length) {
        this.setStatus('error', fails.length ? `тариф не дає: ${fails.join(', ')}` : m.message || 'підписка не вдалась');
      } else if (this.status !== 'live') this.setStatus('connecting', 'чекаю першу ціну…');
    } else if (m.status === 'error' || m.event === 'error') {
      this.setStatus('error', m.message || 'помилка Twelve Data');
    }
  }
}
