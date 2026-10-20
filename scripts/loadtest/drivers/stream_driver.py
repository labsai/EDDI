"""SSE load driver for the stream scenario of loadtest.py. Standard library only.

    python stream_driver.py --target native|openai|mock --base http://eddi:7070 --vus 50 --turns 5 [--warmup-turns 1] --out NAME.json
                            [--agent ID] [--model ID] [--api-key KEY]

k6 has no SSE client, so time to first token is measured here: --vus coroutines, each with its own
conversation, send --turns streamed turns back to back on one asyncio loop, and every turn records

- first_byte_ms   request sent -> response headers
- ttft_ms         request sent -> first token (native: the first `token` event; OpenAI: the first
                  chunk with non-empty delta.content)
- total_ms        request sent -> end of stream (native `done`, OpenAI `[DONE]`)
- spread_ms       first token -> last token: near 0 means the tokens arrived in one burst, i.e.
                  something buffered the stream
- tokens          token events received

Targets: `native` is EDDI's POST /agents/{conversationId}/stream; `openai` is EDDI's
POST /v1/chat/completions with stream=true; `mock` sends the same streaming request straight to the
mock LLM, which is the calibration the overhead is computed against. One asyncio loop instead of
threads: timestamps are taken by the loop that reads the socket, so they do not wait for a thread
the GIL has not scheduled yet.
"""
import argparse
import asyncio
import json
import os
import re
import sys
import time
import urllib.parse

TIMEOUT_S = 180


def percentiles(values):
    if not values:
        return {}
    v = sorted(values)

    def pct(p):  # linear interpolation between closest ranks, as k6 and numpy do
        k = (len(v) - 1) * p
        f = int(k)
        c = min(f + 1, len(v) - 1)
        return v[f] + (v[c] - v[f]) * (k - f)

    return {"count": len(v), "avg": sum(v) / len(v), "min": v[0], "med": pct(0.5), "p(90)": pct(0.9), "p(95)": pct(0.95),
            "p(99)": pct(0.99), "max": v[-1]}


