package com.mouss.ebankbot.agents;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Strategy for a concrete LLM backend. Implement one per backend (Ollama, Grok, OpenAI, ...)
 * and select it with the {@code app.agent.provider} property. The agent itself only depends
 * on this interface, so adding a new backend requires no change in the agent code.
 */
public interface AgentProvider {

    /**
     * Returns the builder used to create the chat agent on top of this backend.
     */
    ChatClient.Builder agentBuilder();
}
