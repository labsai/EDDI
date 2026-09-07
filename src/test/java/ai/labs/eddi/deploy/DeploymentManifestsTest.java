/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the shipped Kubernetes deployment manifests — {@code k8s/},
 * {@code helm/} and the Keycloak realm — against the defects an audit of them
 * found.
 * <p>
 * Every one of those defects was silent. Kustomize exits 0 on a patch that
 * matches nothing; Helm exits 0 on a value no template reads; a probe against a
 * port that serves no health endpoint only shows up as a pod that never becomes
 * Ready. Nothing in the build ever opened these files, so a manifest could be
 * wrong for as long as nobody tried to deploy it — and two of the shipped
 * examples had never built at all.
 * <p>
 * This test is deliberately assertion-heavy and text-level where the file is a
 * Go template rather than YAML. It is not a substitute for {@code kubectl
 * kustomize} and {@code helm template} in CI; it is the part of that coverage
 * that can run in a plain unit build with no cluster tooling installed.
 */
@DisplayName("deployment manifests")
class DeploymentManifestsTest {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path K8S = Path.of("k8s");
    private static final Path HELM = Path.of("helm", "eddi");
    private static final Path HELM_TEMPLATES = HELM.resolve("templates");

    /**
     * The overlays that carry no base of their own and must therefore be
     * components.
     */
    private static final List<String> COMPONENT_OVERLAYS = List.of("auth", "nats", "production", "monitoring", "ingress");

    /** The overlays that include the base and are applied directly. */
    private static final List<String> STANDALONE_OVERLAYS = List.of("mongodb", "postgres");

    private static final Path COMPOSE_REALM = Path.of("keycloak", "eddi-realm.json");
    private static final Path KUSTOMIZE_REALM = K8S.resolve("overlays/auth/eddi-realm.json");
    private static final Path HELM_REALM = HELM.resolve("files/eddi-realm.json");

    private static final Path KUSTOMIZE_KEYCLOAK = K8S.resolve("overlays/auth/keycloak-statefulset.yaml");
    private static final Path HELM_KEYCLOAK = HELM_TEMPLATES.resolve("keycloak.yaml");
    private static final Path AUTH_COMPONENT = K8S.resolve("overlays/auth/kustomization.yaml");

    private static final Path CI = Path.of(".github", "workflows", "ci.yml");

    private static final Path CREATE_SECRETS_SH = K8S.resolve("create-secrets.sh");
    private static final Path CREATE_SECRETS_PS1 = K8S.resolve("create-secrets.ps1");

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * The only reader of {@code EDDI_DATASTORE_TYPE}. The chart's guard is written
     * to the comparison in here, so the two are asserted against each other rather
     * than each against a remembered description of the other.
     */
    private static final Path DATASTORE_PRODUCERS = Path.of("src", "main", "java", "ai", "labs", "eddi", "datastore", "DataStoreProducers.java");

    /**
     * The only reader of the MongoDB connection string. Its
     * {@code @ConfigProperty(name = …)} is the authoritative spelling of the key an
     * operator is told to put in the vault Secret, so the manifests are asserted
     * against it rather than against a spelling remembered here.
     */
    private static final Path PERSISTENCE_MODULE = Path.of("src", "main", "java", "ai", "labs", "eddi", "datastore", "bootstrap",
            "PersistenceModule.java");

    private static final Path K8S_DOC = Path.of("docs", "kubernetes.md");
    private static final Path README = Path.of("README.md");
    private static final Path SECURITY_DOC = Path.of("docs", "security.md");

    /**
     * Every document this suite makes an assertion about, and therefore every
     * document ci.yml's {@code operator_docs} path filter has to list —
     * build-and-test is what runs these guards, and an assertion the file it reads
     * cannot trigger is not a guard. Kept as one list so a fourth guarded document
     * is added here rather than remembered separately by
     * {@link #ciRunsTheTestsOnOperatorDocChanges()}, which is how docs/security.md
     * came to be asserted about but never filtered on.
     */
    private static final List<Path> CI_FILTERED_DOCS = List.of(K8S_DOC, README, SECURITY_DOC);

    /**
     * Every operator-facing copy of the same Kubernetes instructions.
     * <p>
     * README prints the same two `kubectl apply` commands as docs/kubernetes.md and
     * is the repository's front door, but the drift guards below used to read only
     * docs/kubernetes.md — so README kept a claim that had already been corrected
     * in the other copy, and kept sending readers at commands that had silently
     * gained a mandatory prerequisite. A doc assertion that names one file cannot
     * see the second copy; these sweep both.
     */
    private static final List<Path> OPERATOR_DOCS = List.of(K8S_DOC, README);

    // ─────────────────────────────────────────────────────────────
    // Kustomize composition
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("kustomize composition")
    class KustomizeComposition {

