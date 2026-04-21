#!/usr/bin/env python3
"""
Signal Performance Analyzer
Parses alerts.md notification history and checks outcomes via Binance / Yahoo Finance.
Outputs: reports/signal-outcomes.csv + reports/signal-outcomes.md
"""

import re
import csv
import sys
import time
import requests
from datetime import datetime, timezone, timedelta
from dataclasses import dataclass, field
from typing import Optional

# ── Symbol mappings ────────────────────────────────────────────────────────────

INSTRUMENT_TO_SYMBOL = {
    "Solana":       "SOLUSDT",
    "Bitcoin Cash": "BCHUSDT",
    "Ethereum":     "ETHUSDT",
    "Bitcoin":      "BTCUSDT",
    "Nasdaq 100":   "IX.D.NASDAQ.CASH.IP",
    "DAX 40":       "IX.D.DAX.DAILY.IP",
    "FTSE 100":     "IX.D.FTSE.DAILY.IP",
    "S&P 500":      "IX.D.SPTRD.DAILY.IP",
    "Gold":         "CC.D.GOLD.USS.IP",
    "Silver":       "CC.D.SILVER.USS.IP",
    "Oil (Brent)":  "CC.D.LCO.USS.IP",
}

YAHOO_TICKERS = {
    "IX.D.NASDAQ.CASH.IP": "^NDX",
    "IX.D.DAX.DAILY.IP":   "^GDAXI",
    "IX.D.FTSE.DAILY.IP":  "^FTSE",
    "IX.D.SPTRD.DAILY.IP": "^GSPC",
    "CC.D.GOLD.USS.IP":    "GC=F",
    "CC.D.SILVER.USS.IP":  "SI=F",
    "CC.D.LCO.USS.IP":     "BZ=F",
}

BINANCE_SYMBOLS = {"SOLUSDT", "BTCUSDT", "ETHUSDT", "BCHUSDT"}

# Tracked signal types (skip WATCH / anomalies / PARTIAL for main P&L)
TRACKED_TYPES = {"OVERSOLD", "OVERBOUGHT", "TREND_BUY_DIP", "TREND_SELL_RALLY",
                 "PARTIAL_OVERSOLD", "PARTIAL_OVERBOUGHT"}

MAX_HOLD_HOURS = 24

# ── Data model ─────────────────────────────────────────────────────────────────

@dataclass
class Signal:
    timestamp: datetime
    instrument: str
    symbol: str
    direction: str        # LONG / SHORT
    signal_type: str
    entry_price: float
    stop_pts: int
    limit_pts: int
    stop_price: float
    target_price: float
    result: Optional[str] = None       # WIN / LOSS / EXPIRED / OPEN
    exit_timestamp: Optional[datetime] = None
    pnl_pct: Optional[float] = None
    note: str = ""

# ── Alert parser ───────────────────────────────────────────────────────────────

EMOJI_MAP = {
    "🟢": ("OVERSOLD",          "LONG"),
    "🔴": ("OVERBOUGHT",        "SHORT"),
    "📈": ("TREND_BUY_DIP",     "LONG"),
    "📉": ("TREND_SELL_RALLY",  "SHORT"),
    "🟡": ("PARTIAL_OVERSOLD",  "LONG"),
    "🟠": ("PARTIAL_OVERBOUGHT","SHORT"),
}

