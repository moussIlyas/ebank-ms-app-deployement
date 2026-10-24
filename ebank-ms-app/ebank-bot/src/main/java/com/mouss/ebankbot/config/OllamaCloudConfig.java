package com.mouss.ebankbot.config;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.function.Consumer;

/**
 * Builds the Ollama API client. Base URL and API key come from application.properties.
 */
@Configuration
public class OllamaCloudConfig {

    @Bean
    public OllamaApi ollamaApi(
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${spring.ai.ollama.api-key:}") String apiKey) {

        RestClient.Builder restClientBuilder = RestClient.builder();
        WebClient.Builder webClientBuilder = WebClient.builder();

        if (StringUtils.hasText(apiKey)) {
            Consumer<HttpHeaders> authorization = headers -> headers.setBearerAuth(apiKey);
            restClientBuilder.defaultHeaders(authorization);
            webClientBuilder.defaultHeaders(authorization);
        }

        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();
    }
}
