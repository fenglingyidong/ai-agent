import { reactive } from "vue";
import * as defaultApi from "../api/chat.js";
import { ApiError, normalizeBaseUrl } from "../api/http.js";

const AUTH_STORAGE_KEY = "rag-agent-vue-auth";
const PREFERENCES_STORAGE_KEY = "rag-agent-vue-preferences";
const SESSION_PAGE_SIZE = 20;

export function createSessionId() {
    return `shopping-${crypto.randomUUID().slice(0, 8)}`;
}

export function turnsToMessages(turns) {
    return (turns || []).flatMap((turn) => {
        const status = String(turn.status || "COMPLETED").toLowerCase();
        return [
            {
                id: `${turn.id}-user`,
                role: "user",
                content: turn.userText || "",
                modelId: turn.modelId || "",
                status: "completed",
                createdAt: turn.createdAtEpochMillis || 0,
                mediaUrls: [],
                errorMessage: ""
            },
            {
                id: `${turn.id}-assistant`,
                role: "assistant",
                content: turn.assistantText || "",
                modelId: turn.modelId || "",
                status,
                errorMessage: turn.errorMessage || "",
                createdAt: turn.completedAtEpochMillis || turn.createdAtEpochMillis || 0,
                mediaUrls: []
            }
        ];
    });
}

function loadAuth(storage) {
    try {
        const saved = JSON.parse(storage.getItem(AUTH_STORAGE_KEY) || "null");
        return {
            apiBase: normalizeBaseUrl(saved?.apiBase),
            username: saved?.username || "",
            password: saved?.password || ""
        };
    }
    catch {
        return { apiBase: "http://localhost:18082", username: "", password: "" };
    }
}

function loadPreferences(storage) {
    try {
        const saved = JSON.parse(storage.getItem(PREFERENCES_STORAGE_KEY) || "null");
        return {
            selectedModelId: saved?.selectedModelId || "",
            webSearchEnabled: Boolean(saved?.webSearchEnabled)
        };
    }
    catch {
        return { selectedModelId: "", webSearchEnabled: false };
    }
}

function fallbackStorage() {
    return {
        getItem: () => null,
        setItem: () => {},
        removeItem: () => {}
    };
}

function resolveStorage(storage) {
    const target = storage ?? (() => {
        try {
            return globalThis.localStorage;
        }
        catch {
            return null;
        }
    })() ?? fallbackStorage();
    return {
        getItem: (key) => {
            try {
                return target.getItem(key);
            }
            catch {
                return null;
            }
        },
        setItem: (key, value) => {
            try {
                target.setItem(key, value);
            }
            catch {
                // Storage is optional for the store; ignore unavailable persistence.
            }
        },
        removeItem: (key) => {
            try {
                target.removeItem(key);
            }
            catch {
                // Storage is optional for the store; ignore unavailable persistence.
            }
        }
    };
}

function releaseMessageMedia(messages) {
    for (const message of messages || []) {
        for (const url of message.mediaUrls || []) {
            if (url.startsWith("blob:")) {
                URL.revokeObjectURL(url);
            }
        }
    }
}

function resolveErrorMessage(error, fallback, { networkFallback = false } = {}) {
    if (error instanceof ApiError) {
        return error.message || fallback;
    }
    if (networkFallback && error instanceof TypeError) {
        return "请检查后端地址和服务状态。";
    }
    return error?.message || fallback;
}

