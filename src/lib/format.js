export const fmtPrice = (v, digits) => (v == null ? '—' : v.toFixed(digits));

export function fmtLeft(ms) {
  const s = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export const dirWord = (d) => (d === 'call' ? 'ВГОРУ' : 'ВНИЗ');
export const dirHint = (d) =>
  d === 'call' ? 'ціна має вирости за 5 хвилин' : 'ціна має впасти за 5 хвилин';
