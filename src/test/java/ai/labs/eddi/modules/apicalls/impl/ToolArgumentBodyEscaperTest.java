/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.Request;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pieces of {@link ToolArgumentBodyEscaper}: the template analysis, the
 * escaped data, the structural check and the canonical bare values. The end-to-
 * end property through the real request path is
 * {@code ToolArgumentInjectionTest}.
 */
class ToolArgumentBodyEscaperTest {

    private static final Engine QUTE = Engine.builder().addDefaults().build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolArgumentBodyEscaper.Renderer RENDER = (template, data) -> QUTE.parse(template).data(data).render();

    @Test
    void jsonMediaTypes() {
        assertTrue(ToolArgumentBodyEscaper.isJsonContentType("application/json"));
        assertTrue(ToolArgumentBodyEscaper.isJsonContentType("Application/JSON; charset=utf-8"));
        assertTrue(ToolArgumentBodyEscaper.isJsonContentType("application/problem+json"));
        assertTrue(ToolArgumentBodyEscaper.isJsonContentType("text/json"));
        assertFalse(ToolArgumentBodyEscaper.isJsonContentType("text/plain"));
        assertFalse(ToolArgumentBodyEscaper.isJsonContentType("application/x-ndjson"));
        assertFalse(ToolArgumentBodyEscaper.isJsonContentType("multipart/related; type=\"application/json\""));
        assertFalse(ToolArgumentBodyEscaper.isJsonContentType(null));
    }

    // ------------------------------------------------------------ analysis

    @Test
    void onlyAPlainBareArgumentIsRewrittenAndJsonExpressionsReadTheRawValue() {
        var analysis = ToolArgumentBodyEscaper.analyse("{\"a\":{x},\"b\":\"{x}\",\"c\":{x ?: 0},\"d\":{json:serialize(x.y)},\"e\":{data:x}}",
                Set.of("x"));
        assertEquals("{\"a\":{eddiJsonVal_x},\"b\":\"{x}\",\"c\":{x ?: 0},\"d\":{json:serialize(eddiJsonRaw_x.y)},\"e\":{eddiJsonVal_x}}",
                analysis.rewritten());
        assertEquals(Set.of("x"), analysis.bare());
        assertEquals(Set.of("x"), analysis.raw());
        assertTrue(analysis.mentions());
    }

    @Test
    void anAliasShadowingAnArgumentIsNotRedirected() {
        var analysis = ToolArgumentBodyEscaper.analyse("[{#for it in properties.list}{it}{/for}]", Set.of("it"));
        assertEquals("[{#for it in properties.list}{it}{/for}]", analysis.rewritten(), "the loop alias reads the loop, not the argument");
        assertFalse(analysis.mentions());
        assertTrue(ToolArgumentBodyEscaper.analyse("{#with meta}{x}{/with}", Set.of("x")).bare().isEmpty(), "a with-scope may resolve x");
    }

    @Test
    void commentsUnparsedBlocksAndBraceEscapesDoNotFlipTheStringState() {
        assertEquals(Set.of(), ToolArgumentBodyEscaper.analyse("{! say \"hi !}{\"a\":\"{x}\"}", Set.of("x")).bare(),
                "a quote inside a comment is not JSON");
        assertEquals(Set.of(), ToolArgumentBodyEscaper.analyse("{\"a\":1,{|\"x\":\"|}{x}\"}", Set.of("x")).bare(),
                "a quote inside an unparsed block is JSON");
        var escaped = ToolArgumentBodyEscaper.analyse("{\"a\":\\{x},\"b\":{x}}", Set.of("x"));
        assertEquals("{\"a\":\\{x},\"b\":{eddiJsonVal_x}}", escaped.rewritten(), "\\{ is literal text, never rewritten");
        assertEquals("{\"a\":\"say \\\"hi\\\" {x}\"}", ToolArgumentBodyEscaper.analyse("{\"a\":\"say \\\"hi\\\" {x}\"}", Set.of("x")).rewritten());
    }

    @Test
    void aBodyThatNeverNamesAnArgumentIsNotAMention() {
        assertFalse(ToolArgumentBodyEscaper.analyse("{\"name\":\"{properties.name}\"}", Set.of("name")).mentions());
        assertTrue(ToolArgumentBodyEscaper.analyse("{\"q\":\"{#let v=name}{v}{/let}\"}", Set.of("name")).mentions());
        assertTrue(ToolArgumentBodyEscaper.analyse("{\"q\":\"{str:concat(name, 'x')}\"}", Set.of("name")).mentions());
    }

