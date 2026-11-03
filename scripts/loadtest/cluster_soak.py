"""Cluster soak and scale runs on top of loadtest.py's cluster stack.

    python scripts/loadtest/cluster_soak.py soak  --nodes 3 --duration 24h [--churn-every 30] [--sample-interval 60]
    python scripts/loadtest/cluster_soak.py scale --nodes 10 --held-leases 500 [--delay-ms 30000]
    python scripts/loadtest/cluster_soak.py scale --external --balancer http://lb:7070 \
        --metrics http://n1:7070,http://n2:7070 --nats-monitor http://nats-1:8222 --agent <id> --held-leases 5000

soak   Steady load in chunks for --duration on a fresh (or --reuse'd) N-replica stack, optionally
       restarting one replica every --churn-every minutes, while a sampler records per node the
       JVM heap, live threads, leases held and open files, and per NATS server the subscriptions,
       connections and every EDDI stream's (KV buckets included) messages and bytes. The report
       fits a slope per hour to each series (to the per-window minima for the heap, which is a
       sawtooth) and flags the ones that keep growing: a leak shows up as a slope, not as a peak.
scale  One long-held-turn run: --held-leases concurrent conversations per node, each turn held
       for --delay-ms at the mock LLM, so that many leases are held at once. Against the local
       stack (up to 19 replicas on one host) or, with --external, against a cluster you deployed
       on separate hosts (k6 runs here with --network host).

Results: results/<label>-soak-<date>.json/.md and results/<label>-scale-<date>.json/.md.
See README.md, "Soak and scale (cluster)".
"""
import argparse
import datetime
import json
import os
import re
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import loadtest as lt  # noqa: E402  (the sibling driver; this file only adds two commands to it)

K6_IMAGE = "grafana/k6:0.54.0"


def parse_duration(text):
    m = re.fullmatch(r"(\d+)([smhd])", text.strip())
    if not m:
        raise argparse.ArgumentTypeError(f"duration like 90s, 30m, 24h or 3d expected, got {text!r}")
    return int(m.group(1)) * {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]


# ------------------------------------------------------------------ sampling

def node_sample(base):
    """The series a leak shows up in, from one node's /q/metrics."""
    m = lt.scrape(base)
    if not m:
        return None
    heap = sum(v for k, v in m.items() if k.startswith("jvm_memory_used_bytes") and 'area="heap"' in k)
    return {
        "heap_used_bytes": heap,
        "threads_live": m.get("jvm_threads_live_threads"),
        "leases_held": sum(v for k, v in m.items() if k.startswith("eddi_cluster_lease_held")),
        "open_files": m.get("process_files_open_files"),
        "in_flight": sum(v for k, v in m.items() if k.startswith("eddi_processing_conversation_count")),
    }


def http_json(url):
    s, _, body = lt.http("GET", url, timeout=15)
    if s != 200:
        return None
    try:
        return json.loads(body)
    except ValueError:
        return None


def nats_sample(monitor_urls):
    """Per NATS server: subscriptions, connections, and every stream's messages and bytes."""
    out = {}
    for name, url in monitor_urls.items():
        subs = http_json(url + "/subsz") or {}
        conns = http_json(url + "/connz") or {}
        jsz = http_json(url + "/jsz?streams=true") or {}
        streams = {}
        for account in jsz.get("account_details", []) or []:
            for stream in account.get("stream_detail", []) or []:
                state = stream.get("state", {})
                streams[stream.get("name")] = {"messages": state.get("messages", 0), "bytes": state.get("bytes", 0)}
        out[name] = {"subscriptions": subs.get("num_subscriptions"), "connections": conns.get("num_connections"), "streams": streams}
    return out


def local_nats_sample():
    """The cluster stack's NATS servers publish no port, so their monitor is read through docker exec."""
    out = {}
    for i in (1, 2, 3):
        name = f"{lt.CLUSTER_PROJECT}-nats-{i}-1"

        def get(path):
            r = lt.sh("docker", "exec", name, "wget", "-qO-", "http://localhost:8222" + path, timeout=30)
            try:
                return json.loads(r.stdout)
            except ValueError:
                return {}

        jsz = get("/jsz?streams=true")
        streams = {}
        for account in jsz.get("account_details", []) or []:
            for stream in account.get("stream_detail", []) or []:
                state = stream.get("state", {})
                streams[stream.get("name")] = {"messages": state.get("messages", 0), "bytes": state.get("bytes", 0)}
        out[f"nats-{i}"] = {"subscriptions": get("/subsz").get("num_subscriptions"), "connections": get("/connz").get("num_connections"),
                            "streams": streams}
    return out


