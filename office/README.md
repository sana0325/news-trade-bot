# Офіс

Переглядач документів для Android (Vite + React + Capacitor), окремо від основного застосунку.

- Заставка: логотип, привітання, бейдж форматів, смуга завантаження (~2.6 с).
- Головна: картки за типом, пошук, фільтри, список нещодавніх файлів, кнопка «+» для відкриття.
- Перегляд: PDF (pdf.js), Word .docx (mammoth), Excel .xlsx/.xls/.csv/.ods (SheetJS, вкладки аркушів), текстові файли.
- Файли зберігаються лише на пристрої (IndexedDB).

Готовий APK (пряме завантаження): https://github.com/sana0325/news-trade-bot/releases/download/office-latest/office.apk

```bash
cd office
npm install
npm run dev            # http://localhost:5173
npm run build && npx cap sync android
cd android && ./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```
