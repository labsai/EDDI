/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlToolCallPreviewTest {

    private static PendingToolCallBatch batchOf(String args, boolean truncated) {
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setToolName("transfer_funds");
        call.setArgumentsRedacted(args);
        call.setArgsTruncated(truncated);
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(call));
        return batch;
    }

    @Test
    void aTruncatedCallTellsTheApproverItWillNotExecute() {
        var lines = HitlToolCallPreview.lines(batchOf("{\"amount\":1}", true));

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith(HitlToolCallPreview.TRUNCATED_NOTICE));
    }

    @Test
    void aCompleteCallIsShownUnchanged() {
        var lines = HitlToolCallPreview.lines(batchOf("{\"amount\":1}", false));

        assertEquals(List.of("transfer_funds — {\"amount\":1}"), lines);
    }
}
