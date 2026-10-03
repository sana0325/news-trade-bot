import { ALL_ASSETS } from './assets.js';
import { computeVotes } from './indicators.js';
import { pushPrice, bucketOf, TICK_MS, CANDLE_MS, MAX_CANDLES } from './market.js';
import { sessionInfo } from './session.js';
import {
  evaluate,
  isStrong,
  createSignal,
  resolveSignal,
  MAX_ACTIVE,
  PAIR_PAUSE_MS,
  MIN_CONFIDENCE,
} from './signal.js';
import { BinariumPoller } from './binarium.js';
import { TwelveDataFeed, probePairs as realProbe } from './twelvedata.js';
import { detectLevelBounce, detectWedgeBreakout, patternConfidence, patternReasons } from './patterns.js';

const FEED_STALE_MS = 10_000; // OTC: Binarium опитуємо раз на 2.5 с
const SPOT_STALE_MS = 30_000; // форекс може кілька секунд не тікати — це ще не обрив
const SPOT_GAP_RESET_MS = 120_000; // після довшої перерви історія вже не суцільна — розігрів наново
export const WARMUP_CANDLES = 52; // SMA / EMA 50 + закрита свічка

const defaultSpotFeed = (opts) => new TwelveDataFeed(opts);
const defaultOtcFeed = (opts) => new BinariumPoller(opts);
const MAX_RESULTS = 8;

export class Engine {
  constructor({
    now = Date.now,
    assets = ALL_ASSETS,
    createSpotFeed = defaultSpotFeed,
    createOtcFeed = defaultOtcFeed,
    savedCandles = {}, // { 'EUR/USD': [...] } — свічки з минулого запуску
    probe = realProbe,
  } = {}) {
    this.now = now;
    this.probeImpl = probe;
    this.savedCandles = savedCandles;
    this.filter = MIN_CONFIDENCE;
    this.strategies = new Set(['indicators', 'level', 'wedge']);
    this.signals = [];
    this.results = [];
    this.lastEnd = new Map(); // assetId → коли закрився останній сигнал
    this.listeners = new Set();
    const t = now();
    this.session = sessionInfo(t);
    this.state = new Map();
    for (const asset of assets) this.state.set(asset.id, this.makeState(asset, t));
    this.spotStatus = { status: 'nokey', message: '' };
    this.spotFeed = createSpotFeed({
      symbols: this.spotSymbols(),
      onPrice: (symbol, price) => this.applySpotPrice(symbol, price, this.now()),
      onUnavailable: (symbols) => {
        for (const x of this.state.values()) if (x.asset.tdSymbol) x.unavailable = symbols.includes(x.asset.tdSymbol);
      },
      onStatus: (status, message) => {
        this.spotStatus = { status, message };
      },
    });
    this.otcFeed = createOtcFeed({
      onData: (id, data, full) => {
        const s = this.otcState(id);
        if (s) this.applyFeed(s, data, this.now(), full);
      },
      onError: (id, message) => {
        const s = this.otcState(id);
        if (s) s.feedError = message;
      },
    });
    this.otcFeed.setIds(this.otcIds());
    this.evaluateAll(t, false);
  }

  otcState(binariumId) {
    for (const s of this.state.values()) if (s.asset.binariumId === binariumId) return s;
    return null;
  }

  otcIds() {
    return [...this.state.values()].filter((s) => s.asset.market === 'otc').map((s) => s.asset.binariumId);
  }

  makeState(asset, t) {
    // Лише справжні ціни, без симуляції. Спот (Twelve Data): історія зі збережених свічок,
    // якщо перерва була коротка; інакше — порожньо і розігрів. OTC (Binarium): історію дає сам
    // Binarium при підключенні, до перших цін пара «немає цін».
    const spot = asset.feed === 'twelvedata';
    let candles = [];
    if (spot) {
      const saved = this.savedCandles[asset.tdSymbol];
      const last = saved?.[saved.length - 1];
      if (last && t - last.t <= SPOT_GAP_RESET_MS) candles = saved.slice(-MAX_CANDLES).map((c) => ({ ...c }));
    }
    return {
      asset,
      candles,
      price: candles[candles.length - 1]?.c ?? null,
      source: spot ? 'twelvedata' : 'binarium',
      feedAt: 0,
      feedError: null,
      closedT: null,
      closedEv: null,
      patterns: [],
      votes: [],
      ev: null,
    };
  }

