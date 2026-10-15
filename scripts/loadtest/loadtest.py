"""Load-test driver for EDDI turns (planning/throughput-optimization-plan.md, WS0).

    python scripts/loadtest/loadtest.py up [--app DIR | --no-app] [--image IMG]
    python scripts/loadtest/loadtest.py run --scenario steady --vus 200 --turns 10 --delay-ms 3000 [--label baseline]
    python scripts/loadtest/loadtest.py matrix --vus 50,200,500 --delay-ms 3000 [--label baseline]
    python scripts/loadtest/loadtest.py down

`up` starts MongoDB, the WireMock LLM and EDDI, and creates one deployed LLM agent (its id is kept
in results/.state.json). `run` sets the mock's latency, snapshots /q/metrics and MongoDB counters,
runs k6, samples the saturation gauges every second while it runs, snapshots again and writes
results/<label>-<scenario>-<vus>vu-<delay>ms.json. `matrix` runs the steady scenario once per VU
count and writes the Markdown report results/<label>-<date>.md. Standard library only.
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RESULTS = os.path.join(HERE, "results")
STATE = os.path.join(RESULTS, ".state.json")
EDDI = "http://127.0.0.1:" + os.environ.get("LOADTEST_EDDI_PORT", "7370")
WIREMOCK = "http://127.0.0.1:" + os.environ.get("LOADTEST_WIREMOCK_PORT", "18370")

# Gauges sampled during a run; the report shows the peak of every series.
SAMPLED = re.compile(r"^(eddi_runtime_executor_(active|queued|max_threads)|eddi_processing_conversation_count|"
                     r"eddi_coordinator_(queue_depth|active_conversations)|eddi_audit_queue_(depth|capacity)|"
                     r"jvm_threads_(live|peak)_threads|mongodb_driver_pool_(size|checkedout|waitqueuesize))")
# Counters compared before/after a run.
COUNTED = re.compile(r"^(eddi_audit_entries_dropped_total|eddi_coordinator_dead_lettered_total|eddi_turn_idempotency_total|"
                     r"eddi_conversation_processing_duration_seconds_(count|sum)|eddi_llm_circuit[a-z_]*_total|"
                     r"http_server_requests_seconds_count)")


def sh(*cmd, check=False, capture=True, env=None):
    r = subprocess.run(cmd, capture_output=capture, text=True, env=env, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise SystemExit(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def compose(args, *cmd, check=True, capture=True):
    files = ["-f", os.path.join(HERE, "docker-compose.loadtest.yml")]
    env = dict(os.environ)
    state = load_state()
    app = getattr(args, "app", None) or state.get("app")
    if app and not getattr(args, "no_app", False):
        files += ["-f", os.path.join(HERE, "docker-compose.app.yml")]
        env["EDDI_APP"] = app
    image = getattr(args, "image", None) or state.get("image")
    if image:
        env["LOADTEST_EDDI_IMAGE"] = image
    return sh("docker", "compose", *files, *cmd, check=check, capture=capture, env=env)


def http(method, url, body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as e:
        return 0, {}, str(e)


def wait_until(fn, timeout, interval=1.0):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if fn():
                return True
        except Exception:
            pass
        time.sleep(interval)
    return False


def load_state():
    try:
        with open(STATE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(state):
    os.makedirs(RESULTS, exist_ok=True)
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)


# ------------------------------------------------------------------ stack

def cmd_up(args):
    if not args.no_app:
        args.app = os.path.abspath(args.app or os.path.join(REPO, "target", "quarkus-app"))
        if not os.path.isfile(os.path.join(args.app, "quarkus-run.jar")):
            raise SystemExit(f"{args.app} has no quarkus-run.jar — run ./mvnw package -DskipTests -DskipUi=true first, or pass --no-app")
        os.makedirs(os.path.join(args.app, "tmp"), exist_ok=True)  # mount point of the tmpfs
    save_state({"app": None if args.no_app else args.app, "image": args.image})
    compose(args, "up", "-d", "mongodb", "wiremock", "eddi")
    if not wait_until(lambda: http("GET", EDDI + "/q/health/ready", timeout=5)[0] == 200, 300, 2):
        print(compose(args, "logs", "--tail", "80", "eddi", check=False).stdout)
        raise SystemExit("EDDI did not become ready")
    agent = setup_agent()
    state = load_state()
    state["agent"] = agent
    save_state(state)
    print(f"stack up: EDDI {EDDI}, agent {agent}")


def setup_agent():
    body = {"agentName": f"loadtest-{int(time.time())}", "systemPrompt": "You are a load test agent.", "provider": "openai",
            "model": "mock-1", "apiKey": "sk-loadtest-0123456789", "baseUrl": "http://wiremock:8080/v1",
            "enableBuiltInTools": False, "deploy": True}
    s, h, b = http("POST", EDDI + "/administration/agents/setup", body, timeout=120)
    if s >= 300:
        raise SystemExit(f"agent setup failed: {s} {b[:500]}")
    try:
        agent = json.loads(b).get("agentId")
    except ValueError:
        agent = None
    agent = agent or (re.search(r"agents/([0-9a-f-]+)", b) or [None, None])[1]
    if not agent:
        raise SystemExit(f"no agent id in setup response: {b[:500]}")
    ok = wait_until(lambda: json.loads(http("GET", f"{EDDI}/administration/production/deploymentstatus/{agent}?version=1")[2])
                    .get("status") == "READY", 120)
    if not ok:
        raise SystemExit(f"agent {agent} did not deploy")
    return agent


def cmd_down(args):
    compose(args, "down", "-v", check=False)
    print("stack down")


# ------------------------------------------------------------------ measurements

def scrape():
    s, _, body = http("GET", EDDI + "/q/metrics", timeout=10)
    if s != 200:
        return {}
    out = {}
    for line in body.splitlines():
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)", line)
        if m:
            try:
                out[m.group(1) + (m.group(2) or "")] = float(m.group(3))
            except ValueError:
                pass
    return out


def mongo_eval(js):
    r = compose(None, "exec", "-T", "mongodb", "mongosh", "-u", "eddi", "-p", "changeme", "--authenticationDatabase", "admin",
                "--quiet", "--eval", js, check=False)
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {}


def mongo_snapshot():
    # Counters are Longs in mongosh; Number() keeps JSON.stringify from emitting {high, low}.
    ops = mongo_eval("const c=db.serverStatus().opcounters; const o={}; for (const k in c) o[k]=Number(c[k]); JSON.stringify(o)")
    # Per-collection operation counts: what tells conversation loads apart from config reads.
    top = mongo_eval("const t=db.adminCommand({top:1}).totals; const o={}; for (const k in t) { if (k.startsWith('eddi.')) "
                     "{ o[k.slice(5)] = {queries:Number(t[k].queries.count), getmore:Number(t[k].getmore.count), insert:Number(t[k].insert.count), "
                     "update:Number(t[k].update.count), remove:Number(t[k].remove.count), commands:Number(t[k].commands.count)} } } JSON.stringify(o)")
    return {"opcounters": ops, "collections": top}


class Sampler(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.peaks = {}
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            for k, v in scrape().items():
                if SAMPLED.match(k):
                    self.peaks[k] = max(v, self.peaks.get(k, float("-inf")))
            self.stop.wait(1.0)


def diff(before, after):
    return {k: after[k] - before.get(k, 0.0) for k in after if COUNTED.match(k) and after[k] - before.get(k, 0.0) != 0}


def mongo_diff(before, after):
    ops = {k: after["opcounters"].get(k, 0) - before["opcounters"].get(k, 0) for k in after.get("opcounters", {})}
    cols = {}
    for c, a in after.get("collections", {}).items():
        b = before.get("collections", {}).get(c, {})
        d = {k: a[k] - b.get(k, 0) for k in a if a[k] - b.get(k, 0)}
        if d:
            cols[c] = d
    return {"opcounters": ops, "collections": cols}


def metric(summary, name, stat):
    return (summary.get("metrics", {}).get(name, {}).get("values", {}) or {}).get(stat)


def cmd_run(args, label=None):
    state = load_state()
    agent = args.agent or state.get("agent")
    if not agent:
        raise SystemExit("no agent — run `up` first")
    label = label or args.label
    s, _, b = http("POST", WIREMOCK + "/__admin/settings", {"fixedDelay": args.delay_ms})
    if s >= 300:
        raise SystemExit(f"could not set the mock delay: {s} {b}")
    name = f"{label}-{args.scenario}-{args.vus}vu-{args.delay_ms}ms"
    os.makedirs(RESULTS, exist_ok=True)
    m_before, db_before = scrape(), mongo_snapshot()
    sampler = Sampler()
    sampler.start()
    t0 = time.time()
    k6 = ["run", "--rm", "-e", f"AGENT_ID={agent}", "-e", f"SCENARIO={args.scenario}", "-e", f"VUS={args.vus}",
          "-e", f"TURNS={args.turns}", "-e", f"THINK_MS={args.think_ms}", "-e", f"OUT={name}.k6.json",
          "-e", f"RAMP={args.ramp}", "-e", f"HOLD={args.hold}", "k6", "run", "--quiet", "/scripts/turns.js"]
    r = compose(args, "--profile", "k6", *k6, check=False, capture=False)
    elapsed = time.time() - t0
    sampler.stop.set()
    sampler.join(5)
    time.sleep(2)  # let the last turns' persistence settle before counting Mongo ops
    m_after, db_after = scrape(), mongo_snapshot()
    try:
        with open(os.path.join(RESULTS, f"{name}.k6.json"), encoding="utf-8") as f:
            summary = json.load(f)
    except (OSError, ValueError):
        summary = {}
    ok = metric(summary, "turns_ok", "count") or 0
    mongo = mongo_diff(db_before, db_after)
    result = {
        "name": name, "label": label, "scenario": args.scenario, "vus": args.vus, "turns_per_vu": args.turns,
        "llm_delay_ms": args.delay_ms, "think_ms": args.think_ms, "elapsed_s": round(elapsed, 1), "k6_exit": r.returncode,
        "turns_ok": ok, "turns_per_s": metric(summary, "turns_ok", "rate"),
        "latency_ms": {k: metric(summary, "turn_duration", k) for k in ["med", "p(90)", "p(95)", "p(99)", "max"]},
        "errors": {k: metric(summary, k, "count") or 0 for k in
                   ["turns_503", "turns_429", "turns_408", "turns_409", "turns_5xx_other", "turns_other", "turns_no_response",
                    "start_failed"]},
        "mongo": mongo,
        "mongo_ops_per_turn": {k: round(v / ok, 2) for k, v in mongo["opcounters"].items()} if ok else {},
        "peaks": sampler.peaks,
        "counters": diff(m_before, m_after),
    }
    with open(os.path.join(RESULTS, f"{name}.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    print(one_line(result))
    return result


def one_line(r):
    lat = r["latency_ms"]
    errs = ", ".join(f"{k[6:] if k.startswith('turns_') else k}={int(v)}" for k, v in r["errors"].items() if v)
    return (f"{r['name']}: {r['turns_ok']:.0f} ok, {r['turns_per_s'] or 0:.1f} turns/s, p50 {fmt(lat['med'])} p95 {fmt(lat['p(95)'])} "
            f"p99 {fmt(lat['p(99)'])} ms; errors: {errs or 'none'}")


def fmt(v):
    return "-" if v is None else f"{v:.0f}"


def peak(r, prefix, contains=""):
    vals = [v for k, v in r["peaks"].items() if k.startswith(prefix) and contains in k]
    return fmt(max(vals)) if vals else "-"


def report(results, path, args):
    lines = [f"# Load test — {args.label} — {datetime.date.today().isoformat()}", "",
             f"Scenario `{args.scenario}`, {args.turns} turns per conversation, mock LLM delay {args.delay_ms} ms, "
             f"think time {args.think_ms} ms. Image `{load_state().get('image') or 'labsai/eddi:6.5.0'}`"
             f"{' with the local build mounted' if load_state().get('app') else ''}; commit `{git_head()}`.", "",
             "| Concurrent conv. | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | 503 | 408 | 5xx other | no response | "
             "Peak managed active | Peak managed queued | Peak in-flight turns | Peak JVM threads | Mongo ops/turn (q/u/i/cmd) |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        e, o, lat = r["errors"], r["mongo_ops_per_turn"], r["latency_ms"]
        lines.append(f"| {r['vus']} | {r['turns_ok']:.0f} | {r['turns_per_s'] or 0:.1f} | {fmt(lat['med'])} | {fmt(lat['p(95)'])} | "
                     f"{fmt(lat['p(99)'])} | {e['turns_503']:.0f} | {e['turns_408']:.0f} | {e['turns_5xx_other']:.0f} | "
                     f"{e['turns_no_response']:.0f} | {peak(r, 'eddi_runtime_executor_active', 'managed')} | "
                     f"{peak(r, 'eddi_runtime_executor_queued', 'managed')} | {peak(r, 'eddi_processing_conversation_count')} | "
                     f"{peak(r, 'jvm_threads_live_threads')} | {o.get('query', '-')}/{o.get('update', '-')}/{o.get('insert', '-')}/"
                     f"{o.get('command', '-')} |")
    lines += ["", "Audit: " + "; ".join(
        f"{r['vus']} conv. peak queue {peak(r, 'eddi_audit_queue_depth')}, dropped "
        f"{fmt(sum(v for k, v in r['counters'].items() if k.startswith('eddi_audit_entries_dropped')))}" for r in results), "",
        "Per-collection MongoDB operations per OK turn (largest run):", ""]
    big = max(results, key=lambda r: r["vus"])
    if big["turns_ok"]:
        for c, d in sorted(big["mongo"]["collections"].items()):
            lines.append(f"- `{c}`: " + ", ".join(f"{k} {v / big['turns_ok']:.2f}" for k, v in d.items()))
    lines += ["", "Raw numbers: the `.json` files next to this report (not committed).", ""]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"wrote {path}")


def git_head():
    r = sh("git", "-C", REPO, "rev-parse", "--short", "HEAD")
    return r.stdout.strip() or "unknown"


def cmd_matrix(args):
    results = []
    for vus in [int(v) for v in args.vus.split(",")]:
        a = argparse.Namespace(**vars(args))
        a.vus = vus
        results.append(cmd_run(a))
    report(results, os.path.join(RESULTS, f"{args.label}-{args.scenario}-{datetime.date.today().isoformat()}.md"), args)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    up = sub.add_parser("up")
    up.add_argument("--app", help="local build to mount (default: target/quarkus-app of this checkout)")
    up.add_argument("--no-app", action="store_true", help="run the image as published, without a local build")
    up.add_argument("--image", default=None, help="runtime image (default labsai/eddi:6.5.0)")
    sub.add_parser("down")
    for name in ["run", "matrix"]:
        p = sub.add_parser(name)
        p.add_argument("--scenario", default="steady", choices=["steady", "burst", "same-conversation", "long-conversation"])
        p.add_argument("--vus", default="50" if name == "run" else "50,200,500")
        p.add_argument("--turns", type=int, default=10)
        p.add_argument("--delay-ms", type=int, default=3000)
        p.add_argument("--think-ms", type=int, default=0)
        p.add_argument("--ramp", default="30s")
        p.add_argument("--hold", default="60s")
        p.add_argument("--label", default="run")
        p.add_argument("--agent", help="agent id (default: the one `up` created)")
    args = ap.parse_args()
    if args.cmd == "run":
        args.vus = int(args.vus)
    {"up": cmd_up, "down": cmd_down, "run": cmd_run, "matrix": cmd_matrix}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
