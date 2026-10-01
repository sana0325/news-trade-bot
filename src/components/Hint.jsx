import { Arrow } from './Icons.jsx';

export default function Hint({ onClose }) {
  return (
    <section className="hint">
      <div className="hint-row">
        <span className="tag up"><Arrow dir="call" /> ВГОРУ</span>
        <span>ціна має вирости за 5 хвилин</span>
      </div>
      <div className="hint-row">
        <span className="tag down"><Arrow dir="put" /> ВНИЗ</span>
        <span>ціна має впасти за 5 хвилин</span>
      </div>
      <p className="muted small">
        Бот показує лише сильні сигнали: скільки індикаторів за і проти, впевненість і таймер.
        Через 5 хвилин напише, чи пішла ціна куди сказано. Це не ставки.
      </p>
      <p className="muted small">
        Стратегії: індикатори (25 голосів), відбиття від рівня, який ціна вже тримала, і пробій
        клина — коли ціна стискається між двома лініями й вириває за одну з них.
      </p>
      <button type="button" className="btn" onClick={onClose}>Зрозуміло</button>
    </section>
  );
}