  spotSymbols() {
    return [...this.state.values()].filter((s) => s.source === 'twelvedata').map((s) => s.asset.tdSymbol);
  }

  // Новий набір спот-пар. Незмінені пари зберігають історію; сигнали прибраних пар знімаються.
  setSpotPairs(assets) {
    const t = this.now();
    const keep = new Set(assets.map((a) => a.id));
    for (const [id, s] of this.state) {
      if (s.source === 'twelvedata' && !keep.has(id)) {
        this.savedCandles[s.asset.tdSymbol] = s.candles;
        this.state.delete(id);
      }
    }
    this.signals = this.signals.filter((sig) => this.state.has(sig.assetId));
    for (const a of assets) if (!this.state.has(a.id)) this.state.set(a.id, this.makeState(a, t));
    this.spotFeed.setSymbols(this.spotSymbols());
  }

  // Новий набір OTC-пар. Як і для спот-пар: незмінені пари лишаються, сигнали прибраних знімаються.
  setOtcPairs(assets) {
    const t = this.now();
    const keep = new Set(assets.map((a) => a.id));
    for (const [id, s] of this.state) if (s.asset.market === 'otc' && !keep.has(id)) this.state.delete(id);
    this.signals = this.signals.filter((sig) => this.state.has(sig.assetId));
    for (const a of assets) if (!this.state.has(a.id)) this.state.set(a.id, this.makeState(a, t));
    this.otcFeed.setIds(this.otcIds());
  }

  otcPairs() {
    return [...this.state.values()]
      .filter((s) => s.asset.market === 'otc')
      .map(({ asset: a }) => ({ binariumId: a.binariumId, symbol: a.symbol, digits: a.digits }));
  }

  // Свічки спот-пар для збереження на пристрої.
  spotCandles() {
    const out = {};
    for (const s of this.state.values()) if (s.source === 'twelvedata' && s.candles.length) out[s.asset.tdSymbol] = s.candles;
    return out;
  }

  // Перевірка, які пари дає тариф. На час перевірки основний фід на паузі,
  // щоб не перевищити ліміт пробних символів на ключ.
  async probePairs(symbols, onProgress) {
    const key = this.spotFeed.key;
    if (!key) return { ok: [], fail: [], error: 'спершу вставте ключ' };
    this.spotFeed.stop();
    try {
      return await this.probeImpl({ key, symbols, onProgress });
    } finally {
      this.syncSpotFeed();
    }
  }

  setTwelveDataKey(key) {
    this.spotFeed.setKey(key);
  }

  syncSpotFeed() {
    if (this.timer && this.mode === 'spot') this.spotFeed.start();
    else this.spotFeed.stop();
    if (this.timer && this.mode === 'otc') this.otcFeed.start();
    else this.otcFeed.stop();
  }

  // Після повернення в застосунок: OTC-історію — заново (поки WebView спав, дані могли загубитись).
  resyncOtc() {
    this.otcFeed.refresh();
  }

  applySpotPrice(symbol, price, t) {
    const s = [...this.state.values()].find((x) => x.asset.tdSymbol === symbol);
    if (!s) return;
    const last = s.candles[s.candles.length - 1];
    if (last && t - last.t > SPOT_GAP_RESET_MS) s.candles.length = 0;
    s.price = price;
    s.feedAt = t;
    pushPrice(s.candles, t, price);
  }

