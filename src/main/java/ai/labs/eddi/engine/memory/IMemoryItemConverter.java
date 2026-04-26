/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.Map;

public interface IMemoryItemConverter {
    Map<String, Object> convert(IConversationMemory memory);
}