class Response:
    def __init__(self, status, headers, reader):
        self.status, self.headers, self.reader = status, headers, reader
        self.chunked = "chunked" in headers.get("transfer-encoding", "").lower()
        self.remaining = None if self.chunked else int(headers["content-length"]) if "content-length" in headers else None

    async def chunks(self):
        """Body bytes as they arrive (de-chunked)."""
        if self.chunked:
            while True:
                size_line = await self.reader.readline()
                if not size_line:
                    return
                size = int(size_line.split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    await self.reader.readline()
                    return
                data = await self.reader.readexactly(size)
                await self.reader.readline()  # CRLF after the chunk
                yield data
        elif self.remaining is not None:
            while self.remaining > 0:
                data = await self.reader.read(min(65536, self.remaining))
                if not data:
                    return
                self.remaining -= len(data)
                yield data
        else:
            while True:
                data = await self.reader.read(65536)
                if not data:
                    return
                yield data

    async def text(self):
        out = b""
        async for c in self.chunks():
            out += c
        return out.decode("utf-8", "replace")


async def request(base, method, path, body=None, headers=None):
    """One HTTP/1.1 request on its own connection (Connection: close). Returns (Response, writer, t_sent)."""
    u = urllib.parse.urlsplit(base)
    if u.scheme != "http":
        raise ValueError(f"only http:// targets, got {base!r}")
    reader, writer = await asyncio.open_connection(u.hostname, u.port or 80, limit=1 << 20)
    data = json.dumps(body).encode() if body is not None else b""
    h = {"Host": f"{u.hostname}:{u.port or 80}", "Connection": "close", "Content-Length": str(len(data))}
    if body is not None:
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    head = f"{method} {path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in h.items()) + "\r\n"
    writer.write(head.encode() + data)
    await writer.drain()
    t_sent = time.perf_counter()
    status_line = await reader.readline()
    parts = status_line.decode("latin-1").split(" ", 2)
    status = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
    resp_headers = {}
    while True:
        line = await reader.readline()
        if line in (b"\r\n", b"\n", b""):
            break
        k, _, v = line.decode("latin-1").partition(":")
        resp_headers[k.strip().lower()] = v.strip()
    return Response(status, resp_headers, reader), writer, t_sent


async def sse_events(resp):
    """(arrival time, event name, data) per SSE event, timestamped when its last byte was read."""
    buf = ""
    async for chunk in resp.chunks():
        t = time.perf_counter()
        buf += chunk.decode("utf-8", "replace").replace("\r\n", "\n")
        while "\n\n" in buf:
            raw, buf = buf.split("\n\n", 1)
            name, data = "message", []
            for line in raw.split("\n"):
                if line.startswith(":"):
                    continue  # comment
                field, _, value = line.partition(":")
                value = value[1:] if value.startswith(" ") else value
                if field == "event":
                    name = value
                elif field == "data":
                    data.append(value)
            if data or name != "message":
                yield t, name, "\n".join(data)


class Stats:
    def __init__(self):
        self.samples = {"first_byte_ms": [], "ttft_ms": [], "total_ms": [], "spread_ms": [], "tokens": []}
        self.errors = {}
        self.ok = 0
        # Earliest start and latest end of a measured attempt, failed or timed out ones included: the
        # window turns_per_s divides by. Conversation starts and warm-up turns are outside it.
        self.window = [None, None]

    def attempt(self, start, end):
        self.window[0] = start if self.window[0] is None else min(self.window[0], start)
        self.window[1] = end if self.window[1] is None else max(self.window[1], end)

    def error(self, kind):
        self.errors[kind] = self.errors.get(kind, 0) + 1


def openai_token(data):
    """The content of an OpenAI chunk, '' for role/finish chunks, None for [DONE]."""
    if data.strip() == "[DONE]":
        return None
    try:
        choice = (json.loads(data).get("choices") or [{}])[0]
    except ValueError:
        return ""
    return (choice.get("delta") or {}).get("content") or ""


async def stream_turn(args, stats, vu, turn, conversation):
    if args.target == "native":
        path = f"/agents/{conversation}/stream?returnCurrentStepOnly=true"
        body = {"input": f"turn {turn} from vu {vu}"}
        headers = {"Accept": "text/event-stream"}
    else:
        path = "/v1/chat/completions"
        body = {"model": args.model, "stream": True, "messages": [{"role": "user", "content": f"turn {turn} from vu {vu}"}]}
        headers = {"Accept": "text/event-stream"}
        if args.target == "openai":
            # One EDDI conversation per VU across its turns, as Open WebUI keeps one per chat.
            headers.update({"Authorization": f"Bearer {args.api_key}", "X-EDDI-Chat-Id": f"{args.run_id}-vu{vu}",
                            "X-OpenWebUI-User-Id": f"loadtest-vu{vu}"})
    resp, writer, t0 = await request(args.base, "POST", path, body, headers)
    try:
        first_byte = time.perf_counter()
        if resp.status != 200:
            await resp.text()
            stats.error(f"http_{resp.status}")
            return
        first = last = None
        tokens = 0
        done = False
        async for t, name, data in sse_events(resp):
            if args.target == "native":
                if name == "token":
                    tok = data
                elif name == "done":
                    done = True
                    break
                elif name == "error":
                    stats.error("error_event")
                    return
                else:
                    continue
            else:
                tok = openai_token(data)
                if tok is None:
                    done = True
                    break
                if '"error"' in data and not tok:
                    stats.error("error_event")
                    return
            if tok:
                tokens += 1
                first = first if first is not None else t
                last = t
        end = time.perf_counter()
        if not done:
            stats.error("stream_cut")
            return
        if first is None:
            stats.error("no_token")
            return
        s = stats.samples
        s["first_byte_ms"].append((first_byte - t0) * 1000)
        s["ttft_ms"].append((first - t0) * 1000)
        s["total_ms"].append((end - t0) * 1000)
        s["spread_ms"].append((last - first) * 1000)
        s["tokens"].append(tokens)
        stats.ok += 1
    finally:
        writer.close()


async def start_conversation(args, vu):
    resp, writer, _ = await request(args.base, "POST", f"/agents/{args.agent}/start?userId=loadtest-vu{vu}")
    try:
        await resp.text()
        m = re.search(r"conversations/([0-9a-f-]+)", resp.headers.get("location", ""))
        return m.group(1) if m else None
    finally:
        writer.close()


async def vu_loop(args, stats, vu):
    conversation = None
    if args.target == "native":
        try:
            conversation = await asyncio.wait_for(start_conversation(args, vu), TIMEOUT_S)
        except (OSError, asyncio.TimeoutError, asyncio.IncompleteReadError):
            conversation = None
        if not conversation:
            stats.error("start_failed")
            return
    warmup = Stats()  # warm-up turns run the same way and are thrown away
    for turn in range(args.warmup_turns + args.turns):
        target = warmup if turn < args.warmup_turns else stats
        started = time.perf_counter()
        try:
            await asyncio.wait_for(stream_turn(args, target, vu, turn, conversation), TIMEOUT_S)
        except asyncio.TimeoutError:
            target.error("timeout")
        except (OSError, asyncio.IncompleteReadError, ValueError) as e:
            target.error("no_response" if isinstance(e, OSError) else "protocol")
        finally:
            target.attempt(started, time.perf_counter())


async def main_async(args):
    stats = Stats()
    t0 = time.perf_counter()
    await asyncio.gather(*(vu_loop(args, stats, vu) for vu in range(1, args.vus + 1)))
    elapsed = time.perf_counter() - t0
    start, end = stats.window
    measured = end - start if start is not None else 0
    out = {"target": args.target, "vus": args.vus, "turns_per_vu": args.turns, "warmup_turns": args.warmup_turns, "elapsed_s": round(elapsed, 2),
           "turns_ok": stats.ok, "turns_per_s": stats.ok / measured if measured else 0,
           "measured_s": round(measured, 2), "errors": stats.errors,
           "metrics": {k: percentiles(v) for k, v in stats.samples.items()}}
    with open(os.path.join("/results", args.out), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    print(json.dumps({k: out[k] for k in ["target", "turns_ok", "errors"]}))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", required=True, choices=["native", "openai", "mock"])
    ap.add_argument("--base", required=True)
    ap.add_argument("--agent")
    ap.add_argument("--model", default="mock-1")
    ap.add_argument("--api-key", default="")
    ap.add_argument("--vus", type=int, default=50)
    ap.add_argument("--turns", type=int, default=5, help="measured turns per VU")
    ap.add_argument("--warmup-turns", type=int, default=0, help="turns per VU run first and not recorded")
    ap.add_argument("--run-id", default=f"run{int(time.time())}")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if args.target == "native" and not args.agent:
        sys.exit("--agent is required for --target native")
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
