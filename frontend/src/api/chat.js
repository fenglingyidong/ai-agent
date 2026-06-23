import { fetchApi, readTextStream, requestJson } from "./http.js";

export async function loadModels(auth) {
    return requestJson(auth, "/api/models/chat");
}

export async function listSessions(auth, { limit = 20, offset = 0 } = {}) {
    const params = new URLSearchParams({
        limit: String(limit),
        offset: String(offset)
    });
    const result = await requestJson(auth, `/api/conversations?${params.toString()}`);
    return Array.isArray(result.items) ? result.items : [];
}

export async function loadTurns(auth, sessionId) {
    const result = await requestJson(
        auth,
        `/api/conversations/${encodeURIComponent(sessionId)}/turns?limit=50`
    );
    return Array.isArray(result.items) ? result.items : [];
}

export async function deleteSession(auth, sessionId) {
    await fetchApi(auth, `/api/conversations/${encodeURIComponent(sessionId)}`, {
        method: "DELETE"
    });
}

export async function loadMockCheckoutConfirmation(auth, sessionId) {
    const params = new URLSearchParams({ sessionId });
    return requestJson(auth, `/api/mock/checkout/confirmation?${params.toString()}`);
}

export async function confirmMockCheckout(auth, sessionId) {
    return requestJson(auth, "/api/mock/checkout/confirm", {
        method: "POST",
        body: { sessionId }
    });
}

export async function cancelMockCheckout(auth, sessionId) {
    return requestJson(auth, "/api/mock/checkout/cancel", {
        method: "POST",
        body: { sessionId }
    });
}

export async function streamReactChat(auth, payload, onChunk, signal) {
    const formData = new FormData();
    formData.set("sessionId", payload.sessionId);
    formData.set("message", payload.message);
    formData.set("webSearchEnabled", String(Boolean(payload.webSearchEnabled)));
    if (payload.modelId) {
        formData.set("modelId", payload.modelId);
    }
    for (const file of payload.files || []) {
        formData.append("image", file, file.name);
    }
    if (payload.imageUrl) {
        formData.append("imageUrl", payload.imageUrl);
    }
    const response = await fetchApi(auth, "/api/react", {
        method: "POST",
        body: formData,
        signal
    });
    return readTextStream(response, onChunk);
}
