"""Load-test and benchmark driver for EDDI (planning/throughput-optimization-plan.md, WS0).

    python scripts/loadtest/loadtest.py up [--db mongo|postgres] [--nodes N] [--app DIR | --no-app] [--image IMG]
    python scripts/loadtest/loadtest.py run --scenario steady --vus 200 --turns 10 --delay-ms 3000 [--label baseline]
    python scripts/loadtest/loadtest.py matrix --vus 50,200,500 --delay-ms 3000 [--label baseline]
    python scripts/loadtest/loadtest.py stream --vus 50 --turns 5 --first-chunk-ms 500 [--endpoints native,openai]
    python scripts/loadtest/loadtest.py group --members 3 --rounds 1 --vus 10 --discussions 3 --delay-ms 1000
    python scripts/loadtest/loadtest.py scaling --nodes 1,2,4 --vus 300 --turns 20 --delay-ms 0
    python scripts/loadtest/loadtest.py gate --thresholds scripts/loadtest/thresholds/db-ops-mongo.json
    python scripts/loadtest/loadtest.py report --label baseline [--laptop]
    python scripts/loadtest/loadtest.py down

`up` starts the database, the WireMock LLM and EDDI (with --nodes: N EDDI nodes on NATS behind
nginx) and creates one deployed LLM agent; what it started is kept in results/.state.json. Every
measuring command snapshots /q/metrics and the database's operation counters, drives the load
(k6 for turns and group discussions, drivers/stream_driver.py for SSE), samples the saturation
gauges every second, snapshots again and writes results/<label>-<kind>-….json. `report` renders
every result of a label as one Markdown benchmark page. See README.md. Standard library only.
"""
import argparse
import copy
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
NGINX_CONF = os.path.join(RESULTS, ".nginx.conf")
CLUSTER_OVERLAY = os.path.join(RESULTS, ".cluster-nodes.json")
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "eddi-loadtest")
EDDI_PORT = int(os.environ.get("LOADTEST_EDDI_PORT", "7370"))
EDDI = f"http://127.0.0.1:{EDDI_PORT}"
WIREMOCK = "http://127.0.0.1:" + os.environ.get("LOADTEST_WIREMOCK_PORT", "18370")
LB = "http://127.0.0.1:" + os.environ.get("LOADTEST_LB_PORT", "7380")
DEFAULT_IMAGE = "labsai/eddi:6.5.0"
# The /v1 key set in docker-compose.loadtest.yml.
OPENAI_KEY = "sk-loadtest-openai-compat-0123456789"
# The streaming stub's id, so that every `stream` run replaces it instead of adding another.
STREAM_STUB_ID = "5f0c7c1e-57ea-4000-8000-000000000001"
# Load generators of any stack on this machine; a measurement waits while one of them runs.
LOAD_IMAGES = ("grafana/k6", "python:3.12.7-alpine3.20")

# Gauges sampled during a run; the report shows the peak of every series (per node, max over nodes).
SAMPLED = re.compile(r"^(eddi_runtime_executor_(active|queued|max_threads)|eddi_processing_conversation_count|"
                     r"eddi_coordinator_(queue_depth|active_conversations)|eddi_audit_queue_(depth|capacity)|"
                     r"jvm_threads_(live|peak)_threads|mongodb_driver_pool_(size|checkedout|waitqueuesize))")
# Counters compared before/after a run (summed over nodes).
COUNTED = re.compile(r"^(eddi_audit_entries_dropped_total|eddi_turn_rejected_total|eddi_coordinator_dead_lettered_total|eddi_turn_idempotency_total|"
                     r"eddi_conversation_processing_duration_seconds_(count|sum)|eddi_llm_circuit[a-z_]*_total|"
                     r"http_server_requests_seconds_count)")
