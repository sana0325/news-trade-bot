#!/usr/bin/env python3
"""
ndscan v2.2 — ончейн-сканер BSC для монет з ф'ючерсів Binance.

Сигнали:
  FRESH_BUY   (LONG)  свіжий гаманець купив великий обсяг на DEX
  CEX_OUT     (LONG)  великий вивід з біржі на свіжий гаманець (накопичення)
  CEX_IN      (SHORT) великий завід токенів на біржу
  WATCH_OUT   (SHORT) гаманець зі слежки (купував свіжим / виводив з біржі / отримав мінт)
                      продає на DEX або заводить на біржу
  MINT        (INFO)  великий мінт токенів (без оцінки результату)

Кожен LONG/SHORT сигнал оцінюється на ф'ючерсах: LONG — +TARGET% раніше за -STOP%,
SHORT — дзеркально. Тільки сигнали, без торгівлі (або автоторгівля через okx_trader.py).

    pip install requests
    python ndscan.py            # сканер
    python ndscan.py report     # звіт vs критерій
    python ndscan.py recheck    # переоцінити закриті сигнали
    python ndscan.py rebuild    # перебудувати список монет

Telegram: файл tg.json поруч зі скриптом {"token": "...", "chat": "..."} (або змінні TG_TOKEN / TG_CHAT)
Гаманці бірж: cex_wallets.json (перевір і доповнюй). Депозитні адреси бот вчить сам.
"""
import csv, json, math, os, sys, time, re
from datetime import datetime, timezone
import requests

# ------------------------- НАЛАШТУВАННЯ -------------------------
E = os.environ.get
RPCS = [u for u in E("BSC_RPCS", "https://bsc-rpc.publicnode.com,https://bsc.drpc.org,"
                          "https://bsc-dataseed.bnbchain.org,https://1rpc.io/bnb").split(",") if u]
MIN_USD           = float(E("MIN_USD", 30000))          # мін. сума події, $
MIN_PCT_MCAP      = float(E("MIN_PCT_MCAP", 0.5))       # або мін. % від капіталізації (але не менше $5K)
MIN_MCAP          = float(E("MIN_MCAP", 15000000))      # мін. капіталізація токену, $ (якщо відома на DexScreener)
FRESH_NONCE       = int(E("FRESH_NONCE", 10))           # "свіжий" = <= N вихідних транзакцій
WINDOW_H          = float(E("WINDOW_H", 6))             # вікно для кластерів і дедупу подій
WATCH_DAYS        = float(E("WATCH_DAYS", 7))           # скільки тримати гаманець у слежці
MIN_LIQ_USD       = float(E("MIN_LIQ_USD", 50000))
TARGET_PCT        = float(E("TARGET_PCT", 5))
STOP_PCT          = float(E("STOP_PCT", 3))
EVAL_H            = float(E("EVAL_H", 24))
POLL_SEC          = float(E("POLL_SEC", 4))
MAX_LAG           = int(E("MAX_LAG", 600))              # якщо відстали більше — перестрибуємо
ADDR_CHUNK        = int(E("ADDR_CHUNK", 10))            # publicnode дозволяє макс 10 адрес у запиті
COOLDOWN_MIN      = float(E("COOLDOWN_MIN", 30))        # не дублювати однаковий сигнал по монеті частіше
TRACE_BLOCKS      = int(E("TRACE_BLOCKS", 40000))       # свіп з нової депозитки: скільки блоків назад шукати депозит
TRACE_STEP        = int(E("TRACE_STEP", 5000))          # блоків за один запит при пошуку
MAX_SWEEP_AGE_MIN = float(E("MAX_SWEEP_AGE_MIN", 30))   # ігнорувати свіпи, де депозит був > N хв тому
HUB_NONCE         = int(E("HUB_NONCE", 1000))           # відправник з > N транзакцій = бот/хаб -> CEX_IN ігноруємо

# Android: файли стану в теці з NDSCAN_DIR (filesDir застосунку), без неї — поруч зі скриптом.
DIR = E("NDSCAN_DIR") or os.path.dirname(os.path.abspath(__file__))
_tgf = os.path.join(DIR, "tg.json")
try: _tgc = json.load(open(_tgf, encoding="utf-8-sig"))
except Exception: _tgc = {}
TG_TOKEN = E("TG_TOKEN") or _tgc.get("token")
TG_CHAT = E("TG_CHAT") or str(_tgc.get("chat") or "") or None

P = lambda f: os.path.join(DIR, f)
F_UNIVERSE, F_SIGNALS, F_OPEN, F_STATE = P("universe.json"), P("signals.csv"), P("open_signals.json"), P("state.json")
F_CEX, F_DEPOSITS, F_WATCH = P("cex_wallets.json"), P("learned_deposits.json"), P("watchlist.json")

T_V2       = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822"
T_V3       = "0xc42079f94a6350d7e6235f29174924f928cc2ac818eb64fed8004e115fbcca67"
T_PCS3     = "0x19b47279256b2a23a1665c810c8d55a1758940ee09377d4f8d26497a3577dc83"
T_TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
ZERO = "0x" + "0" * 40
SKIP_BASES = {"BTC","ETH","BNB","USDC","USDT","FDUSD","DAI","WBTC","WETH","XRP","SOL","DOGE",
              "ADA","TRX","LTC","BCH","DOT","LINK","AVAX","TON","SHIB","FIL","ETC","ATOM","NEAR"}
