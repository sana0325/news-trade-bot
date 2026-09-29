import { SIGNAL_MS } from '../lib/signal.js';
import { fmtLeft, fmtPrice, dirWord, dirHint } from '../lib/format.js';
import { Arrow } from './Icons.jsx';

export default function SignalCard({ signal: s, now, price, digits, onOpen }) {
  const call = s.direction === 'call';
  const left = s.expiresAt - now;
  const progress = Math.min(1, Math.max(0, left / SIGNAL_MS));
  const against = s.maBuy + s.maSell + s.techBuy + s.techSell - s.agreement;
  const neutral = s.total - s.agreement - against;
  const maFor = call ? s.maBuy : s.maSell;
  const maAgainst = call ? s.maSell : s.maBuy;
  const oscFor = call ? s.techBuy : s.techSell;
  const oscAgainst = call ? s.techSell : s.techBuy;
  const move = price == null ? 0 : (price - s.entry) * (call ? 1 : -1);

  return (
    <article className={`card ${call ? 'card-up' : 'card-down'}`}>
      <div className="card-top">
        <button type="button" className="card-pair" onClick={onOpen} title="Показати графік">
          {s.symbol}
        </button>
        <div className="timer tnum" aria-label={`Лишилось ${fmtLeft(left)}`}>
          {fmtLeft(left)}
        </div>
      </div>

      <div className="card-dir">
        <span className={`dir ${call ? 'up' : 'down'}`}>
          <Arrow dir={s.direction} size={28} /> {dirWord(s.direction)}
        </span>
        <span className="dir-hint">{dirHint(s.direction)}</span>
      </div>

      <div className="bar" aria-hidden="true">
        <div className="bar-fill" style={{ width: `${progress * 100}%` }} />
      </div>

      <div className="stats">
        <div className="stat">
          <div className="stat-num tnum">{s.confidence}%</div>
          <div className="stat-label">впевненість</div>
        </div>
        <div className="stat">
          <div className="stat-num tnum">
            <span className="up-text">{s.agreement}</span>
            <span className="muted"> / </span>
            <span className="down-text">{against}</span>
          </div>
          <div className="stat-label">за / проти</div>
        </div>
        <div className="stat">
          <div className="stat-num tnum muted">{neutral}</div>
          <div className="stat-label">нейтрально</div>
        </div>
      </div>

      <div className="groups">
        <div className="group-line">
          <span>Середні</span>
          <span className="tnum"><span className="up-text">{maFor} за</span> · <span className="down-text">{maAgainst} проти</span></span>
        </div>
        <div className="group-line">
          <span>Осцилятори</span>
          <span className="tnum"><span className="up-text">{oscFor} за</span> · <span className="down-text">{oscAgainst} проти</span></span>
        </div>
      </div>

      <ul className="reasons">
        {s.reasons.map((r) => (
          <li key={r}>{r}</li>
        ))}
      </ul>

      <div className="card-foot tnum">
        <span>Вхід {fmtPrice(s.entry, digits)}</span>
        <span className={move > 0 ? 'up-text' : move < 0 ? 'down-text' : 'muted'}>
          Зараз {fmtPrice(price, digits)}
        </span>
      </div>
    </article>
  );
}
