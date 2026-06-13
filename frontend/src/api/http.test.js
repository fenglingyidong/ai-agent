import { afterEach, describe, expect, it, vi } from "vitest";
import { createAuthHeaders, readTextStream, requestJson } from "./http.js";

function encodeBasicAuth(username, password) {
    const bytes = new TextEncoder().encode(`${username}:${password}`);
    let binary = "";
    bytes.forEach((byte) => {
        binary += String.fromCharCode(byte);
    });
    return `Basic ${btoa(binary)}`;
}

describe("http", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("creates utf8 basic auth header", () => {
        expect(createAuthHeaders({ username: "alice", password: "demo123" }))
            .toEqual({ Authorization: "Basic YWxpY2U6ZGVtbzEyMw==" });
    });

    it("creates utf8 basic auth header for non-ascii credentials", () => {
        expect(createAuthHeaders({ username: "用户", password: "密钥" }))
            .toEqual({ Authorization: encodeBasicAuth("用户", "密钥") });
    });

    it("sends plain object body as json with accept header", async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true })));
        vi.stubGlobal("fetch", fetchMock);

        const result = await requestJson(
            { apiBase: "http://localhost:18082", username: "alice", password: "demo123" },
            "/api/demo",
            {
                method: "POST",
                body: { name: "跑鞋" }
            }
        );

        const [, options] = fetchMock.mock.calls[0];
        expect(options.headers.Accept).toBe("application/json");
        expect(options.headers["Content-Type"]).toBe("application/json");
        expect(options.body).toBe(JSON.stringify({ name: "跑鞋" }));
        expect(result).toEqual({ ok: true });
    });

    it("reads utf8 chunks without breaking multibyte text", async () => {
        const bytes = new TextEncoder().encode("你好，世界");
        const response = new Response(new ReadableStream({
            start(controller) {
                controller.enqueue(bytes.slice(0, 2));
                controller.enqueue(bytes.slice(2));
                controller.close();
            }
        }));
        const chunks = [];

        const result = await readTextStream(response, (chunk) => chunks.push(chunk));

        expect(result).toBe("你好，世界");
        expect(chunks.join("")).toBe("你好，世界");
    });
});
