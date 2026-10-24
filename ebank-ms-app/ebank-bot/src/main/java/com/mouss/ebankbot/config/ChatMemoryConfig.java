package com.mouss.ebankbot.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        ChatMemory delegate = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();

        return new ChatMemory() {
            @Override
            public void add(String conversationId, List<Message> messages) {
                List<Message> cleaned = messages.stream()
                        .map(m -> {
                            if (m instanceof AssistantMessage a
                                    && (a.getToolCalls() == null || a.getToolCalls().isEmpty())) {
                                return (Message) new AssistantMessage(a.getText());
                            }
                            return m;
                        })
                        .toList();
                delegate.add(conversationId, cleaned);
            }

            @Override
            public List<Message> get(String conversationId) {
                return delegate.get(conversationId);
            }

            @Override
            public void clear(String conversationId) {
                delegate.clear(conversationId);
            }
        };
    }
}