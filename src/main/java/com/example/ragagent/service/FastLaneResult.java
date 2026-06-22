package com.example.ragagent.service;

import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 简单任务快车道的执行结果，表示已处理、未处理或需要回退主链路。
 */
record FastLaneResult(
        boolean handled,
        boolean fallbackToCore,
        Flux<String> stream,
        String fallbackReason
) {

    /**
     * 表示快车道不处理本轮请求。
     */
    static FastLaneResult notHandled() {
        return new FastLaneResult(false, false, null, "");
    }

    /**
     * 用固定文本构造已处理的快车道结果。
     */
    static FastLaneResult handled(String answer) {
        return handled(Flux.just(StringUtils.hasText(answer) ? answer : ""));
    }

    /**
     * 用流式文本构造已处理的快车道结果。
     */
    static FastLaneResult handled(Flux<String> stream) {
        return new FastLaneResult(true, false, stream == null ? Flux.empty() : stream, "");
    }

    /**
     * 表示快车道主动放弃，由主 ReAct Agent 继续处理。
     */
    static FastLaneResult fallbackToCore(String reason) {
        return new FastLaneResult(false, true, null, StringUtils.hasText(reason) ? reason.trim() : "fast lane fallback");
    }
}