TURN_ERRORS = ["turns_503", "turns_429", "turns_408", "turns_409", "turns_5xx_other", "turns_other", "turns_no_response",
               "turns_app_error", "start_failed", "turns_abandoned"]
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
    files = ["-f", os.path.join(HERE, "docker-compose.loadtest.yml"),
             "-f", os.path.join(HERE, f"docker-compose.{state.get('db') or 'mongo'}.yml")]
    if state.get("app"):
        files += ["-f", os.path.join(HERE, "docker-compose.app.yml")]
    if state.get("nodes"):
        files += ["-f", os.path.join(HERE, "docker-compose.cluster.yml")]
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
    if state.get("cpus"):
        env["LOADTEST_EDDI_CPUS"] = str(state["cpus"])
    if state.get("nodes"):
        env["LOADTEST_NGINX_CONF"] = NGINX_CONF
    return env


def compose(*cmd, check=True, capture=True, timeout=COMMAND_TIMEOUT_S, on_timeout=None, overlay=True):
    state = load_state()
    files = compose_files(state)
    if overlay and state.get("nodes") and os.path.exists(CLUSTER_OVERLAY):
        files += ["-f", CLUSTER_OVERLAY]
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
    """Host URLs of the EDDI nodes: node 1 on LOADTEST_EDDI_PORT, node i on the port i - 1 above it."""
    n = (state or load_state()).get("nodes") or 1
    return [f"http://127.0.0.1:{EDDI_PORT + i}" for i in range(n)]


def client_base(state=None):
    """Where the load generators, inside the compose network, send their requests."""
    return "http://lb:8080" if (state or load_state()).get("nodes") else "http://eddi:7070"


def topology_suffix(state):
    """Part of a result's file name, so that runs on another database or node count do not overwrite it."""
    return ("-pg" if state.get("db") == "postgres" else "") + (f"-{state['nodes']}n" if state.get("nodes") else "")


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
    state = {"app": None if args.no_app else args.app, "image": args.image, "db": args.db, "nodes": args.nodes,
             "cpus": args.cpus_per_node, "up_command": command_line(), "up_env": env_overrides()}
    save_state(state)
    bring_up(args.nodes)
    state = load_state()
    state["agent"], state["agent_name"] = setup_agent("loadtest")
    save_state(state)
    if state.get("nodes"):
        wait_deployed_everywhere(state["agent"])
    install_stream_stub(500, 10, 20)
    # Pulled now so that the first measurement does not include a pull, and so that their digests are known.
    compose("--profile", "k6", "--profile", "stream", "pull", "-q", "k6", "stream", check=False)
    state["env"] = collect_env()
    save_state(state)
    where = f"nginx {LB} over {state['nodes']} node(s)" if state.get("nodes") else f"EDDI {EDDI}"
    print(f"stack up ({state['db']}): {where}, agent {state['agent']}")


def bring_up(nodes):
    """Start (or resize to) the topology in the state file and wait until every node is ready."""
    state = load_state()
    services = [{"mongo": "mongodb", "postgres": "postgres"}[state.get("db") or "mongo"], "wiremock", "eddi"]
    if nodes:
        write_cluster_files(nodes)
        services += ["nats"] + [f"eddi-{i}" for i in range(2, nodes + 1)]
    compose("up", "-d", "--remove-orphans", *services)
    for i, url in enumerate(node_urls()):
        if not wait_until(lambda: http("GET", url + "/q/health/ready", timeout=5)[0] == 200, 300, 2):
            print(compose("logs", "--tail", "80", "eddi" if i == 0 else f"eddi-{i + 1}", check=False).stdout)
            raise SystemExit(f"EDDI node {i + 1} did not become ready")
    if state.get("db") == "postgres":
        # In the maintenance database, so that the counters' own queries are not counted against eddi.
        psql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
    if nodes:
        # Recreated every time: the upstream list in the mounted config changes with the node count.
        compose("up", "-d", "--force-recreate", "lb")
        if not wait_until(lambda: http("GET", LB + "/q/health/ready", timeout=5)[0] == 200, 60, 1):
            print(compose("logs", "--tail", "40", "lb", check=False).stdout)
            raise SystemExit("nginx did not become ready")


