import { useEffect, useRef, useState } from 'react';
import { Engine } from './lib/engine.js';
import { prefs } from './lib/storage.js';
import { playChime, unlockAudio } from './lib/sound.js';
import Header from './components/Header.jsx';
import SessionBanner from './components/SessionBanner.jsx';
import Hint from './components/Hint.jsx';
import FilterBar from './components/FilterBar.jsx';
import StrategyBar, { strategiesFor } from './components/StrategyBar.jsx';
import SignalCard from './components/SignalCard.jsx';
import PairList from './components/PairList.jsx';
import Chart from './components/Chart.jsx';
import Votes from './components/Votes.jsx';
import Results from './components/Results.jsx';
import Toast from './components/Toast.jsx';
import FeedKey from './components/FeedKey.jsx';
import { MAX_ACTIVE } from './lib/signal.js';
import { OTC_ASSETS, spotAssetsFor, pairNames } from './lib/assets.js';
import PairPicker from './components/PairPicker.jsx';
import { startBackground, updateBackground, notifySignal, notifyResult } from './lib/native.js';

export default function App() {
  const engineRef = useRef(null);
  engineRef.current ??= new Engine({
    assets: [...spotAssetsFor(prefs.spotPairs()), ...OTC_ASSETS],
    savedCandles: prefs.spotCandles(),
  });
  const engine = engineRef.current;

  const [snap, setSnap] = useState(() => engine.snapshot());
  const [sound, setSound] = useState(prefs.sound);
  const [filter, setFilter] = useState(prefs.filter);
  // Вибір стратегії живе лише до перезавантаження: у браузері зберігаємо тільки звук, фільтр і підказку.
  const [strategy, setStrategy] = useState('all');
  const [hintOpen, setHintOpen] = useState(() => !prefs.hintClosed());
  const [selected, setSelected] = useState(null);
  const [toast, setToast] = useState(null);
  const [twelveKey, setTwelveKey] = useState(prefs.twelveKey);
  const [spotPairs, setSpotPairs] = useState(() => engine.spotSymbols());

  const soundRef = useRef(sound);
  soundRef.current = sound;

  useEffect(() => {
    engine.setFilter(filter);
  }, [engine, filter]);

  useEffect(() => {
    engine.setTwelveDataKey(twelveKey);
  }, [engine, twelveKey]);

  useEffect(() => {
    engine.setStrategies(strategiesFor(strategy));
  }, [engine, strategy]);

  useEffect(() => {
    if (engine.mode === 'otc') setToast({ id: Date.now(), text: 'Бот перейшов на OTC' });
    const off = engine.subscribe((ev) => {
      if (ev.type === 'tick') {
        const snap = engine.snapshot();
        setSnap(snap);
        updateBackground(backgroundText(snap));
      } else if (ev.type === 'signal') {
        if (soundRef.current) playChime(ev.signal.direction);
        // Коли застосунок згорнутий або екран вимкнено — сповіщення Android.
        if (document.visibilityState === 'hidden') notifySignal(ev.signal);
      } else if (ev.type === 'result') {
        if (document.visibilityState === 'hidden') notifyResult(ev.signal, engine.asset(ev.signal.assetId)?.digits ?? 5);
      } else if (ev.type === 'mode') {
        setToast({
          id: Date.now(),
          text: ev.mode === 'otc' ? 'Бот перейшов на OTC' : 'Бот повернувся на звичайний ринок',
        });
      }
    });
    engine.start();
    startBackground();
    const unlock = () => unlockAudio();
    window.addEventListener('pointerdown', unlock);
    // Свічки спот-пар — на пристрій: раз на свічку і коли застосунок ховається.
    const save = () => prefs.setSpotCandles(engine.spotCandles());
    const saver = setInterval(save, 15_000);
    const onHide = () => document.visibilityState === 'hidden' && save();
    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('pagehide', save);
    return () => {
      off();
      save();
      engine.stop();
      clearInterval(saver);
      window.removeEventListener('pointerdown', unlock);
      document.removeEventListener('visibilitychange', onHide);
      window.removeEventListener('pagehide', save);
    };
  }, [engine]);

  const assets = snap.assets;
  const selectedId = assets.some((a) => a.id === selected) ? selected : assets[0]?.id;
  const selectedAsset = assets.find((a) => a.id === selectedId);
  const activeFor = snap.signals.find((s) => s.assetId === selectedId);
  const otc = snap.session.mode === 'otc';

  const toggleSound = () => {
    unlockAudio();
    setSound((v) => {
      prefs.setSound(!v);
      return !v;
    });
  };

  const changeFilter = (v) => {
    prefs.setFilter(v);
    setFilter(v);
  };

  const saveKey = (key) => {
    prefs.setTwelveKey(key);
    setTwelveKey(key);
  };

  const applyPairs = (symbols) => {
    engine.setSpotPairs(spotAssetsFor(symbols));
    const now = engine.spotSymbols();
    prefs.setSpotPairs(now);
    setSpotPairs(now);
    setSnap(engine.snapshot());
  };

  const closeHint = () => {
    prefs.closeHint();
    setHintOpen(false);
  };

  const pick = (id) => {
    setSelected(id);
    document.getElementById('chart')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <>
      <Header otc={otc} now={snap.now} sound={sound} onSound={toggleSound} />
      <main className="layout">
        <div className="col">
          <SessionBanner session={snap.session} assets={assets} names={pairNames(spotPairs)} />
          {!otc && <FeedKey value={twelveKey} status={snap.spotFeed} names={pairNames(spotPairs)} onSave={saveKey} />}
          {!otc && twelveKey && (
            <PairPicker
              key={spotPairs.join()}
              selected={spotPairs}
              onProbe={(symbols, onProgress) => engine.probePairs(symbols, onProgress)}
              onApply={applyPairs}
            />
          )}
          {hintOpen && <Hint onClose={closeHint} />}
          <StrategyBar value={strategy} onChange={setStrategy} />
          <FilterBar value={filter} onChange={changeFilter} />

          <section className="section" aria-live="polite">
            <div className="section-head">
              <h2>Сигнали</h2>
              <span className="muted tnum">
                {snap.signals.length} / {MAX_ACTIVE} активні
              </span>
            </div>
            {snap.signals.length === 0 ? (
              <div className="empty">
                <div className="pulse" aria-hidden="true" />
                <p>Чекаю сильний сигнал…</p>
                <p className="muted small">Слабкі й короткі рухи не показую.</p>
              </div>
            ) : (
              <div className="cards">
                {snap.signals.map((s) => (
                  <SignalCard
                    key={s.id}
                    signal={s}
                    now={snap.now}
                    price={engine.priceOf(s.assetId)}
                    digits={engine.asset(s.assetId).digits}
                    onOpen={() => pick(s.assetId)}
                  />
                ))}
              </div>
            )}
          </section>

          <Results results={snap.results} engine={engine} />
        </div>

        <div className="col">
          <PairList assets={assets} signals={snap.signals} selected={selectedId} onSelect={setSelected} />
          {selectedAsset && (
            <>
              <Chart
                asset={selectedAsset}
                candles={engine.candles(selectedId)}
                signal={activeFor}
                now={snap.now}
              />
              <Votes votes={engine.votes(selectedId)} ev={selectedAsset.ev} symbol={selectedAsset.symbol} />
            </>
          )}
          <p className="disclaimer">
            Сигнали — розрахунок індикаторів, не фінансова порада. Ціни {pairNames(spotPairs)} — Twelve Data.
          </p>
        </div>
      </main>
      {toast && <Toast key={toast.id} text={toast.text} onDone={() => setToast(null)} />}
    </>
  );
}

// Текст постійного сповіщення фонової служби.
function backgroundText(snap) {
  const n = snap.signals.length;
  const active = n ? `Активних сигналів: ${n}` : 'Чекаю сильний сигнал';
  if (snap.session.mode === 'otc') return `${active} · OTC`;
  const feed = { live: 'ціни йдуть', connecting: 'підключаюсь', nokey: 'немає ключа', error: 'помилка фіду' }[
    snap.spotFeed?.status
  ];
  return feed ? `${active} · ${feed}` : active;
}
