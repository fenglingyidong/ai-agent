const STORAGE_KEY = "rag-agent-console-state";

const defaultState = {
    settings: {
        apiBase: "http://localhost:8080",
        username: "demo",
        password: "demo123",
        sessionId: createSessionId(),
        modelId: "",
        webSearchEnabled: false
    },
    catalog: {
        defaultModel: "",
        items: []
    },
    mode: "react",
    messages: [],
    imports: []
};

const state = loadState();

const elements = {
    statusBanner: document.getElementById("status-banner"),
    settingsForm: document.getElementById("settings-form"),
    apiBase: document.getElementById("api-base"),
    username: document.getElementById("username"),
    password: document.getElementById("password"),
    sessionId: document.getElementById("session-id"),
    refreshSession: document.getElementById("refresh-session"),
    modeButtons: Array.from(document.querySelectorAll(".mode-btn")),
    modeDescription: document.getElementById("mode-description"),
    modelHint: document.getElementById("model-hint"),
    modelSelect: document.getElementById("model-select"),
    webSearchToggle: document.getElementById("web-search-toggle"),
    promptChips: Array.from(document.querySelectorAll(".chip")),
    chatForm: document.getElementById("chat-form"),
    chatLog: document.getElementById("chat-log"),
    messageInput: document.getElementById("message-input"),
    sendButton: document.getElementById("send-button"),
    stopButton: document.getElementById("stop-button"),
    clearChat: document.getElementById("clear-chat"),
    importForm: document.getElementById("import-form"),
    sourceId: document.getElementById("source-id"),
    docTitle: document.getElementById("doc-title"),
    docContent: document.getElementById("doc-content"),
    clearImport: document.getElementById("clear-import"),
    clearHistory: document.getElementById("clear-history"),
    importHistory: document.getElementById("import-history")
};

let activeAbortController = null;
let isBusy = false;

hydrateFormFields();
renderModelOptions();
renderMode();
renderWebSearchToggle();
renderMessages();
renderImports();
bindEvents();
loadChatModels();

function bindEvents() {
    elements.settingsForm.addEventListener("submit", onSaveSettings);
    elements.refreshSession.addEventListener("click", onRefreshSession);
    elements.modeButtons.forEach((button) => {
        button.addEventListener("click", () => setMode(button.dataset.mode));
    });
    elements.modelSelect.addEventListener("change", onModelChange);
    elements.webSearchToggle.addEventListener("click", onToggleWebSearch);
    elements.promptChips.forEach((button) => {
        button.addEventListener("click", () => {
            elements.messageInput.value = button.dataset.prompt ?? "";
            elements.messageInput.focus();
        });
    });
    elements.chatForm.addEventListener("submit", onSendMessage);
    elements.stopButton.addEventListener("click", onAbortRequest);
    elements.clearChat.addEventListener("click", onClearChat);
    elements.importForm.addEventListener("submit", onImportDocument);
    elements.clearImport.addEventListener("click", onClearImportForm);
    elements.clearHistory.addEventListener("click", onClearImportHistory);
}

async function onSaveSettings(event) {
    event.preventDefault();
    syncSettingsFromInputs();
    announce("连接设置已保存，正在刷新后端模型列表...");
    await loadChatModels(true);
}

function onRefreshSession() {
    state.settings.sessionId = createSessionId();
    hydrateFormFields();
    persistState();
    announce(`已生成新的 Session ID：${state.settings.sessionId}`);
}

function onModelChange() {
    state.settings.modelId = elements.modelSelect.value;
    persistState();
    announce(`当前回答模型已切换为：${lookupModelLabel(state.settings.modelId)}`);
}

function onToggleWebSearch() {
    state.settings.webSearchEnabled = !state.settings.webSearchEnabled;
    persistState();
    renderWebSearchToggle();
    announce(state.settings.webSearchEnabled ? "已启用联网搜索工具。" : "已关闭联网搜索工具。");
}

function setMode(mode) {
    state.mode = mode === "chat" ? "chat" : "react";
    persistState();
    renderMode();
}

