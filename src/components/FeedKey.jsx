import { useState } from 'react';

const STATUS = {
  nokey: 'ключ не вставлено',
  connecting: 'підключаюсь…',
  live: 'ціни йдуть',
  error: 'помилка',
};

// Ключ Twelve Data: вставляється один раз, зберігається лише на цьому пристрої.
export default function FeedKey({ value, status, onSave }) {
  const [open, setOpen] = useState(!value);
  const [draft, setDraft] = useState(value);
  const save = (e) => {
    e.preventDefault();
    onSave(draft.trim());
    setOpen(false);
  };
  const st = status?.status ?? 'nokey';
  return (
    <section className="section feed">
      <div className="feed-row">
        <div>
          <div className="feed-title">Ціни EUR/USD і GBP/USD: Twelve Data</div>
          <div className={`small feed-status feed-${st}`}>
            {STATUS[st]}
            {status?.message ? ` · ${status.message}` : ''}
          </div>
        </div>
        {!open && (
          <button type="button" className="chip" onClick={() => setOpen(true)}>
            Ключ
          </button>
        )}
      </div>
      {open && (
        <form className="feed-form" onSubmit={save}>
          <input
            className="feed-input"
            type="password"
            autoComplete="off"
            spellCheck="false"
            placeholder="API-ключ Twelve Data"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <button type="submit" className="btn feed-save">Зберегти</button>
          <p className="muted small">Ключ зберігається лише на цьому пристрої.</p>
        </form>
      )}
    </section>
  );
}
