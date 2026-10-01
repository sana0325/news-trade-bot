import { kyivDateTime } from '../lib/session.js';

export default function SessionBanner({ session, assets, names }) {
  const otc = session.mode === 'otc';
  const live = assets.filter((a) => a.live).length;
  const when = session.nextChange ? kyivDateTime(session.nextChange) : '—';
  return (
    <section className={`banner ${otc ? 'banner-otc' : ''}`}>
      <div className="banner-title">{otc ? 'Бот перейшов на OTC' : 'Бот на звичайному ринку'}</div>
      <div className="banner-sub">
        {otc
          ? 'Вихідні: форекс закритий, звичайні пари не торгуються. Лише OTC.'
          : `Будні: ${names} за справжніми цінами Twelve Data.`}
      </div>
      {otc && (
        <div className="banner-sub">
          Графік Binarium: {live > 0 ? `наживо ${live} з ${assets.length}` : 'фіду немає, працює симуляція'}
        </div>
      )}
      <div className="banner-next">
        <span className="muted">{otc ? 'Звичайний ринок відкриється:' : 'Перехід на OTC:'}</span>{' '}
        <strong className="tnum">{when}</strong> <span className="muted">за Києвом</span>
      </div>
    </section>
  );
}