def write_cluster_files(nodes):
    """nginx config for N nodes, and nodes 2..N as copies of the fully merged `eddi` service."""
    ensure_results()
    servers = "\n".join(f"        server {'eddi' if i == 1 else f'eddi-{i}'}:7070 resolve max_fails=0;" for i in range(1, nodes + 1))
    with open(NGINX_CONF, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"""# Written by loadtest.py up --nodes {nodes}: round robin over the nodes, no affinity (after
# scripts/cluster-demo/nginx-demo.conf). max_fails=0: a 503 must not take a node out of rotation.
worker_processes auto;
worker_rlimit_nofile 65536;
events {{
    worker_connections 16384;
}}
http {{
    access_log off;
    resolver 127.0.0.11 valid=5s ipv6=off;
    upstream eddi {{
        zone eddi 64k;
{servers}
        keepalive 512;
    }}
    server {{
        listen 8080;
        client_max_body_size 32m;
        location / {{
            proxy_pass http://eddi;
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header Connection "";
            proxy_buffering off;
            proxy_read_timeout 600s;
            proxy_connect_timeout 2s;
            proxy_next_upstream error;
        }}
    }}
}}
""")
    if os.path.exists(CLUSTER_OVERLAY):
        os.remove(CLUSTER_OVERLAY)
    r = compose("config", "--format", "json", overlay=False)
    eddi = json.loads(r.stdout)["services"]["eddi"]
    services = {}
    for i in range(2, nodes + 1):
        svc = copy.deepcopy(eddi)
        svc.pop("container_name", None)
        env = svc.setdefault("environment", {})
        env["EDDI_CLUSTER_NODE_ID"] = f"n{i}"
        env["EDDI_SCHEDULE_INSTANCE_ID"] = f"eddi-{i}"
        for p in svc.get("ports", []):
            p["published"] = str(EDDI_PORT + i - 1)
        services[f"eddi-{i}"] = svc
    with open(CLUSTER_OVERLAY, "w", encoding="utf-8") as f:
        json.dump({"services": services}, f, indent=2)


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


def wait_deployed_everywhere(agent):
    def ready(url):
        s, _, b = http("GET", f"{url}/administration/production/deploymentstatus/{agent}?version=1", timeout=10)
        return s == 200 and json.loads(b).get("status") == "READY"
    for url in node_urls():
        if not wait_until(lambda: ready(url), 120, 1):
            # A node that came up after the deployment did not take it over: deploy it there directly.
            http("POST", f"{url}/administration/production/{agent}?version=1&autoDeploy=true", timeout=60)
            if not wait_until(lambda: ready(url), 120, 1):
                raise SystemExit(f"agent {agent} is not deployed on {url}")


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
                   "nodes": state.get("nodes") or 1, "messaging": "nats" if state.get("nodes") else "in-memory",
                   "java_opts": os.environ.get("LOADTEST_JAVA_OPTS", "-Xmx2g"),
                   "memory_limit": os.environ.get("LOADTEST_EDDI_MEMORY", "3g"),
                   "cpus_per_node": state.get("cpus") or os.environ.get("LOADTEST_EDDI_CPUS") or "unlimited"}
    env["collected"] = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    return env


# ------------------------------------------------------------------ measurements

def scrape(base):
    s, _, body = http("GET", base + "/q/metrics", timeout=10, headers={"Accept": "text/plain"})
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


