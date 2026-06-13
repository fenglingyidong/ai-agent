export class ApiError extends Error {
    constructor(status, message) {
        super(message || `请求失败，HTTP ${status}`);
        this.name = "ApiError";
        this.status = status;
    }
}

export function normalizeBaseUrl(value) {
    return (value || "http://localhost:18082").trim().replace(/\/+$/, "");
}

export function createAuthHeaders(auth) {
    const value = `${auth?.username || ""}:${auth?.password || ""}`;
    const bytes = new TextEncoder().encode(value);
    let binary = "";
    bytes.forEach((byte) => {
        binary += String.fromCharCode(byte);
    });
    return { Authorization: `Basic ${btoa(binary)}` };
}

export async function fetchApi(auth, path, options = {}) {
    const headers = {
        ...createAuthHeaders(auth),
        ...(options.headers || {})
    };
    const response = await fetch(`${normalizeBaseUrl(auth?.apiBase)}${path}`, {
        ...options,
        headers
    });
    if (!response.ok) {
        const text = (await response.text()).trim();
        throw new ApiError(response.status, text);
    }
    return response;
}

export async function requestJson(auth, path, options = {}) {
    const headers = {
        Accept: "application/json",
        ...(options.headers || {})
    };
    let body = options.body;
    if (isPlainObjectBody(body)) {
        body = JSON.stringify(body);
        headers["Content-Type"] = headers["Content-Type"] || "application/json";
    }
    const response = await fetchApi(auth, path, {
        ...options,
        headers,
        body
    });
    return response.json();
}

function isPlainObjectBody(value) {
    if (!value || typeof value !== "object") {
        return false;
    }
    return Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null;
}

export async function readTextStream(response, onChunk) {
    if (!response.body) {
        const text = await response.text();
        onChunk(text);
        return text;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let aggregate = "";
    while (true) {
        const { done, value } = await reader.read();
        if (done) {
            const finalChunk = decoder.decode();
            aggregate += finalChunk;
            if (finalChunk) {
                onChunk(finalChunk);
            }
            return aggregate;
        }
        const chunk = decoder.decode(value, { stream: true });
        aggregate += chunk;
        if (chunk) {
            onChunk(chunk);
        }
    }
}