    @Test
    void deployTimeFindingsNameTheConstructNotTheValue() {
        List<String> findings = ToolArgumentBodyEscaper.deployTimeFindings(
                "{\"ids\":[{id ?: 0}],\"v\":{#let v=name}{v}{/let},\"plain\":{id},\"s\":\"{name}\"}", Set.of("id", "name"));
        assertEquals(2, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("{id ?: 0}"), findings.toString());
        assertTrue(findings.get(1).contains("{#let v=name}") && findings.get(1).contains("{v}"), findings.toString());
        assertTrue(ToolArgumentBodyEscaper.deployTimeFindings("{\"a\":{x},\"b\":\"{x.y}\"}", Set.of("x")).isEmpty());
        assertTrue(ToolArgumentBodyEscaper.deployTimeFindings("{requestBody}", Set.of("requestBody")).isEmpty());
    }

    @Test
    void anArgumentExpressionIsOneRootedAtAnArgument() {
        assertTrue(ToolArgumentBodyEscaper.isArgumentExpression("{name}", Set.of("name")));
        assertTrue(ToolArgumentBodyEscaper.isArgumentExpression("{name.first}", Set.of("name")));
        assertTrue(ToolArgumentBodyEscaper.isArgumentExpression("{data:name}", Set.of("name")));
        assertFalse(ToolArgumentBodyEscaper.isArgumentExpression("{properties.name}", Set.of("name")));
        assertFalse(ToolArgumentBodyEscaper.isArgumentExpression("{names}", Set.of("name")));
    }

    // ------------------------------------------------------------ escaped data

