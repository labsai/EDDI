"""EDDI cluster demo: three EDDI nodes, a three-node NATS JetStream cluster, a
database and an nginx round-robin load balancer, all in Docker; every failure
scenario of docs/clustering.md run against them, each with a PASS/FAIL verdict.

    python demo.py --app <path to target/quarkus-app> [--db mongo|postgres]
                   [--image labsai/eddi:6.5.0] [--only 1,2,3] [--baseline]

The EDDI nodes run the PUBLISHED image (labsai/eddi) with the locally built
`target/quarkus-app` mounted over /deployments, i.e. exactly one build of the
branch inside the shipped runtime. `--baseline` runs the stock image unchanged
(cluster mode does not exist there) for the before/after comparison.

Results go to results-<db>[-baseline].json next to this script, and the
summary to stdout. See README.md.
"""
import argparse
import collections
import concurrent.futures as cf
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
P = "p11"                                   # container name prefix
NET = f"{P}-net"
NODE_PORTS = [7211, 7212, 7213]
NATS_PORTS = [4310, 4311, 4312]
NATS_MON = [8310, 8311, 8312]
LB_PORT = 7230
MOCK_PORT = 18210
MONGO_PORT = 27310
PG_PORT = 54410
NATS_PASSWORD = "demo-nats-password-1234"
VAULT_KEY = "review-test-master-key-0123456789abcdef"
LB = f"http://127.0.0.1:{LB_PORT}"
NODES = [f"http://127.0.0.1:{p}" for p in NODE_PORTS]
RESULTS = collections.OrderedDict()
MOCK_LOG = os.path.join(HERE, "mock-requests.jsonl")


# ------------------------------------------------------------------ helpers

def sh(*cmd, check=False, quiet=True):
    r = subprocess.run(list(cmd), capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} -> {r.returncode}: {r.stderr.strip()}")
    if not quiet:
        print(r.stdout.strip())
    return r


