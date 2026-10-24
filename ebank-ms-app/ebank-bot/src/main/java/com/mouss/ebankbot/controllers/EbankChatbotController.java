package com.mouss.ebankbot.controllers;

import com.mouss.ebankbot.agents.EbankAIAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EbankChatbotController {

    private final EbankAIAgent agent;

    public EbankChatbotController(EbankAIAgent agent) {
        this.agent = agent;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam(name = "query", defaultValue = "Bonjour") String query) {
        return agent.chat("default", query);
    }
}
