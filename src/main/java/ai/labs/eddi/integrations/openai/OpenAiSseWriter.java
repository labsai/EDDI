/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.integrations.openai.model.ChatCompletionChunk;
import ai.labs.eddi.integrations.openai.model.ChunkChoice;
import ai.labs.eddi.integrations.openai.model.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Frames OpenAI streaming chunks onto an {@link OutputStream} as SSE.
 * <p>
 * {@code id}, {@code created} and {@code model} are fixed at construction and
 * repeated on every chunk — some clients key on their stability across a
 * response.
 * <p>
 * Every frame is flushed immediately. A buffered SSE stream is
 * indistinguishable from a hung one at the client, so batching here would look
 * like a bug in the agent.
 *
 * @since 6.1.0
 */
public class OpenAiSseWriter {

    private static final Logger LOGGER = Logger.getLogger(OpenAiSseWriter.class);

    private static final byte[] DATA_PREFIX = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FRAME_SUFFIX = "\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DONE_FRAME = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);

    private final OutputStream out;
    private final ObjectMapper objectMapper;
    private final String id;
    private final String model;
    private final long created;
    private final boolean includeUsage;

    private boolean roleSent;
    private boolean finished;
    private boolean broken;
    private TokenUsage usage;

    public OpenAiSseWriter(OutputStream out, ObjectMapper objectMapper,
            String id, String model, long createdEpochSeconds, boolean includeUsage) {
        this.out = out;
        this.objectMapper = objectMapper;
        this.id = id;
        this.model = model;
        this.created = createdEpochSeconds;
        this.includeUsage = includeUsage;
    }

    /**
     * Emit the opening {@code {"role":"assistant"}} delta. Idempotent, so content
     * emitters can call it defensively without tracking whether it has run.
     */
    public void role() {
        if (roleSent) {
            return;
        }
        roleSent = true;
        emit(ChunkChoice.role());
    }

    /**
     * Emit one content delta. Empty text is skipped — an empty delta means nothing.
     */
    public void content(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        role();
        emit(ChunkChoice.content(text));
    }

    /**
     * Record the turn's token usage, to be emitted with the terminator.
     * <p>
     * Deferred rather than written immediately because usage is only known once the
     * turn completes, and OpenAI places it after the {@code finish_reason} chunk.
     * Ignored when the caller did not ask for it — an unrequested usage chunk is a
     * protocol deviation, and some clients treat the empty {@code choices} array as
     * malformed.
     */
    public void usage(TokenUsage tokenUsage) {
        this.usage = tokenUsage;
    }

    /**
     * Emit the terminal chunk, the optional usage chunk and the {@code [DONE]}
     * sentinel. Idempotent: the streaming handler terminates from several paths
     * (complete, error, skipped) and a double terminator would confuse strict
     * clients.
     */
    public void finish(String finishReason) {
        if (finished) {
            return;
        }
        finished = true;
        role();
        emit(ChunkChoice.finish(finishReason));
        if (includeUsage && usage != null) {
            emitChunk(ChatCompletionChunk.usageOnly(id, model, created, usage));
        }
        write(DONE_FRAME);
    }

    /**
     * Whether anything has been written yet — i.e. whether the opening role frame
     * has gone out. Note this becomes true on {@link #finish} as well, so it means
     * "the stream has started", not "content was sent".
     */
    public boolean hasStarted() {
        return roleSent;
    }

    private void emit(ChunkChoice choice) {
        emitChunk(ChatCompletionChunk.of(id, model, created, choice));
    }

    private void emitChunk(ChatCompletionChunk chunk) {
        if (broken) {
            return;
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(chunk);
            write(DATA_PREFIX);
            write(json);
            write(FRAME_SUFFIX);
            flush();
        } catch (IOException e) {
            markBroken(e);
        } catch (Exception e) {
            // Serialization failure: drop this chunk rather than tearing down a
            // stream the client is otherwise reading fine.
            LOGGER.warnf("Could not serialize an OpenAI chunk: %s", e.getMessage());
        }
    }

    private void write(byte[] bytes) {
        if (broken) {
            return;
        }
        try {
            out.write(bytes);
        } catch (IOException e) {
            markBroken(e);
        }
    }

    private void flush() {
        if (broken) {
            return;
        }
        try {
            out.flush();
        } catch (IOException e) {
            markBroken(e);
        }
    }

    /**
     * A client that disconnected mid-stream is routine, not an error worth
     * escalating. Latch it so the remaining handler callbacks become no-ops instead
     * of raising once per token.
     */
    private void markBroken(IOException e) {
        if (!broken) {
            broken = true;
            LOGGER.debugf("OpenAI SSE stream closed by the client: %s", e.getMessage());
        }
    }
}