def http(base, method, path, body=None, timeout=120, headers=None):
    data = None if body is None else json.dumps(body).encode()
    h = {"Content-Type": "application/json", "Accept": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(base + path, data=data, method=method, headers=h)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read().decode(errors="replace"), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode(errors="replace"), time.time() - t0
    except Exception as e:  # connection reset, timeout
        return -1, {}, str(e), time.time() - t0


def jl(text):
    try:
        return json.loads(text)
    except Exception:
        return {}


def record(sid, title, passed, **details):
    """passed=None: the scenario cannot be driven through the public API; details explain why."""
    RESULTS[sid] = {"title": title, "pass": None if passed is None else bool(passed), **details}
    verdict = "N/A " if passed is None else ("PASS" if passed else "FAIL")
    print(f"[{verdict}] {sid} {title} :: {json.dumps(details)[:600]}", flush=True)


def wait_until(fn, timeout, interval=0.5):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            v = fn()
            if v:
                return v
        except Exception:
            pass
        time.sleep(interval)
    return None


def mock_entries(tag=None, since=0.0):
    out = []
    if not os.path.exists(MOCK_LOG):
        return out
    for line in open(MOCK_LOG, encoding="utf-8"):
        try:
            e = json.loads(line)
        except Exception:
            continue
        if e["start"] >= since and (tag is None or e.get("tag") == tag):
            out.append(e)
    return out


def metric(base, name, label_filter=""):
    # text exposition: with Accept: application/json Quarkus answers in JSON
    s, _, b, _ = http(base, "GET", "/q/metrics", timeout=10, headers={"Accept": "text/plain"})
    total = 0.0
    for line in b.splitlines():
        if line.startswith(name) and label_filter in line and not line.startswith("#"):
            try:
                # OpenMetrics exemplars follow the value: "<series> <value> # {...} 1.0 <ts>"
                total += float(line.split(" # ")[0].rsplit(" ", 1)[1])
            except ValueError:
                pass
    return total


# ------------------------------------------------------------------ infrastructure

class Infra:
    def __init__(self, args):
        self.args = args
        self.db = args.db
        self.image = args.image
        self.mock = None

    def nats(self, i):
        return f"{P}-nats-{i + 1}"

    def node(self, i):
        return f"{P}-eddi-{i + 1}"

    def start_nats(self, i):
        routes = ",".join(f"nats://{self.nats(j)}:6222" for j in range(3) if j != i)
        sh("docker", "rm", "-f", self.nats(i))
        sh("docker", "run", "-d", "--name", self.nats(i), "--network", NET, "--memory", "256m",
           "-p", f"127.0.0.1:{NATS_PORTS[i]}:4222", "-p", f"127.0.0.1:{NATS_MON[i]}:8222",
           "nats:2.11-alpine", "--name", self.nats(i), "--jetstream", "--store_dir=/data", "-m", "8222",
           "--user", "eddi", "--pass", NATS_PASSWORD,
           "--cluster_name", "eddi", "--cluster", "nats://0.0.0.0:6222", "--routes", routes, check=True)

    def nats_ready(self):
        def ok():
            for i in range(3):
                s, _, b, _ = http(f"http://127.0.0.1:{NATS_MON[i]}", "GET", "/healthz?js-enabled-only=true", timeout=3)
                if s != 200:
                    return False
            return True
        return wait_until(ok, 60)

    def start_db(self):
        if self.db == "mongo":
            sh("docker", "rm", "-f", f"{P}-mongo")
            sh("docker", "run", "-d", "--name", f"{P}-mongo", "--network", NET, "--memory", "1g",
               "-p", f"127.0.0.1:{MONGO_PORT}:27017",
               "-e", "MONGO_INITDB_ROOT_USERNAME=eddi", "-e", "MONGO_INITDB_ROOT_PASSWORD=changeme", "mongo:7.0.14", check=True)
        else:
            sh("docker", "rm", "-f", f"{P}-postgres")
            sh("docker", "run", "-d", "--name", f"{P}-postgres", "--network", NET, "--memory", "1g",
               "-p", f"127.0.0.1:{PG_PORT}:5432",
               "-e", "POSTGRES_DB=eddi", "-e", "POSTGRES_USER=eddi", "-e", "POSTGRES_PASSWORD=eddi", "postgres:16-alpine", check=True)
        time.sleep(5)

    def node_env(self, i, cluster=True):
        env = {
            "EDDI_VAULT_MASTER_KEY": VAULT_KEY,
            "EDDI_SECURITY_ALLOW_UNAUTHENTICATED": "true",
            "EDDI_MCP_ALLOW_UNAUTHENTICATED": "true",
            "EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED": "true",
            "QUARKUS_OIDC_TENANT_ENABLED": "false",
            "QUARKUS_ANALYTICS_DISABLED": "true",
            "JAVA_OPTS_APPEND": "-Xmx768m",
            "EDDI_SCHEDULE_INSTANCE_ID": self.node(i),
            "EDDI_TOOLS_RATELIMIT_GLOBAL_ENABLED": "true",
            "EDDI_TOOLS_RATELIMIT_GLOBAL_LIMIT": "10",
        }
        if cluster:
            env.update({
                "EDDI_MESSAGING_TYPE": "nats",
                "EDDI_CLUSTER_NODE_ID": f"n{i + 1}",
                "EDDI_NATS_URL": ",".join(f"nats://{self.nats(j)}:4222" for j in range(3)),
                "EDDI_NATS_USERNAME": "eddi",
                "EDDI_NATS_PASSWORD": NATS_PASSWORD,
                "EDDI_NATS_REPLICAS": "3",
                "EDDI_SHUTDOWN_DRAIN_TIMEOUT_SECONDS": "40",
            })
        if self.db == "mongo":
            env["MONGODB_CONNECTIONSTRING"] = f"mongodb://eddi:changeme@{P}-mongo:27017/eddi?authSource=admin"
        else:
            env.update({"QUARKUS_PROFILE": "postgres", "EDDI_DATASTORE_TYPE": "postgres",
                        "QUARKUS_DATASOURCE_JDBC_URL": f"jdbc:postgresql://{P}-postgres:5432/eddi",
                        "QUARKUS_DATASOURCE_USERNAME": "eddi", "QUARKUS_DATASOURCE_PASSWORD": "eddi"})
        return env

    def start_node(self, i, cluster=True):
        sh("docker", "rm", "-f", self.node(i))
        cmd = ["docker", "run", "-d", "--name", self.node(i), "--network", NET, "--memory", "1100m",
               "--add-host", "host.docker.internal:host-gateway", "-p", f"127.0.0.1:{NODE_PORTS[i]}:7070"]
        if self.args.app and not self.args.baseline:
            # the read-only build needs the mount point of the writable scratch dir
            os.makedirs(os.path.join(self.args.app, "tmp"), exist_ok=True)
            # the build read-only, plus the scratch directory export/import writes to
            cmd += ["-v", f"{self.args.app}:/deployments:ro", "--tmpfs", "/deployments/tmp:mode=1777"]
        for k, v in self.node_env(i, cluster).items():
            cmd += ["-e", f"{k}={v}"]
        cmd += [self.image]
        sh(*cmd, check=True)

    def node_ready(self, i, timeout=240):
        return wait_until(lambda: http(NODES[i], "GET", "/q/health/ready", timeout=5)[0] == 200, timeout, 1)

    def start_lb(self):
        conf = os.path.join(HERE, "nginx-demo.conf")
        sh("docker", "rm", "-f", f"{P}-lb")
        sh("docker", "run", "-d", "--name", f"{P}-lb", "--network", NET, "--memory", "64m",
           "-p", f"127.0.0.1:{LB_PORT}:7230", "-v", f"{conf}:/etc/nginx/nginx.conf:ro", "nginx:1.27-alpine", check=True)

    def start_mock(self):
        if os.path.exists(MOCK_LOG):
            os.remove(MOCK_LOG)
        self.mock = subprocess.Popen([sys.executable, os.path.join(HERE, "mock_llm.py"), MOCK_LOG, str(MOCK_PORT)])
        time.sleep(1)

    def up(self):
        sh("docker", "network", "create", NET)
        if not self.args.external_mock:
            self.start_mock()
        self.start_db()
        cluster = not self.args.baseline
        if cluster or self.args.baseline_nats:
            for i in range(3):
                self.start_nats(i)
            self.nats_ready()
        for i in range(3):
            self.start_node(i, cluster)
        for i in range(3):
            if not self.node_ready(i):
                print(sh("docker", "logs", "--tail", "60", self.node(i)).stdout)
                raise SystemExit(f"node {i + 1} did not become ready")
        self.start_lb()
        wait_until(lambda: http(LB, "GET", "/q/health/ready", timeout=5)[0] == 200, 60)

    def down(self):
        for name in [self.node(i) for i in range(3)] + [self.nats(i) for i in range(3)] + \
                    [f"{P}-lb", f"{P}-mongo", f"{P}-postgres"]:
            sh("docker", "rm", "-f", "-v", name)
        sh("docker", "network", "rm", NET)
        if self.mock:
            self.mock.kill()

    def logs(self, i):
        return sh("docker", "logs", self.node(i)).stdout + sh("docker", "logs", self.node(i)).stderr


# ------------------------------------------------------------------ agents

def setup_llm_agent(base, name, api_key, extra=None):
    body = {"agentName": name, "systemPrompt": "You are a demo agent.", "provider": "openai", "model": "mock-1",
            "apiKey": api_key, "baseUrl": f"http://host.docker.internal:{MOCK_PORT}/v1", "enableBuiltInTools": True, "deploy": True}
    body.update(extra or {})
    s, h, b, _ = http(base, "POST", "/administration/agents/setup", body)
    j = jl(b)
    agent = j.get("agentId") or (re.search(r"agents/([0-9a-f-]+)", b) or [None, None])[1]
    wait_until(lambda: jl(http(base, "GET", f"/administration/production/deploymentstatus/{agent}?version=1")[2]).get("status") == "READY",
               90)
    return agent


def wait_ready_everywhere(agent, version=1, timeout=60):
    return wait_until(lambda: all(jl(http(n, "GET", f"/administration/production/deploymentstatus/{agent}?version={version}")[2])
                                  .get("status") == "READY" for n in NODES), timeout)


def start_conv(base, agent, user="demo-user"):
    s, h, b, _ = http(base, "POST", f"/agents/{agent}/start?userId={user}")
    m = re.search(r"conversations/([0-9a-f-]+)", h.get("Location", "") or "")
    return m.group(1) if m else None


def say(base, conv, text, timeout=120):
    return http(base, "POST", f"/agents/{conv}?returnCurrentStepOnly=true", {"input": text}, timeout=timeout)


def stored_inputs(conv, base=LB):
    s, _, b, _ = http(base, "GET", f"/agents/{conv}?returnCurrentStepOnly=false")
    return [o.get("input") for o in jl(b).get("conversationOutputs", []) if o.get("input")]


def conv_state(conv, base=LB):
    return jl(http(base, "GET", f"/agents/{conv}?returnCurrentStepOnly=true")[2]).get("conversationState")


def nats_box(*args):
    return sh("docker", "run", "--rm", "--network", NET, "natsio/nats-box:0.16.0", "nats",
              "-s", f"nats://eddi:{NATS_PASSWORD}@{P}-nats-1:4222", *args)


# ------------------------------------------------------------------ scenarios

def s1_ordering(infra, ctx):
    """Concurrent turns of one conversation from three nodes (slow LLM): serial, none lost, none duplicated,
    every turn sees every previously committed one."""
    agent = ctx["llm"]
    conv = start_conv(NODES[0], agent)
    tag = f"[s1-{conv[-6:]}]"
    t0 = time.time()
    jobs = [(NODES[i % 3], f"{tag} nap {i}") for i in range(18)]
    with cf.ThreadPoolExecutor(18) as ex:
        res = list(ex.map(lambda j: (j[1], say(j[0], conv, j[1], timeout=180)), jobs))
    wall = time.time() - t0
    statuses = collections.Counter(r[0] for _, r in res)
    entries = sorted(mock_entries(tag, since=t0), key=lambda e: e["start"])
    overlaps = sum(1 for a, b in zip(entries, entries[1:]) if b["start"] < a["end"] - 0.01)
    counts = [e["messages"] for e in entries]
    # Grows by 2 per committed turn until the history window caps it; and every
    # call carries the previous call's user message as its own previous one.
    increasing = all(b > a or b == a == max(counts) for a, b in zip(counts, counts[1:]))
    chained = all(b.get("prev_user") == a["user"] for a, b in zip(entries, entries[1:]))
    stored = stored_inputs(conv)
    ok_inputs = [t for t, r in res if r[0] == 200]
    dupes = [x for x, c in collections.Counter(stored).items() if c > 1]
    lost = sorted(set(ok_inputs) - set(stored))
    # fast variant: 3 x 40 instant turns
    conv2 = start_conv(NODES[0], agent)
    tag2 = f"[s1f-{conv2[-6:]}]"
    jobs2 = [(NODES[i % 3], f"{tag2} fast {i}") for i in range(120)]
    with cf.ThreadPoolExecutor(30) as ex:
        res2 = list(ex.map(lambda j: (j[1], say(j[0], conv2, j[1], timeout=180)), jobs2))
    entries2 = sorted(mock_entries(tag2, since=t0), key=lambda e: e["start"])
    overlaps2 = sum(1 for a, b in zip(entries2, entries2[1:]) if b["start"] < a["end"] - 0.01)
    counts2 = [e["messages"] for e in entries2]
    stored2 = stored_inputs(conv2)
    ok2 = [t for t, r in res2 if r[0] == 200]
    chained2 = all(b.get("prev_user") == a["user"] for a, b in zip(entries2, entries2[1:]))
    record("1", "strict ordering across nodes (18 concurrent 2 s turns + 120 instant turns)",
           overlaps == 0 and increasing and chained and not dupes and not lost and overlaps2 == 0
           and chained2 and not (set(ok2) - set(stored2)),
           slow={"statuses": dict(statuses), "llm_calls": len(entries), "overlaps": overlaps, "message_counts": counts,
                 "grows_until_window": increasing, "each_turn_saw_previous": chained, "stored": len(stored), "duplicates": dupes, "lost": lost,
                 "wall_s": round(wall, 1)},
           fast={"statuses": dict(collections.Counter(r[0] for _, r in res2)), "llm_calls": len(entries2), "overlaps": overlaps2,
                 "each_turn_saw_previous": chained2,
                 "stored": len(stored2), "lost": sorted(set(ok2) - set(stored2))[:5],
                 "retry_after_on_409": all(r[1].get("Retry-After") for _, r in res2 if r[0] == 409)})
    ctx["s1_conversations"] = [conv, conv2]


def s2_kill(infra, ctx):
    """kill -9 the node running a slow turn: the lease expires, the next turn runs on another node."""
    agent = ctx["llm"]
    conv = start_conv(NODES[0], agent)
    say(NODES[0], conv, "[s2] warmup")
    before = len(stored_inputs(conv))
    holder = {}
    th = threading.Thread(target=lambda: holder.setdefault("r", say(NODES[0], conv, "[s2] slow turn", timeout=120)))
    th.start()
    time.sleep(3)
    sh("docker", "kill", infra.node(0))
    t0 = time.time()
    s, h, b, d = say(NODES[1], conv, "[s2] after the crash", timeout=120)
    took = time.time() - t0
    th.join(5)
    state = conv_state(conv, NODES[1])
    stored = stored_inputs(conv, NODES[1])
    takeover = metric(NODES[1], "eddi_cluster_lease_takeover_total")
    infra.start_node(0)
    infra.node_ready(0)
    stored_after = stored_inputs(conv, NODES[1])
    record("2", "kill -9 mid-turn: lease expiry/takeover, self-heal, no zombie write",
           s == 200 and took < 40 and state == "READY" and "[s2] slow turn" not in stored_after
           and stored_after.count("[s2] after the crash") == 1,
           crashed_client=str(holder.get("r", ("reset",))[0]), next_turn_status=s, next_turn_waited_s=round(took, 1),
           state=state, stored_before=before, stored_after=len(stored_after), takeover_metric_n2=takeover)


def s3_nats(infra, ctx):
    """One NATS node down: nothing visible. All NATS down: degraded, readiness UP, turns continue; recovery."""
    agent = ctx["llm"]
    conv = start_conv(LB, agent)
    sh("docker", "stop", infra.nats(1))
    one_down = [say(LB, conv, f"[s3] one nats down {i}")[0] for i in range(10)]
    ready_one = [http(n, "GET", "/q/health/ready")[0] for n in NODES]
    for i in range(3):
        sh("docker", "stop", "-t", "1", infra.nats(i))
    time.sleep(8)
    ready_all = [http(n, "GET", "/q/health/ready") for n in NODES]
    degraded = [("\"degraded\":true" in r[2].replace(" ", "")) for r in ready_all]
    all_down = []
    for i in range(6):
        r = say(NODES[i % 3], conv, f"[s3] all nats down {i}")
        all_down.append((r[0], round(r[3], 2)))
    decisions = sum(metric(n, "eddi_cluster_degraded_decisions_total", 'area="turns"') for n in NODES)
    for i in range(3):
        sh("docker", "start", infra.nats(i))
    infra.nats_ready()
    reconnected = wait_until(lambda: all("\"nats\":\"CONNECTED\"" in http(n, "GET", "/q/health/ready")[2].replace(" ", "") for n in NODES), 60)
    after = [say(LB, conv, f"[s3] recovered {i}") for i in range(6)]
    stored = stored_inputs(conv)
    record("3", "NATS: one node down invisible; all down = degraded (readiness UP, turns local); recovery",
           all(s == 200 for s in one_down) and all(r[0] == 200 for r in ready_all) and all(degraded)
           and all(s == 200 for s, _ in all_down) and bool(reconnected) and all(r[0] == 200 for r in after)
           and len([x for x in stored if x.startswith("[s3]")]) == 22,
           one_down_statuses=one_down, readiness_one_down=ready_one, readiness_all_down=[r[0] for r in ready_all],
           degraded_flags=degraded, all_down_turns=all_down, degraded_turn_decisions=decisions,
           recovered=bool(reconnected), after=[r[0] for r in after], stored_s3=len([x for x in stored if x.startswith("[s3]")]))


def s4_rolling(infra, ctx):
    """Rolling restart of all three nodes under continuous load through the LB."""
    agent = ctx["llm"]
    convs = [start_conv(LB, agent) for _ in range(5)]
    stop = threading.Event()
    log = []

    def client(conv, k):
        n = 0
        while not stop.is_set():
            text = f"[s4-{k}] turn {n}"
            for attempt in range(10):
                s, h, b, d = say(LB, conv, text, timeout=150)
                log.append((k, n, s, h.get("Retry-After")))
                if s == 200:
                    break
                time.sleep(float(h.get("Retry-After") or 1))
            n += 1
            time.sleep(0.2)

    threads = [threading.Thread(target=client, args=(c, k)) for k, c in enumerate(convs)]
    for t in threads:
        t.start()
    time.sleep(10)
    for i in range(3):
        sh("docker", "stop", "-t", "70", infra.node(i))
        sh("docker", "start", infra.node(i))
        infra.node_ready(i)
        time.sleep(5)
    time.sleep(5)
    stop.set()
    for t in threads:
        t.join(200)
    statuses = collections.Counter(s for _, _, s, _ in log)
    failed = [(k, n, s) for k, n, s, ra in log if s not in (200, 409)]
    no_retry_after = [(k, n) for k, n, s, ra in log if s == 409 and not ra]
    lost = []
    for k, c in enumerate(convs):
        sent_ok = {f"[s4-{kk}] turn {n}" for kk, n, s, _ in log if kk == k and s == 200}
        lost += sorted(sent_ok - set(stored_inputs(c)))
    recovery_fired = sum(l.count("Recovered stuck IN_PROGRESS") for l in [infra.logs(i) for i in range(3)])
    record("4", "rolling restart of all nodes under load: no failed or lost turns beyond 409 Retry-After",
           not failed and not lost and not no_retry_after and recovery_fired == 0,
           requests=len(log), statuses=dict(statuses), failed=failed[:10], lost=lost[:10], conflicts_without_retry_after=no_retry_after,
           hitl_recovery_on_live_conversations=recovery_fired)


def s5_cancel_gdpr(infra, ctx):
    """Cancel on node B stops a slow turn on node A; GDPR erasure on A stops in-flight work on B."""
    agent = ctx["llm"]
    conv = start_conv(NODES[0], agent)
    holder = {}
    th = threading.Thread(target=lambda: holder.setdefault("r", say(NODES[0], conv, "[s5] linger cancel me", timeout=120)))
    th.start()
    time.sleep(3)
    s, _, b, _ = http(NODES[1], "POST", f"/agents/{conv}/cancel")
    cancel_outcome = (s, b[:120])
    th.join(90)
    state = wait_until(lambda: conv_state(conv) == "EXECUTION_INTERRUPTED" and "EXECUTION_INTERRUPTED", 30)
    stored = stored_inputs(conv)
    # GDPR
    user = f"gdpr-user-{int(time.time())}"
    conv2 = start_conv(NODES[1], agent, user)
    holder2 = {}
    th2 = threading.Thread(target=lambda: holder2.setdefault("r", say(NODES[1], conv2, "[s5g] linger erase me", timeout=120)))
    th2.start()
    time.sleep(3)
    t0 = time.time()
    s2, _, b2, _ = http(NODES[0], "DELETE", f"/admin/gdpr/{user}", timeout=60)
    report = jl(b2)
    th2.join(90)
    turn_after = holder2.get("r", (None,))[0]
    log_a = infra.logs(0)
    m = re.findall(r"Signalled (\d+) in-flight", log_a)
    report["inFlightWorkStopped"] = int(m[-1]) if m else 0
    log_b = infra.logs(1)
    time.sleep(2)
    conv2_after = http(NODES[2], "GET", f"/agents/{conv2}?returnCurrentStepOnly=true")[0]
    record("5", "cancel and GDPR stop reach the node that runs the turn",
           s == 200 and state == "EXECUTION_INTERRUPTED" and "[s5] linger cancel me" not in stored
           and s2 == 200 and report.get("inFlightWorkStopped", 0) >= 1 and conv2_after == 404,
           erased_conversation_after_turn_ended=conv2_after,
           cancel=cancel_outcome, state_after_cancel=state, cancelled_turn_stored=("[s5] linger cancel me" in stored),
           gdpr_status=s2, gdpr_inflight_stopped=report.get("inFlightWorkStopped"), gdpr_failed_steps=report.get("failedSteps"),
           erased_turn_response=turn_after, erase_took_s=round(time.time() - t0, 1),
           node_b_log_signalled=("Signalled" in log_b or "stop" in log_b))


def s6_undeploy(infra, ctx):
    """Undeploy on node A: B and C stop serving within seconds; a new version deployed on A propagates."""
    agent = setup_llm_agent(NODES[0], f"p11-undeploy-{int(time.time())}", "sk-undeploy-0123456789")
    wait_ready_everywhere(agent)
    conv = start_conv(NODES[1], agent)
    s, _, _, _ = http(NODES[0], "POST", f"/administration/production/undeploy/{agent}?version=1&endAllActiveConversations=true")
    t0 = time.time()
    gone = wait_until(lambda: all(jl(http(n, "GET", f"/administration/production/deploymentstatus/{agent}?version=1")[2]).get("status")
                                  != "READY" for n in NODES[1:]), 30, 0.25)
    took = time.time() - t0
    s_start = http(NODES[2], "POST", f"/agents/{agent}/start")[0]
    t1 = time.time()
    http(NODES[0], "POST", f"/administration/production/deploy/{agent}?version=1")
    back = wait_ready_everywhere(agent, 1, 30)
    took_deploy = time.time() - t1
    record("6", "undeploy propagates within seconds; redeploy propagates",
           s in (200, 202, 204) and bool(gone) and took < 5 and s_start != 201 and bool(back),
           undeploy_status=s, undeploy_propagated_s=round(took, 2), start_after_undeploy_on_n3=s_start,
           redeploy_propagated_s=round(took_deploy, 2))


def s7_secret(infra, ctx):
    """Rotate a vault secret on A; C's next LLM call carries the new key."""
    key = "p11-llm-key"
    http(NODES[0], "PUT", f"/secretstore/secrets/default/{key}", {"value": "sk-first-0123456789abcdef"})
    agent = setup_llm_agent(NODES[0], f"p11-secret-{int(time.time())}", None, {"vaultKeyName": key})
    wait_ready_everywhere(agent)
    conv = start_conv(NODES[2], agent)
    t0 = time.time()
    say(NODES[2], conv, "[s7] before rotation")
    before = [e["auth"] for e in mock_entries("[s7]", since=t0)]
    s, _, _, _ = http(NODES[0], "PUT", f"/secretstore/secrets/default/{key}", {"value": "sk-second-0123456789abcdef"})
    time.sleep(2)
    t1 = time.time()
    say(NODES[2], conv, "[s7] after rotation")
    after = [e["auth"] for e in mock_entries("[s7]", since=t1)]
    import hashlib
    h1 = hashlib.sha256(b"Bearer sk-first-0123456789abcdef").hexdigest()[:12]
    h2 = hashlib.sha256(b"Bearer sk-second-0123456789abcdef").hexdigest()[:12]
    record("7", "secret rotated on n1 is used by n3's very next LLM call",
           bool(before) and bool(after) and before[-1] == h1 and after[-1] == h2,
           rotate_status=s, auth_before=before, auth_after=after, expected_before=h1, expected_after=h2)


def s8_nonce(infra, ctx):
    """Replay nonces are cluster-wide. Signed envelopes are produced and checked inside EDDI only (group
    discussions), so the replay is shown on the shared bucket itself plus the in-JVM IT."""
    listing = nats_box("kv", "ls").stdout
    buckets = [l.split()[1] for l in listing.splitlines() if "EDDI_" in l and len(l.split()) > 1]
    record("8", "nonce replay across nodes", None,
           buckets=buckets,
           why=("Signed envelopes are created and verified inside EDDI only (agent-to-agent signing in group "
                "discussions); no public endpoint accepts a caller-supplied envelope, so a replay cannot be sent "
                "from outside. NonceCacheService claims each nonce with one atomic create on the shared "
                "<prefix>_NONCES bucket (created on first use) and refuses the envelope when NATS is unreachable; "
                "covered by NonceCacheServiceTest and SharedKvCacheTest (second claim from another node refused, "
                "degraded store fails closed)."))


def s9_ratelimit(infra, ctx):
    """Global tool limit 10/min: 15 calls spread over nodes, exactly 10 allowed."""
    agent = ctx["llm"]
    time.sleep(61)  # a fresh window: earlier scenarios may have used calculate
    t0 = time.time()
    limit = 10
    convs = [start_conv(NODES[i % 3], agent) for i in range(15)]
    for i, c in enumerate(convs):
        say(NODES[i % 3], c, '[s9] usetool:calculate:{"expression":"6*7"}')
    results = [e["tool_result"] or "" for e in mock_entries("[s9]", since=t0) if e.get("role") == "tool"]
    ctx["ratelimit_window_used"] = time.time()
    allowed = sum(1 for r in results if "42" in r)
    denied = sum(1 for r in results if "Rate limit exceeded" in r)
    elapsed = time.time() - t0
    refilled = int(elapsed * limit / 60)
    decisions = {n: metric(n, "eddi_cluster_ratelimit_decisions_total", 'scope="global"') for n in NODES}
    # one bucket for the cluster: never more than the burst plus what refilled meanwhile,
    # where three node-local buckets would have admitted all 15
    record("9", "global tool rate limit (10/min) enforced cluster-wide, calls spread over three nodes",
           len(results) == 15 and allowed <= limit + refilled and denied >= 15 - limit - refilled and denied > 0,
           tool_calls=len(results), allowed=allowed, denied=denied, elapsed_s=round(elapsed, 1),
           refilled_during_run=refilled, global_decisions_per_node=decisions)


def s10_pages(infra, ctx):
    """A paginated tool response stored on n1 is fetched through n2."""
    agent = ctx.get("paged")
    if not agent:
        record("10", "paginated tool page via another node", False, note="paging agent not set up")
        return
    used = ctx.get("ratelimit_window_used")
    if used:  # scenario 9 spent this minute's calculate budget
        time.sleep(max(0, 62 - (time.time() - used)))
    conv = start_conv(NODES[0], agent)
    t0 = time.time()
    # formatJson pretty-prints a 300-element array: far past the page size (the engine
    # floors a configured limit at 256 characters, so the result has to be longer)
    say(NODES[0], conv, "[s10] usetool:formatJson:" + json.dumps({"jsonString": json.dumps(list(range(300)))}))
    tool_results = [e["tool_result"] for e in mock_entries("[s10]", since=t0) if e.get("tool_result")]
    rid = None
    for t in tool_results:
        m = re.search(r'responseId=\\?"([0-9a-f-]{36})', t or "")
        rid = rid or (m.group(1) if m else None)
    t1 = time.time()
    say(NODES[1], conv, '[s10] usetool:fetch_tool_response_page:{"responseId":"%s","pageNumber":2}' % rid)
    page = [e["tool_result"] for e in mock_entries("[s10]", since=t1) if e.get("tool_result")]
    record("10", "paginated tool page stored on n1, fetched via n2",
           bool(rid) and bool(page) and "error" not in (page[-1] or "").lower(),
           response_id=rid, first_page=tool_results[-1:] , page2=page[-1:])


def s11_hitl(infra, ctx):
    """HITL: a tool call gated for approval pauses the turn on n1; the approval goes to n2."""
    agent = setup_llm_agent(NODES[0], f"p11-hitl-{int(time.time())}", "sk-hitl-0123456789",
                            {"hitlConfig": {"toolApprovals": {"requireApproval": ["calculate"]}}})
    wait_ready_everywhere(agent)
    used = ctx.get("ratelimit_window_used")
    if used:  # scenario 9 spent this minute's calculate budget
        time.sleep(max(0, 62 - (time.time() - used)))
    conv = start_conv(NODES[0], agent)
    t0 = time.time()
    s1, _, _, _ = say(NODES[0], conv, '[s11] usetool:calculate:{"expression":"6*7"}')
    st = conv_state(conv, NODES[0])
    s2, _, b2, _ = http(NODES[1], "POST", f"/agents/{conv}/resume", {"verdict": "APPROVED", "note": "demo"})
    after = wait_until(lambda: conv_state(conv, NODES[2]) == "READY" and "READY", 30)
    tool_results = [e["tool_result"] for e in mock_entries("[s11]", since=t0) if e.get("tool_result")]
    record("11", "HITL pause on n1 resumed via n2", st == "AWAITING_HUMAN" and s2 in (200, 202) and after == "READY"
           and any("42" in (t or "") for t in tool_results),
           pause_status=s1, state_after_pause=st, resume_status=s2, resume_body=b2[:160], state_after_resume=after,
           tool_result_after_approval=tool_results[-1:])


def s12_audit(infra, ctx):
    """Audit chains written by three nodes verify intact; no collision WARN."""
    reports = {}
    convs = list(ctx.get("s1_conversations", []))
    # plus one written here, turn by turn on alternating nodes
    own = start_conv(NODES[0], ctx["llm"])
    for i in range(12):
        say(NODES[i % 3], own, f"[s12] turn {i}")
    convs.append(own)
    time.sleep(6)  # the ledger flushes on an interval
    for conv in convs:
        s, _, b, _ = http(LB, "GET", f"/auditstore/verify/{conv}")
        j = jl(b)
        reports[conv] = {"status": s, "verdict": j.get("chainStatus"),
                         "summary": {k: j.get(k) for k in ("entriesChecked", "valid", "invalid", "unsigned", "missingSequences",
                                                           "duplicateSequences") if k in j}}
    warns = sum(infra.logs(i).count("another replica is allocating") for i in range(3))
    unformatted = sum(infra.logs(i).count("{2}") for i in range(3))
    collisions = sum(metric(n, "eddi_audit_sequence_collisions_total") for n in NODES)
    verdicts = [r["verdict"] for r in reports.values()]
    record("12", "audit chain intact across nodes (no BROKEN, no collision WARN)",
           reports and all(v == "INTACT" for v in verdicts) and warns == 0 and collisions == 0,
           reports=reports, collision_warns=warns, unformatted_placeholders=unformatted, collision_metric=collisions)


def set_fence(infra, conv, value):
    """Simulates a writer holding a newer lease: raises (or, with None, removes) the fence the
    stored conversation has seen. MongoDB: the `_fence` field; PostgreSQL: data->'_fence'."""
    if infra.db == "mongo":
        op = "{$unset:{_fence:''}}" if value is None else "{$set:{_fence:NumberLong('%d')}}" % value
        return sh("docker", "exec", f"{P}-mongo", "mongosh", "--quiet", "-u", "eddi", "-p", "changeme",
                  "--authenticationDatabase", "admin", "eddi", "--eval",
                  "db.conversationmemories.updateOne({_id:ObjectId('%s')}, %s).modifiedCount" % (conv, op)).stdout.strip()
    expr = "data - '_fence'" if value is None else "jsonb_set(data, '{_fence}', '%d'::jsonb)" % value
    return sh("docker", "exec", f"{P}-postgres", "psql", "-U", "eddi", "-d", "eddi", "-tAc",
              "UPDATE conversation_memories SET data = %s WHERE id = '%s'" % (expr, conv)).stdout.strip()


def fenced_turn(infra, conv, text):
    """A turn on n1 whose write meets a newer fence: it must be refused and dead-lettered."""
    holder = {}
    th = threading.Thread(target=lambda: holder.setdefault("r", say(NODES[0], conv, text, timeout=90)))
    th.start()
    time.sleep(3)
    raised = set_fence(infra, conv, 1 << 40)
    th.join(80)
    set_fence(infra, conv, None)
    return holder.get("r", (None,))[0], raised


def s13_deadletters(infra, ctx):
    """A write refused by the fence is dead-lettered with its input; every node lists it; replay on n3
    stores it; discard answers 204 then 404; purge empties the stream."""
    agent = ctx["llm"]
    conv = start_conv(NODES[0], agent)
    say(NODES[0], conv, "[s13] warmup")
    before_rejected = metric(NODES[0], "eddi_cluster_fence_rejected_total")
    status, raised = fenced_turn(infra, conv, "[s13] linger fenced one")
    wait_until(lambda: metric(NODES[0], "eddi_cluster_fence_rejected_total") > before_rejected, 15)
    fenced = metric(NODES[0], "eddi_cluster_fence_rejected_total") - before_rejected
    lists = [jl(http(n, "GET", "/administration/coordinator/dead-letters")[2]) or [] for n in NODES]
    mine = [[e for e in l if e.get("conversationId") == conv] for l in lists]
    entry = mine[0][0] if mine[0] else None
    stored = stored_inputs(conv)
    replay = None
    if entry:
        replay = http(NODES[2], "POST", f"/administration/coordinator/dead-letters/{entry['id']}/replay")[0]
        wait_until(lambda: "[s13] linger fenced one" in stored_inputs(conv), 40)
    stored_after = stored_inputs(conv)
    still = [e for e in (jl(http(NODES[0], "GET", "/administration/coordinator/dead-letters")[2]) or [])
             if e.get("conversationId") == conv]
    fenced_turn(infra, conv, "[s13] linger fenced two")
    time.sleep(2)
    l2 = [e for e in (jl(http(NODES[1], "GET", "/administration/coordinator/dead-letters")[2]) or [])
          if e.get("conversationId") == conv]
    discard = discard2 = None
    if l2:
        discard = http(NODES[1], "DELETE", f"/administration/coordinator/dead-letters/{l2[0]['id']}")[0]
        discard2 = http(NODES[2], "DELETE", f"/administration/coordinator/dead-letters/{l2[0]['id']}")[0]
    purge = http(NODES[0], "DELETE", "/administration/coordinator/dead-letters")
    after_purge = [len(jl(http(n, "GET", "/administration/coordinator/dead-letters")[2]) or []) for n in NODES]
    record("13", "a fenced write is dead-lettered with its input; listed on every node, replayed on n3, discarded 204 then 404",
           fenced >= 1 and all(len(m) >= 1 for m in mine) and "[s13] linger fenced one" not in stored
           and replay == 204 and "[s13] linger fenced one" in stored_after and not still
           and discard == 204 and discard2 == 404 and after_purge == [0, 0, 0],
           fence_raised=raised, fenced_turn_status=status, fence_rejected=fenced, listed_per_node=[len(m) for m in mine],
           entry_turn=(entry or {}).get("turn"), stored_before_replay=("[s13] linger fenced one" in stored),
           replay_status=replay, stored_after_replay=("[s13] linger fenced one" in stored_after),
           remaining_after_replay=len(still), discard=discard, discard_again=discard2, purge=(purge[0], purge[2][:20]),
           after_purge=after_purge,
           how="the stored conversation's fence is raised under a running turn, as a newer lease holder's write would")


def s14_baseline(infra, ctx):
    """Stock image + EDDI_MESSAGING_TYPE=nats (inert): concurrent cross-node turns overlap."""
    agent = ctx["llm"]
    conv = start_conv(NODES[0], agent)
    tag = f"[s14-{conv[-6:]}]"
    t0 = time.time()
    jobs = [(NODES[i % 3], f"{tag} nap {i}") for i in range(9)]
    with cf.ThreadPoolExecutor(9) as ex:
        res = list(ex.map(lambda j: say(j[0], conv, j[1], timeout=180), jobs))
    entries = sorted(mock_entries(tag, since=t0), key=lambda e: e["start"])
    overlaps = sum(1 for a, b in zip(entries, entries[1:]) if b["start"] < a["end"] - 0.01)
    counts = [e["messages"] for e in entries]
    status = jl(http(NODES[0], "GET", "/administration/coordinator/status")[2])
    record("14", "BASELINE stock image: cross-node turns overlap and miss each other's context",
           overlaps > 0, coordinator_type=status.get("coordinatorType"), statuses=dict(collections.Counter(r[0] for r in res)),
           overlaps=overlaps, message_counts=counts, wall_s=round(time.time() - t0, 1))


SCENARIOS = {"1": s1_ordering, "2": s2_kill, "3": s3_nats, "4": s4_rolling, "5": s5_cancel_gdpr, "6": s6_undeploy,
             "7": s7_secret, "8": s8_nonce, "9": s9_ratelimit, "10": s10_pages, "11": s11_hitl, "12": s12_audit,
             "13": s13_deadletters}


def setup_paged_agent(base):
    """An LLM agent whose tool responses are paginated at 6 characters (versioned config update)."""
    agent = setup_llm_agent(base, f"p11-paged-{int(time.time())}", "sk-paged-0123456789")
    a = jl(http(base, "GET", f"/agentstore/agents/{agent}?version=1")[2])
    wf_uri = a["workflows"][0]
    wf_id, wf_ver = re.search(r"workflows/([^?]+)\?version=(\d+)", wf_uri).groups()
    wf = jl(http(base, "GET", f"/workflowstore/workflows/{wf_id}?version={wf_ver}")[2])
    for step in wf["workflowSteps"]:
        uri = (step.get("config") or {}).get("uri", "")
        m = re.search(r"/(llmstore/llms|langchainstore/langchains)/([^?]+)\?version=(\d+)", uri)
        if m:
            store, lid, lver = m.groups()
            cfg = jl(http(base, "GET", f"/{store}/{lid}?version={lver}")[2])
            for task in cfg.get("tasks", []):
                task["toolResponseLimits"] = {"defaultMaxChars": 6, "truncationStrategy": "paginate"}
            s, h, _, _ = http(base, "PUT", f"/{store}/{lid}?version={lver}", cfg)
            new_ver = re.search(r"version=(\d+)", h.get("Location", "")).group(1)
            step["config"]["uri"] = uri.replace(f"version={lver}", f"version={new_ver}")
    s, h, _, _ = http(base, "PUT", f"/workflowstore/workflows/{wf_id}?version={wf_ver}", wf)
    new_wf = re.search(r"version=(\d+)", h.get("Location", "")).group(1)
    a["workflows"][0] = wf_uri.replace(f"version={wf_ver}", f"version={new_wf}")
    s, h, _, _ = http(base, "PUT", f"/agentstore/agents/{agent}?version=1", a)
    new_agent = re.search(r"version=(\d+)", h.get("Location", "")).group(1)
    http(base, "POST", f"/administration/production/deploy/{agent}?version={new_agent}")
    wait_ready_everywhere(agent, int(new_agent), 90)
    # only the paginating version may serve new conversations
    http(base, "POST", f"/administration/production/undeploy/{agent}?version=1&endAllActiveConversations=true")
    return agent


def main():
    ap = argparse.ArgumentParser()
    default_app = os.path.normpath(os.path.join(HERE, "..", "..", "target", "quarkus-app"))
    ap.add_argument("--app", default=os.environ.get("EDDI_APP") or (default_app if os.path.isdir(default_app) else None),
                    help="absolute path of target/quarkus-app (mounted over /deployments); default $EDDI_APP")
    ap.add_argument("--db", default="mongo", choices=["mongo", "postgres"])
    ap.add_argument("--image", default="labsai/eddi:6.5.0")
    ap.add_argument("--only", default="")
    ap.add_argument("--baseline", action="store_true", help="stock image, no cluster mode (scenario 14)")
    ap.add_argument("--keep", action="store_true", help="leave the stack running")
    ap.add_argument("--external-mock", action="store_true", help="the mock LLM is already running on 18210")
    ap.add_argument("--reuse", action="store_true", help="run against a stack left by --keep (mock LLM started separately)")
    args = ap.parse_args()
    if not args.baseline and not args.app:
        ap.error("--app (or EDDI_APP) is required: without it the nodes would run the stock image")
    args.baseline_nats = args.baseline
    infra = Infra(args)
    suffix = "-baseline" if args.baseline else ("-s" + args.only.replace(",", "_") if args.only else "")
    out = os.path.join(HERE, f"results-{args.db}{suffix}.json")
    try:
        if args.reuse:
            ctx_file = os.path.join(HERE, "ctx.json")
        else:
            infra.up()
        ctx = {"llm": setup_llm_agent(NODES[0], f"p11-llm-{int(time.time())}", "sk-demo-0123456789")}
        wait_ready_everywhere(ctx["llm"])
        if args.baseline:
            s14_baseline(infra, ctx)
        else:
            wanted = [s for s in args.only.split(",") if s] or list(SCENARIOS)
            if "10" in wanted:
                ctx["paged"] = setup_paged_agent(NODES[0])
            for sid in wanted:
                if sid not in SCENARIOS:
                    continue
                try:
                    SCENARIOS[sid](infra, ctx)
                except Exception as e:  # a crashed scenario is a failed one, and the rest still run
                    record(sid, SCENARIOS[sid].__doc__.strip().splitlines()[0], False, error=repr(e))
    finally:
        json.dump(RESULTS, open(out, "w"), indent=2)
        print(f"\n{sum(1 for r in RESULTS.values() if r['pass'])}/{len(RESULTS)} scenarios passed, "
              f"{sum(1 for r in RESULTS.values() if r['pass'] is None)} not applicable -> {out}")
        logdir = os.path.join(HERE, f"logs-{args.db}{suffix}")
        os.makedirs(logdir, exist_ok=True)
        for i in range(3):
            try:
                with open(os.path.join(logdir, f"eddi-{i + 1}.log"), "w", encoding="utf-8") as f:
                    f.write(infra.logs(i))
            except Exception:
                pass
        if not args.keep and not args.reuse:
            infra.down()


if __name__ == "__main__":
    main()
