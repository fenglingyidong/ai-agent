package com.example.ragagent.controller;

import com.example.ragagent.mall.MallProperties;
import com.example.ragagent.service.ChatModelRegistry;
import com.example.ragagent.service.ReActAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.content.Media;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void reactShouldConvertMultipartImageToSpringAiMedia() {
        ReActAgent reActAgent = mock(ReActAgent.class);
        ChatController controller = new ChatController(reActAgent, mock(ChatModelRegistry.class), new MallProperties());
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "shoe.png",
                "image/png",
                new byte[]{1, 2, 3}
        );

        whenRunStream(reActAgent).thenReturn(Flux.just("ok"));

        controller.react(
                "请帮我找相似款",
                "front-session",
                null,
                false,
                List.of(image),
                List.of(),
                new MockHttpServletRequest(),
                null
        );

        ArgumentCaptor<List<Media>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(reActAgent).runStream(
                anyString(),
                sessionCaptor.capture(),
                isNull(),
                messageCaptor.capture(),
                anyBoolean(),
                mediaCaptor.capture(),
                anyString(),
                anyString(),
                anyString()
        );

        assertEquals(1, mediaCaptor.getValue().size());
        assertEquals("front-session", sessionCaptor.getValue());
        assertEquals("image/png", mediaCaptor.getValue().get(0).getMimeType().toString());
        assertEquals("请帮我找相似款", messageCaptor.getValue());
    }

    @Test
    void reactShouldForwardModelAndWebSearchFlags() {
        ReActAgent reActAgent = mock(ReActAgent.class);
        ChatController controller = new ChatController(reActAgent, mock(ChatModelRegistry.class), new MallProperties());
        whenRunStream(reActAgent).thenReturn(Flux.just("ok"));

        controller.react(
                "推荐跑步鞋",
                "front-session",
                "qwen",
                true,
                List.of(),
                List.of(),
                new MockHttpServletRequest(),
                null
        );

        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> webSearchCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(reActAgent).runStream(
                anyString(),
                anyString(),
                modelCaptor.capture(),
                anyString(),
                webSearchCaptor.capture(),
                org.mockito.ArgumentMatchers.<List<Media>>any(),
                anyString(),
                anyString(),
                anyString()
        );

        assertEquals("qwen", modelCaptor.getValue());
        assertEquals(true, webSearchCaptor.getValue());
    }

    @Test
    void reactShouldUseInjectedAuthenticationNameAsUserId() {
        ReActAgent reActAgent = mock(ReActAgent.class);
        ChatController controller = new ChatController(reActAgent, mock(ChatModelRegistry.class), new MallProperties());
        whenRunStream(reActAgent).thenReturn(Flux.just("ok"));

        controller.react(
                "推荐跑步鞋",
                "front-session",
                null,
                false,
                List.of(),
                List.of(),
                new MockHttpServletRequest(),
                UsernamePasswordAuthenticationToken.authenticated("mall-user", null, List.of())
        );

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(reActAgent).runStream(
                userIdCaptor.capture(),
                anyString(),
                isNull(),
                anyString(),
                anyBoolean(),
                org.mockito.ArgumentMatchers.<List<Media>>any(),
                anyString(),
                anyString(),
                anyString()
        );

        assertEquals("mall-user", userIdCaptor.getValue());
    }

    @Test
    void reactShouldStreamPlainTextResponse() throws Exception {
        ReActAgent reActAgent = mock(ReActAgent.class);
        ChatController controller = new ChatController(reActAgent, mock(ChatModelRegistry.class), new MallProperties());
        whenRunStream(reActAgent).thenReturn(Flux.just("hello", " world"));

        StreamingResponseBody body = controller.react(
                        "请推荐跑步鞋",
                        "front-session",
                        null,
                        false,
                        List.of(),
                        List.of(),
                        new MockHttpServletRequest(),
                        null
                )
                .getBody();

        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        body.writeTo(outputStream);

        assertEquals("hello world", outputStream.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reactShouldForwardMallHeadersToAgentTools() {
        ReActAgent reActAgent = mock(ReActAgent.class);
        ChatController controller = new ChatController(reActAgent, mock(ChatModelRegistry.class), new MallProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Mall-Authorization", "Bearer mall-token");
        request.addHeader(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("mall-user:mall-pass".getBytes(StandardCharsets.UTF_8))
        );
        whenRunStream(reActAgent).thenReturn(Flux.just("ok"));

        controller.react(
                "查购物车",
                "front-session",
                null,
                false,
                List.of(),
                List.of(),
                request,
                null
        );

        ArgumentCaptor<String> mallTokenCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> mallUsernameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> mallPasswordCaptor = ArgumentCaptor.forClass(String.class);
        verify(reActAgent).runStream(
                anyString(),
                anyString(),
                isNull(),
                anyString(),
                anyBoolean(),
                org.mockito.ArgumentMatchers.<List<Media>>any(),
                mallTokenCaptor.capture(),
                mallUsernameCaptor.capture(),
                mallPasswordCaptor.capture()
        );

        assertEquals("Bearer mall-token", mallTokenCaptor.getValue());
        assertEquals("mall-user", mallUsernameCaptor.getValue());
        assertEquals("mall-pass", mallPasswordCaptor.getValue());
    }

    private org.mockito.stubbing.OngoingStubbing<Flux<String>> whenRunStream(ReActAgent reActAgent) {
        return when(reActAgent.runStream(
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                anyString(),
                anyBoolean(),
                org.mockito.ArgumentMatchers.<List<Media>>any(),
                anyString(),
                anyString(),
                anyString()
        ));
    }
}
