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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final Path K8S_DOC = Path.of("docs", "kubernetes.md");
    private static final Path README = Path.of("README.md");

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
         * Presence is not the property that protects the key — ORDER is. Both scripts
         * still carry the pre-existing {@code kubectl delete secret
         * eddi-secrets --ignore-not-found}, and they have to: that delete is what makes
         * {@code --force} able to rotate at all. The guard only saves anything while it
         * runs BEFORE it. A refactor that lifts the delete above the check, or drops
         * the check into a function nobody calls, leaves every string the test above
         * looks for exactly where it was and restores the key-destroying behaviour in
         * full.
         * <p>
         * This is the one data-destroying path in these manifests: the key it removes
         * is the one the file's own banner calls UNRECOVERABLE, so the assertion is on
         * the offsets, not on the words.
         */
        @Test
        @DisplayName("the generator checks for a live key BEFORE it deletes one")
        void secretGeneratorChecksBeforeItDeletes() throws IOException {
            for (Path script : List.of(K8S.resolve("create-secrets.sh"), K8S.resolve("create-secrets.ps1"))) {
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
            }
        }

        /**
         * `kubectl create secret` against a Secret the same manifest already created
         * fails with AlreadyExists, leaving the operator on the empty-key Secret with
         * only a log warning to go on. Three copies of this snippet had drifted apart;
         * they are now all idempotent.
         */
        @Test
        @DisplayName("every documented secret-creation snippet is idempotent")
        void secretCreationSnippetsAreIdempotent() throws IOException {
            List<Path> sources = List.of(
                    Path.of("docs", "kubernetes.md"),
                    K8S.resolve("quickstart.yaml"),
                    K8S.resolve("base/eddi-secret.yaml.example"));
            for (Path source : sources) {
                String text = read(source);
                int creates = countOccurrences(text, "kubectl create secret generic eddi-secrets");
                int idempotent = countOccurrences(text, "--dry-run=client -o yaml | kubectl apply -f -");
                assertEquals(creates, idempotent,
                        source + " has " + creates + " `kubectl create secret generic eddi-secrets` snippet(s) but "
                                + idempotent + " piped through `--dry-run=client -o yaml | kubectl apply -f -`; "
                                + "a bare create fails with AlreadyExists on the second run");
            }
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
         * {@code start-dev} keeps Keycloak's H2 database under
         * {@code /opt/keycloak/data}. With no volume that is the container's ephemeral
         * writable layer, so any rollout, eviction, node drain or OOM kill discarded
         * every realm, client and user.
         */
        @Test
        @DisplayName("keycloak state is persisted across restarts")
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
            }
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
         */
        @Test
        @DisplayName("the OIDC token issuer is pinned, and agrees with the public URL")
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
                if (volumeName.equals(volume.path("name").asText())) {
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

            String fallback = captureAfter(template, "\\$realmOrigin\\s*:=\\s*default\\s+\"([^\"]+)\"");
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
            String security = read(Path.of("docs", "security.md"));
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
         * This release removes {@code manager.*}, {@code monitoring.*} and
         * {@code namespace} outright and turns two previously-optional values into
         * render-time requirements, so an upgrade carrying an old values file either
         * fails to render or silently loses a setting. That is a major bump, and
         * Chart.yaml's own comment records why the version had gone stale before: "it
         * sat at 1.0.0 across three releases because nothing enforces this". Nothing
         * enforced it — this does, on both halves. The major has to have moved, and the
         * break it claims has to be real, or the version is just a different lie.
         */
        @Test
        @DisplayName("the chart version records the break the values file took")
        void chartVersionRecordsTheBreakingChange() throws IOException {
            JsonNode chart = YAML.readTree(HELM.resolve("Chart.yaml").toFile());
            String version = chart.path("version").asText();
            assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"),
                    "helm/eddi/Chart.yaml version must be semver; chart repositories key on it. Got: " + version);
            int major = Integer.parseInt(version.substring(0, version.indexOf('.')));
            assertTrue(major >= 2,
                    "helm/eddi/Chart.yaml is at " + version + ", but this chart dropped manager.*, "
                            + "monitoring.* and namespace and made eddi.oidc.publicUrl / nats.buildProfileImage "
                            + "render-time requirements. An operator reading a 1.x version reasonably runs "
                            + "`helm upgrade` with their existing values file and gets a failed render, or a "
                            + "setting that quietly stopped being read");

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
        assertTrue(run.contains("helm lint helm/eddi") && run.contains("helm template eddi helm/eddi"),
                "manifest-lint must both lint and template the chart: lint catches the malformed chart, "
                        + "template catches the one that renders into invalid Kubernetes");

        for (String guarded : List.of(
                "no datastore configured at all",
                "messagingType=nats on a stock image",
                "OIDC enabled without a browser-facing publicUrl",
                "OIDC with neither an in-chart Keycloak nor an authServerUrl")) {
            assertTrue(run.contains(guarded),
                    "manifest-lint no longer asserts that the chart REFUSES `" + guarded + "`. Each of those "
                            + "used to render happily and hand the operator a deployment that could not work, "
                            + "and a fail gate that stops firing is silent by definition — the only way to see "
                            + "it is to demand the failure");
        }
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

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
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
