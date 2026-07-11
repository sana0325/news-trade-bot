# ScalpBot

Автономний Android-додаток для скальпінгу на BingX Perpetual Futures з торговими
рішеннями від DeepSeek API. Нативний Kotlin + Jetpack Compose — **все працює
всередині одного додатка**: без сервера, без Termux, без MT5. Торгове ядро живе
у Foreground Service і продовжує працювати при заблокованому екрані й закритому UI.

## ⚠️ Перед першим запуском

- Додаток стартує в режимі **DEMO** (BingX VST — віртуальна торгівля). Перемикання
  на **LIVE** відбувається лише вручну в Налаштуваннях з явним підтвердженням —
  перевірте на DEMO, що бот поводиться очікувано, перш ніж довіряти йому реальні гроші.
- **API-ключ BingX має мати ЛИШЕ право на торгівлю. Вимкніть право на вивід коштів**
  при створенні ключа — додаток ним не користується, і це головний захист від втрати
  коштів у разі витоку ключа.
- Kill-switch (закриття всіх позицій і зупинка торгівлі при просадці -30% від
  стартового депозиту) — хардкод, з інтерфейсу не вимикається. Це навмисне рішення.
- Ризик-пресет за замовчуванням — Conservative (5% депозиту, плече 5x). Пресет
  Aggressive (50% депозиту, плече 20x) вмикається окремим підтвердженням і несе
  високий ризик ліквідації.

## Стек

- Kotlin, Jetpack Compose (Material3), minSdk 26, targetSdk 35
- Ktor Client (CIO) — REST і WebSocket до BingX і DeepSeek
- Room — журнал угод, дводенні звіти, версійовані "уроки", кеш пар
- EncryptedSharedPreferences (Jetpack Security) — API-ключі та ризик-конфіг
- WorkManager — оновлення списку пар, watchdog сервісу, дводенний аналітичний звіт
- Графіки — чистий Compose Canvas (sparkline, свічковий графік), без сторонньої
  бібліотеки візуалізації
- GitHub Actions — збірка debug APK на кожен push (`.github/workflows/build-apk.yml`)

## Отримання ключів

### BingX (DEMO / VST)

1. Зареєструйтесь на BingX, увімкніть Demo Trading (VST) у розділі ф'ючерсів.
2. У налаштуваннях API BingX створіть ключ для демо-акаунта: дозвольте **торгівлю**,
   заборонте **вивід коштів**.
3. Введіть API Key і API Secret у Налаштуваннях додатка, розділ "BingX DEMO (VST)".

### BingX (LIVE)

Той самий процес на реальному акаунті BingX (Perpetual Futures API), ключ для
LIVE вводиться окремо від DEMO — режими повністю ізольовані одне від одного
(різні базові хости, різні ключі, немає ризику випадково торгувати "не тим" ключем).

### DeepSeek

Створіть API-ключ на platform.deepseek.com і введіть його в Налаштуваннях. Модель —
`deepseek-chat`. Двигун опитує DeepSeek часто (на закритті кожної M1-свічки по
кожній активній парі) — токени DeepSeek дешеві, тож економити на кількості
запитів немає сенсу.

## Збірка APK

### GitHub Actions (рекомендовано)

Кожен push у гілку `main`/`develop`/`claude/**` запускає workflow
`.github/workflows/build-apk.yml`, який ганяє юніт-тести і збирає debug APK.
Готовий файл — в артефактах запуску (`scalpbot-debug-apk`).

### Локально

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Потрібні: JDK 17, Android SDK (compileSdk 35). Gradle-wrapper уже в репозиторії.

## Необхідні дозволи

| Дозвіл | Навіщо |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | REST/WebSocket до BingX і DeepSeek |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | торговий двигун працює у Foreground Service безперервно |
| `WAKE_LOCK` | partial wake lock, щоб CPU не засинав під час торгівлі |
| `RECEIVE_BOOT_COMPLETED` | автозапуск сервісу після перезавантаження, якщо бот був активний |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | виняток з Doze/App Standby — без нього Android рано чи пізно приспить фоновий процес |
| `POST_NOTIFICATIONS` (Android 13+) | сповіщення про угоди, kill-switch, стан сервісу |

## Архітектура коротко

```
data/local     — Room (журнал угод, звіти, уроки, кеш пар) + EncryptedSharedPreferences
data/remote    — BingX REST/WS клієнт, DeepSeek клієнт
domain/engine  — TradingEngine, RiskManager, PositionSizer, SlTpCalculator, DecisionValidator
service        — TradingForegroundService, BootReceiver, WorkManager-воркери
notification   — канали сповіщень (угоди/системні/сервіс) + TradeNotifier
ui             — Compose-екрани: Дашборд, Ринок, Журнал, Статистика, Налаштування
```

Один відкритий трейд одночасно — хардкод (`SingleTradeLock`), поки угода не
закриється по TP/SL/тайм-ауту, нові сигнали ігноруються. SL і TP виставляються
на біржі одразу при відкритті позиції — навіть якщо Android уб'є процес додатка,
позиція лишається захищеною.

## Відомі обмеження

- Мапінг полів BingX REST/WebSocket API (назви ключів у JSON) зроблений за
  найкращим наближенням до документації BingX Swap V2 без можливості звірити з
  живими відповідями під час розробки. Парсинг навмисно захисний (nullable-поля,
  дефолти замість падінь), але варто звірити реальні відповіді після підключення
  ключів і за потреби скоригувати DTO в `data/remote/bingx/dto`.
- У цьому середовищі розробки немає Android SDK/емулятора — код перевірявся
  компіляцією й юніт-тестами через CI (GitHub Actions), UI вручну не тестувався.
  Перед LIVE-торгівлею обов'язково прогнати повний цикл на DEMO.

## Тести

```bash
./gradlew testDebugUnitTest
```

Покриті юніт-тестами: розрахунок розміру позиції (`PositionSizerTest`), SL/TP
(`SlTpCalculatorTest`), kill-switch/денний ліміт/спред (`RiskMathTest`), парсинг
відповіді DeepSeek (`DecisionValidatorTest`), підпис запитів BingX (`BingXSignerTest`).
