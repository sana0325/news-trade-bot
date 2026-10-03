import { fmtPrice } from '../lib/format.js';

export default function PairList({ assets, signals, selected, onSelect }) {
  return (
    <section className="section">
      <div className="section-head">
        <h2>Пари</h2>
        <span className="muted small">за / проти зараз</span>
      </div>
      <div className="pairs">
        {assets.map((a) => {
          const sig = signals.find((s) => s.assetId === a.id);
          const ev = a.ev;
          const lean = ev ? ev.buy - ev.sell : 0;
          return (
            <button
              key={a.id}
              type="button"
              className={`pair ${a.id === selected ? 'pair-on' : ''}`}
              onClick={() => onSelect(a.id)}
              aria-pressed={a.id === selected}
            >
              <span className="pair-row">
                <span className="pair-name">{a.symbol}</span>
                {sig && <span className={`badge ${sig.direction === 'call' ? 'up' : 'down'}`}>сигнал</span>}
                {a.market === 'spot' && !sig && !a.live && <span className="src">офлайн</span>}
                {a.market === 'otc' && !sig && (
                  <span className={`src ${a.live ? 'src-live' : ''}`}>{a.live ? 'фід' : 'офлайн'}</span>
                )}
              </span>
              <span className="pair-row">
                <span className={`pair-price tnum ${a.change > 0 ? 'up-text' : a.change < 0 ? 'down-text' : ''}`}>
                  {fmtPrice(a.price, a.digits)}
                </span>
                <span className={`lean tnum ${lean >= 8 ? 'up-text' : lean <= -8 ? 'down-text' : 'muted'}`}>
                  {ev
                    ? `${ev.buy}/${ev.sell}`
                    : a.unavailable
                      ? 'тариф не дає'
                      : a.price == null
                        ? 'немає цін'
                      : `розігрів ${a.warmup.have}/${a.warmup.need}`}
                </span>
              </span>
              {a.crowd != null && (
                <span className="pair-row muted small tnum">
                  натовп: {Math.round(a.crowd * 100)}% вгору
                </span>
              )}
            </button>
          );
        })}
      </div>
    </section>
  );
}
