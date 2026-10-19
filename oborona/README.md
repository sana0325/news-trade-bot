# Оборона міста

Мобільний 3D-шутер від першої особи для Android на **Godot 4.3+ (GDScript)**, рендерер **Mobile**,
горизонтальна орієнтація, інтерфейс українською. Тема — оборона зруйнованої вулиці українського міста на світанку.
Уся геометрія — процедурні примітиви, усі звуки — згенеровані в коді. Сторонніх ассетів немає.

## Структура

```
project.godot            налаштування (Mobile, landscape, autoload)
export_presets.cfg       Android, тільки arm64-v8a
scenes/                  MainMenu.tscn, Game.tscn
scripts/Config.gd        УСІ параметри балансу (autoload Config)
scripts/Settings.gd      налаштування гравця + якість графіки (autoload Settings)
scripts/Game.gd          збирає рівень/гравця/HUD/хвилі, пауза, перемога/поразка
scripts/player/          Player.gd: рух, огляд, присід, стрибок, ADS, гранати, дрон, автоприціл
scripts/weapons/         Rifle.gd (hitscan RayCast3D), Grenade.gd, Drone.gd, Explosion.gd
scripts/enemies/         Enemy.gd (NavigationAgent3D + стан-машина), WaveManager.gd
scripts/ui/              TouchControls.gd, HUD.gd, MainMenu.gd, SettingsPanel.gd, UiKit.gd
scripts/level/           Level.gd: вулиця, барикади, туман, світанок, сніг з попелом, артилерія
scripts/fx/              Fx.gd (GPUParticles3D), Sfx.gd (процедурні звуки)
audio/                   місце для CC0-звуків (зараз звуки генеруються)
tests/SmokeTest.gd       автоматичний headless-тест
```

## Керування

| Сенсор | ПК (для тестів) |
|---|---|
| Ліва половина — віртуальний джойстик | WASD |
| Свайп справа (і по кнопці «Вогонь») — огляд | миша (клік — захопити курсор) |
| ВОГОНЬ (утримувати) | ЛКМ |
| ПРИЦІЛ (перемикач ADS) | ПКМ |
| ПЕРЕЗАР. | R |
| ПРИСІД (перемикач) | C |
| СТРИБОК | Пробіл |
| ГРАНАТА — утримувати: видно траєкторію, відпустити: кидок | G |
| ДРОН — 15 с FPV, «ПІДРИВ» — вибух | F |
| II — пауза | Esc |

Налаштування (меню або пауза): чутливість огляду, розмір кнопок, автоприціл-асист, вібрація, якість графіки
(Низька/Середня/Висока). Зберігаються в `user://settings.cfg`.

## Що реалізовано по етапах

1. **Ядро** — Mobile-рендерер; джойстик + свайп + кнопки; гвинтівка (магазин 30, віддача, розкид, що
   росте від черги/руху, спалах + світло, звук); hitscan через `RayCast3D`, окрема колізія голови → хедшот ×2;
   вулиця з руїн, барикад (мішки — `MultiMesh`), «їжаків», спалених авто; туман і світанкове небо;
   `CharacterBody3D` з колізіями.
