// На пристрої зберігаємо лише: звук, фільтр сили, чи закрита перша підказка,
// ключі Twelve Data (у коді й на GitHub їх немає), обрані пари (спот і OTC) і свічки спот-пар
// (щоб після короткого перезапуску не чекати розігріву заново).
const KEYS = {
  sound: 'vektor.sound',
  filter: 'vektor.filter',
  hint: 'vektor.hintClosed',
  twelve: 'vektor.twelveKey',
  pairs: 'vektor.spotPairs',
  otcPairs: 'vektor.otcPairs',
  candles: 'vektor.spotCandles',
};

function read(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v == null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* приватне вікно або заблоковане сховище — не страшно */
  }
}

export const prefs = {
  sound: () => read(KEYS.sound, true) !== false,
  setSound: (v) => write(KEYS.sound, !!v),
  filter: () => {
    const v = read(KEYS.filter, 74);
    return [74, 80, 86].includes(v) ? v : 74;
  },
  setFilter: (v) => write(KEYS.filter, v),
  hintClosed: () => read(KEYS.hint, false) === true,
  closeHint: () => write(KEYS.hint, true),
  twelveKey: () => {
    const v = read(KEYS.twelve, '');
    return typeof v === 'string' ? v : '';
  },
  setTwelveKey: (v) => write(KEYS.twelve, v),
  spotPairs: () => {
    const v = read(KEYS.pairs, null);
    return Array.isArray(v) && v.every((x) => typeof x === 'string') ? v : ['EUR/USD'];
  },
  setSpotPairs: (v) => write(KEYS.pairs, v),
  otcPairs: () => read(KEYS.otcPairs, null), // перевіряє otcAssetsFor
  setOtcPairs: (v) => write(KEYS.otcPairs, v),
  spotCandles: () => {
    const v = read(KEYS.candles, {});
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
  },
  setSpotCandles: (v) => write(KEYS.candles, v),
};
