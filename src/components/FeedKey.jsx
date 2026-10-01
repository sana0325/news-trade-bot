import { useState } from 'react';
import { parseKeys } from '../lib/twelvedata.js';

const STATUS = {
  nokey: 'ключ не вставлено',
  connecting: 'підключаюсь…',
  live: 'ціни йдуть',
  error: 'помилка',
};

// Ключі Twelve Data: вставляються один раз, зберігаються лише на цьому пристрої.
// Працює перший; якщо він відмовить — застосунок сам бере наступний.
const keysWord = (n) => {
  const d = n % 10;
  const dd = n % 100;
  if (d === 1 && dd !== 11) return 'ключ';
  if (d >= 2 && d <= 4 && (dd < 12 || dd > 14)) return 'ключі';
  return 'ключів';
};

export default function FeedKey({ value, status, onSave }) {
  const [open, setOpen] = useState(!value);
  const [draft, setDraft] = useState(value);
  const save = (e) => {
    e.preventDefault();
    onSave(draft.trim());
    setOpen(false);
  };
  const st = status?.status ?? 'nokey';
  const count = parseKeys(draft).length;
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
            Ключі
          </button>
        )}
      </div>
      {open && (
        <form className="feed-form" onSubmit={save}>
          <textarea
            className="feed-input feed-keys"
            rows={4}
            autoComplete="off"
            spellCheck="false"
            placeholder="API-ключі Twelve Data — кожен з нового рядка"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <button type="submit" className="btn feed-save">
            Зберегти{count > 1 ? ` (${count} ${keysWord(count)})` : ''}
          </button>
          <p className="muted small">
            Ключі зберігаються лише на цьому пристрої. Працює перший; якщо він відмовить, бот сам перейде на
            наступний.
          </p>
        </form>
      )}
    </section>
  );
}
