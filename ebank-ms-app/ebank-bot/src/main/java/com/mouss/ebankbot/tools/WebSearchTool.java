package com.mouss.ebankbot.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class WebSearchTool {

    private final String baseUrl;
    private final String apiKey;
    private final int maxResults;
    private final RestClient restClient;

    public WebSearchTool(
            @Value("${app.tavily.base-url}") String baseUrl,
            @Value("${app.tavily.api-key}") String apiKey,
            @Value("${app.tavily.max-results}") int maxResults,
            RestClient.Builder restClientBuilder) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxResults = maxResults;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Tool(name = "webSearch",
            description = "Search the web for current information, news, exchange rates and recent facts. "
                    + "Returns the most relevant results with their titles, URLs and a content summary.")
    public String webSearch(
            @ToolParam(description = "The search query, written as a natural language question") String query) {

        if (!StringUtils.hasText(apiKey)) {
            return "Web search is not configured: missing Tavily API key.";
        }

        try {
            TavilyResponse response = restClient.post()
                    .uri("/search")
                    .body(Map.of(
                            "query", query,
                            "max_results", maxResults,
                            "search_depth", "basic"))
                    .retrieve()
                    .body(TavilyResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return "No web search results found for: " + query;
            }

            StringBuilder result = new StringBuilder("Search results for: ").append(query).append("\n\n");
            int index = 1;
            for (Result item : response.results()) {
                result.append(index++).append(". ").append(item.title()).append('\n')
                        .append(item.url()).append('\n')
                        .append(truncate(item.content())).append("\n\n");
            }
            return result.toString();
        }
        catch (RestClientException ex) {
            return "Web search failed: " + ex.getMessage();
        }
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        int maxLength = 500;
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }

    record TavilyResponse(List<Result> results) {
    }

    record Result(String title, String url, String content) {
    }
}
