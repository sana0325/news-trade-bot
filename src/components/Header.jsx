import { kyivClock } from '../lib/session.js';
import { SoundOn, SoundOff } from './Icons.jsx';

export default function Header({ otc, now, sound, onSound }) {
  return (
    <header className="header">
      <div className="brand">
        <span className="logo" aria-hidden="true">V</span>
        <span className="brand-name">VEKTOR</span>
      </div>
      <span className={`live ${otc ? 'live-otc' : ''}`}>
        <span className="dot" aria-hidden="true" />
        {otc ? 'LIVE OTC' : 'LIVE'}
      </span>
      <div className="clock tnum" title="Час Києва">
        <span className="muted small">Київ</span> {kyivClock(now)}
      </div>
      <button
        type="button"
        className="icon-btn"
        onClick={onSound}
        aria-pressed={sound}
        aria-label={sound ? 'Вимкнути звук' : 'Увімкнути звук'}
        title={sound ? 'Звук увімкнено' : 'Звук вимкнено'}
      >
        {sound ? <SoundOn /> : <SoundOff />}
      </button>
    </header>
  );
}
