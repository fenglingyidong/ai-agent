package com.example.ragagent.service;

import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.List;

record RoutedAgentRequest(String userMessage,
                          List<Media> media,
                          Flux<String> shortCircuitStream,
                          boolean mallToolsAllowed) {

    RoutedAgentRequest(String userMessage, List<Media> media, Flux<String> shortCircuitStream) {
        this(userMessage, media, shortCircuitStream, true);
    }
}
