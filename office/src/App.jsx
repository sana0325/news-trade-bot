import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Splash, { Logo } from './Splash.jsx';
import Viewer from './Viewer.jsx';
import { listenIncoming } from './incoming.js';
import { listFiles, saveFile, deleteFile } from './storage.js';
import { ACCEPT, KINDS, kindOf, formatSize } from './types.js';

const FILTERS = [['all', 'Усі'], ...Object.entries(KINDS).map(([k, v]) => [k, v.label])];

export default function App() {
  const [splash, setSplash] = useState(true);
  const [files, setFiles] = useState([]);
  const [filter, setFilter] = useState('all');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(null);
  const [notice, setNotice] = useState('');
  const input = useRef(null);

  const reload = () => listFiles().then((f) => setFiles(f.sort((a, b) => b.openedAt - a.openedAt))).catch(() => {});
  useEffect(() => { reload(); }, []);

  useEffect(() => listenIncoming(async ({ name, data }) => {
    const kind = kindOf(name, data);
    if (!kind) { setNotice(`Формат файлу «${name}» не підтримується`); return; }
    const rec = { id: `${name}-${data.byteLength}`, name, size: data.byteLength, kind, openedAt: Date.now(), data };
    await saveFile(rec);
    setSplash(false);
    setOpen(rec);
    reload();
  }, (err) => setNotice(`Не вдалося відкрити файл: ${err}`)), []);
  const endSplash = useCallback(() => setSplash(false), []);

  async function addFiles(list) {
    let skipped = 0;
    for (const f of list) {
      const kind = kindOf(f.name);
      if (!kind) { skipped++; continue; }
      const rec = { id: `${f.name}-${f.size}-${f.lastModified}`, name: f.name, size: f.size, kind, openedAt: Date.now(), data: await f.arrayBuffer() };
      await saveFile(rec);
      if (list.length === 1) setOpen(rec);
    }
    setNotice(skipped ? `Пропущено файлів: ${skipped} (формат не підтримується)` : '');
    reload();
  }

  async function openFile(f) {
    const rec = { ...f, openedAt: Date.now() };
    await saveFile(rec);
    setOpen(rec);
    reload();
  }

  async function remove(e, f) {
    e.stopPropagation();
    if (!confirm(`Прибрати «${f.name}» зі списку?`)) return;
    await deleteFile(f.id);
    reload();
  }

  const shown = useMemo(() => files.filter((f) =>
    (filter === 'all' || f.kind === filter) && f.name.toLowerCase().includes(query.toLowerCase())), [files, filter, query]);

  if (splash) return <Splash onDone={endSplash} />;
  if (open) return <Viewer file={open} onBack={() => setOpen(null)} />;

  const counts = Object.fromEntries(Object.keys(KINDS).map((k) => [k, files.filter((f) => f.kind === k).length]));

  return (
    <div className="home">
      <header className="home-head">
        <Logo size={40} />
        <h1>Офіс</h1>
      </header>

      <div className="cards">
        {Object.entries(KINDS).map(([k, v]) => (
          <button key={k} className="card" style={{ '--c': v.color }} onClick={() => setFilter(k)}>
            <span className="card-icon">{v.label[0]}</span>
            <span className="card-label">{v.label}</span>
            <span className="card-count">{counts[k]}</span>
          </button>
        ))}
      </div>

      <input className="search" placeholder="Пошук документів" value={query} onChange={(e) => setQuery(e.target.value)} />

      <div className="tabs">
        {FILTERS.map(([k, label]) => (
          <button key={k} className={filter === k ? 'tab on' : 'tab'} onClick={() => setFilter(k)}>{label}</button>
        ))}
      </div>

      {notice && <div className="notice">{notice}</div>}

      <ul className="list">
        {shown.map((f) => (
          <li key={f.id} onClick={() => openFile(f)}>
            <span className="file-icon" style={{ background: KINDS[f.kind].color }}>{KINDS[f.kind].label[0]}</span>
            <div className="file-meta">
              <div className="ellipsis">{f.name}</div>
              <div className="muted small">{formatSize(f.size)} · {new Date(f.openedAt).toLocaleDateString('uk-UA')}</div>
            </div>
            <button className="icon-btn" onClick={(e) => remove(e, f)} aria-label="Прибрати">×</button>
          </li>
        ))}
        {!shown.length && (
          <li className="empty">
            {files.length ? 'Нічого не знайдено' : 'Тут з’являться ваші документи. Натисніть «+», щоб відкрити PDF, Word, Excel або текстовий файл.'}
          </li>
        )}
      </ul>

      <button className="fab" onClick={() => input.current.click()} aria-label="Відкрити файл">+</button>
      <input ref={input} type="file" accept={ACCEPT} multiple hidden
        onChange={(e) => { addFiles([...e.target.files]); e.target.value = ''; }} />
    </div>
  );
}
