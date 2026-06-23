import { describe, expect, it, vi } from "vitest";
import { nextTick, watch } from "vue";
import { ApiError } from "../api/http.js";
import { createAppStore, turnsToMessages } from "./appStore.js";

function memoryStorage() {
    const values = new Map();
    return {
        getItem: (key) => values.get(key) || null,
        setItem: (key, value) => values.set(key, value),
        removeItem: (key) => values.delete(key)
    };
}

describe("appStore", () => {
    it("converts each turn to user and assistant messages", () => {
        const messages = turnsToMessages([{
            id: "turn-1",
            userText: "问题",
            assistantText: "回答",
            status: "COMPLETED",
            createdAtEpochMillis: 100,
            completedAtEpochMillis: 200,
            modelId: "qwen"
        }]);

        expect(messages.map((message) => message.role)).toEqual(["user", "assistant"]);
        expect(messages[1].content).toBe("回答");
        expect(messages[1].status).toBe("completed");
        expect(messages.every((message) => Object.hasOwn(message, "errorMessage"))).toBe(true);
        expect(messages[0].errorMessage).toBe("");
    });

    it("deletes current session and selects newest remaining session", async () => {
        const api = {
            deleteSession: vi.fn(),
            listSessions: vi.fn().mockResolvedValue([
                { sessionId: "session-2", title: "第二个会话" }
            ]),
            loadTurns: vi.fn().mockResolvedValue([])
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.sessions = [
            { sessionId: "session-1", title: "第一个会话" },
            { sessionId: "session-2", title: "第二个会话" }
        ];
        store.state.currentSessionId = "session-1";

        await store.removeSession("session-1");

        expect(api.deleteSession).toHaveBeenCalled();
        expect(store.state.currentSessionId).toBe("session-2");
        expect(api.loadTurns).toHaveBeenCalledWith(store.state.auth, "session-2");
    });

    it("appends streamed chunks to the pending assistant message", async () => {
        const api = {
            streamReactChat: vi.fn(async (_auth, _payload, onChunk) => {
                onChunk("推荐");
                onChunk("结果");
            }),
            listSessions: vi.fn().mockResolvedValue([])
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";

        await store.sendMessage({ message: "推荐跑鞋", files: [], imageUrl: "" });

        expect(store.state.messages[1].content).toBe("推荐结果");
        expect(store.state.messages[1].status).toBe("completed");
    });

    it("adds an in-chat checkout confirmation card after checkout intent", async () => {
        const api = {
            streamReactChat: vi.fn(async (_auth, _payload, onChunk) => {
                onChunk("请确认购物车。");
            }),
            listSessions: vi.fn().mockResolvedValue([]),
            loadMockCheckoutConfirmation: vi.fn().mockResolvedValue({
                sessionId: "session-1",
                items: [{ skuId: "1001", name: "旗舰降噪耳机 黑色", unitPrice: "699.00", quantity: 1, subtotal: "699.00" }],
                totalAmount: "699.00",
                empty: false
            })
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";

        await store.sendMessage({ message: "看看购物车，然后下单。", files: [], imageUrl: "" });

        expect(api.loadMockCheckoutConfirmation).toHaveBeenCalledWith(store.state.auth, "session-1");
        expect(store.state.messages.at(-1)).toMatchObject({
            role: "assistant",
            content: "请确认购物车内容后再下单。",
            status: "completed",
            orderConfirmation: {
                totalAmount: "699.00"
            }
        });
    });

    it("confirms and cancels checkout card deterministically", async () => {
        const api = {
            confirmMockCheckout: vi.fn().mockResolvedValue({ orderId: "MOCK-1", status: "CREATED" }),
            cancelMockCheckout: vi.fn().mockResolvedValue({ status: "CLEARED" })
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";
        store.state.messages = [{
            id: "checkout-1",
            role: "assistant",
            content: "请确认购物车内容后再下单。",
            status: "completed",
            createdAt: 100,
            mediaUrls: [],
            errorMessage: "",
            orderConfirmation: { sessionId: "session-1", items: [], totalAmount: "0.00", empty: false }
        }];

        await store.confirmCheckout("checkout-1");
        await store.cancelCheckout("checkout-1");

        expect(api.confirmMockCheckout).toHaveBeenCalledWith(store.state.auth, "session-1");
        expect(api.cancelMockCheckout).toHaveBeenCalledWith(store.state.auth, "session-1");
        expect(store.state.messages[0].orderConfirmation.status).toBe("cancelled");
        expect(store.state.messages.at(-2).content).toBe("已创建订单 MOCK-1。");
        expect(store.state.messages.at(-1).content).toBe("已取消下单并清空购物车。");
    });

    it("notifies watchers when streamed chunks update the assistant message", async () => {
        const api = {
            streamReactChat: vi.fn(async (_auth, _payload, onChunk) => {
                onChunk("推荐");
                await nextTick();
                onChunk("结果");
                await nextTick();
            }),
            listSessions: vi.fn().mockResolvedValue([])
        };
        const store = createAppStore(api, memoryStorage());
        const observedContents = [];
        const stopWatching = watch(
            () => store.state.messages[1]?.content || "",
            (content) => observedContents.push(content)
        );
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";

        try {
            await store.sendMessage({ message: "推荐跑鞋", files: [], imageUrl: "" });
            await nextTick();
        }
        finally {
            stopWatching();
        }

        expect(observedContents).toContain("推荐");
        expect(observedContents).toContain("推荐结果");
    });

    it("keeps assistant completed when session refresh fails after streaming", async () => {
        const api = {
            streamReactChat: vi.fn(async (_auth, _payload, onChunk) => {
                onChunk("推荐");
                onChunk("结果");
            }),
            listSessions: vi.fn().mockRejectedValue(new Error("刷新失败"))
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";

        await store.sendMessage({ message: "推荐跑鞋", files: [], imageUrl: "" });

        expect(store.state.messages[1].content).toBe("推荐结果");
        expect(store.state.messages[1].status).toBe("completed");
        expect(store.state.error).toBe("刷新失败");
    });

    it("shows a friendly refresh error when session refresh fails due to network", async () => {
        const api = {
            streamReactChat: vi.fn(async (_auth, _payload, onChunk) => {
                onChunk("推荐");
            }),
            listSessions: vi.fn().mockRejectedValue(new TypeError("Failed to fetch"))
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";

        await store.sendMessage({ message: "推荐跑鞋", files: [], imageUrl: "" });

        expect(store.state.messages[1].status).toBe("completed");
        expect(store.state.error).toBe("请检查后端地址和服务状态。");
    });

    it("keeps a visible error and failed assistant message when streaming auth expires", async () => {
        const api = {
            streamReactChat: vi.fn().mockRejectedValue(new ApiError(401, "Unauthorized")),
            listSessions: vi.fn().mockResolvedValue([])
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.isAuthenticated = true;
        store.state.currentSessionId = "session-1";

        await expect(store.sendMessage({ message: "推荐跑鞋", files: [], imageUrl: "" }))
            .rejects.toThrow("Unauthorized");

        expect(store.state.isAuthenticated).toBe(false);
        expect(store.state.error).toBe("登录已失效，请重新登录。");
        expect(store.state.messages[1]).toMatchObject({
            role: "assistant",
            status: "failed",
            errorMessage: "Unauthorized"
        });
    });

    it("shows a friendly error when login cannot reach the backend", async () => {
        const api = {
            loadModels: vi.fn().mockRejectedValue(new TypeError("Failed to fetch"))
        };
        const store = createAppStore(api, memoryStorage());

        await expect(store.login({
            apiBase: "http://localhost:18082",
            username: "alice",
            password: "demo123"
        })).rejects.toThrow("Failed to fetch");

        expect(store.state.isAuthenticated).toBe(false);
        expect(store.state.error).toBe("请检查后端地址和服务状态。");
    });

    it("keeps server error details when login returns a non-auth api error", async () => {
        const api = {
            loadModels: vi.fn().mockRejectedValue(new ApiError(500, "服务暂不可用"))
        };
        const store = createAppStore(api, memoryStorage());

        await expect(store.login({
            apiBase: "http://localhost:18082",
            username: "alice",
            password: "demo123"
        })).rejects.toThrow("服务暂不可用");

        expect(store.state.isAuthenticated).toBe(false);
        expect(store.state.error).toBe("服务暂不可用");
    });

    it("logout clears messages for manual sign out", () => {
        const store = createAppStore({}, memoryStorage());
        store.state.isAuthenticated = true;
        store.state.sessions = [{ sessionId: "session-1", title: "第一个会话" }];
        store.state.currentSessionId = "session-1";
        store.state.error = "已有错误";
        store.state.messages = [{
            id: "message-1",
            role: "assistant",
            content: "回答",
            modelId: "qwen",
            status: "completed",
            createdAt: 100,
            mediaUrls: [],
            errorMessage: ""
        }];

        store.logout();

        expect(store.state.isAuthenticated).toBe(false);
        expect(store.state.messages).toEqual([]);
        expect(store.state.sessions).toEqual([]);
        expect(store.state.currentSessionId).toBe("");
        expect(store.state.error).toBe("");
    });

    it("keeps current session and media when selecting another session fails", async () => {
        const revokeObjectURL = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
        const api = {
            loadTurns: vi.fn().mockRejectedValue(new Error("加载失败"))
        };
        const store = createAppStore(api, memoryStorage());
        const currentMessages = [{
            id: "message-1",
            role: "user",
            content: "当前消息",
            modelId: "qwen",
            status: "completed",
            createdAt: 100,
            mediaUrls: ["blob:current"],
            errorMessage: ""
        }];
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";
        store.state.messages = currentMessages;

        await expect(store.selectSession("session-2")).rejects.toThrow("加载失败");

        expect(store.state.currentSessionId).toBe("session-1");
        expect(store.state.messages).toStrictEqual(currentMessages);
        expect(store.state.error).toBe("加载失败");
        expect(revokeObjectURL).not.toHaveBeenCalled();
        revokeObjectURL.mockRestore();
    });

    it("keeps sessions unchanged and stores an error when deleting fails", async () => {
        const api = {
            deleteSession: vi.fn().mockRejectedValue(new Error("删除失败")),
            listSessions: vi.fn(),
            loadTurns: vi.fn()
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.sessions = [{ sessionId: "session-1", title: "第一个会话" }];
        store.state.currentSessionId = "session-1";

        await expect(store.removeSession("session-1")).rejects.toThrow("删除失败");

        expect(store.state.sessions).toEqual([{ sessionId: "session-1", title: "第一个会话" }]);
        expect(store.state.currentSessionId).toBe("session-1");
        expect(store.state.error).toBe("删除失败");
        expect(api.listSessions).not.toHaveBeenCalled();
    });

    it("rethrows send failures after storing assistant error", async () => {
        const api = {
            streamReactChat: vi.fn().mockRejectedValue(new Error("请求失败")),
            listSessions: vi.fn().mockResolvedValue([])
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";

        await expect(store.sendMessage({ message: "推荐跑鞋", files: [], imageUrl: "" }))
            .rejects.toThrow("请求失败");

        expect(store.state.messages[1]).toMatchObject({
            role: "assistant",
            status: "failed",
            errorMessage: "请求失败"
        });
        expect(store.state.error).toBe("请求失败");
    });

    it("normalizes network send failures to a friendly error", async () => {
        const api = {
            streamReactChat: vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
            listSessions: vi.fn().mockResolvedValue([])
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };
        store.state.currentSessionId = "session-1";

        await expect(store.sendMessage({ message: "推荐跑鞋", files: [], imageUrl: "" }))
            .rejects.toThrow("Failed to fetch");

        expect(store.state.messages[1]).toMatchObject({
            role: "assistant",
            status: "failed",
            errorMessage: "请检查后端地址和服务状态。"
        });
        expect(store.state.error).toBe("请检查后端地址和服务状态。");
    });

    it("restores a valid saved model preference after login", async () => {
        const storage = memoryStorage();
        storage.setItem("rag-agent-vue-preferences", JSON.stringify({
            selectedModelId: "qwen",
            webSearchEnabled: true
        }));
        const api = {
            loadModels: vi.fn().mockResolvedValue({
                defaultModel: "default",
                items: [{ id: "default" }, { id: "qwen" }]
            }),
            listSessions: vi.fn().mockResolvedValue([])
        };
        const store = createAppStore(api, storage);

        await store.login({ apiBase: "http://localhost:18082", username: "alice", password: "demo123" });

        expect(store.state.selectedModelId).toBe("qwen");
        expect(store.state.webSearchEnabled).toBe(true);
    });

    it("does not let storage failures block login", async () => {
        const throwingStorage = {
            getItem: () => {
                throw new Error("get failed");
            },
            setItem: () => {
                throw new Error("set failed");
            },
            removeItem: () => {
                throw new Error("remove failed");
            }
        };
        const api = {
            loadModels: vi.fn().mockResolvedValue({
                defaultModel: "default",
                items: [{ id: "default" }]
            }),
            listSessions: vi.fn().mockResolvedValue([])
        };
        const store = createAppStore(api, throwingStorage);

        await store.login({ apiBase: "http://localhost:18082", username: "alice", password: "demo123" });

        expect(store.state.isAuthenticated).toBe(true);
        expect(store.state.selectedModelId).toBe("default");
    });

    it("loads more sessions by appending the next page", async () => {
        const api = {
            listSessions: vi.fn()
                .mockResolvedValueOnce(Array.from({ length: 20 }, (_, index) => ({
                    sessionId: `session-${index + 1}`,
                    title: `Session ${index + 1}`
                })))
                .mockResolvedValueOnce([
                    { sessionId: "session-21", title: "Session 21" }
                ])
        };
        const store = createAppStore(api, memoryStorage());
        store.state.auth = { apiBase: "http://localhost:18082", username: "alice", password: "demo123" };

        await store.refreshSessions();
        await store.loadMoreSessions();

        expect(api.listSessions).toHaveBeenNthCalledWith(1, store.state.auth, { limit: 20, offset: 0 });
        expect(api.listSessions).toHaveBeenNthCalledWith(2, store.state.auth, { limit: 20, offset: 20 });
        expect(store.state.sessions).toHaveLength(21);
        expect(store.state.sessions.at(-1).sessionId).toBe("session-21");
        expect(store.state.hasMoreSessions).toBe(false);
    });
});
