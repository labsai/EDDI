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
from http.client import HTTPConnection  # the module name would clash with http() below
import re
# The driver's job is to run `docker compose` and `git`: fixed argument lists, no shell.
import subprocess  # nosec B404
import sys
import threading
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RESULTS = os.path.join(HERE, "results")
STATE = os.path.join(RESULTS, ".state.json")
EDDI = "http://127.0.0.1:" + os.environ.get("LOADTEST_EDDI_PORT", "7370")
WIREMOCK = "http://127.0.0.1:" + os.environ.get("LOADTEST_WIREMOCK_PORT", "18370")
# The single-node stack (docker-compose.loadtest.yml) is the default; the cluster-* commands switch
# every helper below to docker-compose.cluster.yml through use_cluster_stack().
STACK_FILE = "docker-compose.loadtest.yml"

# Gauges sampled during a run; the report shows the peak of every series.
SAMPLED = re.compile(r"^(eddi_runtime_executor_(active|queued|max_threads)|eddi_processing_conversation_count|"
                     r"eddi_coordinator_(queue_depth|active_conversations)|eddi_audit_queue_(depth|capacity)|"
                     r"jvm_threads_(live|peak)_threads|mongodb_driver_pool_(size|checkedout|waitqueuesize))")
# Counters compared before/after a run.
COUNTED = re.compile(r"^(eddi_audit_entries_dropped_total|eddi_turn_rejected_total|eddi_coordinator_dead_lettered_total|eddi_turn_idempotency_total|"
                     r"eddi_conversation_processing_duration_seconds_(count|sum)|eddi_llm_circuit[a-z_]*_total|"
                     r"http_server_requests_seconds_count)")


# How long one docker/git call may take. `docker compose up` pulls images on a first run, so it gets
# room; a hung daemon still ends the run with a message instead of blocking it forever.
COMMAND_TIMEOUT_S = 900