DIRECTION = {"FRESH_BUY": "LONG", "CEX_OUT": "LONG", "CEX_IN": "SHORT", "WATCH_OUT": "SHORT", "MINT": "INFO"}
FAPI, DS = "https://fapi.binance.com", "https://api.dexscreener.com"
S = requests.Session(); S.headers["User-Agent"] = "ndscan/2.2"

# Android: кнопка «Стоп» і стан для сповіщення / watchdog.
STOP = False
STATUS = {"block": None, "lag": None, "loop_ts": 0.0, "block_ts": 0.0}
def request_stop():
    global STOP
    STOP = True

LOG_CB = None   # Android: колбек у Kotlin (рядок логу -> UI)
_flog = None
def log(*a):
    global _flog
    now = datetime.now()
    line = " ".join([now.strftime("%H:%M:%S")] + [str(x) for x in a])
    print(line, flush=True)
    try:
        if _flog is None:
            import logging, logging.handlers
            _flog = logging.getLogger("ndscan.file")
            if not _flog.handlers:
                h = logging.handlers.RotatingFileHandler(P("log.txt"), maxBytes=2 * 1024 * 1024, backupCount=2, encoding="utf-8")
                h.setFormatter(logging.Formatter("%(message)s"))
                _flog.addHandler(h)
            _flog.setLevel(logging.INFO); _flog.propagate = False
        _flog.info(now.strftime("%Y-%m-%d ") + line)
    except Exception:
        pass
    if LOG_CB:
        try: LOG_CB(line)
        except Exception: pass

def load_json(f, default):
    try:
        with open(f, encoding="utf-8") as fp:
            return json.load(fp)
    except Exception:
        return default

def save_json(f, obj):
    tmp = f + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fp:
        json.dump(obj, fp, indent=1)
    os.replace(tmp, f)

def get(url, **params):
    for i in range(3):
        try:
            r = S.get(url, params=params, timeout=15)
            if r.status_code == 429: time.sleep(5 * (i + 1)); continue
            r.raise_for_status(); return r.json()
        except Exception:
            if i == 2: raise
            time.sleep(2)

# ------------------------- RPC -------------------------
_rpc_i = 0
def rpc(method, params):
    global _rpc_i
    last = None
    for _ in range(len(RPCS) * 2):
        url = RPCS[_rpc_i % len(RPCS)]
        try:
            j = S.post(url, json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params}, timeout=10).json()
            if "error" in j: raise RuntimeError(j["error"])
            return j["result"]
        except Exception as e:
            last = e; _rpc_i += 1
    raise RuntimeError(f"RPC fail {method}: {last}")

def eth_call(to, data): return rpc("eth_call", [{"to": to, "data": data}, "latest"])
def word(data, i): return int(data[2 + 64 * i: 2 + 64 * (i + 1)], 16)
def sword(data, i):
    v = word(data, i); return v - (1 << 256) if v >= (1 << 255) else v
def topic_addr(a): return "0x" + "0" * 24 + a[2:].lower()
def addr_of(topic): return "0x" + topic[-40:].lower()

_eoa_cache = {}
def is_eoa(addr):
    if addr not in _eoa_cache:
        try: _eoa_cache[addr] = rpc("eth_getCode", [addr, "latest"]) in ("0x", "0x0")
        except Exception: _eoa_cache[addr] = False
    return _eoa_cache[addr]

_fresh_cache = {}
def is_fresh_eoa(addr):
    """Свіжий звичайний гаманець: не контракт і мало вихідних транзакцій."""
    if addr in _fresh_cache: return _fresh_cache[addr]
    try:
        if rpc("eth_getCode", [addr, "latest"]) not in ("0x", "0x0"): res = (False, -1)
        else:
            n = int(rpc("eth_getTransactionCount", [addr, "latest"]), 16)
            res = (n <= FRESH_NONCE, n)
    except Exception: res = (False, -1)
    _fresh_cache[addr] = res
    return res

def is_hub(addr):
    """Бот/хаб: звичайний гаманець з дуже великою кількістю транзакцій (скрипт переганяє монети)."""
    return is_fresh_eoa(addr)[1] > HUB_NONCE

