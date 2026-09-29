# VEKTOR

Сигнальний бот по валютних парах, українською. Не термінал і не ставки: лише сильний сигнал
(пара, ВГОРУ / ВНИЗ, чому) і таймер 5 хвилин. Потім — результат словами.

## Запуск

```bash
npm install
npm run dev      # http://localhost:5173
npm test         # індикатори, сигнал, сесія, фід, рушій
npm run build    # статичні файли в dist/
```

## Як влаштовано

- `src/lib/indicators.js` — 25 індикаторів (12 середніх, 13 осциляторів), голос buy / sell / neutral.
- `src/lib/signal.js` — правила сигналу, впевненість, результат через 300 с.
- `src/lib/session.js` — сесія форексу за America/New_York, час Києва.
- `src/lib/market.js` — симуляція: тік 280 мс, свічка 15 с, 128 свічок історії, до 140 у пам’яті.
- `src/lib/binarium.js` — OTC-фід публічного графіка Binarium кожні 2.5 с.
- `src/lib/engine.js` — усе разом: ≤ 3 активні сигнали, пауза пари 4 хв.

Спот-пари — симуляція, не біржа. Для OTC браузер ходить на `/binarium/...`; у `npm run dev` і
`npm run preview` це проксі на `https://binarium.com`. Для іншого хоста задайте `VITE_BINARIUM_BASE`.
Якщо фіду немає, OTC-пари працюють на симуляції.

У браузері зберігаються лише: звук, фільтр сили і чи закрита перша підказка.

## APK

GitHub Actions (`.github/workflows/build-apk.yml`) на кожен пуш у `main` збирає debug-APK:
тести → `npm run build` → `npx cap sync android` → `./gradlew assembleDebug`.
Готовий файл — в артефакті `vektor-debug-apk` на сторінці запуску в Actions.

Локально (потрібні JDK 21 і Android SDK):

```bash
npm run build && npx cap sync android
cd android && ./gradlew assembleDebug
```

В APK OTC-фід Binarium іде напряму через CapacitorHttp, без проксі.
