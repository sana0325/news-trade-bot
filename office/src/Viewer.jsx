import { useEffect, useRef, useState } from 'react';
import { KINDS } from './types.js';

async function renderPdf(buf, el) {
  const pdfjs = await import('pdfjs-dist');
  pdfjs.GlobalWorkerOptions.workerSrc = (await import('pdfjs-dist/build/pdf.worker.min.mjs?url')).default;
  const doc = await pdfjs.getDocument({ data: buf.slice(0) }).promise;
  const width = el.clientWidth - 16;
  for (let n = 1; n <= doc.numPages; n++) {
    const page = await doc.getPage(n);
    const base = page.getViewport({ scale: 1 });
    const ratio = window.devicePixelRatio || 1;
    const vp = page.getViewport({ scale: (width / base.width) * ratio * 2 });
    const canvas = document.createElement('canvas');
    canvas.width = vp.width;
    canvas.height = vp.height;
    canvas.style.width = `${width}px`;
    canvas.className = 'pdf-page';
    el.appendChild(canvas);
    await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp }).promise;
  }
}

async function renderWord(buf) {
  const mammoth = await import('mammoth');
  const { value } = await mammoth.convertToHtml({ arrayBuffer: buf });
  return value;
}

async function renderExcel(buf) {
  const XLSX = await import('xlsx');
  const wb = XLSX.read(buf, { type: 'array' });
  return wb.SheetNames.map((name) => ({ name, html: XLSX.utils.sheet_to_html(wb.Sheets[name], { header: '', footer: '' }) }));
}

export default function Viewer({ file, onBack }) {
  const pdfRef = useRef(null);
  const [html, setHtml] = useState(null);
  const [sheets, setSheets] = useState(null);
  const [sheet, setSheet] = useState(0);
  const [text, setText] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [zoom, setZoom] = useState(1);
  const [fit, setFit] = useState(1);
  const bodyRef = useRef(null);
  const contentRef = useRef(null);
  const pinch = useRef(null);

  // Документ верстається у своїй справжній ширині (сторінка A4 або ширша таблиця),
  // а потім масштабується під екран — без розчавлених стовпців.
  useEffect(() => {
    if (loading || file.kind === 'pdf' || file.kind === 'text') return;
    const el = contentRef.current;
    const prev = el.style.zoom;
    el.style.zoom = 1;
    const w = el.scrollWidth;
    el.style.zoom = prev;
    const avail = bodyRef.current.clientWidth - 16;
    const f = Math.min(1, avail / w);
    const start = file.kind === 'excel' ? Math.max(f, 0.6) : f;
    setFit(f);
    setZoom(start);
  }, [loading, html, sheets, sheet, file.kind]);

  const clamp = (z) => Math.min(4, Math.max(0.25, z));
  const onTouchStart = (e) => {
    if (e.touches.length === 2) {
      const [a, b] = e.touches;
      pinch.current = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), z: zoom };
    }
  };
  const onTouchMove = (e) => {
    if (e.touches.length !== 2 || !pinch.current) return;
    const [a, b] = e.touches;
    const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
    setZoom(clamp(pinch.current.z * (d / pinch.current.d)));
  };
  const onTouchEnd = () => { pinch.current = null; };

  useEffect(() => {
    let alive = true;
    const run = async () => {
      if (file.kind === 'pdf') await renderPdf(file.data, pdfRef.current);
      else if (file.kind === 'word') { const h = await renderWord(file.data); alive && setHtml(h); }
      else if (file.kind === 'excel') { const s = await renderExcel(file.data); alive && setSheets(s); }
      else alive && setText(new TextDecoder().decode(file.data));
    };
    run().catch((e) => alive && setError(e.message || String(e))).finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [file]);

  const k = KINDS[file.kind];
  return (
    <div className="viewer">
      <header className="topbar">
        <button className="icon-btn" onClick={onBack} aria-label="Назад">‹</button>
        <div className="topbar-title">
          <span className="chip" style={{ background: k.color }}>{k.label}</span>
          <span className="ellipsis">{file.name}</span>
        </div>
      </header>
      {sheets && sheets.length > 1 && (
        <div className="tabs">
          {sheets.map((s, i) => (
            <button key={s.name} className={i === sheet ? 'tab on' : 'tab'} onClick={() => setSheet(i)}>{s.name}</button>
          ))}
        </div>
      )}
      <div className="zoombar">
        <button onClick={() => setZoom((z) => clamp(z / 1.25))} aria-label="Зменшити">−</button>
        <button className="zoom-fit" onClick={() => setZoom(file.kind === 'pdf' || file.kind === 'text' ? 1 : fit)}>
          {Math.round(zoom * 100)}% · за шириною
        </button>
        <button onClick={() => setZoom((z) => clamp(z * 1.25))} aria-label="Збільшити">+</button>
      </div>
      <div className="viewer-body" ref={bodyRef} onTouchStart={onTouchStart} onTouchMove={onTouchMove} onTouchEnd={onTouchEnd}>
        {loading && <div className="muted center">Відкриваю…</div>}
        {error && <div className="error">Не вдалося відкрити файл: {error}</div>}
        <div ref={contentRef} className={`content content-${file.kind}`} style={{ zoom }}>
        <div ref={pdfRef} className="pdf" />
        {html && <article className="doc" dangerouslySetInnerHTML={{ __html: html }} />}
        {sheets && <div className="sheet" dangerouslySetInnerHTML={{ __html: sheets[sheet].html }} />}
        {text !== null && <pre className="text">{text}</pre>}
        </div>
      </div>
    </div>
  );
}
