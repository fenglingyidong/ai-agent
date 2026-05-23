package com.example.ragagent.controller;

import com.example.ragagent.mall.MallAuthCache;
import com.example.ragagent.mall.MallProperties;
import com.example.ragagent.service.ReActAgent;
import com.example.ragagent.service.ChatModelRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.content.Media;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");
    private static final int MAX_IMAGE_COUNT = 4;
    private static final String DEFAULT_USER_ID = "anonymous";
    private static final String DEFAULT_SESSION_ID = "default";
    private static final String DEFAULT_MESSAGE = "请帮我看看这件商品适合什么场景";

    private final ReActAgent reActAgent;

    private final ChatModelRegistry chatModelRegistry;

    private final MallProperties mallProperties;

    private final MallAuthCache mallAuthCache;

    @Autowired
    public ChatController(ReActAgent reActAgent,
                          ChatModelRegistry chatModelRegistry,
                          MallProperties mallProperties,
                          MallAuthCache mallAuthCache) {
        this.reActAgent = reActAgent;
        this.chatModelRegistry = chatModelRegistry;
        this.mallProperties = mallProperties;
        this.mallAuthCache = mallAuthCache;
    }

    public ChatController(ReActAgent reActAgent, ChatModelRegistry chatModelRegistry, MallProperties mallProperties) {
        this(reActAgent, chatModelRegistry, mallProperties, null);
    }

    @PostMapping(value = "/react", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StreamingResponseBody> react(
            @RequestParam(value = "message", defaultValue = DEFAULT_MESSAGE) String message,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "webSearchEnabled", defaultValue = "false") boolean webSearchEnabled,
            @RequestParam(value = "image", required = false) List<MultipartFile> images,
            @RequestParam(value = "imageUrl", required = false) List<String> imageUrls,
            HttpServletRequest request,
            Authentication authentication) {
        List<Media> media = new ArrayList<>();
        media.addAll(buildImageMedia(images));
        media.addAll(buildImageUrlMedia(imageUrls));
        if (media.size() > MAX_IMAGE_COUNT) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "at most " + MAX_IMAGE_COUNT + " images are allowed");
        }

        String currentUserId = resolveCurrentUserId(authentication);
        BasicCredentials credentials = resolveBasicAuth(request);
        return streamFlux(reActAgent.runStream(
                currentUserId,
                resolveSessionId(sessionId, request),
                modelId,
                normalizeMessage(message),
                webSearchEnabled,
                media,
                resolveMallToken(request, currentUserId),
                credentials.username(),
                credentials.password()
        ));
    }

    @GetMapping("/models/chat")
    public ChatModelsResponse chatModels() {
        return new ChatModelsResponse(chatModelRegistry.getDefaultModelId(), chatModelRegistry.listAvailableModels());
    }

    private List<Media> buildImageMedia(List<MultipartFile> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<Media> media = new ArrayList<>();
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty()) {
                continue;
            }
            MediaType contentType = image.getContentType() == null
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(image.getContentType());
            if (!isImage(contentType)) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "only image multipart files are supported");
            }
            try {
                media.add(new Media(contentType, new NamedByteArrayResource(image.getBytes(), image.getOriginalFilename())));
            }
            catch (IOException ex) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "failed to read uploaded image", ex);
            }
        }
        return media;
    }

    private List<Media> buildImageUrlMedia(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        List<Media> media = new ArrayList<>();
        for (String imageUrl : imageUrls) {
            if (!StringUtils.hasText(imageUrl)) {
                continue;
            }
            try {
                URI uri = new URI(imageUrl.trim());
                if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "imageUrl must use http or https");
                }
                media.add(new Media(MediaType.IMAGE_JPEG, uri));
            }
            catch (URISyntaxException ex) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid imageUrl", ex);
            }
        }
        return media;
    }

    private boolean isImage(MimeType contentType) {
        return contentType != null && "image".equalsIgnoreCase(contentType.getType());
    }

    private String normalizeMessage(String message) {
        return StringUtils.hasText(message) ? message.trim() : DEFAULT_MESSAGE;
    }

    private ResponseEntity<StreamingResponseBody> streamFlux(Flux<String> responseStream) {
        StreamingResponseBody body = outputStream -> {
            try {
                Flux<String> safeStream = responseStream == null ? Flux.empty() : responseStream;
                for (String chunk : safeStream.toIterable()) {
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

    private String resolveCurrentUserId(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && StringUtils.hasText(authentication.getName())
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return DEFAULT_USER_ID;
    }

    private String resolveSessionId(String sessionId, HttpServletRequest request) {
        if (StringUtils.hasText(sessionId)) {
            return sessionId.trim();
        }
        if (request != null && request.getSession(false) != null) {
            return request.getSession(false).getId();
        }
        return DEFAULT_SESSION_ID;
    }

    private String resolveMallToken(HttpServletRequest request, String currentUserId) {
        String headerName = mallProperties == null ? "X-Mall-Authorization" : mallProperties.getAuthorizationHeader();
        String token = request == null ? "" : request.getHeader(headerName);
        if (StringUtils.hasText(token)) {
            return token.trim();
        }
        if (mallAuthCache == null || !StringUtils.hasText(currentUserId) || DEFAULT_USER_ID.equals(currentUserId)) {
            return "";
        }
        return mallAuthCache.get(buildMallAuthCacheKey(currentUserId));
    }

    private String buildMallAuthCacheKey(String username) {
        return username.trim() + ":default:mall";
    }

    private BasicCredentials resolveBasicAuth(HttpServletRequest request) {
        if (request == null) {
            return new BasicCredentials("", "");
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith("Basic ")) {
            return new BasicCredentials("", "");
        }
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 0) {
                return new BasicCredentials("", "");
            }
            return new BasicCredentials(decoded.substring(0, separator), decoded.substring(separator + 1));
        }
        catch (IllegalArgumentException ex) {
            return new BasicCredentials("", "");
        }
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = StringUtils.hasText(filename) ? filename : "uploaded-image";
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    private record BasicCredentials(String username, String password) {
    }

    public record ChatModelsResponse(
            String defaultModel,
            java.util.List<ChatModelRegistry.AvailableChatModel> items
    ) {
    }
}
