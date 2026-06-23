import { afterEach, describe, expect, it, vi } from "vitest";
import {
    cancelMockCheckout,
    confirmMockCheckout,
    loadMockCheckoutConfirmation,
    listSessions,
    streamReactChat
} from "./chat.js";

describe("chat api", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("submits session model search flag and streams response", async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response("推荐结果"));
        vi.stubGlobal("fetch", fetchMock);
        const chunks = [];

        const result = await streamReactChat(
            { apiBase: "http://localhost:18082", username: "alice", password: "demo123" },
            {
                sessionId: "shopping-1",
                message: "推荐跑鞋",
                modelId: "qwen",
                webSearchEnabled: true,
                files: [],
                imageUrl: ""
            },
            (chunk) => chunks.push(chunk)
        );

        const [, options] = fetchMock.mock.calls[0];
        expect(options.body.get("sessionId")).toBe("shopping-1");
        expect(options.body.get("message")).toBe("推荐跑鞋");
        expect(options.body.get("modelId")).toBe("qwen");
        expect(options.body.get("webSearchEnabled")).toBe("true");
        expect(result).toBe("推荐结果");
        expect(chunks.join("")).toBe("推荐结果");
    });

    it("loads conversation sessions with limit and offset", async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
            items: [{ sessionId: "session-21" }]
        }), {
            headers: { "Content-Type": "application/json" }
        }));
        vi.stubGlobal("fetch", fetchMock);

        const sessions = await listSessions(
            { apiBase: "http://localhost:18082", username: "alice", password: "demo123" },
            { limit: 20, offset: 40 }
        );

        expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:18082/api/conversations?limit=20&offset=40");
        expect(sessions).toEqual([{ sessionId: "session-21" }]);
    });

    it("loads mock checkout confirmation for the current session", async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
            sessionId: "shopping-1",
            items: [{ skuId: "1001" }],
            totalAmount: "699.00",
            empty: false
        }), {
            headers: { "Content-Type": "application/json" }
        }));
        vi.stubGlobal("fetch", fetchMock);

        const confirmation = await loadMockCheckoutConfirmation(
            { apiBase: "http://localhost:18082", username: "alice", password: "demo123" },
            "shopping-1"
        );

        expect(fetchMock.mock.calls[0][0])
            .toBe("http://localhost:18082/api/mock/checkout/confirmation?sessionId=shopping-1");
        expect(confirmation.totalAmount).toBe("699.00");
    });

    it("submits mock checkout confirmation and cancellation", async () => {
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(new Response(JSON.stringify({ status: "CREATED", orderId: "MOCK-1" }), {
                headers: { "Content-Type": "application/json" }
            }))
            .mockResolvedValueOnce(new Response(JSON.stringify({ status: "CLEARED" }), {
                headers: { "Content-Type": "application/json" }
            }));
        vi.stubGlobal("fetch", fetchMock);
        const auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };

        const confirmed = await confirmMockCheckout(auth, "shopping-1");
        const cancelled = await cancelMockCheckout(auth, "shopping-1");

        expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:18082/api/mock/checkout/confirm");
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ sessionId: "shopping-1" });
        expect(fetchMock.mock.calls[1][0]).toBe("http://localhost:18082/api/mock/checkout/cancel");
        expect(confirmed.orderId).toBe("MOCK-1");
        expect(cancelled.status).toBe("CLEARED");
    });
});
