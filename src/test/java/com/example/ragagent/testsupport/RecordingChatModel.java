package com.example.ragagent.testsupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public final class RecordingChatModel implements ChatModel {

    private final List<Prompt> prompts = new ArrayList<>();
    private Function<Prompt, Flux<ChatResponse>> streamFunction = prompt -> Flux.empty();

    public static RecordingChatModel responding(String... chunks) {
        return new RecordingChatModel().thenRespond(chunks);
    }

    public RecordingChatModel thenRespond(String... chunks) {
        List<String> safeChunks = chunks == null ? List.of() : Arrays.asList(chunks);
        this.streamFunction = prompt -> Flux.fromIterable(safeChunks).map(RecordingChatModel::chatResponse);
        return this;
    }

    public RecordingChatModel thenStream(Flux<String> chunks) {
        Flux<String> safeChunks = chunks == null ? Flux.empty() : chunks;
        this.streamFunction = prompt -> safeChunks.map(RecordingChatModel::chatResponse);
        return this;
    }

    public RecordingChatModel thenThrow(RuntimeException ex) {
        this.streamFunction = prompt -> {
            throw ex;
        };
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return chatResponse("");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        return streamFunction.apply(prompt);
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public Prompt lastPrompt() {
        if (prompts.isEmpty()) {
            throw new IllegalStateException("No prompt was recorded");
        }
        return prompts.get(prompts.size() - 1);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
