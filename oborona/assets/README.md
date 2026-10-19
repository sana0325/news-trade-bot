# assets/ — готові ассети

## Завантажується автоматично
`textures/`, `sky/`, `models/` — з Poly Haven (CC0) через GitHub Actions
(`.github/workflows/fetch-oborona-assets.yml`, скрипт `tools/fetch_assets.py`). Список — у `../CREDITS.md`.

## Потрібно завантажити вручну (потрібен вхід у акаунт на сайті)

Після додавання файлів гра підхопить їх автоматично, без змін у коді.
Якщо файлу немає — використовується запасна процедурна модель.

### 1. Гвинтівка → `weapons/rifle.glb` (+ `weapons/rifle.json`)
- Сайт: https://sketchfab.com/search?q=assault+rifle&type=models — увімкніть фільтри **Downloadable** і ліцензії **CC0** / **CC Attribution**.

- Критерії: реалістична штурмова гвинтівка без логотипів і назв відомих ігор; до ~30 000 трикутників;
  PBR-текстури; бажано з коліматором або планкою під нього.
- Кнопка Download → формат **glTF Binary (.glb)** → перейменуйте на `rifle.glb`.
- `rifle.json` (підбирається під модель; я налаштую сам, якщо надішлете модель):
  ```json
  {"scale": 1.0, "rotation": [0, 180, 0], "position": [0, 0, 0],
   "muzzle": [0, 0.01, -0.7], "sight": [0, 0.12, -0.02]}
  ```
  Вісь стволу моделі має дивитися в −Z, початок координат — біля пістолетного руків'я.

### 2. Руки від першої особи → `weapons/arms.glb` (+ `weapons/arms.json`)
- Sketchfab, пошук `fps arms` / `first person arms gloves` з тими ж фільтрами (CC0/CC-BY).
- Критерії: рукави з камуфляжем, тактичні рукавиці; формат .glb.

### 3. Солдати → `characters/soldier.glb`
- Варіант А (найпростіше, CC0, стилізовано): Quaternius — https://quaternius.com → пакети з людьми
  (напр. «Ultimate Animated Character Pack» / «Modular Men»), формат glTF/GLB, анімації вже всередині.
- Варіант Б (реалістичніше): модель солдата з Sketchfab (CC-BY, rigged) +
  анімації з Mixamo (https://www.mixamo.com, безкоштовно з акаунтом Adobe; дозволено використовувати в іграх):
  Idle, Walking, Running, Rifle Aiming Idle, Firing Rifle, Death. Завантажуйте **FBX for Unity, With Skin** для першої
  і **Without Skin** для решти — або надішліть мені FBX, я зберу в GLB.
- Критерії: людиноподібний скелет (humanoid), без шевронів/символіки реальних армій.

### Після завантаження
1. Покладіть файли в цю теку (`oborona/assets/...`) і закомітьте, або надішліть мені в чат.
2. Допишіть рядок у `../CREDITS.md`: назва, автор, посилання, ліцензія (для CC-BY це обов'язково).
