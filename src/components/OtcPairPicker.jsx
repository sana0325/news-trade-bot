import { useState } from 'react';
import { MAX_OTC_PAIRS } from '../lib/assets.js';

// «Перевірити»: Binarium сам каже, які активи з позначкою OTC є; бот перевіряє, по яких зараз
// ідуть ціни, і з них обираємо до 8. selected — [{ binariumId, symbol, digits }].
export default function OtcPairPicker({ selected, onProbe, onApply }) {
  const [state, setState] = useState({ phase: 'idle' }); // idle | running | done
  const [chosen, setChosen] = useState(selected.map((x) => x.binariumId));

  const run = async () => {
    setState({ phase: 'running', done: 0, total: 0 });
    const res = await onProbe((done, total) => setState({ phase: 'running', done, total }));
    setChosen((c) => c.filter((id) => res.ok.some((x) => x.binariumId === id)));
    setState({ phase: 'done', ...res });
  };

  const toggle = (id) =>
    setChosen((c) => (c.includes(id) ? c.filter((x) => x !== id) : c.length < MAX_OTC_PAIRS ? [...c, id] : c));

  const was = selected.map((x) => x.binariumId);
  const changed = chosen.length > 0 && (chosen.length !== was.length || chosen.some((x) => !was.includes(x)));

  return (
    <section className="section">
      <div className="section-head">
        <h2>Пари OTC</h2>
        {state.phase === 'done' && <span className="muted small tnum">доступно {state.ok.length}</span>}
      </div>

      {state.phase === 'idle' && (
        <div className="feed-row">
          <p className="muted small picker-text">
            Зараз: {selected.map((x) => x.symbol).join(', ')}. Binarium сам скаже, які OTC-пари є і по яких
            ідуть ціни.
          </p>
          <button type="button" className="chip" onClick={run}>
            Перевірити
          </button>
        </div>
      )}

      {state.phase === 'running' && (
        <p className="small tnum">
          Перевіряю… {state.done} / {state.total || '…'}
        </p>
      )}

      {state.phase === 'done' && (
        <>
          {state.error && <p className="small down-text">{state.error}</p>}
          <ul className="picker">
            {state.ok.map((a) => (
              <li key={a.binariumId}>
                <label className="picker-row">
                  <input type="checkbox" checked={chosen.includes(a.binariumId)} onChange={() => toggle(a.binariumId)} />
                  <span className="picker-sym">{a.symbol}</span>
                </label>
              </li>
            ))}
          </ul>
          {state.fail.length > 0 && (
            <p className="muted small picker-text">Цін зараз немає: {state.fail.join(', ')}.</p>
          )}
          <p className="muted small picker-text">
            Обрано {chosen.length} з {MAX_OTC_PAIRS} можливих. Історію нова пара бере одразу, без розігріву.
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
                onApply(state.ok.filter((x) => chosen.includes(x.binariumId)));
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