function renderMode() {
    elements.modeButtons.forEach((button) => {
        button.classList.toggle("is-active", button.dataset.mode === state.mode);
    });

    if (state.mode === "react") {
        elements.modeDescription.textContent = "当前模式会调用 /api/react。所选模型用于最终用户可见回答；开启“联网搜索”后，后端会按需暴露 MCP 联网搜索工具。";
        elements.messageInput.placeholder = "输入给 ReAct Agent 的问题，例如让它分步骤分析一个任务。";
        elements.webSearchToggle.disabled = false;
    }
    else {
        elements.modeDescription.textContent = "当前模式会调用 /api/chat；如果后端未提供该接口，请切回 ReAct Agent。联网搜索按钮仅在 ReAct 模式生效。";
        elements.messageInput.placeholder = "输入问题，前端会使用 Basic Auth 调用后端接口。";
        elements.webSearchToggle.disabled = true;
    }

    renderWebSearchToggle();
}

function renderWebSearchToggle() {
    const enabled = Boolean(state.settings.webSearchEnabled);
    const active = enabled && state.mode === "react";
    elements.webSearchToggle.classList.toggle("is-active", active);
    elements.webSearchToggle.setAttribute("aria-pressed", String(active));
    elements.webSearchToggle.textContent = enabled ? "联网搜索：开" : "联网搜索";
}

async function onSendMessage(event) {
    event.preventDefault();
    if (isBusy) {
        return;
    }

    const message = elements.messageInput.value.trim();
    if (!message) {
        announce("请先输入一条消息。", true);
        return;
    }

    syncSettingsFromInputs();
    const selectedModelId = resolveSelectedModelId();
    const userMessage = createMessage("user", message, state.mode, selectedModelId);
    const assistantMessage = createMessage("assistant", "", state.mode, selectedModelId, true);
    state.messages.push(userMessage, assistantMessage);
    persistState();
    renderMessages();

    elements.messageInput.value = "";
    setBusy(true);
    announce(`正在请求 ${state.mode === "react" ? "/api/react" : "/api/chat"}，回答模型：${lookupModelLabel(selectedModelId)}。`);

    activeAbortController = new AbortController();
    try {
        const text = await streamChatResponse(message, selectedModelId, (chunk) => {
            assistantMessage.content += chunk;
            renderMessages();
        }, activeAbortController.signal);

        if (!assistantMessage.content && text) {
            assistantMessage.content = text;
        }
        assistantMessage.pending = false;
        persistState();
        renderMessages();
        announce("响应完成。");
    }
    catch (error) {
        assistantMessage.pending = false;
        assistantMessage.content = assistantMessage.content || humanizeError(error);
        persistState();
        renderMessages();
        announce(assistantMessage.content, true);
    }
    finally {
        activeAbortController = null;
        setBusy(false);
    }
}

function onAbortRequest() {
    if (activeAbortController) {
        activeAbortController.abort();
    }
}

function onClearChat() {
    state.messages = [];
    persistState();
    renderMessages();
    announce("对话记录已清空。");
}

function onClearImportForm() {
    elements.importForm.reset();
    announce("文档表单已清空。");
}

function onClearImportHistory() {
    state.imports = [];
    persistState();
    renderImports();
    announce("导入记录已清空。");
}

