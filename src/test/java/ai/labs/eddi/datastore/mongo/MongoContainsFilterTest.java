/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.engine.security.spaces.Subjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link IResourceFilter.Contains} and the conversation listing's agent group
 * against a real MongoDB. {@code PostgresResourceStorageContainerTest} asserts
 * the same rows on PostgreSQL, so the two backends cannot drift apart.
 */
@DisplayName("MongoResourceStorage Contains filter")
class MongoContainsFilterTest extends MongoTestBase {

    private static final String COLLECTION = "contains_test";

    private MongoResourceStorage<Map<String, Object>> storage;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dropCollections(COLLECTION, COLLECTION + ".history");
        storage = new MongoResourceStorage<>(getDatabase(), COLLECTION, documentBuilder, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @Test
    @DisplayName("matches the text literally, anywhere, case-sensitively; an absent field does not match")
    void containsIsALiteralSubstring() throws IOException {
        String middle = store(Map.of("name", "the a+b (x) agent"));
        String start = store(Map.of("name", "a+b (x)"));
        store(Map.of("name", "aab (x)")); // "+" is literal, not "one or more"
        store(Map.of("name", "the A+B (X) agent")); // case-sensitive
        store(Map.of("description", "a+b (x)")); // the field is absent

        assertEquals(Set.of(middle, start), find("name", new IResourceFilter.Contains("a+b (x)")));
    }

    @Test
    @DisplayName("an empty text matches every document that has the field")
    void emptyTextMatchesEveryPresentField() throws IOException {
        String a = store(Map.of("name", "anything"));
        String b = store(Map.of("name", ""));
        store(Map.of("description", "no name"));

        assertEquals(Set.of(a, b), find("name", new IResourceFilter.Contains("")));
    }

    @Test
    @DisplayName("a quote, a percent, an underscore or a backslash in the text is just text")
    void noCharacterIsSpecial() throws IOException {
        String hit = store(Map.of("name", "50% off 'today' \\o/ a_b"));
        store(Map.of("name", "50X off 'today' \\o/ axb")); // % and _ are not wildcards

        assertEquals(Set.of(hit), find("name", new IResourceFilter.Contains("% off 'today' \\o/ a_b")));
    }

    /**
     * The agent group the conversation listing pushes into its descriptor query
     * (RestConversationStore.listingRestrictions): the descriptor names this agent,
     * or names no agent at all.
     */
    @Test
    @DisplayName("the listing's agent group: this agent, or no agent — not a longer id, not another agent")
    void conversationListingAgentGroup() throws IOException {
        String agent = "0000000000000000000000a1";
        String versioned = store(Map.of("agentResource", "eddi://ai.labs.agent/agentstore/agents/" + agent + "?version=2"));
        String unversioned = store(Map.of("agentResource", "eddi://ai.labs.agent/agentstore/agents/" + agent));
        store(Map.of("agentResource", "eddi://ai.labs.agent/agentstore/agents/" + agent + "ff?version=2"));
        store(Map.of("agentResource", "eddi://ai.labs.agent/agentstore/agents/0000000000000000000000b2?version=2"));
        String absent = store(Map.of("name", "a descriptor an earlier 6.x rewrote without its agent"));
        String blank = store(Map.of("agentResource", "  "));

        var group = new IResourceFilter.QueryFilters(IResourceFilter.QueryFilters.ConnectingType.OR, List.of(
                new IResourceFilter.QueryFilter("agentResource", "/" + Subjects.escapeRegex(agent) + "(\\?|$)"),
                new IResourceFilter.QueryFilter("agentResource", new IResourceFilter.NotMatching("\\S"))));

        assertEquals(Set.of(versioned, unversioned, absent, blank), ids(group));
    }

    private Set<String> find(String field, Object value) {
        return ids(new IResourceFilter.QueryFilters(List.of(new IResourceFilter.QueryFilter(field, value))));
    }

    private Set<String> ids(IResourceFilter.QueryFilters group) {
        Set<String> ids = new HashSet<>();
        storage.findResources(new IResourceFilter.QueryFilters[]{group}, null, 0, 50).forEach(id -> ids.add(id.getId()));
        return ids;
    }

    private String store(Map<String, Object> content) throws IOException {
        var resource = storage.newResource(content);
        storage.store(resource);
        return resource.getId();
    }
}
