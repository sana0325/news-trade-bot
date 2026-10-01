const OPTIONS = [
  { value: 'all', label: 'Усі стратегії' },
  { value: 'indicators', label: 'Індикатори' },
  { value: 'level', label: 'Відбиття від рівня' },
  { value: 'wedge', label: 'Пробій клина' },
];

export const strategiesFor = (value) => (value === 'all' ? ['indicators', 'level', 'wedge'] : [value]);

export default function StrategyBar({ value, onChange }) {
  return (
    <div className="strategy-bar" role="radiogroup" aria-label="Стратегія">
      {OPTIONS.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          className={`chip ${value === o.value ? 'chip-on' : ''}`}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
