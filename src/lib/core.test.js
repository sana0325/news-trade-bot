import { describe, it, expect } from 'vitest';
import { computeVotes, rsi, sma, BUY, SELL } from './indicators.js';
import { evaluate, isStrong, resolveSignal, createSignal, SIGNAL_MS } from './signal.js';
import { isForexOpen, nextSessionChange, kyivDateTime } from './session.js';
import { parseCandles, parseLastQuote, toBars } from './binarium.js';
import { Engine } from './engine.js';
import { OTC_ASSETS } from './assets.js';

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
    let t = Date.parse('2026-09-29T10:00:00Z');
    const e = new Engine({ now: () => t, fetchFeed: async () => null });
    expect(e.candles('eurusd')).toHaveLength(128);
    const created = [];
    e.subscribe((ev) => ev.type === 'signal' && created.push(ev.signal));
    for (let i = 0; i < 3000; i++) {
      t += 280;
      e.tick();
      expect(e.signals.length).toBeLessThanOrEqual(3);
    }
    expect(e.candles('eurusd').length).toBeLessThanOrEqual(140);
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