class Sampler(threading.Thread):
    def __init__(self, nodes_fn, nats_fn, interval):
        super().__init__(daemon=True)
        self.nodes_fn, self.nats_fn, self.interval = nodes_fn, nats_fn, interval
        self.samples = []
        self.stop = threading.Event()

    def run(self):
        t0 = time.time()
        while not self.stop.is_set():
            sample = {"t": round(time.time() - t0, 1), "nodes": {}, "nats": {}}
            for name, base in self.nodes_fn():
                sample["nodes"][name] = node_sample(base)
            try:
                sample["nats"] = self.nats_fn()
            except SystemExit as e:  # a NATS server mid-restart: keep sampling the rest of the run
                sample["nats_error"] = str(e)
            self.samples.append(sample)
            self.stop.wait(self.interval)


# ------------------------------------------------------------------ growth

def slope_per_hour(points):
    """Least-squares slope of [(t_seconds, value)] in value per hour; None with fewer than 3 points."""
    pts = [(t, v) for t, v in points if v is not None]
    if len(pts) < 3:
        return None
    n = len(pts)
    mt = sum(t for t, _ in pts) / n
    mv = sum(v for _, v in pts) / n
    den = sum((t - mt) ** 2 for t, _ in pts)
    if den == 0:
        return None
    return 3600 * sum((t - mt) * (v - mv) for t, v in pts) / den


def window_minima(points, windows=10):
    """The minimum of each of `windows` equal slices: the floor a sawtooth (the heap) returns to."""
    pts = [(t, v) for t, v in points if v is not None]
    if len(pts) < windows:
        return pts
    size = len(pts) // windows
    return [min(pts[i * size:(i + 1) * size], key=lambda p: p[1]) for i in range(windows)]


def growth(samples, warmup_share=0.1, threshold=0.10):
    """{series: {first, last, slope_per_h, grows}}. Series that rise by more than `threshold` of their
    starting value over the run (after the warm-up share) are flagged."""
    if not samples:
        return {}
    start = samples[int(len(samples) * warmup_share)]["t"] if len(samples) > 10 else 0
    kept = [s for s in samples if s["t"] >= start]
    series = {}
    for s in kept:
        for node, values in s["nodes"].items():
            for k, v in (values or {}).items():
                series.setdefault(f"{node}.{k}", []).append((s["t"], v))
        for server, values in (s.get("nats") or {}).items():
            for k in ("subscriptions", "connections"):
                series.setdefault(f"{server}.{k}", []).append((s["t"], values.get(k)))
            for stream, st in (values.get("streams") or {}).items():
                series.setdefault(f"{server}.stream.{stream}.messages", []).append((s["t"], st.get("messages")))
                series.setdefault(f"{server}.stream.{stream}.bytes", []).append((s["t"], st.get("bytes")))
    out = {}
    hours = max((kept[-1]["t"] - kept[0]["t"]) / 3600, 1e-9) if kept else 0
    for name, pts in sorted(series.items()):
        vals = [v for _, v in pts if v is not None]
        if not vals:
            continue
        fit = window_minima(pts) if name.endswith("heap_used_bytes") else pts
        slope = slope_per_hour(fit)
        first = vals[0]
        rise = (slope or 0) * hours
        grows = slope is not None and rise > threshold * max(abs(first), 1) and rise > 1
        out[name] = {"first": first, "last": vals[-1], "max": max(vals), "slope_per_h": slope, "grows": grows}
    return out


