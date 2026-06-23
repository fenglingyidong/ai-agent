package com.example.ragagent.controller;

import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/mock/checkout")
public class MockCheckoutController {

    private final Map<String, List<CartItem>> carts = new ConcurrentHashMap<>();

    @GetMapping("/confirmation")
    public OrderConfirmationResponse confirmation(@RequestParam String sessionId,
                                                  Authentication authentication) {
        String userId = userId(authentication);
        List<CartItem> items = cart(userId, sessionId);
        return new OrderConfirmationResponse(
                sessionId,
                userId,
                items,
                amount(items),
                items.isEmpty()
        );
    }

    @PostMapping("/confirm")
    public OrderSubmitResponse confirm(@RequestBody OrderActionRequest request,
                                       Authentication authentication) {
        String userId = userId(authentication);
        String sessionId = sessionId(request);
        List<CartItem> items = cart(userId, sessionId);
        String orderId = "MOCK-" + Math.abs((userId + ":" + sessionId + ":" + items.size()).hashCode());
        return new OrderSubmitResponse(orderId, "CREATED", sessionId, items, amount(items));
    }

    @PostMapping("/cancel")
    public OrderActionResponse cancel(@RequestBody OrderActionRequest request,
                                      Authentication authentication) {
        String userId = userId(authentication);
        carts.put(key(userId, sessionId(request)), List.of());
        return new OrderActionResponse("CLEARED", request.sessionId());
    }

    private List<CartItem> cart(String userId, String sessionId) {
        return carts.computeIfAbsent(key(userId, sessionId), ignored -> new ArrayList<>(List.of(
                new CartItem("1001", "旗舰降噪耳机 黑色", "699.00", 1, "699.00"),
                new CartItem("2001", "轻量跑步鞋 42码", "399.00", 1, "399.00"),
                new CartItem("3020", "儿童积木套装 300片", "149.00", 3, "447.00")
        )));
    }

    private String amount(List<CartItem> items) {
        BigDecimal total = items.stream()
                .map(item -> new BigDecimal(item.subtotal()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String key(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private String userId(Authentication authentication) {
        if (authentication == null || !StringUtils.hasText(authentication.getName())) {
            return "anonymous";
        }
        return authentication.getName();
    }

    private String sessionId(OrderActionRequest request) {
        return request == null || !StringUtils.hasText(request.sessionId()) ? "default" : request.sessionId();
    }

    public record OrderConfirmationResponse(
            String sessionId,
            String userId,
            List<CartItem> items,
            String totalAmount,
            boolean empty
    ) {
    }

    public record CartItem(
            String skuId,
            String name,
            String unitPrice,
            int quantity,
            String subtotal
    ) {
    }

    public record OrderActionRequest(String sessionId) {
    }

    public record OrderActionResponse(String status, String sessionId) {
    }

    public record OrderSubmitResponse(
            String orderId,
            String status,
            String sessionId,
            List<CartItem> items,
            String totalAmount
    ) {
    }
}
