package ai.labs.eddi.modules.templating.impl.dialects.uuid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UUIDWrapperTest {
    private UUIDWrapper wrapper;

    @BeforeEach
    void setUp() {
        wrapper = new UUIDWrapper();
    }

    @Test
    void extractId_withMongoObjectId() {
        assertEquals("6740832a2b0f614abcaee7ab",
                wrapper.extractId("http://localhost:7070/behaviorstore/behaviorsets/6740832a2b0f614abcaee7ab?version=1"));
    }

    @Test
    void extractId_withPostgresUUID() {
        assertEquals("f3be2bcd-aff3-41f0-9a1a-cf4eb513dd81",
                wrapper.extractId("http://localhost:7070/behaviorstore/behaviorsets/f3be2bcd-aff3-41f0-9a1a-cf4eb513dd81?version=1"));
    }

    @Test
    void extractId_botLocation() {
        assertEquals("6740832b2b0f614abcaee7ca",
                wrapper.extractId("http://localhost:7070/AgentStore/bots/6740832b2b0f614abcaee7ca?version=1"));
    }

    @Test
    void extractId_packageLocation() {
        assertEquals("c1d2e3f4-a5b6-47c8-9d0e-f1a2b3c4d5e6",
                wrapper.extractId("http://localhost:7070/PipelineStore/packages/c1d2e3f4-a5b6-47c8-9d0e-f1a2b3c4d5e6?version=2"));
    }

    @Test
    void extractId_withoutVersion() {
        assertEquals("abc123",
                wrapper.extractId("http://localhost:7070/store/resources/abc123"));
    }

    @Test
    void extractId_nullInput() {
        assertEquals("", wrapper.extractId(null));
    }

    @Test
    void extractId_emptyInput() {
        assertEquals("", wrapper.extractId(""));
    }

    @Test
    void extractVersion_withMongoObjectId() {
        assertEquals("1",
                wrapper.extractVersion("http://localhost:7070/behaviorstore/behaviorsets/6740832a2b0f614abcaee7ab?version=1"));
    }

    @Test
    void extractVersion_withPostgresUUID() {
        assertEquals("3",
                wrapper.extractVersion("http://localhost:7070/behaviorstore/behaviorsets/f3be2bcd-aff3-41f0-9a1a-cf4eb513dd81?version=3"));
    }

    @Test
    void extractVersion_noVersion() {
        assertEquals("",
                wrapper.extractVersion("http://localhost:7070/store/resources/abc123"));
    }

    @Test
    void extractVersion_nullInput() {
        assertEquals("", wrapper.extractVersion(null));
    }

    @Test
    void generateUUID_returnsValidUUID() {
        String uuid = wrapper.generateUUID();
        assertEquals(36, uuid.length());
        assertEquals(4, uuid.chars().filter(c -> c == '-').count());
    }
}
