"""
bingx_trader — автоторгівля сигналами ndscan на BingX (USDT-M perpetual swaps).

Ключі та налаштування — у файлі bingx_keys.json поруч зі скриптом (НЕ в коді):
{
  "key": "...", "secret": "...",
  "demo": true,             # демо-рахунок BingX (VST, open-api-vst.bingx.com)
  "enabled": true,          # false — сканер працює як раніше, без угод
  "trade_usd": 50,          # розмір позиції (номінал, $); при auto_ladder — запасний
  "lever": 3,               # плече (isolated)
  "max_pos": 3,             # макс. одночасних позицій
  "max_spread_pct": 1.0,    # макс. спред bid/ask у % (захист від неліквідного прострілу)
  "types": ["CEX_IN","CEX_OUT","FRESH_BUY","WATCH_OUT"],  # які сигнали торгувати (лонг і шорт)
  "auto_ladder": true,      # розгін «драбинкою»: маржа від балансу замість фіксованого trade_usd
  "margin_pct": 0.45,       # частка бази драбинки на одну угоду (маржа); notional = маржа * lever
  "ladder_steps": [100, 140, 200, 280, 400, 560, 800, 1100],  # сходинки балансу USDT
  "step_down_dd_pct": 20.0  # просадка від сходинки або її піку -> база на сходинку нижче
}
Угоди: trades.json (відкриті), trades.csv (журнал: рядок OPEN при вході, CLOSE з PnL при виході).
Драбинка: trader_state.json (пік балансу, активна сходинка, рівень відновлення після відкату).
"""
import csv, hmac, hashlib, json, math, os, time
from datetime import datetime, timezone
from decimal import Decimal, ROUND_DOWN, ROUND_UP
from urllib.parse import quote
import requests


class BingXError(RuntimeError): pass


class BingX:
    LIVE = "https://open-api.bingx.com"
    DEMO = "https://open-api-vst.bingx.com"

    def __init__(self, key, secret, demo=True):
        self.k, self.s, self.demo = key, secret, demo
        self.base = self.DEMO if demo else self.LIVE
        self.S = requests.Session()

    def req(self, method, path, params=None, auth=True):
        """Підпис: параметри за абеткою, key=value без кодування, HMAC-SHA256 hex; у URL кодуються лише JSON-значення."""
        last_err = None
        for attempt in range(3):
            p = {k: v for k, v in (params or {}).items() if v is not None}
            if auth:
                p["timestamp"] = int(time.time() * 1000)
                p["recvWindow"] = 5000
            items = sorted((k, str(v)) for k, v in p.items())
            raw = "&".join(f"{k}={v}" for k, v in items)
            url_q = "&".join(f"{k}={quote(v, safe='') if ('{' in v or '[' in v) else v}" for k, v in items)
            if auth:
                url_q += ("&" if url_q else "") + "signature=" + hmac.new(self.s.encode(), raw.encode(), hashlib.sha256).hexdigest()
            h = {"X-BX-APIKEY": self.k} if auth else {}
            try:
                r = self.S.request(method, self.base + path + ("?" + url_q if url_q else ""), headers=h, timeout=15)
                if r.status_code in (429, 502, 503, 504):
                    time.sleep(1.5 * (attempt + 1)); continue
                j = r.json()
            except Exception as e:
                last_err = e
                if attempt < 2 and method == "GET":
                    time.sleep(1.5 * (attempt + 1)); continue
                raise BingXError(f"HTTP/JSON error {method} {path}: {e}") from e
            if str(j.get("code")) != "0":
                raise BingXError(f"{j.get('code')} {j.get('msg')}".strip())
            return j.get("data")
        raise BingXError(f"Max retries {method} {path}: {last_err}")


def _round(x, step, mode=ROUND_DOWN):
    s = Decimal(str(step))
    return float((Decimal(str(x)) / s).to_integral_value(mode) * s)


def _step(precision):
    return float(Decimal(1).scaleb(-int(precision)))