    @Test
    void mapKeysAndTheTextOfContainersAreEscaped() throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        String key = "x\",\"admin\":true,\"y\":\"";
        meta.put(key, "1");
        Object escaped = ToolArgumentBodyEscaper.escapeLeaves(meta);
        // Printed inside a string literal, the map is its own JSON as text.
        assertEquals(JSON.writeValueAsString(meta), JSON.readTree("\"" + escaped + "\"").asText());
        assertTrue(((Map<?, ?>) escaped).containsKey(ToolArgumentBodyEscaper.escapeContent(key)));
        Object list = ToolArgumentBodyEscaper.escapeLeaves(List.of("a\""));
        assertEquals("[\"a\\\"\"]", JSON.readTree("\"" + list + "\"").asText());
    }

    // ------------------------------------------------------------ the check

    @Test
    void theBackstopRefusesACutThatLeavesADanglingBackslash() {
        // Emulates {name.substring(0,3)}: a cut through the escaped text leaves a
        // backslash that escapes the author's closing quote.
        ToolArgumentBodyEscaper.Renderer cutting = (template, data) -> "{\"q\":\"" + ((String) data.get("name")).substring(0, 3)
                + "\",\"role\":\"user\"}";
        var guard = guard("{\"q\":\"{name}\",\"role\":\"user\"}", Set.of("name"));
        assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class, () -> guard.renderBody(Map.of("name", "ab\"c"), cutting));
    }

    @Test
    void theBackstopAcceptsAuthorStructureAndRefusesArgumentStructure() throws Exception {
        var guard = guard("{\"ids\":[{#each ids}{it}{#if it_hasNext},{/if}{/each}]}", Set.of("ids"));
        assertEquals("{\"ids\":[1,2]}", guard.renderBody(Map.of("ids", List.of(1, 2)), RENDER));
        var refused = assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class,
                () -> guard.renderBody(Map.of("ids", List.of("1,\"admin\":true")), RENDER));
        assertFalse(refused.getMessage().contains("admin"));
    }

    @Test
    void sameStructureComparesKindsKeysLengthsAndAuthorLeaves() throws Exception {
        var expected = JSON.readTree("{\"a\":\"\",\"b\":\"user\",\"c\":[1,2],\"d\":\"\"}");
        assertFalse(ToolArgumentBodyEscaper.sameStructure(expected, JSON.readTree("{\"a\":\"xyz\",\"b\":\"user\",\"c\":[3,4],\"d\":{\"k\":[]}}")),
                "author numbers are compared");
        assertTrue(ToolArgumentBodyEscaper.sameStructure(expected, JSON.readTree("{\"a\":\"xyz\",\"b\":\"user\",\"c\":[1,2],\"d\":{\"k\":[]}}")));
        assertFalse(ToolArgumentBodyEscaper.sameStructure(expected, JSON.readTree("{\"a\":\"x\",\"b\":\"admin\",\"c\":[1,2],\"d\":1}")));
        assertFalse(ToolArgumentBodyEscaper.sameStructure(expected, JSON.readTree("{\"a\":1,\"b\":\"user\",\"c\":[1,2],\"d\":1}")));
        assertFalse(ToolArgumentBodyEscaper.sameStructure(expected, JSON.readTree("{\"a\":\"x\",\"b\":\"user\",\"c\":[1,2,3],\"d\":1}")));
        assertFalse(ToolArgumentBodyEscaper.sameStructure(expected, JSON.readTree("{\"a\":\"x\",\"z\":\"user\",\"c\":[1,2],\"d\":1}")));
    }

    @Test
    void aStructureFreeValueIsKeptAndEverythingThatCanFormStructureIsNot() {
        assertTrue(ToolArgumentBodyEscaper.isStructureFree("asc"));
        assertTrue(ToolArgumentBodyEscaper.isStructureFree("-1.5e+3"));
        assertTrue(ToolArgumentBodyEscaper.isStructureFree("express delivery"), "a space joins no tokens");
        assertTrue(ToolArgumentBodyEscaper.isStructureFree("Müller & Söhne"));
        for (String structural : List.of("1,2", "\"x", "a\\b", "{x", "x}", "[1", "a:b", "${vars:x}", "a\nb", "a\tb")) {
            assertFalse(ToolArgumentBodyEscaper.isStructureFree(structural), structural);
        }
    }

    @Test
    void aBranchOnABenignValueWithASpaceIsTakenInBothRenders() throws Exception {
        var guard = guard("{#if mode == 'express delivery'}{\"fast\":true}{#else}{\"fast\":false,\"slow\":1}{/if}", Set.of("mode"));
        assertEquals("{\"fast\":true}", guard.renderBody(Map.of("mode", "express delivery"), RENDER));
        assertEquals("{\"fast\":false,\"slow\":1}", guard.renderBody(Map.of("mode", "standard"), RENDER));
    }

    @Test
    void aReferenceIsAllowedOnlyWhereTheTemplateWritesIt() {
        assertTrue(ToolArgumentBodyEscaper.referencesComeFromTheTemplate("{\"k\":\"${vars:a}\",\"q\":\"\"}",
                "{\"k\":\"${vars:a}\",\"q\":\"x\"}"));
        assertFalse(ToolArgumentBodyEscaper.referencesComeFromTheTemplate("{\"k\":\"${vars:a}\",\"q\":\"\"}",
                "{\"k\":\"${vars:a}\",\"q\":\"${vars:a}\"}"), "counted: a second copy came from the argument");
        assertFalse(ToolArgumentBodyEscaper.referencesComeFromTheTemplate("{\"q\":\"\"}", "{\"q\":\"${vault:key}\"}"));
    }

    @Test
    void aTemplateThatRendersNothingForAnArgumentIsRefused() throws Exception {
        var guard = guard("{#if on}{\"a\":1}{/if}", Set.of("on"));
        assertEquals("{\"a\":1}", guard.renderBody(Map.of("on", true), RENDER));
        assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class, () -> guard.renderBody(Map.of("on", false), RENDER));
    }

    @Test
    void aTypedValueIsUsedOnlyWhileItsBindingIsUnchanged() throws Exception {
        var guard = ToolArgumentBodyEscaper.guard(call("{\"amount\":{amount}}", "application/json"), Set.of("amount"),
                Map.of("amount", JsonNodeFactory.instance.textNode("2")));
        assertEquals("{\"amount\":\"2\"}", guard.renderBody(Map.of("amount", "2"), RENDER), "the validated string");
        assertEquals("{\"amount\":7}", guard.renderBody(Map.of("amount", 7), RENDER), "a response saved over it is what is sent");
        assertEquals("{\"amount\":\"<scrubbed>\"}", guard.renderBody(Map.of("amount", "<scrubbed>"), RENDER),
                "a scrubbed binding is never replaced by the validated plaintext");
    }

    // ------------------------------------------------------------ bare values

    @Test
    void bareValuesKeepTheirDigitsAndRefuseNonFiniteNumbers() {
        assertEquals("12345678901234567890.12", ToolArgumentBodyEscaper.bareJson("n", "12345678901234567890.12", null));
        assertEquals("0.1000000000000000055511151231257827", ToolArgumentBodyEscaper.bareJson("n", " 0.1000000000000000055511151231257827 ", null));
        assertEquals("\"1,2\"", ToolArgumentBodyEscaper.bareJson("n", "1,2", null));
        assertEquals("\"abc\"", ToolArgumentBodyEscaper.bareJson("n", "abc", null));
        assertEquals("{\"k\":[1,2]}", ToolArgumentBodyEscaper.bareJson("n", "{ \"k\" : [1, 2] }", null));
        assertEquals("0.0000001", ToolArgumentBodyEscaper.bareJson("n", null, JsonNodeFactory.instance.numberNode(new BigDecimal("1E-7"))));
        assertEquals("\"5\"", ToolArgumentBodyEscaper.bareJson("n", "5", JsonNodeFactory.instance.textNode("5")),
                "a typed string stays a string");
        assertEquals("null", ToolArgumentBodyEscaper.bareJson("n", null, null));
        var refused = assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class,
                () -> ToolArgumentBodyEscaper.bareJson("n", "1e999", null));
        assertTrue(refused.getMessage().contains("'n'") && !refused.getMessage().contains("999"));
    }

    @Test
    void aGuardExistsOnlyWhereThereIsSomethingToGuard() {
        assertNull(ToolArgumentBodyEscaper.guard(call("{requestBody}", "application/json"), Set.of("requestBody"), Map.of()));
        assertNull(ToolArgumentBodyEscaper.guard(call("a={a}", "application/x-www-form-urlencoded"), Set.of("a"), Map.of()));
        assertNull(ToolArgumentBodyEscaper.guard(call("{\"a\":\"{a}\"}", "application/json"), Set.of(), Map.of()));
        ApiCall optedOut = call("{\"a\":\"{a}\"}", "application/json");
        optedOut.setEscapeToolArguments(false);
        assertNull(ToolArgumentBodyEscaper.guard(optedOut, Set.of("a"), Map.of()));
        ApiCall headerOnly = call("a={a}", "text/plain");
        headerOnly.getRequest().getHeaders().put("X-A", "{a}");
        var guard = ToolArgumentBodyEscaper.guard(headerOnly, Set.of("a"), Map.of());
        assertFalse(guard.guardsBody());
        assertThrows(ToolArgumentBodyEscaper.ArgumentRefusedException.class, () -> guard.checkHeader("X-A", "a\nb"));
        guard.checkHeader("X-A", "a\tb");
    }

    @Test
    void aForEachOverAnArgumentGuardsTheItem() {
        var guard = guard("{\"a\":\"{item}\"}", Set.of("items"));
        assertEquals(Set.of("items", "item"), guard.forIteration("items", "item").argumentNames());
        assertEquals(Set.of("items"), guard.forIteration("properties.items", "item").argumentNames());
        var hyphenated = guard("{\"a\":\"{item}\"}", Set.of("order-items"));
        assertEquals(Set.of("order-items", "item"), hyphenated.forIteration("order-items", "item").argumentNames(),
                "the selector's path grammar: a hyphen is part of the key");
        assertEquals(Set.of("order-items", "item"), hyphenated.forIteration(" order-items[0].lines ", "item").argumentNames());
        assertEquals("order", ToolArgumentBodyEscaper.rootKey("order.items"));
    }

    @Test
    void forEachGuardsTheSharedArrayTheSameWayForEveryItem() throws Exception {
        List<String> items = List.of("a\"", "b");
        var guard = guard("{\"a\":\"{item}\",\"n\":\"{items}\"}", Set.of("items")).forIteration("items", "item");
        for (String item : items) {
            Map<String, Object> data = new HashMap<>();
            data.put("items", items);
            data.put("item", item);
            JSON.readTree(guard.renderBody(data, RENDER));
        }
        // The array's twins are reused across items (one copy per call, not per item);
        // what the template prints of it is still the escaped text.
        Map<String, Object> data = new HashMap<>(Map.of("items", items, "item", "c"));
        assertEquals("[\"a\\\"\",\"b\"]", JSON.readTree(guard.renderBody(data, RENDER)).get("n").asText());
    }

    private static ToolArgumentBodyEscaper.Guard guard(String body, Set<String> names) {
        return ToolArgumentBodyEscaper.guard(call(body, "application/json"), names, Map.of());
    }

    private static ApiCall call(String body, String contentType) {
        var request = new Request();
        request.setMethod("post");
        request.setPath("/x");
        request.setContentType(contentType);
        request.setBody(body);
        request.setHeaders(new HashMap<>());
        var call = new ApiCall();
        call.setName("c");
        call.setRequest(request);
        return call;
    }
}
