import { emaSeries } from '../lib/indicators.js';
import { kyivClock } from '../lib/session.js';
import { CANDLE_MS } from '../lib/market.js';
import { STRATEGY_LABEL } from '../lib/patterns.js';
import { fmtPrice, fmtLeft, dirWord } from '../lib/format.js';

const SHOW = 72;
const W = 360;
const H = 220;
const PAD = { l: 4, r: 60, t: 10, b: 20 };
const EMA_N = 20;

export default function Chart({ asset, candles, signal, now }) {
  const all = candles;
  const ema = emaSeries(all.map((c) => c.c), EMA_N);
  const start = Math.max(0, all.length - SHOW);
  const view = all.slice(start);
  const emaView = ema.slice(start);
  const price = view[view.length - 1]?.c;

  let lo = Infinity;
  let hi = -Infinity;
  for (const c of view) {
    lo = Math.min(lo, c.l);
    hi = Math.max(hi, c.h);
  }
  if (signal) {
    lo = Math.min(lo, signal.entry);
    hi = Math.max(hi, signal.entry);
    if (signal.pattern?.type === 'level') {
      lo = Math.min(lo, signal.pattern.price);
      hi = Math.max(hi, signal.pattern.price);
    }
  }
  const span = hi - lo || hi * 1e-4 || 1;
  lo -= span * 0.06;
  hi += span * 0.06;

  const plotW = W - PAD.l - PAD.r;
  const plotH = H - PAD.t - PAD.b;
  const step = plotW / SHOW;
  const x = (i) => PAD.l + (SHOW - view.length + i) * step + step / 2;
  const y = (v) => PAD.t + ((hi - v) / (hi - lo)) * plotH;
  const body = Math.max(1, step * 0.62);

  const grid = Array.from({ length: 5 }, (_, i) => lo + ((hi - lo) * (i + 0.5)) / 5);
  const emaPath = emaView
    .map((v, i) => (v == null ? null : `${x(i).toFixed(1)},${y(v).toFixed(1)}`))
    .filter(Boolean)
    .join(' L');
  const up = view.length > 1 && price >= view[view.length - 2].c;
  // Межі клина: лінія від t0 до останньої свічки (обрізана по лівому краю графіка).
  const t0View = view[0]?.t ?? 0;
  const xt = (t) => x((t - t0View) / CANDLE_MS);
  const wedgeLine = (ln) => {
    const k = (ln.p1 - ln.p0) / (ln.t1 - ln.t0 || 1);
    const ta = Math.max(ln.t0, t0View);
    const tb = view[view.length - 1]?.t ?? ln.t1;
    const at = (t) => ln.p0 + k * (t - ln.t0);
    return { x1: xt(ta), y1: y(at(ta)), x2: xt(tb), y2: y(at(tb)) };
  };
  const pat = signal?.pattern;
  const times = view.map((c, i) => ({ i, t: c.t })).filter(({ t }) => t % 180_000 === 0);

  return (
    <section className="section" id="chart">
      <div className="section-head">
        <h2>{asset.symbol}</h2>
        <span className="muted small">
          свічки 15 с · EMA {EMA_N} · {asset.market === 'otc' ? (asset.live ? 'фід Binarium' : 'симуляція') : 'симуляція'}
        </span>
      </div>
      {signal && (
        <p className={`chart-sig small ${signal.direction === 'call' ? 'up-text' : 'down-text'}`}>
          {STRATEGY_LABEL[signal.strategy]}: {dirWord(signal.direction)} · лишилось {fmtLeft(signal.expiresAt - now)} · пунктир — вхід
          {pat?.type === 'level' && ', синя лінія — рівень'}
          {pat?.type === 'wedge' && ', сині лінії — клин'}
        </p>
      )}
      <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Графік ${asset.symbol}, ціна ${fmtPrice(price, asset.digits)}`}>
        {grid.map((g) => (
          <g key={g}>
            <line x1={PAD.l} x2={W - PAD.r} y1={y(g)} y2={y(g)} className="grid" />
            {(price == null || Math.abs(y(g) - y(price)) > 12) && (
              <text x={W - PAD.r + 4} y={y(g) + 3} className="axis">
                {fmtPrice(g, asset.digits)}
              </text>
            )}
          </g>
        ))}
        {times.map(({ i, t }) => (
          <g key={t}>
            <line x1={x(i)} x2={x(i)} y1={PAD.t} y2={H - PAD.b} className="grid" />
            <text x={x(i)} y={H - 6} className="axis" textAnchor="middle">
              {kyivClock(t).slice(0, 5)}
            </text>
          </g>
        ))}
        {view.map((c, i) => {
          const rising = c.c >= c.o;
          const top = y(Math.max(c.o, c.c));
          const h = Math.max(1, y(Math.min(c.o, c.c)) - top);
          return (
            <g key={c.t} className={rising ? 'cu' : 'cd'}>
              <line x1={x(i)} x2={x(i)} y1={y(c.h)} y2={y(c.l)} />
              <rect x={x(i) - body / 2} y={top} width={body} height={h} />
            </g>
          );
        })}
        {emaPath && <path d={`M${emaPath}`} className="ema" />}
        {pat?.type === 'level' && (
          <line x1={PAD.l} x2={W - PAD.r} y1={y(pat.price)} y2={y(pat.price)} className="pattern" />
        )}
        {pat?.type === 'wedge' && (
          <>
            <line {...wedgeLine(pat.upper)} className="pattern" />
            <line {...wedgeLine(pat.lower)} className="pattern" />
          </>
        )}
        {signal && (
          <line x1={PAD.l} x2={W - PAD.r} y1={y(signal.entry)} y2={y(signal.entry)} className="entry" />
        )}
        {price != null && (
          <g>
            <line x1={PAD.l} x2={W - PAD.r} y1={y(price)} y2={y(price)} className="now-line" />
            <rect x={W - PAD.r + 1} y={y(price) - 8} width={PAD.r - 2} height={16} rx={3} className={up ? 'tag-up' : 'tag-down'} />
            <text x={W - PAD.r + 4} y={y(price) + 3.5} className="axis axis-now">
              {fmtPrice(price, asset.digits)}
            </text>
          </g>
        )}
      </svg>
    </section>
  );
}
