package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.model.LangChainConfiguration;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

import java.util.concurrent.Callable;

/**
 * Static utility for retry logic with exponential backoff.
 * <p>
 * Used by agenth {@link LegacyChatExecutor} and {@link AgentOrchestrator}.
 */
class AgentExecutionHelper {
    private static final Logger LOGGER = Logger.getLogger(AgentExecutionHelper.class);

    /**
     * Executes a generic action with retry logic based on configuration.
     */
    static <T> T executeWithRetry(
            Callable<T> action,
            LangChainConfiguration.Task task,
            String actionDescription) throws LifecycleException {

        LangChainConfiguration.RetryConfiguration retryConfig = task.getRetry();
        if (retryConfig == null) {
            retryConfig = new LangChainConfiguration.RetryConfiguration();
        }

        int maxAttempts = retryConfig.getMaxAttempts() != null ? retryConfig.getMaxAttempts() : 3;
        long backoffDelay = retryConfig.getBackoffDelayMs() != null ? retryConfig.getBackoffDelayMs() : 1000L;
        double backoffMultiplier = retryConfig.getBackoffMultiplier() != null ? retryConfig.getBackoffMultiplier()
                : 2.0;
        long maxBackoffDelay = retryConfig.getMaxBackoffDelayMs() != null ? retryConfig.getMaxBackoffDelayMs() : 10000L;

        int attempt = 0;
        long currentBackoff = backoffDelay;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;

            try {
                LOGGER.debug(actionDescription + " attempt " + attempt + "/" + maxAttempts);

                T result = action.call();

                LOGGER.info(actionDescription + " succeeded on attempt " + attempt);
                return result;

            } catch (Exception e) {
                lastException = e;

                if (attempt < maxAttempts) {
                    if (isRetryableError(e)) {
                        LOGGER.warn(actionDescription + " failed (attempt " + attempt + "/" + maxAttempts
                                + "), retrying after " + currentBackoff + "ms: " + e.getMessage());

                        try {
                            Thread.sleep(currentBackoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new LifecycleException("Retry interrupted", ie);
                        }

                        currentBackoff = Math.min((long) (currentBackoff * backoffMultiplier), maxBackoffDelay);
                    } else {
                        LOGGER.error(actionDescription + " failed with non-retryable error: " + e.getMessage());
                        throw new LifecycleException(actionDescription + " failed: " + e.getMessage(), e);
                    }
                } else {
                    LOGGER.error(actionDescription + " failed after " + maxAttempts + " attempts");
                }
            }
        }

        throw new LifecycleException(actionDescription + " failed after " + maxAttempts + " attempts", lastException);
    }

    /**
     * Executes chat model with retry logic based on configuration.
     */
    static ChatResponse executeChatWithRetry(
            ChatModel chatModel,
            java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
            LangChainConfiguration.Task task) throws LifecycleException {

        return executeWithRetry(
                () -> chatModel.chat(messages),
                task,
                "Chat model execution");
    }

    /**
     * Enum of known retryable error types.
     */
    private enum RetryableErrorType {
        SOCKET_TIMEOUT(java.net.SocketTimeoutException.class),
        TIMEOUT(java.util.concurrent.TimeoutException.class),
        CONNECT_EXCEPTION(java.net.ConnectException.class),
        UNKNOWN_HOST(java.net.UnknownHostException.class);

        private final Class<? extends Throwable> exceptionClass;

        RetryableErrorType(Class<? extends Throwable> exceptionClass) {
            this.exceptionClass = exceptionClass;
        }

        public boolean matches(Throwable t) {
            return exceptionClass.isInstance(t);
        }
    }

    /**
     * Determines if an error is retryable by checking known types and traversing
     * the cause chain.
     */
    private static boolean isRetryableError(Exception e) {
        Throwable current = e;

        while (current != null) {
            for (RetryableErrorType type : RetryableErrorType.values()) {
                if (type.matches(current)) {
                    return true;
                }
            }

            String message = current.getMessage() != null ? current.getMessage().toLowerCase() : "";

            if (message.contains("timeout") ||
                    message.contains("rate limit") ||
                    message.contains("too many requests") ||
                    message.contains("503") ||
                    message.contains("502") ||
                    message.contains("504") ||
                    message.contains("connection") ||
                    message.contains("temporary")) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
