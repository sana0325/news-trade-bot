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

const FEED_STALE_MS = 10_000;
const MAX_RESULTS = 8;

export class Engine {
  constructor({ now = Date.now, rand = Math.random, fetchFeed = realFetchFeed, assets = ALL_ASSETS } = {}) {
    this.now = now;
    this.fetchFeed = fetchFeed;
    this.filter = MIN_CONFIDENCE;
    this.signals = [];
    this.results = [];
    this.lastEnd = new Map(); // assetId → коли закрився останній сигнал
    this.listeners = new Set();
    const t = now();
    this.session = sessionInfo(t);
    this.state = new Map();
    for (const asset of assets) {
      const walker = createWalker(asset, rand);
      const candles = buildHistory(walker, t);
      this.state.set(asset.id, {
        asset,
        walker,
        candles,
        price: walker.price,
        source: 'sim',
        feedAt: 0,
        feedSynced: false,
        pending: false,
        closedT: null,
        closedEv: null,
        votes: [],
        ev: null,
      });
    }
    this.evaluateAll(t, false);
  }

  subscribe(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  emit(ev) {
    for (const fn of this.listeners) fn(ev);
  }

  setFilter(value) {
    this.filter = Math.max(MIN_CONFIDENCE, value);
  }

  start() {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), TICK_MS);
    this.poller = setInterval(() => this.poll(), POLL_MS);
    this.poll();
  }

  stop() {
    clearInterval(this.timer);
    clearInterval(this.poller);
    this.timer = this.poller = null;
  }

  get mode() {
    return this.session.mode;
  }

  visibleAssets() {
    return [...this.state.values()].filter((s) => s.asset.market === this.mode);
  }

  feedLive(s, t) {
    return s.source === 'feed' && t - s.feedAt < FEED_STALE_MS;
  }

  tick() {
    const t = this.now();
    const session = sessionInfo(t);
    if (session.mode !== this.session.mode) {
      this.session = session;
      this.emit({ type: 'mode', mode: session.mode });
    } else this.session = session;

    for (const s of this.state.values()) {
      if (this.feedLive(s, t)) {
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
      s.votes = computeVotes(s.candles, digits);
      s.ev = evaluate(s.votes);
      // Закрита свічка теж має бути сильною — так відсікаємо короткі імпульси.
      const closedT = s.candles[s.candles.length - 2]?.t;
      if (closedT !== s.closedT) {
        s.closedT = closedT;
        s.closedEv = evaluate(computeVotes(s.candles.slice(0, -1), digits));
      }
      if (allowSignals) this.maybeSignal(s, t);
    }
  }

  canSignal(s, t) {
    if (this.signals.length >= MAX_ACTIVE) return false;
    if (this.signals.some((x) => x.assetId === s.asset.id)) return false;
    const end = this.lastEnd.get(s.asset.id);
    return end == null || t - end >= PAIR_PAUSE_MS;
  }

  maybeSignal(s, t) {
    if (!this.canSignal(s, t)) return;
    const { ev, closedEv } = s;
    if (!closedEv || !isStrong(ev, this.filter) || !isStrong(closedEv, this.filter)) return;
    if (ev.direction !== closedEv.direction) return;
    const sig = createSignal({ asset: s.asset, votes: s.votes, ev, price: s.price, now: t });
    this.signals.push(sig);
    this.emit({ type: 'signal', signal: sig });
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
      assets: this.visibleAssets().map((s) => {
        const c = s.candles;
        const ref = c[Math.max(0, c.length - 21)].c; // ~5 хв тому
        return {
          ...s.asset,
          price: s.price,
          change: s.price - ref,
          live: this.feedLive(s, t),
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
