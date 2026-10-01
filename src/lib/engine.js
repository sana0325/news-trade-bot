import { ALL_ASSETS } from './assets.js';
import { computeVotes } from './indicators.js';
import { createWalker, stepWalker, pushPrice, buildHistory, TICK_MS, MAX_CANDLES } from './market.js';
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
import { fetchFeed as realFetchFeed, POLL_MS } from './binarium.js';
import { TwelveDataFeed } from './twelvedata.js';
import { detectLevelBounce, detectWedgeBreakout, patternConfidence, patternReasons } from './patterns.js';

const FEED_STALE_MS = 10_000;
const SPOT_STALE_MS = 30_000; // форекс може кілька секунд не тікати — це ще не обрив
const SPOT_GAP_RESET_MS = 120_000; // після довшої перерви історія вже не суцільна — розігрів наново
export const WARMUP_CANDLES = 52; // SMA / EMA 50 + закрита свічка

const defaultSpotFeed = (opts) => new TwelveDataFeed(opts);
const MAX_RESULTS = 8;

export class Engine {
  constructor({
    now = Date.now,
    rand = Math.random,
    fetchFeed = realFetchFeed,
    assets = ALL_ASSETS,
    createSpotFeed = defaultSpotFeed,
  } = {}) {
    this.now = now;
    this.fetchFeed = fetchFeed;
    this.filter = MIN_CONFIDENCE;
    this.strategies = new Set(['indicators', 'level', 'wedge']);
    this.signals = [];
    this.results = [];
    this.lastEnd = new Map(); // assetId → коли закрився останній сигнал
    this.listeners = new Set();
    const t = now();
    this.session = sessionInfo(t);
    this.state = new Map();
    for (const asset of assets) {
      // Спот-пари Twelve Data починають з порожньої історії: лише справжні ціни, без симуляції.
      const real = asset.feed === 'twelvedata';
      const walker = real ? null : createWalker(asset, rand);
      const candles = real ? [] : buildHistory(walker, t);
      this.state.set(asset.id, {
        asset,
        walker,
        candles,
        price: real ? null : walker.price,
        source: real ? 'twelvedata' : 'sim',
        feedAt: 0,
        feedSynced: false,
        pending: false,
        closedT: null,
        closedEv: null,
        patterns: [],
        votes: [],
        ev: null,
      });
    }
    this.spotStatus = { status: 'nokey', message: '' };
    const spot = assets.filter((a) => a.feed === 'twelvedata');
    this.spotFeed = createSpotFeed({
      symbols: spot.map((a) => a.tdSymbol),
      onPrice: (symbol, price) => this.applySpotPrice(symbol, price, this.now()),
      onUnavailable: (symbols) => {
        for (const x of this.state.values()) if (x.asset.tdSymbol) x.unavailable = symbols.includes(x.asset.tdSymbol);
      },
      onStatus: (status, message) => {
        this.spotStatus = { status, message };
      },
    });
    this.evaluateAll(t, false);
  }

  setTwelveDataKey(key) {
    this.spotFeed.setKey(key);
  }

  syncSpotFeed() {
    if (this.timer && this.mode === 'spot') this.spotFeed.start();
    else this.spotFeed.stop();
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
    this.poller = setInterval(() => this.poll(), POLL_MS);
    this.poll();
    this.syncSpotFeed();
  }

  stop() {
    clearInterval(this.timer);
    clearInterval(this.poller);
    this.timer = this.poller = null;
    this.syncSpotFeed();
  }

  get mode() {
    return this.session.mode;
  }

  visibleAssets() {
    return [...this.state.values()].filter((s) => s.asset.market === this.mode);
  }

  feedLive(s, t) {
    if (s.source === 'twelvedata') return s.price != null && t - s.feedAt < SPOT_STALE_MS;
    return s.source === 'feed' && t - s.feedAt < FEED_STALE_MS;
  }

  warm(s) {
    return s.candles.length >= WARMUP_CANDLES;
  }

  tick() {
    const t = this.now();
    const session = sessionInfo(t);
    if (session.mode !== this.session.mode) {
      this.session = session;
      this.syncSpotFeed();
      this.emit({ type: 'mode', mode: session.mode });
    } else this.session = session;

    for (const s of this.state.values()) {
      if (s.source === 'twelvedata') {
        // Між тіками свічка триває з останньою ціною; без фіду — стоїть.
        if (this.feedLive(s, t)) pushPrice(s.candles, t, s.price);
      } else if (this.feedLive(s, t)) {
        pushPrice(s.candles, t, s.price);
      } else {
        if (s.source === 'feed') s.source = 'sim'; // фіду немає — лишаємо симуляцію
        s.walker.price = s.price;
        s.price = stepWalker(s.walker);
        pushPrice(s.candles, t, s.price);
      }
    }

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
    if (s.source === 'twelvedata' && !this.feedLive(s, t)) return false; // ціни не йдуть — сигналів немає
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

  poll() {
    if (this.mode !== 'otc') return;
    for (const s of this.state.values()) {
      if (s.asset.market !== 'otc' || s.pending) continue;
      s.pending = true;
      this.fetchFeed(s.asset.binariumId, this.now())
        .then((data) => {
          if (data) this.applyFeed(s, data, this.now());
        })
        .catch(() => {})
        .finally(() => {
          s.pending = false;
        });
    }
  }

  // Вливає бари фіду в історію. Перший раз зсуває симуляцію до рівня реальної ціни.
  applyFeed(s, { bars, quote }, t) {
    const c = s.candles;
    if (!s.feedSynced) {
      const ref = bars.length ? bars[0].o : quote?.value;
      if (!ref) return;
      const cut = bars.length ? bars[0].t : Infinity;
      const kept = c.filter((k) => k.t < cut);
      const last = kept[kept.length - 1]?.c ?? s.price;
      const ratio = ref / last;
      for (const k of kept) {
        k.o *= ratio;
        k.h *= ratio;
        k.l *= ratio;
        k.c *= ratio;
      }
      c.length = 0;
      c.push(...kept);
      s.walker.base = ref; // симуляція після фіду тримається біля реальної ціни
      s.feedSynced = true;
    }
    for (const b of bars) {
      const i = c.findIndex((k) => k.t === b.t);
      if (i >= 0) c[i] = { ...b };
      else if (!c.length || b.t > c[c.length - 1].t) c.push({ ...b });
    }
    if (c.length > MAX_CANDLES) c.splice(0, c.length - MAX_CANDLES);
    s.price = quote?.value ?? c[c.length - 1].c;
    pushPrice(c, t, s.price);
    s.walker.price = s.price;
    s.source = 'feed';
    s.feedAt = t;
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
