/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactValidator;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ValidatorKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I17 — the declarative artifact validation chain: save-time spec checks fail
 * the config save with an actionable path, write-time failures come back as
 * rejection sentences for the model, and a broken spec fails closed.
 *
 * @author tests
 */
class ArtifactValidatorsTest {

    private static final String SCHEMA = """
            {"type":"object","required":["title"],"properties":{"title":{"type":"string"}}}""";

    private static ArtifactConfig config(ArtifactValidator... validators) {
        return new ArtifactConfig(true, 5, List.of(validators));
    }

    // =================================================================
    // save-time: requireValidSpecs
    // =================================================================

    @Test
    @DisplayName("valid specs of all three kinds pass the save-time check")
    void validSpecs_pass() {
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(config(
                new ArtifactValidator(ValidatorKind.JSON_SCHEMA, SCHEMA),
                new ArtifactValidator(ValidatorKind.REGEX, "^#"),
                new ArtifactValidator(ValidatorKind.MAX_LENGTH, "1000"))));
    }

    @Test
    @DisplayName("null config or empty chain is a no-op")
    void absentConfig_noOp() {
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(null));
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(new ArtifactConfig(true, 5, null)));
    }

    @Test
    @DisplayName("a broken spec fails the save and names the validator's position")
    void brokenSpecs_throwWithPath() {
        var badRegex = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.REGEX, "[unclosed"))));
        assertTrue(badRegex.getMessage().contains("validators[0]"), badRegex.getMessage());

        var badLength = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(
                        new ArtifactValidator(ValidatorKind.REGEX, "ok"),
                        new ArtifactValidator(ValidatorKind.MAX_LENGTH, "lots"))));
        assertTrue(badLength.getMessage().contains("validators[1]"), badLength.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.MAX_LENGTH, "0"))));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(null, "x"))));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, " "))));
    }

    @Test
    @DisplayName("a schema with an invalid keyword VALUE fails the save — parse alone would admit it")
    void invalidKeywordValue_failsMetaSchema() {
        // {"type":"strng"} is perfectly valid JSON and getSchema() parses it;
        // only meta-schema validation catches the typo'd simple type.
        var e = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(
                        new ArtifactValidator(ValidatorKind.JSON_SCHEMA, "{\"type\":\"strng\"}"))));
        assertTrue(e.getMessage().contains("validators[0]"), e.getMessage());
    }

    @Test
    @DisplayName("a null validator ENTRY fails the save with the positional message, not an NPE")
    void nullValidatorEntry_actionableNotNpe() {
        // Arrays.asList permits the null element; the config copy must too
        // (List.copyOf would NPE during deserialization, preempting this message).
        var config = new ArtifactConfig(true, 5, Arrays.asList(
                new ArtifactValidator(ValidatorKind.MAX_LENGTH, "10"), null));

        var e = assertThrows(IllegalArgumentException.class, () -> ArtifactValidators.requireValidSpecs(config));
        assertTrue(e.getMessage().contains("validators[1]"), e.getMessage());
    }

    // =================================================================
    // write-time: firstRejection
    // =================================================================

    @Test
    @DisplayName("content passing the whole chain yields null")
    void passingContent_null() {
        var validators = List.of(
                new ArtifactValidator(ValidatorKind.MAX_LENGTH, "100"),
                new ArtifactValidator(ValidatorKind.REGEX, "title"),
                new ArtifactValidator(ValidatorKind.JSON_SCHEMA, SCHEMA));

        assertNull(ArtifactValidators.firstRejection(validators, "{\"title\":\"ok\"}"));
    }

    @Test
    @DisplayName("MAX_LENGTH rejection names both counts so the model can act")
    void maxLength_rejects() {
        String rejection = ArtifactValidators.firstRejection(
                List.of(new ArtifactValidator(ValidatorKind.MAX_LENGTH, "5")), "123456");

        assertNotNull(rejection);
        assertTrue(rejection.contains("6") && rejection.contains("5"), rejection);
    }

    @Test
    @DisplayName("REGEX requires a match somewhere in the content")
    void regex_rejectsAndPasses() {
        var validators = List.of(new ArtifactValidator(ValidatorKind.REGEX, "^# .+"));

        assertNull(ArtifactValidators.firstRejection(validators, "# Heading\nbody"));
        String rejection = ArtifactValidators.firstRejection(validators, "no heading here");
        assertNotNull(rejection);
        assertTrue(rejection.contains("pattern"), rejection);
    }

    @Test
    @DisplayName("JSON_SCHEMA distinguishes 'not JSON' from 'JSON that violates the schema'")
    void jsonSchema_rejections() {
        var validators = List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, SCHEMA));

        String notJson = ArtifactValidators.firstRejection(validators, "plain prose");
        assertNotNull(notJson);
        assertTrue(notJson.contains("valid JSON"), notJson);

        String violates = ArtifactValidators.firstRejection(validators, "{\"other\":1}");
        assertNotNull(violates);
        assertTrue(violates.contains("title"), "the violation message must name what is missing: " + violates);

        assertNull(ArtifactValidators.firstRejection(validators, "{\"title\":\"x\"}"));
    }

    @Test
    @DisplayName("the chain runs in config order — the first failure wins")
    void chainOrder_firstFailureWins() {
        var validators = List.of(
                new ArtifactValidator(ValidatorKind.MAX_LENGTH, "3"),
                new ArtifactValidator(ValidatorKind.REGEX, "nope"));

        String rejection = ArtifactValidators.firstRejection(validators, "12345");
        assertNotNull(rejection);
        assertTrue(rejection.contains("character"), "the length failure comes first: " + rejection);
    }

    @Test
    @DisplayName("a catastrophically backtracking pattern aborts on its deadline instead of pinning the turn")
    void catastrophicRegex_abortsOnDeadline() {
        // (a+)+$ against a long non-matching tail backtracks exponentially —
        // unguarded it runs for astronomical time, not the ~500ms budget.
        var validators = List.of(new ArtifactValidator(ValidatorKind.REGEX, "(a+)+$"));
        String content = "a".repeat(60_000) + "b";

        long start = System.nanoTime();
        String rejection = ArtifactValidators.firstRejection(validators, content);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(rejection, "a runaway match must refuse the write, not admit it");
        assertTrue(rejection.contains("did not finish in time"), rejection);
        assertTrue(elapsedMs < 5_000, "the deadline guard must abort far below the member-turn timeout, took " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("a backtracking pattern inside a JSON schema is deadline-bound too, not an unbounded JDK match")
    void catastrophicSchemaPattern_abortsOnDeadline() {
        var validators = List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA,
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\"(a+)+$\"}}}"));
        String content = "{\"x\":\"" + "a".repeat(60_000) + "b\"}";

        long start = System.nanoTime();
        String rejection = ArtifactValidators.firstRejection(validators, content);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(rejection, "a runaway schema match must refuse the write, not admit it");
        assertTrue(rejection.contains("did not finish in time"), rejection);
        assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms");
    }

    /**
     * Each match reads its input through a fresh guard that samples the clock every
     * 1024th character, so short inputs never reached a sample: many
     * patternProperties patterns times many short keys ran on long past the budget,
     * one cheap match at a time.
     */
    @Test
    @DisplayName("many short schema matches stop at the shared deadline, not only one long match")
    void manyShortSchemaMatches_stopAtTheSharedDeadline() {
        StringBuilder patterns = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            patterns.append(i == 0 ? "" : ",").append("\"zq").append(i).append("x\":{}");
        }
        StringBuilder content = new StringBuilder("{");
        String padding = "a".repeat(190);
        for (int i = 0; i < 4_000; i++) {
            content.append(i == 0 ? "" : ",").append("\"k").append(i).append('_').append(padding).append("\":1");
        }
        content.append('}');
        var validators = List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA,
                "{\"type\":\"object\",\"patternProperties\":{" + patterns + "}}"));

        String rejection = ArtifactValidators.firstRejection(validators, content.toString());

        assertNotNull(rejection, "millions of short matches must hit the shared budget and refuse the write");
        assertTrue(rejection.contains("did not finish in time"), rejection);
    }

    @Test
    @DisplayName("an ordinary schema pattern still matches and still rejects")
    void schemaPatternStillWorks() {
        var validators = List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA,
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\"^ab+$\"}}}"));
        assertNull(ArtifactValidators.firstRejection(validators, "{\"x\":\"abbb\"}"));
        assertNotNull(ArtifactValidators.firstRejection(validators, "{\"x\":\"zzz\"}"));
    }

    @Test
    @DisplayName("a broken spec at write time fails closed — the write is refused, never admitted")
    void brokenSpecAtWriteTime_failsClosed() {
        assertNotNull(ArtifactValidators.firstRejection(
                List.of(new ArtifactValidator(ValidatorKind.REGEX, "[unclosed")), "anything"));
        assertNotNull(ArtifactValidators.firstRejection(
                List.of(new ArtifactValidator(ValidatorKind.MAX_LENGTH, "many")), "anything"));
        assertNotNull(ArtifactValidators.firstRejection(
                List.of(new ArtifactValidator(null, null)), "anything"));
    }

    // =================================================================
    // $ref loading (security review M9.1): inline and bundled only
    // =================================================================

    @Test
    @DisplayName("an http $ref is refused at save time and never connects")
    void httpRef_refusedWithoutConnecting() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(300);
            String spec = "{\"$ref\":\"http://127.0.0.1:" + listener.getLocalPort() + "/x.json\"}";

            var e = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IllegalArgumentException.class,
                    () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec)))));
            assertTrue(e.getMessage().contains("not loaded"), e.getMessage());

            String rejection = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> ArtifactValidators.firstRejection(List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec)), "{}"));
            assertNotNull(rejection, "a refused $ref must refuse the write, not admit it");
            assertTrue(rejection.contains("misconfigured"), rejection);

            assertThrows(SocketTimeoutException.class, listener::accept, "the validator must not have connected to the $ref host");
        }
    }

    @Test
    @DisplayName("an http $ref to a closed port is refused by the loader, not by a connect error")
    void httpRefClosedPort_refused() {
        String spec = "{\"$ref\":\"http://127.0.0.1:1/x.json\"}";
        var e = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec))));
        assertTrue(e.getMessage().contains("not allowed to be loaded"), e.getMessage());
    }

    @Test
    @DisplayName("a file: $ref is never read — the local schema would otherwise reject the content")
    void fileRef_notRead(@TempDir Path dir) throws Exception {
        Path local = dir.resolve("loc.json");
        Files.writeString(local, "{\"type\":\"integer\"}");
        String spec = "{\"$ref\":\"" + local.toUri() + "\"}";

        var e = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec))));
        assertTrue(e.getMessage().contains("not loaded"), e.getMessage());

        String rejection = ArtifactValidators.firstRejection(List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec)), "\"text\"");
        assertNotNull(rejection);
        assertTrue(rejection.contains("misconfigured"), "the file schema must not have been applied: " + rejection);
    }

    @Test
    @DisplayName("internal $defs references and the bundled meta-schema still work")
    void internalRefs_stillWork() {
        String spec = """
                {"$defs":{"t":{"type":"string"}},"type":"object","required":["title"],
                 "properties":{"title":{"$ref":"#/$defs/t"}}}""";
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec))));
        assertNull(ArtifactValidators.firstRejection(List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec)), "{\"title\":\"x\"}"));
        assertNotNull(ArtifactValidators.firstRejection(List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec)), "{\"title\":1}"));

        String metaRef = "{\"$ref\":\"https://json-schema.org/draft/2020-12/schema\"}";
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, metaRef))));
    }

    // =================================================================
    // bounded evaluation: references, recursion, empty repetitions
    // =================================================================

    /** {@code depth} definitions, each referencing the previous one twice. */
    private static String doublingSchema(int depth) {
        StringBuilder defs = new StringBuilder("\"a0\":{\"type\":\"object\"}");
        for (int i = 1; i <= depth; i++) {
            defs.append(",\"a").append(i).append("\":{\"allOf\":[{\"$ref\":\"#/$defs/a").append(i - 1)
                    .append("\"},{\"$ref\":\"#/$defs/a").append(i - 1).append("\"}]}");
        }
        return "{\"$defs\":{" + defs + "},\"$ref\":\"#/$defs/a" + depth + "\"}";
    }

    private static List<ArtifactValidator> schemaChain(String spec) {
        return List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec));
    }

    @Test
    @DisplayName("references that multiply are refused at save and not run at write: 2^20 subschemas took seconds per artifact")
    void multiplyingReferences_refused() {
        String spec = doublingSchema(20);

        var e = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec))));
        assertTrue(e.getMessage().contains("expands to more than"), e.getMessage());

        long started = System.nanoTime();
        String rejection = ArtifactValidators.firstRejection(schemaChain(spec), "{}");
        assertNotNull(rejection);
        assertTrue(rejection.contains("could not finish"), rejection);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1000, "a stored schema like this must not be evaluated");

        // a few references to one definition are ordinary and still pass
        assertDoesNotThrow(
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, doublingSchema(3)))));
    }

    @Test
    @DisplayName("a $ref back to its own schema at the same content location is refused: it recursed into a StackOverflowError")
    void sameLocationRecursion_refused() {
        String spec = """
                {"$defs":{"r":{"allOf":[{"$ref":"#/$defs/r"}]}},"$ref":"#/$defs/r"}""";

        var e = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec))));
        assertTrue(e.getMessage().contains("recurses endlessly"), e.getMessage());
        assertTrue(ArtifactValidators.firstRejection(schemaChain(spec), "{}").contains("could not finish"));
    }

    @Test
    @DisplayName("a recursion that reaches the same property by two routes is refused: it doubles per nesting level")
    void doubledRecursion_refused() {
        String spec = """
                {"$defs":{"r":{"type":"object","allOf":[{"properties":{"c":{"$ref":"#/$defs/r"}}},
                 {"properties":{"c":{"$ref":"#/$defs/r"}}}]}},"$ref":"#/$defs/r"}""";

        var e = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec))));
        assertTrue(e.getMessage().contains("two routes"), e.getMessage());
    }

    @Test
    @DisplayName("ordinary recursive schemas — a tree, a binary tree — still save and validate")
    void ordinaryRecursion_passes() {
        String tree = """
                {"type":"object","required":["title"],"properties":{"title":{"type":"string"},
                 "children":{"type":"array","items":{"$ref":"#"}}}}""";
        String binary = """
                {"$defs":{"n":{"type":"object","properties":{"left":{"$ref":"#/$defs/n"},"right":{"$ref":"#/$defs/n"}}}},
                 "$ref":"#/$defs/n"}""";

        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, tree),
                new ArtifactValidator(ValidatorKind.JSON_SCHEMA, binary))));
        assertNull(ArtifactValidators.firstRejection(schemaChain(tree), "{\"title\":\"a\",\"children\":[{\"title\":\"b\"}]}"));
        assertNotNull(ArtifactValidators.firstRejection(schemaChain(tree), "{\"title\":\"a\",\"children\":[{}]}"));
        assertNull(ArtifactValidators.firstRejection(schemaChain(binary), "{\"left\":{\"right\":{}}}"));
    }

    @Test
    @DisplayName("a counted repetition of an empty match is refused in a REGEX validator and a schema pattern: the deadline never sees it")
    void emptyRepetition_refused() {
        String regex = "(?:(?:){99999}){99999}";

        var e = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.REGEX, regex))));
        assertTrue(e.getMessage().contains("iterations"), e.getMessage());
        long started = System.nanoTime();
        assertNotNull(ArtifactValidators.firstRejection(List.of(new ArtifactValidator(ValidatorKind.REGEX, regex)), "abc"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1000, "the pattern must not be run");

        String spec = "{\"type\":\"string\",\"pattern\":\"" + regex + "\"}";
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, spec))));
        started = System.nanoTime();
        assertNotNull(ArtifactValidators.firstRejection(schemaChain(spec), "\"abc\""));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1000, "the schema pattern must not be run");

        // a counted repetition of something that reads input is fine
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.REGEX, "^\\d{4}-\\d{2}$"))));
    }

    @Test
    @DisplayName("only the bundled meta-schema classpath prefixes are loadable")
    void loadableIris() {
        assertTrue(ArtifactValidators.isLoadableSchemaIri("classpath:draft/2020-12/schema"));
        assertTrue(ArtifactValidators.isLoadableSchemaIri("classpath:draft-07/schema"));
        assertFalse(ArtifactValidators.isLoadableSchemaIri("classpath:application.properties"));
        assertFalse(ArtifactValidators.isLoadableSchemaIri("classpath:draft/../application.properties"));
        assertFalse(ArtifactValidators.isLoadableSchemaIri("file:/etc/passwd"));
        assertFalse(ArtifactValidators.isLoadableSchemaIri("https://example.com/s.json"));
        assertFalse(ArtifactValidators.isLoadableSchemaIri(null));
    }
}
