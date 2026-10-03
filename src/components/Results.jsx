import { RESULT_TEXT } from '../lib/signal.js';
import { fmtPrice, dirWord } from '../lib/format.js';
import { Arrow } from './Icons.jsx';
import { STRATEGY_LABEL } from '../lib/patterns.js';

// «За натовпом: 5 з 8 (63%)» — як часто вгадують OTC-сигнали за і проти думки більшості.
function rate({ hit, miss, flat }) {
  const n = hit + miss + flat;
  return n ? `${hit} з ${n} (${Math.round((hit / n) * 100)}%)` : 'ще немає';
}

export default function Results({ results, engine, crowdStats }) {
  const hasCrowd = crowdStats && Object.values(crowdStats).some((x) => x.hit + x.miss + x.flat > 0);
  if (!results.length && !hasCrowd) return null;
  return (
    <section className="section">
      <div className="section-head">
        <h2>Минулі сигнали</h2>
        <span className="muted small">через 5 хвилин</span>
      </div>
      {hasCrowd && (
        <div className="groups">
          <div className="group-line">
            <span>За натовпом Binarium</span>
            <span className="tnum">{rate(crowdStats.with)}</span>
          </div>
          <div className="group-line">
            <span>Проти натовпу</span>
            <span className="tnum">{rate(crowdStats.against)}</span>
          </div>
        </div>
      )}
      <ul className="results">
        {results.map((r) => {
          const d = engine.asset(r.assetId)?.digits ?? 5;
          return (
            <li key={r.id} className="result">
              <div className="result-main">
                <span className="result-pair">{r.symbol}</span>
                <span className={`mini ${r.direction === 'call' ? 'up-text' : 'down-text'}`}>
                  <Arrow dir={r.direction} size={14} /> {dirWord(r.direction)}
                </span>
                <span className="muted small">{STRATEGY_LABEL[r.strategy]}</span>
              </div>
              <div className="result-side">
                <span className={`result-text res-${r.status}`}>{RESULT_TEXT[r.status]}</span>
                <span className="muted small tnum">
                  {fmtPrice(r.entry, d)} → {fmtPrice(r.exit, d)}
                </span>
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
