package com.example.ragagent.service;

import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

record FastLaneResult(
        boolean handled,
        boolean fallbackToCore,
        Flux<String> stream,
        String fallbackReason
) {

    static FastLaneResult notHandled() {
        return new FastLaneResult(false, false, null, "");
    }

    static FastLaneResult handled(String answer) {
        return handled(Flux.just(StringUtils.hasText(answer) ? answer : ""));
    }

    static FastLaneResult handled(Flux<String> stream) {
        return new FastLaneResult(true, false, stream == null ? Flux.empty() : stream, "");
    }

    static FastLaneResult fallbackToCore(String reason) {
        return new FastLaneResult(false, true, null, StringUtils.hasText(reason) ? reason.trim() : "fast lane fallback");
    }
}
