package ai.labs.eddi.modules.langchain.agents;

import dev.langchain4j.service.UserMessage;

/**
 * AI Service interface for declarative agents with tool support.
 * This interface enables langchain4j to automatically handle tool calls
 * during response generation.
 *
 * Phase 3: This interface works with AiServices to provide direct tool calling.
 */
public interface DeclarativeAgent {

    /**
     * Main chat method that the agent uses to respond to user input.
     * Tools will be automatically called by the LLM as needed.
     *
     * @param userMessage The user's input message
     * @return The agent's response (may include results from tool calls)
     */
    String chat(@UserMessage String userMessage);
}

