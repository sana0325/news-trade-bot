#!/usr/bin/env node
// Збір статистики OTC з ноутбука: ті самі ціни Binarium і той самий рушій сигналів, що в застосунку,
// але без обмежень телефона (фон, батарея) і без ліміту «3 активні сигнали» — кожен сигнал іде в облік.
//
//   node tools/collect-otc.mjs                 — перевірити, які OTC-пари зараз з цінами, взяти до 10
//   node tools/collect-otc.mjs --pairs 43,46   — лише ці id Binarium
//   node tools/collect-otc.mjs --max 20        — до 20 пар
//   node tools/collect-otc.mjs --report        — лише підсумок з уже зібраного файлу
//
// Кожен результат (через 5 хв) дописується в data/otc-signals.csv, тож перезапуск нічого не губить.
// Окрім сигналів рушія, скрипт раз на 5 хв по кожній парі робить «чисту» ставку проти натовпу
// (стратегія crowd), якщо натовп перекошений хоча б 60 / 40, — щоб перевірити саму ідею на великій вибірці.

import { mkdirSync, existsSync, readFileSync, appendFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.BINARIUM_BASE ||= 'https://api.binarium.com';
const { Engine } = await import('../src/lib/engine.js');
const { probeOtc, fetchOpinion } = await import('../src/lib/binarium.js');
const { otcAssetsFor, DEFAULT_OTC } = await import('../src/lib/assets.js');
const { resolveSignal, SIGNAL_MS } = await import('../src/lib/signal.js');

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const FILE = join(ROOT, 'data', 'otc-signals.csv');
const HEAD = 'time,pair,strategy,direction,confidence,crowd_up,with_crowd,entry,exit,status';
const BREAKEVEN = 52; // при виплаті ~92% треба вгадувати більше ніж 52%

const args = process.argv.slice(2);
const arg = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};

// ---------- файл і підсумок ----------

function rows() {
  if (!existsSync(FILE)) return [];
  return readFileSync(FILE, 'utf8')
    .split('\n')
    .slice(1)
    .filter(Boolean)
    .map((line) => {
      const [time, pair, strategy, direction, confidence, crowdUp, withCrowd, entry, exit, status] = line.split(',');
      return { time, pair, strategy, direction, confidence: +confidence, crowdUp, withCrowd, entry, exit, status };
    });
}

function record(r) {
  mkdirSync(dirname(FILE), { recursive: true });
  if (!existsSync(FILE)) appendFileSync(FILE, `${HEAD}\n`);
  const line = [
    new Date(r.createdAt).toISOString(),
    r.symbol,
    r.strategy,
    r.direction === 'call' ? 'ВГОРУ' : 'ВНИЗ',
    r.confidence ?? '',
    r.crowd == null ? '' : r.crowd.toFixed(3),
    r.withCrowd == null ? '' : r.withCrowd ? 'за' : 'проти',
    r.entry,
    r.exit,
    r.status,
  ].join(',');
  appendFileSync(FILE, `${line}\n`);
}

function line(label, list) {
  const n = list.length;
  const hit = list.filter((r) => r.status === 'hit').length;
  const flat = list.filter((r) => r.status === 'flat').length;
  const pct = n ? Math.round((hit / n) * 100) : 0;
  const mark = n >= 30 ? (pct > BREAKEVEN ? ' ✔' : ' ✘') : n ? ' (мало даних)' : '';
  return `  ${label.padEnd(28)} ${String(hit).padStart(4)} з ${String(n).padEnd(5)} ${n ? `${pct}%` : '—'}${flat ? `, без змін ${flat}` : ''}${mark}`;
}

function report() {
  const all = rows();
  const out = [`\n=== Статистика OTC: ${all.length} результатів (беззбитковість > ${BREAKEVEN}%) ===`];
  out.push(line('Усі сигнали рушія', all.filter((r) => r.strategy !== 'crowd')));
  out.push('Стратегії:');
  for (const s of ['indicators', 'level', 'wedge', 'crowd']) {
    const name = { indicators: 'Індикатори', level: 'Відбиття від рівня', wedge: 'Пробій клина', crowd: 'Лише проти натовпу' }[s];
    out.push(line(name, all.filter((r) => r.strategy === s)));
  }
  out.push('Сигнали рушія і натовп:');
  const eng = all.filter((r) => r.strategy !== 'crowd');
  out.push(line('За натовпом', eng.filter((r) => r.withCrowd === 'за')));
  out.push(line('Проти натовпу', eng.filter((r) => r.withCrowd === 'проти')));
  out.push('Впевненість:');
  out.push(line('74–79%', eng.filter((r) => r.confidence < 80)));
  out.push(line('80–85%', eng.filter((r) => r.confidence >= 80 && r.confidence < 86)));
  out.push(line('86%+', eng.filter((r) => r.confidence >= 86)));
  out.push('Пари:');
  for (const p of [...new Set(all.map((r) => r.pair))].sort()) out.push(line(p, all.filter((r) => r.pair === p)));
  console.log(out.join('\n'));
  console.log(`Файл: ${FILE}\n`);
}