async function onImportDocument(event) {
    event.preventDefault();
    syncSettingsFromInputs();

    const payload = {
        sourceId: elements.sourceId.value.trim() || null,
        title: elements.docTitle.value.trim() || null,
        content: elements.docContent.value.trim()
    };

    if (!payload.content) {
        announce("文档内容不能为空。", true);
        return;
    }

    const button = document.getElementById("import-button");
    button.disabled = true;
    announce("正在导入文档到父子分块索引...");

    try {
        const response = await fetch(`${state.settings.apiBase}/api/rag/documents/import`, {
            method: "POST",
            headers: {
                ...createAuthHeaders(),
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw await createHttpError(response);
        }

        const result = await response.json();
        state.imports.unshift({
            ...result,
            createdAt: new Date().toISOString()
        });
        state.imports = state.imports.slice(0, 8);
        persistState();
        renderImports();
        elements.importForm.reset();
        announce(`导入成功，已生成 ${result.parentCount} 个父文档块。`);
    }
    catch (error) {
        announce(humanizeError(error), true);
    }
    finally {
        button.disabled = false;
    }
}

async function loadChatModels(showSuccessMessage = false) {
    syncSettingsFromInputs();
    try {
        const response = await fetch(`${state.settings.apiBase}/api/models/chat`, {
            method: "GET",
            headers: createAuthHeaders()
        });

        if (!response.ok) {
            throw await createHttpError(response);
        }

        const result = await response.json();
        state.catalog = {
            defaultModel: result.defaultModel || "",
            items: Array.isArray(result.items) ? result.items : []
        };

        const availableIds = new Set(state.catalog.items.map((item) => item.id));
        if (!availableIds.has(state.settings.modelId)) {
            state.settings.modelId = state.catalog.defaultModel || state.catalog.items[0]?.id || "";
        }

        renderModelOptions();
        persistState();

        if (showSuccessMessage) {
            announce(`模型列表已刷新，共 ${state.catalog.items.length} 个可选模型。`);
        }
        else {
            elements.modelHint.textContent = "模型列表已从后端加载。无效选择会自动回退到默认模型。";
        }
    }
    catch (error) {
        renderModelOptions();
        elements.modelHint.textContent = "暂时无法加载模型列表，将回退到后端默认模型。";
        if (showSuccessMessage) {
            announce(humanizeError(error), true);
        }
    }
}

async function streamChatResponse(message, modelId, onChunk, signal) {
    const endpoint = new URL(state.mode === "react" ? "/api/react" : "/api/chat", `${state.settings.apiBase}/`);
    endpoint.searchParams.set("message", message);

    if (state.mode === "chat") {
        endpoint.searchParams.set("sessionId", state.settings.sessionId);
    }
    if (modelId) {
        endpoint.searchParams.set("modelId", modelId);
    }
    if (state.mode === "react" && state.settings.webSearchEnabled) {
        endpoint.searchParams.set("webSearchEnabled", "true");
    }

    const response = await fetch(endpoint, {
        method: "GET",
        headers: createAuthHeaders(),
        signal
    });

    if (!response.ok) {
        throw await createHttpError(response);
    }

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
            aggregate += decoder.decode();
            break;
        }
        const chunk = decoder.decode(value, { stream: true });
        aggregate += chunk;
        onChunk(chunk);
    }

    return aggregate;
}

function renderModelOptions() {
    const items = state.catalog.items || [];
    if (!items.length) {
        elements.modelSelect.innerHTML = `<option value="">使用后端默认模型</option>`;
        elements.modelSelect.value = "";
        return;
    }

    elements.modelSelect.innerHTML = items.map((item) => {
        const label = `${escapeHtml(item.label || item.id)} (${escapeHtml(item.model)})`;
        return `<option value="${escapeHtml(item.id)}">${label}</option>`;
    }).join("");

    elements.modelSelect.value = resolveSelectedModelId();
}

function renderMessages() {
    if (!state.messages.length) {
        elements.chatLog.innerHTML = `<div class="empty-state">这里还没有消息。先导入一份文档，或者直接提一个问题试试。</div>`;
        return;
    }

    elements.chatLog.innerHTML = state.messages.map((message) => {
        const roleLabel = message.role === "user" ? "你" : "Agent";
        const timeLabel = new Date(message.createdAt).toLocaleTimeString("zh-CN", {
            hour: "2-digit",
            minute: "2-digit"
        });
        const modelLabel = message.modelId ? ` · ${escapeHtml(lookupModelLabel(message.modelId))}` : "";
        const pendingClass = message.pending ? " is-pending" : "";
        return `
            <article class="message message-${message.role}${pendingClass}">
                <div class="message-header">
                    <span>${roleLabel} · ${message.mode === "react" ? "ReAct" : "RAG"}${modelLabel}</span>
                    <span>${timeLabel}</span>
                </div>
                <div class="message-bubble">${escapeHtml(message.content || "正在等待模型返回内容")}</div>
            </article>
        `;
    }).join("");

    elements.chatLog.scrollTop = elements.chatLog.scrollHeight;
}

