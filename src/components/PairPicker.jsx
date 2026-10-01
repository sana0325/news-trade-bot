import { useState } from 'react';
import { SPOT_CATALOG, MAX_SPOT_PAIRS } from '../lib/assets.js';

// «Перевірити пари»: Twelve Data сам каже, які пари дає тариф; з доступних обираємо до 8.
export default function PairPicker({ selected, onProbe, onApply }) {
  const [state, setState] = useState({ phase: 'idle' }); // idle | running | done
  const [chosen, setChosen] = useState(selected);

  const run = async () => {
    setState({ phase: 'running', done: 0, total: SPOT_CATALOG.length });
    const res = await onProbe(
      SPOT_CATALOG.map((a) => a.symbol),
      (done, total) => setState({ phase: 'running', done, total }),
    );
    setChosen((c) => c.filter((x) => res.ok.includes(x)));
    setState({ phase: 'done', ...res });
  };

  const toggle = (sym) =>
    setChosen((c) =>
      c.includes(sym) ? c.filter((x) => x !== sym) : c.length < MAX_SPOT_PAIRS ? [...c, sym] : c,
    );

  const changed = chosen.length > 0 && (chosen.length !== selected.length || chosen.some((x) => !selected.includes(x)));

  return (
    <section className="section">
      <div className="section-head">
        <h2>Пари Twelve Data</h2>
        {state.phase === 'done' && <span className="muted small tnum">доступно {state.ok.length}</span>}
      </div>

      {state.phase === 'idle' && (
        <div className="feed-row">
          <p className="muted small picker-text">
            Twelve Data сам скаже, які з {SPOT_CATALOG.length} пар дає ваш тариф. Ціни на ~10 с стануть на паузу.
          </p>
          <button type="button" className="chip" onClick={run}>
            Перевірити
          </button>
        </div>
      )}

      {state.phase === 'running' && (
        <p className="small tnum">
          Перевіряю… {state.done} / {state.total}
        </p>
      )}

      {state.phase === 'done' && (
        <>
          {state.error && <p className="small down-text">Не вдалось: {state.error}</p>}
          <ul className="picker">
            {SPOT_CATALOG.filter((a) => state.ok.includes(a.symbol)).map((a) => (
              <li key={a.symbol}>
                <label className="picker-row">
                  <input type="checkbox" checked={chosen.includes(a.symbol)} onChange={() => toggle(a.symbol)} />
                  <span className="picker-sym">{a.symbol}</span>
                </label>
              </li>
            ))}
          </ul>
          {state.fail.length > 0 && (
            <p className="muted small picker-text">Тариф не дає: {state.fail.join(', ')}.</p>
          )}
          <p className="muted small picker-text">
            Обрано {chosen.length} з {MAX_SPOT_PAIRS} можливих. Нова пара почне з розігріву ~13 хв.
          </p>
          <div className="picker-actions">
            <button type="button" className="chip" onClick={run}>
              Ще раз
            </button>
            <button
              type="button"
              className="btn"
              disabled={!changed}
              onClick={() => {
                onApply(chosen);
                setState({ phase: 'idle' });
              }}
            >
              Застосувати
            </button>
          </div>
        </>
      )}
    </section>
  );
}
