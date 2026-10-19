"""Load-test and benchmark driver for EDDI (planning/throughput-optimization-plan.md, WS0).

    python scripts/loadtest/loadtest.py up [--db mongo|postgres] [--app DIR | --no-app] [--image IMG]
    python scripts/loadtest/loadtest.py run --scenario steady --vus 200 --turns 10 --delay-ms 3000 [--label baseline]
    python scripts/loadtest/loadtest.py matrix --vus 50,200,500 --delay-ms 3000 [--label baseline]
    python scripts/loadtest/loadtest.py stream --vus 50 --turns 5 --first-chunk-ms 500 [--endpoints native,openai]
    python scripts/loadtest/loadtest.py group --members 3 --rounds 1 --vus 10 --discussions 3 --delay-ms 1000
    python scripts/loadtest/loadtest.py cluster-matrix --nodes 1,2,4,8 --vus-per-node 300 --turns 20 --delay-ms 3000
    python scripts/loadtest/loadtest.py gate --thresholds scripts/loadtest/thresholds/db-ops-mongo.json
    python scripts/loadtest/loadtest.py report --label baseline [--laptop]
    python scripts/loadtest/loadtest.py down

`up` starts the database, the WireMock LLM and EDDI and creates one deployed LLM agent (`cluster-up`
does the same for N replicas on NATS behind nginx, from docker-compose.cluster.yml); what it started is kept in results/.state.json. Every
measuring command snapshots /q/metrics and the database's operation counters, drives the load
(k6 for turns and group discussions, drivers/stream_driver.py for SSE), samples the saturation
gauges every second, snapshots again and writes results/<label>-<kind>-….json. `report` renders
every result of a label as one Markdown benchmark page. See README.md. Standard library only.
"""
import argparse
import datetime
import json
import math
import os
import platform
from http.client import HTTPConnection  # the module name would clash with http() below
import re
import shlex
# The driver's job is to run `docker compose`, `docker` and `git`: fixed argument lists, no shell.
import subprocess  # nosec B404
import sys
import threading
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RESULTS = os.path.join(HERE, "results")
STATE = os.path.join(RESULTS, ".state.json")
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "eddi-loadtest")
EDDI_PORT = int(os.environ.get("LOADTEST_EDDI_PORT", "7370"))
EDDI = f"http://127.0.0.1:{EDDI_PORT}"
WIREMOCK = "http://127.0.0.1:" + os.environ.get("LOADTEST_WIREMOCK_PORT", "18370")
DEFAULT_IMAGE = "labsai/eddi:6.5.0"
# The /v1 key set in docker-compose.loadtest.yml.
OPENAI_KEY = "sk-loadtest-openai-compat-0123456789"
# The streaming stub's id, so that every `stream` run replaces it instead of adding another.
STREAM_STUB_ID = "5f0c7c1e-57ea-4000-8000-000000000001"
# Load generators of any stack on this machine; a measurement waits while one of them runs.
LOAD_IMAGES = ("grafana/k6", "python:3.12.7-alpine3.20")
# The single-node stack (docker-compose.loadtest.yml) is the default; the cluster-* commands switch
# every helper below to docker-compose.cluster.yml through use_cluster_stack().
STACK_FILE = "docker-compose.loadtest.yml"

# Gauges sampled during a run; the report shows the peak of every series (per node, max over nodes).
SAMPLED = re.compile(r"^(eddi_runtime_executor_(active|queued|max_threads)|eddi_processing_conversation_count|"
                     r"eddi_coordinator_(queue_depth|active_conversations)|eddi_audit_queue_(depth|capacity)|"
                     r"jvm_threads_(live|peak)_threads|mongodb_driver_pool_(size|checkedout|waitqueuesize))")
# Counters compared before/after a run (summed over nodes).
COUNTED = re.compile(r"^(eddi_audit_entries_dropped_total|eddi_turn_rejected_total|eddi_coordinator_dead_lettered_total|eddi_turn_idempotency_total|"
                     r"eddi_conversation_processing_duration_seconds_(count|sum)|eddi_llm_circuit[a-z_]*_total|"
                     r"http_server_requests_seconds_count)")
TURN_ERRORS = ["turns_503", "turns_429", "turns_408", "turns_409", "turns_5xx_other", "turns_other", "turns_no_response",
               "turns_app_error", "start_failed", "turns_abandoned", "starts_refused"]
STATS = ["med", "p(90)", "p(95)", "p(99)", "max"]


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
    except FileNotFoundError:
        raise SystemExit(f"{cmd[0]} is not installed or not on PATH")
    if check and r.returncode != 0:
        raise SystemExit(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def compose_files(state):
    if STACK_FILE == "docker-compose.loadtest.yml":
        files = ["-f", os.path.join(HERE, STACK_FILE),
                 "-f", os.path.join(HERE, f"docker-compose.{state.get('db') or 'mongo'}.yml")]
    else:  # the cluster stack brings its own MongoDB
        files = ["-f", os.path.join(HERE, STACK_FILE)]
    if state.get("app"):
        files += ["-f", os.path.join(HERE, "docker-compose.app.yml")]
    # Your own overrides last, e.g. EDDI settings under test (README: "Changing EDDI settings").
    for extra in filter(None, os.environ.get("LOADTEST_COMPOSE_EXTRA", "").split(os.pathsep)):
        files += ["-f", os.path.abspath(extra)]
    return files


def compose_env(state):
    env = dict(os.environ)
    if state.get("app"):
        env["EDDI_APP"] = state["app"]
    if state.get("image"):
        env["LOADTEST_EDDI_IMAGE"] = state["image"]
    return env


def compose(*cmd, check=True, capture=True, timeout=COMMAND_TIMEOUT_S, on_timeout=None):
    # The cluster commands pass their argparse namespace first; what they start is in the state file.
    if cmd and not isinstance(cmd[0], str):
        cmd = cmd[1:]
    state = load_state()
    files = compose_files(state)
    return sh("docker", "compose", *files, *cmd, check=check, capture=capture, env=compose_env(state), timeout=timeout,
              on_timeout=on_timeout)


def http(method, url, body=None, timeout=30, headers=None):
    """One request to the local stack. Plain http only: every target is EDDI or WireMock on 127.0.0.1."""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "http" or not parts.hostname:
        raise ValueError(f"only http:// URLs are used here, got {url!r}")
    data = json.dumps(body).encode() if body is not None else None
    path = parts.path + ("?" + parts.query if parts.query else "")
    h = {"Content-Type": "application/json"} if data else {}
    h.update(headers or {})
    conn = HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    try:
        conn.request(method, path or "/", body=data, headers=h)
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


def ensure_results():
    """results/ is bind-mounted into the k6 container, which runs as a non-root user (12345). On a
    Linux host that user cannot write into a directory the caller created with mode 0755; Docker
    Desktop hides this. World-writable it is: the directory only ever holds load-test output."""
    os.makedirs(RESULTS, exist_ok=True)
    if os.name == "posix":
        os.chmod(RESULTS, 0o777)  # nosec B103 - scratch output directory shared with a container user


def save_state(state):
    ensure_results()
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)


def node_urls(state=None):
    """Host URLs of the EDDI nodes the measurements scrape. The single-node stack has one; the
    cluster-* commands keep their own list (cluster_nodes)."""
    return [EDDI]


def client_base(state=None):
    """Where the load generators, inside the compose network, send their requests."""
    return "http://eddi:7070"


def topology_suffix(state):
    """Part of a result's file name, so that runs on another database do not overwrite it."""
    return "-pg" if state.get("db") == "postgres" else ""


def command_line():
    return "python scripts/loadtest/loadtest.py " + " ".join(shlex.quote(a) for a in sys.argv[1:])


def env_overrides():
    return {k: v for k, v in sorted(os.environ.items()) if k.startswith("LOADTEST_") or k == "COMPOSE_PROJECT_NAME"}


# ------------------------------------------------------------------ stack

