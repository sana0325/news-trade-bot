// Android (APK): сповіщення про сигнали і фонова служба, щоб система не заморожувала бота.
// У браузері всі функції нічого не роблять.
import { Capacitor } from '@capacitor/core';
import { LocalNotifications } from '@capacitor/local-notifications';
import { ForegroundService } from '@capawesome-team/capacitor-android-foreground-service';
import { RESULT_TEXT } from './signal.js';
import { STRATEGY_LABEL } from './patterns.js';
import { dirWord, dirHint, fmtPrice } from './format.js';

const ICON = 'ic_stat_vektor';
const SIGNALS_CHANNEL = 'signals';
const SERVICE_CHANNEL = 'service';
const SERVICE_ID = 1;

export const isNative = () => Capacitor.isNativePlatform();

let serviceOn = false;
let lastBody = '';
let nextId = 100;

// Дозвіл на сповіщення, канали і фонова служба. Безпечно викликати кілька разів.
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
    await ForegroundService.createNotificationChannel({
      id: SERVICE_CHANNEL,
      name: 'Робота у фоні',
      description: 'Постійне сповіщення, поки бот стежить за ринком',
      importance: 2,
    });
    await updateBackground('Стежу за ринком');
  } catch (e) {
    console.warn('VEKTOR: фонова робота недоступна', e);
  }
}

// Текст постійного сповіщення фонової служби (оновлюємо лише коли він змінився).
export async function updateBackground(body) {
  if (!isNative() || (serviceOn && body === lastBody)) return;
  lastBody = body;
  const opts = {
    id: SERVICE_ID,
    title: 'VEKTOR працює у фоні',
    body,
    smallIcon: ICON,
    notificationChannelId: SERVICE_CHANNEL,
    silent: true,
  };
  try {
    if (serviceOn) await ForegroundService.updateForegroundService(opts);
    else {
      await ForegroundService.startForegroundService(opts);
      serviceOn = true;
    }
  } catch (e) {
    console.warn('VEKTOR: фонова служба', e);
  }
}

export async function stopBackground() {
  if (!isNative() || !serviceOn) return;
  serviceOn = false;
  try {
    await ForegroundService.stopForegroundService();
  } catch {
    /* вже зупинена */
  }
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
