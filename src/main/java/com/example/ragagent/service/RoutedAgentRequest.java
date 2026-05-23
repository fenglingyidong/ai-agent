package com.example.ragagent.service;

import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.List;

record RoutedAgentRequest(String userMessage,
                          List<Media> media,
                          Flux<String> shortCircuitStream,
                          boolean mallToolsAllowed,
                          List<ShoppingTaskPolicy> taskPolicies) {

    RoutedAgentRequest {
        media = media == null ? List.of() : List.copyOf(media);
        taskPolicies = taskPolicies == null ? List.of() : List.copyOf(taskPolicies);
    }

    RoutedAgentRequest(String userMessage, List<Media> media, Flux<String> shortCircuitStream) {
        this(userMessage, media, shortCircuitStream, true, List.of());
    }

    RoutedAgentRequest(String userMessage,
                       List<Media> media,
                       Flux<String> shortCircuitStream,
                       boolean mallToolsAllowed) {
        this(userMessage, media, shortCircuitStream, mallToolsAllowed, List.of());
    }
}
