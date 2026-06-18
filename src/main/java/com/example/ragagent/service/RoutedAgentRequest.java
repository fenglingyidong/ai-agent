package com.example.ragagent.service;

import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.List;

record RoutedAgentRequest(String userMessage,
                          List<Media> media,
                          Flux<String> shortCircuitStream,
                          boolean mallToolsAllowed,
                          List<ShoppingTaskPolicy> taskPolicies,
                          boolean orderCreationAllowed,
                          String trustedContext) {

    RoutedAgentRequest {
        media = media == null ? List.of() : List.copyOf(media);
        taskPolicies = taskPolicies == null ? List.of() : List.copyOf(taskPolicies);
        trustedContext = trustedContext == null ? "" : trustedContext.trim();
    }

    RoutedAgentRequest(String userMessage, List<Media> media, Flux<String> shortCircuitStream) {
        this(userMessage, media, shortCircuitStream, true, List.of(), false, "");
    }

    RoutedAgentRequest(String userMessage,
                       List<Media> media,
                       Flux<String> shortCircuitStream,
                       boolean mallToolsAllowed) {
        this(userMessage, media, shortCircuitStream, mallToolsAllowed, List.of(), false, "");
    }

    RoutedAgentRequest(String userMessage,
                       List<Media> media,
                       Flux<String> shortCircuitStream,
                       boolean mallToolsAllowed,
                       List<ShoppingTaskPolicy> taskPolicies) {
        this(userMessage, media, shortCircuitStream, mallToolsAllowed, taskPolicies, false, "");
    }

    RoutedAgentRequest(String userMessage,
                       List<Media> media,
                       Flux<String> shortCircuitStream,
                       boolean mallToolsAllowed,
                       List<ShoppingTaskPolicy> taskPolicies,
                       boolean orderCreationAllowed) {
        this(userMessage, media, shortCircuitStream, mallToolsAllowed, taskPolicies, orderCreationAllowed, "");
    }
}