def parse_alerts(filepath: str) -> list:
    with open(filepath, "r") as fh:
        content = fh.read()

    blocks = re.split(r"(?=BI Alert bot, \[)", content)
    signals = []

    for block in blocks:
        if not block.strip():
            continue

        ts_match = re.search(r"BI Alert bot, \[(\d{2}/\d{2}/\d{4} \d{2}:\d{2})\]", block)
        if not ts_match:
            continue
        ts = datetime.strptime(ts_match.group(1), "%d/%m/%Y %H:%M").replace(tzinfo=timezone.utc)

        # Detect signal type from emoji on the signal title line
        sig_type = None
        direction = None
        instrument = None

        for emoji, (stype, sdir) in EMOJI_MAP.items():
            if emoji in block:
                sig_type = stype
                direction = sdir
                # Extract instrument name from the title line
                title_match = re.search(
                    re.escape(emoji) + r"\s+(.+?)\s+"
                    r"(?:BUY SIGNAL|SELL SIGNAL|TREND BUY|TREND SELL|Partial Buy|Partial Sell|WATCH Buy|WATCH Sell)",
                    block
                )
                if title_match:
                    instrument = title_match.group(1).strip()
                break

        if not sig_type or not instrument:
            continue

        symbol = INSTRUMENT_TO_SYMBOL.get(instrument)
        if not symbol:
            continue

        # Parse price (strip currency symbol, commas)
        price_match = re.search(r"Price:\s*[$£€]([\d,]+\.?\d*)", block)
        if not price_match:
            continue
        entry_price = float(price_match.group(1).replace(",", ""))

        # Parse stop/limit points from guidance line
        guide_match = re.search(r"Stop\s+(\d+)pt.*?Limit\s+(\d+)pt", block)
        if not guide_match:
            continue
        stop_pts  = int(guide_match.group(1))
        limit_pts = int(guide_match.group(2))

        if direction == "LONG":
            stop_price   = entry_price - stop_pts
            target_price = entry_price + limit_pts
        else:
            stop_price   = entry_price + stop_pts
            target_price = entry_price - limit_pts

        signals.append(Signal(
            timestamp=ts,
            instrument=instrument,
            symbol=symbol,
            direction=direction,
            signal_type=sig_type,
            entry_price=entry_price,
            stop_pts=stop_pts,
            limit_pts=limit_pts,
            stop_price=stop_price,
            target_price=target_price,
        ))

    return signals

# ── Price fetchers ─────────────────────────────────────────────────────────────

def fetch_binance_candles(symbol: str, start: datetime, hours: int = MAX_HOLD_HOURS) -> list:
    """Returns list of (open_time, high, low, close) dicts for 15m candles."""
    start_ms  = int(start.timestamp() * 1000)
    end_ms    = int((start + timedelta(hours=hours)).timestamp() * 1000)
    url = "https://api.binance.com/api/v3/klines"
    params = {"symbol": symbol, "interval": "15m", "startTime": start_ms,
              "endTime": end_ms, "limit": hours * 4 + 4}
    try:
        resp = requests.get(url, params=params, timeout=15)
        resp.raise_for_status()
        return [
            {
                "open_time": datetime.fromtimestamp(c[0] / 1000, tz=timezone.utc),
                "high": float(c[2]),
                "low":  float(c[3]),
                "close": float(c[4]),
            }
            for c in resp.json()
        ]
    except Exception as exc:
        print(f"  ⚠ Binance fetch failed for {symbol}: {exc}", file=sys.stderr)
        return []

def fetch_yahoo_candles(yahoo_ticker: str, start: datetime, hours: int = MAX_HOLD_HOURS) -> list:
    """Returns list of (open_time, high, low, close) for 15m candles via Yahoo v8."""
    period1 = int(start.timestamp())
    period2 = int((start + timedelta(hours=hours + 1)).timestamp())
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{yahoo_ticker}"
    params = {"interval": "15m", "period1": period1, "period2": period2}
    headers = {"User-Agent": "Mozilla/5.0"}
    try:
        resp = requests.get(url, params=params, headers=headers, timeout=15)
        resp.raise_for_status()
        data = resp.json()
        result = data["chart"]["result"][0]
        timestamps = result["timestamp"]
        ohlcv = result["indicators"]["quote"][0]
        candles = []
        for i, t in enumerate(timestamps):
            h = ohlcv["high"][i]
            l = ohlcv["low"][i]
            c = ohlcv["close"][i]
            if h is None or l is None:
                continue
            candles.append({
                "open_time": datetime.fromtimestamp(t, tz=timezone.utc),
                "high": h,
                "low":  l,
                "close": c,
            })
        return candles
    except Exception as exc:
        print(f"  ⚠ Yahoo fetch failed for {yahoo_ticker}: {exc}", file=sys.stderr)
        return []