# ------------------------- ВСЕСВІТ МОНЕТ -------------------------
def build_universe():
    log("Будую список монет (Binance futures -> BSC пули)... ~2-4 хв")
    info = get(f"{FAPI}/fapi/v1/exchangeInfo")
    futs = {}
    for s in info["symbols"]:
        if s.get("contractType") == "PERPETUAL" and s.get("quoteAsset") == "USDT" and s.get("status") == "TRADING":
            base = re.sub(r"^1(0)+", "", s["baseAsset"])
            if base not in SKIP_BASES and len(base) >= 2: futs[base] = s["symbol"]
    pools = {}
    for n, (base, fsym) in enumerate(sorted(futs.items())):
        try: res = get(f"{DS}/latest/dex/search", q=base).get("pairs") or []
        except Exception as e: log("dexscreener err", base, e); continue
        cand = []
        for p in res:
            if p.get("chainId") != "bsc": continue
            liq = (p.get("liquidity") or {}).get("usd") or 0
            if liq < MIN_LIQ_USD: continue
            if p["baseToken"]["symbol"].upper() == base: cand.append((liq, p, p["baseToken"]["address"], "base"))
            elif p["quoteToken"]["symbol"].upper() == base: cand.append((liq, p, p["quoteToken"]["address"], "quote"))
        if cand:
            cand.sort(key=lambda x: -x[0]); tok = cand[0][2].lower()
            for liq, p, addr, side in [c for c in cand if c[2].lower() == tok][:3]:
                pools[p["pairAddress"].lower()] = {"symbol": base, "fut": fsym, "token": tok, "side": side,
                                                   "dex": p.get("dexId"), "liq": liq}
        time.sleep(0.25)
        if n % 50 == 0: log(f"  {n}/{len(futs)} перевірено, пулів: {len(pools)}")
    decs = {}
    for pa, d in list(pools.items()):
        try:
            d["is0"] = ("0x" + eth_call(pa, "0x0dfe1681")[-40:]).lower() == d["token"]
            if d["token"] not in decs: decs[d["token"]] = int(eth_call(d["token"], "0x313ce567"), 16)
            d["dec"] = decs[d["token"]]
        except Exception as e:
            log("пул пропущено", pa, e); pools.pop(pa)
    save_json(F_UNIVERSE, {"ts": time.time(), "pools": pools})
    log(f"Готово: {len(decs)} монет, {len(pools)} пулів")
    return pools

def load_universe():
    u = load_json(F_UNIVERSE, None)
    if u and time.time() - u["ts"] < 24 * 3600 and u["pools"]: return u["pools"]
    return build_universe()

def tokens_of(pools):
    t = {}
    for pa, d in pools.items():
        t.setdefault(d["token"], {"symbol": d["symbol"], "fut": d["fut"], "dec": d["dec"], "token": d["token"]})
    return t

# ------------------------- ЦІНИ -------------------------
PRICES = {}
def refresh_prices(pools):
    by_tok = {}
    for pa, d in pools.items(): by_tok.setdefault(d["token"], pa)
    pas = list(by_tok.values())
    for i in range(0, len(pas), 30):
        try: j = get(f"{DS}/latest/dex/pairs/bsc/" + ",".join(pas[i:i + 30]))
        except Exception as e: log("prices err", e); continue
        for p in j.get("pairs") or []:
            d = pools.get(p["pairAddress"].lower())
            if not d: continue
            pu = float(p.get("priceUsd") or 0)
            if d["side"] == "quote":
                pn = float(p.get("priceNative") or 0); pu = pu / pn if pn else 0; mc = 0
            else: mc = float(p.get("marketCap") or p.get("fdv") or 0)
            if pu: PRICES[d["token"]] = (pu, mc)

def usd_of(tok, raw, dec):
    price, mcap = PRICES.get(tok, (0, 0))
    usd = raw / 10 ** dec * price
    return usd, (round(usd / mcap * 100, 3) if mcap else None)

def is_big(usd, pct):
    return usd >= MIN_USD or (pct is not None and pct >= MIN_PCT_MCAP and usd >= 5000)

# ------------------------- СВОПИ -------------------------
def decode_swap(lg, d):
    """(+raw) = токен куплено з пулу, (-raw) = токен продано в пул, 0 = інше."""
    t, data = lg["topics"][0], lg["data"]
    if t == T_V2:
        a0i, a1i, a0o, a1o = (word(data, k) for k in range(4))
        tin, tout = (a0i, a0o) if d["is0"] else (a1i, a1o)
        return tout - tin
    if t in (T_V3, T_PCS3):
        amt = sword(data, 0) if d["is0"] else sword(data, 1)
        return -amt
    return 0

def decode_buy(lg, d):
    v = decode_swap(lg, d); return v if v > 0 else 0

# ------------------------- ФЬЮЧЕРСИ -------------------------
def fut_context(fsym):
    out = {"fut_px": None, "px_1h": None, "oi_1h": None, "vol_x": None, "book_imb": None}
    try:
        k = get(f"{FAPI}/fapi/v1/klines", symbol=fsym, interval="1h", limit=25)
        out["fut_px"] = float(k[-1][4])
        out["px_1h"] = round((float(k[-1][4]) / float(k[-2][4]) - 1) * 100, 2)
        vols = [float(x[7]) for x in k[:-1]]
        avg = sum(vols[:-1]) / max(1, len(vols) - 1)
        out["vol_x"] = round(vols[-1] / avg, 2) if avg else None
    except Exception: pass
    try:
        oi = get(f"{FAPI}/futures/data/openInterestHist", symbol=fsym, period="5m", limit=13)
        a, b = float(oi[0]["sumOpenInterestValue"]), float(oi[-1]["sumOpenInterestValue"])
        out["oi_1h"] = round((b / a - 1) * 100, 2) if a else None
    except Exception: pass
    try:
        ob = get(f"{FAPI}/fapi/v1/depth", symbol=fsym, limit=100)
        mid = (float(ob["bids"][0][0]) + float(ob["asks"][0][0])) / 2
        bid = sum(float(p) * float(q) for p, q in ob["bids"] if float(p) >= mid * 0.98)
        ask = sum(float(p) * float(q) for p, q in ob["asks"] if float(p) <= mid * 1.02)
        out["book_imb"] = round(bid / ask, 2) if ask else None
    except Exception: pass
    return out