export function createAppStore(api = defaultApi, storage) {
    const safeStorage = resolveStorage(storage);
    const preferences = loadPreferences(safeStorage);
    const state = reactive({
        auth: loadAuth(safeStorage),
        isAuthenticated: false,
        isBootstrapping: false,
        isStreaming: false,
        error: "",
        models: [],
        defaultModel: "",
        selectedModelId: preferences.selectedModelId,
        webSearchEnabled: preferences.webSearchEnabled,
        sessions: [],
        sessionsOffset: 0,
        hasMoreSessions: true,
        isLoadingSessions: false,
        currentSessionId: "",
        messages: []
    });

    function persistPreferences() {
        safeStorage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify({
            selectedModelId: state.selectedModelId,
            webSearchEnabled: state.webSearchEnabled
        }));
    }

    function clearError() {
        state.error = "";
    }

    async function refreshSessions() {
        state.isLoadingSessions = true;
        try {
            const sessions = await api.listSessions(state.auth, { limit: SESSION_PAGE_SIZE, offset: 0 });
            state.sessions = sessions;
            state.sessionsOffset = sessions.length;
            state.hasMoreSessions = sessions.length === SESSION_PAGE_SIZE;
            return state.sessions;
        }
        finally {
            state.isLoadingSessions = false;
        }
    }

    async function loadMoreSessions() {
        if (state.isLoadingSessions || !state.hasMoreSessions) {
            return state.sessions;
        }
        state.isLoadingSessions = true;
        try {
            const sessions = await api.listSessions(state.auth, {
                limit: SESSION_PAGE_SIZE,
                offset: state.sessionsOffset
            });
            const existingIds = new Set(state.sessions.map((session) => session.sessionId));
            const nextSessions = sessions.filter((session) => !existingIds.has(session.sessionId));
            state.sessions.push(...nextSessions);
            state.sessionsOffset += sessions.length;
            state.hasMoreSessions = sessions.length === SESSION_PAGE_SIZE;
            return state.sessions;
        }
        catch (error) {
            state.error = resolveErrorMessage(error, "会话列表加载失败。", { networkFallback: true });
            throw error;
        }
        finally {
            state.isLoadingSessions = false;
        }
    }

    async function selectSession(sessionId) {
        if (state.isStreaming) {
            return;
        }
        clearError();
        try {
            const nextMessages = turnsToMessages(await api.loadTurns(state.auth, sessionId));
            releaseMessageMedia(state.messages);
            state.currentSessionId = sessionId;
            state.messages = nextMessages;
        }
        catch (error) {
            state.error = resolveErrorMessage(error, "加载会话失败。", { networkFallback: true });
            throw error;
        }
    }

    function newSession() {
        if (state.isStreaming) {
            return;
        }
        clearError();
        releaseMessageMedia(state.messages);
        state.currentSessionId = createSessionId();
        state.messages = [];
    }

    async function login(credentials) {
        state.error = "";
        state.isBootstrapping = true;
        try {
            state.auth = {
                apiBase: normalizeBaseUrl(credentials.apiBase),
                username: credentials.username.trim(),
                password: credentials.password
            };
            const catalog = await api.loadModels(state.auth);
            state.models = Array.isArray(catalog.items) ? catalog.items : [];
            state.defaultModel = catalog.defaultModel || "";
            const modelIds = new Set(state.models.map((model) => model.id));
            state.selectedModelId = modelIds.has(state.selectedModelId)
                ? state.selectedModelId
                : state.defaultModel || state.models[0]?.id || "";
            safeStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(state.auth));
            persistPreferences();
            state.isAuthenticated = true;
            await refreshSessions();
            if (state.sessions.length) {
                await selectSession(state.sessions[0].sessionId);
            }
            else {
                newSession();
            }
        }
        catch (error) {
            state.isAuthenticated = false;
            state.error = error instanceof ApiError && [401, 403].includes(error.status)
                ? "账号或密码无效。"
                : resolveErrorMessage(error, "无法连接后端服务。", { networkFallback: true });
            throw error;
        }
        finally {
            state.isBootstrapping = false;
        }
    }

    function resetAuthState({ preserveMessages = false, error = "" } = {}) {
        safeStorage.removeItem(AUTH_STORAGE_KEY);
        if (!preserveMessages) {
            releaseMessageMedia(state.messages);
        }
        state.auth = { apiBase: "http://localhost:18082", username: "", password: "" };
        state.isAuthenticated = false;
        state.sessions = [];
        state.sessionsOffset = 0;
        state.hasMoreSessions = true;
        state.isLoadingSessions = false;
        if (!preserveMessages) {
            state.messages = [];
        }
        state.currentSessionId = "";
        state.error = error;
    }

    function logout() {
        resetAuthState();
    }

    async function removeSession(sessionId) {
        if (state.isStreaming) {
            return;
        }
        clearError();
        try {
            await api.deleteSession(state.auth, sessionId);
            await refreshSessions();
            if (state.currentSessionId !== sessionId) {
                return;
            }
            if (state.sessions.length) {
                await selectSession(state.sessions[0].sessionId);
            }
            else {
                newSession();
            }
        }
        catch (error) {
            state.error = resolveErrorMessage(error, "删除会话失败。", { networkFallback: true });
            throw error;
        }
    }

    let abortController = null;

    async function sendMessage({ message, files, imageUrl }) {
        if (state.isStreaming) {
            return;
        }
        const effectiveMessage = message.trim() || "图片导购咨询";
        const userMessage = {
            id: crypto.randomUUID(),
            role: "user",
            content: effectiveMessage,
            modelId: state.selectedModelId,
            status: "completed",
            createdAt: Date.now(),
            mediaUrls: (files || []).map((file) => URL.createObjectURL(file))
                .concat(imageUrl ? [imageUrl] : [])
        };
        const assistantMessage = {
            id: crypto.randomUUID(),
            role: "assistant",
            content: "",
            modelId: state.selectedModelId,
            status: "processing",
            createdAt: Date.now(),
            mediaUrls: []
        };
        state.messages.push(userMessage, assistantMessage);
        state.isStreaming = true;
        state.error = "";
        abortController = new AbortController();
        try {
            await api.streamReactChat(
                state.auth,
                {
                    sessionId: state.currentSessionId,
                    message: effectiveMessage,
                    modelId: state.selectedModelId,
                    webSearchEnabled: state.webSearchEnabled,
                    files,
                    imageUrl
                },
                (chunk) => {
                    assistantMessage.content += chunk;
                },
                abortController.signal
            );
            assistantMessage.status = "completed";
            try {
                await refreshSessions();
            }
            catch (error) {
                state.error = resolveErrorMessage(error, "会话列表刷新失败。", { networkFallback: true });
            }
        }
        catch (error) {
            state.error = resolveErrorMessage(error, "请求失败。", { networkFallback: true });
            assistantMessage.status = error?.name === "AbortError" ? "partial" : "failed";
            assistantMessage.errorMessage = error?.name === "AbortError"
                ? "请求已停止。"
                : state.error;
            if (error instanceof ApiError && [401, 403].includes(error.status)) {
                resetAuthState({
                    preserveMessages: true,
                    error: "登录已失效，请重新登录。"
                });
            }
            throw error;
        }
        finally {
            state.isStreaming = false;
            abortController = null;
        }
    }

    function stopStreaming() {
        abortController?.abort();
    }

    async function bootstrap() {
        if (!state.auth.username || !state.auth.password) {
            return;
        }
        try {
            await login(state.auth);
        }
        catch {
            safeStorage.removeItem(AUTH_STORAGE_KEY);
        }
    }

    return {
        state,
        bootstrap,
        login,
        logout,
        newSession,
        selectSession,
        removeSession,
        sendMessage,
        stopStreaming,
        refreshSessions,
        loadMoreSessions,
        persistPreferences,
        clearError
    };
}

export const appStore = createAppStore();