if (args.includes('--report')) {
  report();
  process.exit(0);
}

// ---------- які пари ----------

let pairs;
if (arg('--pairs')) {
  const ids = arg('--pairs').split(',').map(Number);
  pairs = ids.map((id) => DEFAULT_OTC.find((x) => x.binariumId === id) ?? { binariumId: id, symbol: `OTC ${id}`, digits: 5 });
} else {
  const max = Number(arg('--max') ?? 10);
  console.log('Перевіряю, які OTC-пари Binarium зараз з цінами…');
  const res = await probeOtc({ onProgress: (d, n) => process.stdout.write(`\r  ${d} / ${n}`) });
  process.stdout.write('\n');
  if (res.error) console.log(`  ${res.error}`);
  pairs = res.ok.slice(0, max);
  if (!pairs.length) {
    console.log('Жодна OTC-пара не віддає ціни. Перевірте інтернет і чи відкривається binarium.com.');
    process.exit(1);
  }
}
console.log(`Пари (${pairs.length}): ${pairs.map((p) => `${p.symbol} [${p.binariumId}]`).join(', ')}`);

// ---------- рушій ----------

const idle = { setKey() {}, setSymbols() {}, start() {}, stop() {}, key: '' };
const engine = new Engine({
  assets: otcAssetsFor(pairs),
  createSpotFeed: () => idle,
  forceMode: 'otc',
  maxActive: Infinity,
});

engine.subscribe((ev) => {
  if (ev.type === 'signal') {
    const s = ev.signal;
    const crowd = s.crowd == null ? '' : ` · натовп ${Math.round(s.crowd * 100)}% вгору (${s.withCrowd ? 'за' : 'проти'})`;
    console.log(`${new Date().toLocaleTimeString('uk-UA')}  СИГНАЛ ${s.symbol} ${s.direction === 'call' ? '▲ ВГОРУ' : '▼ ВНИЗ'} ${s.confidence}% · ${s.strategy}${crowd}`);
  } else if (ev.type === 'result') {
    record(ev.signal);
    console.log(`${new Date().toLocaleTimeString('uk-UA')}  РЕЗУЛЬТАТ ${ev.signal.symbol} ${ev.signal.strategy}: ${ev.signal.status}`);
  }
});

engine.start();

// ---------- «чиста» ставка проти натовпу ----------

const lastCrowdBet = new Map();
const crowdBets = [];
setInterval(async () => {
  const t = Date.now();
  for (const p of pairs) {
    const s = engine.state.get(`otc-${p.binariumId}`);
    if (!s || !engine.feedLive(s, t)) continue;
    if (t - (lastCrowdBet.get(p.binariumId) ?? 0) < SIGNAL_MS) continue;
    const up = engine.crowdOf(s, t);
    if (up == null || Math.abs(up - 0.5) < 0.1) continue;
    lastCrowdBet.set(p.binariumId, t);
    crowdBets.push({
      symbol: p.symbol,
      assetId: s.asset.id,
      digits: p.digits,
      strategy: 'crowd',
      direction: up > 0.5 ? 'put' : 'call',
      confidence: '',
      crowd: up,
      withCrowd: false,
      entry: s.price,
      createdAt: t,
      expiresAt: t + SIGNAL_MS,
    });
  }
  for (let i = crowdBets.length - 1; i >= 0; i--) {
    const b = crowdBets[i];
    if (t < b.expiresAt) continue;
    crowdBets.splice(i, 1);
    const s = engine.state.get(b.assetId);
    if (!s || !engine.feedLive(s, t)) continue; // ціни пропали — ставку не рахуємо
    record(resolveSignal(b, s.price, b.digits));
  }
}, 1000);

// Раз на 10 хв — коротко про стан і підсумок.
setInterval(() => {
  const t = Date.now();
  const snap = engine.snapshot();
  const live = snap.assets.filter((a) => a.live).length;
  const err = snap.assets.find((a) => a.feedError)?.feedError;
  console.log(`${new Date(t).toLocaleTimeString('uk-UA')}  ціни йдуть по ${live} з ${snap.assets.length} парах${err ? ` · ${err}` : ''}`);
  report();
}, 10 * 60_000);

process.on('SIGINT', () => {
  engine.stop();
  report();
  process.exit(0);
});

report();
console.log('Збираю. Сигнали й результати з’являтимуться тут, підсумок — раз на 10 хв. Зупинити: Ctrl+C.\n');
