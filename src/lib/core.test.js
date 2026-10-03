import { describe, it, expect, vi, afterEach } from 'vitest';
import { computeVotes, rsi, sma, BUY, SELL } from './indicators.js';
import { evaluate, isStrong, resolveSignal, createSignal, SIGNAL_MS } from './signal.js';
import { isForexOpen, nextSessionChange, kyivDateTime } from './session.js';
import {
  parseCandles,
  parseLastQuote,
  toBars,
  candlesUrl,
  quotesUrl,
  fetchFeed,
  buildFeed,
  BinariumPoller,
  probeOtc,
  digitsOf,
  fetchOpinion,
  FeedError,
} from './binarium.js';
import { Engine } from './engine.js';
import { OTC_ASSETS, spotAssetsFor, otcAssetsFor, otcSymbol } from './assets.js';
import { TwelveDataFeed, parseKeys, probePairs } from './twelvedata.js';

const series = (step, n = 128) =>
  Array.from({ length: n }, (_, i) => {
    const c = 1 + i * step + (i % 2 ? 0.00002 : -0.00002);
    return { t: i * 15000, o: c - step / 2, h: c + 0.0001, l: c - 0.0001, c };
  });

describe('індикатори', () => {
  it('25 голосів: 12 середніх і 13 осциляторів', () => {
    const v = computeVotes(series(0.0001));
    expect(v).toHaveLength(25);
    expect(v.filter((x) => x.group === 'ma')).toHaveLength(12);
    expect(v.filter((x) => x.group === 'osc')).toHaveLength(13);
  });

  it('висхідний тренд дає переважно buy', () => {
    const ev = evaluate(computeVotes(series(0.0001)));
    expect(ev.direction).toBe('call');
    expect(ev.buy - ev.sell).toBeGreaterThanOrEqual(12);
  });

  it('низхідний тренд дає переважно sell', () => {
    const ev = evaluate(computeVotes(series(-0.0001)));
    expect(ev.direction).toBe('put');
  });

  it('sma і rsi', () => {
    expect(sma([1, 2, 3, 4], 2)).toBe(3.5);
    expect(rsi(Array.from({ length: 30 }, (_, i) => i))).toBe(100);
  });
});

describe('сигнал', () => {
  const votes = (b, s) => [
    ...Array(b).fill({ vote: BUY, group: 'ma' }),
    ...Array(s).fill({ vote: SELL, group: 'osc' }),
    ...Array(25 - b - s).fill({ vote: 'neutral', group: 'osc' }),
  ];

  it('формули trendScore і confidence', () => {
    const ev = evaluate(votes(18, 6));
    expect(ev.trendScore).toBe(75);
    expect(ev.confidence).toBe(78);
    expect(ev.direction).toBe('call');
    expect(evaluate(votes(0, 0))).toMatchObject({ trendScore: 50, confidence: 50, direction: null });
    expect(evaluate(votes(25, 0)).confidence).toBe(90);
    expect(evaluate(votes(16, 8)).confidence).toBe(70); // напрямок є → підлога 70
  });

  it('поріг показу і фільтр', () => {
    expect(isStrong(evaluate(votes(15, 5)))).toBe(false); // різниця 10 < 12
    expect(isStrong(evaluate(votes(18, 6)), 74)).toBe(true);
    expect(isStrong(evaluate(votes(18, 6)), 80)).toBe(false);
    expect(isStrong(evaluate(votes(22, 1)), 86)).toBe(true);
  });

  it('результат через 5 хвилин', () => {
    const ev = evaluate(votes(20, 2));
    const sig = createSignal({ asset: { id: 'eurusd', symbol: 'EUR/USD' }, votes: [], ev, price: 1.1, now: 0 });
    expect(sig.expiresAt).toBe(SIGNAL_MS);
    expect(sig.tf).toBe(300);
    expect(resolveSignal(sig, 1.10002, 5).status).toBe('hit');
    expect(resolveSignal(sig, 1.09998, 5).status).toBe('miss');
    expect(resolveSignal(sig, 1.100004, 5).status).toBe('flat');
  });
});

