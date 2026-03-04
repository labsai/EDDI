package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;

/**
 * Coordinates message processing across conversations to ensure proper ordering and concurrency.
 *
 * <p>This is a critical component of EDDI's architecture that solves the challenge of handling
 * concurrent requests while maintaining conversation state consistency.</p>
 *
 * <h2>The Problem</h2>
 * <p>In a multi-threaded environment, if two messages for the same conversation arrive
 * simultaneously, they could be processed in parallel, leading to:</p>
 * <ul>
 *   <li>Race conditions in conversation state updates</li>
 *   <li>Messages processed out of order</li>
 *   <li>Corrupted conversation memory</li>
 *   <li>Unpredictable bot behavior</li>
 * </ul>
 *
 * <h2>The Solution</h2>
 * <p>ConversationCoordinator ensures that:</p>
 * <ul>
 *   <li><strong>Sequential Processing per Conversation</strong>: Messages within the same
 *       conversation are processed one after another, in order</li>
 *   <li><strong>Concurrent Processing across Conversations</strong>: Different conversations
 *       can be processed in parallel without blocking each other</li>
 *   <li><strong>No Race Conditions</strong>: Conversation state is never accessed concurrently
 *       by multiple threads</li>
 * </ul>
 *
 * <h2>How It Works</h2>
 * <p>The coordinator maintains a queue for each conversation:</p>
 * <pre>
 * Conversation A: [Message 1] → [Message 2] → [Message 3]
 * Conversation B: [Message 1] → [Message 2]
 * Conversation C: [Message 1]
 * </pre>
 *
 * <p>Each conversation's queue is processed sequentially, but different conversations'
 * queues are processed concurrently by the thread pool.</p>
 *
 * <h2>Example Scenario</h2>
 * <pre>
 * Time T1: User A sends "Hello" → Queued for Conv-A, processing starts
 * Time T2: User B sends "Hi" → Queued for Conv-B, processing starts in parallel
 * Time T3: User A sends "How are you?" → Queued for Conv-A, waits for "Hello" to finish
 * Time T4: Conv-A "Hello" completes → "How are you?" starts processing
 * Time T5: Conv-B "Hi" completes → Conv-B queue is empty
 * </pre>
 *
 * <p>This ensures User A's messages are processed in order, User B's messages are processed
 * in order, but User A and User B don't block each other.</p>
 *
 * @author ginccc
 * @see IRuntime
 * @see ai.labs.eddi.engine.internal.RestBotEngine
 */
@ApplicationScoped
public class ConversationCoordinator implements IConversationCoordinator {
    /**
     * Map of conversation ID to processing queue.
     * Each conversation has its own queue to ensure sequential processing.
     */
    private final Map<String, BlockingQueue<Callable<Void>>> conversationQueues = new ConcurrentHashMap<>();

    private final IRuntime runtime;

    private static final Logger log = Logger.getLogger(ConversationCoordinator.class);

    @Inject
    public ConversationCoordinator(IRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Submits a message processing task to be executed in order for the given conversation.
     *
     * <p>This method ensures that messages for the same conversation are processed sequentially,
     * while allowing messages from different conversations to be processed concurrently.</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Get or create a queue for this conversation (thread-safe via ConcurrentHashMap)</li>
     *   <li>If queue is empty:
     *     <ul>
     *       <li>Add the task to the queue</li>
     *       <li>Submit it to the runtime thread pool immediately</li>
     *       <li>When task completes, automatically submit the next task in queue</li>
     *     </ul>
     *   </li>
     *   <li>If queue is not empty:
     *     <ul>
     *       <li>Add the task to the queue</li>
     *       <li>Wait - it will be automatically submitted when its turn comes</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is thread-safe and can be called concurrently from multiple threads.
     * The queue is synchronized to prevent race conditions when checking emptiness and
     * removing completed tasks.</p>
     *
     * <h3>Example Usage</h3>
     * <pre>{@code
     * coordinator.submitInOrder(conversationId, () -> {
     *     // This code will execute in order with other messages for this conversation
     *     processMessage(conversationMemory, userInput);
     *     return null;
     * });
     * }</pre>
     *
     * <h3>Error Handling</h3>
     * <p>If a task throws an exception, it is logged and the queue continues processing
     * the next task. This prevents one failed message from blocking the entire conversation.</p>
     *
     * @param conversationId the unique identifier of the conversation
     * @param callable the task to execute (typically lifecycle pipeline execution)
     */
    @Override
    public void submitInOrder(String conversationId, Callable<Void> callable) {
        // Get or create a queue for this conversation (thread-safe)
        final BlockingQueue<Callable<Void>> queue = conversationQueues.
                computeIfAbsent(conversationId, (key) -> new LinkedTransferQueue<>());

        // Synchronize the isEmpty() check + offer() + submit to prevent race condition
        // where two threads both see isEmpty()=true and both submit to runtime
        synchronized (queue) {
            boolean wasEmpty = queue.isEmpty();
            queue.offer(callable);

            if (wasEmpty) {
                // Submit to runtime thread pool with callback for when it completes
                runtime.submitCallable(callable,
                        new IRuntime.IFinishedExecution<>() {
                            @Override
                            public void onComplete(Void result) {
                                submitNext();
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                // Log error but continue processing next message
                                // This prevents one failed message from blocking the conversation
                                log.error(t.getLocalizedMessage(), t);
                                submitNext();
                            }

                            /**
                             * Processes the next message in the queue after current one completes.
                             * This creates a chain reaction where each completed message triggers
                             * the next one, ensuring sequential processing.
                             */
                            private void submitNext() {
                                synchronized (queue) {
                                    if (!queue.isEmpty()) {
                                        // Remove the just-completed task
                                        queue.remove();

                                        // If there are more tasks waiting, submit the next one
                                        if (!queue.isEmpty()) {
                                            runtime.submitCallable(queue.element(), this, null);
                                        }
                                        // If queue is now empty, conversation is idle until next message
                                    }
                                }
                            }
                        }, Collections.emptyMap());
            }
            // If queue was not empty, the task is already queued and will be
            // automatically submitted when its turn comes (by submitNext() callback)
        }
    }
}
