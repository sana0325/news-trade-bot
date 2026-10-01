import { describe, it, expect } from 'vitest';
import { computeVotes, rsi, sma, BUY, SELL } from './indicators.js';
import { evaluate, isStrong, resolveSignal, createSignal, SIGNAL_MS } from './signal.js';
import { isForexOpen, nextSessionChange, kyivDateTime } from './session.js';
import { parseCandles, parseLastQuote, toBars } from './binarium.js';
import { Engine } from './engine.js';
import { OTC_ASSETS } from './assets.js';
import { TwelveDataFeed, parseKeys } from './twelvedata.js';

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
  it('терпимо читає свічки й котирування', () => {
    const c = parseCandles({ data: [{ time: 30, open: '1', high: 2, low: 0.5, close: 1.5 }, [15, 1, 1, 1, 1]] });
    expect(c.map((x) => x.t)).toEqual([15000, 30000]);
    expect(parseLastQuote([{ value: 1.1, time: 1 }, { value: 1.2, time: 2 }]).value).toBe(1.2);
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
  it('не більше 3 активних і пауза пари, історія 128 → ≤140', () => {
    let t = Date.parse('2026-10-03T12:00:00Z'); // субота: OTC без фіду працює на симуляції
    const e = new Engine({ now: () => t, fetchFeed: async () => null });
    expect(e.candles('eurusd-otc')).toHaveLength(128);
    const created = [];
    e.subscribe((ev) => ev.type === 'signal' && created.push(ev.signal));
    for (let i = 0; i < 3000; i++) {
      t += 280;
      e.tick();
      expect(e.signals.length).toBeLessThanOrEqual(3);
    }
    expect(e.candles('eurusd-otc').length).toBeLessThanOrEqual(140);
    expect(created.every((s) => s.confidence >= 74)).toBe(true);
    const ind = created.filter((s) => s.strategy === 'indicators');
    expect(ind.every((s) => Math.abs(s.maBuy + s.techBuy - s.maSell - s.techSell) >= 12)).toBe(true);
    const byPair = {};
    for (const s of created) (byPair[s.assetId] ??= []).push(s);
    for (const list of Object.values(byPair))
      for (let i = 1; i < list.length; i++)
        expect(list[i].createdAt - list[i - 1].expiresAt).toBeGreaterThanOrEqual(240_000 - 280);
  }, 30_000); // повільні раннери CI не повинні валити тест по таймауту

  it('у суботу лише OTC і фід підхоплюється', async () => {
    let t = Date.parse('2026-10-03T12:00:00Z');
    const feed = async () => ({
      bars: [{ t: Math.floor((t - 30000) / 15000) * 15000, o: 1.2, h: 1.21, l: 1.19, c: 1.2 }],
      quote: { t, value: 1.2005 },
    });
    const e = new Engine({ now: () => t, fetchFeed: feed });
    expect(e.snapshot().assets.map((a) => a.id)).toEqual(OTC_ASSETS.map((a) => a.id));
    e.poll();
    await new Promise((r) => setTimeout(r, 0));
    expect(e.priceOf('eurusd-otc')).toBe(1.2005);
    t += 280;
    e.tick();
    expect(e.snapshot().assets[0].live).toBe(true);
  });
});

describe('Twelve Data', () => {
  const fakeFeed = () => {
    const f = { key: '', started: false };
    const create = (opts) => {
      Object.assign(f, opts);
      return {
        setKey: (k) => (f.key = k),
        start: () => (f.started = true),
        stop: () => (f.started = false),
      };
    };
    return { f, create };
  };

  it('у будні лише EUR/USD і USD/JPY, без симуляції: до перших цін історії немає', () => {
    const t = Date.parse('2026-09-29T10:00:00Z');
    const { create } = fakeFeed();
    const e = new Engine({ now: () => t, createSpotFeed: create });
    const snap = e.snapshot();
    expect(snap.assets.map((a) => a.symbol)).toEqual(['EUR/USD', 'USD/JPY']);
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
      createSpotFeed: (o) => ((opts = o), { setKey() {}, start() {}, stop() {} }),
    });
    opts.onUnavailable(['USD/JPY']);
    const [eur, jpy] = e.snapshot().assets;
    expect(eur.unavailable).toBe(false);
    expect(jpy.unavailable).toBe(true);
  });
});
