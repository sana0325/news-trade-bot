import { useEffect, useState } from 'react';

const DURATION = 2600;

export function Logo({ size = 168 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 168 168" aria-hidden="true">
      <rect width="168" height="168" rx="38" fill="#0a2fd0" />
      <rect x="70" y="40" width="70" height="88" rx="8" fill="#7fa6ff" />
      <path d="M44 28h52l18 18v76a8 8 0 0 1-8 8H44a8 8 0 0 1-8-8V36a8 8 0 0 1 8-8z" fill="#fff" />
      <path d="M96 28v14a6 6 0 0 0 6 6h12z" fill="#c9d8ff" />
      <rect x="50" y="52" width="46" height="6" rx="3" fill="#2b6de8" />
      <rect x="50" y="64" width="50" height="6" rx="3" fill="#2b6de8" />
      <rect x="50" y="76" width="50" height="6" rx="3" fill="#2b6de8" />
      <rect x="28" y="88" width="90" height="28" rx="6" fill="#2b6de8" stroke="#fff" strokeWidth="3" />
      <text x="73" y="108" textAnchor="middle" fill="#fff" fontFamily="Poppins, sans-serif"
        fontWeight="700" fontSize="17">ОФІС</text>
    </svg>
  );
}

function Laurel({ flip }) {
  // Листки вздовж дуги від низу до верху.
  const leaves = Array.from({ length: 7 }, (_, i) => {
    const t = i / 6;
    const a = Math.PI * (1.35 - t * 0.85);
    const x = 60 + Math.cos(a) * 42;
    const y = 72 - Math.sin(a) * 60;
    const rot = (-a * 180) / Math.PI - 90 + 35;
    return <ellipse key={i} cx={x} cy={y} rx="6" ry="13" fill="#f9c22e" transform={`rotate(${rot} ${x} ${y})`} />;
  });
  return (
    <svg width="54" height="120" viewBox="0 0 70 150" style={flip ? { transform: 'scaleX(-1)' } : null}>
      <path d="M58 140 C 20 120, 10 70, 30 16" stroke="#f0a500" strokeWidth="3" fill="none" />
      <g transform="translate(-6 8)">{leaves}</g>
    </svg>
  );
}

export default function Splash({ onDone }) {
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    const start = performance.now();
    let raf;
    const step = (now) => {
      const p = Math.min(1, (now - start) / DURATION);
      setProgress(p);
      if (p < 1) raf = requestAnimationFrame(step);
      else onDone();
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [onDone]);

  return (
    <div className="splash">
      <div className="splash-top">
        <Logo size={150} />
        <h1 className="splash-name">Офіс</h1>
      </div>
      <h2 className="splash-hello">Раді бачити вас<br />на борту!</h2>
      <div className="badge">
        <Laurel />
        <div className="badge-body">
          <div className="badge-big">4 формати</div>
          <div className="badge-small">PDF · WORD · EXCEL · TXT</div>
          <div className="stars">★★★★★</div>
        </div>
        <Laurel flip />
      </div>
      <div className="splash-bottom">
        <p>Документи відкриваються лише на вашому пристрої</p>
        <div className="bar"><div className="bar-fill" style={{ width: `${progress * 100}%` }} /></div>
      </div>
    </div>
  );
}
