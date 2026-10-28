/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3: {@link ReservedPrincipals} is the one list of principal namespaces EDDI
 * makes up for itself, and it must not fall behind the code.
 * <p>
 * Deliberately simple: it finds every {@code "<word>:"} literal in
 * {@code src/main/java} that is either concatenated with something
 * ({@code "a2a:" + principal}) or assigned to a {@code *PREFIX*} constant, and
 * demands that each is either reserved or listed below as not a principal. A
 * new synthetic principal namespace fails here until somebody decides which.
 */
class ReservedPrincipalsTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static final Pattern CONCATENATED = Pattern.compile("\"([a-z][a-z0-9-]*:)\"\\s*\\+");
    private static final Pattern PREFIX_CONSTANT = Pattern.compile("PREFIX\\w*\\s*=\\s*\"([a-z][a-z0-9-]*:)\"");

    /**
     * Prefixes the scan finds that are not principals, with where they come from.
     * Adding one here is a claim that no {@code userId}/owner/{@code decidedBy} is
     * ever built from it.
     */
    private static final Map<String, String> NOT_PRINCIPALS = Map.ofEntries(
            Map.entry("user:", "grant subject / attachment lock key (Subjects.USER_PREFIX encodes the principal)"),
            Map.entry("team:", "grant subject (Subjects.TEAM_PREFIX)"),
            Map.entry("owner:", "descriptor access token (Subjects.OWNER_TOKEN_PREFIX)"),
            Map.entry("space:", "descriptor access token (Subjects.SPACE_TOKEN_PREFIX)"),
            Map.entry("conversation:", "attachment lock key"),
            Map.entry("agent:", "tool-cache scope tag"),
            Map.entry("context:", "cache/template key"),
            Map.entry("file:", "URL scheme"),
            Map.entry("url:", "cache key"),
            Map.entry("src:", "cache key"),
            Map.entry("ref:", "cache key"),
            Map.entry("name:", "cache key"),
            Map.entry("httpcall:", "cache key"),
            Map.entry("openai:", "token-estimator cache key"),
            Map.entry("s:", "hash input tag"),
            Map.entry("c:", "hash input tag"),
            Map.entry("h:", "hash input tag"),
            Map.entry("h1:", "hash input tag"),
            Map.entry("k:", "hash input tag"),
            Map.entry("u:", "hash input tag"),
            Map.entry("b:", "webhook signature digest tag"),
            Map.entry("t:", "webhook signature digest tag"),
            Map.entry("event:", "webhook event dedup fallback key"),
            Map.entry("v2:", "ciphertext/HMAC format version"),
            Map.entry("v3:", "ciphertext/HMAC format version"),
            Map.entry("v4:", "ciphertext/HMAC format version"),
            Map.entry("v5:", "ciphertext/HMAC format version"),
            Map.entry("data:", "data URL scheme"),
            // One entry for two uses: Map.ofEntries rejects a duplicate key, and two
            // features each adding their own "sha256:" broke class init.
            Map.entry("sha256:", "content hash prefix: brand image hash and ETag (BrandAssetValidator.hash); "
                    + "synthetic Message-ID / hash prefix (MimeMessageParser, email ledger)"),
            Map.entry("retro:", "RETRO memory entry key"),
            Map.entry("rag-ingestion:", "schedule name"),
            Map.entry("audit-key-id:", "audit key id record"),
            Map.entry("agent-signing-key:", "vault key name"),
            Map.entry("eddi-attachments-quota:", "advisory lock key"),
            Map.entry("system-value:", "template value tag"),
            Map.entry("pii:", "guardrail audit reason code (PiiRedactionGuardrail: pii:<kinds>)"),
            Map.entry("unknown:", "in-memory dedupe key for the unknown-guardrail-type WARN (GuardrailService)"),
            Map.entry("sha256:", "synthetic Message-ID / hash prefix (MimeMessageParser, email ledger)"),
            Map.entry("notify:", "email ledger idempotency key (HitlEmailNotifier)"),
            Map.entry("notice:", "email ledger idempotency key (EmailOwnerNotifier)"),
            Map.entry("in:", "email ledger idempotency key of an inbound mail"),
            Map.entry("imap:", "email ledger sourceRef (IMAP folder:uidvalidity:uid)"),
            Map.entry("email-poll:", "schedule name (EmailPollSchedules)"),
            Map.entry("loop:", "inbound mail refusal reason"),
            Map.entry("unauthenticated:", "inbound mail refusal reason"),
            Map.entry("failed:", "inbound mail refusal reason"),
            Map.entry("recovered:", "inbound ledger note of the stranded-turn sweep"));

    @Test
    @DisplayName("every principal-shaped prefix in src/main is either reserved or known not to be a principal")
    void everyPrefixClassified() throws IOException {
        Map<String, Set<String>> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (Pattern pattern : new Pattern[]{CONCATENATED, PREFIX_CONSTANT}) {
                    Matcher matcher = pattern.matcher(source);
                    while (matcher.find()) {
                        found.computeIfAbsent(matcher.group(1), k -> new TreeSet<>()).add(MAIN.relativize(file).toString());
                    }
                }
            }
        }
        assertTrue(found.containsKey("slack:") && found.containsKey("a2a:"), "the scan found nothing it should have: " + found.keySet());

        Map<String, Set<String>> unclassified = new TreeMap<>();
        found.forEach((prefix, where) -> {
            if (!ReservedPrincipals.PREFIXES.contains(prefix) && !NOT_PRINCIPALS.containsKey(prefix)) {
                unclassified.put(prefix, where);
            }
        });
        assertTrue(unclassified.isEmpty(), "Principal-shaped prefixes that are neither in ReservedPrincipals.PREFIXES (a synthetic principal "
                + "no signed-in user may carry) nor in this test's NOT_PRINCIPALS: " + unclassified);
    }

    @Test
    @DisplayName("nothing is both reserved and declared not a principal")
    void listsDisjoint() {
        for (String prefix : ReservedPrincipals.PREFIXES) {
            assertTrue(!NOT_PRINCIPALS.containsKey(prefix), prefix + " is in both lists");
        }
    }

    @Test
    @DisplayName("matching is a case-insensitive prefix match, and ordinary names pass")
    void matching() {
        assertTrue(ReservedPrincipals.isReserved("SLACK:T1:U2"));
        assertTrue(ReservedPrincipals.isReserved("anonymous"));
        assertTrue(ReservedPrincipals.isReserved("Unverified:alice"));
        assertTrue(!ReservedPrincipals.isReserved("alice"));
        assertTrue(!ReservedPrincipals.isReserved("anonymous.user"));
        assertTrue(!ReservedPrincipals.isReserved(null));
    }
}
