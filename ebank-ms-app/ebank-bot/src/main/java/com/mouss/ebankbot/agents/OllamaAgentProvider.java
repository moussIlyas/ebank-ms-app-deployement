package com.mouss.ebankbot.agents;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatProperties;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.agent.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaAgentProvider implements AgentProvider {

    private final ChatClient.Builder agentBuilder;

    public OllamaAgentProvider(OllamaApi ollamaApi,
                               OllamaChatProperties chatProperties,
                               ObjectProvider<ObservationRegistry> observationRegistry,
                               ObjectProvider<ToolCallingAdvisor.Builder<?>> toolCallingAdvisorBuilder) {
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(chatProperties.toOptions())
                .build();

        this.agentBuilder = ChatClient.builder(
                chatModel,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
                null,
                null,
                toolCallingAdvisorBuilder.getIfAvailable());
    }

    @Override
    public ChatClient.Builder agentBuilder() {
        return agentBuilder.clone();
    }
}