def tg(text):
    if not (TG_TOKEN and TG_CHAT): return
    url = f"https://api.telegram.org/bot{TG_TOKEN}/sendMessage"
    payload = {"chat_id": TG_CHAT, "text": text, "disable_web_page_preview": True}
    for i in range(3):
        try:
            r = S.post(url, json=payload, headers={"Connection": "close"}, timeout=10)
            r.raise_for_status()
            return
        except Exception as e:
            if i == 2:
                log("tg err", e)
            else:
                time.sleep(1.5 * (i + 1))

# ------------------------- СТАН -------------------------
SIG_FIELDS = ["id", "time_utc", "type", "dir", "symbol", "fut", "level", "wallet", "counterparty", "usd",
              "pct_mcap", "n_wallets", "note", "fut_px", "px_1h", "oi_1h", "vol_x", "book_imb", "tx",
              "outcome", "result_pct", "minutes"]
CEX = {k.lower(): v for k, v in load_json(F_CEX, {}).items() if k.startswith("0x")}
DEPOSITS = load_json(F_DEPOSITS, {})   # deposit_addr -> exchange
WATCH = load_json(F_WATCH, {})         # wallet -> {reason, symbol, token, ts}
_st = load_json(F_STATE, {})
RECENT = _st.get("recent", {})         # "TYPE|token" -> [[ts, wallet, usd], ...]
LAST_ALERT = _st.get("last_alert", {}) # "TYPE|token" -> ts
EVENTS = _st.get("events", {})         # "SYMBOL|DIR" -> ts початку події (для дедупу)
OPEN = load_json(F_OPEN, [])

def save_state(last_block):
    cut = time.time() - WATCH_DAYS * 86400
    for w in [w for w, v in WATCH.items() if v["ts"] < cut]: WATCH.pop(w)
    save_json(F_STATE, {"last_block": last_block, "recent": RECENT, "last_alert": LAST_ALERT, "events": EVENTS})
    save_json(F_OPEN, OPEN); save_json(F_WATCH, WATCH); save_json(F_DEPOSITS, DEPOSITS)

def exch_of(a): return CEX.get(a) or DEPOSITS.get(a)

def write_signal(row):
    new = not os.path.exists(F_SIGNALS)
    with open(F_SIGNALS, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, SIG_FIELDS)
        if new: w.writeheader()
        w.writerow({k: row.get(k, "") for k in SIG_FIELDS})

def watch(wallet, reason, t):
    if wallet in CEX or wallet in DEPOSITS: return
    WATCH[wallet] = {"reason": reason, "symbol": t["symbol"], "token": t["token"], "ts": time.time()}

ICON = {"FRESH_BUY": "👀", "CEX_OUT": "🏦", "CEX_IN": "📤", "WATCH_OUT": "🚨", "MINT": "🪙"}
OKX_SYMS = None
def load_okx_syms():
    """Монети з USDT-ф'ючерсами на реальному OKX (публічний API, ключ не потрібен)."""
    global OKX_SYMS
    try:
        d = get("https://www.okx.com/api/v5/public/instruments", instType="SWAP")["data"]
        OKX_SYMS = {i["instId"].split("-")[0] for i in d if i["instId"].endswith("-USDT-SWAP") and i.get("state") == "live"}
        log(f"OKX: {len(OKX_SYMS)} USDT-ф'ючерсів")
    except Exception as e: log("OKX список монет недоступний:", e)

def fp(x, ref):
    """Ціна з 4 значущими цифрами."""
    d = max(2, 3 - int(math.floor(math.log10(abs(ref))))) if ref else 4
    return f"{x:.{d}f}"

def signal_text(typ, t, dirn, level, usd, pct, note, wallets, tot, ctx, txh, wallet, ts, is_repeat=False):
    head = f"{ICON[typ]} {typ} {level} ${t['symbol']}"
    px = ctx["fut_px"]
    if dirn in ("LONG", "SHORT") and px:
        k = f"{t['symbol']}|{dirn}"
        short = dirn == "SHORT"
        tp = px * (1 - TARGET_PCT / 100) if short else px * (1 + TARGET_PCT / 100)
        sl = px * (1 + STOP_PCT / 100) if short else px * (1 - STOP_PCT / 100)
        okx = "" if OKX_SYMS is None else ("OKX: ✅ є" if t["symbol"] in OKX_SYMS else "OKX: ❌ немає")
        rep = (f"🔁 ПОВТОР — по цій події вже був сигнал о "
               f"{datetime.fromtimestamp(EVENTS.get(k, ts)).strftime('%H:%M')}, угоду не дублюємо\n") if is_repeat else ""
        trade = (f"{'🔴 ШОРТ' if short else '🟢 ЛОНГ'} {t['fut']}  {okx}\n" + rep +
                 f"Вхід:  ~{fp(px, px)} (ринком)\n"
                 f"Тейк:  {fp(tp, px)}  ({'-' if short else '+'}{TARGET_PCT:g}%)\n"
                 f"Стоп:  {fp(sl, px)}  ({'+' if short else '-'}{STOP_PCT:g}%)\n"
                 f"Якщо не закрилась — вийти через {EVAL_H:g}г "
                 f"({datetime.fromtimestamp(ts + EVAL_H * 3600).strftime('%d.%m %H:%M')})\n")
    else:
        trade = "ℹ️ Інформаційний сигнал, без угоди\n"
    return (f"{head}\n{trade}"
            f"—\n${usd:,.0f}" + (f" = {pct}% капи" if pct else "") + (f" | {note}" if note else "") +
            f"\nГаманців за {WINDOW_H:g}г: {len(wallets)}, сума ${tot:,.0f}\n"
            f"1г {ctx['px_1h']}% | OI 1г {ctx['oi_1h']}% | обсяг x{ctx['vol_x']} | стакан bid/ask {ctx['book_imb']}\n"
            f"https://bscscan.com/tx/{txh}")