def sh(*cmd, check=False, capture=True, env=None, timeout=COMMAND_TIMEOUT_S, on_timeout=None):
    # An argument list, never a shell string: the callers pass `docker compose …` and `git …` with this
    # script's own file paths and the operator's CLI options, so nothing is interpreted by a shell.
    try:
        r = subprocess.run(cmd, capture_output=capture, text=True, env=env, encoding="utf-8", errors="replace",  # nosec B603
                           timeout=timeout)
    except subprocess.TimeoutExpired:
        # Killing the CLI does not stop what it started (a `docker compose run` container keeps
        # running), so the caller gets to clean up before the run ends.
        if on_timeout is not None:
            on_timeout()
        raise SystemExit(f"command did not finish within {timeout} s: {' '.join(cmd[:4])} …")
    if check and r.returncode != 0:
        raise SystemExit(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def compose(args, *cmd, check=True, capture=True, timeout=COMMAND_TIMEOUT_S, on_timeout=None):
    files = ["-f", os.path.join(HERE, STACK_FILE)]
    env = dict(os.environ)
    state = load_state()
    app = getattr(args, "app", None) or state.get("app")
    if app and not getattr(args, "no_app", False):
        files += ["-f", os.path.join(HERE, "docker-compose.app.yml")]
        env["EDDI_APP"] = app
    image = getattr(args, "image", None) or state.get("image")
    if image:
        env["LOADTEST_EDDI_IMAGE"] = image
    return sh("docker", "compose", *files, *cmd, check=check, capture=capture, env=env, timeout=timeout,
              on_timeout=on_timeout)


def http(method, url, body=None, timeout=30):
    """One request to the local stack. Plain http only: every target is EDDI or WireMock on 127.0.0.1."""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "http" or not parts.hostname:
        raise ValueError(f"only http:// URLs are used here, got {url!r}")
    data = json.dumps(body).encode() if body is not None else None
    path = parts.path + ("?" + parts.query if parts.query else "")
    conn = HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    try:
        conn.request(method, path or "/", body=data, headers={"Content-Type": "application/json"} if data else {})
        r = conn.getresponse()
        return r.status, dict(r.getheaders()), r.read().decode("utf-8", "replace")
    except OSError as e:  # refused, reset, timed out: the stack is not (yet) answering
        return 0, {}, str(e)
    finally:
        conn.close()


def wait_until(fn, timeout, interval=1.0):
    end = time.time() + timeout
    last_error = None
    while time.time() < end:
        try:
            if fn():
                return True
        except Exception as e:  # a probe that throws (no JSON yet, connection refused) is "not yet"
            last_error = e
        time.sleep(interval)
    if last_error is not None:
        print(f"gave up waiting; last probe error: {last_error}", file=sys.stderr)
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

def scrape(base=None):
    s, _, body = http("GET", (base or EDDI) + "/q/metrics", timeout=10)
    if s != 200:
        return {}
    out = {}
    unparsable = 0
    for line in body.splitlines():
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)", line)
        if m:
            try:
                out[m.group(1) + (m.group(2) or "")] = float(m.group(3))
            except ValueError:  # a value Prometheus text allows but float() does not; not a meter we read
                unparsable += 1
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
    if s == 0 or s >= 300:  # 0: WireMock not reachable
        raise SystemExit(f"could not set the mock delay: {s} {b}")
    name = f"{label}-{args.scenario}-{args.vus}vu-{args.delay_ms}ms"
    os.makedirs(RESULTS, exist_ok=True)
    summary_path = os.path.join(RESULTS, f"{name}.k6.json")
    if os.path.exists(summary_path):
        os.remove(summary_path)  # a summary left by an earlier run must never stand in for this one
    m_before, db_before = scrape(), mongo_snapshot()
    sampler = Sampler()
    sampler.start()
    t0 = time.time()
    k6 = ["run", "--rm", "-e", f"AGENT_ID={agent}", "-e", f"SCENARIO={args.scenario}", "-e", f"VUS={args.vus}",
          "-e", f"TURNS={args.turns}", "-e", f"THINK_MS={args.think_ms}", "-e", f"OUT={name}.k6.json",
          "-e", f"RAMP={args.ramp}", "-e", f"HOLD={args.hold}", "k6", "run", "--quiet", "/scripts/turns.js"]
    # On a timeout the one-off k6 container would keep loading EDDI after the driver gave up, so it is
    # stopped and removed by service name before the run ends.
    r = compose(args, "--profile", "k6", *k6, check=False, capture=False, timeout=args.k6_timeout,
                on_timeout=lambda: compose(args, "--profile", "k6", "rm", "--stop", "--force", "k6", check=False, timeout=120))
    elapsed = time.time() - t0
    sampler.stop.set()
    sampler.join(5)
    time.sleep(2)  # let the last turns' persistence settle before counting Mongo ops
    m_after, db_after = scrape(), mongo_snapshot()
    if r.returncode != 0:
        raise SystemExit(f"k6 exited {r.returncode}; no result written for {name}")
    try:
        with open(summary_path, encoding="utf-8") as f:
            summary = json.load(f)
    except (OSError, ValueError) as e:
        raise SystemExit(f"k6 left no readable summary for {name} ({e}); no result written")
    ok = metric(summary, "turns_ok", "count") or 0
    mongo = mongo_diff(db_before, db_after)
    result = {
        "name": name, "label": label, "scenario": args.scenario, "vus": args.vus, "turns_per_vu": args.turns,
        "llm_delay_ms": args.delay_ms, "think_ms": args.think_ms, "elapsed_s": round(elapsed, 1), "k6_exit": r.returncode,
        "turns_ok": ok, "turns_per_s": metric(summary, "turns_ok", "rate"),
        "latency_ms": {k: metric(summary, "turn_duration", k) for k in ["med", "p(90)", "p(95)", "p(99)", "max"]},
        "errors": {k: metric(summary, k, "count") or 0 for k in
                   ["turns_503", "turns_429", "turns_408", "turns_409", "turns_5xx_other", "turns_other", "turns_no_response",
                    "turns_app_error", "start_failed"]},
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
             + "200 with TurnError | Peak managed active | Peak managed queued | Peak in-flight turns | Peak JVM threads | Mongo ops/turn (q/u/i/cmd) |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        e, o, lat = r["errors"], r["mongo_ops_per_turn"], r["latency_ms"]
        lines.append(f"| {r['vus']} | {r['turns_ok']:.0f} | {r['turns_per_s'] or 0:.1f} | {fmt(lat['med'])} | {fmt(lat['p(95)'])} | "
                     f"{fmt(lat['p(99)'])} | {e['turns_503']:.0f} | {e['turns_408']:.0f} | {e['turns_5xx_other']:.0f} | "
                     f"{e['turns_no_response']:.0f} | {e.get('turns_app_error', 0):.0f} | "
                     f"{peak(r, 'eddi_runtime_executor_active', 'managed')} | "
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


# ------------------------------------------------------------------ cluster (N replicas behind nginx)
#
# docker-compose.cluster.yml: N EDDI replicas (eddi.messaging.type=nats), a three-node NATS JetStream
# cluster, one MongoDB, the WireMock LLM and an nginx round-robin balancer. `cluster-matrix` brings a
# fresh stack up for every point so each starts from an empty database, warms it up, measures one
# steady run and writes results/cluster-scaling-<date>.md.

CLUSTER_STATE = os.path.join(RESULTS, ".cluster-state.json")
CLUSTER_PROJECT = "eddi-loadtest-cluster"
# Counters compared before/after a run on every node, on top of COUNTED.
CLUSTER_COUNTED = re.compile(r"^(eddi_cluster_lease_acquire_seconds_(count|sum)|eddi_cluster_fence_rejected_total|"
                             r"eddi_cluster_lease_(takeover|release_conflicts)_total|eddi_cluster_degraded_decisions_total|"
                             r"eddi_conversation_processing_count_total)")
CLUSTER_SAMPLED = re.compile(SAMPLED.pattern + r"|^(eddi_cluster_lease_held|eddi_cluster_lease_acquire_seconds_max|"
                             r"eddi_cluster_degraded|eddi_conversation_max_in_flight_turns)")


def use_cluster_stack():
    global STACK_FILE, STATE, EDDI, WIREMOCK
    STACK_FILE = "docker-compose.cluster.yml"
    STATE = CLUSTER_STATE
    EDDI = "http://127.0.0.1:" + os.environ.get("LOADTEST_CLUSTER_PORT", "7570")
    WIREMOCK = "http://127.0.0.1:" + os.environ.get("LOADTEST_CLUSTER_WIREMOCK_PORT", "18570")


def cluster_env(args):
    """What makes two points comparable, passed to compose explicitly rather than left to defaults."""
    return {
        "LOADTEST_MESSAGING": args.messaging,
        "LOADTEST_MAX_IN_FLIGHT": str(args.max_in_flight),
        "LOADTEST_MONGO_POOL": str(args.mongo_pool),
        "LOADTEST_JAVA_OPTS": args.java_opts,
        "LOADTEST_EDDI_MEMORY": args.eddi_memory,
        "LOADTEST_EDDI_CPUS": str(args.eddi_cpus),
        "LOADTEST_MONGO_CPUS": str(args.mongo_cpus),
        "LOADTEST_NATS_CPUS": str(args.nats_cpus),
    }


def cluster_nodes():
    """[{name, ip, base}] of the running EDDI replicas, in replica order."""
    r = sh("docker", "ps", "--filter", f"label=com.docker.compose.project={CLUSTER_PROJECT}",
           "--filter", "label=com.docker.compose.service=eddi", "--format", "{{.Names}}", check=True)
    nodes = []
    for name in sorted(r.stdout.split(), key=lambda n: int(n.rsplit("-", 1)[1])):
        ip = sh("docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name, check=True).stdout.strip()
        port = sh("docker", "port", name, "7070/tcp", check=True).stdout.split()[0].rsplit(":", 1)[1]
        nodes.append({"name": name, "ip": ip, "base": f"http://127.0.0.1:{port}"})
    return nodes


def cmd_cluster_up(args):
    use_cluster_stack()
    if not args.no_app:
        args.app = os.path.abspath(args.app or os.path.join(REPO, "target", "quarkus-app"))
        if not os.path.isfile(os.path.join(args.app, "quarkus-run.jar")):
            raise SystemExit(f"{args.app} has no quarkus-run.jar — run ./mvnw package -DskipTests -DskipUi=true first, or pass --no-app")
        os.makedirs(os.path.join(args.app, "tmp"), exist_ok=True)
    save_state({"app": None if args.no_app else args.app, "image": args.image, "nodes": args.nodes, "messaging": args.messaging,
                "env": cluster_env(args)})
    os.environ.update(cluster_env(args))
    compose(args, "up", "-d", "--scale", f"eddi={args.nodes}", "mongodb", "wiremock", "nats-1", "nats-2", "nats-3", "eddi", "lb")
    nodes = cluster_nodes()
    if len(nodes) != args.nodes:
        raise SystemExit(f"expected {args.nodes} EDDI replicas, found {len(nodes)}")
    for n in nodes:
        if not wait_until(lambda: http("GET", n["base"] + "/q/health/ready", timeout=5)[0] == 200, 300, 2):
            print(sh("docker", "logs", "--tail", "80", n["name"]).stdout)
            raise SystemExit(f"{n['name']} did not become ready")
    if not wait_until(lambda: http("GET", EDDI + "/q/health/ready", timeout=5)[0] == 200, 60, 1):
        raise SystemExit("the load balancer does not reach EDDI")
    # Set up on the first node directly: right after start the balancer can still hold a replica
    # address it resolved before that replica was up and answer 502 to the (non-retried) POST.
    global EDDI
    lb, EDDI = EDDI, nodes[0]["base"]
    try:
        agent = setup_agent()
    finally:
        EDDI = lb
    # Deployed through the balancer on one node; every node must serve it before a turn arrives.
    for n in nodes:
        ok = wait_until(lambda: json.loads(http("GET", f"{n['base']}/administration/production/deploymentstatus/{agent}?version=1")[2])
                        .get("status") == "READY", 120)
        if not ok:
            raise SystemExit(f"agent {agent} is not deployed on {n['name']}")
    state = load_state()
    state.update({"agent": agent, "node_names": [n["name"] for n in nodes]})
    save_state(state)
    print(f"cluster up: {args.nodes} x EDDI ({args.messaging}) behind {EDDI}, agent {agent}", flush=True)


def cmd_cluster_down(args):
    use_cluster_stack()
    compose(args, "down", "-v", "--remove-orphans", check=False)
    print("cluster down", flush=True)


class DockerStats(threading.Thread):
    """CPU of every container on the Docker host, sampled continuously while a run lasts.

    All containers, not only this stack's: on one machine the host is shared, and another load test
    running at the same time is part of the answer to "where did the host saturate"."""

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = []  # [{container: cpu%}]
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            r = sh("docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}", timeout=60)
            sample = {}
            for line in (r.stdout or "").splitlines():
                name, _, value = line.partition("\t")
                try:
                    sample[name] = float(value.strip().rstrip("%"))
                except ValueError:
                    pass
            if sample:
                self.samples.append(sample)

    def summary(self):
        if not self.samples:
            return {"samples": 0, "containers": {}, "total_mean": 0.0, "total_peak": 0.0, "foreign_mean": 0.0}
        n = len(self.samples)
        names = {k for s in self.samples for k in s}
        per = {k: {"mean": sum(s.get(k, 0.0) for s in self.samples) / n, "peak": max(s.get(k, 0.0) for s in self.samples)}
               for k in names}
        totals = [sum(s.values()) for s in self.samples]
        foreign = [sum(v for k, v in s.items() if not k.startswith(CLUSTER_PROJECT)) for s in self.samples]
        return {"samples": n, "containers": per, "total_mean": sum(totals) / n, "total_peak": max(totals),
                "foreign_mean": sum(foreign) / n}


class HostCpu(threading.Thread):
    """CPU of the machine itself, which docker stats cannot see: on Docker Desktop the containers share a
    VM with whatever else runs on the host (IDEs, builds, other sessions' test suites). Windows: the
    `% Processor Time` counter through typeperf; elsewhere the 1-minute load average per CPU."""

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = []
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            v = None
            if sys.platform == "win32":
                r = sh("typeperf", r"\Processor(_Total)\% Processor Time", "-si", "1", "-sc", "1", timeout=30)
                m = re.search(r'^"[^"]+","([0-9.,]+)"', r.stdout or "", re.M)
                v = float(m.group(1).replace(",", ".")) if m else None
            elif hasattr(os, "getloadavg"):
                v = 100.0 * os.getloadavg()[0] / (os.cpu_count() or 1)
                self.stop.wait(1.0)
            if v is not None:
                self.samples.append(v)

    def summary(self):
        if not self.samples:
            return {"samples": 0}
        return {"samples": len(self.samples), "mean": sum(self.samples) / len(self.samples), "peak": max(self.samples),
                "share_at_95_plus": sum(1 for v in self.samples if v >= 95) / len(self.samples)}


def nats_varz():
    out = {}
    for i in (1, 2, 3):
        r = sh("docker", "exec", f"{CLUSTER_PROJECT}-nats-{i}-1", "wget", "-qO-", "http://localhost:8222/varz", timeout=30)
        try:
            v = json.loads(r.stdout)
            out[f"nats-{i}"] = {k: v.get(k, 0) for k in ("in_msgs", "out_msgs", "in_bytes", "out_bytes")}
        except ValueError:
            pass
    return out


class ClusterSampler(threading.Thread):
    def __init__(self, nodes):
        super().__init__(daemon=True)
        self.nodes = nodes
        self.peaks = {n["name"]: {} for n in nodes}
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            for n in self.nodes:
                p = self.peaks[n["name"]]
                for k, v in scrape(n["base"]).items():
                    if CLUSTER_SAMPLED.match(k):
                        p[k] = max(v, p.get(k, float("-inf")))
            self.stop.wait(1.0)


def node_diff(before, after):
    return {k: after[k] - before.get(k, 0.0) for k in after
            if (COUNTED.match(k) or CLUSTER_COUNTED.match(k)) and after[k] - before.get(k, 0.0) != 0}


def lease_by_outcome(counters):
    """{outcome: {count, mean_ms}} from the eddi_cluster_lease_acquire_seconds_{count,sum} deltas."""
    agg = {}
    for k, v in counters.items():
        m = re.match(r"eddi_cluster_lease_acquire_seconds_(count|sum)\{(.*)\}", k)
        if not m:
            continue
        outcome = (re.search(r'outcome="([^"]*)"', m.group(2)) or [None, "?"])[1]
        contended = (re.search(r'contended="([^"]*)"', m.group(2)) or [None, "?"])[1]
        a = agg.setdefault(outcome + (" (contended)" if contended == "true" else ""), {"count": 0.0, "sum": 0.0})
        a["count" if m.group(1) == "count" else "sum"] += v
    return {k: {"count": a["count"], "mean_ms": 1000 * a["sum"] / a["count"] if a["count"] else None} for k, a in agg.items()}


def k6_run(args, agent, vus, turns, name, nodes):
    summary_path = os.path.join(RESULTS, f"{name}.k6.json")
    if os.path.exists(summary_path):
        os.remove(summary_path)
    addrs = ",".join(f"{n['ip']}:7070" for n in nodes)
    k6 = ["run", "--rm", "-e", f"AGENT_ID={agent}", "-e", "SCENARIO=steady", "-e", f"VUS={vus}", "-e", f"TURNS={turns}",
          "-e", f"THINK_MS={args.think_ms}", "-e", f"OUT={name}.k6.json", "-e", f"NODE_ADDRS={addrs}",
          "k6", "run", "--quiet", "/scripts/turns.js"]
    t0 = time.time()
    r = compose(args, "--profile", "k6", *k6, check=False, capture=False, timeout=args.k6_timeout,
                on_timeout=lambda: compose(args, "--profile", "k6", "rm", "--stop", "--force", "k6", check=False, timeout=120))
    elapsed = time.time() - t0
    if r.returncode != 0:
        raise SystemExit(f"k6 exited {r.returncode}; no result written for {name}")
    with open(summary_path, encoding="utf-8") as f:
        return json.load(f), elapsed


def cmd_cluster_run(args):
    use_cluster_stack()
    state = load_state()
    agent = state.get("agent")
    if not agent:
        raise SystemExit("no agent — run `cluster-up` first")
    nodes = cluster_nodes()
    vus = args.vus_per_node * len(nodes)
    s, _, b = http("POST", WIREMOCK + "/__admin/settings", {"fixedDelay": args.delay_ms})
    if s == 0 or s >= 300:
        raise SystemExit(f"could not set the mock delay: {s} {b}")
    label = f"{args.label}-{state.get('messaging')}-{len(nodes)}n"
    os.makedirs(RESULTS, exist_ok=True)
    if args.warmup_turns:
        # JIT, connection pools and caches on every node; in the WS2 report a first run's p95 doubled.
        print(f"{label}: warm-up {vus} conversations x {args.warmup_turns} turns", flush=True)
        k6_run(args, agent, vus, args.warmup_turns, f"{label}-warmup", nodes)
        time.sleep(3)
    name = f"{label}-{vus}vu-{args.delay_ms}ms"
    before = {n["name"]: scrape(n["base"]) for n in nodes}
    db_before, nats_before = mongo_snapshot(), nats_varz()
    sampler, stats, host = ClusterSampler(nodes), DockerStats(), HostCpu()
    for t in (sampler, stats, host):
        t.start()
    summary, elapsed = k6_run(args, agent, vus, args.turns, name, nodes)
    for t in (sampler, stats, host):
        t.stop.set()
    sampler.join(5)
    stats.join(15)
    host.join(15)
    time.sleep(2)  # let the last turns' persistence settle before counting Mongo ops
    after = {n["name"]: scrape(n["base"]) for n in nodes}
    db_after, nats_after = mongo_snapshot(), nats_varz()
    ok = metric(summary, "turns_ok", "count") or 0
    duration_s = (summary.get("state", {}).get("testRunDurationMs") or elapsed * 1000) / 1000
    mongo = mongo_diff(db_before, db_after)
    per_node = []
    for n in nodes:
        tag = f"{{node:{n['ip']}:7070}}"
        counters = node_diff(before[n["name"]], after[n["name"]])
        turns_ok = metric(summary, "turns_ok" + tag, "count") or 0
        per_node.append({
            "name": n["name"], "ip": n["ip"], "turns_ok": turns_ok, "turns_per_s": turns_ok / duration_s if duration_s else None,
            "latency_ms": {k: metric(summary, "turn_duration" + tag, k) for k in ["med", "p(95)", "p(99)", "max"]},
            "errors": {k: metric(summary, k + tag, "count") or 0 for k in ["turns_503", "turns_409", "turns_5xx_other", "turns_app_error"]},
            "lease_acquire": lease_by_outcome(counters),
            "fence_rejected": sum(v for k, v in counters.items() if k.startswith("eddi_cluster_fence_rejected")),
            "counters": counters, "peaks": sampler.peaks[n["name"]],
        })
    nats = {k: {f: nats_after[k][f] - nats_before.get(k, {}).get(f, 0) for f in ("in_msgs", "out_msgs", "in_bytes", "out_bytes")}
            for k in nats_after}
    total_ops = sum(mongo["opcounters"].values())
    result = {
        "name": name, "label": args.label, "nodes": len(nodes), "messaging": state.get("messaging"), "env": state.get("env"),
        "image": state.get("image"), "app": state.get("app"),
        "vus": vus, "vus_per_node": args.vus_per_node, "turns_per_vu": args.turns, "warmup_turns": args.warmup_turns,
        "llm_delay_ms": args.delay_ms, "elapsed_s": round(elapsed, 1), "duration_s": round(duration_s, 1),
        "turns_ok": ok, "turns_per_s": metric(summary, "turns_ok", "rate"),
        "latency_ms": {k: metric(summary, "turn_duration", k) for k in ["med", "p(90)", "p(95)", "p(99)", "max"]},
        "errors": {k: metric(summary, k, "count") or 0 for k in
                   ["turns_503", "turns_429", "turns_408", "turns_409", "turns_5xx_other", "turns_other", "turns_no_response",
                    "turns_app_error", "start_failed"]},
        "mongo": mongo, "mongo_ops_total": total_ops, "mongo_ops_per_s": total_ops / duration_s if duration_s else None,
        "mongo_ops_per_turn": {k: round(v / ok, 2) for k, v in mongo["opcounters"].items()} if ok else {},
        "nats": nats, "nats_msgs_per_turn": (sum(v["in_msgs"] for v in nats.values()) / ok) if ok and nats else None,
        "docker": stats.summary(), "host_cpu": host.summary(), "per_node": per_node,
    }
    with open(os.path.join(RESULTS, f"{name}.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    print(one_line(result) + f"; mongo {result['mongo_ops_per_s'] or 0:.0f} ops/s; containers {result['docker']['total_mean']:.0f}% CPU"
          f" (other stacks {result['docker']['foreign_mean']:.0f}%); host CPU {result['host_cpu'].get('mean', 0):.0f}%", flush=True)
    return result


def cmd_cluster_matrix(args):
    results = []
    points = ([("in-memory", 1)] if args.baseline else []) + [("nats", int(n)) for n in args.nodes.split(",")]
    for messaging, n in points:
        a = argparse.Namespace(**vars(args))
        a.nodes, a.messaging = n, messaging
        for attempt in (1, 2):
            cmd_cluster_down(a)
            try:
                cmd_cluster_up(a)
                break
            except SystemExit as e:  # a stack that did not come up is retried once, from scratch
                if attempt == 2:
                    raise
                print(f"cluster-up failed ({e}); retrying once", flush=True)
        results.append(cmd_cluster_run(a))
        cluster_report(results, a)  # rewritten after every point, so an aborted sweep keeps what it measured
    if not args.keep:
        cmd_cluster_down(args)


def cluster_report_from_files(args):
    """Rebuild the report from the .json results of earlier cluster-run calls (`cluster-report a.json b.json`)."""
    results = []
    for p in args.files:
        with open(p, encoding="utf-8") as f:
            results.append(json.load(f))
    cluster_report(results, args)


def cpu(r, service):
    """(sum of mean CPU %, sum of peak CPU %, containers) of one service of this stack."""
    rows = [v for k, v in r["docker"]["containers"].items() if k.startswith(f"{CLUSTER_PROJECT}-{service}")]
    return sum(v["mean"] for v in rows), sum(v["peak"] for v in rows), len(rows)


def host_txt(r):
    h = r.get("host_cpu") or {}
    return f"{h['mean']:.0f} · {100 * h['share_at_95_plus']:.0f} %" if h.get("samples") else "-"


def cluster_report(results, args):
    today = datetime.date.today().isoformat()
    path = getattr(args, "out", None) or os.path.join(RESULTS, f"cluster-scaling-{today}.md")
    first = results[0]
    env = first.get("env") or {}
    ncpu = sh("docker", "info", "--format", "{{.NCPU}}").stdout.strip() or "?"
    base1 = next((r for r in results if r["messaging"] == "nats" and r["nodes"] == 1), None)
    t1 = (base1 or {}).get("turns_per_s") or None
    lines = [f"# Cluster scaling — {today}", "",
             f"Scenario `steady`, {first['vus_per_node']} conversations per node, {first['turns_per_vu']} turns each (after a "
             f"{first.get('warmup_turns', 0)}-turn warm-up), mock LLM delay {first['llm_delay_ms']} ms. Per node: "
             f"`eddi.conversation.max-in-flight-turns={env.get('LOADTEST_MAX_IN_FLIGHT')}`, MongoDB `maxPoolSize={env.get('LOADTEST_MONGO_POOL')}`, "
             f"`{env.get('LOADTEST_JAVA_OPTS')}`, {env.get('LOADTEST_EDDI_CPUS')} CPUs, {env.get('LOADTEST_EDDI_MEMORY')}. "
             f"MongoDB {env.get('LOADTEST_MONGO_CPUS')} CPUs, NATS 3 x {env.get('LOADTEST_NATS_CPUS')} CPU (R3). "
             f"Docker host: {ncpu} CPUs. Image `{first.get('image') or 'labsai/eddi:6.5.0'}`"
             f"{' with the local build mounted' if first.get('app') else ''}; commit `{git_head()}`.", "",
             "| Nodes | Messaging | Conv. | OK turns | Turns/s | Efficiency | p50 ms | p95 ms | p99 ms | 409 | 503 | other err. | "
             "Lease acquire: count · mean ms | Fence rejected | Mongo ops/turn | Mongo ops/s | Mongo CPU % mean/peak | "
             "NATS CPU % mean/peak (3 nodes) | NATS msgs/turn | EDDI CPU % mean per node | All containers CPU % mean (other stacks) | "
             "Host CPU % mean · time at >= 95 % |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        e, lat = r["errors"], r["latency_ms"]
        other = sum(v for k, v in e.items() if k not in ("turns_409", "turns_503"))
        eff = f"{r['turns_per_s'] / (r['nodes'] * t1):.2f}" if t1 and r["messaging"] == "nats" else "-"
        leases = {}
        for p in r["per_node"]:
            for k, v in p["lease_acquire"].items():
                a = leases.setdefault(k, [0.0, 0.0])
                a[0] += v["count"]
                a[1] += (v["mean_ms"] or 0) * v["count"]
        lease_txt = "; ".join(f"{k} {c:.0f} · {s / c:.1f}" for k, (c, s) in sorted(leases.items()) if c) or "-"
        mc, mp, _ = cpu(r, "mongodb")
        nc, np_, _ = cpu(r, "nats")
        ec, _, en = cpu(r, "eddi")
        nm = r.get("nats_msgs_per_turn")
        lines.append(f"| {r['nodes']} | {r['messaging']} | {r['vus']} | {r['turns_ok']:.0f} | {r['turns_per_s'] or 0:.1f} | {eff} | "
                     f"{fmt(lat['med'])} | {fmt(lat['p(95)'])} | {fmt(lat['p(99)'])} | {e['turns_409']:.0f} | {e['turns_503']:.0f} | "
                     f"{other:.0f} | {lease_txt} | {sum(p['fence_rejected'] for p in r['per_node']):.0f} | "
                     f"{sum(r['mongo_ops_per_turn'].values()):.1f} | {r['mongo_ops_per_s'] or 0:.0f} | {mc:.0f}/{mp:.0f} | "
                     f"{nc:.0f}/{np_:.0f} | {'-' if nm is None or r['messaging'] != 'nats' else f'{nm:.1f}'} | "
                     f"{ec / en if en else 0:.0f} | {r['docker']['total_mean']:.0f} ({r['docker']['foreign_mean']:.0f}) | {host_txt(r)} |")
    lines += ["", "## Per node", ""]
    for r in results:
        lines += [f"**{r['nodes']} x {r['messaging']}**", "",
                  "| Node | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | 409 | 503 | Peak in-flight turns | Peak leases held | CPU % mean |",
                  "|---|---|---|---|---|---|---|---|---|---|---|"]
        for p in r["per_node"]:
            c = r["docker"]["containers"].get(p["name"], {}).get("mean", 0.0)
            lines.append(f"| {p['name'].replace(CLUSTER_PROJECT + '-', '')} | {p['turns_ok']:.0f} | {p['turns_per_s'] or 0:.1f} | "
                         f"{fmt(p['latency_ms']['med'])} | {fmt(p['latency_ms']['p(95)'])} | {fmt(p['latency_ms']['p(99)'])} | "
                         f"{p['errors']['turns_409']:.0f} | {p['errors']['turns_503']:.0f} | "
                         f"{peak(p, 'eddi_processing_conversation_count')} | {peak(p, 'eddi_cluster_lease_held')} | {c:.0f} |")
        lines.append("")
    lines += ["Raw numbers: the `.json` files next to this report (not committed).", ""]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"wrote {path}", flush=True)
    return path


def add_cluster_args(p):
    p.add_argument("--app", help="local build to mount (default: target/quarkus-app of this checkout)")
    p.add_argument("--no-app", action="store_true", help="run the image as published, without a local build")
    p.add_argument("--image", default=None, help="runtime image (default labsai/eddi:6.5.0)")
    p.add_argument("--messaging", default="nats", choices=["nats", "in-memory"],
                   help="eddi.messaging.type; in-memory is the single-node baseline (cluster-up only)")
    p.add_argument("--max-in-flight", type=int, default=400, help="eddi.conversation.max-in-flight-turns per node (default 400)")
    p.add_argument("--mongo-pool", type=int, default=100, help="MongoDB maxPoolSize per node (default 100)")
    p.add_argument("--java-opts", default="-Xmx1536m", help="heap per node (default -Xmx1536m)")
    p.add_argument("--eddi-memory", default="2g", help="container memory per node (default 2g)")
    p.add_argument("--eddi-cpus", type=float, default=2.0, help="CPUs per node (default 2)")
    p.add_argument("--mongo-cpus", type=float, default=4.0, help="CPUs of the MongoDB container (default 4)")
    p.add_argument("--nats-cpus", type=float, default=1.0, help="CPUs per NATS node (default 1, the docs' sizing)")


def add_cluster_run_args(p):
    p.add_argument("--vus-per-node", type=int, default=300, help="concurrent conversations per node (default 300)")
    p.add_argument("--turns", type=int, default=20, help="turns per conversation (default 20)")
    p.add_argument("--warmup-turns", type=int, default=3, help="turns per conversation of the discarded warm-up run (0: none)")
    p.add_argument("--delay-ms", type=int, default=3000)
    p.add_argument("--think-ms", type=int, default=0)
    p.add_argument("--label", default="cluster")
    p.add_argument("--k6-timeout", type=int, default=3600)


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
        p.add_argument("--turns", type=int, default=None, help="turns per conversation (default 10; long-conversation 200)")
        p.add_argument("--delay-ms", type=int, default=3000)
        p.add_argument("--think-ms", type=int, default=0)
        p.add_argument("--ramp", default="30s")
        p.add_argument("--hold", default="60s")
        p.add_argument("--label", default="run")
        p.add_argument("--agent", help="agent id (default: the one `up` created)")
        p.add_argument("--k6-timeout", type=int, default=7200,
                       help="seconds a k6 run may take before the driver gives up (default 7200; long-conversation needs room)")
    cu = sub.add_parser("cluster-up", help="start N replicas behind the balancer and deploy the agent")
    cu.add_argument("--nodes", type=int, default=1)
    add_cluster_args(cu)
    sub.add_parser("cluster-down")
    cr = sub.add_parser("cluster-run", help="one steady run against the running cluster")
    add_cluster_run_args(cr)
    cm = sub.add_parser("cluster-matrix", help="fresh cluster per node count, one run each, Markdown report")
    cm.add_argument("--nodes", default="1,2,4,8", help="comma-separated node counts (default 1,2,4,8)")
    cm.add_argument("--baseline", action="store_true", help="also measure one in-memory single node first")
    cm.add_argument("--keep", action="store_true", help="leave the last cluster running")
    add_cluster_args(cm)
    add_cluster_run_args(cm)
    cp = sub.add_parser("cluster-report", help="rebuild the Markdown report from cluster-run .json results")
    cp.add_argument("files", nargs="+")
    cp.add_argument("--out", help="report path (default results/cluster-scaling-<date>.md)")
    args = ap.parse_args()
    if args.cmd in ("run", "matrix") and args.turns is None:
        args.turns = 200 if args.scenario == "long-conversation" else 10
    if args.cmd == "run":
        args.vus = int(args.vus)
    {"up": cmd_up, "down": cmd_down, "run": cmd_run, "matrix": cmd_matrix, "cluster-up": cmd_cluster_up,
     "cluster-down": cmd_cluster_down, "cluster-run": cmd_cluster_run, "cluster-matrix": cmd_cluster_matrix,
     "cluster-report": cluster_report_from_files}[args.cmd](args)


if __name__ == "__main__":
    main()