  subscribe(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  emit(ev) {
    for (const fn of this.listeners) fn(ev);
  }

  // Які стратегії можуть давати нові сигнали. Активні сигнали доживають свої 5 хвилин.
  setStrategies(list) {
    this.strategies = new Set(list);
  }

  setFilter(value) {
    this.filter = Math.max(MIN_CONFIDENCE, value);
  }

  start() {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), TICK_MS);
    this.syncSpotFeed();
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
    this.syncSpotFeed();
  }

  get mode() {
    return this.session.mode;
  }

  visibleAssets() {
    return [...this.state.values()].filter((s) => s.asset.market === this.mode);
  }

  feedLive(s, t) {
    return s.price != null && t - s.feedAt < (s.source === 'twelvedata' ? SPOT_STALE_MS : FEED_STALE_MS);
  }

  warm(s) {
    return s.candles.length >= WARMUP_CANDLES;
  }

  // Пульс від нативної служби: тікаємо, лише якщо власний таймер давно не спрацьовував.
  tickIfStale(ms = 800) {
    if (this.now() - (this.lastTick ?? 0) >= ms) this.tick();
  }

  // Свічки від нативної служби (зібрані, поки WebView спав). Беремо їх, якщо вони новіші
  // або довші за наші — так після повернення в застосунок розігрів не починається наново.
  restoreSpotCandles(map) {
    const t = this.now();
    for (const s of this.state.values()) {
      if (s.source !== 'twelvedata') continue;
      const list = map[s.asset.tdSymbol];
      const last = list?.[list.length - 1];
      if (!last || t - last.t > SPOT_GAP_RESET_MS) continue;
      const mine = s.candles[s.candles.length - 1];
      if (mine && mine.t > last.t) continue;
      if (mine && mine.t === last.t && s.candles.length >= list.length) continue;
      s.candles = list.slice(-MAX_CANDLES).map((c) => ({ ...c }));
      s.price = last.c;
      s.closedT = null; // перерахувати закриту свічку
    }
  }

  tick() {
    const t = this.now();
    this.lastTick = t;
    const session = sessionInfo(t);
    if (session.mode !== this.session.mode) {
      this.session = session;
      this.syncSpotFeed();
      this.emit({ type: 'mode', mode: session.mode });
    } else this.session = session;

    // Між цінами свічка триває з останньою ціною; без фіду — стоїть.
    for (const s of this.state.values()) if (this.feedLive(s, t)) pushPrice(s.candles, t, s.price);

    this.resolveExpired(t);
    this.evaluateAll(t, true);
    this.emit({ type: 'tick' });
  }

  resolveExpired(t) {
    const still = [];
    for (const sig of this.signals) {
      if (t >= sig.expiresAt) {
        const s = this.state.get(sig.assetId);
        const done = resolveSignal(sig, s.price, s.asset.digits);
        this.results.unshift(done);
        this.lastEnd.set(sig.assetId, t);
        this.emit({ type: 'result', signal: done });
      } else still.push(sig);
    }
    this.signals = still;
    if (this.results.length > MAX_RESULTS) this.results.length = MAX_RESULTS;
  }

  evaluateAll(t, allowSignals) {
    for (const s of this.visibleAssets()) {
      const { digits } = s.asset;
      if (!this.warm(s)) {
        s.votes = [];
        s.ev = null;
        s.closedEv = null;
        s.patterns = [];
        continue;
      }
      s.votes = computeVotes(s.candles, digits);
      s.ev = evaluate(s.votes);
      // Закрита свічка теж має бути сильною — так відсікаємо короткі імпульси.
      const closedT = s.candles[s.candles.length - 2]?.t;
      if (closedT !== s.closedT) {
        s.closedT = closedT;
        const closed = s.candles.slice(0, -1);
        s.closedEv = evaluate(computeVotes(closed, digits));
        s.patterns = [detectLevelBounce(closed), detectWedgeBreakout(closed)].filter(Boolean);
      }
      if (allowSignals) this.maybeSignal(s, t);
    }
  }

  canSignal(s, t) {
    if (!this.feedLive(s, t)) return false; // ціни не йдуть — сигналів немає
    if (this.signals.length >= MAX_ACTIVE) return false;
    if (this.signals.some((x) => x.assetId === s.asset.id)) return false;
    const end = this.lastEnd.get(s.asset.id);
    return end == null || t - end >= PAIR_PAUSE_MS;
  }

  maybeSignal(s, t) {
    if (!this.canSignal(s, t)) return;
    // Патерн живе одну свічку, а сильний стан індикаторів триває довше — тому патерн першим.
    const sig = this.patternSignal(s, t) ?? this.indicatorSignal(s, t);
    if (!sig) return;
    this.signals.push(sig);
    this.emit({ type: 'signal', signal: sig });
  }

  indicatorSignal(s, t) {
    if (!this.strategies.has('indicators')) return null;
    const { ev, closedEv } = s;
    if (!closedEv || !isStrong(ev, this.filter) || !isStrong(closedEv, this.filter)) return null;
    if (ev.direction !== closedEv.direction) return null;
    return createSignal({ asset: s.asset, votes: s.votes, ev, price: s.price, now: t });
  }

  // Відбиття від рівня і пробій клина. Патерн знайдено на закритій свічці,
  // а поточна ціна має ще стояти по правильний бік рівня / межі клина.
  patternSignal(s, t) {
    for (const p of s.patterns) {
      if (!this.strategies.has(p.strategy)) continue;
      const call = p.direction === 'call';
      const confidence = patternConfidence(p, s.closedEv);
      if (confidence < this.filter) continue;
      if (p.strategy === 'level') {
        if (call ? s.price <= p.level : s.price >= p.level) continue;
      } else {
        const edge = call ? p.lines.upper.p1 : p.lines.lower.p1;
        if (call ? s.price <= edge : s.price >= edge) continue;
        const diff = s.ev.buy - s.ev.sell;
        if (call ? diff <= -12 : diff >= 12) continue; // індикатори рішуче проти пробою
      }
      const pattern =
        p.strategy === 'level' ? { type: 'level', price: p.level } : { type: 'wedge', ...p.lines, kind: p.kind };
      return createSignal({
        asset: s.asset,
        votes: s.votes,
        ev: s.ev,
        price: s.price,
        now: t,
        strategy: p.strategy,
        direction: p.direction,
        confidence,
        reasons: patternReasons(p, s.closedEv, s.asset.digits),
        pattern,
      });
    }
    return null;
  }

  // Свічки Binarium. Повна історія замінює все, що було; нове — доливається.
  applyFeed(s, { bars, quote, note }, t, full = false) {
    // Годинник сервера буває трохи попереду телефона — свічок «з майбутнього» не беремо,
    // інакше нова свічка тіку стала б перед ними і порядок зламався.
    const cur = bucketOf(t);
    bars = bars.filter((b) => b.t <= cur);
    const c = s.candles;
    const last = c[c.length - 1];
    if (full) s.candles = bars.slice(-MAX_CANDLES).map((b) => ({ ...b }));
    else if (bars.length && (!last || bars[0].t > last.t + CANDLE_MS)) {
      // Між нашою останньою свічкою і новими — дірка (WebView спав): просимо всю історію.
      this.otcFeed.refresh(s.asset.binariumId);
      if (last && t - last.t > SPOT_GAP_RESET_MS) return;
    }
    if (!full) {
      for (const b of bars) {
        let i = c.length - 1;
        while (i >= 0 && c[i].t > b.t) i--;
        if (i >= 0 && c[i].t === b.t) c[i] = { ...b };
        else if (!c.length || b.t > c[c.length - 1].t) c.push({ ...b });
      }
      if (c.length > MAX_CANDLES) c.splice(0, c.length - MAX_CANDLES);
    }
    const price = quote?.value ?? s.candles[s.candles.length - 1]?.c;
    if (price == null) return;
    s.price = price;
    pushPrice(s.candles, t, price);
    s.feedAt = t;
    s.feedError = note || null;
    s.closedT = null; // свічки змінились — перерахувати закриту
  }

  snapshot() {
    const t = this.now();
    return {
      now: t,
      session: this.session,
      spotFeed: this.spotStatus,
      assets: this.visibleAssets().map((s) => {
        const c = s.candles;
        const ref = c.length ? c[Math.max(0, c.length - 21)].c : null; // ~5 хв тому
        return {
          ...s.asset,
          price: s.price,
          change: s.price != null && ref != null ? s.price - ref : 0,
          live: this.feedLive(s, t),
          warmup: { have: Math.min(c.length, WARMUP_CANDLES), need: WARMUP_CANDLES },
          unavailable: !!s.unavailable,
          feedError: s.feedError ?? null,
          ev: s.ev,
        };
      }),
      signals: [...this.signals],
      results: [...this.results],
    };
  }

  candles(id) {
    return this.state.get(id)?.candles ?? [];
  }

  votes(id) {
    return this.state.get(id)?.votes ?? [];
  }

  asset(id) {
    return this.state.get(id)?.asset;
  }

  priceOf(id) {
    return this.state.get(id)?.price;
  }
}