def signal(typ, t, wallet, usd, pct, txh, counterparty="", note=""):
    dirn = DIRECTION[typ]
    _, mcap = PRICES.get(t["token"], (0, 0))
    if dirn in ("LONG", "SHORT") and MIN_MCAP > 0 and mcap > 0 and mcap < MIN_MCAP:
        log(f"(мікро-капа) {typ} ${t['symbol']} mcap=${mcap / 1e6:.1f}M < ${MIN_MCAP / 1e6:g}M — пропускаю")
        return

    ts = time.time(); key = f"{typ}|{t['token']}"
    prev = [x for x in RECENT.get(key, []) if ts - x[0] < WINDOW_H * 3600]
    new_wallet = wallet not in {x[1] for x in prev}
    lst = prev + [[ts, wallet, usd]]
    RECENT[key] = lst
    wallets = {x[1] for x in lst}; tot = sum(x[2] for x in lst)
    level = "CLUSTER" if len(wallets) >= 2 else "SINGLE"

    if ts - LAST_ALERT.get(key, 0) < COOLDOWN_MIN * 60 and not (level == "CLUSTER" and new_wallet):
        log(f"(кулдаун) {typ} ${t['symbol']} ${usd:,.0f}"); return

    ctx = fut_context(t["fut"])

    # Анти-памп захист для SHORT: не шортимо проти сильного висхідного розгону ціни або OI
    if dirn == "SHORT":
        if (ctx["px_1h"] is not None and ctx["px_1h"] > 1.0) or (ctx["oi_1h"] is not None and ctx["oi_1h"] > 2.0):
            log(f"(анти-памп) {typ} ${t['symbol']} px_1h={ctx['px_1h']}% oi_1h={ctx['oi_1h']}% — пропускаю")
            return

    LAST_ALERT[key] = ts
    ev_key = f"{t['symbol']}|{dirn}"
    is_repeat = (dirn in ("LONG", "SHORT")) and (ts - EVENTS.get(ev_key, 0) < WINDOW_H * 3600)
    if dirn in ("LONG", "SHORT") and not is_repeat:
        EVENTS[ev_key] = ts

    sid = f"{int(ts)}_{typ}_{t['symbol']}"
    csv_note = f"REPEAT; {note}" if (is_repeat and note) else ("REPEAT" if is_repeat else note)
    row = dict(id=sid, time_utc=datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
               type=typ, dir=dirn, symbol=t["symbol"], fut=t["fut"], level=level, wallet=wallet,
               counterparty=counterparty, usd=round(usd), pct_mcap=pct, n_wallets=len(wallets),
               note=csv_note, tx=txh, **ctx)
    write_signal(row)

    # У трекер і в автоторгівлю відправляємо лише первинну подію (без повторів у межах WINDOW_H)
    if dirn != "INFO" and ctx["fut_px"] and not is_repeat:
        OPEN.append({"id": sid, "fut": t["fut"], "ts": ts, "px": ctx["fut_px"], "dir": dirn, "row": row})
        if TRADER:
            try: TRADER.on_signal(typ, t["symbol"], dirn, sid)
            except Exception as e: log(f"OKX помилка угоди {t['symbol']}: {e}")

    msg = signal_text(typ, t, dirn, level, usd, pct, note, wallets, tot, ctx, txh, wallet, ts, is_repeat)
    log(msg.replace("\n", " | ")); tg(msg)

# ------------------------- ОБРОБКА ПОДІЙ -------------------------
def handle_pool_flows(logs, pools, toks):
    """Покупки/продажі з переказів: токен вийшов з пулу = купівля, зайшов у пул = продаж."""
    agg = {}  # (tx, token) -> raw net (+купівля / -продаж)
    for lg in logs:
        if len(lg["topics"]) < 3 or lg["data"] in ("0x", ""): continue
        tok = lg["address"].lower()
        if tok not in toks: continue
        frm, to = addr_of(lg["topics"][1]), addr_of(lg["topics"][2])
        raw = int(lg["data"][2:66], 16); k = (lg["transactionHash"], tok)
        if frm in pools and pools[frm]["token"] == tok: agg[k] = agg.get(k, 0) + raw
        if to in pools and pools[to]["token"] == tok: agg[k] = agg.get(k, 0) - raw
    for (txh, tok), raw in agg.items():
        if not raw: continue
        t = toks[tok]
        usd, pct = usd_of(tok, abs(raw), t["dec"])
        if not is_big(usd, pct): continue
        tx = rpc("eth_getTransactionByHash", [txh])
        if not tx or not tx.get("from"): continue
        frm, nonce = tx["from"].lower(), int(tx["nonce"], 16)
        if raw > 0:
            if nonce <= FRESH_NONCE:
                watch(frm, "FRESH_BUY", t)
                signal("FRESH_BUY", t, frm, usd, pct, txh, note=f"tx гаманця #{nonce}")
            else: log(f"велика покупка ${t['symbol']} ${usd:,.0f} (не свіжий, tx #{nonce})")
        elif frm in WATCH and WATCH[frm].get("token") == tok:
            signal("WATCH_OUT", t, frm, usd, pct, txh, counterparty="DEX",
                   note=f"продаж на DEX; у слежці через {WATCH[frm]['reason']}")