        /**
         * A {@code resources:} entry may be a directory outside the kustomization root
         * — kustomize builds it as its own root — but never a loose FILE outside the
         * root. Both shipped examples referenced files that way and therefore failed to
         * build at all: {@code security; file '...' is not in or below '...'}. They
         * were printed in docs/kubernetes.md as ready-made recipes.
         */
        @Test
        @DisplayName("no kustomization references a file outside its own root")
        void noEscapingFileResources() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (Path kustomization : kustomizations()) {
                for (String entry : stringList(YAML.readTree(kustomization.toFile()).get("resources"))) {
                    boolean escapes = entry.startsWith("../");
                    boolean isFile = entry.endsWith(".yaml") || entry.endsWith(".yml");
                    if (escapes && isFile) {
                        offenders.add(kustomization + " -> " + entry);
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "kustomize refuses a `resources:` entry that is a file outside the root; "
                            + "reference the overlay DIRECTORY (or list it under `components:`) instead. Offenders: "
                            + offenders);
        }

        /**
         * The component overlays exist to patch resources they do not own — eddi-config
         * and the eddi Deployment. As {@code kind: Kustomization} referenced under
         * {@code resources:} each was built as an independent root first, so those
         * patches matched zero objects and were dropped without a word: OIDC stayed
         * disabled, messaging stayed in-memory, and the production resource limits
         * never applied.
         */
        @Test
        @DisplayName("component overlays declare kind: Component")
        void componentOverlaysAreComponents() throws IOException {
            for (String overlay : COMPONENT_OVERLAYS) {
                Path kustomization = K8S.resolve("overlays").resolve(overlay).resolve("kustomization.yaml");
                JsonNode root = YAML.readTree(kustomization.toFile());
                assertEquals("Component", root.path("kind").asText(),
                        kustomization + " must be a kustomize Component — its patches target resources it does "
                                + "not own, and a Kustomization referenced under `resources:` applies them to its "
                                + "own isolated resource set, where they match nothing and are silently dropped");
                assertEquals("kustomize.config.k8s.io/v1alpha1", root.path("apiVersion").asText(),
                        kustomization + " declares kind: Component, which lives in the v1alpha1 API group");
            }
        }

        @Test
        @DisplayName("standalone overlays stay applyable kustomizations that include the base")
        void standaloneOverlaysIncludeBase() throws IOException {
            for (String overlay : STANDALONE_OVERLAYS) {
                Path kustomization = K8S.resolve("overlays").resolve(overlay).resolve("kustomization.yaml");
                JsonNode root = YAML.readTree(kustomization.toFile());
                assertEquals("Kustomization", root.path("kind").asText(), kustomization + " is applied directly");
                assertTrue(stringList(root.get("resources")).contains("../../base"),
                        kustomization + " must include ../../base — it is the entry point operators apply");
            }
        }

        /**
         * The examples are the only place the composition pattern is demonstrated end
         * to end, so they have to demonstrate the pattern that works.
         */
        @Test
        @DisplayName("examples compose components under components:, not resources:")
        void examplesUseComponents() throws IOException {
            for (String example : List.of("mongodb-full", "postgres-ha")) {
                Path kustomization = K8S.resolve("examples").resolve(example).resolve("kustomization.yaml");
                JsonNode root = YAML.readTree(kustomization.toFile());
                List<String> components = stringList(root.get("components"));
                assertFalse(components.isEmpty(), kustomization + " must pull its component overlays in under "
                        + "`components:` so their patches reach eddi-config and the eddi Deployment");
                for (String component : components) {
                    assertTrue(component.startsWith("../../overlays/"),
                            kustomization + " lists an unexpected component: " + component);
                }
                assertEquals(1, stringList(root.get("resources")).size(),
                        kustomization + " should reference exactly one standalone overlay under `resources:`");
            }
        }

        @Test
        @DisplayName("docs teach the components pattern, not the file-reference one that cannot build")
        void docsTeachWorkingComposition() throws IOException {
            String doc = read(Path.of("docs", "kubernetes.md"));
            assertFalse(doc.contains("overlays/auth/keycloak-deployment.yaml"),
                    "docs/kubernetes.md still tells readers to reference an overlay FILE from outside its root, "
                            + "which is the exact accumulation error that made both shipped examples fail");
            assertTrue(doc.contains("components:"),
                    "docs/kubernetes.md must show the `components:` composition, since that is the only shape "
                            + "in which the overlays' patches actually apply");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Secrets
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("vault secret")
    class VaultSecret {

        /**
         * A Secret that kustomize (or a re-applied all-in-one manifest) reconciles
         * overwrites the operator's vault master key with the shipped placeholder. On a
         * first install that means running with a key published in this repository; on
         * any later apply it destroys the live key and every secret encrypted under it
         * becomes permanently undecryptable.
         */
        @Test
        @DisplayName("no shipped manifest creates the eddi-secrets Secret")
        void noShippedEddiSecret() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (Path manifest : manifestsUnder(K8S)) {
                for (JsonNode doc : yamlDocuments(manifest)) {
                    boolean isSecret = "Secret".equals(doc.path("kind").asText());
                    boolean isEddiSecrets = "eddi-secrets".equals(doc.path("metadata").path("name").asText());
                    if (isSecret && isEddiSecrets) {
                        offenders.add(manifest.toString());
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "eddi-secrets must be created out-of-band (k8s/create-secrets.sh) so that no apply can "
                            + "overwrite a live vault master key. Offenders: " + offenders);
        }

        @Test
        @DisplayName("the base secret survives only as a non-applied template")
        void baseSecretIsATemplateOnly() throws IOException {
            assertFalse(Files.exists(K8S.resolve("base/eddi-secret.yaml")),
                    "k8s/base/eddi-secret.yaml would be picked up by `kubectl apply -f k8s/base/`; "
                            + "the template lives at eddi-secret.yaml.example");
            assertTrue(Files.exists(K8S.resolve("base/eddi-secret.yaml.example")),
                    "the commented Secret template should stay as documentation");
            List<String> baseResources = stringList(YAML.readTree(K8S.resolve("base/kustomization.yaml").toFile()).get("resources"));
            assertFalse(baseResources.stream().anyMatch(r -> r.contains("eddi-secret")),
                    "k8s/base/kustomization.yaml must not apply the Secret template");
        }

        /**
         * Dropping the Secret from the manifests closed the {@code kubectl apply -k}
         * vector, and the docs now route every install through create-secrets. That
         * script installs a NEW key and used to delete whatever was already there first
         * — so re-running it on a live install destroyed exactly the data the manifest
         * fix was written to protect, just through a different door.
         */
        @Test
        @DisplayName("the secret generator refuses to replace a live master key")
        void secretGeneratorRefusesToClobberLiveKey() throws IOException {
            String bash = stripComments(read(K8S.resolve("create-secrets.sh")));
            assertTrue(bash.contains("kubectl get secret eddi-secrets"),
                    "create-secrets.sh must check for an existing eddi-secrets before installing a new key");
            assertTrue(bash.contains("--force"),
                    "create-secrets.sh must offer --force as the deliberate way to rotate");

            String pwsh = stripComments(read(K8S.resolve("create-secrets.ps1")));
            assertTrue(pwsh.contains("kubectl get secret eddi-secrets"),
                    "create-secrets.ps1 must make the same check — the two scripts are documented as "
                            + "equivalent and must not differ on whether they can destroy a key");
            assertTrue(pwsh.contains("[switch]$Force"),
                    "create-secrets.ps1 must offer -Force as the deliberate way to rotate");
        }

        /**
         * The script's own banner advertised "Optional PostgreSQL credentials" it has
         * never created — the body writes only {@code eddi.vault.master-key=}, and the
         * PostgreSQL credentials are a separate manifest whose header the same branch
         * rewrote to say "change these BEFORE the first apply". An operator reading the
         * header of the now-mandatory script reasonably concludes the script seeds them
         * and skips that file, and the postgres image reads its password only during
         * initdb — so by the time the mistake shows up it cannot be undone without
         * touching the database by hand.
         */
        @Test
        @DisplayName("the generator's header describes only what it creates")
        void secretGeneratorHeaderDoesNotPromisePostgresCredentials() throws IOException {
            for (Path script : List.of(K8S.resolve("create-secrets.sh"), K8S.resolve("create-secrets.ps1"))) {
                String code = read(script);
                assertFalse(code.contains("- Optional PostgreSQL credentials"),
                        script + " advertises PostgreSQL credentials in its header that it never creates: the "
                                + "body writes eddi.vault.master-key and nothing else. They live in "
                                + "k8s/overlays/postgres/postgres-secret.yaml, which has to be edited BEFORE "
                                + "the first apply");
                assertTrue(code.contains("postgres-secret.yaml"),
                        script + " should point at k8s/overlays/postgres/postgres-secret.yaml instead, since "
                                + "the reader was looking for exactly that");
            }
        }

        /**
         * Presence is not the property that protects the key — ORDER is, and so is the
         * CONDITION. Both scripts still carry the pre-existing
         * {@code kubectl delete secret eddi-secrets --ignore-not-found}, and they have
         * to: that delete is what makes {@code --force} able to rotate at all. But it
         * belongs to {@code --force} and to nothing else. Unconditional, it was a
         * destructive step justified by a read that had already gone stale — the probe,
         * the key generation and an interactive passphrase prompt all sit in between,
         * and a Secret created inside that window was erased by a run that never asked
         * to rotate anything.
         * <p>
         * Both halves are asserted because either alone is satisfiable while the key is
         * at risk: a delete that runs before the guard destroys the key it was meant to
         * find, and a delete outside the force branch destroys one the guard never saw.
         * <p>
         * This is the one data-destroying path in these manifests: the key it removes
         * is the one the file's own banner calls UNRECOVERABLE, so the assertions are
         * on the offsets and the enclosing condition, not on the words.
         */
        @Test
        @DisplayName("the generator deletes a live key only after checking, and only when forced")
        void secretGeneratorChecksBeforeItDeletes() throws IOException {
            // The force switch as each shell spells it. Case tells the two apart, which
            // is why the lookup is per-script rather than one string for both.
            Map<Path, String> forceGate = new LinkedHashMap<>();
            forceGate.put(K8S.resolve("create-secrets.sh"), "$FORCE");
            forceGate.put(K8S.resolve("create-secrets.ps1"), "$Force)");

            for (Map.Entry<Path, String> entry : forceGate.entrySet()) {
                Path script = entry.getKey();
                String code = stripComments(read(script));
                int guard = code.indexOf("kubectl get secret eddi-secrets");
                int destroy = code.indexOf("kubectl delete secret eddi-secrets");

                assertTrue(guard >= 0, script + " must look for an existing eddi-secrets before installing a key");
                assertTrue(destroy >= 0,
                        script + " must keep the delete-then-create — it is what makes the documented "
                                + "--force rotation work, and a guard in front of nothing guards nothing");
                assertTrue(guard < destroy,
                        script + " deletes the live eddi-secrets (offset " + destroy + ") before it checks "
                                + "whether one exists (offset " + guard + "). The check has to come first: by "
                                + "the time the delete has run the master key is gone and everything encrypted "
                                + "under it is permanently undecryptable");
                assertTrue(code.substring(guard, destroy).contains("exit 1"),
                        script + " finds an existing eddi-secrets and then carries on to the delete anyway; "
                                + "the guard has to abort, not warn");

                // The condition immediately in front of the delete, not merely somewhere
                // in the file: `--force` is parsed at the top of both scripts, so a
                // whole-file search for the switch matches the argument parser and says
                // nothing about what gates the delete.
                String justBefore = code.substring(Math.max(0, destroy - 200), destroy);
                assertTrue(justBefore.contains(entry.getValue()),
                        script + " reaches `kubectl delete secret eddi-secrets` without " + entry.getValue()
                                + " gating it — the 200 characters in front of the delete are: "
                                + justBefore.strip() + ". An unconditional delete is justified only by the "
                                + "probe above having found nothing, and that read is already stale by the "
                                + "time it runs: the key generation and an interactive passphrase prompt sit "
                                + "in between, which is room enough for another installer to create the "
                                + "Secret this run then erases. The delete belongs to the force path; normal "
                                + "creation relies on `kubectl create` refusing with AlreadyExists");
            }
        }

        /**
         * A guard that cannot see the cluster is not a guard. Both scripts discarded
         * the output AND the exit status of every kubectl call: the {@code get} that
         * decides whether a key already exists treated a wrong kube-context, an expired
         * token, an RBAC denial and an unreachable API server as "no Secret there" and
         * walked on into the delete, and the {@code create} that installs the key
         * reported success either way — PowerShell printed the green tick and the "Save
         * this key!" box for a Secret that was never created, so the operator filed the
         * key and watched the pod sit in ContainerCreating with a key that exists
         * nowhere. ($ErrorActionPreference does not apply to native commands, and bash
         * cannot see a status it redirected to /dev/null and then ignored.)
         * <p>
         * These scripts are now the ONLY way to get a vault master key into a cluster,
         * so both directions matter: never destroy on an unverified read, never claim
         * success on a failed write.
         */
        @Test
        @DisplayName("the generator checks whether kubectl actually succeeded")
        void secretGeneratorChecksKubectlExitStatus() throws IOException {
            String bash = stripComments(read(K8S.resolve("create-secrets.sh")));
            int create = bash.indexOf("kubectl create secret generic eddi-secrets");
            assertTrue(create >= 0, "create-secrets.sh must still create the Secret");
            String createCall = bash.substring(create, Math.min(bash.length(), create + 220));
            assertFalse(createCall.contains("/dev/null"),
                    "create-secrets.sh discards the output of `kubectl create secret`, so under `set -e` a "
                            + "failed create aborts with no message at all — right after printing 'Creating "
                            + "eddi-secrets... ' and with nothing to say why. Capture stderr and print it: "
                            + createCall.strip());
            assertTrue(bash.contains("if ! create_error=$(kubectl create secret generic eddi-secrets"),
                    "create-secrets.sh must inspect the result of the create and report the kubectl error, "
                            + "rather than printing the key box for a Secret that may not exist");
            assertTrue(bash.contains("secret_probe=$(kubectl get secret eddi-secrets"),
                    "create-secrets.sh must capture the output of its existence check so a failure can be "
                            + "told apart from a genuine NotFound");
            assertTrue(bash.contains("(NotFound)"),
                    "only a real NotFound may count as 'no key there'; every other kubectl failure has to "
                            + "abort before the delete");
            // The failure branch runs BEFORE the key box. Saying "the key above" sends
            // the operator looking for a value that was never printed — the only
            // earlier output is the one-line "Using provided vault key" notice — and
            // invites them to believe a key they cannot see was generated and lost.
            assertFalse(bash.contains("the key above was not installed"),
                    "create-secrets.sh's create-failure message points at a key printed 'above'; nothing "
                            + "above that line prints one. The value appears only in the box AFTER a "
                            + "successful create, which this branch never reaches");

            String pwsh = stripComments(read(K8S.resolve("create-secrets.ps1")));
            assertEquals(2, countOccurrences(pwsh, "$LASTEXITCODE"),
                    "create-secrets.ps1 must check $LASTEXITCODE for BOTH kubectl calls — the existence probe "
                            + "and the create. $ErrorActionPreference = 'Stop' does not apply to native "
                            + "commands, so without it a failed create still prints the success tick and the "
                            + "'Save this key!' box");
            assertTrue(pwsh.contains("kubectl create secret failed"),
                    "create-secrets.ps1 must say the create failed instead of reporting success");
            // Escaped, because PowerShell's -match takes a regex and the parentheses
            // are the part being matched literally.
            assertTrue(pwsh.contains("\\(NotFound\\)"),
                    "create-secrets.ps1's existence probe must distinguish NotFound from an API/RBAC error "
                            + "and refuse to continue on the latter");
        }

        /**
         * "Absent" has to be recognised by kubectl's STRUCTURED reason — the
         * parenthesised {@code (NotFound)} that every
         * {@code Error from server (NotFound): secrets "eddi-secrets" not found}
         * carries — and by nothing looser. Matching prose let an UNREACHABLE cluster in
         * through the side door: kubectl answers a DNS failure with
         * {@code Unable to connect to the server: dial tcp: lookup <host>: no such
         * host}, and the alternation {@code 'not found|no such|notfound'} read that as
         * "there is no Secret here". The script then prompted for a key and walked into
         * the delete, which is unconditional once the guard is passed — so if the name
         * resolved again in between (a VPN reconnecting is enough), it destroyed a live
         * master key on a cluster the probe had never seen. That is precisely the
         * scenario the fail-closed rewrite was written to close, reopened by the
         * pattern that implements it.
         * <p>
         * Nothing kubectl prints for a missing object omits the reason in parentheses,
         * so the tight match loses no genuine NotFound.
         */
        @Test
        @DisplayName("the generator's absence check matches kubectl's reason, not English")
        void secretGeneratorDoesNotReadAnUnreachableClusterAsEmpty() throws IOException {
            for (Path script : List.of(K8S.resolve("create-secrets.sh"), K8S.resolve("create-secrets.ps1"))) {
                // Backslashes dropped so one assertion covers both spellings: bash
                // matches the literal `(NotFound)`, PowerShell the regex `\(NotFound\)`.
                String code = stripComments(read(script)).replace("\\", "");
                assertFalse(code.contains("no such"),
                        script + "'s existence probe matches the prose 'no such', which is what kubectl says "
                                + "when it cannot RESOLVE the API server: \"Unable to connect to the server: "
                                + "dial tcp: lookup <host>: no such host\". A cluster it never reached would "
                                + "again count as a cluster with no key in it, and the delete below the guard "
                                + "is unconditional. Match the structured reason '(NotFound)' instead");
                assertTrue(code.contains("(NotFound)"),
                        script + " must classify absence on kubectl's parenthesised reason. Anything looser "
                                + "eventually matches a transport error, and this guard is the only thing "
                                + "standing between a re-run and an unrecoverable vault key");
            }
        }

        /**
         * {@code #Requires -Version 7}, read raw: {@code stripComments} would drop it,
         * and it is the one comment-shaped line in the file that is load-bearing.
         * <p>
         * Two things in create-secrets.ps1 are PowerShell 7 only, and Windows
         * PowerShell 5.1 is what {@code powershell.exe} — the shell a stock Windows box
         * gives you — still runs. {@code [RandomNumberGenerator]::Fill} is .NET Core
         * only, so the auto-generate path has never worked there ("does not contain a
         * method named 'Fill'"); and 7's native-command redirection is what makes
         * {@code $probe = kubectl … 2>&1} a string. Under 5.1 that same capture turns
         * each stderr line into an ErrorRecord, so with
         * {@code $ErrorActionPreference = 'Stop'} the very first
         * {@code Error from server (NotFound)} — the line EVERY first install produces
         * — throws a terminating RemoteException before the NotFound check can classify
         * it. Reproduced on 5.1.26100: the probe threw; the same script under pwsh
         * 7.6.5 captured the text and carried on.
         * <p>
         * Declared rather than worked around, because the failure it prevents is
         * half-finished: this script is the only supported way to install a vault
         * master key, and a key that was never created is indistinguishable afterwards
         * from one that was. docs/kubernetes.md prints the command, so it has to say
         * pwsh too — a requirement the reader only meets by accident is not one.
         */
        @Test
        @DisplayName("the PowerShell generator declares the shell it actually needs")
        void powerShellGeneratorDeclaresItsVersionRequirement() throws IOException {
            String pwsh = read(K8S.resolve("create-secrets.ps1"));
            assertTrue(pwsh.startsWith("#Requires -Version 7"),
                    "k8s/create-secrets.ps1 must open with `#Requires -Version 7`. Under Windows PowerShell "
                            + "5.1 — what powershell.exe runs on a stock Windows box — its `2>&1` probe throws "
                            + "a terminating RemoteException on the NotFound line every first install "
                            + "produces, and New-RandomKey calls a .NET Core-only method. Without the "
                            + "declaration both surface as raw .NET noise partway through installing an "
                            + "unrecoverable key");

            String doc = read(K8S_DOC);
            assertTrue(doc.contains("pwsh -File .\\k8s\\create-secrets.ps1"),
                    K8S_DOC + " prints the PowerShell command that operators actually run, so it must invoke "
                            + "it with pwsh. `.\\k8s\\create-secrets.ps1` on a stock Windows box is Windows "
                            + "PowerShell 5.1, which the script now refuses — a refusal the docs have to "
                            + "explain rather than deliver");
            assertTrue(doc.contains("#Requires -Version 7"),
                    K8S_DOC + " must say WHY PowerShell 7 is needed next to the command; a version "
                            + "requirement discovered from an error message is a support ticket");

            // One rewritten call site is not the fix. The #Requires landed with only
            // the quickstart command changed, so the troubleshooting section, the
            // --force ROTATION path, README's install block and the script's own
            // header and -Help text all still printed the bare form — which is the one
            // an operator copies when something has already gone wrong. Every
            // occurrence has to carry pwsh, in every file that prints one.
            for (Path file : List.of(K8S_DOC, README, K8S.resolve("create-secrets.ps1"))) {
                String text = read(file);
                String invocation = ".\\k8s\\create-secrets.ps1";
                String qualified = "pwsh -File " + invocation;
                for (int at = text.indexOf(invocation); at >= 0; at = text.indexOf(invocation, at + 1)) {
                    int start = at - "pwsh -File ".length();
                    assertTrue(start >= 0 && text.startsWith(qualified, start),
                            file + " prints a bare `" + invocation + "` at offset " + at + ". On a stock "
                                    + "Windows box that starts Windows PowerShell 5.1, which the script's "
                                    + "`#Requires -Version 7` now refuses outright — so this line hands the "
                                    + "reader a command that cannot run. Print `" + qualified + "`");
                }
            }
        }

        /**
         * The documented inline snippets must fail closed, exactly like the scripts.
         * <p>
         * These three copies were once made "idempotent" by piping the create through
         * {@code --dry-run=client -o yaml | kubectl apply -f -}. That is the wrong kind
         * of idempotent for this Secret: every snippet begins with
         * {@code key=$(openssl rand -base64 24)}, so each run mints a NEW master key
         * and the apply REPLACES the live one — silently, exit 0. Everything sealed
         * under the previous key is then permanently undecryptable, which is the
         * outcome dropping the Secret from the manifests was meant to close. A re-run
         * is not hypothetical: it is what a reader does after a later step in the same
         * fenced block fails, or on a second cluster session.
         * <p>
         * A bare {@code kubectl create} refusing with AlreadyExists IS the idempotency
         * wanted here, and the explicit {@code kubectl get} guard in front of it says
         * so before the key is even generated — the same order create-secrets.sh uses.
         * Rotation stays possible, deliberately, through
         * {@code create-secrets.sh --force}.
         */
        @Test
        @DisplayName("every documented secret-creation snippet refuses to replace a live key")
        void secretCreationSnippetsRefuseToClobberALiveKey() throws IOException {
            List<Path> sources = List.of(
                    K8S_DOC,
                    K8S.resolve("quickstart.yaml"),
                    K8S.resolve("base/eddi-secret.yaml.example"));
            for (Path source : sources) {
                String text = read(source);
                int creates = countOccurrences(text, "kubectl create secret generic eddi-secrets");
                assertTrue(creates > 0, source + " should keep documenting how to create eddi-secrets by hand");

                assertEquals(0, countOccurrences(text, "--dry-run=client -o yaml | kubectl apply -f -"),
                        source + " pipes `kubectl create secret generic eddi-secrets` through "
                                + "`--dry-run=client -o yaml | kubectl apply -f -`. Every one of these snippets "
                                + "generates a fresh random key first, so that pipe REPLACES a live vault master "
                                + "key on a re-run and makes everything encrypted under it permanently "
                                + "undecryptable — with no prompt and exit 0. A bare `kubectl create` refuses "
                                + "with AlreadyExists, which is the behaviour this one Secret needs");

                int guards = countOccurrences(text, "kubectl get secret eddi-secrets");
                assertTrue(guards >= creates,
                        source + " has " + creates + " `kubectl create secret generic eddi-secrets` snippet(s) "
                                + "but only " + guards + " `kubectl get secret eddi-secrets` guard(s) in front of "
                                + "them. AlreadyExists as the only backstop tells the reader what went wrong "
                                + "after they have already generated the key; the guard says it first");

                int guard = text.indexOf("kubectl get secret eddi-secrets");
                int create = text.indexOf("kubectl create secret generic eddi-secrets");
                assertTrue(guard < create,
                        source + " creates the Secret (offset " + create + ") before it checks whether one "
                                + "exists (offset " + guard + "); the check has to come first, as it does in "
                                + "k8s/create-secrets.sh");
            }
        }

        /**
         * The Secret is a PROPERTIES FILE, and MicroProfile Config looks its keys up
         * case-sensitively from a file source — there is no relaxed-name matching the
         * way there is for the {@code MONGODB_CONNECTIONSTRING} environment variable,
         * whose mapping is what makes the lowercase spelling look plausible. So
         * {@code mongodb.connectionstring=…} in the file an operator hand-writes is not
         * a typo that fails: it is a line EDDI never reads. The property keeps
         * application.properties' baked-in {@code mongodb://mongodb:27017/eddi}, which
         * resolves to nothing outside the chart's own Service, and the pod dies on the
         * readiness probe with a DNS failure — the identical symptom the datastore
         * guards in the chart exist to refuse, arrived at through the one file no guard
         * renders.
         * <p>
         * eddi-secret.yaml.example shipped exactly that lowercase spelling. Pinned
         * against {@code PersistenceModule}'s own annotation rather than a spelling
         * repeated here, so the doc cannot drift from the reader in either direction,
         * and every case-variant occurrence is rejected rather than merely requiring
         * the correct one to appear somewhere.
         */
        @Test
        @DisplayName("the documented Secret keys are spelled the way EDDI reads them")
        void documentedSecretPropertyNamesMatchTheConfigPropertiesEddiReads() throws IOException {
            String property = captureAfter(read(PERSISTENCE_MODULE),
                    "@ConfigProperty\\(name = \"(mongodb\\.[A-Za-z]+)\"\\)");
            assertEquals("mongodb.connectionString", property,
                    PERSISTENCE_MODULE + " no longer reads @ConfigProperty(name = \"mongodb.connectionString\"). "
                            + "That annotation is the authoritative spelling of the key operators are told to "
                            + "put in eddi-secrets; if it changed, every manifest and doc below has to change "
                            + "with it. Found: \"" + property + "\"");

            List<Path> sources = List.of(
                    K8S.resolve("base/eddi-secret.yaml.example"),
                    HELM_TEMPLATES.resolve("secret.yaml"));
            for (Path source : sources) {
                String text = read(source);
                assertTrue(text.contains(property),
                        source + " documents the vault Secret's contents but never names " + property + ", "
                                + "which is the key an external MongoDB is configured with");

                // Case-insensitive sweep: requiring the correct spelling to appear
                // somewhere would pass a file that ALSO prints the lowercase one, and
                // the lowercase one is what a reader copies.
                Matcher variants = Pattern.compile(Pattern.quote(property), Pattern.CASE_INSENSITIVE).matcher(text);
                while (variants.find()) {
                    assertEquals(property, variants.group(),
                            source + " spells the property `" + variants.group() + "` at offset "
                                    + variants.start() + ". The Secret is a properties FILE, and MicroProfile "
                                    + "Config matches file keys case-sensitively — only the env-var form "
                                    + "MONGODB_CONNECTIONSTRING is relaxed. A lowercased key is silently never "
                                    + "read: EDDI keeps the baked-in mongodb://mongodb:27017/eddi and the pod "
                                    + "dies on the readiness probe. It must be exactly `" + property + "`, as "
                                    + PERSISTENCE_MODULE + " declares it");
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Secret generator — behaviour, not source text
    // ─────────────────────────────────────────────────────────────

    /**
     * The one data-destroying path in this repository, run rather than grepped.
     * <p>
     * Everything in {@link VaultSecret} that concerns the fail-closed guard reads
     * the two scripts as SOURCE TEXT — {@code code.contains("(NotFound)")},
     * {@code !code.contains("no such")}, two occurrences of {@code $LASTEXITCODE}.
     * Every one of those assertions survives the mutation that matters. Invert
     * PowerShell's {@code -notmatch '\(NotFound\)'} to {@code -match}, or swap the
     * {@code if}/{@code elif} arms of the bash guard, and the strings are all still
     * in the file, spelled exactly as the greps demand, while the classification
     * they implement has been turned inside out: a cluster the probe could not
     * reach is read as a cluster with no key in it, and the unconditional
     * {@code kubectl delete secret eddi-secrets} below the guard destroys a live
     * vault master key. Nothing encrypted under it can be recovered.
     * <p>
     * So the classification is exercised instead. The scripts talk to the cluster
     * through exactly one binary, and PATH decides which: a stand-in
     * {@code kubectl} placed first answers the existence probe with a chosen exit
     * status and a chosen stderr line, and appends every invocation it receives to
     * a log. That log is the assertion that source text cannot make — whether the
     * run reached the delete. Three outcomes, one per arm of the guard:
     * <ul>
     * <li>exit 0 — a Secret is there. Refuse, and do not delete.</li>
     * <li>nonzero carrying kubectl's structured {@code (NotFound)} — genuinely
     * absent. Proceed, delete (harmless, nothing is there) and create.</li>
     * <li>any other nonzero — unreachable, wrong context, RBAC denial. Abort BEFORE
     * the delete, whatever the prose says. {@code no such host} is the one that
     * matters: it is what a DNS failure looks like, and the loose
     * {@code 'not found|no such|notfound'} alternation this branch replaced read it
     * as "there is no Secret here".</li>
     * </ul>
     * No cluster, no network and no container: {@code kubectl} is never the real
     * one. The shells are, which is why each test skips rather than fails where its
     * interpreter is absent — CI's ubuntu-latest runner has both.
     */
    @Nested
    @DisplayName("secret generator — fail-closed classification")
    class SecretGeneratorBehaviour {

        @Test
        @DisplayName("create-secrets.sh refuses to delete a key it could not check for")
        void shellGeneratorAbortsBeforeTheDeleteUnlessTheProbeSaysNotFound() throws Exception {
            GeneratorRun exists = runShellGenerator("exists");
            assertNotEquals(0, exists.exitCode(),
                    CREATE_SECRETS_SH + " found an existing eddi-secrets (the probe exited 0) and did not "
                            + "abort. Installing over it replaces the vault master key and everything "
                            + "encrypted under the old one becomes permanently undecryptable — only --force "
                            + "may do that. " + exists);
            assertTrue(exists.output().contains("already exists in namespace"),
                    CREATE_SECRETS_SH + " must say the Secret already exists and point at --force, so the "
                            + "operator knows nothing was changed rather than guessing. " + exists);
            assertFalse(exists.issued("delete secret eddi-secrets"),
                    CREATE_SECRETS_SH + " ran `kubectl delete secret eddi-secrets` on a cluster that HAS one, "
                            + "without --force. That delete is the irreversible step; the guard exists to sit "
                            + "in front of it. " + exists);

            for (String unreadable : List.of("unreachable", "forbidden")) {
                GeneratorRun run = runShellGenerator(unreadable);
                assertNotEquals(0, run.exitCode(),
                        CREATE_SECRETS_SH + " carried on after a probe that failed without kubectl's "
                                + "(NotFound) reason (stub mode `" + unreadable + "`). A wrong kube-context, "
                                + "an expired token, an RBAC denial and an unresolvable API server all look "
                                + "like this, and none of them means 'no key there'. " + run);
                assertTrue(run.output().contains("Refusing to continue"),
                        CREATE_SECRETS_SH + " aborted without saying why (stub mode `" + unreadable + "`); the "
                                + "operator has to be told the check failed, not that the key was installed. "
                                + run);
                assertFalse(run.issued("delete secret eddi-secrets"),
                        CREATE_SECRETS_SH + " reached `kubectl delete secret eddi-secrets` on a cluster it "
                                + "could not read (stub mode `" + unreadable + "`). This is the exact path the "
                                + "fail-closed rewrite closed: the probe never saw the cluster, and if the "
                                + "name resolves again before the delete — a VPN reconnecting is enough — a "
                                + "live master key is destroyed. " + run);
                assertFalse(run.issued("create secret generic eddi-secrets"),
                        CREATE_SECRETS_SH + " installed a new key after a failed probe (stub mode `"
                                + unreadable + "`). " + run);
            }
        }

        /**
         * The other half, and not a formality: a guard that aborted on everything would
         * satisfy every assertion above while making a first install impossible. Only
         * kubectl's structured {@code (NotFound)} may be read as absent — and it must
         * be.
         * <p>
         * And on that path it must NOT delete. "Absent at probe time" is not "nothing
         * to lose at delete time": the probe, the key generation and — on the
         * custom-passphrase path — an unbounded {@code read} waiting on a human all sit
         * between the two, and a second installer creating eddi-secrets inside that
         * window had it erased by a run that never passed {@code --force}. The
         * assertion used to be the opposite one, requiring the delete on exactly this
         * path, which is what made the race a codified requirement rather than a bug.
         * Idempotency on the normal path comes from {@code kubectl create} refusing
         * with AlreadyExists, which the API server evaluates against the live object at
         * the moment of the write.
         */
        @Test
        @DisplayName("create-secrets.sh installs the key on a genuine NotFound, and deletes nothing")
        void shellGeneratorProceedsOnAStructuredNotFound() throws Exception {
            GeneratorRun absent = runShellGenerator("notfound");
            assertEquals(0, absent.exitCode(),
                    CREATE_SECRETS_SH + " refused to install a key on a cluster whose probe returned "
                            + "kubectl's genuine `Error from server (NotFound)`. That is every first install; "
                            + "a guard that also blocks it has stopped being a guard. " + absent);
            assertTrue(absent.issued("create secret generic eddi-secrets"),
                    CREATE_SECRETS_SH + " exited cleanly without creating eddi-secrets. " + absent);
            assertFalse(absent.issued("delete secret eddi-secrets"),
                    CREATE_SECRETS_SH + " ran `kubectl delete secret eddi-secrets` on the NORMAL path, on the "
                            + "strength of a probe that had already returned. Between the two sit the key "
                            + "generation and, on the passphrase path, a `read` waiting on a human — long "
                            + "enough for a second installer to create the Secret and have this run destroy "
                            + "it without --force and without a word. The delete belongs to --force alone; "
                            + "AlreadyExists is what makes the normal path safe. " + absent);
            assertTrue(absent.output().contains("Save this key"),
                    CREATE_SECRETS_SH + " created the Secret but never printed the key. It is generated in "
                            + "the script and stored nowhere else; unprinted, it is unrecoverable. " + absent);
        }

        /**
         * Rotation is the one path that may destroy a key, and it has to keep working:
         * gating the delete behind {@code --force} is only correct if {@code --force}
         * still deletes. Run against a cluster that HAS a Secret, which is the only
         * situation rotation is for.
         */
        @Test
        @DisplayName("create-secrets.sh deletes only on the explicit --force rotation")
        void shellGeneratorDeletesOnlyWhenForced() throws Exception {
            GeneratorRun rotated = runShellGenerator("exists", true);
            assertEquals(0, rotated.exitCode(),
                    CREATE_SECRETS_SH + " --force failed against a cluster that already has eddi-secrets, "
                            + "which is the only case rotation exists for. " + rotated);
            assertTrue(rotated.issued("delete secret eddi-secrets"),
                    CREATE_SECRETS_SH + " --force did not delete the existing eddi-secrets, so the create "
                            + "that follows can only fail with AlreadyExists and the documented rotation is "
                            + "impossible. " + rotated);
            assertTrue(rotated.issued("create secret generic eddi-secrets"),
                    CREATE_SECRETS_SH + " --force deleted the live key and then did not install a new one. "
                            + rotated);
        }

        @Test
        @DisplayName("create-secrets.ps1 refuses to delete a key it could not check for")
        void powerShellGeneratorAbortsBeforeTheDeleteUnlessTheProbeSaysNotFound() throws Exception {
            GeneratorRun exists = runPowerShellGenerator("exists");
            assertNotEquals(0, exists.exitCode(),
                    CREATE_SECRETS_PS1 + " found an existing eddi-secrets (the probe exited 0) and did not "
                            + "abort. The two scripts are documented as equivalent and must not differ on "
                            + "whether they can destroy a key. " + exists);
            assertTrue(exists.output().contains("already exists in namespace"),
                    CREATE_SECRETS_PS1 + " must say the Secret already exists and point at -Force. " + exists);
            assertFalse(exists.issued("delete secret eddi-secrets"),
                    CREATE_SECRETS_PS1 + " ran `kubectl delete secret eddi-secrets` on a cluster that HAS "
                            + "one, without -Force. " + exists);

            for (String unreadable : List.of("unreachable", "forbidden")) {
                GeneratorRun run = runPowerShellGenerator(unreadable);
                assertNotEquals(0, run.exitCode(),
                        CREATE_SECRETS_PS1 + " carried on after a probe that failed without kubectl's "
                                + "(NotFound) reason (stub mode `" + unreadable + "`). $ErrorActionPreference "
                                + "= 'Stop' does not apply to native commands, so nothing else stops it: the "
                                + "$LASTEXITCODE check and the (NotFound) match are the whole guard. " + run);
                assertTrue(run.output().contains("Refusing to continue"),
                        CREATE_SECRETS_PS1 + " aborted without saying why (stub mode `" + unreadable + "`). "
                                + run);
                assertFalse(run.issued("delete secret eddi-secrets"),
                        CREATE_SECRETS_PS1 + " reached `kubectl delete secret eddi-secrets` on a cluster it "
                                + "could not read (stub mode `" + unreadable + "`), destroying a live vault "
                                + "master key on a cluster this run never actually saw. " + run);
                assertFalse(run.issued("create secret generic eddi-secrets"),
                        CREATE_SECRETS_PS1 + " installed a new key after a failed probe (stub mode `"
                                + unreadable + "`). " + run);
            }
        }

        @Test
        @DisplayName("create-secrets.ps1 installs the key on a genuine NotFound, and deletes nothing")
        void powerShellGeneratorProceedsOnAStructuredNotFound() throws Exception {
            GeneratorRun absent = runPowerShellGenerator("notfound");
            assertEquals(0, absent.exitCode(),
                    CREATE_SECRETS_PS1 + " refused to install a key on a cluster whose probe returned "
                            + "kubectl's genuine `Error from server (NotFound)` — which is what every first "
                            + "install produces. " + absent);
            assertTrue(absent.issued("create secret generic eddi-secrets"),
                    CREATE_SECRETS_PS1 + " exited cleanly without creating eddi-secrets. " + absent);
            assertFalse(absent.issued("delete secret eddi-secrets"),
                    CREATE_SECRETS_PS1 + " ran `kubectl delete secret eddi-secrets` on the NORMAL path, on "
                            + "the strength of a probe that had already returned — the same destructive race "
                            + "as the shell script, and the two are documented as equivalent. The delete "
                            + "belongs to -Force alone. " + absent);
            assertTrue(absent.output().contains("Save this key"),
                    CREATE_SECRETS_PS1 + " created the Secret but never printed the key. " + absent);
        }

        @Test
        @DisplayName("create-secrets.ps1 deletes only on the explicit -Force rotation")
        void powerShellGeneratorDeletesOnlyWhenForced() throws Exception {
            GeneratorRun rotated = runPowerShellGenerator("exists", true);
            assertEquals(0, rotated.exitCode(),
                    CREATE_SECRETS_PS1 + " -Force failed against a cluster that already has eddi-secrets, "
                            + "which is the only case rotation exists for. " + rotated);
            assertTrue(rotated.issued("delete secret eddi-secrets"),
                    CREATE_SECRETS_PS1 + " -Force did not delete the existing eddi-secrets, so the documented "
                            + "rotation is impossible. " + rotated);
            assertTrue(rotated.issued("create secret generic eddi-secrets"),
                    CREATE_SECRETS_PS1 + " -Force deleted the live key and then did not install a new one. "
                            + rotated);
        }

        /**
         * A generator may report an installed master key exactly when it installed one.
         * <p>
         * {@code SupportsShouldProcess} makes {@code -WhatIf} decline every write in
         * create-secrets.ps1 — the namespace, the {@code -Force} delete and the create
         * — and the run then fell through to the "Save this key!" box, "Secret created
         * in namespace" and the {@code kubectl apply -k} next steps regardless. Two
         * lies in one run: a key was reported as installed when nothing was written,
         * and the key printed for safekeeping existed in no cluster, no file and no
         * vault. File it and the real install later generates a different one; act on
         * the next steps and the pod waits forever for a Secret nobody made.
         * <p>
         * Asserted as the biconditional rather than as "-WhatIf prints nothing",
         * because the property is what matters and it also covers the paths that
         * legitimately print: a genuine install must still show the key, since the
         * script is the only place it exists. Every mode either script has is swept, so
         * a future early-return that forgets the box fails here too.
         * <p>
         * create-secrets.sh has no analogous path and is not changed: it offers no
         * dry-run, its create is unconditional, and every failure leaves through
         * {@code fail}. It is swept anyway — the two are documented as equivalent, and
         * this is the invariant they have to stay equivalent on.
         */
        @Test
        @DisplayName("neither generator reports a key it did not install")
        void generatorsReportAKeyOnlyWhenTheyInstalledOne() throws Exception {
            List<GeneratorRun> runs = new ArrayList<>();
            for (String mode : List.of("exists", "notfound", "unreachable", "forbidden")) {
                runs.add(runShellGenerator(mode));
            }
            runs.add(runShellGenerator("exists", true));
            for (GeneratorRun run : runs) {
                assertEquals(run.issued("create secret generic eddi-secrets"), run.reportedAnInstalledKey(),
                        CREATE_SECRETS_SH + " printed the vault master key and reported eddi-secrets as "
                                + "created without issuing the create (or installed one and never printed "
                                + "it — the key exists nowhere else). " + run);
            }

            runs.clear();
            for (String mode : List.of("exists", "notfound", "unreachable", "forbidden")) {
                runs.add(runPowerShellGenerator(mode));
            }
            runs.add(runPowerShellGenerator("exists", true));
            runs.add(runPowerShellGenerator("notfound", false, true));
            runs.add(runPowerShellGenerator("exists", true, true));
            for (GeneratorRun run : runs) {
                assertEquals(run.issued("create secret generic eddi-secrets"), run.reportedAnInstalledKey(),
                        CREATE_SECRETS_PS1 + " printed the vault master key and reported eddi-secrets as "
                                + "created without issuing the create (or installed one and never printed "
                                + "it). Under -WhatIf ShouldProcess declines every write, so the operator was "
                                + "handed a key for a Secret that exists in no cluster. " + run);
            }

            // Vacuity guard: the biconditional is satisfied trivially if -WhatIf ever
            // starts writing, which is the one thing -WhatIf must never do.
            GeneratorRun dryRun = runPowerShellGenerator("notfound", false, true);
            assertFalse(dryRun.issued("create secret generic eddi-secrets"),
                    CREATE_SECRETS_PS1 + " -WhatIf created eddi-secrets. " + dryRun);
            assertEquals(0, dryRun.exitCode(),
                    CREATE_SECRETS_PS1 + " -WhatIf exited non-zero. A declined dry run is not a failure, and "
                            + "an installer that exits 1 on it will be retried without -WhatIf by a script "
                            + "that cannot tell the two apart. " + dryRun);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Keycloak
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("keycloak")
    class Keycloak {

        /**
         * Since Keycloak 25 the health endpoints live on the management interface
         * (9000), not the main HTTP port, and they stay disabled unless
         * KC_HEALTH_ENABLED is set. Probing /health/ready on 8080 returns 404 forever:
         * the pod never becomes Ready, its Service gets no endpoints, and the liveness
         * probe restart-loops the container.
         */
        @Test
        @DisplayName("probes hit the management interface with health enabled")
        void healthProbesUseManagementPort() throws IOException {
            for (Path manifest : List.of(KUSTOMIZE_KEYCLOAK, HELM_KEYCLOAK)) {
                String text = stripComments(read(manifest));
                assertTrue(text.contains("KC_HEALTH_ENABLED"),
                        manifest + " must set KC_HEALTH_ENABLED — Keycloak serves /health/* only when it is on");
                assertTrue(text.contains("containerPort: 9000"),
                        manifest + " must declare the management port; that is where /health/* lives since "
                                + "Keycloak 25");
                assertEquals(2, countOccurrences(text, "port: management"),
                        manifest + " must point BOTH the readiness and liveness probes at the management port");
                assertFalse(text.contains("port: http"),
                        manifest + " still probes a named port `http`; /health/* on the main HTTP port answers 404");
            }
        }

        /**
         * Without an import Keycloak boots with only the built-in `master` realm, so
         * OIDC discovery on .../realms/eddi 404s, none of the five roles the Java code
         * enforces exist, and there is nobody to log in as.
         */
        @Test
        @DisplayName("the eddi realm is imported on first boot")
        void realmIsImported() throws IOException {
            for (Path manifest : List.of(KUSTOMIZE_KEYCLOAK, HELM_KEYCLOAK)) {
                String text = stripComments(read(manifest));
                assertTrue(text.contains("--import-realm"),
                        manifest + " must start Keycloak with --import-realm");
                assertTrue(text.contains("/opt/keycloak/data/import"),
                        manifest + " must mount the realm where --import-realm reads it");
            }
            assertTrue(Files.exists(KUSTOMIZE_REALM), KUSTOMIZE_REALM + " must ship with the auth component");
            assertTrue(Files.exists(HELM_REALM),
                    HELM_REALM + " must ship inside the chart — .Files.Get cannot read outside it");
        }

        /**
         * KC_BOOTSTRAP_ADMIN_PASSWORD is the master-realm superuser, and the
         * {@code keycloak} Service in front of it is a ClusterIP: every pod in the
         * namespace can reach it. It shipped in the kustomize component as
         * {@code value: "admin"} under a comment asking the operator to change it —
         * which is a guessable superuser on any cluster where nobody read the comment,
         * and an audit finding on every cluster where somebody did.
         * <p>
         * Neither delivery path may carry a literal, and neither may DEFAULT one:
         * <ul>
         * <li>kustomize takes it from a Secret through {@code secretKeyRef}. With
         * {@code optional} unset the kubelet cannot build the container until that
         * Secret exists, so the component fails closed — CreateContainerConfigError
         * naming the missing Secret — instead of coming up reachable with a known
         * password. {@code optional: true} would restore exactly the old failure mode
         * in a new shape: Keycloak boots, with no bootstrap admin at all.</li>
         * <li>Helm renders it through {@code required}, which refuses to template a
         * chart whose {@code keycloak.adminPassword} is unset.</li>
         * </ul>
         * Failing closed is only usable if the operator is told what to create, so the
         * Secret named in the manifest is asserted to be the Secret the component
         * header and docs/kubernetes.md hand out a {@code kubectl create secret}
         * command for — read out of the YAML rather than remembered here, so renaming
         * it in one place and not the others fails.
         */
        @Test
        @DisplayName("the keycloak bootstrap admin password has no default on either delivery path")
        void bootstrapAdminPasswordIsSuppliedByTheOperatorOnBothPaths() throws IOException {
            JsonNode passwordVariable = null;
            for (JsonNode variable : documentOfKind(KUSTOMIZE_KEYCLOAK, "StatefulSet")
                    .path("spec").path("template").path("spec").path("containers").get(0).path("env")) {
                if ("KC_BOOTSTRAP_ADMIN_PASSWORD".equals(variable.path("name").asText())) {
                    passwordVariable = variable;
                }
            }
            assertTrue(passwordVariable != null,
                    KUSTOMIZE_KEYCLOAK + " sets no KC_BOOTSTRAP_ADMIN_PASSWORD. Keycloak then creates no "
                            + "bootstrap admin and there is nobody to administer the realm with");
            assertFalse(passwordVariable.has("value"),
                    KUSTOMIZE_KEYCLOAK + " sets KC_BOOTSTRAP_ADMIN_PASSWORD to the literal `"
                            + passwordVariable.path("value").asText() + "`. That is the master-realm "
                            + "superuser password, in the repository, on a workload fronted by a ClusterIP "
                            + "every pod in the namespace can reach — a comment telling the operator to "
                            + "change it is not a control. It must come from a Secret the operator creates");

            JsonNode reference = passwordVariable.path("valueFrom").path("secretKeyRef");
            String secretName = reference.path("name").asText();
            assertFalse(secretName.isBlank(),
                    KUSTOMIZE_KEYCLOAK + " must read KC_BOOTSTRAP_ADMIN_PASSWORD from a secretKeyRef");
            assertFalse(reference.path("key").asText().isBlank(),
                    KUSTOMIZE_KEYCLOAK + " names no key inside Secret `" + secretName + "`");
            assertFalse(reference.path("optional").asBoolean(false),
                    KUSTOMIZE_KEYCLOAK + " marks the `" + secretName + "` reference optional, so a missing "
                            + "Secret starts Keycloak with the variable simply unset rather than failing "
                            + "closed. Keycloak then boots with no bootstrap admin, silently, which is the "
                            + "same class of surprise as the default password this replaced");

            String creationCommand = "kubectl create secret generic " + secretName;
            for (Path instruction : List.of(AUTH_COMPONENT, K8S_DOC)) {
                assertTrue(read(instruction).contains(creationCommand),
                        instruction + " never says `" + creationCommand + "`, but " + KUSTOMIZE_KEYCLOAK
                                + " will not start a pod until that Secret exists. A component that fails "
                                + "closed has to say exactly what to create, in the place the operator is "
                                + "reading when they hit it");
            }

            String bootstrapPassword = read(HELM_KEYCLOAK).lines()
                    .dropWhile(line -> !line.contains("- name: KC_BOOTSTRAP_ADMIN_PASSWORD"))
                    .filter(line -> line.contains("value:"))
                    .findFirst()
                    .orElse("");
            assertTrue(bootstrapPassword.contains("required "),
                    HELM_KEYCLOAK + " renders KC_BOOTSTRAP_ADMIN_PASSWORD without `required`, so a chart "
                            + "installed without keycloak.adminPassword templates an empty password rather "
                            + "than refusing. Rendered as: " + bootstrapPassword.strip());
            assertEquals("", YAML.readTree(HELM.resolve("values.yaml").toFile())
                    .path("keycloak").path("adminPassword").asText(),
                    HELM.resolve("values.yaml") + " gives keycloak.adminPassword a default, which is what "
                            + "`required` in " + HELM_KEYCLOAK + " exists to prevent — a defaulted value "
                            + "satisfies it and ships as the identity provider for the whole deployment");
        }

        /**
         * {@code start-dev} keeps Keycloak's H2 database under
         * {@code /opt/keycloak/data}. With no volume that is the container's ephemeral
         * writable layer, so any rollout, eviction, node drain or OOM kill discarded
         * every realm, client and user.
         * <p>
         * The fsGroup is part of the same fix, not a separate hardening. The keycloak
         * image runs as {@code USER 1000}, and on EBS/PD/Azure Disk and most CSI
         * drivers a freshly provisioned volume is root:root 0755 until the kubelet
         * applies fsGroup — so mounting a claim over the data directory without one
         * turns "every restart wipes Keycloak" into "Keycloak never starts", on exactly
         * the clusters the claim was added for. kind and minikube hostPath volumes are
         * world-writable and hide it, which is why asserting the mount and the claim
         * exist is not enough: neither says the pod can WRITE there.
         */
        @Test
        @DisplayName("keycloak state is persisted across restarts, on a volume it can write to")
        void keycloakStateIsPersisted() throws IOException {
            for (Path manifest : List.of(KUSTOMIZE_KEYCLOAK, HELM_KEYCLOAK)) {
                String text = stripComments(read(manifest));
                assertTrue(text.contains("kind: StatefulSet"),
                        manifest + " must be a StatefulSet: the data claim is ReadWriteOnce and a Deployment's "
                                + "RollingUpdate would deadlock on it");
                assertTrue(text.contains("volumeClaimTemplates"),
                        manifest + " must claim persistent storage for Keycloak's database");
                assertTrue(text.contains("mountPath: /opt/keycloak/data"),
                        manifest + " must mount that claim where Keycloak keeps its H2 database");
                assertTrue(text.contains("fsGroup: 1000"),
                        manifest + " mounts a PersistentVolumeClaim over /opt/keycloak/data with no fsGroup. "
                                + "The keycloak image runs as uid 1000 and a newly provisioned volume is "
                                + "root-owned until the kubelet applies one, so Keycloak gets EACCES creating "
                                + "its H2 database and CrashLoopBackOffs before the readiness probe ever runs. "
                                + "The mongodb and postgres StatefulSets set fsGroup for this reason");
            }

            // The kustomize path is the one that renders to a literal, so it can be
            // read structurally rather than as text.
            JsonNode podSpec = documentOfKind(KUSTOMIZE_KEYCLOAK, "StatefulSet")
                    .path("spec").path("template").path("spec");
            assertEquals(1000, podSpec.path("securityContext").path("fsGroup").asInt(),
                    KUSTOMIZE_KEYCLOAK + " must set the pod-level securityContext.fsGroup to the uid the "
                            + "keycloak image runs as; a container-level one does not chown the volume");
        }

        /**
         * The other two lines of the same block, which the test above cannot see: it
         * greps for {@code fsGroup: 1000} and stops.
         * <p>
         * {@code runAsNonRoot: true} is the load-bearing one. It is what makes the
         * kubelet REFUSE to start a container whose image USER resolves to 0, instead
         * of running Keycloak as root — and a root process can write to the volume
         * whatever its ownership, so dropping this line makes every permission
         * assertion in the sibling test vacuously true while handing the pod
         * unnecessary privilege.
         * <p>
         * {@code runAsUser} pins WHICH non-root uid, and it has to agree with
         * {@code fsGroup} for a reason the first boot does not show. fsGroup does two
         * things — it chowns the volume root to that GID and adds the GID to the pod's
         * supplementary groups — so a mismatched uid can still CREATE files there. The
         * files it creates are the problem: {@code keycloakdb.mv.db} lands as
         * {@code <runAsUser>:<fsGroup> 0644} under a default umask, group-readable but
         * not group-writable. Change runAsUser later (a base-image bump, a hardening
         * edit) and the next pod's supplementary group is no longer enough: it can read
         * the H2 database it inherited and cannot write it, which is a Keycloak that
         * starts and then fails on the first realm write rather than a clean crash.
         * Equal numbers make the uid that creates the files the same uid that comes
         * back for them.
         * <p>
         * {@code runAsUser: 0} is refused outright as well, because it and
         * {@code runAsNonRoot: true} contradict each other and the kubelet rejects the
         * pair with CreateContainerConfigError rather than picking one.
         * <p>
         * Both copies are read as STRUCTURE, not as remembered literals. The kustomize
         * copy is plain YAML. The chart's copy is a Go template, but this particular
         * block carries no directive — four literal YAML lines — so it is parsed out of
         * the template text by indentation, which is what lets the same three relations
         * be asserted on it: that the block sits at pod level (a container-level
         * securityContext does not chown the volume at all), that runAsUser is not 0,
         * and that runAsUser and fsGroup are the SAME number rather than each
         * separately equal to 1000. Grepping two literals could not see the last of
         * those: it passes on {@code runAsUser: 1000} beside {@code fsGroup: 1001},
         * which is the mismatch this test's own name is about.
         */
        @Test
        @DisplayName("keycloak runs as a pinned non-root uid, matching the volume's fsGroup")
        void keycloakRunsAsAPinnedNonRootUid() throws IOException {
            for (Path manifest : List.of(KUSTOMIZE_KEYCLOAK, HELM_KEYCLOAK)) {
                String text = stripComments(read(manifest));
                assertTrue(text.contains("runAsNonRoot: true"),
                        manifest + " must set runAsNonRoot: true. It is what makes the kubelet REFUSE an "
                                + "image whose USER resolves to 0; without it a base image that reverts to "
                                + "root runs as root, and a root process satisfies every volume-permission "
                                + "assertion here by being able to write anywhere");
                assertTrue(text.contains("runAsUser: 1000"),
                        manifest + " must pin runAsUser to the same 1000 it chowns the volume to. Left "
                                + "implicit it follows the image, and the H2 files a previous uid wrote are "
                                + "0644 — readable through the fsGroup supplementary group, not writable");
            }

            // The chart's copy, parsed out of the template text by indentation. Its
            // securityContext block is four literal YAML lines with no Go directive in
            // them, so the same relations the kustomize copy is checked for below are
            // checkable here — and only these see a uid that disagrees with the gid.
            List<String> chartLines = read(HELM_KEYCLOAK).lines().toList();
            List<Integer> podLevel = new ArrayList<>();
            for (int line = 0; line < chartLines.size(); line++) {
                boolean isSecurityContext = "securityContext:".equals(chartLines.get(line).strip());
                if (isSecurityContext && "spec.template.spec.securityContext".equals(yamlKeyPath(chartLines, line))) {
                    podLevel.add(line);
                }
            }
            assertEquals(1, podLevel.size(),
                    HELM_KEYCLOAK + " must carry exactly one POD-level securityContext (found " + podLevel.size()
                            + " at spec.template.spec). fsGroup is only honoured there — the kubelet chowns the "
                            + "volume for the pod, not for a container — so a block that drifts down into the "
                            + "container list keeps every line this test greps for and stops granting Keycloak "
                            + "the directory its H2 database lives in");
            int at = podLevel.get(0);
            assertEquals("StatefulSet", documentKindAt(chartLines, at),
                    HELM_KEYCLOAK + " puts its pod-level securityContext on a "
                            + documentKindAt(chartLines, at) + ", not on the Keycloak StatefulSet that mounts "
                            + "the data volume");

            Map<String, String> chartContext = yamlScalarChildren(chartLines, at);
            assertTrue(chartContext.keySet().containsAll(List.of("runAsNonRoot", "runAsUser", "fsGroup")),
                    HELM_KEYCLOAK + "'s pod securityContext is " + chartContext + "; it must set runAsNonRoot, "
                            + "runAsUser and fsGroup. Comparing fields that are not there would compare two "
                            + "nulls and pass on a manifest that sets neither");
            assertEquals("true", chartContext.get("runAsNonRoot"),
                    HELM_KEYCLOAK + " must set runAsNonRoot: true at POD level, where it also covers any "
                            + "container added later; the text assertion above cannot tell where in the file "
                            + "the line sits");
            assertNotEquals("0", chartContext.get("runAsUser"),
                    HELM_KEYCLOAK + " runs Keycloak as uid 0 while also declaring runAsNonRoot: true. The "
                            + "kubelet does not pick one — it refuses the pod with CreateContainerConfigError");
            assertEquals(chartContext.get("fsGroup"), chartContext.get("runAsUser"),
                    HELM_KEYCLOAK + " chowns the Keycloak data volume to gid " + chartContext.get("fsGroup")
                            + " but runs the container as uid " + chartContext.get("runAsUser") + ". fsGroup "
                            + "gets the pod into the directory, but the files it writes there are 0644 owned "
                            + "by runAsUser — so once these two differ, a pod that inherits an existing "
                            + "keycloakdb.mv.db can read it and not write it, and Keycloak fails on the first "
                            + "realm write instead of crashing cleanly at startup. Two literals asserted "
                            + "separately cannot see this: each is still the number it was told to be");

            JsonNode securityContext = documentOfKind(KUSTOMIZE_KEYCLOAK, "StatefulSet")
                    .path("spec").path("template").path("spec").path("securityContext");
            assertTrue(securityContext.has("runAsUser") && securityContext.has("fsGroup"),
                    KUSTOMIZE_KEYCLOAK + " must set both runAsUser and fsGroup at pod level; it has "
                            + securityContext + ". Comparing two absent fields would compare 0 to 0 and pass "
                            + "on a manifest that sets neither");
            assertTrue(securityContext.path("runAsNonRoot").asBoolean(),
                    KUSTOMIZE_KEYCLOAK + " must set runAsNonRoot: true at POD level, where it also covers "
                            + "any container added later; the text assertion above cannot tell where in the "
                            + "file the line sits");
            assertNotEquals(0, securityContext.path("runAsUser").asInt(),
                    KUSTOMIZE_KEYCLOAK + " runs Keycloak as uid 0 while also declaring runAsNonRoot: true. "
                            + "The kubelet does not pick one — it refuses the pod with "
                            + "CreateContainerConfigError");
            assertEquals(securityContext.path("fsGroup").asInt(), securityContext.path("runAsUser").asInt(),
                    KUSTOMIZE_KEYCLOAK + " chowns the Keycloak data volume to gid "
                            + securityContext.path("fsGroup").asInt() + " but runs the container as uid "
                            + securityContext.path("runAsUser").asInt() + ". fsGroup gets the pod into the "
                            + "directory, but the files it writes there are 0644 owned by runAsUser — so once "
                            + "these two differ, a pod that inherits an existing keycloakdb.mv.db can read it "
                            + "and not write it, and Keycloak fails on the first realm write instead of "
                            + "crashing cleanly at startup");
        }

        /**
         * The Manager SPA learns where Keycloak is from
         * {@code window.__EDDI_AUTH__.url}, spliced in by RestManagerResource from this
         * variable, and application.properties splices the same value into the CSP
         * connect-src. Neither delivery path used to set it, so login could not start
         * and the browser would have blocked the call anyway.
         */
        @Test
        @DisplayName("the browser-facing identity provider URL is configured")
        void publicKeycloakUrlIsConfigured() throws IOException {
            assertTrue(read(K8S.resolve("overlays/auth/kustomization.yaml")).contains("EDDI_KEYCLOAK_PUBLIC_URL"),
                    "the auth component must patch EDDI_KEYCLOAK_PUBLIC_URL into eddi-config");
            assertTrue(read(HELM_TEMPLATES.resolve("configmap.yaml")).contains("EDDI_KEYCLOAK_PUBLIC_URL"),
                    "the chart must render EDDI_KEYCLOAK_PUBLIC_URL when OIDC is enabled");
        }

        /**
         * EDDI_KEYCLOAK_PUBLIC_URL's sibling, and guarded on neither path until now.
         * Keycloak stamps tokens with the PUBLIC issuer (KC_HOSTNAME) while EDDI
         * fetches discovery over the in-cluster Service address, so the two disagree
         * and Quarkus rejects every token on the mismatch unless the issuer is pinned
         * by hand. Both the auth component's own header and docs/kubernetes.md list it
         * among the four settings that must agree.
         * <p>
         * The chart derives it from the same {@code $publicUrl} it renders
         * EDDI_KEYCLOAK_PUBLIC_URL from, so the two cannot drift there. The kustomize
         * component writes both out as literals, which can — so they are compared to
         * each other rather than merely counted.
         * <p>
         * There are THREE literals on the kustomize path, not two: KC_HOSTNAME lives in
         * keycloak-statefulset.yaml, in a different file, and it is the one Keycloak
         * actually stamps into tokens. Comparing only the two that share a file left
         * the drift that costs a 401 on every request — change KC_HOSTNAME in the
         * StatefulSet whose own comment tells you to change it, forget the component
         * patch, and the suite stayed green — so it is read here too.
         */
        @Test
        @DisplayName("the OIDC token issuer is pinned, and agrees with the public URL and KC_HOSTNAME")
        void tokenIssuerIsPinnedOnBothDeliveryPaths() throws IOException {
            // The Go comment beside it discusses QUARKUS_OIDC_TOKEN_ISSUER by name.
            // Prose is not the setting: matching it would let a deleted key read as
            // covered, which is the failure mode this whole class exists for.
            String chart = stripGoComments(read(HELM_TEMPLATES.resolve("configmap.yaml")));
            String issuerLine = chart.lines()
                    .filter(line -> line.contains("QUARKUS_OIDC_TOKEN_ISSUER"))
                    .findFirst()
                    .orElse("");
            assertFalse(issuerLine.isBlank(),
                    HELM_TEMPLATES.resolve("configmap.yaml") + " never renders QUARKUS_OIDC_TOKEN_ISSUER; "
                            + "tokens then carry the public issuer while EDDI validates against the "
                            + "in-cluster discovery document, and every request 401s on the mismatch");
            assertTrue(issuerLine.contains("$publicUrl"),
                    "the chart must derive QUARKUS_OIDC_TOKEN_ISSUER from the same $publicUrl it renders "
                            + "EDDI_KEYCLOAK_PUBLIC_URL and KC_HOSTNAME from — a second literal is a fourth "
                            + "thing to keep in step. Rendered as: " + issuerLine.strip());
            assertTrue(issuerLine.contains("/realms/"),
                    "the issuer is the realm URL, not the Keycloak root. Rendered as: " + issuerLine.strip());

            Map<String, String> patched = eddiConfigPatch(AUTH_COMPONENT);
            assertTrue(patched.containsKey("/data/QUARKUS_OIDC_TOKEN_ISSUER"),
                    AUTH_COMPONENT + " patches EDDI_KEYCLOAK_PUBLIC_URL into eddi-config but not "
                            + "QUARKUS_OIDC_TOKEN_ISSUER, so the kustomize path keeps the mismatch the chart "
                            + "path fixed. Patched keys: " + patched.keySet());
            assertEquals(patched.get("/data/EDDI_KEYCLOAK_PUBLIC_URL") + "/realms/eddi",
                    patched.get("/data/QUARKUS_OIDC_TOKEN_ISSUER"),
                    AUTH_COMPONENT + " sets a token issuer that is not the eddi realm on the browser-facing "
                            + "Keycloak URL. Those two are two of the four settings the component's own header "
                            + "says must agree, and disagreeing costs a 401 on every request");

            String kcHostname = containerEnv(documentOfKind(KUSTOMIZE_KEYCLOAK, "StatefulSet"), "KC_HOSTNAME");
            assertEquals(patched.get("/data/EDDI_KEYCLOAK_PUBLIC_URL"), kcHostname,
                    KUSTOMIZE_KEYCLOAK + " sets KC_HOSTNAME=" + kcHostname + " while "
                            + AUTH_COMPONENT + " patches EDDI_KEYCLOAK_PUBLIC_URL="
                            + patched.get("/data/EDDI_KEYCLOAK_PUBLIC_URL") + ". KC_HOSTNAME is the issuer "
                            + "Keycloak STAMPS INTO TOKENS; the other two only say what EDDI expects to see. "
                            + "They live in different files, so nothing but this holds them together — and a "
                            + "disagreement is a 401 on every authenticated request");

            // Helm cannot drift the same way (all three derive from $publicUrl), so
            // what is asserted there is that they still do.
            String chartKeycloak = stripComments(read(HELM_KEYCLOAK));
            String hostnameLine = chartKeycloak.lines()
                    .filter(line -> line.contains("value:") && line.contains(".Values.eddi.oidc.publicUrl"))
                    .findFirst()
                    .orElse("");
            assertFalse(hostnameLine.isBlank(),
                    HELM_KEYCLOAK + " must render KC_HOSTNAME from .Values.eddi.oidc.publicUrl — the same "
                            + "value configmap.yaml renders EDDI_KEYCLOAK_PUBLIC_URL and the issuer from");
            assertTrue(hostnameLine.contains("trimSuffix"),
                    HELM_KEYCLOAK + " must trim a trailing slash off eddi.oidc.publicUrl before rendering it "
                            + "as KC_HOSTNAME, as configmap.yaml does: otherwise `--set "
                            + "eddi.oidc.publicUrl=https://host/` stamps tokens with an issuer that has one "
                            + "and the pinned issuer does not. Rendered as: " + hostnameLine.strip());
        }

        /**
         * {@code keycloak.publicOrigin} is substituted into the realm both as a
         * redirect pattern ("…/*") and as a bare webOrigin, and an origin must carry no
         * path at all. A value passed with a trailing slash — which looks entirely
         * correct — produced the redirect {@code https://host//*} and the origin
         * {@code https://host/}, so Keycloak answered "Invalid parameter: redirect_uri"
         * and the browser's preflight failed CORS. The chart already knew values arrive
         * that way: the issuer line trimmed, and only it.
         */
        @Test
        @DisplayName("a trailing slash on either public URL cannot reach the realm")
        void publicUrlsAreTrimmedBeforeSubstitution() throws IOException {
            String chart = stripGoComments(read(HELM_KEYCLOAK));
            String realmOriginLine = chart.lines()
                    .filter(line -> line.contains("$realmOrigin :="))
                    .findFirst()
                    .orElse("");
            assertFalse(realmOriginLine.isBlank(),
                    HELM_KEYCLOAK + " must derive $realmOrigin from keycloak.publicOrigin");
            assertTrue(realmOriginLine.contains("trimSuffix"),
                    HELM_KEYCLOAK + " substitutes $realmOrigin into the realm's redirectUris ('…/*') and into "
                            + "a bare webOrigin, so it must be trimmed first: `--set "
                            + "keycloak.publicOrigin=https://host/` otherwise yields the redirect "
                            + "'https://host//*' and the origin 'https://host/', and an origin may not carry a "
                            + "path. Rendered as: " + realmOriginLine.strip());
        }

        /**
         * Three of those four settings are the KEYCLOAK URL; the fourth is the EDDI
         * one. The realm's {@code redirectUris} / {@code webOrigins} have to list where
         * the Manager SPA is served, because that is where it redirects back to — and
         * the placeholder the realm actually carries says so: {@code
         * https://eddi.example.com/*} is the EDDI ingress host, not the Keycloak host.
         * <p>
         * Every operator-facing text said "four settings must agree on the URL the
         * BROWSER uses for Keycloak". Behind an ingress those are two different hosts
         * (the docs say so themselves — the Ingress fronts only EDDI), so following the
         * instruction exactly put the Keycloak host into the client redirects and
         * Keycloak answered "Invalid parameter: redirect_uri". A doc-driven
         * misconfiguration, on the one path this whole component was repaired for.
         */
        @Test
        @DisplayName("docs and headers do not send the Keycloak URL into the realm's redirectUris")
        void redirectUrisAreDocumentedAsTheEddiOriginNotTheIdp() throws IOException {
            JsonNode frontend = client(JSON.readTree(KUSTOMIZE_REALM.toFile()), "eddi-frontend");
            assertTrue(stringList(frontend.get("redirectUris")).stream().anyMatch(uri -> uri.contains("eddi.example.com")),
                    KUSTOMIZE_REALM + "'s eddi-frontend redirectUris placeholder is the EDDI host; if that "
                            + "ever changes to an IdP-shaped host, the guidance below has to change with it");

            for (Path text : List.of(K8S_DOC, AUTH_COMPONENT, KUSTOMIZE_KEYCLOAK)) {
                String content = read(text);
                assertFalse(content.contains("Four settings must all name the URL the **browser** uses for Keycloak")
                        || content.contains("Four settings must agree on")
                        || content.contains("all four together"),
                        text + " tells the operator that all four auth settings name the Keycloak URL. Three "
                                + "do (KC_HOSTNAME, EDDI_KEYCLOAK_PUBLIC_URL, QUARKUS_OIDC_TOKEN_ISSUER); the "
                                + "fourth — the realm's redirectUris/webOrigins — is the EDDI/Manager origin, "
                                + "which behind an ingress is a different host. Following this as written "
                                + "produces `Invalid parameter: redirect_uri`");
                assertTrue(content.contains("redirect_uri"),
                        text + " should name the error an operator actually sees when these are confused");
            }

            String doc = read(K8S_DOC);
            assertTrue(doc.contains("browser-facing origin of EDDI"),
                    K8S_DOC + " must say explicitly which of the four is the EDDI origin rather than the IdP "
                            + "URL — the whole defect was that one row of one table meant the other host");
        }

        /**
         * The realm reaches Keycloak through a name reference: the auth component
         * generates a ConfigMap, the pod mounts one by name, and kustomize's
         * nameReference transformer rewrites the mount to the hash-suffixed name it
         * produced. A typo on either side breaks that silently — kustomize does not
         * check that a referenced ConfigMap exists, so {@code kubectl kustomize}
         * renders and exits 0 too, and the new CI job would not catch it either. What
         * the operator gets is a pod wedged in ContainerCreating with no realm, which
         * is the same first-run symptom the Troubleshooting entry elsewhere in this
         * suite exists for.
         * <p>
         * realmIsImported checks the file ships and the flag and mount path are
         * present; nothing checked that the volume resolves to anything.
         */
        @Test
        @DisplayName("the realm volume resolves to the ConfigMap that actually carries the realm")
        void realmVolumeResolvesToTheGeneratedConfigMap() throws IOException {
            JsonNode component = YAML.readTree(AUTH_COMPONENT.toFile());
            Set<String> generated = new TreeSet<>();
            component.path("configMapGenerator").forEach(generator -> {
                generated.add(generator.path("name").asText());
                assertTrue(stringList(generator.get("files")).contains(KUSTOMIZE_REALM.getFileName().toString()),
                        AUTH_COMPONENT + "'s generator must be fed from " + KUSTOMIZE_REALM);
            });
            assertFalse(generated.isEmpty(),
                    AUTH_COMPONENT + " must generate the realm ConfigMap; a hand-written one would not roll "
                            + "the StatefulSet when the realm changes");

            JsonNode keycloak = documentOfKind(KUSTOMIZE_KEYCLOAK, "StatefulSet");
            JsonNode podSpec = keycloak.path("spec").path("template").path("spec");
            JsonNode container = podSpec.path("containers").get(0);

            String volumeName = null;
            for (JsonNode mount : container.path("volumeMounts")) {
                if ("/opt/keycloak/data/import".equals(mount.path("mountPath").asText())) {
                    volumeName = mount.path("name").asText();
                }
            }
            assertEquals("realm-import", volumeName,
                    KUSTOMIZE_KEYCLOAK + " must mount a volume at /opt/keycloak/data/import, which is the "
                            + "only directory --import-realm reads");

            String referenced = null;
            for (JsonNode volume : podSpec.path("volumes")) {
                // Receiver is the never-null side: volumeName is null when no mount
                // matched, and this loop would then NPE instead of reaching the
                // assertion that reports the missing mount.
                if (volume.path("name").asText().equals(volumeName)) {
                    referenced = volume.path("configMap").path("name").asText();
                }
            }
            assertTrue(generated.contains(referenced),
                    KUSTOMIZE_KEYCLOAK + " mounts ConfigMap `" + referenced + "` at the import directory, but "
                            + AUTH_COMPONENT + " generates " + generated + ". kustomize does not validate that "
                            + "a referenced ConfigMap exists — it renders, exits 0, and the pod then hangs in "
                            + "ContainerCreating with no realm");

            String chart = read(HELM_KEYCLOAK);
            String declared = captureAfter(chart, "kind: ConfigMap\\s*\\n\\s*metadata:\\s*\\n\\s*name: (.+)");
            String mounted = captureAfter(chart, "configMap:\\s*\\n\\s*name: (.+)");
            assertEquals(declared, mounted,
                    HELM_KEYCLOAK + " declares the realm ConfigMap as `" + declared + "` and mounts `" + mounted
                            + "`. Helm renders both happily; the pod never starts");
        }

        /**
         * The workload changed kind — Deployment to StatefulSet — under the SAME object
         * name, with byte-identical pod labels, behind a Service that selects on
         * exactly those labels. {@code kubectl apply -k} never prunes, so on an
         * existing install the old Deployment's ReplicaSet keeps running and BOTH pods
         * answer the one {@code keycloak} Service: one with the imported realm, one
         * without. Logins then fail on roughly every other request, and nothing in
         * {@code kubectl get} explains it. The only fix is a delete the operator has to
         * be told about.
         */
        @Test
        @DisplayName("the kind change carries a delete-the-old-Deployment upgrade note")
        void keycloakKindChangeIsDocumentedAsAnUpgradeStep() throws IOException {
            String overlay = read(KUSTOMIZE_KEYCLOAK);
            assertTrue(overlay.contains("kubectl delete deployment keycloak"),
                    KUSTOMIZE_KEYCLOAK + " must tell the operator to delete the old `keycloak` Deployment: this "
                            + "StatefulSet takes its name and its pod labels, kubectl apply -k does not prune, "
                            + "and the Service below then load-balances across both");

            String doc = read(K8S_DOC);
            assertTrue(doc.contains("kubectl delete deployment keycloak"),
                    K8S_DOC + " documents the auth component but not the one manual step an upgrading operator "
                            + "must take before applying it");
        }

        /**
         * The auth component patches {@code eddi-config} and nothing else, and the EDDI
         * Deployment consumes that ConfigMap through {@code envFrom}. Environment
         * variables are fixed at container start — unlike a ConfigMap mounted as a
         * volume, which the kubelet refreshes in place — so {@code kubectl apply -k}
         * updates the ConfigMap while the running pod keeps
         * {@code QUARKUS_OIDC_TENANT_ENABLED: "false"} and the three
         * {@code ALLOW_UNAUTHENTICATED} escape hatches it booted with. Every object
         * reports as applied and the install stays UNAUTHENTICATED, which is precisely
         * the state the previously ineffective (kind: Kustomization) overlay left
         * behind: an operator upgrading to the fixed component to CLOSE that hole gets
         * the same hole and a clean apply.
         * <p>
         * Helm needs no such note, because the pod template carries the
         * configmap/secret checksums that make the upgrade roll the pod — which is why
         * the assertion is on the Kustomize instructions only.
         */
        @Test
        @DisplayName("enabling auth carries a restart-EDDI step for kustomize")
        void enablingAuthIsDocumentedAsNeedingAnEddiRestart() throws IOException {
            String restart = "kubectl rollout restart deployment/eddi";
            for (Path file : List.of(AUTH_COMPONENT, K8S_DOC)) {
                assertTrue(read(file).contains(restart),
                        file + " must tell the operator to run `" + restart + " -n eddi` after applying the "
                                + "auth component. It patches eddi-config only, and the Deployment reads it "
                                + "via envFrom — environment variables are fixed at container start, so the "
                                + "running pod keeps QUARKUS_OIDC_TENANT_ENABLED=\"false\" and the "
                                + "ALLOW_UNAUTHENTICATED hatches it booted with. The apply succeeds and the "
                                + "install is still unauthenticated");
            }
        }

        /**
         * Enabling OIDC while the base's quickstart escape hatches stay open would let
         * every request through unauthenticated, making the whole component decorative.
         */
        @Test
        @DisplayName("enabling OIDC closes the unauthenticated escape hatches")
        void authComponentClosesEscapeHatches() throws IOException {
            String text = read(K8S.resolve("overlays/auth/kustomization.yaml"));
            for (String flag : List.of(
                    "EDDI_SECURITY_ALLOW_UNAUTHENTICATED",
                    "EDDI_MCP_ALLOW_UNAUTHENTICATED",
                    "EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED")) {
                assertTrue(text.contains(flag), "the auth component must set " + flag + " to \"false\"");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Realm files
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("keycloak realm")
    class Realm {

        /**
         * keycloak/eddi-realm.json is calibrated for docker-compose: redirects are
         * allowed to localhost only, and its login theme is mounted only by the compose
         * file. Shipped into a cluster unchanged it answers
         * {@code Invalid parameter: redirect_uri} for any real hostname, and names a
         * theme that does not exist there.
         */
        @Test
        @DisplayName("the cluster copies are calibrated for a cluster")
        void clusterRealmsAreClusterCalibrated() throws IOException {
            for (Path realm : List.of(KUSTOMIZE_REALM, HELM_REALM)) {
                JsonNode root = JSON.readTree(realm.toFile());
                assertFalse(root.has("loginTheme"),
                        realm + " must not name a login theme: keycloak/themes/eddi is mounted only by "
                                + "docker-compose.auth.yml, so in-cluster the theme does not exist");
                JsonNode spa = client(root, "eddi-frontend");
                List<String> redirects = stringList(spa.get("redirectUris"));
                assertTrue(redirects.stream().anyMatch(uri -> !uri.contains("localhost")),
                        realm + " allows redirects to localhost only, so the Manager SPA served through an "
                                + "ingress cannot complete a login. Redirect URIs: " + redirects);
            }
        }

        /**
         * The realm-level switch that decides whether Keycloak will speak cleartext. It
         * shipped as {@code none}, which is "never require TLS, from anywhere" — every
         * login form, every authorization code and every token exchange served over
         * plain HTTP to any caller that asked, on all three delivery paths.
         * <p>
         * The assertion is on the PROPERTY — TLS is required for callers outside the
         * local network — not on one spelling, so {@code all} (stricter still)
         * satisfies it and only the settings that permit cleartext externally fail.
         * <p>
         * {@code external}, Keycloak's own default, keeps every documented quick start
         * working: {@code kubectl port-forward svc/keycloak 8080:8080} reaches the pod
         * as 127.0.0.1, docker-compose's published port arrives from the bridge
         * gateway, and EDDI's backchannel to {@code http://keycloak:8080} comes from an
         * RFC 1918 pod address. All three are local addresses, which this setting
         * exempts; what it stops is a public hostname served over HTTP.
         */
        @Test
        @DisplayName("the realm requires TLS for clients outside the local network")
        void realmRequiresTlsForExternalClients() throws IOException {
            for (Path realm : List.of(COMPOSE_REALM, KUSTOMIZE_REALM, HELM_REALM)) {
                String sslRequired = JSON.readTree(realm.toFile()).path("sslRequired").asText();
                assertTrue(Set.of("external", "all").contains(sslRequired),
                        realm + " sets sslRequired to `" + sslRequired + "`, which lets Keycloak serve the "
                                + "login form, the authorization code and the token endpoint over cleartext "
                                + "HTTP to a caller outside the local network. Only `external` (TLS for "
                                + "non-local clients, which is Keycloak's own default and leaves the "
                                + "port-forward and docker-compose quick starts working) or `all` (TLS for "
                                + "everyone) require TLS at all");
            }
        }

        /**
         * No shipped realm may carry a password for an account that holds EDDI's
         * privileged roles.
         * <p>
         * The {@code eddi} fixture shipped as {@code eddi}/{@code eddi} with
         * {@code eddi-admin} and {@code eddi-editor} — a full EDDI administrator with a
         * password equal to its username, in a public repository, imported by BOTH
         * cluster delivery paths. "Development component" describes the manifests, not
         * the network: the Keycloak Service is a ClusterIP, so anything running in the
         * cluster could use it, and nothing stops the auth component being applied to a
         * shared one. {@code "temporary": true} was not a mitigation either — the
         * compose overlay's own header records that Keycloak 26 does not turn it into
         * an UPDATE_PASSWORD action on realm import, so these logged straight in.
         * <p>
         * Asserted as a relationship — privileged implies no shipped credential —
         * rather than against a remembered username, so a second admin fixture added
         * later is covered by construction. The unprivileged fixtures (viewer, user)
         * are deliberately untouched.
         * <p>
         * The last assertion is what stops this passing vacuously: deleting every
         * privileged user, rather than its password, would otherwise satisfy the loop
         * while removing the role wiring the operator is told to reuse.
         */
        @Test
        @DisplayName("no realm copy ships a password for a privileged account")
        void privilegedRealmUsersShipWithoutACredential() throws IOException {
            Set<String> privileged = Set.of("eddi-admin", "eddi-editor");
            for (Path realm : List.of(COMPOSE_REALM, KUSTOMIZE_REALM, HELM_REALM)) {
                List<String> privilegedUsers = new ArrayList<>();
                for (JsonNode user : JSON.readTree(realm.toFile()).path("users")) {
                    List<String> roles = stringList(user.get("realmRoles"));
                    if (roles.stream().noneMatch(privileged::contains)) {
                        continue;
                    }
                    String username = user.path("username").asText();
                    privilegedUsers.add(username);
                    assertFalse(user.path("credentials").elements().hasNext(),
                            realm + " seeds `" + username + "` with " + roles + " AND a credential. That is a "
                                    + "guessable full EDDI administrator on every cluster this realm is "
                                    + "imported into, reachable through the Keycloak ClusterIP from any pod. "
                                    + "A privileged fixture may ship its ROLES — the operator sets a password "
                                    + "in the admin console — but never a password");
                }
                assertFalse(privilegedUsers.isEmpty(),
                        realm + " seeds no user holding " + privileged + " at all. The credential-free `eddi` "
                                + "account is what the component headers, NOTES.txt and docs/kubernetes.md "
                                + "tell the operator to set a password on; removing it instead of its "
                                + "password leaves those instructions pointing at nothing — and makes the "
                                + "assertion above pass by having nothing to check");
            }
        }

        /**
         * Three copies of one realm can drift. The parts that MAY differ are the
         * hostname-shaped ones — redirectUris, webOrigins, loginTheme. Everything the
         * Java code depends on must not: RestManagerResource hardcodes the SPA client
         * id, and {@code @RolesAllowed} names these roles.
         */
        @Test
        @DisplayName("all realm copies agree on realm, clients, roles and seed users")
        void realmCopiesDoNotDrift() throws IOException {
            JsonNode reference = JSON.readTree(COMPOSE_REALM.toFile());
            for (Path realm : List.of(KUSTOMIZE_REALM, HELM_REALM)) {
                JsonNode root = JSON.readTree(realm.toFile());
                assertEquals(reference.path("realm").asText(), root.path("realm").asText(),
                        realm + " names a different realm than " + COMPOSE_REALM);
                assertEquals(names(reference, "clients", "clientId"), names(root, "clients", "clientId"),
                        realm + " has drifted from " + COMPOSE_REALM + " on client ids — RestManagerResource "
                                + "hardcodes eddi-frontend, so an SPA client by another name means invalid_client");
                assertEquals(realmRoles(reference), realmRoles(root),
                        realm + " has drifted from " + COMPOSE_REALM + " on realm roles, which @RolesAllowed "
                                + "enforces by name");
                assertEquals(names(reference, "users", "username"), names(root, "users", "username"),
                        realm + " has drifted from " + COMPOSE_REALM + " on seed users");
            }
        }

        /**
         * Naming the parts that must agree leaves everything unnamed free to drift, and
         * the list left out the parts most likely to: the eddi-backend audience mapper,
         * the realm-roles and groups protocolMappers, defaultClientScopes, the
         * defaultRole composites, bruteForceProtected and the groups list. A change to
         * any of those in the compose realm would simply never reach the cluster
         * copies, and the symptom — a token whose audience Quarkus rejects — appears in
         * Kubernetes only.
         * <p>
         * So this compares the whole document instead, after normalising away the three
         * things that are ALLOWED to differ, all of them hostname-shaped: the root
         * {@code loginTheme} (its theme directory is mounted only by docker-compose)
         * and each client's {@code redirectUris} / {@code webOrigins} (the cluster
         * copies add a non-localhost origin). Everything else is pinned by
         * construction, including fields nobody has thought of yet.
         */
        @Test
        @DisplayName("realm copies differ ONLY in the hostname-shaped fields")
        void realmCopiesDifferOnlyInHostnameFields() throws IOException {
            JsonNode reference = hostAgnostic(JSON.readTree(COMPOSE_REALM.toFile()));
            for (Path realm : List.of(KUSTOMIZE_REALM, HELM_REALM)) {
                assertEquals(reference, hostAgnostic(JSON.readTree(realm.toFile())),
                        realm + " differs from " + COMPOSE_REALM + " in something other than loginTheme, "
                                + "redirectUris or webOrigins. Those three are the only fields a cluster copy "
                                + "may change; anything else is drift that reaches only one delivery path");
            }
        }

        /**
         * Strips the fields a cluster copy is allowed to re-point: the login theme, and
         * every client's redirect/origin allow-lists.
         */
        private static JsonNode hostAgnostic(JsonNode realm) {
            ObjectNode copy = realm.deepCopy();
            copy.remove("loginTheme");
            for (JsonNode clientNode : copy.path("clients")) {
                ((ObjectNode) clientNode).remove(List.of("redirectUris", "webOrigins"));
            }
            return copy;
        }

        /**
         * The chart does not template the realm — it ships a copy and string-replaces
         * one literal host in it at render time, so {@code keycloak.publicOrigin} works
         * only for as long as that literal is still in the file. Nothing couples the
         * two: they live in different files, in different languages, and
         * {@code replace} on a string that is absent is a no-op that returns the input
         * unchanged. Edit the placeholder host in the realm and Helm still exits 0,
         * publicOrigin becomes inert, the realm goes on allowing localhost only, and
         * login answers {@code Invalid parameter: redirect_uri}.
         * <p>
         * clusterRealmsAreClusterCalibrated does not see this: it asks only that SOME
         * non-localhost redirect exists, which any other placeholder satisfies just as
         * well. This asserts they are the SAME host.
         */
        @Test
        @DisplayName("the chart's replace literal is the placeholder the realm actually carries")
        void publicOriginSubstitutionMatchesTheShippedPlaceholder() throws IOException {
            String template = read(HELM_KEYCLOAK);
            String placeholder = captureAfter(template,
                    "\\.Files\\.Get\\s+\"files/eddi-realm\\.json\"\\s*\\|\\s*replace\\s+\"([^\"]+)\"");
            assertFalse(placeholder.isBlank(),
                    HELM_KEYCLOAK + " must render files/eddi-realm.json through `replace \"<placeholder>\"`; "
                            + "that substitution is the only route keycloak.publicOrigin has into the realm");

            // `default` may be wrapped (trimSuffix, for one), so the pattern reaches
            // across the rest of that one line rather than pinning the exact shape.
            String fallback = captureAfter(template, "\\$realmOrigin\\s*:=[^\\n]*?default\\s+\"([^\"]+)\"");
            assertEquals(placeholder, fallback,
                    HELM_KEYCLOAK + " defaults $realmOrigin to `" + fallback + "` while replacing `"
                            + placeholder + "`. With publicOrigin unset the default has to be the placeholder "
                            + "itself, so the replace is a no-op rather than a rewrite to some other host");

            JsonNode spa = client(JSON.readTree(HELM_REALM.toFile()), "eddi-frontend");
            List<String> redirects = stringList(spa.get("redirectUris"));
            List<String> origins = stringList(spa.get("webOrigins"));
            assertTrue(redirects.stream().anyMatch(uri -> uri.startsWith(placeholder)),
                    HELM_REALM + " lists no redirectUri on " + placeholder + ", the host " + HELM_KEYCLOAK
                            + " substitutes. `replace` finds nothing, returns the realm untouched, and Helm "
                            + "exits 0: keycloak.publicOrigin is inert and the realm still allows only "
                            + "localhost, so login fails with `Invalid parameter: redirect_uri`. Redirects: "
                            + redirects);
            assertTrue(origins.contains(placeholder),
                    HELM_REALM + " lists no webOrigin equal to " + placeholder + ", so even a matched "
                            + "redirect leaves the SPA's XHRs blocked by CORS. values.yaml promises "
                            + "publicOrigin is substituted in BOTH lists. Origins: " + origins);

            List<String> componentRedirects = stringList(client(JSON.readTree(KUSTOMIZE_REALM.toFile()), "eddi-frontend").get("redirectUris"));
            assertTrue(componentRedirects.stream().anyMatch(uri -> uri.startsWith(placeholder)),
                    KUSTOMIZE_REALM + " carries a different placeholder host than " + HELM_REALM
                            + ". The kustomize path has no substitution at all — the operator edits the file by "
                            + "hand — so " + AUTH_COMPONENT + "'s header names the placeholder to look for, and "
                            + "the two copies must agree on it. Redirects: " + componentRedirects);
            assertTrue(read(AUTH_COMPONENT).contains(placeholder),
                    AUTH_COMPONENT + " must name " + placeholder + " as the placeholder to replace; it is the "
                            + "only instruction the kustomize path gets");
        }

        @Test
        @DisplayName("docs name the client id the code actually asks for")
        void docsNameTheRealClientId() throws IOException {
            String security = read(SECURITY_DOC);
            assertFalse(security.contains("`eddi-manager`"),
                    "docs/security.md names a client id that neither the realm nor RestManagerResource uses; "
                            + "an operator provisioning Keycloak from it gets invalid_client at login");
            assertTrue(security.contains("`eddi-frontend`"),
                    "docs/security.md should name eddi-frontend, the id the Manager SPA requests tokens for");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helm chart
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("helm chart")
    class Helm {

        private static final Pattern VALUE_REFERENCE = Pattern.compile("\\.Values\\.([A-Za-z0-9_.]+)");

        /**
         * Sub-keys of a toggle whose "on" state makes the chart FAIL to render. They
         * are unreachable rather than dead — no configuration gets far enough to read
         * them — and values.yaml documents them as the shape a distributed conversation
         * coordinator will need. The {@code fail} gate that justifies the exemption is
         * asserted below, so lifting it re-opens the check on them.
         */
        private static final Set<String> UNREACHABLE_BEHIND_A_FAIL_GATE = Set.of(
                "autoscaling.minReplicas",
                "autoscaling.maxReplicas",
                "autoscaling.targetCPUUtilizationPercentage",
                "autoscaling.targetMemoryUtilizationPercentage");

        /**
         * A value no template reads is worse than a missing one: {@code --set
         * monitoring.prometheus.enabled=true} rendered nothing, exited 0 and reported
         * success, so the operator believed metrics collection was deployed.
         * {@code namespace} was the same shape — Helm takes the namespace from
         * {@code --namespace}, so setting the value put the release somewhere else with
         * no error.
         * <p>
         * Both of those were top-level keys, and matching only top-level keys is what
         * this check used to do — which left every NESTED value the chart added
         * (keycloak.storage.*, nats.buildProfileImage, eddi.datastore.external*)
         * outside it. It now walks values.yaml to its leaves and matches dotted
         * reference paths, so a template that stops reading a sub-key is caught too. A
         * leaf counts as consumed when a reference names it, names an ancestor of it
         * ({@code toYaml .Values.eddi.resources} covers the whole subtree) or names a
         * descendant of it.
         */
        @Test
        @DisplayName("every value is consumed by a template, at every depth")
        void noDeadValues() throws IOException {
            Set<String> referenced = new TreeSet<>();
            for (Path template : templates()) {
                Matcher matcher = VALUE_REFERENCE.matcher(stripGoComments(read(template)));
                while (matcher.find()) {
                    String reference = matcher.group(1);
                    while (reference.endsWith(".")) {
                        reference = reference.substring(0, reference.length() - 1);
                    }
                    referenced.add(reference);
                }
            }

            assertTrue(read(HELM_TEMPLATES.resolve("deployment.yaml"))
                    .contains("{{- fail \"autoscaling.enabled=true is not supported"),
                    "the autoscaling sub-keys are exempted from this check ONLY because enabling autoscaling "
                            + "fails rendering outright. That gate is gone, so they are settable and dead again "
                            + "— implement or delete them, and drop " + UNREACHABLE_BEHIND_A_FAIL_GATE);

            Set<String> dead = new TreeSet<>();
            for (String leaf : leafPaths(YAML.readTree(HELM.resolve("values.yaml").toFile()), "")) {
                boolean consumed = referenced.stream().anyMatch(reference -> reference.equals(leaf)
                        || leaf.startsWith(reference + ".")
                        || reference.startsWith(leaf + "."));
                if (!consumed && !UNREACHABLE_BEHIND_A_FAIL_GATE.contains(leaf)) {
                    dead.add(leaf);
                }
            }
            assertTrue(dead.isEmpty(),
                    "values.yaml offers settings no template consumes, so setting them renders nothing and "
                            + "exits 0: " + dead + ". Either implement them or delete them.");
        }

        /**
         * Gating {@code serviceAccountName} on {@code serviceAccount.create} discarded
         * {@code serviceAccount.name} in the bring-your-own-SA case (EKS IRSA, GKE
         * Workload Identity, AKS pod identity): the pod silently ran under the
         * namespace's default ServiceAccount and never assumed the cloud IAM role bound
         * to the named one.
         */
        @Test
        @DisplayName("serviceAccountName is always rendered")
        void serviceAccountNameAlwaysRendered() throws IOException {
            String deployment = read(HELM_TEMPLATES.resolve("deployment.yaml"));
            assertTrue(deployment.contains("serviceAccountName: {{ include \"eddi.serviceAccountName\" . }}"),
                    "deployment.yaml must render serviceAccountName from the helper");
            assertFalse(deployment.contains("{{- if .Values.serviceAccount.create }}"),
                    "deployment.yaml must not gate serviceAccountName on serviceAccount.create — the helper "
                            + "already returns the right name when create=false, and gating throws that away");
        }

        /**
         * The convention is {@code <name>-<version>}. Rendering only the name made the
         * label the constant string "eddi", so the standard way to ask a live cluster
         * which chart revision produced an object matched nothing.
         */
        @Test
        @DisplayName("helm.sh/chart carries the chart version")
        void chartLabelCarriesVersion() throws IOException {
            String helpers = read(HELM_TEMPLATES.resolve("_helpers.tpl"));
            assertTrue(helpers.contains("helm.sh/chart: {{ printf \"%s-%s\" .Chart.Name .Chart.Version"),
                    "_helpers.tpl must render helm.sh/chart as <chart name>-<chart version>");
        }

        /**
         * The chart fails loudly everywhere else. These are the two branches that used
         * to fail silently: a datastore that resolves to nothing, and an OIDC
         * auth-server URL naming a Service this chart never creates.
         */
        @Test
        @DisplayName("silent branches fail loudly instead")
        void silentBranchesFailLoudly() throws IOException {
            String configmap = read(HELM_TEMPLATES.resolve("configmap.yaml"));
            assertTrue(configmap.contains("eddi.datastore.externalConnectionString")
                    && configmap.contains("eddi.datastore.externalJdbcUrl"),
                    "the chart must offer an external-datastore knob; with both in-chart datastores off it "
                            + "otherwise starts against the built-in mongodb://mongodb:27017/eddi default");
            assertTrue(configmap.contains("{{- fail \"No datastore configured"),
                    "disabling both datastores with no external one supplied must fail rendering");
            assertTrue(configmap.contains("{{ required \"eddi.oidc.authServerUrl is required"),
                    "authServerUrl must be required when bringing your own IdP, not defaulted to a Service "
                            + "the chart never creates");

            JsonNode values = YAML.readTree(HELM.resolve("values.yaml").toFile());
            assertEquals("", values.path("eddi").path("oidc").path("authServerUrl").asText(),
                    "eddi.oidc.authServerUrl must have no default: the old one named a bare `keycloak` "
                            + "Service that only the kustomize overlay creates");
        }

        /**
         * The datastore gate above only asks that SOME datastore is configured — and
         * then, in its own failure message, asks the operator to "match
         * eddi.datastoreType to it" without checking that they did.
         * {@code EDDI_DATASTORE_TYPE} is what EDDI actually switches on and it defaults
         * to {@code mongodb}, so supplying only {@code externalJdbcUrl} passed the
         * gate, rendered no MONGODB_CONNECTIONSTRING, and left the pod booting the
         * MongoDB datastore against application.properties' baked-in
         * {@code mongodb://mongodb:27017/eddi} — the exact DNS-failure death the gate
         * exists to prevent, one forgotten value away, and reachable by forgetting the
         * value the message itself names.
         */
        @Test
        @DisplayName("the datastore type must agree with the datastore actually configured")
        void datastoreTypeIsEnforcedNotMerelyRequested() throws IOException {
            String configmap = read(HELM_TEMPLATES.resolve("configmap.yaml"));
            assertTrue(configmap.contains("$postgresConfigured") && configmap.contains("$mongoConfigured"),
                    "configmap.yaml must derive which datastore is actually configured rather than trusting "
                            + "eddi.datastoreType, which defaults to mongodb and is checked by nothing");
            assertEquals(2, countOccurrences(configmap, "but the datastore actually configured is"),
                    "both directions have to fail: a postgres datastore with datastoreType=mongodb, and a "
                            + "mongodb datastore with datastoreType=postgres. Only one of the two guards is "
                            + "present, so the other mismatch still renders and dies on the readiness probe");
        }

        /**
         * The guard above was written case-INSENSITIVE — {@code lower (toString …)} —
         * against a reader that is case-SENSITIVE, which handed back the whole failure
         * it was added to refuse for the price of one capital letter.
         * {@code DataStoreProducers.isPostgres()} is
         * {@code "postgres".equals(datastoreType)} and PropertiesMigrationService asks
         * {@code "mongodb".equals(…)}; the chart rendered the RAW value into
         * {@code EDDI_DATASTORE_TYPE}. So {@code --set eddi.datastoreType=Postgres}
         * with {@code postgres.enabled=true} satisfied the guard, rendered
         * {@code EDDI_DATASTORE_TYPE: "Postgres"} plus {@code QUARKUS_PROFILE:
         * "postgres"} and no Mongo connection string, matched NEITHER Java branch — the
         * env var is config ordinal 300 and outranks application.properties'
         * {@code %postgres} line at 250 — and booted the MongoDB store against the
         * baked-in {@code mongodb://mongodb:27017/eddi}. values.yaml meanwhile promised
         * "the chart refuses to render when it disagrees, rather than booting the other
         * store".
         * <p>
         * Asserted against the Java as well as the chart, in both directions: the
         * template may only relax its comparison if the code it feeds relaxes first. A
         * guard is allowed to be stricter than its consumer; it is never allowed to be
         * looser.
         */
        @Test
        @DisplayName("the datastore type is compared as exactly as the Java that reads it")
        void datastoreTypeIsComparedTheWayJavaComparesIt() throws IOException {
            String producers = read(DATASTORE_PRODUCERS);
            assertTrue(producers.contains("\"postgres\".equals(datastoreType)"),
                    DATASTORE_PRODUCERS + " no longer selects the store with an exact equals(). The chart's "
                            + "guard is written to that comparison — if this became case-insensitive the "
                            + "guard may relax with it, but the two must be changed together, never one of "
                            + "them");
            assertFalse(producers.contains("equalsIgnoreCase"),
                    DATASTORE_PRODUCERS + " matches the datastore type case-insensitively now; update the "
                            + "chart guard and this test together");

            String configmap = stripGoComments(read(HELM_TEMPLATES.resolve("configmap.yaml")));
            assertTrue(configmap.contains("$declaredType := toString (default \"\" .Values.eddi.datastoreType)"),
                    "configmap.yaml must bind eddi.datastoreType WITHOUT lowering it. `lower` made the guard "
                            + "case-insensitive while its only reader compares with equals(), so "
                            + "datastoreType=Postgres passed the guard and booted the MongoDB store — the "
                            + "exact outcome the guard exists to refuse");
            assertTrue(configmap.contains("has $declaredType (list \"mongodb\" \"postgres\")"),
                    "configmap.yaml must refuse every spelling EDDI does not match, not merely the two "
                            + "mismatches. \"Postgres\", \"MONGODB\" and \"mongo\" all render happily, match "
                            + "neither Java branch, and silently fall through to the MongoDB store");
            assertTrue(configmap.contains("EDDI_DATASTORE_TYPE: {{ $declaredType | quote }}"),
                    "the ConfigMap must render the VALIDATED binding rather than "
                            + ".Values.eddi.datastoreType. Rendering the raw value is how a guarded string "
                            + "and the string EDDI actually reads came apart in the first place");
        }

        /**
         * {@code mongodb.enabled} defaults to TRUE, so a values file that only ever
         * said {@code postgres.enabled: true} + {@code datastoreType: postgres} — the
         * shape that rendered before the type guards existed, wasting a MongoDB
         * StatefulSet — reached those guards with BOTH kinds configured, and they
         * contradicted each other. The PostgreSQL guard demanded
         * {@code datastoreType=mongodb}; following that advice hit the MongoDB guard,
         * which demanded {@code datastoreType=postgres}. Each also told the operator
         * the store they had named was "a database that is not there" when
         * {@code postgres.enabled=true} had demonstrably deployed one, and neither
         * message named the real cause.
         * <p>
         * Refused up front, before the type checks, so exactly one KIND can be
         * configured by the time they run — which is what makes that sentence true
         * wherever they print it.
         */
        @Test
        @DisplayName("two datastores of different kinds are refused before the type guards run")
        void twoDatastoresOfDifferentKindsAreRefused() throws IOException {
            String configmap = stripGoComments(read(HELM_TEMPLATES.resolve("configmap.yaml")));
            int refusal = configmap.indexOf("{{- if and $postgresConfigured $mongoConfigured }}");
            assertTrue(refusal >= 0,
                    "configmap.yaml must refuse a MongoDB and a PostgreSQL datastore configured at once. "
                            + "mongodb.enabled defaults to true, so `--set postgres.enabled=true` alone is "
                            + "that shape, and the two type guards then refuse it with mutually contradictory "
                            + "instructions instead of naming the cause");
            assertTrue(configmap.contains("Two datastores of different kinds are configured"),
                    "the refusal must say what is actually wrong — two kinds at once — rather than leave the "
                            + "operator with two messages each demanding the value the other rejects");
            assertTrue(configmap.contains("mongodb.enabled defaults to TRUE"),
                    "the message must name the default that produces this shape; an operator who set exactly "
                            + "one value cannot otherwise see why two are reported");

            int firstTypeGuard = configmap.indexOf("but the datastore actually configured is");
            assertTrue(firstTypeGuard > refusal,
                    "the different-kinds refusal has to come BEFORE the type guards. Behind them it is "
                            + "unreachable, and their \"a database that is not there\" is a falsehood whenever "
                            + "both kinds are configured");
        }

        /**
         * Sprig's {@code toString} is {@code fmt.Sprintf("%v", …)}, so a nil renders as
         * the literal string {@code <nil>} — six characters, non-empty, and therefore
         * TRUTHY to a Go template. Every guard in this chart decides by asking whether
         * a value is set, so stringifying one before testing it does not make the test
         * safe, it inverts it. Both directions were shipped in a single edit:
         * <ul>
         * <li>{@code trimSuffix "/" (toString .Values.eddi.oidc.publicUrl)} made the
         * publicUrl guard stop firing, so a values file with a bare {@code publicUrl:}
         * rendered {@code EDDI_KEYCLOAK_PUBLIC_URL: "<nil>"} and the issuer
         * {@code "<nil>/realms/eddi"} with exit 0 — the Manager SPA was handed
         * {@code <nil>} as its Keycloak authority, every authenticated request 401ing,
         * and nothing to see before deploying.</li>
         * <li>{@code ne (toString $externalJdbc) ""} made the datastore-type guard fire
         * when it should not, so a bare {@code externalJdbcUrl:} refused a stock
         * MongoDB install with a message naming a PostgreSQL that appears nowhere in
         * the values.</li>
         * </ul>
         * A nil is what an optional value in a values file actually IS — {@code ""} is
         * only what {@code --set x=} and the shipped placeholder give — so the rule is
         * structural rather than per-guard: coalesce with {@code default} (or test with
         * {@code empty}) FIRST, stringify after. It is what the pre-existing gate at
         * the top of configmap.yaml has always done, in plain Go truthiness.
         * <p>
         * Asserted over every template rather than the one that broke, because the
         * defect is the idiom, and the idiom spread to three call sites at once.
         */
        @Test
        @DisplayName("no chart guard stringifies a value before coalescing it")
        void chartGuardsCoalesceBeforeTheyStringify() throws IOException {
            // `toString .Values.x` and `toString $derivedFromValues` — the two forms
            // that can reach a nil. `toString (default "" .Values.x)` is the sanctioned
            // spelling and does not match: the parenthesis is what makes it safe.
            Pattern unguarded = Pattern.compile("toString\\s+(\\.Values\\.[\\w.]+|\\$\\w+)");
            for (Path template : templates()) {
                Matcher matcher = unguarded.matcher(stripGoComments(read(template)));
                assertFalse(matcher.find(),
                        template + " applies toString straight to a chart value: `"
                                + (matcher.reset().find() ? matcher.group() : "") + "`. Sprig's toString is "
                                + "fmt.Sprintf(\"%v\", …), so a nil — which is what a values file's bare "
                                + "`key:` holds — becomes the literal, non-empty, TRUTHY string \"<nil>\". A "
                                + "guard written that way either stops firing (publicUrl rendered \"<nil>\" as "
                                + "the Keycloak authority, exit 0) or starts firing on a correct install (a "
                                + "bare externalJdbcUrl refused a stock MongoDB deployment, naming "
                                + "PostgreSQL). Coalesce first: `toString (default \"\" .Values.x)`, or test "
                                + "with `empty`");
            }

            String configmap = stripGoComments(read(HELM_TEMPLATES.resolve("configmap.yaml")));
            assertTrue(configmap.contains("trimSuffix \"/\" (toString (default \"\" .Values.eddi.oidc.publicUrl))"),
                    "the publicUrl guard must coalesce BEFORE it trims and stringifies. The order is the whole "
                            + "point: `default \"\" (toString nil)` is \"<nil>\", which defaults to nothing, "
                            + "and the `if not $publicUrl` below it never fires");
            assertTrue(configmap.contains("(not (empty $externalJdbc))")
                    && configmap.contains("(not (empty $externalMongo))"),
                    "the datastore-type guard must decide 'is an external datastore configured' with `empty`, "
                            + "which treats nil and \"\" alike, rather than by comparing a stringified value "
                            + "to \"\" — that comparison counts a nil as configured and refuses installs which "
                            + "have no such datastore at all");
        }

        /**
         * The chart accepted two datastores of one kind and then picked between them by
         * SmallRye config ordinal. {@code mongodb.enabled} defaults to true, so leaving
         * it alone while setting {@code externalConnectionString} — one stale line in
         * an upgraded values file is enough — deployed an in-chart MongoDB StatefulSet,
         * printed "MongoDB (in-chart)" in NOTES.txt, and connected the pod to the
         * EXTERNAL database: the external URI rides in the mounted properties file
         * (QUARKUS_CONFIG_LOCATIONS, ordinal 400) and outranks the ConfigMap env var
         * (ordinal 300). Which database EDDI talks to changed, with no render error and
         * an install note saying the opposite; from the operator's side every
         * conversation and agent had vanished.
         * <p>
         * Refused rather than resolved. Silently preferring either one is a decision
         * about where the data lives, and the chart does not get to make that quietly —
         * the same reasoning as the datastore-type guard beside it.
         */
        @Test
        @DisplayName("an in-chart datastore alongside an external one of the same kind is refused")
        void twoDatastoresOfOneKindAreRefused() throws IOException {
            String configmap = read(HELM_TEMPLATES.resolve("configmap.yaml"));
            assertTrue(configmap.contains("{{- if and .Values.mongodb.enabled $externalMongo }}"),
                    "configmap.yaml must refuse mongodb.enabled=true together with "
                            + "eddi.datastore.externalConnectionString. secret.yaml renders the external URI "
                            + "into the mounted properties file, whose config ordinal (400) beats the "
                            + "ConfigMap env var (300), so the external database wins while the chart deploys "
                            + "and reports an in-chart one");
            assertTrue(configmap.contains("{{- if and .Values.postgres.enabled $externalJdbc }}"),
                    "the same must hold for PostgreSQL: postgres.enabled=true with "
                            + "eddi.datastore.externalJdbcUrl renders the in-chart URL and silently drops the "
                            + "external one — including its credentials, which secret.yaml then never renders");
            assertEquals(2, countOccurrences(configmap, "datastores are configured"),
                    "both kinds need the refusal; with one of the two guards missing, the combination it "
                            + "would have caught still resolves itself by config ordinal");
        }

        /**
         * {@code eddi.datastore.externalJdbcUrl} rendered a JDBC URL and nothing else:
         * no username, no password, no Secret. The pod died on "password authentication
         * failed", and the only way through was {@code ?user=…&password=…} appended to
         * the URL — which lands in a ConfigMap in plaintext, contradicting the chart's
         * own "secrets arrive as FILES, never env" design. The credentials therefore go
         * into the projected {@code <release>-secrets} properties file the pod already
         * mounts.
         * <p>
         * The same applies to {@code externalConnectionString}: values.yaml documents
         * it as {@code mongodb://user:pass@host:27017/eddi}, so rendering it into
         * eddi-config published a database credential to every principal with
         * {@code get configmaps} in the namespace.
         */
        @Test
        @DisplayName("external datastore credentials go in the mounted Secret, never the ConfigMap")
        void externalDatastoreCredentialsAreNotInTheConfigMap() throws IOException {
            String configmap = stripGoComments(read(HELM_TEMPLATES.resolve("configmap.yaml")));
            String secret = read(HELM_TEMPLATES.resolve("secret.yaml"));

            assertFalse(configmap.contains("MONGODB_CONNECTIONSTRING: {{ $externalMongo"),
                    "configmap.yaml renders eddi.datastore.externalConnectionString into eddi-config. That "
                            + "value is documented as mongodb://user:pass@host:27017/eddi — a credential — and "
                            + "a ConfigMap is readable by anything holding `get configmaps` in the namespace. "
                            + "It belongs in the projected Secret, like the vault key and the in-chart "
                            + "PostgreSQL credentials");
            assertTrue(secret.contains("mongodb.connectionString={{"),
                    "secret.yaml must carry the external MongoDB connection string, as a Quarkus property "
                            + "name (PersistenceModule reads @ConfigProperty(name = \"mongodb.connectionString\"))");

            assertTrue(secret.contains("quarkus.datasource.username=")
                    && secret.contains("quarkus.datasource.password="),
                    "secret.yaml must render credentials for an external PostgreSQL; without them "
                            + "eddi.datastore.externalJdbcUrl cannot authenticate at all and the only "
                            + "workaround puts a password in a ConfigMap");
            assertEquals(2, countOccurrences(secret, "is required when eddi.datastore.externalJdbcUrl is set"),
                    "both the username and the password must be `required` when externalJdbcUrl is set. A "
                            + "datasource missing either fails at runtime with an error that names neither the "
                            + "chart value nor the missing half");

            for (String value : List.of("externalJdbcUsername", "externalJdbcPassword")) {
                assertTrue(YAML.readTree(HELM.resolve("values.yaml").toFile())
                        .path("eddi").path("datastore").has(value),
                        "values.yaml must document eddi.datastore." + value + "; a `required` on a value the "
                                + "file does not mention is a failure with nowhere to go");
            }
        }

        /**
         * Every setting EDDI reads reaches it from two objects that are not part of the
         * Deployment: eddi-config via {@code envFrom}, and eddi-secrets via the
         * projected volume. A {@code helm upgrade} that changes only those leaves the
         * Deployment spec byte-identical, so Kubernetes creates no new ReplicaSet and
         * the running pod keeps the environment it started with. Enabling OIDC, moving
         * to an external datastore or rotating the vault master key therefore reported
         * a successful upgrade and changed nothing at all until somebody restarted the
         * pod by hand — with no error anywhere to suggest they should.
         * <p>
         * {@code envFrom} is the sharper half: a ConfigMap mounted as a VOLUME is at
         * least refreshed in place by the kubelet, but environment variables are fixed
         * at container start.
         * <p>
         * The hash has to be of the RENDERED template, not of {@code .Values}:
         * configmap.yaml derives most of what it emits — the validated datastore type,
         * the derived unauthenticated flags, the trimmed public URL — so hashing the
         * inputs would miss a change only the output shows. Both spellings are
         * therefore asserted, and both files, because either annotation alone leaves
         * one of the two objects able to change without a rollout.
         */
        @Test
        @DisplayName("a config or secret change rolls the EDDI pod")
        void configurationChangesRollThePod() throws IOException {
            String deployment = stripGoComments(read(HELM_TEMPLATES.resolve("deployment.yaml")));
            for (String source : List.of("configmap.yaml", "secret.yaml")) {
                String annotation = "checksum/" + (source.startsWith("config") ? "config" : "secret");
                String expected = annotation + ": {{ include (print $.Template.BasePath \"/" + source
                        + "\") . | sha256sum }}";
                assertTrue(deployment.contains(expected),
                        HELM_TEMPLATES.resolve("deployment.yaml") + " must carry `" + expected + "` on the pod "
                                + "template. " + source + " is consumed by the pod (envFrom for the ConfigMap, "
                                + "the projected volume for the Secret) but is not part of this Deployment's "
                                + "spec, so `helm upgrade` updates it, changes nothing in the pod template, and "
                                + "Kubernetes rolls no new pod. Environment variables are fixed at container "
                                + "start, so the running EDDI keeps the old configuration — an upgrade that "
                                + "enables OIDC reports success and stays unauthenticated");
            }

            // The annotations block must be unconditional. It used to be wrapped in
            // `{{- with .Values.eddi.podAnnotations }}`, and folding the checksums into
            // that block is the natural-looking tidy-up: it renders identically for
            // anyone who sets podAnnotations and silently drops both checksums for
            // everyone who does not — which is the default.
            int annotations = deployment.indexOf("annotations:");
            int guard = deployment.indexOf("{{- with .Values.eddi.podAnnotations }}");
            assertTrue(annotations >= 0 && (guard < 0 || annotations < guard),
                    HELM_TEMPLATES.resolve("deployment.yaml") + " renders `annotations:` only inside "
                            + "`{{- with .Values.eddi.podAnnotations }}`, so the checksums above disappear on "
                            + "every install that does not set podAnnotations — the default — and the upgrade "
                            + "stops rolling the pod for exactly the installs least likely to notice");
        }

        /**
         * {@code eddi.messaging.type} is read by no Java code, and the NATS coordinator
         * is gated on a BUILD-time profile the published image is not built with.
         * Setting it provisioned a JetStream StatefulSet and a PVC that EDDI could
         * never connect to, while the install notes reported success.
         */
        @Test
        @DisplayName("nats messaging is gated on a build-profile image")
        void natsRequiresABuildProfileImage() throws IOException {
            String configmap = read(HELM_TEMPLATES.resolve("configmap.yaml"));
            assertTrue(configmap.contains("nats.buildProfileImage"),
                    "the chart must refuse eddi.messagingType=nats unless the operator confirms a "
                            + "-Dquarkus.profile=nats image");
            assertTrue(configmap.contains("-Dquarkus.profile=nats"),
                    "the failure message must name the build-profile requirement, which is the part an "
                            + "operator cannot discover from the manifests");
        }

        /**
         * {@code nats.buildProfileImage} says the IMAGE can speak NATS;
         * {@code nats.enabled} is what actually deploys the broker. The guard checked
         * only the first, so
         * {@code eddi.messagingType=nats,nats.buildProfileImage=true} rendered a
         * NATS-profile EDDI with no StatefulSet, no Service and no EDDI_NATS_URL — and
         * the coordinator then falls back to {@code nats://localhost:4222}, inside the
         * EDDI pod, where nothing listens. The inverse wastes a StatefulSet and a PVC
         * on a broker nothing publishes to.
         */
        @Test
        @DisplayName("messaging type and the deployed broker must agree")
        void natsMessagingRequiresADeployedBroker() throws IOException {
            String configmap = read(HELM_TEMPLATES.resolve("configmap.yaml"));
            assertTrue(configmap.contains("{{- fail \"eddi.messagingType is not \\\"in-memory\\\" but nats.enabled=false"),
                    "a non-in-memory messagingType must also require nats.enabled=true: without the broker "
                            + "there is no EDDI_NATS_URL and the coordinator connects to nats://localhost:4222 "
                            + "in its own pod");
            assertTrue(configmap.contains("{{- fail \"nats.enabled=true while eddi.messagingType is"),
                    "the inverse must be refused too: nats.enabled=true with in-memory messaging provisions a "
                            + "JetStream StatefulSet and a PVC that EDDI never connects to, which is the same "
                            + "silent-waste failure the buildProfileImage gate was written for");
        }

        /**
         * The guard coalesced {@code eddi.messagingType} to the values.yaml default
         * while the two places that PRINT it read the raw value, so the chart disagreed
         * with itself about what a null means: {@code --set
         * eddi.messagingType=null} — the shape a values file with a bare
         * {@code messagingType:} has — passed the guard as "in-memory" and then
         * rendered {@code EDDI_MESSAGING_TYPE:} with nothing after it, because Sprig's
         * {@code quote} skips a nil, under an install note reading "Messaging: ".
         * <p>
         * Coalesced once in a helper instead. Cosmetic while no Java reads the
         * property, but a guard and a render that disagree about a value is the shape
         * of every other defect on this chart, and configmap.yaml and NOTES.txt are
         * separate templates — a local {@code $binding} could not have covered both.
         */
        @Test
        @DisplayName("the messaging type is coalesced once, for the guard and both renders alike")
        void messagingTypeIsCoalescedOnce() throws IOException {
            String helpers = read(HELM_TEMPLATES.resolve("_helpers.tpl"));
            assertTrue(helpers.contains("{{- define \"eddi.messagingType\" -}}"),
                    "_helpers.tpl must define eddi.messagingType. configmap.yaml and NOTES.txt are separate "
                            + "templates, so a local binding cannot keep the guard and the two renders in "
                            + "step — and they were already out of step");
            assertTrue(helpers.contains("lower (toString (default \"in-memory\" .Values.eddi.messagingType))"),
                    "the helper must coalesce to the values.yaml default BEFORE stringifying: `toString nil` "
                            + "is the truthy literal \"<nil>\", so the other order refuses a bare "
                            + "`messagingType:` with a message about a NATS broker it never asked for");

            String configmap = stripGoComments(read(HELM_TEMPLATES.resolve("configmap.yaml")));
            assertTrue(configmap.contains("{{- if ne (include \"eddi.messagingType\" .) \"in-memory\" }}"),
                    "the NATS guard must read the helper, not its own inline coalescing");
            assertTrue(configmap.contains("EDDI_MESSAGING_TYPE: {{ include \"eddi.messagingType\" . | quote }}"),
                    "EDDI_MESSAGING_TYPE must render the same coalesced value the guard checked. Rendering "
                            + ".Values.eddi.messagingType raw emitted a valueless `EDDI_MESSAGING_TYPE:` for "
                            + "the null the guard had just read as \"in-memory\"");
            assertTrue(read(HELM_TEMPLATES.resolve("NOTES.txt")).contains("include \"eddi.messagingType\" ."),
                    "NOTES.txt prints the messaging type to the operator, so it must print the coalesced one "
                            + "— it reported a blank \"Messaging: \" for a working in-memory install");
        }

        /**
         * The Service port is a value; the Manager and the notes hardcoded 7070 beside
         * it.
         */
        @Test
        @DisplayName("the printed port-forward honours eddi.service.port")
        void notesHonourServicePort() throws IOException {
            String notes = read(HELM_TEMPLATES.resolve("NOTES.txt"));
            assertFalse(notes.contains("7070:7070"),
                    "NOTES.txt hardcodes 7070 while service.yaml renders the port from a value, so the first "
                            + "debugging step an operator is given fails after --set eddi.service.port=…");
            assertTrue(notes.contains("{{ .Values.eddi.service.port }}"),
                    "NOTES.txt should render the port-forward command from eddi.service.port");
        }

        /**
         * The chart version this test is written against. Bump it in the same commit as
         * helm/eddi/Chart.yaml — see chartVersionRecordsTheBreakingChange.
         */
        private static final String EXPECTED_CHART_VERSION = "2.0.0";

        /**
         * This release removes {@code manager.*}, {@code monitoring.*} and
         * {@code namespace} outright and turns two previously-optional values into
         * render-time requirements, so an upgrade carrying an old values file either
         * fails to render or silently loses a setting. That is a major bump, and
         * Chart.yaml's own comment records why the version had gone stale before: "it
         * sat at 1.0.0 across three releases because nothing enforces this". Nothing
         * enforced it — this does, on both halves. The break it claims has to be real,
         * or the version is just a different lie.
         * <p>
         * The version is pinned EXACTLY, not asserted to be {@code >= 2}. A
         * greater-than assertion is a one-way ratchet: once the major had moved it
         * could never fail again, so the next breaking change shipped under 2.x — the
         * very drift the Chart.yaml comment describes — would pass unnoticed, and the
         * check would read as coverage it is not. Pinned, this test is the thing you
         * bump alongside Chart.yaml, which is the point. (Parsing the major out of the
         * string is gone with it, and with it an unguarded NumberFormatException.)
         */
        @Test
        @DisplayName("the chart version records the break the values file took")
        void chartVersionRecordsTheBreakingChange() throws IOException {
            JsonNode chart = YAML.readTree(HELM.resolve("Chart.yaml").toFile());
            String version = chart.path("version").asText();
            // Shape before value. Exact equality implies semver only for as long as the
            // constant happens to be one: bump both to "2.0" or "v2.0.0" and the
            // equality still passes while chart repositories, which key on this field,
            // reject it. The message below promises the field is checked; this is the
            // half that checks the field rather than the constant.
            assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"),
                    "helm/eddi/Chart.yaml's version must be semver MAJOR.MINOR.PATCH, not " + version
                            + " — Helm rejects a chart whose version is not, and `helm package` names the "
                            + "tarball from it");
            assertEquals(EXPECTED_CHART_VERSION, version,
                    "helm/eddi/Chart.yaml is at " + version + " and this test expects "
                            + EXPECTED_CHART_VERSION + ". Chart repositories key on this field, so shipping two "
                            + "different chart contents under one version leaves caches unable to tell them "
                            + "apart — and the last time nothing enforced it, the version sat still across three "
                            + "releases. If you changed anything under helm/, bump Chart.yaml AND this constant "
                            + "together: major for a values file that no longer renders (this release dropped "
                            + "manager.*, monitoring.* and namespace, and made eddi.oidc.publicUrl / "
                            + "nats.buildProfileImage render-time requirements), minor or patch otherwise");

            JsonNode values = YAML.readTree(HELM.resolve("values.yaml").toFile());
            for (String removed : List.of("manager", "monitoring", "namespace")) {
                assertFalse(values.has(removed),
                        "values.yaml still offers `" + removed + "`, which the major version bump is justified "
                                + "by removing. Either it is gone and the bump stands, or it is back and the "
                                + "version is telling operators about a break that did not happen");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Manager UI
    // ─────────────────────────────────────────────────────────────

    /**
     * The Manager is bundled into the EDDI image and served at /manage. Both
     * delivery paths additionally deployed labsai/eddi-config-ui — a UI generation
     * this repository no longer builds or publishes — at the mutable {@code
     * :latest} tag that every other image reference here deliberately avoids.
     */
    @Test
    @DisplayName("no manifest deploys the retired standalone Manager image")
    void noStandaloneManagerImage() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : Stream.concat(walk(K8S), walk(HELM)).toList()) {
            if (stripComments(read(file)).contains("eddi-config-ui")) {
                offenders.add(file.toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "labsai/eddi-config-ui is not built or published by this repository, and EDDI already serves "
                        + "the Manager at /manage. Offenders: " + offenders);
        assertFalse(Files.exists(K8S.resolve("overlays/manager")), "k8s/overlays/manager should be gone");
        assertFalse(Files.exists(HELM_TEMPLATES.resolve("manager.yaml")),
                "helm/eddi/templates/manager.yaml should be gone");
    }

    /**
     * The workload is gone; the ROUTE to it outlived it. The ingress overlay kept a
     * commented-out {@code /manage} rule pointing at a separate
     * {@code eddi-manager} Service, captioned "Uncomment to expose Manager UI on
     * the same host" — an invitation to break the thing it claims to enable.
     * Uncommenting it sends {@code /manage} at a Service no manifest creates, which
     * takes the real Manager (EDDI serves it from its own Service,
     * IRestManagerResource) and {@code /manage/__auth_config__.js} — the endpoint
     * that tells the SPA where Keycloak is — off the ingress entirely. Behind an
     * ingress, that is the difference between a Manager that logs in and a blank
     * page.
     * <p>
     * Read RAW rather than through {@code stripComments}, because a commented-out
     * rule beside an instruction to uncomment it is exactly as live as an
     * uncommented one — and the ingress is parsed as well, so the surviving rules
     * are asserted to name the Service that actually exists rather than merely to
     * omit one that does not. {@code noStandaloneManagerImage} above cannot see
     * either: it matches the IMAGE name, and this rule names a Service.
     */
    @Test
    @DisplayName("no ingress routes /manage away from the EDDI Service that serves it")
    void ingressDoesNotRouteManageToARetiredService() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : Stream.concat(walk(K8S), walk(HELM)).toList()) {
            if (read(file).contains("name: eddi-manager")) {
                offenders.add(file.toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "a manifest names an `eddi-manager` backend Service, which nothing in this repository "
                        + "creates — the Manager is served by EDDI itself at /manage. Even commented out it is "
                        + "a defect: the block it replaced was captioned \"Uncomment to expose Manager UI\", "
                        + "and following that takes /manage and /manage/__auth_config__.js off the ingress. "
                        + "Offenders: " + offenders);

        JsonNode ingress = documentOfKind(K8S.resolve("overlays/ingress/ingress.yaml"), "Ingress");
        List<String> backends = new ArrayList<>();
        for (JsonNode rule : ingress.path("spec").path("rules")) {
            for (JsonNode path : rule.path("http").path("paths")) {
                backends.add(path.path("path").asText() + " -> "
                        + path.path("backend").path("service").path("name").asText());
            }
        }
        assertEquals(List.of("/ -> eddi"), backends,
                "k8s/overlays/ingress/ingress.yaml should route the whole host at the `eddi` Service, which "
                        + "already serves the API, the Manager at /manage and the SPA's auth config. Any "
                        + "second rule is a chance to route one of those somewhere it is not served. Rules: "
                        + backends);
    }

    // ─────────────────────────────────────────────────────────────
    // Namespace portability and monitoring
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("namespace portability")
    class NamespacePortability {

        /**
         * Kustomize's namespace transformer rewrites metadata.namespace and RoleBinding
         * subjects. It does NOT rewrite matchLabels values or ConfigMap payloads — so
         * an operator who changed {@code namespace:} got a NetworkPolicy selecting a
         * namespace that did not exist (nothing could reach EDDI, with policyTypes:
         * Ingress in force) and a Prometheus watching the wrong namespace. Both
         * failures are invisible in {@code kubectl get}.
         */
        @Test
        @DisplayName("the network policy selects its own namespace, not a literal one")
        void networkPolicyIsNamespaceAgnostic() throws IOException {
            String policy = stripComments(read(K8S.resolve("overlays/production/network-policy.yaml")));
            assertFalse(policy.contains("kubernetes.io/metadata.name: eddi"),
                    "network-policy.yaml hardcodes the eddi namespace; a bare `podSelector: {}` already means "
                            + "\"every pod in this policy's own namespace\" and follows `namespace:`");
            assertTrue(policy.contains("- podSelector: {}"),
                    "the in-namespace ingress rule should use a bare podSelector");
        }

        @Test
        @DisplayName("prometheus discovers pods in its own namespace")
        void prometheusScrapesOwnNamespace() throws IOException {
            String stack = stripComments(read(K8S.resolve("overlays/monitoring/monitoring-stack.yaml")));
            assertTrue(stack.contains("own_namespace: true"),
                    "the Prometheus scrape config must use `namespaces: own_namespace: true`; a literal name "
                            + "inside a ConfigMap payload is not rewritten by kustomize, and the symptom is a "
                            + "silently empty scrape");
        }
    }

    @Nested
    @DisplayName("monitoring stack")
    class Monitoring {

        /**
         * In a relabel_config {@code separator} is the string placed BETWEEN
         * concatenated source label values (default ";"). Overriding it to ":" made the
         * input {@code 10.1.2.3:7070}, which the regex {@code (.+);(.+)} can never
         * match — and a replace action whose regex does not match is a no-op. It looked
         * like it worked only because role:pod's default __address__ is already
         * podIP:containerPort here.
         */
        @Test
        @DisplayName("the __address__ relabel rule can actually match")
        void addressRelabelRuleMatches() throws IOException {
            String stack = stripComments(read(K8S.resolve("overlays/monitoring/monitoring-stack.yaml")));
            assertFalse(stack.contains("separator: ':'"),
                    "a ':' separator makes the concatenated input unmatchable by the (.+);(.+) regex beside "
                            + "it, so the rule silently never fires");
        }

        /**
         * The component advertises "Prometheus + Grafana" and shipped a Grafana with no
         * datasource — which an operator had to add by hand, on emptyDir storage that
         * discards it at the next restart. Provisioning is declarative, so it survives.
         */
        @Test
        @DisplayName("grafana ships with its prometheus datasource provisioned")
        void grafanaDatasourceIsProvisioned() throws IOException {
            String stack = stripComments(read(K8S.resolve("overlays/monitoring/monitoring-stack.yaml")));
            assertTrue(stack.contains("/etc/grafana/provisioning/datasources"),
                    "Grafana must mount a provisioning ConfigMap, otherwise the component delivers half of "
                            + "what it advertises and the manual fix dies with the pod");
            assertTrue(stack.contains("url: http://prometheus:9090"),
                    "the provisioned datasource must point at the Prometheus Service this file declares");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PostgreSQL
    // ─────────────────────────────────────────────────────────────

    /**
     * The postgres Secret carries {@code postgres-secrets.properties} alongside the
     * POSTGRES_* keys, and that name is not a legal C_IDENTIFIER. Consumed with
     * {@code envFrom}, the kubelet skipped it and logged an
     * InvalidEnvironmentVariableNames warning on every pod start — permanent noise
     * on the database pod that trains operators to ignore its events.
     */
    @Test
    @DisplayName("postgres takes named secret keys, not envFrom over a mixed Secret")
    void postgresUsesNamedSecretKeys() throws IOException {
        for (Path manifest : List.of(
                K8S.resolve("overlays/postgres/postgres-statefulset.yaml"),
                HELM_TEMPLATES.resolve("postgres.yaml"))) {
            String text = stripComments(read(manifest));
            assertFalse(text.contains("envFrom:"),
                    manifest + " uses envFrom over a Secret that also holds postgres-secrets.properties, whose "
                            + "name is not a legal environment-variable name; use explicit secretKeyRef entries");
            assertTrue(text.contains("key: POSTGRES_PASSWORD"),
                    manifest + " should inject POSTGRES_PASSWORD through an explicit secretKeyRef");
        }
    }

    /**
     * POSTGRES_PASSWORD is consumed only by initdb against an empty PGDATA, and
     * PGDATA lives on a claim that survives every restart. The file's own header
     * invited operators to change it "for production" — on a running install that
     * rotates EDDI's half of the credential and locks it out of its own database.
     */
    @Test
    @DisplayName("the postgres credential warning says WHEN it can be changed")
    void postgresPasswordWarningNamesInitdb() throws IOException {
        for (Path source : List.of(
                K8S.resolve("overlays/postgres/postgres-secret.yaml"),
                HELM.resolve("values.yaml"))) {
            String text = read(source);
            assertTrue(text.contains("initdb"),
                    source + " must say that POSTGRES_PASSWORD is read only by initdb against an empty PGDATA, "
                            + "so changing it later locks EDDI out rather than rotating anything");
            assertTrue(text.contains("ALTER ROLE"),
                    source + " must give the actual rotation procedure, not just the warning");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Documentation drift
    // ─────────────────────────────────────────────────────────────

    /**
     * The manifests pin an immutable patch version on purpose — that is what makes
     * the cosign/SLSA attestation mean anything. A diagram advertising the mutable
     * tag invites a reader to copy it and defeat that.
     */
    @Test
    @DisplayName("no operator-facing doc advertises the mutable image tag the manifests refuse")
    void docsDoNotAdvertiseMutableTag() throws IOException {
        for (Path doc : OPERATOR_DOCS) {
            assertFalse(read(doc).contains("labsai/eddi:latest"),
                    doc + " advertises labsai/eddi:latest while k8s/base/eddi-deployment.yaml pins an "
                            + "immutable patch version under a comment forbidding exactly that");
        }
    }

    /**
     * The production overlay deliberately ships no HPA; both doc copies said it
     * did, and correcting only docs/kubernetes.md left README repeating it.
     */
    @Test
    @DisplayName("no operator-facing doc describes the production overlay as shipping an HPA")
    void docsDescribeProductionOverlayContents() throws IOException {
        for (Path doc : OPERATOR_DOCS) {
            assertFalse(read(doc).contains("HPA, PDB, NetworkPolicy"),
                    doc + " lists an HPA among the production overlay's contents; it deliberately ships NONE "
                            + "(k8s/overlays/production/kustomization.yaml), so the claim sends readers looking "
                            + "for an autoscaler that was removed on purpose");
        }
    }

    /**
     * No shipped manifest creates {@code eddi-secrets} any more — a reconciled
     * Secret would overwrite a live vault master key — so
     * {@code k8s/create-secrets.sh} became a mandatory FIRST step for every
     * kustomize path. docs/kubernetes.md was rewritten for that; README, which
     * prints the same commands, was not, and the failure it produces is a pod that
     * sits in {@code ContainerCreating} forever with nothing in the reader's
     * terminal to explain why.
     */
    @Test
    @DisplayName("every doc that prints `kubectl apply -k` also says to create the Secret first")
    void docsRouteKustomizeInstallsThroughCreateSecrets() throws IOException {
        for (Path doc : OPERATOR_DOCS) {
            String text = read(doc);
            if (!text.contains("kubectl apply -k")) {
                continue;
            }
            assertTrue(text.contains("create-secrets.sh"),
                    doc + " prints `kubectl apply -k` without naming k8s/create-secrets.sh; no manifest ships "
                            + "eddi-secrets any more, so the apply produces a pod stuck in ContainerCreating");
            assertTrue(text.contains("ContainerCreating"),
                    doc + " must name the symptom (`ContainerCreating`) as well as the fix — that string is "
                            + "what a reader has in front of them when they come looking");
        }
    }

    /**
     * Dropping eddi-secrets from the manifests made "pod stuck in
     * ContainerCreating" the single most likely first-run symptom. The happy-path
     * Quickstart explains it; Troubleshooting — the section an operator actually
     * opens once something is wrong — described only the OLD symptom, a "vault
     * master key not set" warning that a fresh install can no longer produce
     * because the pod never starts at all.
     */
    @Test
    @DisplayName("troubleshooting covers the symptom the removed Secret produces")
    void troubleshootingCoversTheMissingSecret() throws IOException {
        String doc = read(K8S_DOC);
        int heading = doc.indexOf("## Troubleshooting");
        assertTrue(heading >= 0, K8S_DOC + " must keep a Troubleshooting section");
        String troubleshooting = doc.substring(heading);

        assertTrue(troubleshooting.contains("ContainerCreating"),
                "Troubleshooting has no entry for ContainerCreating, which is now the most likely first-run "
                        + "symptom: nothing ships eddi-secrets and the Deployment mounts it non-optionally");
        assertTrue(troubleshooting.contains("secret \"eddi-secrets\" not found"),
                "Troubleshooting should quote the kubectl message verbatim — that is the string an operator "
                        + "pastes into a search box");
        assertTrue(troubleshooting.contains("create-secrets.sh"),
                "Troubleshooting must name the script that fixes it");
    }

    /**
     * This class's own header says it "is not a substitute for {@code kubectl
     * kustomize} and {@code helm template} in CI" — so the manifest-lint job is the
     * other half of the coverage, and nothing guarded the guard. Delete the job and
     * every assertion here still passes while all real rendering verification is
     * gone: a kustomization that does not build, a chart that does not template,
     * and a {@code fail} gate that has stopped firing all become invisible again.
     * <p>
     * The four {@code expect_failure} cases are the load-bearing part. A guard that
     * no longer fires produces no output at all, so each of the chart's fail-loud
     * branches is asserted to still be refused; the guards' own presence is checked
     * by silentBranchesFailLoudly and natsRequiresABuildProfileImage above, and
     * this is what proves they still bite when Helm actually renders.
     * <p>
     * manifest-lint deliberately gates on detect-changes and nothing else — it is
     * not in the {@code docker} job's {@code needs:}, exactly like shell-lint. A
     * lint job that blocks a tagged release would make a docs-only manifest typo
     * hold up a security patch; both jobs are PR gates instead.
     */
    @Test
    @DisplayName("CI renders every manifest with the tools this test cannot run")
    void ciRendersTheManifestsThisTestOnlyReads() throws IOException {
        JsonNode job = YAML.readTree(CI.toFile()).path("jobs").path("manifest-lint");
        assertFalse(job.isMissingNode(),
                CI + " has no manifest-lint job. DeploymentManifestsTest reads these files as text and JSON; "
                        + "only kubectl and helm can tell you whether they RENDER, and both shipped examples "
                        + "had never built at all before that job existed");
        assertEquals("detect-changes", job.path("needs").asText(),
                "manifest-lint must gate on detect-changes like shell-lint does, so it runs on the PRs that "
                        + "touch manifests");

        StringBuilder script = new StringBuilder();
        job.path("steps").forEach(step -> script.append(step.path("run").asText()).append('\n'));
        String run = script.toString();

        assertTrue(run.contains("kubectl kustomize"),
                "manifest-lint must run `kubectl kustomize` over k8s/ — a resources: entry naming a file "
                        + "outside the root is a hard error no text assertion here can reproduce");
        assertTrue(run.contains("find k8s -name kustomization.yaml"),
                "the kustomize sweep must find every kustomization rather than a hand-listed few; the "
                        + "unlisted one is the one that breaks");
        // The sweep SKIPS components — they are not applyable roots — so on its own it
        // rendered nothing of ingress or nats, which no shipped example consumes.
        assertTrue(run.contains("components:"),
                "manifest-lint must also compose each Component into a throwaway parent and render THAT. "
                        + "Skipping every Component means the job renders only the three that the shipped "
                        + "examples happen to consume: a composition error in ingress or nats — which nothing "
                        + "consumes — still passes CI, and the auth component is exactly the kind that used to "
                        + "be silently dropped");
        assertTrue(run.contains("helm lint helm/eddi") && run.contains("helm template eddi helm/eddi"),
                "manifest-lint must both lint and template the chart: lint catches the malformed chart, "
                        + "template catches the one that renders into invalid Kubernetes");

        for (String guarded : List.of(
                "no datastore configured at all",
                "messagingType=nats on a stock image",
                "OIDC enabled without a browser-facing publicUrl",
                "OIDC with neither an in-chart Keycloak nor an authServerUrl",
                "external PostgreSQL with no credentials",
                "a datastore type that disagrees with the datastore configured",
                "a mixed-case datastore type EDDI would not match",
                "both in-chart datastores enabled at once",
                "messagingType=nats with no NATS deployed",
                "NATS deployed for in-memory messaging",
                "OIDC with an explicitly null publicUrl",
                "an in-chart MongoDB alongside an external one",
                "an in-chart PostgreSQL alongside an external one")) {
            assertTrue(run.contains(guarded),
                    "manifest-lint no longer asserts that the chart REFUSES `" + guarded + "`. Each of those "
                            + "used to render happily and hand the operator a deployment that could not work, "
                            + "and a fail gate that stops firing is silent by definition — the only way to see "
                            + "it is to demand the failure");
        }

        // The guards are truthiness tests, and a values file's optional key is a YAML
        // NULL rather than "". `toString nil` is the truthy literal "<nil>", so the
        // same edit stopped one guard firing and made another fire on a correct
        // install — and both were invisible here, because every case above sets a
        // STRING. Only rendering a null can tell the difference; grepping for the
        // guard's source text, which is what the unit assertions do, cannot.
        assertTrue(run.contains("--set eddi.datastore.externalJdbcUrl=null"),
                "manifest-lint must render a null-valued optional datastore value and require it to SUCCEED. "
                        + "A bare `externalJdbcUrl:` in a values file is what an operator gets by clearing "
                        + "the shipped placeholder, and it once refused a stock MongoDB install with a "
                        + "message about PostgreSQL");
        assertTrue(run.contains("--set eddi.oidc.publicUrl=null"),
                "manifest-lint must also assert a null publicUrl is REFUSED. The expect_failure above passes "
                        + "the empty-string default, which cannot see the case where the guard renders "
                        + "EDDI_KEYCLOAK_PUBLIC_URL \"<nil>\" and exits 0");
    }

    /**
     * k8s/create-secrets.{sh,ps1} became the ONLY way to get a vault master key
     * into a cluster once the Secret left the manifests, and their
     * refuse-to-clobber guard was pinned by nothing but a string assertion here:
     * the shell-lint job's path filter and its parse steps covered install.* and
     * scripts/ only, and the manifest-lint job does not read scripts at all.
     */
    @Test
    @DisplayName("CI syntax-checks the secret scripts that every install now depends on")
    void ciLintsTheSecretScripts() throws IOException {
        String ci = read(CI);
        assertTrue(ci.contains("'k8s/*.sh'") && ci.contains("'k8s/*.ps1'"),
                "the shell-lint job's `scripts` path filter must include k8s/*.sh and k8s/*.ps1, otherwise a PR "
                        + "touching only create-secrets skips the job entirely");
        assertTrue(ci.contains("for f in install.sh .githooks/* k8s/*.sh; do"),
                "the bash syntax check must iterate k8s/*.sh — a parse error there is an install that cannot "
                        + "produce a vault key");
        assertTrue(ci.contains("./install.ps1, ./scripts, ./k8s"),
                "the PowerShell parse check must cover k8s/, where create-secrets.ps1 lives");
    }

    /**
     * The documentation guards in this class —
     * docsRouteKustomizeInstallsThroughCreateSecrets, docsDoNotAdvertiseMutableTag,
     * docsDescribeProductionOverlayContents, troubleshootingCoversTheMissingSecret
     * — assert things ABOUT docs/kubernetes.md and README.md. Neither file is in
     * CI's {@code code} path filter (it lists src, pom, k8s, helm, workflows…), and
     * build-and-test gates on that filter, so the one change these guards could not
     * see was a change to the very files they guard: a docs-only PR skipped the job
     * entirely, and a skipped required check still satisfies branch protection.
     * <p>
     * Only the test job widens. integration-test and trivy-scan still gate on
     * {@code code}, and docker {@code needs} all three, so a docs-only change runs
     * the tests and publishes nothing.
     * <p>
     * codeql is deliberately NOT in that list. It carries no {@code if} at all — it
     * is unconditional on purpose, because OpenSSF Scorecard reads check runs on
     * the PR head commit, so a docs-only PR that skipped CodeQL counted as an
     * unscanned commit. Asserting "its condition does not mention operator_docs"
     * was therefore vacuous: {@code asText()} of a missing node is {@code ""}, and
     * {@code "".contains("operator_docs")} is false however the job is written. The
     * absence is asserted directly instead, so gating it later fails here rather
     * than silently costing Scorecard points.
     */
    @Test
    @DisplayName("a docs-only change still runs the tests that guard those docs")
    void ciRunsTheTestsOnOperatorDocChanges() throws IOException {
        JsonNode ci = YAML.readTree(CI.toFile());
        String filters = "";
        for (JsonNode step : ci.path("jobs").path("detect-changes").path("steps")) {
            if (step.path("with").has("filters")) {
                filters = step.path("with").path("filters").asText();
            }
        }
        assertTrue(filters.contains("operator_docs:"),
                CI + " needs a path filter covering the operator-facing docs; the `code` filter deliberately "
                        + "excludes docs/** and README.md, so nothing triggers the suite that guards them");
        for (Path doc : CI_FILTERED_DOCS) {
            // The filter spells paths with forward slashes; Path.toString() does not on
            // Windows.
            String path = doc.toString().replace('\\', '/');
            assertTrue(filters.contains("'" + path + "'"),
                    CI + "'s operator_docs filter must list " + path + " — DeploymentManifestsTest asserts "
                            + "its contents, and an assertion that cannot be triggered by the file it reads "
                            + "is not a guard");
        }

        String buildAndTest = ci.path("jobs").path("build-and-test").path("if").asText();
        assertTrue(buildAndTest.contains("operator_docs"),
                "build-and-test must run when the operator docs change, not only when `code` does. Its "
                        + "condition is: " + buildAndTest);

        for (String publishing : List.of("integration-test", "trivy-scan")) {
            JsonNode condition = ci.path("jobs").path(publishing).path("if");
            assertFalse(condition.isMissingNode(),
                    publishing + " has no `if` at all, so this assertion would pass on an empty string "
                            + "whatever the job did. It must keep an explicit condition to gate on");
            assertFalse(condition.asText().contains("operator_docs"),
                    publishing + " must keep gating on `code` alone: widening it would publish a Docker "
                            + "image for a typo fix in a README");
        }
        assertFalse(ci.path("jobs").path("codeql").has("if"),
                "the codeql job must stay unconditional. OpenSSF Scorecard's SAST check reads check runs on "
                        + "the PR HEAD commit, so scanning main on push is invisible to it and a docs-only PR "
                        + "that skipped CodeQL counts as an unscanned commit — gating it cost us 2 of the last "
                        + "30. (Listing it beside the gated jobs above asserted nothing: asText() of a missing "
                        + "node is \"\", which contains nothing at all.)");
    }

    /**
     * {@code sbom} and {@code preflight-check} used to gate on nothing but
     * {@code needs: build-and-test}, inheriting its {@code code} filter for free.
     * Widening build-and-test to operator-docs changes broke that inheritance —
     * both would now run for a README typo, one publishing an SBOM of code nothing
     * touched and the other building a container image for it — so each grew an
     * explicit {@code needs.detect-changes.outputs.code} condition.
     * <p>
     * Which puts a second, quieter requirement on them: an expression may only read
     * {@code needs.<job>.outputs} for a job in its OWN {@code needs} list. Drop
     * {@code detect-changes} from either one — an entirely reasonable-looking
     * cleanup, since neither job consumes an artifact from it — and the reference
     * resolves to the empty string, {@code '' == 'true'} is false, and the job is
     * skipped on every run forever. GitHub reports that as a grey "skipped" check,
     * not a failure; nothing turns red, no SBOM is ever published again, and the PR
     * preflight silently stops running. Both halves are asserted here because
     * either alone is satisfiable while the pipeline is broken.
     */
    @Test
    @DisplayName("the publishing jobs keep the gate they stopped inheriting")
    void publishingJobsGateOnCodeAndDependOnTheJobThatSuppliesIt() throws IOException {
        JsonNode jobs = YAML.readTree(CI.toFile()).path("jobs");
        for (String name : List.of("sbom", "preflight-check")) {
            JsonNode job = jobs.path(name);
            assertFalse(job.isMissingNode(),
                    CI + " has no `" + name + "` job. It is asserted here because it gates on `code` "
                            + "explicitly rather than inheriting it from build-and-test, which now also runs "
                            + "for operator-docs-only changes");

            String condition = job.path("if").asText();
            assertTrue(condition.contains("needs.detect-changes.outputs.code == 'true'"),
                    name + " no longer gates on `code`. build-and-test runs for an operator-docs-only change "
                            + "now, so a job that merely `needs` it inherits nothing: a README typo would "
                            + "publish an SBOM / build a preflight image for code that did not change. Its "
                            + "condition is: " + condition);
            assertFalse(condition.contains("operator_docs"),
                    name + " gates on operator_docs. Only the test job widens — this one is the publishing "
                            + "half, and running it for a docs edit is exactly what the explicit `code` "
                            + "condition was added to prevent. Its condition is: " + condition);

            JsonNode needs = job.path("needs");
            List<String> dependencies = needs.isArray() ? stringList(needs) : List.of(needs.asText());
            assertTrue(dependencies.contains("detect-changes"),
                    name + " reads needs.detect-changes.outputs.code in its `if` but does not list "
                            + "detect-changes in `needs` (" + dependencies + "). The needs context only "
                            + "carries jobs a job actually depends on, so that reference resolves to the "
                            + "empty string, the condition is permanently false, and the job is SKIPPED on "
                            + "every run — reported as a grey check, never a red one");
            assertTrue(dependencies.contains("build-and-test"),
                    name + " must still run after build-and-test (" + dependencies + "): publishing an SBOM "
                            + "or a preflight image for a commit whose tests never passed is the ordering "
                            + "this job existed to enforce");
        }
    }

    /**
     * Three things have to be wired for every path filter, and a filter is silent
     * on all three when they are missing: an undeclared job output reads as the
     * empty string in every consumer, and so does a branch of Resolve that forgets
     * to echo it. {@code operator_docs} was added as the third filter and needed
     * all three edits; the next one will too.
     * <p>
     * The tag branch is the one worth pinning. Every filter is forced true on
     * {@code refs/tags/*} because a release must run the FULL pipeline whatever the
     * tagged commit happens to touch — the "Check paths" step is skipped entirely
     * there, so {@code steps.filter.outputs.*} is empty and a filter missing from
     * that branch gates its jobs OFF for the release rather than on. That is a
     * release which quietly skipped a required check, on the one run where the
     * pipeline result is the artifact.
     * <p>
     * Derived from the filters block rather than from a list repeated here, so a
     * fourth filter is covered the day it is added instead of the day someone
     * remembers to add it to this test.
     * <p>
     * The contract is POSITIONAL, so the script is split on its {@code else} and
     * each half asserted separately. Searching the whole step for both forms cannot
     * tell the two branches apart, and the two ways of getting it wrong are the two
     * ways round: force {@code <filter>=true} in the ELSE branch and every PR runs
     * the jobs that filter gates, whatever it changed; put the
     * {@code steps.filter.outputs.<filter>} passthrough in the TAG branch and it
     * reads empty on the release — "Check paths" never ran there — so the release
     * skips them instead.
     */
    @Test
    @DisplayName("every path filter is declared as an output and forced on a tagged release")
    void everyPathFilterIsWiredThroughDetectChanges() throws IOException {
        JsonNode detect = YAML.readTree(CI.toFile()).path("jobs").path("detect-changes");

        String filters = "";
        String resolve = "";
        for (JsonNode step : detect.path("steps")) {
            if (step.path("with").has("filters")) {
                filters = step.path("with").path("filters").asText();
            }
            if ("result".equals(step.path("id").asText())) {
                resolve = step.path("run").asText();
            }
        }
        assertFalse(filters.isBlank(), CI + "'s detect-changes job declares no path filters at all");
        assertFalse(resolve.isBlank(),
                CI + "'s detect-changes job has no step with `id: result`; that step is what turns the "
                        + "filter outcomes into job outputs and forces them all true on a tag");

        List<String> filterNames = new ArrayList<>();
        YAML.readTree(filters).fieldNames().forEachRemaining(filterNames::add);
        assertTrue(filterNames.containsAll(List.of("code", "scripts", "operator_docs")),
                CI + " should still declare the code, scripts and operator_docs filters; found " + filterNames);

        // Split on the `else`: which branch a line sits in IS the contract, and a
        // search over the whole script cannot see it.
        List<String> resolveLines = resolve.lines().toList();
        int tagBranch = -1;
        int elseAt = -1;
        int endAt = -1;
        for (int line = 0; line < resolveLines.size(); line++) {
            String text = resolveLines.get(line).strip();
            if (tagBranch < 0 && text.startsWith("if [[ \"$GITHUB_REF\" == refs/tags/*")) {
                tagBranch = line;
            } else if (tagBranch >= 0 && elseAt < 0 && text.equals("else")) {
                elseAt = line;
            } else if (elseAt >= 0 && endAt < 0 && text.equals("fi")) {
                endAt = line;
            }
        }
        assertTrue(tagBranch >= 0 && elseAt > tagBranch && endAt > elseAt,
                CI + "'s Resolve step is no longer the `if refs/tags/* … else … fi` this test reads (branch "
                        + "starts at line " + tagBranch + ", else at " + elseAt + ", fi at " + endAt + "). Every "
                        + "filter is forced true in the first half and passed through in the second, and which "
                        + "half a line is in decides whether a release runs the full pipeline or a PR runs it "
                        + "unconditionally. Script:\n" + resolve);
        String forcedOnATag = String.join("\n", resolveLines.subList(tagBranch + 1, elseAt));
        String passedThrough = String.join("\n", resolveLines.subList(elseAt + 1, endAt));

        for (String filter : filterNames) {
            assertEquals("${{ steps.result.outputs." + filter + " }}", detect.path("outputs").path(filter).asText(),
                    CI + "'s detect-changes job does not publish the `" + filter + "` filter as a job output "
                            + "of the Resolve step. An undeclared output is not an error: "
                            + "needs.detect-changes.outputs." + filter + " simply reads as the empty string in "
                            + "every job that gates on it, and each of those is then skipped on every run");

            // Deliberately not matching the redirection target: whether it is written
            // $GITHUB_OUTPUT or "$GITHUB_OUTPUT" is a shell-quoting question, and
            // BuildQualityGatesTest#githubEnvironmentFileRedirectionsAreQuoted owns it.
            // Pinning it here too made this assertion fail when that one was satisfied.
            assertTrue(forcedOnATag.contains("echo \"" + filter + "=true\""),
                    CI + "'s Resolve step does not force `" + filter + "=true` INSIDE the refs/tags/* branch. "
                            + "\"Check paths\" is skipped for tags, so steps.filter.outputs." + filter + " is "
                            + "empty there — a filter missing from that branch gates its jobs OFF for the "
                            + "release rather than on, and a skipped required check still satisfies branch "
                            + "protection. Tag branch:\n" + forcedOnATag);
            assertFalse(forcedOnATag.contains("steps.filter.outputs." + filter),
                    CI + "'s refs/tags/* branch reads steps.filter.outputs." + filter + ". That step does not "
                            + "run on a tag, so the reference expands to nothing and the filter resolves to "
                            + "the empty string on exactly the run where the pipeline result IS the artifact. "
                            + "The tag branch must write the literal true. Tag branch:\n" + forcedOnATag);

            assertTrue(passedThrough.contains(filter + "=${{ steps.filter.outputs." + filter + " }}"),
                    CI + "'s Resolve step never passes the `" + filter + "` filter through in the ELSE branch, "
                            + "so it would be empty on every PR and push — the filter would exist and match "
                            + "nothing. Else branch:\n" + passedThrough);
            assertFalse(passedThrough.contains("echo \"" + filter + "=true\""),
                    CI + "'s ELSE branch forces `" + filter + "=true` instead of passing the path-filter "
                            + "outcome through. That is the non-tag branch: every PR and every push to main "
                            + "would then run whatever `" + filter + "` gates regardless of what it touched, "
                            + "which is the whole point of having the filter. Else branch:\n" + passedThrough);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    // ── Reading structure out of a Go template ───────────────────
    //
    // Helm templates cannot be parsed as YAML, but the blocks that carry no
    // directive are still YAML, and the relation between two values in such a block
    // is exactly what a pair of `contains("key: 1000")` greps cannot express. These
    // three read that structure off the indentation.

    private static int indentOf(String line) {
        return line.length() - line.stripLeading().length();
    }

    /**
     * Whether {@code line} is a plain YAML mapping key — not blank, not a comment,
     * not a Go directive, not a document separator and not a sequence entry.
     */
    private static boolean isMappingKey(String line) {
        String text = line.strip();
        return !text.isEmpty()
                && !text.startsWith("#")
                && !text.startsWith("{{")
                && !text.startsWith("-")
                && text.matches("[A-Za-z0-9_.\\-/]+:( .*)?");
    }

    private static String keyOf(String line) {
        String text = line.strip();
        return text.substring(0, text.indexOf(':'));
    }

    /**
     * The dotted path of the mapping key on {@code lines.get(index)} within its
     * document — {@code spec.template.spec.securityContext} — found by walking back
     * to each enclosing key with strictly smaller indentation.
     */
    private static String yamlKeyPath(List<String> lines, int index) {
        List<String> path = new ArrayList<>();
        path.add(keyOf(lines.get(index)));
        int indent = indentOf(lines.get(index));
        for (int line = index - 1; line >= 0 && indent > 0; line--) {
            if (isMappingKey(lines.get(line)) && indentOf(lines.get(line)) < indent) {
                indent = indentOf(lines.get(line));
                path.add(0, keyOf(lines.get(line)));
            }
        }
        return String.join(".", path);
    }

    /**
     * The scalar {@code key: value} children of the mapping key on
     * {@code lines.get(index)}, in file order. Comments and directives inside the
     * block are stepped over; the block ends at the first key indented no further
     * than its parent.
     */
    private static Map<String, String> yamlScalarChildren(List<String> lines, int index) {
        Map<String, String> children = new LinkedHashMap<>();
        int parent = indentOf(lines.get(index));
        for (int line = index + 1; line < lines.size(); line++) {
            String text = lines.get(line);
            if (text.isBlank() || text.strip().startsWith("#") || text.strip().startsWith("{{")) {
                continue;
            }
            if (indentOf(text) <= parent) {
                break;
            }
            if (isMappingKey(text) && text.strip().contains(": ")) {
                children.put(keyOf(text), text.strip().substring(text.strip().indexOf(':') + 1).strip());
            }
        }
        return children;
    }

    /**
     * The {@code kind} of the multi-document YAML document that
     * {@code lines.get(index)} belongs to — the nearest {@code kind:} at column 0
     * above it.
     */
    private static String documentKindAt(List<String> lines, int index) {
        for (int line = index; line >= 0; line--) {
            if (indentOf(lines.get(line)) == 0 && lines.get(line).strip().startsWith("kind:")) {
                return lines.get(line).strip().substring("kind:".length()).strip();
            }
        }
        return "";
    }

    // ── Driving the secret generators against a stand-in kubectl ──

    /**
     * One run of a secret generator: what it exited with, what it printed, and —
     * the part no source-text assertion can reach — which kubectl calls it actually
     * made, in order.
     */
    private record GeneratorRun(int exitCode, String output, List<String> kubectlCalls) {

        boolean issued(String call) {
            return kubectlCalls.stream().anyMatch(issued -> issued.startsWith(call));
        }

        /**
         * Whether the run told the operator that a vault master key is now installed:
         * the "Save this key!" box (which prints the key itself) and the "Secret
         * created in namespace" line are the two claims either script makes, and both
         * are only true of a Secret that was actually written.
         */
        boolean reportedAnInstalledKey() {
            return output.contains("Save this key") || output.contains("Secret created in namespace");
        }

        @Override
        public String toString() {
            return "Exit status " + exitCode + "; kubectl calls " + kubectlCalls + "; output:\n" + output;
        }
    }

    private static GeneratorRun runShellGenerator(String stubMode) throws IOException, InterruptedException {
        return runShellGenerator(stubMode, false);
    }

    private static GeneratorRun runShellGenerator(String stubMode, boolean force)
            throws IOException, InterruptedException {
        Path bash = locateBash();
        assumeTrue(bash != null, "no non-WSL bash available to run " + CREATE_SECRETS_SH
                + "; CI's ubuntu-latest runner has one");

        Path stubDirectory = kubectlStub("sh", false);
        Path log = freshLog("sh-" + stubMode + (force ? "-force" : ""));
        // PATH is assembled inside the shell rather than in the environment: a
        // Windows directory carries a drive-letter colon, which is PATH's separator
        // here. $PWD after a cd is already in the shell's own form.
        String command = "cd \"" + slashed(stubDirectory) + "\" && chmod +x kubectl && "
                + "PATH=\"$PWD:$PATH\" bash \"" + slashed(CREATE_SECRETS_SH.toAbsolutePath())
                + "\" --auto --namespace=eddi-stub" + (force ? " --force" : "");
        return execute(List.of(bash.toString(), "-c", command), log,
                Map.of("KUBECTL_STUB_MODE", stubMode, "KUBECTL_LOG", slashed(log)));
    }

    private static GeneratorRun runPowerShellGenerator(String stubMode) throws IOException, InterruptedException {
        return runPowerShellGenerator(stubMode, false);
    }

    private static GeneratorRun runPowerShellGenerator(String stubMode, boolean force)
            throws IOException, InterruptedException {
        return runPowerShellGenerator(stubMode, force, false);
    }

    private static GeneratorRun runPowerShellGenerator(String stubMode, boolean force, boolean whatIf)
            throws IOException, InterruptedException {
        Path pwsh = locateOnPath(WINDOWS ? "pwsh.exe" : "pwsh");
        assumeTrue(pwsh != null, "no PowerShell 7 available to run " + CREATE_SECRETS_PS1
                + "; CI's ubuntu-latest runner has one");

        Path stubDirectory = kubectlStub("pwsh", WINDOWS);
        Path log = freshLog("pwsh-" + stubMode + (force ? "-force" : "") + (whatIf ? "-whatif" : ""));
        List<String> command = new ArrayList<>(List.of(pwsh.toString(), "-NoProfile", "-NonInteractive", "-File",
                CREATE_SECRETS_PS1.toAbsolutePath().toString(), "-Auto", "-Namespace", "eddi-stub"));
        if (force) {
            command.add("-Force");
        }
        if (whatIf) {
            command.add("-WhatIf");
        }
        return execute(command, log, Map.of(
                "KUBECTL_STUB_MODE", stubMode,
                "KUBECTL_LOG", log.toAbsolutePath().toString(),
                "PATH", stubDirectory.toAbsolutePath() + File.pathSeparator + System.getenv("PATH")));
    }

    private static GeneratorRun execute(List<String> command, Path log, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Path.of("").toAbsolutePath().toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);

        Process process = builder.start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError(command.get(0) + " did not finish; it printed:\n" + output);
        }
        List<String> calls = Files.exists(log)
                ? Files.readAllLines(log, StandardCharsets.UTF_8).stream().map(String::strip).toList()
                : List.of();
        return new GeneratorRun(process.exitValue(), output, calls);
    }

    /**
     * A directory holding a stand-in {@code kubectl}, first on the generator's
     * PATH. It logs every invocation and answers the existence probe according to
     * {@code $KUBECTL_STUB_MODE}; everything else succeeds silently, so the run
     * reaches — or does not reach — the delete on its own merits.
     * <p>
     * Two spellings, because two shells resolve commands differently: PowerShell on
     * Windows runs what PATHEXT says is executable, and an extensionless file is
     * not that.
     */
    private static Path kubectlStub(String forShell, boolean batch) throws IOException {
        Path directory = Files.createDirectories(Path.of("target", "kubectl-stub", forShell));
        if (batch) {
            Files.writeString(directory.resolve("kubectl.cmd"), """
                    @echo off
                    >>"%KUBECTL_LOG%" echo %*
                    echo %*| find "get secret eddi-secrets" >nul
                    if errorlevel 1 goto ok
                    if "%KUBECTL_STUB_MODE%"=="exists" goto exists
                    if "%KUBECTL_STUB_MODE%"=="notfound" goto notfound
                    if "%KUBECTL_STUB_MODE%"=="unreachable" goto unreachable
                    goto forbidden
                    :ok
                    exit /b 0
                    :exists
                    echo secret/eddi-secrets
                    exit /b 0
                    :notfound
                    echo Error from server (NotFound): secrets "eddi-secrets" not found 1>&2
                    exit /b 1
                    :unreachable
                    echo Unable to connect to the server: dial tcp: lookup eddi.invalid: no such host 1>&2
                    exit /b 1
                    :forbidden
                    echo Error from server (Forbidden): secrets "eddi-secrets" is forbidden 1>&2
                    exit /b 1
                    """, StandardCharsets.US_ASCII);
            return directory;
        }
        Path stub = directory.resolve("kubectl");
        Files.writeString(stub, """
                #!/usr/bin/env bash
                echo "$*" >> "$KUBECTL_LOG"
                case "$*" in
                  "get secret eddi-secrets"*)
                    case "$KUBECTL_STUB_MODE" in
                      exists)
                        echo "secret/eddi-secrets"
                        exit 0
                        ;;
                      notfound)
                        echo 'Error from server (NotFound): secrets "eddi-secrets" not found' >&2
                        exit 1
                        ;;
                      unreachable)
                        echo 'Unable to connect to the server: dial tcp: lookup eddi.invalid: no such host' >&2
                        exit 1
                        ;;
                      *)
                        echo 'Error from server (Forbidden): secrets "eddi-secrets" is forbidden' >&2
                        exit 1
                        ;;
                    esac
                    ;;
                esac
                exit 0
                """, StandardCharsets.US_ASCII);
        stub.toFile().setExecutable(true, false);
        return directory;
    }

    private static Path freshLog(String name) throws IOException {
        Path log = Files.createDirectories(Path.of("target", "kubectl-stub")).resolve(name + ".log");
        Files.deleteIfExists(log);
        return log;
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * {@code System32\bash.exe} is the WSL launcher, not a shell that can see these
     * files — it runs in another filesystem namespace where the repository path
     * does not resolve — so Git for Windows' bash is preferred and a System32 hit
     * is refused.
     */
    private static Path locateBash() {
        if (!WINDOWS) {
            return locateOnPath("bash");
        }
        for (String programFiles : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
            String root = System.getenv(programFiles);
            if (root == null) {
                continue;
            }
            Path candidate = Path.of(root, "Git", "bin", "bash.exe");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        Path onPath = locateOnPath("bash.exe");
        return onPath != null && !onPath.toString().toLowerCase(Locale.ROOT).contains("system32") ? onPath : null;
    }

    private static Path locateOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry, executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (InvalidPathException ignored) {
                // A PATH entry that is not a path cannot hold the executable.
            }
        }
        return null;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /**
     * Go template comments hold prose about {@code .Values.x}; they are not
     * references. The opening delimiter is written {@code &#123;&#123;- /*} with a
     * space in every template here, so the pattern has to allow it — without the
     * {@code \s*} nothing was stripped and a value mentioned only in a comment
     * would have counted as consumed.
     */
    private static String stripGoComments(String template) {
        return template.replaceAll("(?s)\\{\\{-?\\s*/\\*.*?\\*/\\s*-?}}", "");
    }

    /**
     * Drops whole-line {@code #} comments.
     * <p>
     * Every manifest here explains the defect it used to have in a comment beside
     * the fix — "Named keys, not {@code envFrom: secretRef}", "used to deploy
     * labsai/eddi-config-ui". A structural assertion over the raw text finds those
     * words and reports the documentation as the defect; worse, the reverse case
     * lets a comment satisfy an {@code assertTrue} that the real setting no longer
     * does. Structural checks therefore read the manifest with prose removed.
     * <p>
     * Not for Markdown (where {@code #} is a heading) or for the shell snippets
     * that live inside YAML comments — those tests deliberately read the raw text.
     */
    private static String stripComments(String text) {
        return text.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Every leaf path of a values document, dotted — {@code keycloak.storage.size}
     * rather than {@code keycloak}. An empty object or array is itself a leaf: it
     * is a setting an operator can supply, not a container of them.
     */
    private static List<String> leafPaths(JsonNode node, String prefix) {
        List<String> paths = new ArrayList<>();
        if (node.isObject() && node.size() > 0) {
            node.fieldNames().forEachRemaining(name -> paths.addAll(leafPaths(node.get(name), prefix.isEmpty() ? name : prefix + "." + name)));
        } else if (!prefix.isEmpty()) {
            paths.add(prefix);
        }
        return paths;
    }

    private static List<String> stringList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(node -> values.add(node.asText()));
        }
        return values;
    }

    private static JsonNode client(JsonNode realm, String clientId) {
        for (JsonNode candidate : realm.path("clients")) {
            if (clientId.equals(candidate.path("clientId").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("realm has no client " + clientId);
    }

    private static Set<String> names(JsonNode realm, String collection, String field) {
        Set<String> values = new TreeSet<>();
        realm.path(collection).forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    private static Set<String> realmRoles(JsonNode realm) {
        Set<String> values = new TreeSet<>();
        realm.path("roles").path("realm").forEach(node -> values.add(node.path("name").asText()));
        return values;
    }

    /**
     * The first capturing group of {@code pattern} in {@code text}, trimmed, or the
     * empty string when it does not match. {@code DOTALL} is deliberately off — the
     * patterns here step over line breaks explicitly, so a stray {@code .+} cannot
     * swallow half the file — and the trim absorbs the {@code \r} of a CRLF file.
     */
    private static String captureAfter(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * The literal value of one environment variable on a workload's first
     * container, or the empty string when it is not set. Used to compare a value
     * written out in one manifest against the same value written out in another.
     */
    private static String containerEnv(JsonNode workload, String name) {
        for (JsonNode variable : workload.path("spec").path("template").path("spec")
                .path("containers").get(0).path("env")) {
            if (name.equals(variable.path("name").asText())) {
                return variable.path("value").asText();
            }
        }
        return "";
    }

    private static JsonNode documentOfKind(Path manifest, String kind) throws IOException {
        for (JsonNode document : yamlDocuments(manifest)) {
            if (kind.equals(document.path("kind").asText())) {
                return document;
            }
        }
        throw new AssertionError(manifest + " contains no " + kind);
    }

    /**
     * The JSON6902 operations a component applies to the {@code eddi-config}
     * ConfigMap, keyed by path. kustomize carries the patch as an opaque STRING, so
     * it is parsed a second time — which is the only way to compare two values the
     * component writes out as separate literals.
     */
    private static Map<String, String> eddiConfigPatch(Path kustomization) throws IOException {
        Map<String, String> operations = new LinkedHashMap<>();
        for (JsonNode entry : YAML.readTree(kustomization.toFile()).path("patches")) {
            if (!"eddi-config".equals(entry.path("target").path("name").asText())) {
                continue;
            }
            for (JsonNode operation : YAML.readTree(entry.path("patch").asText())) {
                operations.put(operation.path("path").asText(), operation.path("value").asText());
            }
        }
        return operations;
    }

    private static List<JsonNode> yamlDocuments(Path path) throws IOException {
        try (MappingIterator<JsonNode> documents = YAML.readerFor(JsonNode.class).readValues(path.toFile())) {
            return documents.readAll();
        }
    }

    private static List<Path> kustomizations() throws IOException {
        return walk(K8S).filter(path -> path.getFileName().toString().equals("kustomization.yaml")).toList();
    }

    private static List<Path> manifestsUnder(Path root) throws IOException {
        return walk(root)
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return (name.endsWith(".yaml") || name.endsWith(".yml"))
                            && !name.equals("kustomization.yaml");
                })
                .toList();
    }

    private static List<Path> templates() throws IOException {
        return walk(HELM_TEMPLATES).toList();
    }

    private static Stream<Path> walk(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            // Materialised eagerly: the caller outlives the stream's file handle.
            return new LinkedHashSet<>(paths.filter(Files::isRegularFile).toList()).stream();
        } catch (UncheckedIOException e) {
            throw new IOException(e);
        }
    }
}
