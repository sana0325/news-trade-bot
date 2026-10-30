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
    const vp = page.getViewport({ scale: (width / base.width) * ratio });
    const canvas = document.createElement('canvas');
    canvas.width = vp.width;
    canvas.height = vp.height;
    canvas.style.width = `${vp.width / ratio}px`;
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
      <div className="viewer-body">
        {loading && <div className="muted center">Відкриваю…</div>}
        {error && <div className="error">Не вдалося відкрити файл: {error}</div>}
        <div ref={pdfRef} className="pdf" />
        {html && <article className="doc" dangerouslySetInnerHTML={{ __html: html }} />}
        {sheets && <div className="sheet" dangerouslySetInnerHTML={{ __html: sheets[sheet].html }} />}
        {text !== null && <pre className="text">{text}</pre>}
      </div>
    </div>
  );
}