def get_candles(signal: Signal) -> list:
    if signal.symbol in BINANCE_SYMBOLS:
        return fetch_binance_candles(signal.symbol, signal.timestamp)
    yahoo_ticker = YAHOO_TICKERS.get(signal.symbol)
    if yahoo_ticker:
        return fetch_yahoo_candles(yahoo_ticker, signal.timestamp)
    return []

# ── Outcome checker ────────────────────────────────────────────────────────────

def check_outcome(signal: Signal, candles: list) -> Signal:
    if not candles:
        signal.result = "NO_DATA"
        signal.note   = "Could not fetch price data"
        return signal

    deadline = signal.timestamp + timedelta(hours=MAX_HOLD_HOURS)
    is_long  = signal.direction == "LONG"

    for candle in candles:
        if candle["open_time"] < signal.timestamp:
            continue
        if candle["open_time"] > deadline:
            break

        high  = candle["high"]
        low   = candle["low"]
        ctime = candle["open_time"]

        if is_long:
            if low <= signal.stop_price:
                signal.result = "LOSS"
                signal.exit_timestamp = ctime
                signal.pnl_pct = round(-signal.stop_pts / signal.entry_price * 100, 2)
                return signal
            if high >= signal.target_price:
                signal.result = "WIN"
                signal.exit_timestamp = ctime
                signal.pnl_pct = round(signal.limit_pts / signal.entry_price * 100, 2)
                return signal
        else:
            if high >= signal.stop_price:
                signal.result = "LOSS"
                signal.exit_timestamp = ctime
                signal.pnl_pct = round(-signal.stop_pts / signal.entry_price * 100, 2)
                return signal
            if low <= signal.target_price:
                signal.result = "WIN"
                signal.exit_timestamp = ctime
                signal.pnl_pct = round(signal.limit_pts / signal.entry_price * 100, 2)
                return signal

    # Neither hit within window — use last close
    last = candles[-1]
    exit_px = last["close"]
    if is_long:
        raw_pnl = (exit_px - signal.entry_price) / signal.entry_price * 100
    else:
        raw_pnl = (signal.entry_price - exit_px) / signal.entry_price * 100

    signal.result = "EXPIRED"
    signal.exit_timestamp = last["open_time"]
    signal.pnl_pct = round(raw_pnl, 2)
    return signal

# ── Report writer ──────────────────────────────────────────────────────────────

RESULT_EMOJI = {"WIN": "✅", "LOSS": "❌", "EXPIRED": "⏱", "NO_DATA": "❓", "OPEN": "🔄"}

def write_csv(signals: list, path: str):
    fieldnames = [
        "timestamp", "instrument", "direction", "signal_type",
        "entry_price", "stop_price", "target_price", "stop_pts", "limit_pts",
        "result", "exit_timestamp", "pnl_pct", "note",
    ]
    with open(path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fieldnames)
        w.writeheader()
        for s in signals:
            w.writerow({
                "timestamp":      s.timestamp.strftime("%Y-%m-%d %H:%M"),
                "instrument":     s.instrument,
                "direction":      s.direction,
                "signal_type":    s.signal_type,
                "entry_price":    s.entry_price,
                "stop_price":     s.stop_price,
                "target_price":   s.target_price,
                "stop_pts":       s.stop_pts,
                "limit_pts":      s.limit_pts,
                "result":         s.result or "OPEN",
                "exit_timestamp": s.exit_timestamp.strftime("%Y-%m-%d %H:%M") if s.exit_timestamp else "",
                "pnl_pct":        s.pnl_pct if s.pnl_pct is not None else "",
                "note":           s.note,
            })
    print(f"✅  CSV written → {path}")