describe('сесія', () => {
  it('межі неділя 17:00 — п’ятниця 17:00 NY', () => {
    expect(isForexOpen(Date.parse('2026-09-29T12:00:00Z'))).toBe(true); // вівторок
    expect(isForexOpen(Date.parse('2026-10-03T12:00:00Z'))).toBe(false); // субота
    expect(isForexOpen(Date.parse('2026-10-02T20:59:00Z'))).toBe(true); // пт 16:59 EDT
    expect(isForexOpen(Date.parse('2026-10-02T21:00:00Z'))).toBe(false); // пт 17:00 EDT
    expect(isForexOpen(Date.parse('2026-10-04T20:59:00Z'))).toBe(false); // нд 16:59
    expect(isForexOpen(Date.parse('2026-10-04T21:00:00Z'))).toBe(true); // нд 17:00
  });

  it('наступна зміна і час Києва', () => {
    const next = nextSessionChange(Date.parse('2026-09-29T12:00:00Z'));
    expect(next).toBe(Date.parse('2026-10-02T21:00:00Z'));
    expect(kyivDateTime(next)).toBe('сб 03.10, 00:00');
    expect(nextSessionChange(Date.parse('2026-10-03T12:00:00Z'))).toBe(Date.parse('2026-10-04T21:00:00Z'));
    // зимовий час: EST
    expect(nextSessionChange(Date.parse('2026-12-01T12:00:00Z'))).toBe(Date.parse('2026-12-04T22:00:00Z'));
  });
});

describe('фід Binarium', () => {
  const NOW = Date.parse('2026-10-03T12:00:00Z');
  const res = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
  // Сервер: свічки по 15 с на весь запитаний діапазон, котирування — останнє.
  const server = (price = 1.17) => {
    const seen = [];
    const fetch = async (url) => {
      seen.push(url);
      const q = new URL(url, 'http://x').searchParams;
      const from = Date.parse(q.get('from'));
      const to = Date.parse(q.get('to'));
      if (url.includes('/quotes')) return res({ data: [{ value: price, timestamp: to / 1000 }] });
      const data = [];
      for (let t = Math.ceil(from / 15000) * 15000; t <= to; t += 15000)
        data.push({ open: price, high: price + 0.001, low: price - 0.001, close: price, timestamp: t / 1000 });
      return res({ data });
    };
    return { seen, fetch };
  };
  afterEach(() => vi.unstubAllGlobals());

  it('терпимо читає свічки й котирування', () => {
    const c = parseCandles({ data: [{ time: 30, open: '1', high: 2, low: 0.5, close: 1.5 }, [15, 1, 1, 1, 1]] });
    expect(c.map((x) => x.t)).toEqual([15000, 30000]);
    expect(parseLastQuote([{ value: 1.1, time: 1 }, { value: 1.2, time: 2 }]).value).toBe(1.2);
    // Час без поясу — UTC, а не місцевий час телефона.
    expect(parseLastQuote({ data: [{ value: 1.3, createdAt: '2026-10-03 12:00:01' }] }).t).toBe(NOW + 1000);
  });

  it('запити як у терміналі Binarium: ISO-час і detalization', () => {
    const from = NOW - 8 * 60_000;
    expect(candlesUrl(43, from, NOW)).toBe(
      '/binarium/api/v1/assets/43/candles?from=2026-10-03T11%3A52%3A00.000Z&to=2026-10-03T12%3A00%3A00.000Z&detalization=15s',
    );
    expect(quotesUrl(46, from, NOW)).toBe(
      '/binarium/api/v1/assets/46/quotes?from=2026-10-03T11%3A52%3A00.000Z&to=2026-10-03T12%3A00%3A00.000Z&detalization=1s',
    );
  });

  it('історія за 32 хв — чотири запити по 8 хв від найсвіжішого, плюс котирування', async () => {
    const { seen, fetch } = server();
    vi.stubGlobal('fetch', fetch);
    const { bars, quote, note } = await fetchFeed(43, NOW - 32 * 60_000, NOW);
    const candles = seen.filter((u) => u.includes('/candles'));
    expect(candles).toHaveLength(4);
    expect(candles[0]).toContain('to=2026-10-03T12%3A00%3A00.000Z');
    expect(candles[3]).toContain('from=2026-10-03T11%3A28%3A00.000Z');
    expect(seen.filter((u) => u.includes('/quotes'))).toHaveLength(1);
    expect(bars).toHaveLength(129); // 32 хв по 15 с, межі шматків не дублюються
    expect(bars.every((b, i) => i === 0 || b.t - bars[i - 1].t === 15000)).toBe(true);
    expect(quote.value).toBe(1.17);
    expect(note).toBe('');
  });

  it('старшої історії немає — беремо, що дав свіжий шматок', async () => {
    const { fetch } = server();
    vi.stubGlobal('fetch', async (url) => {
      const from = Date.parse(new URL(url, 'http://x').searchParams.get('from'));
      return url.includes('/candles') && from < NOW - 8 * 60_000 ? res({ message: 'range' }, 400) : fetch(url);
    });
    const { bars } = await fetchFeed(43, NOW - 32 * 60_000, NOW);
    expect(bars).toHaveLength(33);
  });

  it('пояснює, чому фіду немає', async () => {
    vi.stubGlobal('fetch', async () => res({ error: 'unauthorized' }, 401));
    const err = await fetchFeed(43, NOW - 60_000, NOW).catch((e) => e);
    expect(err).toBeInstanceOf(FeedError);
    expect(err.message).toBe('HTTP 401');

    vi.stubGlobal('fetch', async () => {
      throw new TypeError('Failed to fetch');
    });
    await expect(fetchFeed(43, NOW - 60_000, NOW)).rejects.toThrow('немає з’єднання');

    // Котирування прочитались, свічки — ні: фід працює, а в примітці видно, які поля прийшли.
    vi.stubGlobal('fetch', async (url) =>
      url.includes('/quotes') ? res({ data: [{ value: 1.17, timestamp: NOW / 1000 }] }) : res({ data: [{ foo: 1, bar: 2 }] }),
    );
    const part = await fetchFeed(43, NOW - 60_000, NOW);
    expect(part.quote.value).toBe(1.17);
    expect(part.note).toBe('свічки: не впізнав формат (поля foo, bar)');

    // Сервер віддає старі дані — це не живі ціни.
    vi.stubGlobal('fetch', async (url) =>
      url.includes('/quotes')
        ? res({ data: [{ value: 1.17, timestamp: (NOW - 3600_000) / 1000 }] })
        : res({ data: [{ open: 1, close: 1, timestamp: (NOW - 3600_000) / 1000 }] }),
    );
    await expect(fetchFeed(43, NOW - 60_000, NOW)).rejects.toThrow('ціни не оновлюються');
  });

  it('збирає бари по 15 секунд', () => {
    const bars = toBars([
      { t: 0, o: 1, h: 2, l: 1, c: 1.5 },
      { t: 5000, o: 1.5, h: 3, l: 0.5, c: 2 },
      { t: 15000, o: 2, h: 2, l: 2, c: 2 },
    ]);
    expect(bars).toEqual([
      { t: 0, o: 1, h: 3, l: 0.5, c: 2 },
      { t: 15000, o: 2, h: 2, l: 2, c: 2 },
    ]);
  });
});

