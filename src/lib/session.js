// Форекс-сесія за America/New_York: неділя 17:00 → п’ятниця 17:00.
const NY = 'America/New_York';

function kyivZone() {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: 'Europe/Kyiv' });
    return 'Europe/Kyiv';
  } catch {
    return 'Europe/Kiev';
  }
}
export const KYIV = kyivZone();

const partsCache = new Map();
function formatter(tz) {
  if (!partsCache.has(tz)) {
    partsCache.set(
      tz,
      new Intl.DateTimeFormat('en-US', {
        timeZone: tz,
        hourCycle: 'h23',
        year: 'numeric',
        month: 'numeric',
        day: 'numeric',
        hour: 'numeric',
        minute: 'numeric',
        second: 'numeric',
        weekday: 'short',
      }),
    );
  }
  return partsCache.get(tz);
}

const WD = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };

export function zonedParts(ms, tz) {
  const p = {};
  for (const { type, value } of formatter(tz).formatToParts(new Date(ms))) p[type] = value;
  return {
    year: +p.year,
    month: +p.month,
    day: +p.day,
    hour: +p.hour,
    minute: +p.minute,
    second: +p.second,
    weekday: WD[p.weekday],
  };
}

// Настінний час у поясі → UTC мс (дві ітерації покривають перехід DST).
export function zonedToUtc(y, mo, d, h, mi, tz) {
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  let ts = guess;
  for (let i = 0; i < 2; i++) {
    const p = zonedParts(ts, tz);
    const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
    ts = guess - (asUtc - ts);
  }
  return ts;
}

export function isForexOpen(ms) {
  const p = zonedParts(ms, NY);
  const mins = p.hour * 60 + p.minute;
  if (p.weekday === 6) return false;
  if (p.weekday === 0) return mins >= 17 * 60;
  if (p.weekday === 5) return mins < 17 * 60;
  return true;
}

// Коли сесія зміниться наступного разу (UTC мс).
export function nextSessionChange(ms) {
  const open = isForexOpen(ms);
  const target = open ? 5 : 0; // п’ятниця закриття, неділя відкриття
  const p = zonedParts(ms, NY);
  let add = (target - p.weekday + 7) % 7;
  for (let i = 0; i < 3; i++) {
    const base = new Date(Date.UTC(p.year, p.month - 1, p.day + add));
    const ts = zonedToUtc(base.getUTCFullYear(), base.getUTCMonth() + 1, base.getUTCDate(), 17, 0, NY);
    if (ts > ms) return ts;
    add += 7;
  }
  return null;
}

export function sessionInfo(ms) {
  const open = isForexOpen(ms);
  return { open, mode: open ? 'spot' : 'otc', nextChange: nextSessionChange(ms) };
}

const DAYS = ['нд', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];
const pad = (n) => String(n).padStart(2, '0');

export function kyivClock(ms) {
  const p = zonedParts(ms, KYIV);
  return `${pad(p.hour)}:${pad(p.minute)}:${pad(p.second)}`;
}

export function kyivDateTime(ms) {
  const p = zonedParts(ms, KYIV);
  return `${DAYS[p.weekday]} ${pad(p.day)}.${pad(p.month)}, ${pad(p.hour)}:${pad(p.minute)}`;
}
