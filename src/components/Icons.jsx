const base = { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true };

export const SoundOn = () => (
  <svg {...base}>
    <path d="M11 5 6 9H3v6h3l5 4z" />
    <path d="M15.5 8.5a5 5 0 0 1 0 7" />
    <path d="M18.5 5.5a9 9 0 0 1 0 13" />
  </svg>
);

export const SoundOff = () => (
  <svg {...base}>
    <path d="M11 5 6 9H3v6h3l5 4z" />
    <path d="m17 9 5 6M22 9l-5 6" />
  </svg>
);

export const Arrow = ({ dir, size = 18 }) => (
  <svg {...base} width={size} height={size} strokeWidth={2.6}>
    {dir === 'call' ? <path d="M12 19V5M5 12l7-7 7 7" /> : <path d="M12 5v14M19 12l-7 7-7-7" />}
  </svg>
);