describe('рушій', () => {
  const flush = () => new Promise((r) => setTimeout(r, 0));
  const SAT = Date.parse('2026-10-03T12:00:07Z'); // субота — лише OTC
  const bar = (t, p) => ({ t, o: p, h: p, l: p, c: p });
  const history = (now, p = 1.2, n = 128) =>
    Array.from({ length: n }, (_, i) => bar(Math.floor(now / 15000) * 15000 - (n - 1 - i) * 15000, p));
  // Фейковий фід OTC: рушій сам викликає onData / onError, як BinariumPoller чи служба.
  const fakeOtc = () => {
    const f = { ids: [], started: false, refreshed: [] };
    const create = (opts) => {
      Object.assign(f, opts);
      return {
        setIds: (l) => (f.ids = l),
        start: () => (f.started = true),
        stop: () => (f.started = false),
        refresh: (id) => f.refreshed.push(id ?? 'all'),
      };
    };
    return { f, create };
  };

  it('OTC без симуляції: до перших цін «немає цін», без історії й сигналів', () => {
    const { f, create } = fakeOtc();
    const e = new Engine({ now: () => SAT, createOtcFeed: create });
    expect(e.snapshot().assets.map((a) => a.symbol)).toEqual(OTC_ASSETS.map((a) => a.symbol));
    expect(f.ids).toEqual([43, 46, 47, 48]);
    expect(e.candles('otc-43')).toEqual([]);
    expect(e.snapshot().assets.every((a) => a.price == null && !a.live && a.ev == null)).toBe(true);
    expect(e.canSignal(e.state.get('otc-43'), SAT)).toBe(false);
  });

  it('OTC: повна історія одразу — без розігріву; обрив фіду зупиняє сигнали, причина видна', () => {
    let t = SAT;
    const { f, create } = fakeOtc();
    const e = new Engine({ now: () => t, createOtcFeed: create });
    f.onData(43, { bars: history(t), quote: { t, value: 1.2 }, note: '' }, true);
    const s = e.state.get('otc-43');
    expect(e.candles('otc-43').length).toBeGreaterThanOrEqual(128);
    expect(e.snapshot().assets[0]).toMatchObject({ live: true, feedError: null, warmup: { have: 52, need: 52 } });
    expect(e.canSignal(s, t)).toBe(true);
    f.onError(43, 'HTTP 401');
    t += 11_000;
    e.tick();
    expect(e.snapshot().assets[0]).toMatchObject({ live: false, feedError: 'HTTP 401' });
    expect(e.canSignal(s, t)).toBe(false);
  });

  it('OTC: нове доливається; дірка після сну — просимо всю історію', () => {
    let t = SAT;
    const { f, create } = fakeOtc();
    const e = new Engine({ now: () => t, createOtcFeed: create });
    f.onData(43, { bars: history(t), quote: { t, value: 1.2 }, note: '' }, true);
    t += 15_000;
    const b = Math.floor(t / 15000) * 15000;
    f.onData(43, { bars: [bar(b - 15000, 1.2), bar(b, 1.21)], quote: { t, value: 1.21 }, note: '' }, false);
    expect(e.candles('otc-43').at(-1)).toMatchObject({ t: b, c: 1.21 });
    expect(f.refreshed).toEqual([]);
    t += 10 * 60_000; // WebView спав, події загубились
    f.onData(43, { bars: [bar(Math.floor(t / 15000) * 15000, 1.22)], quote: { t, value: 1.22 }, note: '' }, false);
    expect(f.refreshed).toEqual([43]);
  });

  it('OTC: свічки з годинника сервера, що поспішає, не ламають порядок', () => {
    let t = Date.parse('2026-10-03T12:00:14Z');
    const { f, create } = fakeOtc();
    const e = new Engine({ now: () => t, createOtcFeed: create });
    f.onData(43, { bars: [bar(Date.parse('2026-10-03T12:00:00Z'), 1.2), bar(Date.parse('2026-10-03T12:00:15Z'), 1.2)], quote: { t, value: 1.2 } }, true);
    t += 2000;
    e.tick();
    const ts = e.candles('otc-43').map((k) => k.t);
    expect(ts).toEqual([...ts].sort((a, b) => a - b));
    expect(new Set(ts).size).toBe(ts.length);
  });

  it('фід OTC працює лише в OTC-сесію, поки рушій запущено; зміна набору пар', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const { f, create } = fakeOtc();
    const e = new Engine({ now: () => SAT, createOtcFeed: create, createSpotFeed: () => ({ setKey() {}, setSymbols() {}, start() {}, stop() {} }) });
    try {
      expect(f.started).toBe(false);
      e.start();
      expect(f.started).toBe(true);
      e.setOtcPairs(otcAssetsFor([{ binariumId: 47, symbol: 'USD/JPY OTC', digits: 3 }, { binariumId: 99, symbol: 'AUD/CAD OTC', digits: 5 }]));
      expect(f.ids).toEqual([47, 99]);
      expect(e.otcPairs()).toEqual([
        { binariumId: 47, symbol: 'USD/JPY OTC', digits: 3 },
        { binariumId: 99, symbol: 'AUD/CAD OTC', digits: 5 },
      ]);
      e.resyncOtc();
      expect(f.refreshed).toEqual(['all']);
    } finally {
      e.stop();
      vi.useRealTimers();
    }
    expect(f.started).toBe(false);
  });

  it('не більше 3 активних і пауза пари, історія ≤140', () => {
    let t = SAT;
    const { f, create } = fakeOtc();
    const e = new Engine({ now: () => t, createOtcFeed: create });
    // Ціни з трендами на кожній з чотирьох пар — сигнали мають з’являтися.
    let seed = 7;
    const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    const ps = { 43: 1.17, 46: 1.34, 47: 147.2, 48: 2650 };
    const drift = {};
    for (const id of f.ids) f.onData(id, { bars: history(t, ps[id]), quote: { t, value: ps[id] } }, true);
    const created = [];
    e.subscribe((ev) => ev.type === 'signal' && created.push(ev.signal));
    for (let i = 0; i < 3000; i++) {
      t += 280;
      for (const id of f.ids) {
        if (i % 300 === 0) drift[id] = (rnd() < 0.5 ? -1 : 1) * ps[id] * 3e-6;
        ps[id] += drift[id] + (rnd() - 0.5) * ps[id] * 4e-6;
        if (i % 9 === 0) f.onData(id, { bars: [], quote: { t, value: ps[id] } }, false);
      }
      e.tick();
      expect(e.signals.length).toBeLessThanOrEqual(3);
    }
    expect(e.candles('otc-43').length).toBeLessThanOrEqual(140);
    expect(created.length).toBeGreaterThan(0);
    expect(created.every((s) => s.confidence >= 74)).toBe(true);
    const ind = created.filter((s) => s.strategy === 'indicators');
    expect(ind.every((s) => Math.abs(s.maBuy + s.techBuy - s.maSell - s.techSell) >= 12)).toBe(true);
    const byPair = {};
    for (const s of created) (byPair[s.assetId] ??= []).push(s);
    for (const list of Object.values(byPair))
      for (let i = 1; i < list.length; i++)
        expect(list[i].createdAt - list[i - 1].expiresAt).toBeGreaterThanOrEqual(240_000 - 280);
  }, 30_000); // повільні раннери CI не повинні валити тест по таймауту
});

