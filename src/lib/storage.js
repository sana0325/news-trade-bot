// У браузері зберігаємо лише: звук, фільтр сили, чи закрита перша підказка.
const KEYS = { sound: 'vektor.sound', filter: 'vektor.filter', hint: 'vektor.hintClosed' };

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
};