class Measurement:
    """Snapshots around one load phase: metrics of every node, database counters, gauge peaks."""

    def __enter__(self):
        self.m_before, self.db_before = scrape_all(), db_snapshot()
        self.sampler = Sampler()
        self.sampler.start()
        self.t0 = time.time()
        return self

    def __exit__(self, *exc):
        self.elapsed = time.time() - self.t0
        self.sampler.stop.set()
        self.sampler.join(5)
        time.sleep(2)  # let the last turns' persistence settle before counting database ops
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
    """Names of load-generator containers of other stacks on this machine."""
    r = sh("docker", "ps", "--format", "{{.Names}}\t{{.Image}}", check=False, timeout=60)
    return [line.split("\t")[0] for line in r.stdout.splitlines() if "\t" in line
            and line.split("\t")[1].startswith(LOAD_IMAGES) and not line.startswith(PROJECT + "-")]


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
    lat, ovh = r["latency_ms"], r["overhead_ms"]
    errs = ", ".join(f"{k[6:] if k.startswith('turns_') else k}={int(v)}" for k, v in r["errors"].items() if v)
    return (f"{r['name']}: {r['turns_ok']:.0f} ok, {r['turns_per_s'] or 0:.1f} turns/s, p50 {fmt(lat['med'])} p95 {fmt(lat['p(95)'])} "
            f"p99 {fmt(lat['p(99)'])} ms; EDDI overhead p50 {fmt(ovh['med'])} p95 {fmt(ovh['p(95)'])} p99 {fmt(ovh['p(99)'])} ms; "
            f"db ops/turn {r['db_ops_per_turn'].get('total', '-')}; errors: {errs or 'none'}"
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
             f"think time {args.think_ms} ms. Image `{load_state().get('image') or DEFAULT_IMAGE}`"
             f"{' with the local build mounted' if load_state().get('app') else ''}; commit `{git_head()}`.", "",
             "| Concurrent conv. | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | Overhead p50/p95/p99 ms | 503 | 408 | 5xx other | "
             "no response | 200 with TurnError | Peak managed active | Peak managed queued | Peak in-flight turns | Peak JVM threads | "
             "DB ops/turn |",
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
    if state.get("nodes"):
        for a in agents:
            wait_deployed_everywhere(a)
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


# ------------------------------------------------------------------ cluster scaling

def cmd_scaling(args):
    state = load_state()
    if not state.get("nodes"):
        raise SystemExit("the scaling scenario needs the cluster topology — run `up --nodes 1` (or any N) first")
    counts = [int(n) for n in args.nodes.split(",")]
    runs = []
    for n in counts:
        state = load_state()
        state["nodes"] = n
        save_state(state)
        print(f"--- {n} node(s)", flush=True)
        bring_up(n)
        wait_deployed_everywhere(state["agent"])
        a = argparse.Namespace(**vars(args))
        a.scenario = "steady"
        runs.append(cmd_run(a, name=f"{args.label}-scaling-{n}n-{args.vus}vu-{args.delay_ms}ms", kind="scaling-step"))
    base = runs[0]["turns_per_s"] or 0
    rows = []
    for n, r in zip(counts, runs):
        tps = r["turns_per_s"] or 0
        rows.append({"nodes": n, "turns_per_s": tps, "speedup": tps / base if base else None,
                     "efficiency": tps / (base * n / counts[0]) if base else None, "run": r["name"]})
    result = write_result({"kind": "scaling", "name": f"{args.label}-scaling-{args.vus}vu-{args.delay_ms}ms", "label": args.label,
                           "vus": args.vus, "turns_per_vu": args.turns, "warmup_turns": args.warmup_turns,
                           "llm_delay_ms": args.delay_ms, "cpus_per_node": state.get("cpus"), "rows": rows})
    for row in result["rows"]:
        print(f"{row['nodes']} node(s): {row['turns_per_s']:.1f} turns/s, speedup {fmt(row['speedup'], 2)}, "
              f"efficiency {fmt(row['efficiency'] * 100 if row['efficiency'] is not None else None)} %")


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
               f"mock LLM delay {sc['delay_ms']} ms, {r['db_kind']}. Ceilings: `{os.path.relpath(args.thresholds, REPO).replace(os.sep, '/')}`.", "",
               *lines, "",
               "Not gated (clock-driven): " + ("; ".join(report_only) or "none") + ".", "",
               f"All counters per turn: {ops_cell(measured)}.", "",
               f"Not gated (shared-runner wall clock): {r['turns_per_s'] or 0:.1f} turns/s, p50 {fmt(r['latency_ms']['med'])} ms, "
               f"p95 {fmt(r['latency_ms']['p(95)'])} ms, EDDI overhead p50 {fmt(r['overhead_ms']['med'])} ms, "
               f"p95 {fmt(r['overhead_ms']['p(95)'])} ms, run {r['elapsed_s']} s.", ""]
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
          f"(kernel {d.get('kernel', '?')}) |",
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
          "EDDI's: HTTP, the lifecycle pipeline, conversation load and save, audit. No model time is hidden in the numbers.",
          "- **Load generators run inside the Docker network** next to EDDI: k6 for turns and group discussions, a standard-library "
          "asyncio SSE client (`scripts/loadtest/drivers/stream_driver.py`) for streaming, because k6 has no SSE client.",
          "- **Stateful conversations:** every virtual user owns one conversation and sends its turns back to back, so the "
          "conversation's memory is loaded and stored on every turn, as in production.",
          "- **EDDI overhead** = turn latency minus the mock's configured delay (one LLM call per turn). For streaming, overhead = "
          "EDDI's time to first token minus the mock's own time to first token, measured by the same client at the same concurrency.",
          "- **Database operations per turn** are MongoDB `serverStatus().opcounters` (or PostgreSQL `pg_stat_statements` calls) over "
          "the measured phase, divided by successful turns. They include conversation starts and background work; they do not "
          "depend on hardware.", ""]
    for kind in ("turns", "gate"):
        rows = by.get(kind, [])
        if not rows:
            continue
        L += ["## Turns — stateful conversations" if kind == "turns" else "## Regression gate scenario", "",
              "| Scenario | Nodes | DB | Concurrent conv. | Mock delay ms | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | "
              "EDDI overhead p50 / p95 / p99 ms | Errors | DB ops per turn |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for r in sorted(rows, key=lambda r: (r["scenario"], r["llm_delay_ms"], r["vus"])):
            errs = ", ".join(f"{k}={int(v)}" for k, v in r["errors"].items() if v) or "none"
            L.append(f"| {r['scenario']}{flag(r)} | {r['nodes']} | {r['db_kind']} | {r['vus']} | {r['llm_delay_ms']} | {r['turns_ok']:.0f} | "
                     f"{r['turns_per_s'] or 0:.1f} | {ms(r['latency_ms'], 'med')} | {ms(r['latency_ms'], 'p(95)')} | "
                     f"{ms(r['latency_ms'], 'p(99)')} | {ms(r['overhead_ms'], 'med')} / {ms(r['overhead_ms'], 'p(95)')} / "
                     f"{ms(r['overhead_ms'], 'p(99)')} | {errs} | {ops_cell(r['db_ops_per_turn'])} |")
        L.append("")
        retried = [r for r in rows if r.get("retry_503")]
        if retried:
            L += ["Runs with a client that honours 503 + Retry-After (`--retry-503 N`): a refused turn waits Retry-After "
                  "(capped, ±20 % jitter) and is resent, at most N attempts. Latency above is the accepted attempt; "
                  "the user waited the total below. `turns_503` counts refused attempts, not lost turns.", "",
                  "| Scenario | Concurrent conv. | Max attempts | Retry-After cap ms | OK turns | Total p50 ms | Total p95 ms | "
                  "Total p99 ms | Attempts avg / p99 / max | Abandoned |",
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
              f"{r['turns_per_vu']} measured turns per stream after {r['warmup_turns']} warm-up turn(s).", "",
              "| Path | OK turns | TTFT p50 ms | TTFT p95 ms | TTFT p99 ms | Overhead p50 / p95 / p99 ms | Last token p50 ms | "
              "Token spread p50 ms | Tokens p50 | Errors |",
              "|---|---|---|---|---|---|---|---|---|---|",
              f"| mock alone (calibration) | {r['mock']['turns_ok']} | {ms(m['ttft_ms'], 'med')} | {ms(m['ttft_ms'], 'p(95)')} | "
              f"{ms(m['ttft_ms'], 'p(99)')} | — | {ms(m['total_ms'], 'med')} | {ms(m['spread_ms'], 'med')} | "
              f"{ms(m['tokens'], 'med')} | {r['mock']['errors'] or 'none'} |"]
        names = {"native": "EDDI SSE `POST /agents/{id}/stream`", "openai": "EDDI `POST /v1/chat/completions` stream=true"}
        for ep, res in r["endpoints"].items():
            x, o = res["metrics"], res["overhead_vs_mock_ms"]
            L.append(f"| {names.get(ep, ep)}{flag(res)} | {res['turns_ok']} | {ms(x.get('ttft_ms'), 'med')} | {ms(x.get('ttft_ms'), 'p(95)')} | "
                     f"{ms(x.get('ttft_ms'), 'p(99)')} | {ms(o, 'med')} / {ms(o, 'p(95)')} / {ms(o, 'p(99)')} | "
                     f"{ms(x.get('total_ms'), 'med')} | {ms(x.get('spread_ms'), 'med')} | {ms(x.get('tokens'), 'med')} | "
                     f"{res['errors'] or 'none'} |")
        L += ["", "A token spread close to the mock's means EDDI forwards tokens as they arrive; near 0 would mean it buffered "
              "the answer.", ""]
    for r in by.get("scaling", []):
        L += [f"## Cluster scaling — {r['vus']} conversations, mock delay {r['llm_delay_ms']} ms", "",
              f"Nodes on NATS behind nginx round robin (no affinity), CPUs per node: {r.get('cpus_per_node') or 'unlimited'}. "
              "Efficiency = turns/s ÷ (nodes × single-node turns/s).", "",
              "| Nodes | Turns/s | Speedup | Efficiency | p50 ms | p95 ms | EDDI overhead p95 ms | Errors | DB ops per turn |",
              "|---|---|---|---|---|---|---|---|---|"]
        steps = {s["name"]: s for s in by.get("scaling-step", [])}
        for row in r["rows"]:
            s = steps.get(row["run"], {})
            errs = ", ".join(f"{k}={int(v)}" for k, v in s.get("errors", {}).items() if v) or "none"
            eff = row["efficiency"]
            L.append(f"| {row['nodes']}{flag(s)} | {row['turns_per_s']:.1f} | {fmt(row['speedup'], 2)}× | "
                     f"{fmt(eff * 100 if eff is not None else None)} % | {ms(s.get('latency_ms'), 'med')} | "
                     f"{ms(s.get('latency_ms'), 'p(95)')} | {ms(s.get('overhead_ms'), 'p(95)')} | {errs} | "
                     f"{ops_cell(s.get('db_ops_per_turn'))} |")
        L.append("")
    rows = by.get("group", [])
    if rows:
        L += ["## Multi-agent group discussions", "",
              "Each discussion: every member speaks once per round, then the moderator synthesises "
              "(docs/group-conversations.md). A member turn is one full pipeline run of that member's agent.", "",
              "| Style | Members | Rounds | Concurrent discussions | Mock delay ms | Discussions OK | Member turns | Member turns/s | "
              "Turns per discussion | Discussion p50 ms | Discussion p95 ms | Failed | DB ops per member turn |",
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
              f"({', '.join(sorted({c for r in contended for c in r['contended_by']}))}): those rows share the CPU with it.", ""]
    L += ["## Reproduce", "", "From the repository root, with Docker and Python 3.10+:", "", "```bash"]
    overrides = next((r.get("env_overrides") for r in results if r.get("env_overrides")), {})
    for k, v in overrides.items():
        L.append(f"export {k}={shlex.quote(v)}")
    L.append("./mvnw package -DskipTests -DskipUi=true")
    seen, stack = [], None
    # In the order they ran; each topology's `up` before the commands measured on it.
    for r in sorted(results, key=lambda r: r.get("finished") or r.get("date", "")):
        if r["kind"] == "scaling-step":
            continue  # `scaling` itself is listed
        # A command run with other settings than the first gets them in front of it.
        own = r.get("env_overrides") or {}
        prefix = " ".join(f"{k}={shlex.quote(v)}" for k, v in own.items() if overrides.get(k) != v)
        up = f"{prefix} {r.get('up_command') or ''}".strip()
        if up != stack:
            if stack is not None:
                seen.append("python scripts/loadtest/loadtest.py down")
            seen.append(up)
            stack = up
        line = f"{prefix} {r.get('command')}".strip()
        if line not in seen:
            seen.append(line)
    L += seen + ["python scripts/loadtest/loadtest.py down", "```", "",
                 f"Raw results: `scripts/loadtest/results/{args.label}-*.json` (not committed). "
                 f"Report: `{command_line()}`.", ""]
    out = args.out or os.path.join(RESULTS, f"{args.label}-report.md")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(L))
    print(f"wrote {out}")


