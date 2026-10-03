// Android (APK): нативна фонова служба VektorFeed (android/.../FeedService.java) збирає ціни
// Twelve Data і свічки окремо від WebView, тримає постійне сповіщення і щосекунди будить
// рушій сигналів. Тут же — сповіщення про сигнали. У браузері все це нічого не робить.
import { Capacitor, registerPlugin } from '@capacitor/core';
import { LocalNotifications } from '@capacitor/local-notifications';
import { RESULT_TEXT } from './signal.js';
import { STRATEGY_LABEL } from './patterns.js';
import { dirWord, dirHint, fmtPrice } from './format.js';
import { parseKeys } from './twelvedata.js';
import { buildFeed, FeedError } from './binarium.js';

const ICON = 'ic_stat_vektor';
const SIGNALS_CHANNEL = 'signals';

const VektorFeed = registerPlugin('VektorFeed');

export const isNative = () => Capacitor.isNativePlatform();

let lastText = '';
let nextId = 100;

// Дозвіл на сповіщення, канал сигналів і запуск фонової служби.
export async function startBackground() {
  if (!isNative()) return;
  try {
    await LocalNotifications.requestPermissions();
    await LocalNotifications.createChannel({
      id: SIGNALS_CHANNEL,
      name: 'Сигнали',
      description: 'Новий сигнал і результат через 5 хвилин',
      importance: 5,
      visibility: 1,
      vibration: true,
    });
  } catch (e) {
    console.warn('VEKTOR: сповіщення недоступні', e);
  }
  await updateBackground('Стежу за ринком');
}

// Текст постійного сповіщення (служба стартує, якщо ще не запущена).
export async function updateBackground(text) {
  if (!isNative() || text === lastText) return;
  lastText = text;
  try {
    await VektorFeed.setText({ text });
  } catch (e) {
    console.warn('VEKTOR: фонова служба', e);
  }
}

// Фід Twelve Data для рушія: той самий інтерфейс, що й TwelveDataFeed, але працює в службі.
export function createNativeSpotFeed({ symbols, onPrice, onStatus, onUnavailable }) {
  let keysText = '';
  let syms = symbols;
  let enabled = false;
  VektorFeed.addListener('price', (e) => onPrice(e.symbol, e.price, e.t));
  VektorFeed.addListener('status', (e) => {
    onStatus(e.status, e.message);
    onUnavailable?.(e.unavailable || []);
  });
  const push = () => VektorFeed.configure({ keys: keysText, symbols: syms, enabled }).catch(() => {});
  return {
    get key() {
      return parseKeys(keysText)[0] ?? '';
    },
    setKey(text) {
      keysText = text || '';
      push();
    },
    setSymbols(list) {
      syms = list;
      push();
    },
    start() {
      enabled = true;
      push();
    },
    stop() {
      enabled = false;
      push();
    },
  };
}

// Фід Binarium для рушія: той самий інтерфейс, що й BinariumPoller, але опитує служба —
// її не гальмують, коли WebView у фоні. Служба віддає сирі відповіді сервера, розбираємо тут.
export function createNativeOtcFeed({ onData, onError }) {
  let ids = [];
  let enabled = false;
  const parse = (text) => {
    try {
      return JSON.parse(text);
    } catch {
      return null;
    }
  };
  VektorFeed.addListener('otc', (e) => {
    try {
      const data = buildFeed(
        {
          candles: (e.candles || []).map(parse),
          candleError: e.candleError || null,
          quotes: e.quotes == null ? null : parse(e.quotes),
          quoteError: e.quoteError || null,
        },
        e.now,
      );
      onData(e.id, data, !!e.full);
    } catch (err) {
      onError(e.id, err instanceof FeedError ? err.message : 'помилка фіду');
    }
  });
  const push = () => VektorFeed.otcConfigure({ ids, enabled }).catch(() => {});
  return {
    setIds(list) {
      ids = [...list];
      push();
    },
    start() {
      if (enabled) return;
      enabled = true;
      push();
    },
    stop() {
      if (!enabled) return;
      enabled = false;
      push();
    },
    refresh(id) {
      VektorFeed.otcRefresh(id == null ? {} : { id }).catch(() => {});
    },
  };
}

// Свічки, які служба назбирала (і поки WebView спав): { 'EUR/USD': [{t,o,h,l,c}, ...] }.
export async function nativeCandles() {
  if (!isNative()) return {};
  try {
    const { candles } = await VektorFeed.getCandles();
    const out = {};
    for (const [sym, list] of Object.entries(candles || {})) {
      out[sym] = list.map(([t, o, h, l, c]) => ({ t, o, h, l, c }));
    }
    return out;
  } catch {
    return {};
  }
}

// Пульс зі служби щосекунди — рушій працює, навіть коли таймери сторінки пригальмовані.
export function onNativeTick(fn) {
  if (!isNative()) return () => {};
  const h = VektorFeed.addListener('tick', fn);
  return () => h.then((x) => x.remove());
}

export async function batteryUnrestricted() {
  if (!isNative()) return true;
  try {
    return (await VektorFeed.batteryStatus()).ignoring;
  } catch {
    return true;
  }
}

export function requestBatteryUnrestricted() {
  if (isNative()) VektorFeed.requestBattery().catch(() => {});
}

async function notify(title, body) {
  if (!isNative()) return;
  try {
    await LocalNotifications.schedule({
      notifications: [{ id: nextId++, title, body, smallIcon: ICON, channelId: SIGNALS_CHANNEL }],
    });
  } catch (e) {
    console.warn('VEKTOR: сповіщення', e);
  }
}

export function notifySignal(s) {
  const arrow = s.direction === 'call' ? '▲' : '▼';
  const against = s.maBuy + s.maSell + s.techBuy + s.techSell - s.agreement;
  return notify(
    `${s.symbol} ${arrow} ${dirWord(s.direction)} · ${s.confidence}%`,
    `${dirHint(s.direction)[0].toUpperCase()}${dirHint(s.direction).slice(1)}. ${STRATEGY_LABEL[s.strategy]}, за ${s.agreement} / проти ${against}.`,
  );
}

export function notifyResult(s, digits) {
  return notify(
    `${s.symbol}: ${RESULT_TEXT[s.status].toLowerCase()}`,
    `${dirWord(s.direction)} · вхід ${fmtPrice(s.entry, digits)} → ${fmtPrice(s.exit, digits)}`,
  );
}
