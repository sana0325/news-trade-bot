"""
Обгортка для Android: запускає ndscan.scan() у потоці сервісу, перезапускає після падіння,
шле heartbeat у Telegram і віддає стан для сповіщення. Торгова логіка — лише в ndscan / bingx_trader.
"""
import contextlib, csv, importlib, io, json, os, sys, threading, time, traceback
from datetime import datetime, timezone

HEARTBEAT_SEC = 6 * 3600
RESTART_DELAY = 30

_mod = None
_stop = False
_state = "stopped"     # stopped / running / restarting
_started = 0.0
_hb_thread = None
_alive_ts = 0.0        # для watchdog: потік живий, навіть якщо чекає перезапуску


def _setup(data_dir, env_json):
    os.environ.update(json.loads(env_json or "{}"))
    os.environ["NDSCAN_DIR"] = data_dir


def _load():
    """Свіжий імпорт ndscan: змінні оточення і файли стану читаються заново."""
    global _mod
    if "ndscan" in sys.modules:
        _mod = importlib.reload(sys.modules["ndscan"])
    else:
        import ndscan
        _mod = ndscan
    return _mod


def _sleep(sec):
    global _alive_ts
    end = time.time() + sec
    while not _stop and time.time() < end:
        _alive_ts = time.time()
        time.sleep(1)


def run(data_dir, env_json, sink):
    """Блокує потік до stop(). sink.onLog(str) — рядки логу в UI."""
    global _stop, _state, _started, _hb_thread, _alive_ts
    _stop = False
    _started = time.time()
    _setup(data_dir, env_json)
    if _hb_thread is None or not _hb_thread.is_alive():
        _hb_thread = threading.Thread(target=_heartbeat_loop, daemon=True)
        _hb_thread.start()
    while not _stop:
        m = None
        try:
            _state = "running"
            _alive_ts = time.time()
            m = _load()
            m.LOG_CB = sink.onLog
            m.scan()
            if _stop:
                break
            m.log("scan() завершився сам — перезапуск через 30 с")
        except BaseException:
            tb = traceback.format_exc()
            _state = "restarting"
            for line in tb.rstrip().splitlines():
                _log(m, sink, line)
            _log(m, sink, f"Бот впав — перезапуск через {RESTART_DELAY} с")
            _tg(m, f"⚠️ ndscan впав, перезапускаю через {RESTART_DELAY} с\n{tb.strip().splitlines()[-1][:300]}")
        if not _stop:
            _state = "restarting"
            _sleep(RESTART_DELAY)
    _state = "stopped"


def stop():
    global _stop
    _stop = True
    if _mod is not None:
        _mod.request_stop()


def _log(m, sink, line):
    try:
        if m is not None:
            m.log(line)
        else:
            sink.onLog(datetime.now().strftime("%H:%M:%S ") + line)
    except Exception:
        pass


def _tg(m, text):
    try:
        if m is not None:
            m.tg(text)
    except Exception:
        pass


def _signals_since(m, sec):
    """Скільки сигналів записано за останні sec секунд (без рядків-результатів)."""
    try:
        cut = time.time() - sec
        seen = set()
        with open(m.F_SIGNALS, encoding="utf-8", errors="replace") as f:
            for r in csv.DictReader(f):
                if r.get("outcome") or r["id"] in seen:
                    continue
                t = datetime.strptime(r["time_utc"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
                if t >= cut:
                    seen.add(r["id"])
        return len(seen)
    except Exception:
        return 0


def _heartbeat_loop():
    last = time.time()
    while True:
        time.sleep(60)
        if _stop or _mod is None or time.time() - last < HEARTBEAT_SEC:
            continue
        last = time.time()
        m = _mod
        trades = f"відкритих угод: {len(m.TRADER.trades)}" if m.TRADER else f"BingX вимкнено, відкритих сигналів: {len(m.OPEN)}"
        _tg(m, f"💚 живий, блок {m.STATUS.get('block')}, сигналів за 6г: {_signals_since(m, HEARTBEAT_SEC)}, {trades}")


def status():
    """JSON для сповіщення, UI і watchdog."""
    m = _mod
    d = {"state": _state, "started": _started, "alive_ts": _alive_ts, "block": None, "lag": None, "loop_ts": 0, "block_ts": 0,
         "watch": 0, "deposits": 0, "signals_6h": 0, "trades": 0}
    if m is not None:
        d.update(m.STATUS)
        d.update(watch=len(m.WATCH), deposits=len(m.DEPOSITS), signals_6h=_signals_since(m, HEARTBEAT_SEC),
                 trades=len(m.TRADER.trades) if m.TRADER else 0)
    return json.dumps(d)


def report(data_dir, env_json):
    """Вивід ndscan.report() текстом. Не чіпає модуль, що працює в сервісі."""
    _setup(data_dir, env_json)
    m = _mod
    if m is None:
        import ndscan as m
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        try:
            m.report()
        except Exception:
            traceback.print_exc(file=buf)
    return buf.getvalue()