# ------------------------------------------------------------------ CLI

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
    up.add_argument("--nodes", type=int, default=None, help="cluster topology: N EDDI nodes on NATS behind nginx")
    up.add_argument("--cpus-per-node", type=float, default=None, help="CPU limit per EDDI node (default: none)")
    sub.add_parser("down")

    def common(p, delay=3000):
        p.add_argument("--label", default="run")
        p.add_argument("--delay-ms", type=int, default=delay, help="mock LLM latency")
        p.add_argument("--k6-timeout", type=int, default=7200, help="seconds a load phase may take before the driver gives up")
        p.add_argument("--no-wait", action="store_true", help="do not wait for other load tests on this machine to finish")

    for name in ["run", "matrix", "scaling"]:
        p = sub.add_parser(name)
        common(p, 0 if name == "scaling" else 3000)
        if name != "scaling":
            p.add_argument("--scenario", default="steady", choices=["steady", "burst", "same-conversation", "long-conversation"])
        p.add_argument("--vus", default={"run": "50", "matrix": "50,200,500", "scaling": "300"}[name])
        p.add_argument("--turns", type=int, default=None, help="turns per conversation (default 10; long-conversation 200)")
        p.add_argument("--think-ms", type=int, default=0)
        p.add_argument("--ramp", default="30s")
        p.add_argument("--hold", default="60s")
        p.add_argument("--agent", help="agent id (default: the one `up` created)")
        p.add_argument("--retry-503", type=int, default=0, metavar="N",
                       help="honour 503 + Retry-After: back off and resend the turn, at most N attempts (default 0: off)")
        p.add_argument("--retry-cap-ms", type=int, default=10000, help="longest Retry-After the client waits")
        p.add_argument("--warmup-turns", type=int, default=3 if name == "scaling" else 0,
                       help="turns per conversation run first and not recorded")
        if name == "scaling":
            p.add_argument("--nodes", default="1,2,4")
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
    args = ap.parse_args()
    if args.cmd in ("run", "matrix", "scaling") and args.turns is None:
        args.turns = 200 if getattr(args, "scenario", "steady") == "long-conversation" else (20 if args.cmd == "scaling" else 10)
    if args.cmd in ("run", "scaling"):
        args.vus = int(args.vus)
    {"up": cmd_up, "down": cmd_down, "run": cmd_run, "matrix": cmd_matrix, "stream": cmd_stream, "group": cmd_group,
     "scaling": cmd_scaling, "gate": cmd_gate, "report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    main()