def cmd_up(args):
    if not args.no_app:
        args.app = os.path.abspath(args.app or os.path.join(REPO, "target", "quarkus-app"))
        if not os.path.isfile(os.path.join(args.app, "quarkus-run.jar")):
            raise SystemExit(f"{args.app} has no quarkus-run.jar — run ./mvnw package -DskipTests -DskipUi=true first, or pass --no-app")
        os.makedirs(os.path.join(args.app, "tmp"), exist_ok=True)  # mount point of the tmpfs
    state = {"app": None if args.no_app else args.app, "image": args.image, "db": args.db,
             "up_command": command_line(), "up_env": env_overrides()}
    save_state(state)
    bring_up()
    state = load_state()
    state["agent"], state["agent_name"] = setup_agent("loadtest")
    save_state(state)
    install_stream_stub(500, 10, 20)
    # Pulled now so that the first measurement does not include a pull, and so that their digests are known.
    compose("--profile", "k6", "--profile", "stream", "pull", "-q", "k6", "stream", check=False)
    state["env"] = collect_env()
    save_state(state)
    print(f"stack up ({state['db']}): EDDI {EDDI}, agent {state['agent']}")


def bring_up():
    """Start the stack in the state file and wait until EDDI is ready."""
    state = load_state()
    services = [{"mongo": "mongodb", "postgres": "postgres"}[state.get("db") or "mongo"], "wiremock", "eddi"]
    compose("up", "-d", "--remove-orphans", *services)
    if not wait_until(lambda: http("GET", EDDI + "/q/health/ready", timeout=5)[0] == 200, 300, 2):
        print(compose("logs", "--tail", "80", "eddi", check=False).stdout)
        raise SystemExit("EDDI did not become ready")
    if state.get("db") == "postgres":
        # In the maintenance database, so that the counters' own queries are not counted against eddi.
        psql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")


def setup_agent(prefix, system_prompt="You are a load test agent."):
    name = f"{prefix}-{int(time.time() * 1000) % 10**10}"
    body = {"agentName": name, "systemPrompt": system_prompt, "provider": "openai",
            "model": "mock-1", "apiKey": "sk-loadtest-0123456789", "baseUrl": "http://wiremock:8080/v1",
            "enableBuiltInTools": False, "deploy": True}
    s, h, b = http("POST", EDDI + "/administration/agents/setup", body, timeout=120)
    if s >= 300 or s == 0:
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
    return agent, name


def cmd_down(args):
    compose("down", "-v", "--remove-orphans", check=False)
    print("stack down")


def collect_env():
    """Hardware and versions, recorded once per `up` and copied into every result."""
    state = load_state()
    env = {"host": {"os": platform.platform(), "machine": platform.machine(), "python": platform.python_version()}}
    r = sh("docker", "info", "--format", "{{json .}}", check=False)
    try:
        d = json.loads(r.stdout)
        env["docker"] = {"server": d.get("ServerVersion"), "os": d.get("OperatingSystem"), "kernel": d.get("KernelVersion"),
                         "cpus": d.get("NCPU"), "memory_gib": round((d.get("MemTotal") or 0) / 2**30, 1),
                         "architecture": d.get("Architecture")}
    except ValueError:
        env["docker"] = {}
    cpu = compose("exec", "-T", "wiremock", "sh", "-c", "grep -m1 'model name' /proc/cpuinfo", check=False).stdout
    env["cpu_model"] = cpu.split(":", 1)[1].strip() if ":" in cpu else platform.processor()
    java = compose("exec", "-T", "eddi", "java", "-version", check=False)
    env["java"] = next((line for line in (java.stderr + java.stdout).splitlines() if " version " in line), "?")
    images = {}
    r = compose("--profile", "k6", "--profile", "stream", "config", "--images", check=False)
    for img in sorted(set(r.stdout.split())):
        digest = sh("docker", "image", "inspect", "--format", "{{index .RepoDigests 0}}", img, check=False).stdout.strip()
        images[img] = digest.split("@", 1)[1] if "@" in digest else "?"
    env["images"] = images
    app = state.get("app")
    if app:
        checkout = os.path.abspath(os.path.join(app, "..", ".."))
        head = sh("git", "-C", checkout, "rev-parse", "HEAD", check=False).stdout.strip()
        branch = sh("git", "-C", checkout, "rev-parse", "--abbrev-ref", "HEAD", check=False).stdout.strip()
        dirty = sh("git", "-C", checkout, "status", "--porcelain", "--", "src", "pom.xml", check=False).stdout.strip()
        env["build"] = {"commit": head or "unknown", "branch": branch, "dirty": bool(dirty)}
    env["eddi"] = {"image": state.get("image") or DEFAULT_IMAGE, "local_build": bool(app), "db": state.get("db") or "mongo",
                   "nodes": 1, "messaging": "in-memory",
                   "java_opts": os.environ.get("LOADTEST_JAVA_OPTS", "-Xmx2g"),
                   "memory_limit": os.environ.get("LOADTEST_EDDI_MEMORY", "3g"),
                   "cpus_per_node": os.environ.get("LOADTEST_EDDI_CPUS") or "unlimited"}
    env["collected"] = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    return env


# ------------------------------------------------------------------ measurements

def scrape(base=None):
    s, _, body = http("GET", (base or EDDI) + "/q/metrics", timeout=10, headers={"Accept": "text/plain"})
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
            except ValueError:  # a value Prometheus text allows but float() does not; not a meter we read
                pass
    return out


def scrape_all():
    return [scrape(u) for u in node_urls()]


def mongo_eval(js):
    r = compose("exec", "-T", "mongodb", "mongosh", "-u", "eddi", "-p", "changeme", "--authenticationDatabase", "admin",
                "--quiet", "--eval", js, check=False)
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {}


def psql(sql):
    r = compose("exec", "-T", "postgres", "psql", "-U", "eddi", "-d", "postgres", "-tAc", sql, check=False)
    return r.stdout.strip()


PG_SNAPSHOT = """SELECT json_build_object(
 'ops', (SELECT coalesce(json_object_agg(k, n), '{}'::json) FROM (
    SELECT lower(split_part(ltrim(s.query), ' ', 1)) AS k, sum(s.calls)::bigint AS n
    FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid WHERE d.datname = 'eddi' GROUP BY 1) t),
 'detail', (SELECT json_build_object('rows_returned', tup_returned, 'rows_fetched', tup_fetched, 'rows_inserted', tup_inserted,
    'rows_updated', tup_updated, 'rows_deleted', tup_deleted, 'commits', xact_commit, 'rollbacks', xact_rollback)
    FROM pg_stat_database WHERE datname = 'eddi'))"""


def db_snapshot():
    """Operation counters of the database: MongoDB opcounters, or PostgreSQL statements by verb."""
    if (load_state().get("db") or "mongo") == "postgres":
        try:
            d = json.loads(psql(PG_SNAPSHOT) or "{}")
        except ValueError:
            d = {}
        return {"kind": "postgres", "ops": d.get("ops") or {}, "detail": {"database": d.get("detail") or {}}}
    # Counters are Longs in mongosh; Number() keeps JSON.stringify from emitting {high, low}.
    ops = mongo_eval("const c=db.serverStatus().opcounters; const o={}; for (const k in c) o[k]=Number(c[k]); JSON.stringify(o)")
    # Per-collection operation counts: what tells conversation loads apart from config reads.
    top = mongo_eval("const t=db.adminCommand({top:1}).totals; const o={}; for (const k in t) { if (k.startsWith('eddi.')) "
                     "{ o[k.slice(5)] = {queries:Number(t[k].queries.count), getmore:Number(t[k].getmore.count), insert:Number(t[k].insert.count), "
                     "update:Number(t[k].update.count), remove:Number(t[k].remove.count), commands:Number(t[k].commands.count)} } } JSON.stringify(o)")
    return {"kind": "mongo", "ops": ops, "detail": top}


def db_diff(before, after):
    ops = {k: after["ops"].get(k, 0) - before["ops"].get(k, 0) for k in after.get("ops", {})}
    ops = {k: v for k, v in ops.items() if v}
    detail = {}
    for c, a in after.get("detail", {}).items():
        b = before.get("detail", {}).get(c, {})
        d = {k: a[k] - b.get(k, 0) for k in a if isinstance(a[k], (int, float)) and a[k] - b.get(k, 0)}
        if d:
            detail[c] = d
    return {"kind": after.get("kind"), "ops": ops, "detail": detail}