def _f(x, default=0.0):
    try: return float(x)
    except (TypeError, ValueError): return default


DEFAULT_STEPS = [100, 140, 200, 280, 400, 560, 800, 1100]


def ladder_eval(bal, steps, st, dd_pct):
    """
    Драбинка розгону. bal — закритий баланс USDT (без нереалізованого PnL).
    st — стан {"peak_equity", "step_idx", "step_peak", "recover_at"}; повертає (база, новий стан, примітка).
    - сходинка = найбільший поріг <= bal; нижче першого порогу база = сам баланс;
    - просадка >= dd_pct від порогу сходинки або від її піку -> на сходинку нижче;
      нижча сходинка тримається, доки баланс не відновить пік, з якого почалась просадка (recover_at).
    """
    st = dict(st or {})
    k = 1 - dd_pct / 100
    nominal = max((i for i, v in enumerate(steps) if v <= bal), default=-1)
    st["peak_equity"] = max(float(st.get("peak_equity") or 0), bal)
    act = st.get("step_idx")
    if act is None or not -1 <= act < len(steps):
        act, st["step_peak"] = nominal, bal
    rec = st.get("recover_at")
    note = ""
    if rec is not None and bal >= rec:
        rec, note = None, "баланс відновився — блокування сходинки знято"
    if nominal > act and rec is None:
        act, st["step_peak"], note = nominal, bal, f"вгору на сходинку {steps[nominal]:g}"
    elif nominal < act:
        act, st["step_peak"], note = nominal, bal, ("вниз: баланс нижче порогу " + (f"{steps[nominal]:g}" if nominal >= 0 else "першої сходинки"))
    st["step_peak"] = max(float(st.get("step_peak") or bal), bal)
    if act >= 0 and (bal <= steps[act] * k or bal <= st["step_peak"] * k):
        rec = max(rec or 0, st["step_peak"], steps[act])
        note = (f"просадка {(1 - bal / st['step_peak']) * 100:.1f}% від піку {st['step_peak']:g} — відкат зі сходинки "
                f"{steps[act]:g} на {steps[act - 1]:g}" if act > 0 else f"просадка — нижче першої сходинки")
        act, st["step_peak"] = act - 1, bal
    st["step_idx"], st["recover_at"] = act, rec
    base = float(steps[act]) if act >= 0 else bal
    return base, st, note