2. **Вороги** — `NavigationAgent3D` на навмеші, що запікається з колізій при старті; стан-машина
   патруль → помітив (час реакції) → біг в укриття (точки за барикадами, «смерть» напрямку загрози) →
   стрільба чергами → пошук гравця, періодична зміна укриття. Розкид ворога зменшується, поки він вас бачить,
   і росте, коли втрачає з поля зору або під вогнем. Автоприціл-асист (м'яке підтягування до цілі у конусі 6°).
3. **Геймплей** — HUD (здоров'я, набої, динамічний приціл, маркер влучання/хедшоту, червоні стрілки
   напрямку шкоди, віньєтка), 5 хвиль, лічильник вбивств, бонуси між хвилями, головне меню, пауза,
   перемога/поразка; гранати з показом траєкторії і точки падіння; FPV-дрон 15 с з окремою камерою й підривом.
4. **Атмосфера/оптимізація** — частинки пилу/іскор/диму від влучань і вибухів, дим від авто, сніг з попелом
   навколо гравця, далекі спалахи артилерії з запізнілим звуком, фоновий гул; одна динамічна тінь (тільки
   на «Високій»), `visibility_range` (HLOD) для дрібних об'єктів, `mesh_lod_threshold`, масштаб 3D-рендеру
   0.67/0.85/1.0, кількість частинок за якістю, ліміт 60 FPS; вібрація при пострілі, шкоді та вибухах.
5. **Збірка** — `export_presets.cfg` (arm64-v8a, дозвіл VIBRATE, immersive). Інструкція нижче.

## Перевірка

```bash
godot --headless --path . --import                    # імпорт / кеш класів
godot --headless --path . -s res://tests/SmokeTest.gd # тест: навмеш, вороги, стрільба, граната, дрон
godot --path .                                        # запуск з головного меню
```

> `godot --check-only -s <файл>` перевіряє скрипт ізольовано, без autoload — тому для цього
> проєкту він помилково скаржиться на `Settings`/`Fx`/`Sfx`. Використовуйте SmokeTest або запуск сцени.

## Збірка APK для Android — покроково

**1. Встановіть інструменти**
- Godot **4.3** (або новіший 4.x — тоді змініть `config/features` у `project.godot`).
- **JDK 17** (напр. Eclipse Temurin 17).
- **Android SDK**: найпростіше — Android Studio → SDK Manager; потрібні *Android SDK Platform-Tools*,
  *Build-Tools 34.x*, *Platform 34*, *Command-line Tools*.

**2. Встановіть Android export templates**
- У Godot: **Editor → Manage Export Templates → Download and Install**.
- Офлайн: завантажте `Godot_v4.3-stable_export_templates.tpz` з офіційної сторінки завантажень Godot
  і в тому ж вікні натисніть **Install From File**.

**3. Вкажіть шляхи в Godot**
**Editor → Editor Settings → Export → Android**:
- *Java SDK Path* — тека JDK 17;
- *Android SDK Path* — тека Android SDK (напр. `~/Android/Sdk` або `%LOCALAPPDATA%\Android\Sdk`).

**4. Згенеруйте debug keystore**
```bash
keytool -keyalg RSA -genkeypair -alias androiddebugkey -keypass android \
  -keystore debug.keystore -storepass android \
  -dname "CN=Android Debug,O=Android,C=US" -validity 9999 -deployment
```
(у старих JDK приберіть `-deployment`). Потім **Editor Settings → Export → Android**:
*Debug Keystore* = шлях до `debug.keystore`, *Debug Keystore User* = `androiddebugkey`,
*Debug Keystore Pass* = `android`. Не комітьте keystore в репозиторій (`.gitignore` вже це враховує).

**5. Зберіть APK**
- У редакторі: **Project → Export → Android → Export Project**, зніміть «Export With Debug» лише для релізу.
- Або з терміналу:
  ```bash
  mkdir -p build
  godot --headless --path . --export-debug "Android" build/oborona-debug.apk
  ```

**6. Встановіть на телефон**
```bash
adb install -r build/oborona-debug.apk
```
(або увімкніть USB-налагодження й натисніть іконку Android у правому верхньому куті редактора — One-click deploy).

**Реліз**: створіть окремий release-keystore (`keytool ... -alias oborona`), заповніть `keystore/release*`
у пресеті або в Editor Settings і використайте `--export-release`. Для Google Play потрібен AAB:
увімкніть *Use Gradle Build* і *Export Format = AAB* (вимагає **Project → Install Android Build Template**).

## Оптимізація під середній Android

- Якість «Середня» за замовчуванням: 85 % роздільності 3D, без тіней, ~600 частинок снігу.
- Якщо FPS < 60 — оберіть «Низька» (67 % роздільності, менше частинок, щільніший туман ховає дальні об'єкти).
- Баланс (здоров'я, урон, розкид, хвилі тощо) змінюється лише в `scripts/Config.gd`.
