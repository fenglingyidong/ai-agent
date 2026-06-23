package com.example.ragagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockCheckoutControllerTest {

    @Test
    void confirmationShouldReturnCartItemsAndTotal() {
        MockCheckoutController controller = new MockCheckoutController();

        MockCheckoutController.OrderConfirmationResponse response = controller.confirmation(
                "session-1",
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of())
        );

        assertEquals("session-1", response.sessionId());
        assertEquals("alice", response.userId());
        assertEquals(3, response.items().size());
        assertEquals("1001", response.items().get(0).skuId());
        assertEquals("旗舰降噪耳机 黑色", response.items().get(0).name());
        assertEquals("699.00", response.items().get(0).unitPrice());
        assertEquals("1545.00", response.totalAmount());
        assertFalse(response.empty());
    }

    @Test
    void confirmShouldCreateMockOrderFromCurrentCart() {
        MockCheckoutController controller = new MockCheckoutController();

        MockCheckoutController.OrderSubmitResponse response = controller.confirm(
                new MockCheckoutController.OrderActionRequest("session-1"),
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of())
        );

        assertEquals("CREATED", response.status());
        assertTrue(response.orderId().startsWith("MOCK-"));
        assertEquals("1545.00", response.totalAmount());
    }

    @Test
    void cancelShouldClearMockCartForCurrentUserAndSession() {
        MockCheckoutController controller = new MockCheckoutController();

        MockCheckoutController.OrderActionResponse response = controller.cancel(
                new MockCheckoutController.OrderActionRequest("session-1"),
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of())
        );
        MockCheckoutController.OrderConfirmationResponse confirmation = controller.confirmation(
                "session-1",
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of())
        );

        assertEquals("CLEARED", response.status());
        assertTrue(confirmation.empty());
        assertEquals("0.00", confirmation.totalAmount());
    }
}