class Trader:
    def __init__(self, cfg_path, data_dir, log, window_h=6, eval_h=24, target=5, stop=3):
        cfg = json.load(open(cfg_path, encoding="utf-8-sig"))
        self.log, self.window_h, self.eval_h, self.target, self.stop = log, window_h, eval_h, target, stop
        self.enabled = cfg.get("enabled", True)
        self.usd = float(cfg.get("trade_usd", 50))
        self.lever = int(cfg.get("lever", 3))
        self.max_pos = int(cfg.get("max_pos", 3))
        self.max_spread_pct = float(cfg.get("max_spread_pct", 1.0))
        self.types = set(cfg.get("types", ["CEX_IN", "CEX_OUT", "FRESH_BUY", "WATCH_OUT"]))
        self.auto_ladder = bool(cfg.get("auto_ladder", True))
        self.margin_pct = float(cfg.get("margin_pct", 0.45))
        self.steps = sorted(float(x) for x in (cfg.get("ladder_steps") or DEFAULT_STEPS))
        self.dd_pct = float(cfg.get("step_down_dd_pct", 20.0))
        self.api = BingX(cfg["key"], cfg["secret"], cfg.get("demo", True))
        self.f_open, self.f_csv, self.f_last = (os.path.join(data_dir, f) for f in ("trades.json", "trades.csv", "trades_last.json"))
        self.f_ladder = os.path.join(data_dir, "trader_state.json")
        self.ladder = self._load(self.f_ladder, {})  # драбинка: пік, сходинка, рівень відновлення
        self.trades = self._load(self.f_open, {})   # symbol (BTC-USDT) -> trade
        self.last = self._load(self.f_last, {})     # "symbol|dir" -> ts останньої угоди
        dual = self.api.req("GET", "/openApi/swap/v1/positionSide/dual") or {}
        self.hedge = str(dual.get("dualSidePosition")).lower() == "true"
        bal = self._balance_row()
        self.inst = {}
        for c in self.api.req("GET", "/openApi/swap/v2/quote/contracts", auth=False) or []:
            sym = c.get("symbol", "")
            if not sym.endswith("-USDT") or str(c.get("apiStateOpen", "true")).lower() == "false" or str(c.get("status", "1")) == "0":
                continue
            base = sym.split("-")[0]
            self.inst[base] = c
        for base, c in list(self.inst.items()):   # 1000PEPE-USDT -> PEPE (ndscan прибирає префікс 1000 з Binance)
            short = base.lstrip("0123456789")
            if short and short != base and short not in self.inst:
                self.inst[short] = c
        self.log(f"BingX {'ДЕМО' if self.api.demo else 'РЕАЛ'}: баланс {_f(bal.get('equity')):,.2f}$, "
                 f"свопів {len(self.inst)}, режим {'long/short' if self.hedge else 'one-way'}, "
                 f"угода {'драбинкою' if self.auto_ladder else f'{self.usd:g}$'} x{self.lever}, макс {self.max_pos}, "
                 f"спред<={self.max_spread_pct}%, типи {sorted(self.types)}, {'УВІМКНЕНО' if self.enabled else 'ВИМКНЕНО'}")
        if self.auto_ladder:
            self.log(f"BingX драбинка: сходинки {[f'{x:g}' for x in self.steps]}, маржа {self.margin_pct * 100:g}% бази, "
                     f"відкат при просадці {self.dd_pct:g}%")
            self._position_size("старт", self._closed_balance(bal))

    def _balance_row(self):
        """Рядок балансу ф'ючерсного акаунта: USDT (на демо — VST)."""
        data = self.api.req("GET", "/openApi/swap/v3/user/balance")
        rows = data if isinstance(data, list) else [((data or {}).get("balance") or data or {})]
        for asset in ("USDT", "VST"):
            for r in rows:
                if r.get("asset") == asset:
                    return r
        return rows[0] if rows else {}

    @staticmethod
    def _closed_balance(row):
        """Закритий баланс: balance (без нереалізованого PnL), інакше availableMargin."""
        for k in ("balance", "availableMargin"):
            if row.get(k) not in (None, ""):
                return float(row[k])
        return None

    @staticmethod
    def _load(f, d):
        try:
            with open(f, encoding="utf-8") as fp:
                return json.load(fp)
        except Exception:
            return d

    @staticmethod
    def _atomic_dump(path, obj):
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fp:
            json.dump(obj, fp, indent=1)
        os.replace(tmp, path)

    def _save(self):
        self._atomic_dump(self.f_open, self.trades)
        self._atomic_dump(self.f_last, self.last)

    def _position_size(self, tag, balance=None):
        """Номінал угоди, $: драбинка від балансу або статичний trade_usd (fallback)."""
        if not self.auto_ladder:
            return self.usd
        if balance is None:
            try:
                balance = self._closed_balance(self._balance_row())
            except BingXError as e:
                self.log(f"{tag}: баланс BingX недоступний ({e}) — fallback на trade_usd {self.usd:g}$"); return self.usd
        if balance is None:
            self.log(f"{tag}: у відповіді BingX немає балансу USDT — fallback на trade_usd {self.usd:g}$"); return self.usd
        base, self.ladder, note = ladder_eval(balance, self.steps, self.ladder, self.dd_pct)
        self._atomic_dump(self.f_ladder, self.ladder)
        margin = round(base * self.margin_pct, 2)
        notional = round(margin * self.lever, 2)
        idx = self.ladder["step_idx"]
        step = f"{self.steps[idx]:g}" if idx >= 0 else f"нижче {self.steps[0]:g} (база = баланс)"
        self.log(f"{tag}: баланс {balance:,.2f} USDT, сходинка {step}, пік {self.ladder['peak_equity']:,.2f}"
                 + (f", утримання до {self.ladder['recover_at']:,.2f}" if self.ladder.get("recover_at") else "")
                 + f" | маржа {margin:g}$ x{self.lever} = {notional:g}$" + (f" | {note}" if note else ""))
        return notional

    def _book(self, sym):
        d = self.api.req("GET", "/openApi/swap/v2/quote/bookTicker", {"symbol": sym}, auth=False) or {}
        d = d.get("book_ticker", d)
        bid = _f(d.get("bidPrice", d.get("bid_price")))
        ask = _f(d.get("askPrice", d.get("ask_price")))
        return bid, ask

    def on_signal(self, typ, symbol, dirn, sid):
        if not self.enabled or typ not in self.types or dirn not in ("LONG", "SHORT"): return
        tag = f"BingX {typ} {dirn} {symbol}"
        ev_key = f"{symbol}|{dirn}"
        if time.time() - self.last.get(ev_key, 0) < self.window_h * 3600:
            self.log(f"{tag}: та сама подія (<{self.window_h:g}г від попередньої угоди) — пропуск"); return
        c = self.inst.get(symbol)
        if not c: self.log(f"{tag}: на BingX немає свопу — пропуск"); return
        sym = c["symbol"]
        if sym in self.trades: self.log(f"{tag}: вже є позиція — пропуск"); return
        if len(self.trades) >= self.max_pos: self.log(f"{tag}: ліміт {self.max_pos} позицій — пропуск"); return

        bid_px, ask_px = self._book(sym)
        if not (bid_px and ask_px): self.log(f"{tag}: немає bid/ask — пропуск"); return
        mid_px = (bid_px + ask_px) / 2
        spread_pct = (ask_px - bid_px) / mid_px * 100
        if spread_pct > self.max_spread_pct:
            self.log(f"{tag}: занадто широкий спред {spread_pct:.2f}% > {self.max_spread_pct}% — пропуск"); return

        short = dirn == "SHORT"
        ref_px = bid_px if short else ask_px
        lot, tick = _step(c.get("quantityPrecision", 0)), _step(c.get("pricePrecision", 4))
        min_qty = max(_f(c.get("tradeMinQuantity")), _round(_f(c.get("tradeMinUSDT")) / ref_px, lot, ROUND_UP))
        notional = self._position_size(tag)
        qty = _round(notional / ref_px, lot)
        if qty <= 0 or qty < min_qty:
            self.log(f"{tag}: {notional:g}$ менше мін. лоту ({min_qty:g} = {min_qty * ref_px:,.2f}$) — пропуск"); return

        actual_usd = round(qty * ref_px, 2)
        tp = _round(ref_px * (1 - self.target / 100) if short else ref_px * (1 + self.target / 100), tick)
        sl = _round(ref_px * (1 + self.stop / 100) if short else ref_px * (1 - self.stop / 100), tick)
        pos_side = ("SHORT" if short else "LONG") if self.hedge else "BOTH"

        try: self.api.req("POST", "/openApi/swap/v2/trade/marginType", {"symbol": sym, "marginType": "ISOLATED"})
        except BingXError as e: self.log(f"{tag}: ізольовану маржу не встановлено ({e}) — продовжую")
        try: self.api.req("POST", "/openApi/swap/v2/trade/leverage", {"symbol": sym, "side": pos_side, "leverage": self.lever})
        except BingXError as e: self.log(f"{tag}: плече не встановлено ({e}) — продовжую")

        js = lambda o: json.dumps(o, separators=(",", ":"))
        order = {
            "symbol": sym, "side": "SELL" if short else "BUY", "positionSide": pos_side,
            "type": "MARKET", "quantity": f"{qty:.{max(0, int(c.get('quantityPrecision', 0)))}f}",
            "takeProfit": js({"type": "TAKE_PROFIT_MARKET", "stopPrice": tp, "price": tp, "workingType": "CONTRACT_PRICE"}),
            "stopLoss": js({"type": "STOP_MARKET", "stopPrice": sl, "price": sl, "workingType": "MARK_PRICE"}),
        }
        r = self.api.req("POST", "/openApi/swap/v2/trade/order", order) or {}
        r = r.get("order", r)
        self.trades[sym] = {
            "symbol": symbol, "sid": sid, "type": typ, "dir": dirn, "ts": time.time(),
            "px": ref_px, "sz": qty, "usd": actual_usd, "tp": tp, "sl": sl,
            "posSide": pos_side, "ordId": str(r.get("orderID") or r.get("orderId") or "")
        }
        self.last[ev_key] = time.time()
        self._save()
        self._csv(event="OPEN", time_utc=self._now(), symbol=symbol, type=typ, dir=dirn, sz=qty, usd=actual_usd,
                  open_px=ref_px, tp=tp, sl=sl, signal_id=sid)
        self.log(f"✅ {tag}: відкрито {qty:g} (~{actual_usd:g}$) по ~{ref_px} (спред {spread_pct:.2f}%) | TP {tp} SL {sl}")

    CSV_HEAD = ["event", "time_utc", "symbol", "type", "dir", "sz", "usd", "open_px", "tp", "sl",
                "close_px", "pnl_usdt", "how", "minutes", "signal_id"]

    def _csv(self, **row):
        new = not os.path.exists(self.f_csv)
        with open(self.f_csv, "a", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, self.CSV_HEAD)
            if new: w.writeheader()
            w.writerow({k: row.get(k, "") for k in self.CSV_HEAD})

    @staticmethod
    def _now(): return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

    def _record(self, sym, tr, how):
        pnl = close_px = ""
        try:
            rows = self.api.req("GET", "/openApi/swap/v2/user/income", {
                "symbol": sym, "incomeType": "REALIZED_PNL", "startTime": int(tr["ts"] * 1000), "limit": 100}) or []
            if rows:
                pnl = round(sum(_f(x.get("income")) for x in rows), 4)
        except BingXError as e: self.log(f"BingX історія {sym}: {e}")
        self._csv(event="CLOSE", time_utc=self._now(), symbol=tr["symbol"], type=tr["type"], dir=tr["dir"], sz=tr["sz"],
                  usd=tr.get("usd", self.usd), open_px=tr["px"], tp=tr["tp"], sl=tr["sl"], close_px=close_px,
                  pnl_usdt=pnl, how=how, minutes=round((time.time() - tr["ts"]) / 60), signal_id=tr["sid"])
        self.log(f"💰 BingX {tr['symbol']} закрито ({how}): PnL {pnl} USDT")

    def _match(self, p, sym, tr):
        if p.get("symbol") != sym or _f(p.get("positionAmt")) == 0: return False
        if not self.hedge: return True
        return p.get("positionSide") == tr.get("posSide")

    def housekeep(self):
        if not self.trades: return
        positions = self.api.req("GET", "/openApi/swap/v2/user/positions") or []
        for sym, tr in list(self.trades.items()):
            age = time.time() - tr["ts"]
            pos = next((p for p in positions if self._match(p, sym, tr)), None)
            if pos is None:
                if age < 20: continue  # позиція ще не встигла з'явитися в списку
                self._record(sym, tr, "TP/SL")
                self.trades.pop(sym, None)
                self._save()
            elif age > self.eval_h * 3600:
                self.api.req("POST", "/openApi/swap/v1/trade/closePosition", {"positionId": str(pos.get("positionId"))})
                time.sleep(2)
                self._record(sym, tr, "TIMEOUT")
                self.trades.pop(sym, None)
                self._save()
