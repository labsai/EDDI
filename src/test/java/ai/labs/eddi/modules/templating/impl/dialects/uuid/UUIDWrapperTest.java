package ai.labs.eddi.modules.templating.impl.dialects.uuid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UUIDWrapperTest {
    @Test
    public void testGenerateUUID() {
        assertEquals(36, new UUIDWrapper().generateUUID().length());
    }
}