describe('OTC: опитувач і вибір пар', () => {
  const NOW = Date.parse('2026-10-03T12:00:00Z');
  const flush = () => new Promise((r) => setTimeout(r, 0));
  const res = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
  afterEach(() => vi.unstubAllGlobals());

  it('опитувач: спершу вся історія, далі лише нове; refresh — знову вся', async () => {
    let t = NOW;
    const calls = [];
    const got = [];
    const p = new BinariumPoller({
      now: () => t,
      fetchImpl: async (id, from, now) => (calls.push({ id, from, now }), { bars: [], quote: { t: now, value: 1 } }),
      onData: (id, d, full) => got.push(full),
      onError: () => {},
    });
    p.setIds([43]);
    p.poll();
    await flush();
    expect(calls[0].from).toBe(NOW - 32 * 60_000);
    t += 2500;
    p.poll();
    await flush();
    expect(calls[1].from).toBe(NOW - 30_000);
    p.refresh();
    p.poll();
    await flush();
    expect(calls[2].from).toBe(t - 32 * 60_000);
    expect(got).toEqual([true, false, true]);
  });

  it('спільний розбір відповіді: так само для сирих відповідей нативної служби', () => {
    const d = buildFeed({ candles: [{ data: [{ open: 1, close: 1.1, timestamp: NOW / 1000 }] }], quotes: { data: [{ value: 1.1, timestamp: NOW / 1000 }] } }, NOW);
    expect(d.quote.value).toBe(1.1);
    expect(d.bars).toHaveLength(1);
    expect(() => buildFeed({ candleError: 'HTTP 401', quoteError: 'HTTP 401' }, NOW)).toThrow('HTTP 401');
  });

  it('назви й збережений вибір', () => {
    expect(otcSymbol('EUR/USD (OTC)')).toBe('EUR/USD OTC');
    expect(otcSymbol('GOLD (OTC)')).toBe('GOLD OTC');
    expect(otcAssetsFor(null).map((a) => a.binariumId)).toEqual([43, 46, 47, 48]);
    expect(otcAssetsFor([{ binariumId: 'x' }]).map((a) => a.binariumId)).toEqual([43, 46, 47, 48]);
    expect(digitsOf([147.123, 147.1])).toBe(3);
    expect(digitsOf([2650.5])).toBe(2);
  });

  it('«Перевірити»: список активів Binarium → лише OTC → по яких ідуть ціни', async () => {
    const seen = [];
    vi.stubGlobal('fetch', async (url) => {
      seen.push(url);
      if (url.includes('/api/v1/assets?'))
        return res({ data: [{ id: 1, name: 'EUR/USD' }, { id: 43, name: 'EUR/USD (OTC)' }, { id: 48, name: 'GOLD (OTC)' }, { id: 77, name: 'APPLE (OTC)' }] });
      const id = Number(url.match(/assets\/(\d+)\/quotes/)[1]);
      if (id === 77) return res({ data: [] }); // цін немає
      const v = id === 48 ? [2650.5, 2650.75] : [1.17123, 1.1712];
      return res({ data: v.map((value, i) => ({ value, timestamp: NOW / 1000 - i })) });
    });
    const progress = [];
    const r = await probeOtc({ now: () => NOW, onProgress: (d, n) => progress.push(`${d}/${n}`) });
    expect(seen[0]).toContain('pagination%5Blimit%5D=250');
    expect(r.ok).toEqual([
      { binariumId: 43, symbol: 'EUR/USD OTC', digits: 5 },
      { binariumId: 48, symbol: 'GOLD OTC', digits: 2 },
    ]);
    expect(r.fail).toEqual(['APPLE OTC']);
    expect(progress.at(-1)).toBe('3/3');
    expect(r.error).toBe('');
  });

  it('думка більшості: частка ВГОРУ з GetRatio; «Unauthorized» — помилка', async () => {
    vi.stubGlobal('fetch', async (url) => {
      expect(url).toBe('/binarium/rpc/v1.Opinion.GetRatio?asset=43');
      return res({ data: { value: 0.74233503144493 }, error: null });
    });
    expect(await fetchOpinion(43)).toBeCloseTo(0.742, 3);
    vi.stubGlobal('fetch', async () => res({ data: null, error: { message: 'Unauthorized' } }));
    await expect(fetchOpinion(43)).rejects.toThrow('Unauthorized');
  });

  it('сигнал пам’ятає натовп: за чи проти', () => {
    const ev = { direction: 'call', confidence: 80, buy: 20, sell: 2, total: 25, maBuy: 10, maSell: 1, techBuy: 10, techSell: 1, trendScore: 80 };
    const a = { id: 'otc-43', symbol: 'EUR/USD OTC' };
    expect(createSignal({ asset: a, votes: [], ev, price: 1, now: 0, crowd: 0.74 })).toMatchObject({ crowd: 0.74, withCrowd: true });
    expect(createSignal({ asset: a, votes: [], ev, price: 1, now: 0, crowd: 0.3 }).withCrowd).toBe(false);
    expect(createSignal({ asset: a, votes: [], ev, price: 1, now: 0 }).withCrowd).toBeNull();
  });

  it('«Перевірити»: список активів не прийшов — перевіряємо відомі пари', async () => {
    vi.stubGlobal('fetch', async (url) =>
      url.includes('/api/v1/assets?') ? res({}, 401) : res({ data: [{ value: 1.17, timestamp: NOW / 1000 }] }),
    );
    const r = await probeOtc({ now: () => NOW });
    expect(r.error).toContain('HTTP 401');
    expect(r.ok.map((x) => x.binariumId)).toEqual([43, 46, 47, 48]);
  });
});