def write_markdown(signals: list, path: str):
    wins   = [s for s in signals if s.result == "WIN"]
    losses = [s for s in signals if s.result == "LOSS"]
    expire = [s for s in signals if s.result == "EXPIRED"]
    nodata = [s for s in signals if s.result in ("NO_DATA", None, "OPEN")]

    with open(path, "w") as fh:
        fh.write("# Signal Outcome Report\n\n")
        fh.write(f"Generated: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}  \n")
        fh.write(f"Source: `alerts.md` (Apr 15–18 2026)  \n")
        fh.write(f"Signals analysed: **{len(signals)}**  \n")
        fh.write(f"✅ Wins: **{len(wins)}** | ❌ Losses: **{len(losses)}** | ⏱ Expired: **{len(expire)}** | ❓ No data: **{len(nodata)}**\n\n")

        if wins or losses:
            total_r  = len(wins) * 2 - len(losses)   # approx R (2:1 R:R for most)
            fh.write(f"**Approx R-total (excl expired):** {total_r:+d}R  \n\n")

        fh.write("| # | Instrument | Dir | Type | Entry Time (UTC) | Entry | Stop | Target | Result | Exit Time | P&L% |\n")
        fh.write("|---|-----------|-----|------|-----------------|-------|------|--------|--------|-----------|------|\n")

        for i, s in enumerate(signals, 1):
            emoji  = RESULT_EMOJI.get(s.result or "OPEN", "❓")
            result = f"{emoji} {s.result or 'OPEN'}"
            exit_t = s.exit_timestamp.strftime("%m-%d %H:%M") if s.exit_timestamp else "—"
            pnl    = f"{s.pnl_pct:+.2f}%" if s.pnl_pct is not None else "—"
            fh.write(
                f"| {i} | {s.instrument} | {s.direction} | {s.signal_type} "
                f"| {s.timestamp.strftime('%m-%d %H:%M')} "
                f"| {s.entry_price:,.2f} | {s.stop_price:,.2f} | {s.target_price:,.2f} "
                f"| {result} | {exit_t} | {pnl} |\n"
            )

    print(f"✅  Markdown written → {path}")

# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    import os
    base = os.path.dirname(os.path.abspath(__file__))
    alerts_path = os.path.join(base, "alerts.md")
    os.makedirs(os.path.join(base, "reports"), exist_ok=True)
    csv_path = os.path.join(base, "reports", "signal-outcomes.csv")
    md_path  = os.path.join(base, "reports", "signal-outcomes.md")

    print("📋  Parsing alerts.md …")
    signals = parse_alerts(alerts_path)
    print(f"    Found {len(signals)} actionable signals")

    print("📡  Fetching price data and checking outcomes …")
    now = datetime.now(timezone.utc)
    for i, sig in enumerate(signals, 1):
        age_h = (now - sig.timestamp).total_seconds() / 3600
        print(f"  [{i}/{len(signals)}] {sig.instrument} {sig.direction} @ {sig.entry_price:,.2f}"
              f"  ({sig.timestamp.strftime('%m-%d %H:%M')})")
        if age_h < MAX_HOLD_HOURS:
            sig.result = "OPEN"
            sig.note   = f"Signal only {age_h:.1f}h ago — position may still be live"
            continue
        candles = get_candles(sig)
        check_outcome(sig, candles)
        time.sleep(0.25)  # gentle rate-limit

    write_csv(signals, csv_path)
    write_markdown(signals, md_path)

    print("\n── Summary ──────────────────────────")
    for s in signals:
        emoji = RESULT_EMOJI.get(s.result or "OPEN", "❓")
        print(f"  {emoji} {s.instrument:<16} {s.direction:<5} {s.signal_type:<22}"
              f" entry {s.entry_price:>10,.2f}  "
              f"exit {s.exit_timestamp.strftime('%m-%d %H:%M') if s.exit_timestamp else '—':>12}"
              f"  {(str(s.pnl_pct)+'%') if s.pnl_pct is not None else '':>8}")


if __name__ == "__main__":
    main()
