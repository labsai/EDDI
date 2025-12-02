package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.langchain.tools.impl.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Helper class for managing agent tool execution with retry logic.
 */
public class AgentExecutionHelper {
    private static final Logger LOGGER = Logger.getLogger(AgentExecutionHelper.class);

    /**
     * Executes a generic action with retry logic based on configuration
     */
    public static <T> T executeWithRetry(
            Callable<T> action,
            LangChainConfiguration.Task task,
            String actionDescription) throws LifecycleException {

        LangChainConfiguration.RetryConfiguration retryConfig = task.getRetry();
        if (retryConfig == null) {
            retryConfig = new LangChainConfiguration.RetryConfiguration();
        }

        int maxAttempts = retryConfig.getMaxAttempts() != null ? retryConfig.getMaxAttempts() : 3;
        long backoffDelay = retryConfig.getBackoffDelayMs() != null ? retryConfig.getBackoffDelayMs() : 1000L;
        double backoffMultiplier = retryConfig.getBackoffMultiplier() != null ? retryConfig.getBackoffMultiplier() : 2.0;
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
                    // Check if error is retryable
                    if (isRetryableError(e)) {
                        LOGGER.warn(actionDescription + " failed (attempt " + attempt + "/" + maxAttempts + "), retrying after " + currentBackoff + "ms: " + e.getMessage());

                        try {
                            Thread.sleep(currentBackoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new LifecycleException("Retry interrupted", ie);
                        }

                        // Increase backoff delay
                        currentBackoff = Math.min((long)(currentBackoff * backoffMultiplier), maxBackoffDelay);
                    } else {
                        // Not retryable, fail immediately
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
     * Executes chat model with retry logic based on configuration
     */
    public static ChatResponse executeChatWithRetry(
            ChatModel chatModel,
            List<dev.langchain4j.data.message.ChatMessage> messages,
            LangChainConfiguration.Task task) throws LifecycleException {
        
        return executeWithRetry(
            () -> chatModel.chat(messages),
            task,
            "Chat model execution"
        );
    }

    /**
     * Collects enabled built-in tools based on configuration
     */
    public static List<Object> collectEnabledTools(
            LangChainConfiguration.Task task,
            CalculatorTool calculatorTool,
            DateTimeTool dateTimeTool,
            WebSearchTool webSearchTool,
            DataFormatterTool dataFormatterTool,
            WebScraperTool webScraperTool,
            TextSummarizerTool textSummarizerTool,
            PdfReaderTool pdfReaderTool,
            WeatherTool weatherTool) {

        List<Object> tools = new ArrayList<>();

        if (task.getEnableBuiltInTools() == null || !task.getEnableBuiltInTools()) {
            return tools; // Built-in tools disabled
        }

        List<String> whitelist = task.getBuiltInToolsWhitelist();
        boolean hasWhitelist = whitelist != null && !whitelist.isEmpty();

        // Add tools based on whitelist or enable all if no whitelist
        if (!hasWhitelist || whitelist.contains("calculator")) {
            tools.add(calculatorTool);
            LOGGER.debug("Enabled CalculatorTool");
        }

        if (!hasWhitelist || whitelist.contains("datetime")) {
            tools.add(dateTimeTool);
            LOGGER.debug("Enabled DateTimeTool");
        }

        if (!hasWhitelist || whitelist.contains("websearch")) {
            tools.add(webSearchTool);
            LOGGER.debug("Enabled WebSearchTool (includes Wikipedia & News)");
        }

        if (!hasWhitelist || whitelist.contains("dataformatter")) {
            tools.add(dataFormatterTool);
            LOGGER.debug("Enabled DataFormatterTool");
        }

        if (!hasWhitelist || whitelist.contains("webscraper")) {
            tools.add(webScraperTool);
            LOGGER.debug("Enabled WebScraperTool");
        }

        if (!hasWhitelist || whitelist.contains("textsummarizer")) {
            tools.add(textSummarizerTool);
            LOGGER.debug("Enabled TextSummarizerTool");
        }

        if (!hasWhitelist || whitelist.contains("pdfreader")) {
            tools.add(pdfReaderTool);
            LOGGER.debug("Enabled PdfReaderTool");
        }

        if (!hasWhitelist || whitelist.contains("weather")) {
            tools.add(weatherTool);
            LOGGER.debug("Enabled WeatherTool");
        }

        LOGGER.info("Enabled " + tools.size() + " built-in tools for agent");
        return tools;
    }

    /**
     * Determines if an error is retryable
     */
    private static boolean isRetryableError(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Retry on common transient errors
        return message.contains("timeout") ||
               message.contains("rate limit") ||
               message.contains("too many requests") ||
               message.contains("503") ||
               message.contains("502") ||
               message.contains("504") ||
               message.contains("connection") ||
               message.contains("temporary");
    }
}

