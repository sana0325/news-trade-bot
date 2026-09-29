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
      <button type="button" className="btn" onClick={onClose}>Зрозуміло</button>
    </section>
  );
}
