/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.cluster;

import ai.labs.eddi.integration.ContainerBaseIT;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.LlmCall;
import ai.labs.eddi.integration.cluster.ConversationHistoryChecker.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.StreamInfo;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The multi-process cluster the end-to-end suite runs against: three EDDI
 * processes built from this checkout's packaged app (the production Dockerfile,
 * as {@link ContainerBaseIT} builds it), a three-server NATS JetStream cluster
 * at R3, a real MongoDB or PostgreSQL, the cluster demo's mock LLM, and a
 * Toxiproxy between every EDDI process and every NATS server.
 *
 * <pre>
 *  test JVM ──HTTP──► eddi-1   eddi-2   eddi-3        (one image, cluster mode, degraded.turns=reject)
 *                       │ │ │    │ │ │    │ │ │
 *                     toxiproxy: n{i}-nats{j}  ── one proxy per (EDDI node, NATS server)
 *                       nats-1 ═ nats-2 ═ nats-3      (R3, --no_advertise so clients stay behind the proxy)
 *                     mongodb | postgres     mockllm (scripts/cluster-demo/mock_llm.py)
 * </pre>
 *
 * Faults: {@link #freeze} / {@link #thaw} ({@code docker pause}: the cgroup
 * freezer, i.e. SIGSTOP of every process in the container), {@link #kill9},
 * per-node toxics on the NATS links ({@link #latency}, {@link #dropReplies},
 * {@link #halfOpen}, {@link #partition}), and {@link #stepDownStreamLeaders}.
 * <p>
 * One harness per database per JVM: it is started on first use and torn down by
 * Testcontainers' reaper when the JVM exits, so a rerun of a failed test does
 * not pay for a second cluster.
 */
final class ClusterHarness {

    enum Db {
        MONGO, POSTGRES
    }

    static final int NODES = 3;
    static final String NATS_PASSWORD = "e2e-nats-password-0123456789";
    static final String VAULT_KEY = "e2e-test-master-key-0123456789abcdef";
    static final int MOCK_PORT = 18210;
    /** {@code eddi.cluster.lease.ttl}; the suite runs the defaults. */
    static final Duration LEASE_TTL = Duration.ofSeconds(20);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Db, ClusterHarness> RUNNING = new EnumMap<>(Db.class);
    private static final Pattern AGENT_ID = Pattern.compile("agents/([0-9a-fA-F-]{8,})");
    private static final Pattern TASK_ERRORS = Pattern.compile("\"taskErrors\"\\s*:\\s*\\[\\s*\\{");
    private static final Pattern CONVERSATION_ID = Pattern.compile("conversations/([0-9a-fA-F-]{8,})");

    final Db db;
    private final Network network = Network.newNetwork();
    private final List<GenericContainer<?>> nats = new ArrayList<>();
    private final List<GenericContainer<?>> eddi = new ArrayList<>();
    private final Set<Integer> frozen = new HashSet<>();
    private final Set<Integer> dead = new HashSet<>();
    /**
     * Inputs answered 200 whose reply carries a task error: the pipeline ran, but
     * not the LLM call a scenario depends on.
     */
    final Set<String> answeredWithTaskError = ConcurrentHashMap.newKeySet();
    private GenericContainer<?> database;
    private GenericContainer<?> toxiproxyContainer;
    private GenericContainer<?> mock;
    private Toxiproxy toxiproxy;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1)
            .build();

    private ClusterHarness(Db db) {
        this.db = db;
    }

    static synchronized ClusterHarness get(Db db) {
        return RUNNING.computeIfAbsent(db, d -> {
            ClusterHarness h = new ClusterHarness(d);
            h.start();
            return h;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressWarnings("resource")
    private void start() {
        for (int j = 0; j < NODES; j++) {
            nats.add(new GenericContainer<>("nats:2.11-alpine").withNetwork(network).withNetworkAliases("nats-" + (j + 1))
                    .withExposedPorts(4222, 8222)
                    .withCommand("--name", "nats-" + (j + 1), "--jetstream", "--store_dir=/data", "-m", "8222", "--user", "eddi", "--pass",
                            NATS_PASSWORD, "--cluster_name", "eddi", "--cluster", "nats://0.0.0.0:6222", "--routes",
                            routesExcept(j), "--no_advertise")
                    .waitingFor(Wait.forHttp("/healthz?js-enabled-only=true").forPort(8222).withStartupTimeout(Duration.ofSeconds(90))));
        }
        database = db == Db.MONGO
                ? new MongoDBContainer("mongo:7.0.43").withNetwork(network).withNetworkAliases("mongodb")
                : new PostgreSQLContainer("postgres:16-alpine").withNetwork(network).withNetworkAliases("postgres").withDatabaseName("eddi")
                        .withUsername("eddi").withPassword("eddi");
        toxiproxyContainer = new GenericContainer<>("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(network).withNetworkAliases("toxiproxy")
                .withExposedPorts(8474).waitingFor(Wait.forHttp("/version").forPort(8474));
        mock = new GenericContainer<>("python:3.12.7-alpine3.20").withNetwork(network).withNetworkAliases("mockllm")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("scripts/cluster-demo/mock_llm.py")), "/mock/mock_llm.py")
                .withEnv("MOCK_LLM_BIND", "0.0.0.0").withExposedPorts(MOCK_PORT)
                .withCommand("python", "/mock/mock_llm.py", "/tmp/requests.jsonl", String.valueOf(MOCK_PORT))
                .waitingFor(Wait.forListeningPort());

        List<GenericContainer<?>> infra = new ArrayList<>(nats);
        infra.add(database);
        infra.add(toxiproxyContainer);
        infra.add(mock);
        Startables.deepStart(infra).join();

        toxiproxy = new Toxiproxy("http://" + toxiproxyContainer.getHost() + ":" + toxiproxyContainer.getMappedPort(8474));
        for (int i = 0; i < NODES; i++) {
            for (int j = 0; j < NODES; j++) {
                toxiproxy.createProxy(proxy(i, j), "0.0.0.0:" + proxyPort(i, j), "nats-" + (j + 1) + ":4222");
            }
        }

        ImageFromDockerfile image = ContainerBaseIT.buildEddiImage("eddi-cluster-e2e");
        for (int i = 0; i < NODES; i++) {
            GenericContainer<?> node = new GenericContainer<>(image).withNetwork(network).withNetworkAliases("eddi-" + (i + 1))
                    .withExposedPorts(7070).withEnv(nodeEnv(i))
                    .waitingFor(Wait.forHttp("/q/health/ready").forPort(7070).withStartupTimeout(Duration.ofSeconds(300)));
            eddi.add(node);
        }
        Startables.deepStart(eddi).join();
    }

    private static String routesExcept(int j) {
        List<String> r = new ArrayList<>();
        for (int k = 0; k < NODES; k++) {
            if (k != j) {
                r.add("nats://nats-" + (k + 1) + ":6222");
            }
        }
        return String.join(",", r);
    }

    static String proxy(int node, int natsServer) {
        return "n" + (node + 1) + "-nats" + (natsServer + 1);
    }

    private static int proxyPort(int node, int natsServer) {
        return 20000 + node * 10 + natsServer;
    }

    private Map<String, String> nodeEnv(int i) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("QUARKUS_OIDC_TENANT_ENABLED", "false");
        env.put("AUTHORIZATION_ENABLED", "false");
        env.put("EDDI_SECURITY_ALLOW_UNAUTHENTICATED", "true");
        env.put("EDDI_MCP_ALLOW_UNAUTHENTICATED", "true");
        env.put("EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED", "true");
        env.put("QUARKUS_ANALYTICS_DISABLED", "true");
        env.put("EDDI_VAULT_MASTER_KEY", VAULT_KEY);
        // The mock LLM is plain http on the test network; without this every LLM call
        // is refused
        // (VaultHostAllowlist) and the turns "succeed" with a task error and no LLM
        // call at all.
        env.put("EDDI_VAULT_ALLOW_PLAINTEXT_REMOTE_HOSTS", "true");
        env.put("JAVA_OPTS_APPEND", "-Xmx640m");
        env.put("EDDI_SCHEDULE_INSTANCE_ID", "eddi-" + (i + 1));
        // allConnected() reads "nats":"CONNECTED" from the cluster readiness check,
        // which
        // carries data only when verbose
        env.put("EDDI_HEALTH_VERBOSE", "true");
        env.put("EDDI_MESSAGING_TYPE", "nats");
        env.put("EDDI_CLUSTER_NODE_ID", "n" + (i + 1));
        List<String> urls = new ArrayList<>();
        for (int j = 0; j < NODES; j++) {
            urls.add("nats://toxiproxy:" + proxyPort(i, j));
        }
        env.put("EDDI_NATS_URL", String.join(",", urls));
        env.put("EDDI_NATS_USERNAME", "eddi");
        env.put("EDDI_NATS_PASSWORD", NATS_PASSWORD);
        env.put("EDDI_NATS_REPLICAS", "3");
        // The production recommendation of the review (A8, C2): a node cut off from
        // NATS refuses
        // turns instead of running them unfenced, so every scenario has one contract to
        // check.
        env.put("EDDI_CLUSTER_DEGRADED_TURNS", "reject");
        env.put("EDDI_SHUTDOWN_DRAIN_TIMEOUT_SECONDS", "20");
        if (db == Db.MONGO) {
            env.put("MONGODB_CONNECTIONSTRING", "mongodb://mongodb:27017/eddi?retryWrites=true&w=majority");
        } else {
            env.put("EDDI_DATASTORE_TYPE", "postgres");
            env.put("QUARKUS_PROFILE", "postgres");
            env.put("QUARKUS_DATASOURCE_ACTIVE", "true");
            env.put("QUARKUS_DATASOURCE_DB_KIND", "postgresql");
            env.put("QUARKUS_DATASOURCE_JDBC_URL", "jdbc:postgresql://postgres:5432/eddi");
            env.put("QUARKUS_DATASOURCE_USERNAME", "eddi");
            env.put("QUARKUS_DATASOURCE_PASSWORD", "eddi");
            env.put("QUARKUS_MONGODB_DEVSERVICES_ENABLED", "false");
            env.put("QUARKUS_MONGODB_HEALTH_ENABLED", "false");
        }
        return env;
    }

    // ------------------------------------------------------------------ faults

    private DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    /** SIGSTOP of every process of the node ({@code docker pause}). */
    void freeze(int node) {
        docker().pauseContainerCmd(eddi.get(node).getContainerId()).exec();
        frozen.add(node);
    }

    void thaw(int node) {
        if (frozen.remove(node)) {
            docker().unpauseContainerCmd(eddi.get(node).getContainerId()).exec();
        }
    }

    /** SIGKILL; the node stays dead for the rest of the run. */
    void kill9(int node) {
        thaw(node);
        docker().killContainerCmd(eddi.get(node).getContainerId()).withSignal("KILL").exec();
        dead.add(node);
    }

    boolean alive(int node) {
        return !dead.contains(node);
    }

    /** Latency on everything NATS sends to the node. */
    void latency(int node, int millis, int jitterMillis) {
        forEachProxy(node, p -> toxiproxy.addToxic(p, "latency", "latency", "downstream", Map.of("latency", millis, "jitter", jitterMillis)));
    }

    /**
     * Requests still reach NATS and are applied there; nothing NATS answers reaches
     * the node: a write applied whose reply timed out.
     */
    void dropReplies(int node) {
        forEachProxy(node, p -> toxiproxy.addToxic(p, "drop-replies", "timeout", "downstream", Map.of("timeout", 0)));
    }

    /**
     * The TCP connections stay open and nothing flows either way: a half-open link
     * the NATS client only notices through its pings.
     */
    void halfOpen(int node) {
        forEachProxy(node, p -> {
            toxiproxy.addToxic(p, "half-open-up", "timeout", "upstream", Map.of("timeout", 0));
            toxiproxy.addToxic(p, "half-open-down", "timeout", "downstream", Map.of("timeout", 0));
        });
    }

    /** Closes the node's NATS connections and refuses new ones. */
    void partition(int node) {
        forEachProxy(node, p -> toxiproxy.setEnabled(p, false));
    }

    /** Removes every toxic, re-enables every proxy, thaws every frozen node. */
    void healAll() {
        toxiproxy.reset();
        for (Integer n : new ArrayList<>(frozen)) {
            thaw(n);
        }
    }

    private void forEachProxy(int node, Consumer<String> action) {
        for (int j = 0; j < NODES; j++) {
            action.accept(proxy(node, j));
        }
    }

    /**
     * Asks the leader of every EDDI stream (the KV buckets included) to step down,
     * which forces a leader election on each. Returns the stream names.
     */
    List<String> stepDownStreamLeaders() {
        GenericContainer<?> n = nats.get(0);
        Options options = Options.builder().server("nats://" + n.getHost() + ":" + n.getMappedPort(4222)).userInfo("eddi", NATS_PASSWORD)
                .connectionTimeout(Duration.ofSeconds(5)).build();
        List<String> names = new ArrayList<>();
        try (Connection c = Nats.connect(options)) {
            JetStreamManagement jsm = c.jetStreamManagement();
            for (String stream : jsm.getStreamNames()) {
                if (stream.startsWith("EDDI_") || stream.startsWith("KV_EDDI_")) {
                    names.add(stream);
                    c.request("$JS.API.STREAM.LEADER.STEPDOWN." + stream, new byte[0], Duration.ofSeconds(5));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("could not step down the stream leaders", e);
        }
        return names;
    }

    /** The current leader of a stream, for the result file. */
    String streamLeader(String stream) {
        GenericContainer<?> n = nats.get(0);
        Options options = Options.builder().server("nats://" + n.getHost() + ":" + n.getMappedPort(4222)).userInfo("eddi", NATS_PASSWORD)
                .build();
        try (Connection c = Nats.connect(options)) {
            StreamInfo info = c.jetStreamManagement().getStreamInfo(stream);
            return info.getClusterInfo() == null ? "?" : info.getClusterInfo().getLeader();
        } catch (Exception e) {
            return "unknown (" + e.getMessage() + ")";
        }
    }

    // ------------------------------------------------------------------ REST

    String nodeUrl(int node) {
        GenericContainer<?> c = eddi.get(node);
        return "http://" + c.getHost() + ":" + c.getMappedPort(7070);
    }

    record Response(int status, String body, Map<String, List<String>> headers) {
    }

    Response request(int node, String method, String path, String body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(nodeUrl(node) + path)).timeout(timeout);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body(), r.headers().map());
        } catch (HttpTimeoutException e) {
            return new Response(0, "timeout", Map.of());
        } catch (IOException e) {
            return new Response(0, String.valueOf(e), Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, "interrupted", Map.of());
        }
    }

    /**
     * Sets up and deploys an LLM agent on node 0 and waits until every live node
     * serves it.
     */
    String setupAgent(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentName", name);
        body.put("systemPrompt", "You are a cluster end-to-end test agent.");
        body.put("provider", "openai");
        body.put("model", "mock-1");
        body.put("apiKey", "fixturefixturefixture");
        body.put("baseUrl", "http://mockllm:" + MOCK_PORT + "/v1");
        body.put("deploy", true);
        Response r = request(0, "POST", "/administration/agents/setup", write(body), Duration.ofSeconds(120));
        String agent = null;
        try {
            JsonNode j = JSON.readTree(r.body());
            if (j.hasNonNull("agentId")) {
                agent = j.get("agentId").asText();
            }
        } catch (IOException e) {
            // not JSON: fall back to the location in the body
        }
        if (agent == null) {
            Matcher m = AGENT_ID.matcher(r.body() + " " + r.headers());
            agent = m.find() ? m.group(1) : null;
        }
        if (agent == null) {
            throw new IllegalStateException("agent setup failed: " + r.status() + " " + r.body());
        }
        String id = agent;
        for (int i = 0; i < NODES; i++) {
            int node = i;
            if (!alive(node)) {
                continue;
            }
            boolean ready = waitUntil(Duration.ofSeconds(120), () -> request(node, "GET",
                    "/administration/production/deploymentstatus/" + id + "?version=1", null, Duration.ofSeconds(10)).body().contains("READY"));
            if (!ready) {
                throw new IllegalStateException("agent " + id + " is not deployed on node " + (node + 1));
            }
        }
        return agent;
    }

    String startConversation(int node, String agent) {
        Response r = request(node, "POST", "/agents/" + agent + "/start?userId=e2e-user", null, Duration.ofSeconds(60));
        List<String> location = r.headers().getOrDefault("location", r.headers().getOrDefault("Location", List.of()));
        Matcher m = CONVERSATION_ID.matcher(String.join(" ", location) + " " + r.body());
        if (!m.find()) {
            throw new IllegalStateException("could not start a conversation on node " + (node + 1) + ": " + r.status() + " " + r.body());
        }
        return m.group(1);
    }

    /** One turn, as a client sees it. */
    Turn say(int node, String conversation, String input, Duration timeout) {
        long sent = System.currentTimeMillis();
        Response r = request(node, "POST", "/agents/" + conversation + "?returnCurrentStepOnly=true", write(Map.of("input", input)), timeout);
        long answered = System.currentTimeMillis();
        if (r.status() == 200 && TASK_ERRORS.matcher(r.body()).find()) {
            answeredWithTaskError.add(input);
        }
        return new Turn(conversation, input, "eddi-" + (node + 1), r.status(), sent, answered);
    }

    /**
     * The committed user inputs of a conversation, in step order, read through a
     * live node.
     */
    List<String> committedInputs(String conversation) {
        for (int i = 0; i < NODES; i++) {
            if (!alive(i) || frozen.contains(i)) {
                continue;
            }
            Response r = request(i, "GET", "/agents/" + conversation + "?returnCurrentStepOnly=false", null, Duration.ofSeconds(30));
            if (r.status() != 200) {
                continue;
            }
            try {
                List<String> inputs = new ArrayList<>();
                for (JsonNode o : JSON.readTree(r.body()).path("conversationOutputs")) {
                    String input = o.path("input").asText("");
                    if (!input.isEmpty()) {
                        inputs.add(input);
                    }
                }
                return inputs;
            } catch (IOException e) {
                throw new IllegalStateException("unreadable conversation " + conversation + ": " + r.body(), e);
            }
        }
        throw new IllegalStateException("no live node could read conversation " + conversation);
    }

    String conversationState(int node, String conversation) {
        Response r = request(node, "GET", "/agents/" + conversation + "?returnCurrentStepOnly=true", null, Duration.ofSeconds(30));
        try {
            return JSON.readTree(r.body()).path("conversationState").asText(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Readiness of every live node reports NATS connected. */
    boolean allConnected(Duration timeout) {
        return waitUntil(timeout, () -> {
            for (int i = 0; i < NODES; i++) {
                if (!alive(i)) {
                    continue;
                }
                Response r = request(i, "GET", "/q/health/ready", null, Duration.ofSeconds(5));
                if (r.status() != 200 || !r.body().replace(" ", "").contains("\"nats\":\"CONNECTED\"")) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * The dead letters as the first live node lists them (raw JSON, capped), so a
     * LOST turn in a result file can be told apart from one that was at least
     * dead-lettered.
     */
    String deadLetters() {
        for (int i = 0; i < NODES; i++) {
            if (alive(i) && !frozen.contains(i)) {
                String body = request(i, "GET", "/administration/coordinator/dead-letters", null, Duration.ofSeconds(30)).body();
                return body.length() > 20_000 ? body.substring(0, 20_000) + "..." : body;
            }
        }
        return "";
    }

    String readiness(int node) {
        return request(node, "GET", "/q/health/ready", null, Duration.ofSeconds(5)).body();
    }

    // ------------------------------------------------------------------ evidence

    List<LlmCall> llmCalls() {
        String log = mock.copyFileFromContainer("/tmp/requests.jsonl", in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
        List<LlmCall> calls = new ArrayList<>();
        for (String line : log.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode j = JSON.readTree(line);
                calls.add(new LlmCall(j.path("user").asText(""), j.path("prev_user").asText(""), j.path("role").asText(""),
                        j.path("start").asDouble(), j.path("end").asDouble()));
            } catch (IOException e) {
                // a line cut by a concurrent write is skipped; the next read has it whole
            }
        }
        return calls;
    }

    String logs(int node) {
        try {
            return eddi.get(node).getLogs();
        } catch (RuntimeException e) {
            return "no log: " + e;
        }
    }

    void writeLogs(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (int i = 0; i < NODES; i++) {
            Files.writeString(dir.resolve("eddi-" + (i + 1) + ".log"), logs(i));
        }
        for (int j = 0; j < NODES; j++) {
            try {
                Files.writeString(dir.resolve("nats-" + (j + 1) + ".log"), nats.get(j).getLogs());
            } catch (RuntimeException e) {
                // a NATS container that is gone has no log to save
            }
        }
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean waitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(Duration.ofMillis(500));
        }
        return condition.getAsBoolean();
    }

    static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