def write_report(kind, label, meta, samples, chunks, out_dir=lt.RESULTS):
    os.makedirs(out_dir, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y-%m-%d-%H%M")
    g = growth(samples)
    result = {"kind": kind, "label": label, "meta": meta, "chunks": chunks, "growth": g, "samples": samples}
    base = os.path.join(out_dir, f"{label}-{kind}-{stamp}")
    with open(base + ".json", "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    lines = [f"# Cluster {kind}: {label}", "", f"- started {meta.get('started')}, ran {meta.get('ran_s', 0) / 3600:.2f} h",
             f"- {json.dumps({k: v for k, v in meta.items() if k not in ('started', 'ran_s')})}", "",
             "## Load", "", "| chunk | turns ok | 409 | 503 | other errors | turns/s |", "|---|---|---|---|---|---|"]
    for i, c in enumerate(chunks):
        lines.append(f"| {i + 1} | {c.get('turns_ok', 0):.0f} | {c.get('turns_409', 0):.0f} | {c.get('turns_503', 0):.0f} | "
                     f"{c.get('other_errors', 0):.0f} | {c.get('turns_per_s') or 0:.1f} |")
    flagged = {k: v for k, v in g.items() if v["grows"]}
    lines += ["", f"## Growth ({len(flagged)} series flagged of {len(g)})", "",
              "A flagged series rose by more than 10% of its starting value over the run (heap: the floor it returns to).", "",
              "| series | first | last | max | slope / h | grows |", "|---|---|---|---|---|---|"]
    for k, v in sorted(g.items(), key=lambda kv: (not kv[1]["grows"], kv[0])):
        lines.append(f"| {k} | {v['first']:.0f} | {v['last']:.0f} | {v['max']:.0f} | {(v['slope_per_h'] or 0):.1f} | "
                     f"{'**yes**' if v['grows'] else 'no'} |")
    with open(base + ".md", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{kind}: {len(flagged)} growing series of {len(g)} -> {base}.md", flush=True)
    return result, flagged


# ------------------------------------------------------------------ load

def chunk_result(summary):
    errors = {k: lt.metric(summary, k, "count") or 0 for k in lt.TURN_ERRORS}
    return {"turns_ok": lt.metric(summary, "turns_ok", "count") or 0, "turns_per_s": lt.metric(summary, "turns_ok", "rate"),
            "turns_409": errors.get("turns_409", 0), "turns_503": errors.get("turns_503", 0),
            "other_errors": sum(v for k, v in errors.items() if k not in ("turns_409", "turns_503"))}


def local_nodes():
    return [(n["name"], n["base"]) for n in lt.cluster_nodes()]


def bring_up(args):
    lt.use_cluster_stack()
    if args.reuse:
        return lt.load_state().get("agent")
    lt.cmd_cluster_down(args)
    lt.cmd_cluster_up(args)
    return lt.load_state()["agent"]


def cmd_soak(args):
    agent = bring_up(args)
    s, _, b = lt.http("POST", lt.WIREMOCK + "/__admin/settings", {"fixedDelay": args.delay_ms})
    if s == 0 or s >= 300:
        raise SystemExit(f"could not set the mock delay: {s} {b}")
    sampler = Sampler(local_nodes, local_nats_sample, args.sample_interval)
    sampler.start()
    started, t0 = datetime.datetime.now().isoformat(timespec="seconds"), time.time()
    deadline = t0 + args.duration
    next_churn = t0 + args.churn_every * 60 if args.churn_every else None
    churned, chunks, i = [], [], 0
    try:
        while time.time() < deadline:
            nodes = lt.cluster_nodes()
            if next_churn and time.time() >= next_churn:
                victim = nodes[len(churned) % len(nodes)]["name"]
                # graceful: the node drains, releases its leases and leaves presence (docs/clustering.md, rolling update)
                lt.sh("docker", "restart", "-t", "40", victim, timeout=300)
                churned.append({"t": round(time.time() - t0), "node": victim})
                next_churn += args.churn_every * 60
                nodes = lt.cluster_nodes()
            vus = args.vus_per_node * len(nodes)
            i += 1
            try:
                summary, _ = lt.k6_run(args, agent, vus, args.turns, f"{args.label}-soak-chunk", nodes)
                chunks.append({"t": round(time.time() - t0), **chunk_result(summary)})
            except SystemExit as e:  # one failed chunk (k6 thresholds, a restart mid-chunk) does not end a soak
                chunks.append({"t": round(time.time() - t0), "error": str(e)[:300]})
            print(f"soak chunk {i}: {chunks[-1]}", flush=True)
    finally:
        sampler.stop.set()
        sampler.join(args.sample_interval + 30)
        meta = {"started": started, "ran_s": round(time.time() - t0), "nodes": args.nodes, "duration_s": args.duration,
                "churn_every_min": args.churn_every, "churned": churned, "vus_per_node": args.vus_per_node,
                "turns_per_chunk": args.turns, "delay_ms": args.delay_ms, "sample_interval_s": args.sample_interval,
                "app": lt.load_state().get("app")}
        _, flagged = write_report("soak", args.label, meta, sampler.samples, chunks)
        if not args.keep and not args.reuse:
            lt.cmd_cluster_down(args)
    if args.fail_on_growth and flagged:
        raise SystemExit(f"{len(flagged)} series kept growing: {', '.join(sorted(flagged)[:10])}")


def external_k6(args, vus):
    os.makedirs(lt.RESULTS, exist_ok=True)
    out = f"{args.label}-scale.k6.json"
    cmd = ["docker", "run", "--rm", "--network", "host", "-v", f"{os.path.join(HERE, 'k6')}:/scripts:ro", "-v", f"{lt.RESULTS}:/results",
           "-e", f"BASE_URL={args.balancer}", "-e", f"AGENT_ID={args.agent}", "-e", "SCENARIO=steady", "-e", f"VUS={vus}",
           "-e", f"TURNS={args.turns}", "-e", f"OUT={out}", K6_IMAGE, "run", "--quiet", "/scripts/turns.js"]
    r = lt.sh(*cmd, capture=False, timeout=args.k6_timeout)
    if r.returncode != 0:
        raise SystemExit(f"k6 exited {r.returncode}")
    with open(os.path.join(lt.RESULTS, out), encoding="utf-8") as f:
        return json.load(f)


def cmd_scale(args):
    if args.external:
        if not (args.balancer and args.metrics and args.agent):
            raise SystemExit("--external needs --balancer, --metrics and --agent")
        urls = [u.strip().rstrip("/") for u in args.metrics.split(",") if u.strip()]
        nodes_fn = lambda: [(f"node-{i + 1}", u) for i, u in enumerate(urls)]  # noqa: E731
        monitors = {f"nats-{i + 1}": u.strip().rstrip("/") for i, u in enumerate((args.nats_monitor or "").split(",")) if u.strip()}
        nats_fn = lambda: nats_sample(monitors)  # noqa: E731
        n_nodes = len(urls)
    else:
        if args.nodes > 19:
            raise SystemExit("the local stack publishes 19 replica ports (7581-7599); use --external for more")
        args.agent = bring_up(args)
        s, _, b = lt.http("POST", lt.WIREMOCK + "/__admin/settings", {"fixedDelay": args.delay_ms})
        if s == 0 or s >= 300:
            raise SystemExit(f"could not set the mock delay: {s} {b}")
        nodes_fn, nats_fn, n_nodes = local_nodes, local_nats_sample, args.nodes
    vus = args.held_leases * n_nodes
    sampler = Sampler(nodes_fn, nats_fn, args.sample_interval)
    sampler.start()
    started, t0 = datetime.datetime.now().isoformat(timespec="seconds"), time.time()
    chunks = []
    try:
        if args.external:
            summary = external_k6(args, vus)
        else:
            summary, _ = lt.k6_run(args, args.agent, vus, args.turns, f"{args.label}-scale", lt.cluster_nodes())
        chunks.append(chunk_result(summary))
    finally:
        sampler.stop.set()
        sampler.join(args.sample_interval + 30)
        peak_leases = {}
        for s in sampler.samples:
            for name, v in s["nodes"].items():
                if v:
                    peak_leases[name] = max(peak_leases.get(name, 0), v.get("leases_held") or 0)
        meta = {"started": started, "ran_s": round(time.time() - t0), "nodes": n_nodes, "external": args.external,
                "held_leases_per_node": args.held_leases, "vus": vus, "turns_per_vu": args.turns, "delay_ms": args.delay_ms,
                "peak_leases_held": peak_leases}
        write_report("scale", args.label, meta, sampler.samples, chunks)
        if not args.external and not args.keep and not args.reuse:
            lt.cmd_cluster_down(args)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    so = sub.add_parser("soak", help="steady load with churn for a long time, sampling for growth")
    so.add_argument("--nodes", type=int, default=3)
    so.add_argument("--duration", type=parse_duration, default=parse_duration("1h"), help="e.g. 30m, 24h, 3d (default 1h)")
    so.add_argument("--churn-every", type=int, default=0, metavar="MIN", help="restart one replica every MIN minutes (default 0: off)")
    so.add_argument("--sample-interval", type=int, default=60, metavar="S", help="seconds between samples (default 60)")
    so.add_argument("--fail-on-growth", action="store_true", help="exit 1 when a series kept growing (for CI)")
    sc = sub.add_parser("scale", help="many concurrently held leases per node, local or on separate hosts")
    sc.add_argument("--nodes", type=int, default=3, help="local replicas (max 19); ignored with --external")
    sc.add_argument("--held-leases", type=int, default=500, help="concurrent conversations per node (default 500)")
    sc.add_argument("--sample-interval", type=int, default=10, metavar="S")
    sc.add_argument("--external", action="store_true", help="drive a cluster you deployed elsewhere")
    sc.add_argument("--balancer", help="--external: the cluster's entry URL, reachable from this host")
    sc.add_argument("--metrics", help="--external: comma-separated base URLs of every EDDI node (/q/metrics)")
    sc.add_argument("--nats-monitor", help="--external: comma-separated NATS monitor URLs (port 8222)")
    sc.add_argument("--agent", help="--external: a deployed agent whose LLM answers after --delay-ms")
    for p, turns, delay in ((so, 20, 1000), (sc, 3, 30000)):
        lt.add_cluster_args(p)
        p.add_argument("--vus-per-node", type=int, default=50, help="soak: conversations per node (default 50)")
        p.add_argument("--turns", type=int, default=turns, help="turns per conversation per chunk")
        p.add_argument("--delay-ms", type=int, default=delay, help="mock LLM latency")
        p.add_argument("--think-ms", type=int, default=0)
        p.add_argument("--label", default="cluster")
        p.add_argument("--k6-timeout", type=int, default=7200)
        p.add_argument("--reuse", action="store_true", help="use the stack `loadtest.py cluster-up` left running")
        p.add_argument("--keep", action="store_true", help="leave the stack running afterwards")
    args = ap.parse_args()
    {"soak": cmd_soak, "scale": cmd_scale}[args.cmd](args)


if __name__ == "__main__":
    main()