def _pos(lg): return int(lg["blockNumber"], 16), int(lg["logIndex"], 16)

def trace_deposit(tok, dep, sweep_lg, need_raw):
    """Свіп dep -> гарячий гаманець. Шукаємо, хто заводив токен на dep ДО свіпу."""
    sweep_pos = _pos(sweep_lg)
    hi, lo_limit, step = sweep_pos[0], max(0, sweep_pos[0] - TRACE_BLOCKS), TRACE_STEP
    senders, got = {}, 0
    while hi > lo_limit and got < need_raw * 0.95:
        lo = max(lo_limit, hi - step + 1)
        try:
            logs = rpc("eth_getLogs", [{"fromBlock": hex(lo), "toBlock": hex(hi), "address": tok,
                                        "topics": [T_TRANSFER, None, topic_addr(dep)]}])
        except RuntimeError:
            if step > 500: step //= 2; continue
            log("trace: RPC не віддав логи, зупиняю пошук"); break
        for l in sorted(logs, key=_pos, reverse=True):
            if _pos(l) >= sweep_pos or len(l["topics"]) < 3 or l["data"] in ("0x", ""): continue
            s = addr_of(l["topics"][1])
            if s == dep or s == ZERO: continue
            raw = int(l["data"][2:66], 16)
            d = senders.setdefault(s, {"raw": 0, "tx": l["transactionHash"], "block": _pos(l)[0]})
            d["raw"] += raw; d["block"] = min(d["block"], _pos(l)[0]); got += raw
            if got >= need_raw * 0.95: break
        hi = lo - 1
    return senders

def block_age_min(block):
    try: return round((time.time() - int(rpc("eth_getBlockByNumber", [hex(block), False])["timestamp"], 16)) / 60)
    except Exception: return None

def sweep_signal(lg, t, dep, ex, raw, usd, pct):
    """Свіп з нової депозитки -> сигнал на справжнього відправника, якщо депозит свіжий."""
    snd = trace_deposit(t["token"], dep, lg, raw)
    if snd:
        hubs = [s for s in snd if is_hub(s)]
        for s in hubs: snd.pop(s)
        if hubs and not snd:
            log(f"(хаб) свіп ${t['symbol']} ${usd:,.0f} на {ex} від бота {hubs[0]} — пропускаю"); return
    if not snd:
        log(f"(старий свіп) ${t['symbol']} на {ex} — відправника не знайдено за {TRACE_BLOCKS} блоків, пропускаю")
        return
    main = max(snd, key=lambda s: snd[s]["raw"])
    ago = block_age_min(min(d["block"] for d in snd.values()))
    if ago is None or ago > MAX_SWEEP_AGE_MIN:
        log(f"(старий свіп) ${t['symbol']} депозит {ago} хв тому (>{MAX_SWEEP_AGE_MIN:g} хв) — депозитку вивчено, сигнал пропускаю")
        return
    flags = []
    fresh, n = is_fresh_eoa(main)
    if fresh: flags.append(f"свіжий відправник (tx {n})")
    if exch_of(main): flags.append(f"відправник — {exch_of(main)} (переказ між біржами)")
    note = (f"завід на {ex}, депозит {ago} хв тому (побачено на свіпі)"
            + (f", відправників {len(snd)}" if len(snd) > 1 else "") + ("; " + "; ".join(flags) if flags else ""))
    typ = "WATCH_OUT" if (main in WATCH and WATCH[main].get("token") == t["token"]) else "CEX_IN"
    if typ == "WATCH_OUT": note += f"; у слежці через {WATCH[main]['reason']}"
    key, now = f"{typ}|{t['token']}", time.time()
    for s, d in snd.items():
        if s != main: RECENT.setdefault(key, []).append([now, s, usd_of(t["token"], d["raw"], t["dec"])[0]])
    musd, mpct = usd_of(t["token"], snd[main]["raw"], t["dec"])
    signal(typ, t, main, musd, mpct, snd[main]["tx"], counterparty=ex, note=note)

_not_dep = set()
def handle_transfers(logs, toks):
    for lg in logs:
        if len(lg["topics"]) < 3 or lg["data"] in ("0x", ""): continue
        tok = lg["address"].lower(); t = toks.get(tok)
        if not t: continue
        frm, to = addr_of(lg["topics"][1]), addr_of(lg["topics"][2])
        raw = int(lg["data"][2:66], 16)
        usd, pct = usd_of(tok, raw, t["dec"])
        txh = lg["transactionHash"]
        # навчання: X -> гарячий гаманець = X депозитна адреса біржі
        learned = False
        if to in CEX and frm not in CEX and frm != ZERO and frm not in DEPOSITS and frm not in _not_dep:
            if is_eoa(frm):
                DEPOSITS[frm] = CEX[to]; learned = True
            else: _not_dep.add(frm)
        if not is_big(usd, pct): continue
        if learned:
            sweep_signal(lg, t, frm, CEX[to], raw, usd, pct)
        elif frm == ZERO:
            watch(to, "MINT", t)
            signal("MINT", t, to, usd, pct, txh, note="мінт")
        elif frm in WATCH and WATCH[frm].get("token") == tok and not exch_of(frm) and exch_of(to):
            where = exch_of(to)
            signal("WATCH_OUT", t, frm, usd, pct, txh, counterparty=where,
                   note=f"завід на {where}; у слежці через {WATCH[frm]['reason']}")
        elif exch_of(to) and not exch_of(frm):
            ex = exch_of(to)
            if frm not in WATCH and is_hub(frm):
                log(f"(хаб) завід ${t['symbol']} ${usd:,.0f} на {ex} від бота {frm} — пропускаю"); continue
            signal("CEX_IN", t, frm, usd, pct, txh, counterparty=ex, note=f"завід на {ex}")
        elif frm in CEX and not exch_of(to):
            fresh, n = is_fresh_eoa(to)
            if fresh:
                watch(to, "CEX_OUT", t)
                signal("CEX_OUT", t, to, usd, pct, txh, counterparty=CEX[frm],
                       note=f"вивід з {CEX[frm]} на свіжий гаманець (tx {n})")

