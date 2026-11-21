"""
okx_trader — автоторгівля сигналами ndscan на OKX (USDT perpetual swaps).

Ключі та налаштування — у файлі okx_keys.json поруч зі скриптом (НЕ в коді):
{
  "key": "...", "secret": "...", "passphrase": "...",
  "demo": true,             # демо-рахунок OKX (x-simulated-trading)
  "enabled": true,          # false — сканер працює як раніше, без угод
  "trade_usd": 50,          # розмір позиції (номінал, $)
  "lever": 3,               # плече (isolated)
  "max_pos": 3,             # макс. одночасних позицій
  "max_spread_pct": 1.0,    # макс. спред bid/ask у % (захист від неліквідного прострілу)
  "types": ["CEX_IN","CEX_OUT","FRESH_BUY","WATCH_OUT"]  # які сигнали торгувати (лонг і шорт)
}
Угоди: trades.json (відкриті), trades.csv (журнал: рядок OPEN при вході, CLOSE з PnL при виході).
"""
import base64, csv, hmac, hashlib, json, os, time
from datetime import datetime, timezone
from decimal import Decimal, ROUND_DOWN
import requests


class OKXError(RuntimeError): pass


class OKX:
    BASE = "https://www.okx.com"

    def __init__(self, key, secret, passphrase, demo=True):
        self.k, self.s, self.p, self.demo = key, secret, passphrase, demo
        self.S = requests.Session()

    def req(self, method, path, params=None, body=None, auth=True):
        if params:
            path += "?" + "&".join(f"{k}={v}" for k, v in params.items())
        b = json.dumps(body) if body is not None else ""
        last_err = None
        for attempt in range(3):
            h = {"Content-Type": "application/json"}
            if self.demo:
                h["x-simulated-trading"] = "1"
            if auth:
                ts = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
                sig = base64.b64encode(hmac.new(self.s.encode(), (ts + method + path + b).encode(), hashlib.sha256).digest()).decode()
                h.update({"OK-ACCESS-KEY": self.k, "OK-ACCESS-SIGN": sig, "OK-ACCESS-TIMESTAMP": ts, "OK-ACCESS-PASSPHRASE": self.p})
            try:
                r = self.S.request(method, self.BASE + path, data=b or None, headers=h, timeout=15)
                if r.status_code in (429, 502, 503, 504):
                    time.sleep(1.5 * (attempt + 1)); continue
                j = r.json()
            except Exception as e:
                last_err = e
                if attempt < 2 and method == "GET":
                    time.sleep(1.5 * (attempt + 1)); continue
                raise OKXError(f"HTTP/JSON error {method} {path}: {e}") from e
            if j.get("code") != "0":
                d = (j.get("data") or [{}])[0] if isinstance(j.get("data"), list) else {}
                raise OKXError(f"{j.get('code')} {j.get('msg')} {d.get('sCode', '')} {d.get('sMsg', '')}".strip())
            return j["data"]
        raise OKXError(f"Max retries {method} {path}: {last_err}")


