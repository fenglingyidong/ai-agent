import { afterEach, describe, expect, it, vi } from "vitest";
import { streamReactChat } from "./chat.js";

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
});
