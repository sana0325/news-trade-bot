const LABEL = { buy: 'вгору', sell: 'вниз', neutral: '—' };

function Group({ title, items }) {
  const buy = items.filter((v) => v.vote === 'buy').length;
  const sell = items.filter((v) => v.vote === 'sell').length;
  return (
    <div className="vgroup">
      <div className="vgroup-head">
        <span>{title}</span>
        <span className="tnum small">
          <span className="up-text">{buy}↑</span> <span className="down-text">{sell}↓</span>{' '}
          <span className="muted">{items.length - buy - sell}·</span>
        </span>
      </div>
      <ul>
        {items.map((v) => (
          <li key={v.key} className="vrow">
            <span className="vname">{v.name}</span>
            <span className="vval tnum muted">{v.value}</span>
            <span className={`vote vote-${v.vote}`}>{LABEL[v.vote]}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function Votes({ votes, ev, symbol }) {
  return (
    <section className="section">
      <div className="section-head">
        <h2>Хто як проголосував</h2>
        <span className="muted small">{symbol}</span>
      </div>
      {ev && (
        <p className="muted small vsum tnum">
          За вгору {ev.buy}, за вниз {ev.sell}, нейтрально {ev.neutral} з {ev.total}
        </p>
      )}
      <div className="vgroups">
        <Group title="Середні" items={votes.filter((v) => v.group === 'ma')} />
        <Group title="Осцилятори" items={votes.filter((v) => v.group === 'osc')} />
      </div>
    </section>
  );
}
