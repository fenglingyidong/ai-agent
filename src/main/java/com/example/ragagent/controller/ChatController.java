package com.example.ragagent.controller;

import com.example.ragagent.service.ReActAgent;
import com.example.ragagent.service.ChatModelRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final ReActAgent reActAgent;
    private final ChatModelRegistry chatModelRegistry;

    public ChatController(ReActAgent reActAgent, ChatModelRegistry chatModelRegistry) {
        this.reActAgent = reActAgent;
        this.chatModelRegistry = chatModelRegistry;
    }

    @GetMapping("/react")
    public ResponseEntity<StreamingResponseBody> react(
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "webSearchEnabled", defaultValue = "false") boolean webSearchEnabled,
            @RequestParam(value = "message", defaultValue = "hello") String message) {
        return streamFlux(reActAgent.runStream(message, modelId, webSearchEnabled));
    }

    @GetMapping("/models/chat")
    public ChatModelsResponse chatModels() {
        return new ChatModelsResponse(chatModelRegistry.getDefaultModelId(), chatModelRegistry.listAvailableModels());
    }

    private ResponseEntity<StreamingResponseBody> streamFlux(Flux<String> responseStream) {
        StreamingResponseBody body = outputStream -> {
            try {
                for (String chunk : responseStream.toIterable()) {
                    if (chunk == null || chunk.isEmpty()) {
                        continue;
                    }
                    outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            }
            catch (RuntimeException ex) {
                throw new IOException("Failed to stream model response", ex);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN_UTF8.toString())
                .body(body);
    }

    public record ChatModelsResponse(
            String defaultModel,
            java.util.List<ChatModelRegistry.AvailableChatModel> items
    ) {
    }
}