# ------------------------- ТРЕКЕР РЕЗУЛЬТАТІВ -------------------------
def evaluate(sig, klines):
    """LONG: +TARGET раніше -STOP; SHORT: дзеркально. Обидва в одній свічці = стоп."""
    px, short = sig["px"], sig.get("dir") == "SHORT"
    for k in klines:
        t, hi, lo = k[0] / 1000, float(k[2]), float(k[3])
        if t < sig["ts"]: continue
        mins = max(1, round((t - sig["ts"]) / 60))
        fav = (px - lo) / px * 100 if short else (hi - px) / px * 100
        adv = (hi - px) / px * 100 if short else (px - lo) / px * 100
        if adv >= STOP_PCT: return "STOP", -STOP_PCT, mins
        if fav >= TARGET_PCT: return "TARGET", TARGET_PCT, mins
    if time.time() - sig["ts"] > EVAL_H * 3600:
        last = float(klines[-1][4]) if klines else px
        r = (last / px - 1) * 100
        return "TIMEOUT", round(-r if short else r, 2), int(EVAL_H * 60)
    return None

def track_outcomes():
    done = []
    for sig in OPEN:
        try: k = get(f"{FAPI}/fapi/v1/klines", symbol=sig["fut"], interval="1m",
                     startTime=int(sig["ts"] * 1000) - 60000, limit=1500)
        except Exception: continue
        r = evaluate(sig, k)
        if r:
            write_signal(dict(sig["row"], outcome=r[0], result_pct=r[1], minutes=r[2]))
            log(f"РЕЗУЛЬТАТ {sig['id']}: {r[0]} {r[1]}% за {r[2]} хв"); done.append(sig["id"])
    OPEN[:] = [s for s in OPEN if s["id"] not in done]

# ------------------------- ГОЛОВНИЙ ЦИКЛ -------------------------
def get_logs(frm, to, addresses, topics, chunk=None):
    chunk = chunk or ADDR_CHUNK
    out = []
    for i in range(0, len(addresses), chunk):
        out += rpc("eth_getLogs", [{"fromBlock": hex(frm), "toBlock": hex(to),
                                    "address": addresses[i:i + chunk], "topics": topics}])
    return out

def fetch_transfers(frm, to, token_addrs):
    return get_logs(frm, to, token_addrs, [T_TRANSFER])

TRADER = None
def init_trader():
    global TRADER
    f = P("okx_keys.json")
    if not os.path.exists(f): log("okx_keys.json немає — тільки сигнали, без угод"); return
    try:
        from okx_trader import Trader
        TRADER = Trader(f, DIR, log, WINDOW_H, EVAL_H, TARGET_PCT, STOP_PCT)
        if not TRADER.enabled: TRADER = None
    except Exception as e:
        log(f"OKX не підключено: {e} — працюю тільки на сигнали"); TRADER = None