def mongo_snapshot():
    """db_snapshot() in the shape the cluster-* commands read (opcounters, collections)."""
    d = db_snapshot()
    return {"opcounters": d["ops"], "collections": d["detail"]}


def mongo_diff(before, after):
    d = db_diff({"ops": before["opcounters"], "detail": before["collections"]},
                {"kind": "mongo", "ops": after["opcounters"], "detail": after["collections"]})
    return {"opcounters": d["ops"], "collections": d["detail"]}


def per_turn(db, turns):
    if not turns:
        return {}
    out = {k: round(v / turns, 2) for k, v in sorted(db["ops"].items())}
    out["total"] = round(sum(db["ops"].values()) / turns, 2)
    return out


class Sampler(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.peaks = {}
        self.contended = set()
        self.stop = threading.Event()

    def run(self):
        tick = 0
        while not self.stop.is_set():
            if tick % 5 == 0:
                # A load test another stack starts mid-run shares the CPU with this one: say so in the result.
                self.contended.update(foreign_load())
            tick += 1
            for node in scrape_all():
                for k, v in node.items():
                    if SAMPLED.match(k):
                        self.peaks[k] = max(v, self.peaks.get(k, float("-inf")))
            self.stop.wait(1.0)


def counters_diff(before, after):
    total = {}
    for b, a in zip(before, after):
        for k in a:
            if COUNTED.match(k) and a[k] - b.get(k, 0.0) != 0:
                total[k] = total.get(k, 0.0) + a[k] - b.get(k, 0.0)
    return total


# EDDI writes the audit ledger from a queue every eddi.audit.flush-interval-seconds (default 3), so a
# measurement window could otherwise catch the warm-up's last batch or lose its own (CI saw 3.8 and
# 2.66 inserts per turn against 3.2). Each snapshot waits until that queue is empty and one more
# interval has passed, for a batch already taken off the queue but not yet stored.
AUDIT_FLUSH_S = float(os.environ.get("LOADTEST_AUDIT_FLUSH_S", "3"))


def settle(timeout=60):
    def drained():
        for node in scrape_all():
            depth = [v for k, v in node.items() if k.startswith("eddi_audit_queue_depth")]
            # No gauge means the scrape failed (an empty dict), not an empty queue.
            if not depth or sum(depth) != 0:
                return False
        return True
    if not wait_until(drained, timeout, 0.5):
        # A snapshot now would miss the queued audit inserts and could pass the gate on a low count.
        raise SystemExit(f"the audit queue did not drain within {timeout} s; database counts would be incomplete")
    time.sleep(AUDIT_FLUSH_S + 0.5)


class Measurement:
    """Snapshots around one load phase: metrics of every node, database counters, gauge peaks."""

    def __enter__(self):
        settle()
        self.m_before, self.db_before = scrape_all(), db_snapshot()
        self.sampler = Sampler()
        self.sampler.start()
        self.t0 = time.time()
        return self

    def __exit__(self, *exc):
        self.elapsed = time.time() - self.t0
        self.sampler.stop.set()
        self.sampler.join(5)
        settle()  # the last turns' persistence and audit batch, before counting database ops
        self.counters = counters_diff(self.m_before, scrape_all())
        self.db = db_diff(self.db_before, db_snapshot())
        self.peaks = self.sampler.peaks
        self.contended = sorted(self.sampler.contended)
        if self.contended:
            print(f"warning: another load test ran during this measurement: {', '.join(self.contended)}", flush=True)
        return False


def metric(summary, name, stat):
    return (summary.get("metrics", {}).get(name, {}).get("values", {}) or {}).get(stat)


def foreign_load():
    """Names of load-generator containers of other stacks on this machine.

    By compose project label, compared exactly: a name prefix would take `eddi-loadtest-bench-k6-run-…`
    for part of the `eddi-loadtest` stack. A container without the label is foreign too."""
    r = sh("docker", "ps", "--format", '{{.Names}}\t{{.Image}}\t{{.Label "com.docker.compose.project"}}', check=False, timeout=60)
    busy = []
    for line in r.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and parts[1].startswith(LOAD_IMAGES) and (parts[2] if len(parts) > 2 else "") != PROJECT:
            busy.append(parts[0])
    return busy


def wait_for_quiet(args):
    """Wait while another stack's load generator runs: two load tests on one machine measure each other."""
    if getattr(args, "no_wait", False):
        return
    announced = False
    deadline = time.time() + 7200
    while time.time() < deadline:
        busy = foreign_load()
        if not busy:
            return
        if not announced:
            print(f"waiting for another load test to finish: {', '.join(busy)} (--no-wait to skip)", flush=True)
            announced = True
        time.sleep(15)
    raise SystemExit("another load test kept running for two hours; giving up")


def set_mock_delay(ms):
    s, _, b = http("POST", WIREMOCK + "/__admin/settings", {"fixedDelay": ms})
    if s == 0 or s >= 300:  # 0: WireMock not reachable
        raise SystemExit(f"could not set the mock delay: {s} {b}")


def k6(args, script, env, out_name):
    summary_path = os.path.join(RESULTS, out_name)
    if os.path.exists(summary_path):
        os.remove(summary_path)  # a summary left by an earlier run must never stand in for this one
    cmd = ["run", "--rm", "-e", f"BASE_URL={client_base()}", "-e", f"OUT={out_name}"]
    for k, v in env.items():
        cmd += ["-e", f"{k}={v}"]
    cmd += ["k6", "run", "--quiet", f"/scripts/{script}"]
    # On a timeout the one-off k6 container would keep loading EDDI after the driver gave up, so it is
    # stopped and removed by service name before the run ends.
    r = compose("--profile", "k6", *cmd, check=False, capture=False, timeout=getattr(args, "k6_timeout", 7200),
                on_timeout=lambda: compose("--profile", "k6", "rm", "--stop", "--force", "k6", check=False, timeout=120))
    if r.returncode != 0:
        raise SystemExit(f"k6 exited {r.returncode}; no result written for {out_name}")
    try:
        with open(summary_path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise SystemExit(f"k6 left no readable summary {out_name} ({e}); no result written")


def write_result(result):
    state = load_state()
    result.update({"command": command_line(), "env_overrides": env_overrides(), "env": state.get("env"),
                   "up_command": state.get("up_command"), "date": datetime.date.today().isoformat(),
                   "finished": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")})
    with open(os.path.join(RESULTS, f"{result['name']}.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    return result


def turns_k6_env(agent, args, turns=None):
    return {"AGENT_ID": agent, "SCENARIO": args.scenario, "VUS": args.vus, "TURNS": turns or args.turns,
            "THINK_MS": args.think_ms, "DELAY_MS": args.delay_ms, "RAMP": args.ramp, "HOLD": args.hold,
            "RETRY_503": getattr(args, "retry_503", 0), "RETRY_CAP_MS": getattr(args, "retry_cap_ms", 10000)}


def cmd_run(args, label=None, name=None, kind="turns"):
    state = load_state()
    agent = args.agent or state.get("agent")
    if not agent:
        raise SystemExit("no agent — run `up` first")
    label = label or args.label
    wait_for_quiet(args)
    set_mock_delay(args.delay_ms)
    retry = getattr(args, "retry_503", 0)
    name = name or f"{label}-{args.scenario}{topology_suffix(state)}{f'-retry{retry}' if retry else ''}-{args.vus}vu-{args.delay_ms}ms"
    ensure_results()
    if getattr(args, "warmup_turns", 0):
        # Not recorded: JIT, connection pools and configuration caches warm up here.
        k6(args, "turns.js", turns_k6_env(agent, args, args.warmup_turns), f"{name}.warmup.k6.json")
    with Measurement() as m:
        summary = k6(args, "turns.js", turns_k6_env(agent, args), f"{name}.k6.json")
    ok = metric(summary, "turns_ok", "count") or 0
    result = {
        "kind": kind, "name": name, "label": label, "scenario": args.scenario, "vus": args.vus, "turns_per_vu": args.turns,
        "llm_delay_ms": args.delay_ms, "think_ms": args.think_ms, "nodes": state.get("nodes") or 1,
        "db_kind": state.get("db") or "mongo", "elapsed_s": round(m.elapsed, 1),
        "turns_ok": ok, "turns_per_s": metric(summary, "turns_ok", "rate"),
        "latency_ms": {k: metric(summary, "turn_duration", k) for k in STATS},
        "overhead_ms": {k: metric(summary, "turn_overhead", k) for k in STATS},
        # Retry mode only (--retry-503 N): what the user waited, refusals and backoff included.
        "retry_503": retry, "retry_cap_ms": getattr(args, "retry_cap_ms", 10000) if retry else None,
        "total_latency_ms": {k: metric(summary, "turn_total_duration", k) for k in STATS} if retry else None,
        "attempts": {k: metric(summary, "turn_attempts", k) for k in ["avg", "med", "p(95)", "p(99)", "max"]} if retry else None,
        "errors": {k: metric(summary, k, "count") or 0 for k in TURN_ERRORS},
        "db": m.db, "db_ops_per_turn": per_turn(m.db, ok), "peaks": m.peaks, "counters": m.counters,
        "contended_by": m.contended,
    }
    write_result(result)
    print(one_line(result))
    return result


def one_line(r):
    # cluster-run results carry neither overhead nor per-turn database fields
    lat, ovh = r["latency_ms"], r.get("overhead_ms") or {k: None for k in STATS}
    errs = ", ".join(f"{k[6:] if k.startswith('turns_') else k}={int(v)}" for k, v in r["errors"].items() if v)
    return (f"{r['name']}: {r['turns_ok']:.0f} ok, {r['turns_per_s'] or 0:.1f} turns/s, p50 {fmt(lat['med'])} p95 {fmt(lat['p(95)'])} "
            f"p99 {fmt(lat['p(99)'])} ms; EDDI overhead p50 {fmt(ovh['med'])} p95 {fmt(ovh['p(95)'])} p99 {fmt(ovh['p(99)'])} ms; "
            f"db ops/turn {(r.get('db_ops_per_turn') or {}).get('total', '-')}; errors: {errs or 'none'}"
            + (f"; with backoff p50 {fmt(r['total_latency_ms']['med'])} p95 {fmt(r['total_latency_ms']['p(95)'])} "
               f"p99 {fmt(r['total_latency_ms']['p(99)'])} ms, attempts avg {fmt(r['attempts']['avg'], 2)} "
               f"max {fmt(r['attempts']['max'])}, abandoned {r['errors'].get('turns_abandoned', 0):.0f}"
               if r.get("retry_503") else ""))


def fmt(v, digits=0):
    return "-" if v is None else f"{v:.{digits}f}"


def peak(r, prefix, contains=""):
    vals = [v for k, v in r["peaks"].items() if k.startswith(prefix) and contains in k]
    return fmt(max(vals)) if vals else "-"


def ops_cell(o):
    if not o:
        return "-"
    return " · ".join(f"{k} {v:g}" for k, v in o.items() if k != "total") + f" (total {o.get('total', 0):g})"


def matrix_report(results, path, args):
    lines = [f"# Load test — {args.label} — {datetime.date.today().isoformat()}", "",
             f"Scenario `{args.scenario}`, {args.turns} turns per conversation, mock LLM delay {args.delay_ms} ms, "
             + f"think time {args.think_ms} ms. Image `{load_state().get('image') or DEFAULT_IMAGE}`"
             + f"{' with the local build mounted' if load_state().get('app') else ''}; commit `{git_head()}`.", "",
             "| Concurrent conv. | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | Overhead p50/p95/p99 ms | 503 | 408 | 5xx other | "
             + "no response | 200 with TurnError | Peak managed active | Peak managed queued | Peak in-flight turns | Peak JVM threads | "
             + "DB ops/turn |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        e, lat, ovh = r["errors"], r["latency_ms"], r["overhead_ms"]
        lines.append(f"| {r['vus']} | {r['turns_ok']:.0f} | {r['turns_per_s'] or 0:.1f} | {fmt(lat['med'])} | {fmt(lat['p(95)'])} | "
                     f"{fmt(lat['p(99)'])} | {fmt(ovh['med'])} / {fmt(ovh['p(95)'])} / {fmt(ovh['p(99)'])} | {e['turns_503']:.0f} | "
                     f"{e['turns_408']:.0f} | {e['turns_5xx_other']:.0f} | {e['turns_no_response']:.0f} | {e.get('turns_app_error', 0):.0f} | "
                     f"{peak(r, 'eddi_runtime_executor_active', 'managed')} | "
                     f"{peak(r, 'eddi_runtime_executor_queued', 'managed')} | {peak(r, 'eddi_processing_conversation_count')} | "
                     f"{peak(r, 'jvm_threads_live_threads')} | {ops_cell(r['db_ops_per_turn'])} |")
    lines += ["", "Audit: " + "; ".join(
        f"{r['vus']} conv. peak queue {peak(r, 'eddi_audit_queue_depth')}, dropped "
        f"{fmt(sum(v for k, v in r['counters'].items() if k.startswith('eddi_audit_entries_dropped')))}" for r in results), "",
        "Per-collection database operations per OK turn (largest run):", ""]
    big = max(results, key=lambda r: r["vus"])
    if big["turns_ok"]:
        for c, d in sorted(big["db"]["detail"].items()):
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
    matrix_report(results, os.path.join(RESULTS, f"{args.label}-{args.scenario}-{datetime.date.today().isoformat()}.md"), args)


# ------------------------------------------------------------------ streaming

def stream_body(tokens):
    """An OpenAI chat-completion stream whose SSE events all have the same byte length.

    WireMock's chunkedDribbleDelay cuts the body into numberOfChunks equal slices, so with equal events
    every slice is exactly one event and the first slice is the first token, never half of one."""
    def chunk(delta, finish):
        return "data: " + json.dumps({"id": "chatcmpl-loadtest", "object": "chat.completion.chunk", "created": 1700000000,
                                      "model": "mock-1", "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]},
                                     separators=(",", ":"))
    events = [chunk({"content": f"tok{i:03d} "}, None) for i in range(tokens)]
    events += [chunk({}, "stop"), "data: [DONE]"]
    width = max(len(e) for e in events) + 4
    out = []
    for e in events:
        pad = width - len(e)
        # JSON events are padded with insignificant whitespace, [DONE] with an SSE comment line.
        out.append((e[:-1] + " " * pad + "}" if e.endswith("}") else ":" + " " * (pad - 2) + "\n" + e) + "\n\n")
    return "".join(out), len(out)


def install_stream_stub(first_chunk_ms, tokens, chunk_ms):
    """The stream=true answer of the mock: first token after first_chunk_ms, then one event every chunk_ms.

    WireMock waits fixedDelayMilliseconds, writes the headers, then sleeps one interval before every
    slice — so the delay before the headers is the first-chunk delay minus one interval."""
    if first_chunk_ms < chunk_ms:
        raise SystemExit(f"--first-chunk-ms ({first_chunk_ms}) must be at least --chunk-ms ({chunk_ms})")
    body, n = stream_body(tokens)
    stub = {"id": STREAM_STUB_ID, "name": "chat completions, streamed", "priority": 1,
            "request": {"method": "POST", "urlPathPattern": "/v1/chat/completions",
                        "bodyPatterns": [{"matchesJsonPath": "$[?(@.stream == true)]"}]},
            "response": {"status": 200, "headers": {"Content-Type": "text/event-stream", "Cache-Control": "no-cache"}, "body": body,
                         "fixedDelayMilliseconds": first_chunk_ms - chunk_ms,
                         "chunkedDribbleDelay": {"numberOfChunks": n, "totalDuration": chunk_ms * n}}}
    s, _, b = http("PUT", f"{WIREMOCK}/__admin/mappings/{STREAM_STUB_ID}", stub)
    if s == 404:
        s, _, b = http("POST", f"{WIREMOCK}/__admin/mappings", stub)
    if s == 0 or s >= 300:
        raise SystemExit(f"could not install the streaming stub: {s} {b[:300]}")


def openai_model_id(agent_name):
    s, _, b = http("GET", EDDI + "/v1/models", headers={"Authorization": f"Bearer {OPENAI_KEY}", "X-OpenWebUI-User-Id": "loadtest"})
    if s != 200:
        raise SystemExit(f"GET /v1/models answered {s}: {b[:300]}")
    ids = [m.get("id", "") for m in json.loads(b).get("data", [])]
    match = [i for i in ids if i.startswith(agent_name) and not i.endswith(":stateless")]
    if not match:
        raise SystemExit(f"agent {agent_name} is not among the /v1 models: {ids}")
    return match[0]


def stream_driver(args, target, base, out_name, extra=()):
    path = os.path.join(RESULTS, out_name)
    if os.path.exists(path):
        os.remove(path)
    cmd = ["--profile", "stream", "run", "--rm", "stream", "--target", target, "--base", base, "--vus", str(args.vus),
           "--turns", str(args.turns), "--warmup-turns", str(args.warmup_turns), "--out", out_name, *extra]
    r = compose(*cmd, check=False, capture=False, timeout=args.k6_timeout,
                on_timeout=lambda: compose("--profile", "stream", "rm", "--stop", "--force", "stream", check=False, timeout=120))
    if r.returncode != 0:
        raise SystemExit(f"stream driver exited {r.returncode} for {target}")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def cmd_stream(args):
    state = load_state()
    if not state.get("agent"):
        raise SystemExit("no agent — run `up` first")
    wait_for_quiet(args)
    set_mock_delay(0)  # the global delay would add to the stub's own
    install_stream_stub(args.first_chunk_ms, args.tokens, args.chunk_ms)
    name = f"{args.label}-stream{topology_suffix(state)}-{args.vus}vu-{args.first_chunk_ms}ms"
    run_id = f"{args.label}-{int(time.time())}"
    # Calibration: the same streamed request, straight to the mock, from the same client in the same
    # network. Overhead is what EDDI adds on top of this, percentile by percentile.
    mock = stream_driver(args, "mock", "http://wiremock:8080", f"{name}.mock.stream.json")
    endpoints = {}
    for ep in args.endpoints.split(","):
        extra = ["--agent", state["agent"], "--run-id", run_id]
        if ep == "openai":
            extra += ["--model", openai_model_id(state["agent_name"]), "--api-key", OPENAI_KEY]
        with Measurement() as m:
            res = stream_driver(args, ep, client_base(), f"{name}.{ep}.stream.json", extra)
        ttft, mttft = res["metrics"].get("ttft_ms", {}), mock["metrics"].get("ttft_ms", {})
        res["overhead_vs_mock_ms"] = {k: ttft[k] - mttft[k] for k in STATS if k in ttft and k in mttft}
        res["overhead_vs_configured_ms"] = {k: ttft[k] - args.first_chunk_ms for k in STATS if k in ttft}
        res.update({"db": m.db, "db_ops_per_turn": per_turn(m.db, res["turns_ok"]), "peaks": m.peaks, "counters": m.counters,
                    "contended_by": m.contended})
        endpoints[ep] = res
        print(f"{name} {ep}: {res['turns_ok']} ok, TTFT p50 {fmt(ttft.get('med'))} p95 {fmt(ttft.get('p(95)'))} "
              f"p99 {fmt(ttft.get('p(99)'))} ms (mock alone p50 {fmt(mttft.get('med'))}); overhead p50 "
              f"{fmt(res['overhead_vs_mock_ms'].get('med'))} p95 {fmt(res['overhead_vs_mock_ms'].get('p(95)'))} "
              f"p99 {fmt(res['overhead_vs_mock_ms'].get('p(99)'))} ms; errors {res['errors'] or 'none'}")
    write_result({"kind": "stream", "name": name, "label": args.label, "vus": args.vus, "turns_per_vu": args.turns,
                  "warmup_turns": args.warmup_turns, "first_chunk_ms": args.first_chunk_ms, "tokens": args.tokens,
                  "chunk_ms": args.chunk_ms, "nodes": state.get("nodes") or 1, "db_kind": state.get("db") or "mongo",
                  "mock": mock, "endpoints": endpoints})


# ------------------------------------------------------------------ group discussions

def create_group(members, moderator, rounds, style):
    body = {"name": f"loadtest-{len(members)}m-{int(time.time())}", "style": style, "maxRounds": rounds,
            "members": [{"agentId": a, "displayName": f"Member {i + 1}", "speakingOrder": i + 1} for i, a in enumerate(members)],
            "moderatorAgentId": moderator,
            # Room for the largest groups this scenario runs; the defaults stop at 50 member turns.
            "protocol": {"agentTimeoutSeconds": 300, "onAgentFailure": "SKIP", "maxTurns": 1000}}
    s, h, b = http("POST", EDDI + "/groupstore/groups", body, timeout=60)
    if s >= 300 or s == 0:
        raise SystemExit(f"group create failed: {s} {b[:500]}")
    loc = h.get("Location") or h.get("location") or ""
    m = re.search(r"groups/([0-9a-zA-Z-]+)", loc) or re.search(r'"id"\s*:\s*"([^"]+)"', b)
    if not m:
        raise SystemExit(f"no group id in {loc!r} / {b[:300]}")
    return m.group(1)


def cmd_group(args):
    state = load_state()
    if not state.get("agent"):
        raise SystemExit("no agent — run `up` first")
    # One agent per seat (a group refuses a repeated member) plus the moderator; kept across runs.
    agents = state.setdefault("group_agents", [])
    while len(agents) < args.members + 1:
        agents.append(setup_agent("loadtest-member")[0])
        save_state(state)
    group = create_group(agents[:args.members], agents[args.members], args.rounds, args.style)
    wait_for_quiet(args)
    set_mock_delay(args.delay_ms)
    name = f"{args.label}-group{topology_suffix(state)}-{args.members}m-{args.vus}d-{args.delay_ms}ms"
    env = {"GROUP_ID": group, "VUS": args.vus, "DISCUSSIONS": args.discussions}
    with Measurement() as m:
        summary = k6(args, "groups.js", env, f"{name}.k6.json")
    turns = metric(summary, "member_turns", "count") or 0
    ok = metric(summary, "discussions_ok", "count") or 0
    result = {
        "kind": "group", "name": name, "label": args.label, "members": args.members, "rounds": args.rounds, "style": args.style,
        "vus": args.vus, "discussions_per_vu": args.discussions, "llm_delay_ms": args.delay_ms, "nodes": state.get("nodes") or 1,
        "db_kind": state.get("db") or "mongo", "elapsed_s": round(m.elapsed, 1), "discussions_ok": ok,
        "discussions_failed": metric(summary, "discussions_failed", "count") or 0,
        "http_errors": metric(summary, "discussion_http_errors", "count") or 0,
        "member_turns": turns, "member_turns_failed": metric(summary, "member_turns_failed", "count") or 0,
        "member_turns_per_s": turns / m.elapsed if m.elapsed else 0, "turns_per_discussion": turns / ok if ok else None,
        "discussion_ms": {k: metric(summary, "discussion_duration", k) for k in STATS},
        "db": m.db, "db_ops_per_member_turn": per_turn(m.db, turns), "peaks": m.peaks, "counters": m.counters,
        "contended_by": m.contended,
    }
    write_result(result)
    d = result["discussion_ms"]
    print(f"{name}: {ok:.0f} discussions ok, {turns:.0f} member turns, {result['member_turns_per_s']:.1f} member turns/s, "
          f"discussion p50 {fmt(d['med'])} p95 {fmt(d['p(95)'])} ms, failed {result['discussions_failed']:.0f}")


# ------------------------------------------------------------------ regression gate

def cmd_gate(args):
    """Database operations per turn against committed ceilings: deterministic, unlike wall-clock numbers.

    Two kinds of ceiling. `max_ops_per_turn` caps server-wide counters; MongoDB counts inserted, updated
    and deleted *documents* there, which a fixed scenario reproduces exactly. `max_collection_ops_per_turn`
    caps the operations on the collections a turn reads and writes itself. Collections whose reads
    follow the clock (cache refreshes of agents, workflows, quotas; batched audit flushes) do more work
    on a slower runner, so they are reported, and only the loose total-query ceiling covers them."""
    with open(args.thresholds, encoding="utf-8") as f:
        spec = json.load(f)
    state = load_state()
    if (state.get("db") or "mongo") != spec.get("db", "mongo"):
        raise SystemExit(f"{args.thresholds} is for db {spec.get('db')}, the stack runs {state.get('db')}")
    sc = spec["scenario"]
    a = argparse.Namespace(scenario="steady", vus=sc["vus"], turns=sc["turns"], delay_ms=sc["delay_ms"], think_ms=0,
                           ramp="30s", hold="60s", agent=None, label=args.label, k6_timeout=1800,
                           warmup_turns=sc.get("warmup_turns", 0), no_wait=args.no_wait)
    r = cmd_run(a, name=f"{args.label}-gate-{sc['vus']}vu-{sc['turns']}t", kind="gate")
    ok = r["turns_ok"]
    measured = r["db_ops_per_turn"]
    per_collection = {c: {k: round(v / ok, 2) for k, v in d.items()} for c, d in r["db"]["detail"].items()} if ok else {}
    expected_turns = sc["vus"] * sc["turns"]
    failures, lines = [], ["| Counter | Per turn | Ceiling | |", "|---|---|---|---|"]
    if ok != expected_turns or any(r["errors"].values()):
        failures.append(f"{ok:.0f} of {expected_turns} turns succeeded; errors {r['errors']}")

    headroom = spec.get("headroom", {})

    def room(op):
        return headroom.get(op, headroom.get("default", 0.3))

    recorded = spec.get("measured", {})

    def check(label, op, got, ceiling, baseline):
        # The hint compares with what was measured when the ceiling was recorded, not with the
        # ceiling: the headroom is deliberate and differs per counter.
        if baseline is None:
            baseline = ceiling - room(op)
        verdict = "ok"
        if got > ceiling:
            verdict = "**over**"
            failures.append(f"{label}: {got:g} per turn > ceiling {ceiling:g}")
        elif baseline - got > spec.get("lower_hint", 1.0):
            verdict = "well below: consider lowering the ceiling"
        lines.append(f"| {label} | {got:g} | {ceiling:g} | {verdict} |")

    for op, ceiling in spec.get("max_ops_per_turn", {}).items():
        check(f"`{op}` (all)", op, measured.get(op, 0.0), ceiling, recorded.get("ops_per_turn", {}).get(op))
    for c, ops in spec.get("max_collection_ops_per_turn", {}).items():
        for op, ceiling in ops.items():
            check(f"`{c}` {op}", op, per_collection.get(c, {}).get(op, 0.0), ceiling,
                  recorded.get("per_collection", {}).get(c, {}).get(op))
    gated = set(spec.get("max_collection_ops_per_turn", {}))
    report_only = [f"`{c}` " + ", ".join(f"{k} {v:g}" for k, v in d.items())
                   for c, d in sorted(per_collection.items()) if c not in gated]
    head = "FAIL" if failures else "PASS"
    summary = [f"## Database operations per turn: {head}", "",
               f"Scenario: steady, {sc['vus']} conversations x {sc['turns']} turns after {sc.get('warmup_turns', 0)} warm-up turns each, "
               + f"mock LLM delay {sc['delay_ms']} ms, {r['db_kind']}. Ceilings: `{os.path.relpath(args.thresholds, REPO).replace(os.sep, '/')}`.", "",
               *lines, "",
               "Not gated (clock-driven): " + ("; ".join(report_only) or "none") + ".", "",
               f"All counters per turn: {ops_cell(measured)}.", "",
               f"Not gated (shared-runner wall clock): {r['turns_per_s'] or 0:.1f} turns/s, p50 {fmt(r['latency_ms']['med'])} ms, "
               + f"p95 {fmt(r['latency_ms']['p(95)'])} ms, EDDI overhead p50 {fmt(r['overhead_ms']['med'])} ms, "
               + f"p95 {fmt(r['overhead_ms']['p(95)'])} ms, run {r['elapsed_s']} s.", ""]
    if failures:
        summary += ["Failures:", ""] + [f"- {f}" for f in failures] + [""]
    text = "\n".join(summary)
    with open(os.path.join(RESULTS, "gate-summary.md"), "w", encoding="utf-8") as f:
        f.write(text)
    print(text)
    if args.record:
        def ceil(op, v):
            return math.ceil((v + room(op)) * 10) / 10
        spec["max_ops_per_turn"] = {op: ceil(op, measured.get(op, 0.0)) for op in spec.get("max_ops_per_turn", {})}
        spec["max_collection_ops_per_turn"] = {c: {op: ceil(op, per_collection.get(c, {}).get(op, 0.0)) for op in ops}
                                               for c, ops in spec.get("max_collection_ops_per_turn", {}).items()}
        spec["measured"] = {"ops_per_turn": measured, "per_collection": {c: per_collection.get(c, {}) for c in gated},
                            "commit": (r.get("env") or {}).get("build", {}).get("commit"), "date": r["date"]}
        with open(args.thresholds, "w", encoding="utf-8", newline="\n") as f:
            json.dump(spec, f, indent=2)
            f.write("\n")
        print(f"recorded new ceilings in {args.thresholds}")
    elif failures:
        sys.exit(1)


# ------------------------------------------------------------------ report

def load_results(label):
    out = []
    for fn in sorted(os.listdir(RESULTS)):
        if not fn.endswith(".json") or fn.startswith(".") or fn.count(".") > 1:
            continue  # raw k6/driver summaries are <name>.<what>.json
        try:
            with open(os.path.join(RESULTS, fn), encoding="utf-8") as f:
                r = json.load(f)
        except (OSError, ValueError):
            continue
        if r.get("label") == label and r.get("kind"):
            if not r.get("finished"):  # written before results carried it
                r["finished"] = datetime.datetime.fromtimestamp(os.path.getmtime(os.path.join(RESULTS, fn)),
                                                                datetime.timezone.utc).isoformat(timespec="seconds")
            out.append(r)
    return out


def ms(d, k):
    return fmt((d or {}).get(k))


def scenario_cell(r):
    return r["scenario"] + (f" (retry {r['retry_503']})" if r.get("retry_503") else "")


def flag(r):
    """A marker on a row measured while another stack's load test ran on the same machine."""
    return " ⚠" if r.get("contended_by") else ""


def cmd_report(args):
    results = load_results(args.label)
    if not results:
        raise SystemExit(f"no results with label {args.label!r} in {RESULTS}")
    by = {}
    for r in results:
        by.setdefault(r["kind"], []).append(r)
    env = next((r["env"] for r in results if r.get("env")), {}) or {}
    d, e, b = env.get("docker", {}), env.get("eddi", {}), env.get("build", {})
    date = max(r.get("date", "") for r in results)
    L = [f"# EDDI performance benchmark — {args.label} — {date}", ""]
    if args.laptop:
        L += ["> **Laptop run — not publishable.** These numbers come from a developer laptop shared with other",
              "> workloads (other load tests, IDEs, a browser). They show the method and the shape of the results;",
              "> absolute values belong to dedicated hardware.", ""]
    L += ["## Environment", "",
          "| | |", "|---|---|",
          f"| CPU | {env.get('cpu_model', '?')} — {d.get('cpus', '?')} logical CPUs visible to Docker |",
          f"| Memory | {d.get('memory_gib', '?')} GiB visible to Docker |",
          f"| Host | {env.get('host', {}).get('os', '?')} · Docker {d.get('server', '?')} on {d.get('os', '?')} "
          + f"(kernel {d.get('kernel', '?')}) |",
          f"| EDDI | `{e.get('image', '?')}`" + (f" with the local build of `{b.get('branch')}` @ `{(b.get('commit') or '?')[:12]}`"
                                               f"{' (uncommitted changes)' if b.get('dirty') else ''} mounted" if e.get("local_build") else "") + " |",
          f"| JVM | {env.get('java', '?')} |"]
    topologies = []
    for r in sorted(results, key=lambda r: r.get("finished", "")):
        t = (r.get("env") or {}).get("eddi") or {}
        line = (f"| Stack | {t.get('nodes', 1)} node(s), messaging {t.get('messaging', '?')}, database {t.get('db', '?')}; "
                f"`{t.get('java_opts', '?')}`, container limit {t.get('memory_limit', '?')}, CPUs per node: {t.get('cpus_per_node', '?')} |")
        if t and line not in topologies:
            topologies.append(line)
    L += topologies + [""]
    images = {}
    for r in results:
        for img, dig in ((r.get("env") or {}).get("images") or {}).items():
            if dig != "?" or img not in images:
                images[img] = dig
    if images:
        L += ["Images (digests as pulled):", ""] + [f"- `{img}` `{dig}`" for img, dig in sorted(images.items())] + [""]
    L += ["## Method", "",
          "- **The LLM is a mock** (WireMock, OpenAI wire format) with a fixed, configured latency, so every millisecond above it is "
          + "EDDI's: HTTP, the lifecycle pipeline, conversation load and save, audit. No model time is hidden in the numbers.",
          "- **Load generators run inside the Docker network** next to EDDI: k6 for turns and group discussions, a standard-library "
          + "asyncio SSE client (`scripts/loadtest/drivers/stream_driver.py`) for streaming, because k6 has no SSE client.",
          "- **Stateful conversations:** every virtual user owns one conversation and sends its turns back to back, so the "
          + "conversation's memory is loaded and stored on every turn, as in production.",
          "- **EDDI overhead** = turn latency minus the mock's configured delay (one LLM call per turn). For streaming, overhead = "
          + "EDDI's time to first token minus the mock's own time to first token, measured by the same client at the same concurrency.",
          "- **Database operations per turn** are MongoDB `serverStatus().opcounters` (or PostgreSQL `pg_stat_statements` calls) over "
          + "the measured phase, divided by successful turns. They include conversation starts and background work; they do not "
          + "depend on hardware.", ""]
    for kind in ("turns", "gate"):
        rows = by.get(kind, [])
        if not rows:
            continue
        L += ["## Turns — stateful conversations" if kind == "turns" else "## Regression gate scenario", "",
              "| Scenario | Nodes | DB | Concurrent conv. | Mock delay ms | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | "
              + "EDDI overhead p50 / p95 / p99 ms | Errors | DB ops per turn |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for r in sorted(rows, key=lambda r: (r["scenario"], r["llm_delay_ms"], r["vus"])):
            errs = ", ".join(f"{k}={int(v)}" for k, v in r["errors"].items() if v) or "none"
            L.append(f"| {scenario_cell(r)}{flag(r)} | {r['nodes']} | {r['db_kind']} | {r['vus']} | {r['llm_delay_ms']} | {r['turns_ok']:.0f} | "
                     f"{r['turns_per_s'] or 0:.1f} | {ms(r['latency_ms'], 'med')} | {ms(r['latency_ms'], 'p(95)')} | "
                     f"{ms(r['latency_ms'], 'p(99)')} | {ms(r['overhead_ms'], 'med')} / {ms(r['overhead_ms'], 'p(95)')} / "
                     f"{ms(r['overhead_ms'], 'p(99)')} | {errs} | {ops_cell(r['db_ops_per_turn'])} |")
        L.append("")
        retried = [r for r in rows if r.get("retry_503")]
        if retried:
            L += ["Runs with a client that honours 503 + Retry-After (`--retry-503 N`): a refused turn waits Retry-After "
                  + "(capped, ±20 % jitter) and is resent, at most N attempts. Latency above is the accepted attempt; "
                  + "the user waited the total below. `turns_503` counts refused attempts, not lost turns.", "",
                  "| Scenario | Concurrent conv. | Max attempts | Retry-After cap ms | OK turns | Total p50 ms | Total p95 ms | "
                  + "Total p99 ms | Attempts avg / p99 / max | Abandoned |",
                  "|---|---|---|---|---|---|---|---|---|---|"]
            for r in sorted(retried, key=lambda r: (r["scenario"], r["vus"])):
                t, a = r["total_latency_ms"] or {}, r["attempts"] or {}
                L.append(f"| {r['scenario']}{flag(r)} | {r['vus']} | {r['retry_503']} | {r['retry_cap_ms']} | {r['turns_ok']:.0f} | "
                         f"{ms(t, 'med')} | {ms(t, 'p(95)')} | {ms(t, 'p(99)')} | {fmt(a.get('avg'), 2)} / {ms(a, 'p(99)')} / "
                         f"{ms(a, 'max')} | {r['errors'].get('turns_abandoned', 0):.0f} |")
            L.append("")
    for r in by.get("stream", []):
        m = r["mock"]["metrics"]
        L += [f"## Streaming — time to first token ({r['vus']} concurrent streams)", "",
              f"Mock: first token after {r['first_chunk_ms']} ms, then {r['tokens']} tokens {r['chunk_ms']} ms apart. "
              + f"{r['turns_per_vu']} measured turns per stream after {r['warmup_turns']} warm-up turn(s).", "",
              "| Path | OK turns | TTFT p50 ms | TTFT p95 ms | TTFT p99 ms | Overhead p50 / p95 / p99 ms | Last token p50 ms | "
              + "Token spread p50 ms | Tokens p50 | Errors |",
              "|---|---|---|---|---|---|---|---|---|---|",
              f"| mock alone (calibration) | {r['mock']['turns_ok']} | {ms(m['ttft_ms'], 'med')} | {ms(m['ttft_ms'], 'p(95)')} | "
              + f"{ms(m['ttft_ms'], 'p(99)')} | — | {ms(m['total_ms'], 'med')} | {ms(m['spread_ms'], 'med')} | "
              + f"{ms(m['tokens'], 'med')} | {r['mock']['errors'] or 'none'} |"]
        names = {"native": "EDDI SSE `POST /agents/{id}/stream`", "openai": "EDDI `POST /v1/chat/completions` stream=true"}
        for ep, res in r["endpoints"].items():
            x, o = res["metrics"], res["overhead_vs_mock_ms"]
            L.append(f"| {names.get(ep, ep)}{flag(res)} | {res['turns_ok']} | {ms(x.get('ttft_ms'), 'med')} | {ms(x.get('ttft_ms'), 'p(95)')} | "
                     f"{ms(x.get('ttft_ms'), 'p(99)')} | {ms(o, 'med')} / {ms(o, 'p(95)')} / {ms(o, 'p(99)')} | "
                     f"{ms(x.get('total_ms'), 'med')} | {ms(x.get('spread_ms'), 'med')} | {ms(x.get('tokens'), 'med')} | "
                     f"{res['errors'] or 'none'} |")
        L += ["", "A token spread close to the mock's means EDDI forwards tokens as they arrive; near 0 would mean it buffered "
              + "the answer.", ""]
    rows = by.get("group", [])
    if rows:
        L += ["## Multi-agent group discussions", "",
              "Each discussion: every member speaks once per round, then the moderator synthesises "
              + "(docs/group-conversations.md). A member turn is one full pipeline run of that member's agent.", "",
              "| Style | Members | Rounds | Concurrent discussions | Mock delay ms | Discussions OK | Member turns | Member turns/s | "
              + "Turns per discussion | Discussion p50 ms | Discussion p95 ms | Failed | DB ops per member turn |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for r in sorted(rows, key=lambda r: (r["members"], r["vus"])):
            L.append(f"| {r['style']}{flag(r)} | {r['members']} | {r['rounds']} | {r['vus']} | {r['llm_delay_ms']} | {r['discussions_ok']:.0f} | "
                     f"{r['member_turns']:.0f} | {r['member_turns_per_s']:.1f} | {fmt(r['turns_per_discussion'], 1)} | "
                     f"{ms(r['discussion_ms'], 'med')} | {ms(r['discussion_ms'], 'p(95)')} | "
                     f"{r['discussions_failed'] + r['http_errors']:.0f} | {ops_cell(r['db_ops_per_member_turn'])} |")
        L.append("")
    contended = [r for r in results if r.get("contended_by")] + \
        [res for r in by.get("stream", []) for res in r["endpoints"].values() if res.get("contended_by")]
    if contended:
        L += ["⚠ Measured while another load test ran on the same machine "
              + f"({', '.join(sorted({c for r in contended for c in r['contended_by']}))}): those rows share the CPU with it.", ""]
    L += ["## Reproduce", "", "From the repository root, with Docker and Python 3.10+:", "", "```bash"]
    overrides = next((r.get("env_overrides") for r in results if r.get("env_overrides")), {})
    for k, v in overrides.items():
        L.append(f"export {k}={shlex.quote(v)}")
    L.append("./mvnw package -DskipTests -DskipUi=true")
    seen, stack = [], None
    # In the order they ran; each topology's `up` before the commands measured on it.
    for r in sorted(results, key=lambda r: r.get("finished") or r.get("date", "")):
        # A command run with other settings than the first gets them in front of it.
        own = r.get("env_overrides") or {}
        prefix = " ".join(f"{k}={shlex.quote(v)}" for k, v in own.items() if overrides.get(k) != v)
        up = f"{prefix} {r.get('up_command') or ''}".strip()
        # Same command, new stack: `collected` is stamped by each `up`.
        key = (up, (r.get("env") or {}).get("collected"))
        if key != stack:
            if stack is not None:
                seen.append("python scripts/loadtest/loadtest.py down")
            seen.append(up)
            stack = key
        line = f"{prefix} {r.get('command')}".strip()
        if line not in seen:
            seen.append(line)
    L += seen + ["python scripts/loadtest/loadtest.py down", "```", "",
                 f"Raw results: `scripts/loadtest/results/{args.label}-*.json` (not committed). "
                 + f"Report: `{command_line()}`.", ""]
    out = args.out or os.path.join(RESULTS, f"{args.label}-report.md")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(L))
    print(f"wrote {out}")


# ------------------------------------------------------------------ CLI
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
        agent = setup_agent("loadtest")[0]
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
                    continue  # a row without a number (a container starting or stopping): skip that container
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
            # A node that did not answer with JSON is left out; the run still reports the others.
            print(f"nats-{i}: /varz returned no JSON; its message counts are left out", file=sys.stderr)
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
             (f"Scenario `steady`, {first['vus_per_node']} conversations per node, {first['turns_per_vu']} turns each (after a "
             + f"{first.get('warmup_turns', 0)}-turn warm-up), mock LLM delay {first['llm_delay_ms']} ms. Per node: "
             + f"`eddi.conversation.max-in-flight-turns={env.get('LOADTEST_MAX_IN_FLIGHT')}`, MongoDB `maxPoolSize={env.get('LOADTEST_MONGO_POOL')}`, "
             + f"`{env.get('LOADTEST_JAVA_OPTS')}`, {env.get('LOADTEST_EDDI_CPUS')} CPUs, {env.get('LOADTEST_EDDI_MEMORY')}. "
             + f"MongoDB {env.get('LOADTEST_MONGO_CPUS')} CPUs, NATS 3 x {env.get('LOADTEST_NATS_CPUS')} CPU (R3). "
             + f"Docker host: {ncpu} CPUs. Image `{first.get('image') or 'labsai/eddi:6.5.0'}`"
             + f"{' with the local build mounted' if first.get('app') else ''}; commit `{git_head()}`."), "",
             ("| Run | Nodes | Messaging | Conv. | OK turns | Turns/s | Efficiency | p50 ms | p95 ms | p99 ms | 409 | 503 | other err. | "
             + "Lease acquire: count · mean ms | Fence rejected | Mongo ops/turn | Mongo ops/s | Mongo CPU % mean/peak | "
             + "NATS CPU % mean/peak (3 nodes) | NATS msgs/turn | EDDI CPU % mean per node | All containers CPU % mean (other stacks) | "
             + "Host CPU % mean · time at >= 95 % |"),
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
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
        lines.append(f"| {r['label']} | {r['nodes']} | {r['messaging']} | {r['vus']} | {r['turns_ok']:.0f} | {r['turns_per_s'] or 0:.1f} | {eff} | "
                     f"{fmt(lat['med'])} | {fmt(lat['p(95)'])} | {fmt(lat['p(99)'])} | {e['turns_409']:.0f} | {e['turns_503']:.0f} | "
                     f"{other:.0f} | {lease_txt} | {sum(p['fence_rejected'] for p in r['per_node']):.0f} | "
                     f"{sum(r['mongo_ops_per_turn'].values()):.1f} | {r['mongo_ops_per_s'] or 0:.0f} | {mc:.0f}/{mp:.0f} | "
                     f"{nc:.0f}/{np_:.0f} | {'-' if nm is None or r['messaging'] != 'nats' else f'{nm:.1f}'} | "
                     f"{ec / en if en else 0:.0f} | {r['docker']['total_mean']:.0f} ({r['docker']['foreign_mean']:.0f}) | {host_txt(r)} |")
    lines += ["", "## Per node", ""]
    for r in results:
        lines += [f"**{r['nodes']} x {r['messaging']}** (`{r['label']}`)", "",
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
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # a Windows console is cp1252
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    up = sub.add_parser("up")
    up.add_argument("--app", help="local build to mount (default: target/quarkus-app of this checkout)")
    up.add_argument("--no-app", action="store_true", help="run the image as published, without a local build")
    up.add_argument("--image", default=None, help=f"runtime image (default {DEFAULT_IMAGE})")
    up.add_argument("--db", default="mongo", choices=["mongo", "postgres"])
    sub.add_parser("down")

    def common(p, delay=3000):
        p.add_argument("--label", default="run")
        p.add_argument("--delay-ms", type=int, default=delay, help="mock LLM latency")
        p.add_argument("--k6-timeout", type=int, default=7200, help="seconds a load phase may take before the driver gives up")
        p.add_argument("--no-wait", action="store_true", help="do not wait for other load tests on this machine to finish")

    for name in ["run", "matrix"]:
        p = sub.add_parser(name)
        common(p, 3000)
        p.add_argument("--scenario", default="steady", choices=["steady", "burst", "same-conversation", "long-conversation"])
        p.add_argument("--vus", default={"run": "50", "matrix": "50,200,500"}[name])
        p.add_argument("--turns", type=int, default=None, help="turns per conversation (default 10; long-conversation 200)")
        p.add_argument("--think-ms", type=int, default=0)
        p.add_argument("--ramp", default="30s")
        p.add_argument("--hold", default="60s")
        p.add_argument("--agent", help="agent id (default: the one `up` created)")
        p.add_argument("--retry-503", type=int, default=0, metavar="N",
                       help="honour 503 + Retry-After: back off and resend the turn, at most N attempts (default 0: off)")
        p.add_argument("--retry-cap-ms", type=int, default=10000, help="longest Retry-After the client waits")
        p.add_argument("--warmup-turns", type=int, default=0, help="turns per conversation run first and not recorded")
    st = sub.add_parser("stream")
    common(st, 0)
    st.add_argument("--vus", type=int, default=50)
    st.add_argument("--turns", type=int, default=5, help="measured turns per stream")
    st.add_argument("--warmup-turns", type=int, default=1, help="turns per stream run first and not recorded")
    st.add_argument("--first-chunk-ms", type=int, default=500, help="mock: delay before the first token")
    st.add_argument("--tokens", type=int, default=10, help="mock: tokens per answer")
    st.add_argument("--chunk-ms", type=int, default=20, help="mock: delay between tokens")
    st.add_argument("--endpoints", default="native,openai")
    gr = sub.add_parser("group")
    common(gr, 1000)
    gr.add_argument("--members", type=int, default=3)
    gr.add_argument("--rounds", type=int, default=1)
    gr.add_argument("--style", default="ROUND_TABLE")
    gr.add_argument("--vus", type=int, default=10, help="concurrent discussions")
    gr.add_argument("--discussions", type=int, default=3, help="discussions per VU")
    ga = sub.add_parser("gate")
    ga.add_argument("--thresholds", default=os.path.join(HERE, "thresholds", "db-ops-mongo.json"))
    ga.add_argument("--label", default="gate")
    ga.add_argument("--record", action="store_true", help="write the measured values (plus headroom) as the new ceilings")
    ga.add_argument("--no-wait", action="store_true")
    rp = sub.add_parser("report")
    rp.add_argument("--label", required=True)
    rp.add_argument("--out", help="output file (default results/<label>-report.md)")
    rp.add_argument("--laptop", action="store_true", help="mark the report as a non-publishable laptop run")
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
    {"up": cmd_up, "down": cmd_down, "run": cmd_run, "matrix": cmd_matrix, "stream": cmd_stream, "group": cmd_group,
     "gate": cmd_gate, "report": cmd_report, "cluster-up": cmd_cluster_up, "cluster-down": cmd_cluster_down,
     "cluster-run": cmd_cluster_run, "cluster-matrix": cmd_cluster_matrix, "cluster-report": cluster_report_from_files}[args.cmd](args)


if __name__ == "__main__":
    main()
