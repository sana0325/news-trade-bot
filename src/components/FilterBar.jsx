import { FILTERS } from '../lib/signal.js';

export default function FilterBar({ value, onChange }) {
  return (
    <div className="filter" role="radiogroup" aria-label="Сила сигналу">
      {FILTERS.map((f) => (
        <button
          key={f.value}
          type="button"
          role="radio"
          aria-checked={value === f.value}
          className={`chip ${value === f.value ? 'chip-on' : ''}`}
          onClick={() => onChange(f.value)}
        >
          <span>{f.label}</span>
          <span className="chip-num tnum">{f.value}%+</span>
        </button>
      ))}
    </div>
  );
}