def scan():
    init_trader(); load_okx_syms()
    if not (TG_TOKEN and TG_CHAT): log("Telegram не налаштовано (немає tg.json) — сигнали тільки в консолі")
    pools = load_universe()
    if not pools: log("Немає пулів — перевір інтернет / DexScreener"); return
    toks = tokens_of(pools); refresh_prices(pools)
    token_addrs = list(toks)
    head = int(rpc("eth_blockNumber", []), 16)
    last = max(load_json(F_STATE, {}).get("last_block") or head - 5, head - 400)
    t_prices = t_track = time.time()
    log(f"Старт v2.2. Монет: {len(toks)}, пулів: {len(pools)}, бірж. гаманців: {len(CEX)}, "
        f"депозитів: {len(DEPOSITS)}, у слежці: {len(WATCH)}")
    tg(f"✅ ndscan запущено: {len(toks)} монет, стежу за сигналами")
    span, ok_streak, t_lag = 10, 0, time.time()
    while not STOP:
        STATUS["loop_ts"] = time.time()
        try:
            head = int(rpc("eth_blockNumber", []), 16)
            if head - last > MAX_LAG:
                log(f"відставання {head - last} блоків — пропускаю до свіжих"); last = head - 5
            while last < head and not STOP:
                to = min(head, last + span)
                try:
                    trs = fetch_transfers(last + 1, to, token_addrs)
                except RuntimeError as e:
                    ok_streak = 0
                    if span > 1:
                        span = max(1, span // 2); log(f"RPC не віддав логи, діапазон -> {span} блоків ({str(e)[:120]})"); continue
                    raise
                handle_pool_flows(trs, pools, toks)
                handle_transfers(trs, toks)
                last = to
                ok_streak += 1
                if ok_streak >= 30 and span < 20: span += 1; ok_streak = 0
            if time.time() - t_lag > 600:
                log(f"працюю: блок {last}, відставання {head - last}, діапазон {span}, у слежці {len(WATCH)}, депозитів {len(DEPOSITS)}")
                t_lag = time.time()
            save_state(last)
            STATUS.update(block=last, lag=head - last, block_ts=time.time())
            if time.time() - t_prices > 300: refresh_prices(pools); t_prices = time.time()
            if time.time() - t_track > 60:
                track_outcomes(); t_track = time.time(); save_state(last)
                if TRADER:
                    try: TRADER.housekeep()
                    except Exception as e: log(f"OKX housekeep: {e}")
            if time.time() - load_json(F_UNIVERSE, {"ts": 0})["ts"] > 24 * 3600:
                pools = build_universe(); toks = tokens_of(pools); refresh_prices(pools)
                token_addrs = list(toks)
        except KeyboardInterrupt:
            save_state(last); log("Зупинено"); return
        except Exception as e:
            log("помилка циклу:", e); time.sleep(10)
        time.sleep(POLL_SEC)
    save_state(last); log("Зупинено")

# ------------------------- ЗВІТ -------------------------
def report():
    if not os.path.exists(F_SIGNALS): print("Сигналів ще немає"); return
    with open(F_SIGNALS, encoding="utf-8", errors="replace") as f:
        rows = list(csv.DictReader(f))
    allids = {r["id"]: r for r in rows}
    res = {r["id"]: r for r in rows if r.get("outcome")}
    ts_of = lambda r: datetime.strptime(r["time_utc"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
    events, dups, start = [], 0, {}
    for r in sorted(res.values(), key=ts_of):
        if r.get("note", "").startswith("REPEAT"):
            dups += 1; continue
        k, t0 = (r["symbol"], r["dir"]), ts_of(r)
        if k in start and t0 - start[k] < WINDOW_H * 3600: dups += 1; continue
        start[k] = t0; events.append(r)
    print(f"Сигналів: {len(allids)}, закрито: {len(res)}, відкрито/INFO: {len(allids) - len(res)}")
    print(f"Подій (без дублів): {len(events)}, дублів відкинуто: {dups}\n")
    print(f"Критерій: n>=15 подій і >=50% дають +{TARGET_PCT:g}% раніше за -{STOP_PCT:g}% (у бік сигналу)\n")
    groups = {}
    for r in events:
        groups.setdefault(r["type"], []).append(r)
        groups.setdefault(f"{r['type']}/{r['level']}", []).append(r)
    for g in sorted(groups):
        rs = groups[g]; n = len(rs); win = sum(r["outcome"] == "TARGET" for r in rs)
        avg = sum(float(r["result_pct"]) for r in rs) / n
        verdict = ("✅" if win / n >= 0.5 else "❌") if n >= 15 and "/" not in g else ("…" if "/" not in g else "")
        print(f"{g:20} n={n:3}  ціль першою {win}/{n} = {win / n * 100:3.0f}%  сер. {avg:+.2f}%  {verdict}")
    print("\n✅/❌ — вердикт по типу сигналу (від 15 подій). … — ще мало даних.")

def recheck():
    """Переоцінити всі закриті сигнали виправленою логікою. Запускати при ЗУПИНЕНОМУ сканері."""
    if not os.path.exists(F_SIGNALS): print("Сигналів немає"); return
    with open(F_SIGNALS, encoding="utf-8", errors="replace") as f:
        rows = list(csv.DictReader(f))
    base = {}
    for r in rows: base.setdefault(r["id"], {k: r[k] for k in SIG_FIELDS if k not in ("outcome", "result_pct", "minutes")})
    closed = {r["id"] for r in rows if r.get("outcome")}
    out, reopened = list(base.values()), []
    opened = {s["id"] for s in OPEN}
    for sid in closed:
        b = base[sid]
        if b.get("dir") == "INFO": continue
        ts = datetime.strptime(b["time_utc"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
        sig = {"id": sid, "fut": b["fut"], "ts": ts, "px": float(b["fut_px"]), "dir": b["dir"], "row": b}
        k = get(f"{FAPI}/fapi/v1/klines", symbol=b["fut"], interval="1m", startTime=int(ts * 1000) - 60000, limit=1500)
        r = evaluate(sig, k)
        if r:
            out.append(dict(b, outcome=r[0], result_pct=r[1], minutes=r[2])); print(f"{sid}: {r[0]} {r[1]}% за {r[2]} хв")
        else:
            if sid not in opened: OPEN.append(sig)
            reopened.append(sid); print(f"{sid}: ще відкритий — повернуто в трекер")
        time.sleep(0.2)
    with open(F_SIGNALS, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, SIG_FIELDS); w.writeheader()
        for r in out: w.writerow({k: r.get(k, "") for k in SIG_FIELDS})
    save_json(F_OPEN, OPEN)
    print(f"\nПереоцінено: {len(closed)}, повернуто у відкриті: {len(reopened)}")

if __name__ == "__main__":
    a = sys.argv[1] if len(sys.argv) > 1 else ""
    if a == "report": report()
    elif a == "recheck": recheck()
    elif a == "rebuild": build_universe()
    else: scan()