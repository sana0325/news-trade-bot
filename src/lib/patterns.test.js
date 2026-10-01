import { describe, it, expect } from 'vitest';
import { detectLevelBounce, detectWedgeBreakout, patternConfidence, findPivots } from './patterns.js';

// Пилка між двома лініями top(i) і bottom(i), період 2·half свічок.
function zigzag(n, top, bottom, half = 6) {
  const out = [];
  for (let i = 0; i < n; i++) {
    const ph = i % (2 * half);
    const f = ph < half ? ph / half : 2 - ph / half; // 0 → 1 → 0
    const c = bottom(i) + (top(i) - bottom(i)) * (1 - f);
    const prev = out[i - 1]?.c ?? c;
    out.push({ t: i * 15000, o: prev, h: Math.max(prev, c) + 0.00005, l: Math.min(prev, c) - 0.00005, c });
  }
  return out;
}

const push = (cs, o, h, l, c) => cs.push({ t: cs.length * 15000, o, h, l, c });
const flat = { buy: 10, sell: 10, techBuy: 5, techSell: 5, maBuy: 5, maSell: 5 };

describe('відбиття від рівня', () => {
  const base = () => zigzag(70, () => 1.103, () => 1.1, 6); // 5 дотиків до 1.1000

  it('бачить відбиття від підтримки вгору', () => {
    const cs = base();
    // Ціна сходить до рівня і відскакує свічкою з нижньою тінню.
    push(cs, 1.1009, 1.1013, 1.0999, 1.1012);
    const p = detectLevelBounce(cs);
    expect(p).toMatchObject({ strategy: 'level', direction: 'call' });
    expect(p.level).toBeCloseTo(1.1, 3);
    expect(p.touches).toBeGreaterThanOrEqual(2);
    expect(patternConfidence(p, flat)).toBeGreaterThanOrEqual(74);
  });

  it('бачить відбиття від опору вниз', () => {
    const cs = zigzag(64, () => 1.103, () => 1.1, 6); // закінчується біля вершини
    push(cs, 1.1021, 1.1031, 1.1017, 1.1018);
    const p = detectLevelBounce(cs);
    expect(p).toMatchObject({ strategy: 'level', direction: 'put' });
    expect(p.level).toBeCloseTo(1.103, 3);
  });

  it('пробій рівня — не відбиття', () => {
    const cs = base();
    push(cs, 1.1009, 1.101, 1.0985, 1.0987); // закрилась під рівнем
    expect(detectLevelBounce(cs)).toBeNull();
  });
});

describe('пробій клина', () => {
  // Спадний клин: верхня межа падає швидше за нижню, діапазон звужується.
  const wedge = () => zigzag(60, (i) => 1.106 - 0.0001 * i, (i) => 1.1 - 0.00002 * i, 5);

  it('бачить пробій спадного клина вгору', () => {
    const cs = wedge();
    const last = cs[cs.length - 1].c;
    push(cs, last, 1.1032, last - 0.00005, 1.103);
    const p = detectWedgeBreakout(cs);
    expect(p).toMatchObject({ strategy: 'wedge', direction: 'call', kind: 'falling', classic: true });
    expect(p.squeeze).toBeGreaterThan(0.35);
    expect(patternConfidence(p, flat)).toBeGreaterThanOrEqual(74);
  });

  it('без виходу за межу сигналу немає', () => {
    const cs = wedge();
    expect(detectWedgeBreakout(cs)).toBeNull();
  });

  it('паралельний канал — не клин', () => {
    const cs = zigzag(60, (i) => 1.106 - 0.00005 * i, (i) => 1.1 - 0.00005 * i, 5);
    push(cs, cs[59].c, 1.1065, cs[59].c, 1.1062);
    expect(detectWedgeBreakout(cs)).toBeNull();
  });
});

it('півоти знаходять вершини пилки', () => {
  const { highs, lows } = findPivots(zigzag(40, () => 2, () => 1, 5), 2);
  expect(highs.length).toBeGreaterThanOrEqual(3);
  expect(lows.length).toBeGreaterThanOrEqual(3);
});