function renderImports() {
    if (!state.imports.length) {
        elements.importHistory.innerHTML = `<div class="empty-state">导入成功后，返回的 parentIds 会显示在这里。</div>`;
        return;
    }

    elements.importHistory.innerHTML = state.imports.map((item) => {
        const createdAt = new Date(item.createdAt).toLocaleString("zh-CN");
        const tags = (item.parentIds || []).map((id) => `<span class="tag">${escapeHtml(id)}</span>`).join("");
        return `
            <article class="result-item">
                <h4>${escapeHtml(item.title || item.sourceId || "未命名文档")}</h4>
                <div class="history-meta">
                    <span>Source ID: ${escapeHtml(item.sourceId || "未提供")}</span>
                    <span>父文档数: ${item.parentCount ?? 0}</span>
                    <span>${createdAt}</span>
                </div>
                <div class="tag-list">${tags || '<span class="tag">没有返回 parentIds</span>'}</div>
            </article>
        `;
    }).join("");
}

function hydrateFormFields() {
    elements.apiBase.value = state.settings.apiBase;
    elements.username.value = state.settings.username;
    elements.password.value = state.settings.password;
    elements.sessionId.value = state.settings.sessionId;
}

function syncSettingsFromInputs() {
    state.settings.apiBase = normalizeBaseUrl(elements.apiBase.value);
    state.settings.username = elements.username.value.trim();
    state.settings.password = elements.password.value;
    state.settings.sessionId = elements.sessionId.value.trim() || createSessionId();
    state.settings.modelId = elements.modelSelect.value || state.settings.modelId || "";
    hydrateFormFields();
    persistState();
}

function setBusy(nextBusy) {
    isBusy = nextBusy;
    elements.sendButton.disabled = nextBusy;
    elements.stopButton.disabled = !nextBusy;
}

function announce(message, isError = false) {
    elements.statusBanner.textContent = message;
    elements.statusBanner.style.background = isError
        ? "linear-gradient(135deg, rgba(255, 111, 97, 0.18), rgba(255, 255, 255, 0.05))"
        : "linear-gradient(135deg, rgba(255, 143, 90, 0.16), rgba(111, 211, 192, 0.12))";
}

function createAuthHeaders() {
    return {
        Authorization: `Basic ${encodeBase64(`${state.settings.username || ""}:${state.settings.password || ""}`)}`
    };
}

function createMessage(role, content, mode, modelId, pending = false) {
    return {
        id: crypto.randomUUID(),
        role,
        content,
        mode,
        modelId,
        pending,
        createdAt: new Date().toISOString()
    };
}

function resolveSelectedModelId() {
    const availableIds = new Set((state.catalog.items || []).map((item) => item.id));
    if (availableIds.has(state.settings.modelId)) {
        return state.settings.modelId;
    }
    if (availableIds.has(state.catalog.defaultModel)) {
        return state.catalog.defaultModel;
    }
    return state.catalog.items[0]?.id || "";
}

function lookupModelLabel(modelId) {
    const item = (state.catalog.items || []).find((candidate) => candidate.id === modelId);
    return item ? item.label || item.id : modelId || "后端默认模型";
}

function createSessionId() {
    return `session-${Math.random().toString(36).slice(2, 10)}`;
}

function normalizeBaseUrl(value) {
    return (value || defaultState.settings.apiBase).trim().replace(/\/+$/, "");
}

function loadState() {
    try {
        const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
        return {
            ...defaultState,
            ...saved,
            settings: {
                ...defaultState.settings,
                ...(saved?.settings || {})
            },
            catalog: {
                ...defaultState.catalog,
                ...(saved?.catalog || {}),
                items: Array.isArray(saved?.catalog?.items) ? saved.catalog.items : []
            },
            messages: Array.isArray(saved?.messages) ? saved.messages : [],
            imports: Array.isArray(saved?.imports) ? saved.imports : []
        };
    }
    catch {
        return { ...defaultState };
    }
}

function persistState() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

async function createHttpError(response) {
    const rawText = await response.text();
    const text = rawText.trim();
    return new Error(text || `请求失败，HTTP ${response.status}`);
}

function humanizeError(error) {
    if (error?.name === "AbortError") {
        return "请求已停止。";
    }
    if (error instanceof Error && error.message) {
        return error.message;
    }
    return "请求失败，请检查后端服务、账号密码和跨域配置。";
}

function encodeBase64(value) {
    const bytes = new TextEncoder().encode(value);
    let binary = "";
    bytes.forEach((byte) => {
        binary += String.fromCharCode(byte);
    });
    return btoa(binary);
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}