def _round(x, step, mode=ROUND_DOWN):
    s = Decimal(str(step))
    return float((Decimal(str(x)) / s).to_integral_value(mode) * s)


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
        self.api = OKX(cfg["key"], cfg["secret"], cfg["passphrase"], cfg.get("demo", True))
        self.f_open, self.f_csv, self.f_last = (os.path.join(data_dir, f) for f in ("trades.json", "trades.csv", "trades_last.json"))
        self.trades = self._load(self.f_open, {})   # instId -> trade
        self.last = self._load(self.f_last, {})     # "symbol|dir" -> ts останньої угоди
        acc = self.api.req("GET", "/api/v5/account/config")[0]
        self.hedge = acc.get("posMode") == "long_short_mode"
        bal = self.api.req("GET", "/api/v5/account/balance", {"ccy": "USDT"})[0]
        self.inst = {}
        for i in self.api.req("GET", "/api/v5/public/instruments", {"instType": "SWAP"}, auth=False):
            if i["instId"].endswith("-USDT-SWAP") and i.get("state") == "live":
                self.inst[i["instId"].split("-")[0]] = i
        self.log(f"OKX {'ДЕМО' if self.api.demo else 'РЕАЛ'}: баланс {float(bal.get('totalEq') or 0):,.2f}$, "
                 f"свопів {len(self.inst)}, режим {'long/short' if self.hedge else 'net'}, "
                 f"угода {self.usd:g}$ x{self.lever}, макс {self.max_pos}, спред<={self.max_spread_pct}%, "
                 f"типи {sorted(self.types)}, {'УВІМКНЕНО' if self.enabled else 'ВИМКНЕНО'}")

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

    def on_signal(self, typ, symbol, dirn, sid):
        if not self.enabled or typ not in self.types or dirn not in ("LONG", "SHORT"): return
        tag = f"OKX {typ} {dirn} {symbol}"
        ev_key = f"{symbol}|{dirn}"
        if time.time() - self.last.get(ev_key, 0) < self.window_h * 3600:
            self.log(f"{tag}: та сама подія (<{self.window_h:g}г від попередньої угоди) — пропуск"); return
        i = self.inst.get(symbol)
        if not i: self.log(f"{tag}: на OKX немає свопу — пропуск"); return
        iid = i["instId"]
        if iid in self.trades: self.log(f"{tag}: вже є позиція — пропуск"); return
        if len(self.trades) >= self.max_pos: self.log(f"{tag}: ліміт {self.max_pos} позицій — пропуск"); return

        tk = self.api.req("GET", "/api/v5/market/ticker", {"instId": iid}, auth=False)[0]
        last_px = float(tk["last"])
        bid_px = float(tk.get("bidPx") or last_px)
        ask_px = float(tk.get("askPx") or last_px)
        mid_px = (bid_px + ask_px) / 2 if (bid_px and ask_px) else last_px
        spread_pct = (ask_px - bid_px) / mid_px * 100 if mid_px else 0.0
        if spread_pct > self.max_spread_pct:
            self.log(f"{tag}: занадто широкий спред {spread_pct:.2f}% > {self.max_spread_pct}% — пропуск"); return

        short = dirn == "SHORT"
        ref_px = bid_px if short else ask_px
        ctval, lot, minsz, tick = float(i["ctVal"]), i["lotSz"], float(i["minSz"]), i["tickSz"]
        sz = _round(self.usd / (ctval * ref_px), lot)
        if sz < minsz: self.log(f"{tag}: {self.usd:g}$ менше мін. лоту ({minsz} конт.) — пропуск"); return

        actual_usd = round(sz * ctval * ref_px, 2)
        tp = _round(ref_px * (1 - self.target / 100) if short else ref_px * (1 + self.target / 100), tick)
        sl = _round(ref_px * (1 + self.stop / 100) if short else ref_px * (1 - self.stop / 100), tick)
        pos_side = ("short" if short else "long") if self.hedge else None

        lev = {"instId": iid, "lever": str(self.lever), "mgnMode": "isolated"}
        if pos_side: lev["posSide"] = pos_side
        try: self.api.req("POST", "/api/v5/account/set-leverage", body=lev)
        except OKXError as e: self.log(f"{tag}: плече не встановлено ({e}) — продовжую")

        order = {
            "instId": iid, "tdMode": "isolated",
            "side": "sell" if short else "buy", "ordType": "market", "sz": str(sz),
            "attachAlgoOrds": [{
                "tpTriggerPx": str(tp), "tpOrdPx": "-1", "tpTriggerPxType": "last",
                "slTriggerPx": str(sl), "slOrdPx": "-1", "slTriggerPxType": "mark"
            }]
        }
        if pos_side: order["posSide"] = pos_side

        r = self.api.req("POST", "/api/v5/trade/order", body=order)[0]
        self.trades[iid] = {
            "symbol": symbol, "sid": sid, "type": typ, "dir": dirn, "ts": time.time(),
            "px": ref_px, "sz": sz, "usd": actual_usd, "tp": tp, "sl": sl,
            "posSide": pos_side, "ordId": r.get("ordId")
        }
        self.last[ev_key] = time.time()
        self._save()
        self._csv(event="OPEN", time_utc=self._now(), symbol=symbol, type=typ, dir=dirn, sz=sz, usd=actual_usd,
                  open_px=ref_px, tp=tp, sl=sl, signal_id=sid)
        self.log(f"✅ {tag}: відкрито {sz:g} конт. (~{actual_usd:g}$) по ~{ref_px} (спред {spread_pct:.2f}%) | TP {tp} SL {sl}")

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

    def _record(self, iid, tr, how):
        pnl = close_px = ""
        want_dir = tr["dir"].lower()
        try:
            hist = self.api.req("GET", "/api/v5/account/positions-history", {"instType": "SWAP", "instId": iid, "limit": "5"})
            match = next((h for h in hist if h.get("direction") == want_dir), hist[0] if hist else None)
            if match:
                pnl, close_px = match.get("realizedPnl", ""), match.get("closeAvgPx", "")
        except OKXError as e: self.log(f"OKX історія {iid}: {e}")
        self._csv(event="CLOSE", time_utc=self._now(), symbol=tr["symbol"], type=tr["type"], dir=tr["dir"], sz=tr["sz"],
                  usd=tr.get("usd", self.usd), open_px=tr["px"], tp=tr["tp"], sl=tr["sl"], close_px=close_px,
                  pnl_usdt=pnl, how=how, minutes=round((time.time() - tr["ts"]) / 60), signal_id=tr["sid"])
        self.log(f"💰 OKX {tr['symbol']} закрито ({how}): PnL {pnl} USDT")

    def housekeep(self):
        if not self.trades: return
        positions = self.api.req("GET", "/api/v5/account/positions", {"instType": "SWAP"})
        live = {(p["instId"], p.get("posSide") or None) for p in positions if float(p.get("pos") or 0) != 0}
        for iid, tr in list(self.trades.items()):
            age = time.time() - tr["ts"]
            key = (iid, tr.get("posSide") if self.hedge else None)
            if key not in live:
                if age < 20: continue  # позиція ще не встигла з'явитися в списку
                self._record(iid, tr, "TP/SL")
                self.trades.pop(iid, None)
                self._save()
            elif age > self.eval_h * 3600:
                body = {"instId": iid, "mgnMode": "isolated", "autoCxl": True}
                if tr.get("posSide"): body["posSide"] = tr["posSide"]
                self.api.req("POST", "/api/v5/trade/close-position", body=body)
                time.sleep(2)
                self._record(iid, tr, "TIMEOUT")
                self.trades.pop(iid, None)
                self._save()