describe('Twelve Data', () => {
  const fakeFeed = () => {
    const f = { key: '', started: false };
    const create = (opts) => {
      Object.assign(f, opts);
      return {
        get key() {
          return f.key;
        },
        setKey: (k) => (f.key = k),
        setSymbols: (list) => (f.symbols = list),
        start: () => (f.started = true),
        stop: () => (f.started = false),
      };
    };
    return { f, create };
  };

  it('у будні за замовчуванням лише EUR/USD, без симуляції: до перших цін історії немає', () => {
    const t = Date.parse('2026-09-29T10:00:00Z');
    const { create } = fakeFeed();
    const e = new Engine({ now: () => t, createSpotFeed: create });
    const snap = e.snapshot();
    expect(snap.assets.map((a) => a.symbol)).toEqual(['EUR/USD']);
    expect(snap.assets.every((a) => a.price == null && !a.live && a.ev == null)).toBe(true);
    expect(e.candles('eurusd')).toEqual([]);
  });

  it('живі ціни → свічки, розігрів 52 свічки, обрив фіду зупиняє сигнали', () => {
    let t = Date.parse('2026-09-29T10:00:00Z');
    const { f, create } = fakeFeed();
    const e = new Engine({ now: () => t, createSpotFeed: create });
    const created = [];
    e.subscribe((ev) => ev.type === 'signal' && created.push(ev.signal));
    let p = 1.17;
    // 12 хв рівного росту: усі індикатори «вгору», але до 52 свічок сигналів бути не може.
    for (let i = 0; i < (12 * 60_000) / 280; i++) {
      t += 280;
      p += 0.000002;
      f.onPrice('EUR/USD', p);
      e.tick();
    }
    expect(e.snapshot().assets[0].live).toBe(true);
    expect(e.snapshot().assets[0].ev).toBeNull();
    expect(created).toEqual([]);
    for (let i = 0; i < (3 * 60_000) / 280; i++) {
      t += 280;
      p += 0.000002;
      f.onPrice('EUR/USD', p);
      e.tick();
    }
    expect(e.candles('eurusd').length).toBeGreaterThanOrEqual(52);
    expect(created.length).toBe(1);
    expect(created[0]).toMatchObject({ assetId: 'eurusd', direction: 'call' });
    // Фід замовк на 31 с — пара офлайн.
    t += 31_000;
    e.tick();
    expect(e.snapshot().assets[0].live).toBe(false);
  });

  it('ключ передається у фід, фід вмикається лише в будні', () => {
    const t = Date.parse('2026-09-29T10:00:00Z');
    const { f, create } = fakeFeed();
    const e = new Engine({ now: () => t, createSpotFeed: create });
    e.setTwelveDataKey('abc');
    expect(f.key).toBe('abc');
    e.start();
    expect(f.started).toBe(true);
    e.stop();
    expect(f.started).toBe(false);
  });

  it('клієнт WebSocket: підписка, ціни, відмова тарифу', () => {
    const sent = [];
    let sock;
    class FakeWS {
      constructor(url) {
        this.url = url;
        sock = this;
      }
      send(m) {
        sent.push(JSON.parse(m));
      }
      close() {}
    }
    const prices = [];
    const statuses = [];
    const feed = new TwelveDataFeed({
      symbols: ['EUR/USD', 'GBP/USD'],
      onPrice: (sym, p) => prices.push([sym, p]),
      onStatus: (st, msg) => statuses.push([st, msg]),
      WebSocketImpl: FakeWS,
    });
    feed.start();
    expect(statuses.at(-1)[0]).toBe('nokey');
    feed.setKey('k1');
    expect(sock.url).toContain('apikey=k1');
    sock.onopen();
    expect(sent[0]).toEqual({ action: 'subscribe', params: { symbols: 'EUR/USD,GBP/USD' } });
    sock.onmessage({ data: JSON.stringify({ event: 'price', symbol: 'EUR/USD', price: 1.1712, timestamp: 1790000000 }) });
    expect(prices).toEqual([['EUR/USD', 1.1712]]);
    expect(statuses.at(-1)[0]).toBe('live');
    sock.onmessage({ data: JSON.stringify({ event: 'subscribe-status', status: 'error', fails: [{ symbol: 'GBP/USD' }] }) });
    expect(statuses.at(-1)).toEqual(['error', 'тариф не дає: GBP/USD']);
    feed.stop();
  });

  it('кілька ключів: працює перший, при відмові бере наступний', () => {
    const socks = [];
    class FakeWS {
      constructor(url) {
        this.url = url;
        socks.push(this);
      }
      send() {}
      close() {}
    }
    const statuses = [];
    const feed = new TwelveDataFeed({
      symbols: ['EUR/USD'],
      onPrice: () => {},
      onStatus: (st, msg) => statuses.push([st, msg]),
      WebSocketImpl: FakeWS,
    });
    expect(parseKeys('"aaa",\n "bbb"\nccc aaa')).toEqual(['aaa', 'bbb', 'ccc']);
    feed.setKey('aaa\nbbb\nccc');
    feed.start();
    expect(socks).toHaveLength(1);
    expect(socks[0].url).toContain('apikey=aaa');
    socks[0].onmessage({ data: JSON.stringify({ status: 'error', message: 'invalid api key' }) });
    expect(socks.at(-1).url).toContain('apikey=bbb');
    expect(statuses.some(([st, msg]) => st === 'error' && msg.includes('ключ 1 з 3'))).toBe(true);
    // Другий ключ працює — залишаємось на ньому, третій не чіпаємо.
    socks.at(-1).onmessage({ data: JSON.stringify({ event: 'price', symbol: 'EUR/USD', price: 1.17 }) });
    expect(statuses.at(-1)).toEqual(['live', 'ключ 2 з 3']);
    expect(socks).toHaveLength(2);
    feed.stop();
  });

  it('тариф дає лише частину пар: ключ не відкидаємо, недоступну пару позначаємо', () => {
    const socks = [];
    class FakeWS {
      constructor(url) {
        this.url = url;
        socks.push(this);
      }
      send() {}
      close() {}
    }
    const statuses = [];
    let unavailable = null;
    const feed = new TwelveDataFeed({
      symbols: ['EUR/USD', 'GBP/USD'],
      onPrice: () => {},
      onStatus: (st, msg) => statuses.push([st, msg]),
      onUnavailable: (list) => (unavailable = list),
      WebSocketImpl: FakeWS,
    });
    feed.setKey('k1\nk2');
    feed.start();
    socks[0].onmessage({
      data: JSON.stringify({
        event: 'subscribe-status',
        status: 'error',
        success: [{ symbol: 'EUR/USD' }],
        fails: [{ symbol: 'GBP/USD' }],
      }),
    });
    expect(socks).toHaveLength(1); // на другий ключ не перейшли
    expect(unavailable).toEqual(['GBP/USD']);
    socks[0].onmessage({ data: JSON.stringify({ event: 'price', symbol: 'EUR/USD', price: 1.17 }) });
    expect(statuses.at(-1)).toEqual(['live', 'ключ 1 з 2 · тариф не дає: GBP/USD']);
    feed.stop();
  });

  it('рушій показує пару, яку тариф не дає', () => {
    const t = Date.parse('2026-09-29T10:00:00Z');
    let opts;
    const e = new Engine({
      now: () => t,
      createSpotFeed: (o) => ((opts = o), { setKey() {}, setSymbols() {}, start() {}, stop() {} }),
    });
    e.setSpotPairs(spotAssetsFor(['EUR/USD', 'USD/JPY']));
    opts.onUnavailable(['USD/JPY']);
    const [eur, jpy] = e.snapshot().assets;
    expect(eur.unavailable).toBe(false);
    expect(jpy.unavailable).toBe(true);
  });

  it('зміна набору пар: стара історія лишається, нова пара з нуля, фід підписується заново', () => {
    let t = Date.parse('2026-09-29T10:00:00Z');
    const { f, create } = fakeFeed();
    const e = new Engine({ now: () => t, createSpotFeed: create });
    for (let i = 0; i < 100; i++) {
      t += 280;
      f.onPrice('EUR/USD', 1.17 + i * 1e-5);
      e.tick();
    }
    const had = e.candles('eurusd').length;
    e.setSpotPairs(spotAssetsFor(['EUR/USD', 'AUD/USD']));
    expect(f.symbols).toEqual(['EUR/USD', 'AUD/USD']);
    expect(e.candles('eurusd').length).toBe(had);
    expect(e.candles('audusd')).toEqual([]);
    e.setSpotPairs(spotAssetsFor(['AUD/USD']));
    expect(e.snapshot().assets.map((a) => a.symbol)).toEqual(['AUD/USD']);
  });

  it('збережені свічки: коротка перерва — продовжуємо, довга — розігрів наново', () => {
    const t = Date.parse('2026-09-29T10:00:00Z');
    const candles = Array.from({ length: 60 }, (_, i) => ({ t: t - (60 - i) * 15000, o: 1.17, h: 1.171, l: 1.169, c: 1.17 }));
    const { create } = fakeFeed();
    const fresh = new Engine({ now: () => t, createSpotFeed: create, savedCandles: { 'EUR/USD': candles } });
    expect(fresh.candles('eurusd')).toHaveLength(60);
    expect(fresh.snapshot().assets[0].warmup.have).toBe(52);
    const stale = new Engine({ now: () => t + 5 * 60_000, createSpotFeed: create, savedCandles: { 'EUR/USD': candles } });
    expect(stale.candles('eurusd')).toEqual([]);
  });

  it('перевірка пар: пачки по 8, збирає success і fails', async () => {
    const batches = [];
    class FakeWS {
      constructor(url) {
        this.url = url;
        setTimeout(() => this.onopen(), 0);
      }
      send(m) {
        const list = JSON.parse(m).params.symbols.split(',');
        batches.push(list);
        const success = list.filter((x) => x === 'EUR/USD' || x === 'AUD/JPY').map((symbol) => ({ symbol }));
        const fails = list.filter((x) => x !== 'EUR/USD' && x !== 'AUD/JPY').map((symbol) => ({ symbol }));
        setTimeout(() => this.onmessage({ data: JSON.stringify({ event: 'subscribe-status', status: 'error', success, fails }) }), 0);
      }
      close() {}
    }
    const symbols = ['EUR/USD', 'GBP/USD', 'USD/JPY', 'USD/CHF', 'AUD/USD', 'USD/CAD', 'NZD/USD', 'EUR/GBP', 'EUR/JPY', 'AUD/JPY'];
    const res = await probePairs({ key: 'k', symbols, WebSocketImpl: FakeWS });
    expect(batches.map((b) => b.length)).toEqual([8, 2]);
    expect(res.ok).toEqual(['EUR/USD', 'AUD/JPY']);
    expect(res.fail).toHaveLength(8);
    expect(res.error).toBe('');
  });

  it('рушій ставить основний фід на паузу під час перевірки', async () => {
    const t = Date.parse('2026-09-29T10:00:00Z');
    const { f, create } = fakeFeed();
    let startedDuring = null;
    const e = new Engine({
      now: () => t,
      createSpotFeed: create,
      probe: async () => ((startedDuring = f.started), { ok: ['EUR/USD'], fail: [], error: '' }),
    });
    expect((await e.probePairs(['EUR/USD'])).error).toBe('спершу вставте ключ');
    e.setTwelveDataKey('k');
    e.start();
    const res = await e.probePairs(['EUR/USD']);
    expect(startedDuring).toBe(false);
    expect(f.started).toBe(true);
    expect(res.ok).toEqual(['EUR/USD']);
    e.stop();
  });

  it('свічки від нативної служби: беремо новіші або довші, старі ігноруємо', () => {
    let t = Date.parse('2026-09-29T10:00:00Z');
    const { f, create } = fakeFeed();
    const e = new Engine({ now: () => t, createSpotFeed: create });
    for (let i = 0; i < 20; i++) {
      t += 1000;
      f.onPrice('EUR/USD', 1.17);
      e.tick();
    }
    const mine = e.candles('eurusd').length;
    const native = Array.from({ length: 60 }, (_, i) => ({ t: Math.floor(t / 15000) * 15000 - (59 - i) * 15000, o: 1.17, h: 1.171, l: 1.169, c: 1.1705 }));
    e.restoreSpotCandles({ 'EUR/USD': native });
    expect(e.candles('eurusd')).toHaveLength(60);
    expect(e.priceOf('eurusd')).toBe(1.1705);
    expect(mine).toBeLessThan(60);
    // Застарілі (перерва понад 2 хв) — не беремо.
    const old = native.map((c) => ({ ...c, t: c.t - 10 * 60_000 }));
    e.restoreSpotCandles({ 'EUR/USD': old.slice(0, 70) });
    expect(e.candles('eurusd').at(-1).t).toBe(native.at(-1).t);
  });

  it('пульс служби тікає рушій, лише якщо власний таймер мовчить', () => {
    let t = Date.parse('2026-09-29T10:00:00Z');
    const { create } = fakeFeed();
    const e = new Engine({ now: () => t, createSpotFeed: create });
    let ticks = 0;
    e.subscribe((ev) => ev.type === 'tick' && ticks++);
    e.tick();
    t += 300;
    e.tickIfStale(800);
    expect(ticks).toBe(1);
    t += 600;
    e.tickIfStale(800);
    expect(ticks).toBe(2);
  });
});
