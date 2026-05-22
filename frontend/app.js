const STORAGE_KEY = "shopping-agent-workbench-state";

const defaultState = {
    settings: {
        apiBase: "http://localhost:18082",
        username: "alice",
        password: "demo123",
        sessionId: createSessionId(),
        modelId: "",
        webSearchEnabled: false
    },
    catalog: {
        defaultModel: "",
        items: []
    },
    messages: [],
    products: [],
    compareIds: [],
    cart: []
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
    modelSelect: document.getElementById("model-select"),
    webSearchToggle: document.getElementById("web-search-toggle"),
    promptChips: Array.from(document.querySelectorAll(".prompt-chip")),
    chatForm: document.getElementById("chat-form"),
    chatLog: document.getElementById("chat-log"),
    messageInput: document.getElementById("message-input"),
    imageInput: document.getElementById("image-input"),
    imageUrlInput: document.getElementById("image-url-input"),
    imagePreviewList: document.getElementById("image-preview-list"),
    sendButton: document.getElementById("send-button"),
    stopButton: document.getElementById("stop-button"),
    clearChat: document.getElementById("clear-chat"),
    productGrid: document.getElementById("product-grid"),
    compareView: document.getElementById("compare-view"),
    clearCompare: document.getElementById("clear-compare"),
    productImportForm: document.getElementById("product-import-form"),
    productId: document.getElementById("product-id"),
    productTitle: document.getElementById("product-title"),
    productBrand: document.getElementById("product-brand"),
    productCategory: document.getElementById("product-category"),
    productPrice: document.getElementById("product-price"),
    productStock: document.getElementById("product-stock"),
    productGuide: document.getElementById("product-guide"),
    productImportButton: document.getElementById("product-import-button"),
    cartOpen: document.getElementById("cart-open"),
    cartClose: document.getElementById("cart-close"),
    cartDrawer: document.getElementById("cart-drawer"),
    cartCount: document.getElementById("cart-count"),
    cartItems: document.getElementById("cart-items"),
    cartTotal: document.getElementById("cart-total"),
    cartCheck: document.getElementById("cart-check")
};

let activeAbortController = null;
let isBusy = false;
let selectedImages = [];

hydrateSettings();
renderModelOptions();
renderWebSearchToggle();
renderMessages();
renderProducts();
renderCompare();
renderCart();
bindEvents();
loadChatModels();

function bindEvents() {
    elements.settingsForm.addEventListener("submit", onSaveSettings);
    elements.refreshSession.addEventListener("click", onRefreshSession);
    elements.modelSelect.addEventListener("change", onModelChange);
    elements.webSearchToggle.addEventListener("click", onToggleWebSearch);
    elements.promptChips.forEach((button) => {
        button.addEventListener("click", () => {
            elements.messageInput.value = button.dataset.prompt ?? "";
            elements.messageInput.focus();
        });
    });
    elements.imageInput.addEventListener("change", onImagesSelected);
    elements.chatForm.addEventListener("submit", onSendMessage);
    elements.stopButton.addEventListener("click", onAbortRequest);
    elements.clearChat.addEventListener("click", onClearChat);
    elements.productGrid.addEventListener("click", onProductAction);
    elements.compareView.addEventListener("click", onCompareAction);
    elements.clearCompare.addEventListener("click", onClearCompare);
    elements.productImportForm.addEventListener("submit", onImportProduct);
    elements.cartOpen.addEventListener("click", () => setCartOpen(true));
    elements.cartClose.addEventListener("click", () => setCartOpen(false));
    elements.cartDrawer.addEventListener("click", (event) => {
        if (event.target.matches("[data-close-cart]")) {
            setCartOpen(false);
        }
    });
    elements.cartItems.addEventListener("click", onCartAction);
    elements.cartCheck.addEventListener("click", onCartCheck);
}

async function onSaveSettings(event) {
    event.preventDefault();
    syncSettingsFromInputs();
    announce("连接设置已保存，正在刷新模型列表。");
    await loadChatModels(true);
}

function onRefreshSession() {
    state.settings.sessionId = createSessionId();
    hydrateSettings();
    state.cart = [];
    persistState();
    renderCart();
    announce(`已创建新会话：${state.settings.sessionId}`);
}

function onModelChange() {
    state.settings.modelId = elements.modelSelect.value;
    persistState();
    announce(`回答模型已切换为：${lookupModelLabel(state.settings.modelId)}`);
}

function onToggleWebSearch() {
    state.settings.webSearchEnabled = !state.settings.webSearchEnabled;
    persistState();
    renderWebSearchToggle();
    announce(state.settings.webSearchEnabled ? "已启用 MCP 联网搜索。" : "已关闭联网搜索。");
}

function onImagesSelected() {
    const files = Array.from(elements.imageInput.files || []);
    const imageFiles = files.filter((file) => file.type.startsWith("image/"));
    selectedImages = selectedImages.concat(imageFiles).slice(0, 4);
    elements.imageInput.value = "";
    renderImagePreviews();
}

function renderImagePreviews() {
    if (!selectedImages.length) {
        elements.imagePreviewList.innerHTML = "";
        return;
    }
    elements.imagePreviewList.innerHTML = selectedImages.map((file, index) => {
        const url = URL.createObjectURL(file);
        return `
            <div class="image-preview">
                <img src="${url}" alt="${escapeHtml(file.name)}">
                <button type="button" data-remove-image="${index}" aria-label="移除图片">×</button>
            </div>
        `;
    }).join("");
    elements.imagePreviewList.querySelectorAll("[data-remove-image]").forEach((button) => {
        button.addEventListener("click", () => {
            selectedImages.splice(Number(button.dataset.removeImage), 1);
            renderImagePreviews();
        });
    });
}

async function onSendMessage(event) {
    event.preventDefault();
    if (isBusy) {
        return;
    }
    syncSettingsFromInputs();

    const message = elements.messageInput.value.trim();
    const imageUrl = elements.imageUrlInput.value.trim();
    const imagesToSend = [...selectedImages];
    const imageUrlToSend = imageUrl;
    if (!message && !selectedImages.length && !imageUrl) {
        announce("请输入导购需求，或上传一张商品图。", true);
        return;
    }

    const displayText = message || "请基于图片帮我推荐相似商品";
    const mediaUrls = await createDisplayMediaUrls(imagesToSend, imageUrlToSend);
    elements.messageInput.value = "";
    elements.imageUrlInput.value = "";
    selectedImages = [];
    renderImagePreviews();
    await sendAgentPrompt(displayText, {
        images: imagesToSend,
        imageUrl: imageUrlToSend,
        mediaUrls
    });
}

async function sendAgentPrompt(message, options = {}) {
    if (isBusy) {
        announce("Agent 正在处理上一条请求，请稍后再试。", true);
        return;
    }
    syncSettingsFromInputs();
    const displayText = message || "请基于图片帮我推荐相似商品";
    const selectedModelId = resolveSelectedModelId();
    const userMessage = createMessage("user", displayText, selectedModelId, false, options.mediaUrls || []);
    const assistantMessage = createMessage("assistant", "", selectedModelId, true, []);
    state.messages.push(userMessage, assistantMessage);
    persistState();
    renderMessages();

    setBusy(true);
    activeAbortController = new AbortController();
    announce(`正在调用 /api/react，模型：${lookupModelLabel(selectedModelId)}。`);

    try {
        await streamReactChat(displayText, selectedModelId, options.images || [], options.imageUrl || "", (chunk) => {
            assistantMessage.content += chunk;
            renderMessages();
        }, activeAbortController.signal);
        assistantMessage.pending = false;
        persistState();
        renderMessages();
        applyAssistantProductHints(assistantMessage.content);
        announce("导购响应完成。");
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

async function createDisplayMediaUrls(files, imageUrl) {
    const urls = files.map((file) => URL.createObjectURL(file));
    if (imageUrl) {
        urls.push(imageUrl);
    }
    return urls;
}

async function streamReactChat(message, modelId, images, imageUrl, onChunk, signal) {
    const formData = new FormData();
    formData.set("message", message);
    if (modelId) {
        formData.set("modelId", modelId);
    }
    formData.set("sessionId", state.settings.sessionId);
    formData.set("webSearchEnabled", String(Boolean(state.settings.webSearchEnabled)));
    images.forEach((file) => formData.append("image", file, file.name));
    if (imageUrl) {
        formData.append("imageUrl", imageUrl);
    }

    const response = await fetch(`${state.settings.apiBase}/api/react`, {
        method: "POST",
        headers: createAuthHeaders(),
        body: formData,
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

function onAbortRequest() {
    if (activeAbortController) {
        activeAbortController.abort();
    }
}

function onClearChat() {
    state.messages = [];
    persistState();
    renderMessages();
    announce("聊天区已清空。");
}

function onProductAction(event) {
    const button = event.target.closest("[data-product-action]");
    if (!button) {
        return;
    }
    const product = findProduct(button.dataset.productId);
    if (!product) {
        return;
    }
    const action = button.dataset.productAction;
    if (action === "compare") {
        toggleCompare(product.productId);
    }
    if (action === "cart") {
        addCartItem(product);
    }
    if (action === "ask") {
        elements.messageInput.value = `请详细说明 ${product.productId} ${product.title} 的适用人群、优缺点和替代款。`;
        elements.messageInput.focus();
    }
}

function onCompareAction(event) {
    const button = event.target.closest("[data-remove-compare]");
    if (!button) {
        return;
    }
    state.compareIds = state.compareIds.filter((id) => id !== button.dataset.removeCompare);
    persistState();
    renderProducts();
    renderCompare();
}

function onClearCompare() {
    state.compareIds = [];
    persistState();
    renderProducts();
    renderCompare();
    announce("对比视图已清空。");
}

async function onImportProduct(event) {
    event.preventDefault();
    syncSettingsFromInputs();
    const product = {
        productId: elements.productId.value.trim() || `P${Date.now().toString().slice(-4)}`,
        skuId: `SKU-${(elements.productId.value.trim() || Date.now()).toString().toUpperCase()}`,
        title: elements.productTitle.value.trim(),
        brand: elements.productBrand.value.trim(),
        category: elements.productCategory.value.trim(),
        price: Number(elements.productPrice.value || 0),
        stock: Number(elements.productStock.value || 0),
        guideText: elements.productGuide.value.trim(),
        description: elements.productGuide.value.trim(),
        reviewSummary: "前端快速导入商品，暂无评价摘要。",
        attributes: {}
    };

    if (!product.title) {
        announce("商品标题不能为空。", true);
        return;
    }

    elements.productImportButton.disabled = true;
    announce("正在导入商品知识。");
    try {
        const response = await fetch(`${state.settings.apiBase}/api/rag/documents/products/import`, {
            method: "POST",
            headers: {
                ...createAuthHeaders(),
                "Content-Type": "application/json"
            },
            body: JSON.stringify(product)
        });
        if (!response.ok) {
            throw await createHttpError(response);
        }
        state.products = dedupeProducts([normalizeImportedProduct(product), ...state.products]).slice(0, 12);
        if (!state.compareIds.length) {
            state.compareIds = state.products.slice(0, 2).map((item) => item.productId);
        }
        persistState();
        renderProducts();
        renderCompare();
        elements.productImportForm.reset();
        announce("商品知识导入成功。商品卡片为本地展示，商城查询由 Agent 工具执行。");
    }
    catch (error) {
        announce(humanizeError(error), true);
    }
    finally {
        elements.productImportButton.disabled = false;
    }
}

async function onCartAction(event) {
    const button = event.target.closest("[data-cart-action]");
    if (!button) {
        return;
    }
    const productId = button.dataset.productId;
    const item = state.cart.find((candidate) => candidate.productId === productId);
    if (!item) {
        return;
    }
    if (button.dataset.cartAction === "inc") {
        await sendAgentPrompt(`请把购物车中 ${item.skuId || item.title} 的数量调整为 ${item.quantity + 1} 件。`);
        return;
    }
    if (button.dataset.cartAction === "dec") {
        await sendAgentPrompt(`请把购物车中 ${item.skuId || item.title} 的数量调整为 ${Math.max(0, item.quantity - 1)} 件。`);
        return;
    }
    if (button.dataset.cartAction === "remove") {
        await sendAgentPrompt(`请从购物车移除 ${item.skuId || item.title}。`);
    }
}

async function onCartCheck() {
    setCartOpen(false);
    await sendAgentPrompt("查看我的购物车，帮我检查价格、库存、尺码风险，并给出下一步建议。");
}

async function addCartItem(product) {
    await sendAgentPrompt(`请把 ${product.skuId || product.productId} ${product.title} 加入购物车，数量 1 件。`);
}

function toggleCompare(productId) {
    if (state.compareIds.includes(productId)) {
        state.compareIds = state.compareIds.filter((id) => id !== productId);
    }
    else {
        state.compareIds = [...state.compareIds, productId].slice(-3);
    }
    persistState();
    renderProducts();
    renderCompare();
}

function applyAssistantProductHints(content) {
    const ids = new Set(content.match(/P\d{4}/g) || []);
    if (!ids.size) {
        return;
    }
    state.products = [
        ...state.products.filter((product) => ids.has(product.productId)),
        ...state.products.filter((product) => !ids.has(product.productId))
    ];
    persistState();
    renderProducts();
}

function renderMessages() {
    if (!state.messages.length) {
        elements.chatLog.innerHTML = `<div class="empty-state">可以输入预算、品类、尺码、品牌偏好，也可以上传商品图找相似款。</div>`;
        return;
    }
    elements.chatLog.innerHTML = state.messages.map((message) => {
        const roleLabel = message.role === "user" ? "你" : "导购Agent";
        const timeLabel = new Date(message.createdAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
        const pendingClass = message.pending ? " is-pending" : "";
        const mediaHtml = message.mediaUrls?.length
            ? `<div class="message-media">${message.mediaUrls.map((url) => `<img src="${escapeHtml(url)}" alt="上传图片">`).join("")}</div>`
            : "";
        return `
            <article class="message message-${message.role}${pendingClass}">
                <div class="message-header">
                    <span>${roleLabel} · ${escapeHtml(lookupModelLabel(message.modelId))}</span>
                    <span>${timeLabel}</span>
                </div>
                ${mediaHtml}
                <div class="message-bubble">${escapeHtml(message.content || "正在等待模型返回内容")}</div>
            </article>
        `;
    }).join("");
    elements.chatLog.scrollTop = elements.chatLog.scrollHeight;
}

function renderProducts() {
    if (!state.products.length) {
        elements.productGrid.innerHTML = `<div class="empty-state">暂时无法获取真实商城商品数据。请检查商城账号、密码和后端业务服务状态。</div>`;
        return;
    }
    elements.productGrid.innerHTML = state.products.map((product) => {
        const inCompare = state.compareIds.includes(product.productId);
        return `
            <article class="product-card">
                <div class="product-visual">${escapeHtml(product.category?.slice(0, 2) || "商品")}</div>
                <div class="product-main">
                    <h3 class="product-title">${escapeHtml(product.title)}</h3>
                    <div class="product-meta">${escapeHtml(product.productId)} · ${escapeHtml(product.brand)} · ${escapeHtml(product.category)}</div>
                    <div class="product-price">¥${formatNumber(product.price)} <span class="product-meta">库存 ${product.stock} · 评分 ${product.rating}</span></div>
                    <div class="product-desc">${escapeHtml(product.desc)}</div>
                    <div class="product-actions">
                        <button class="mini-btn${inCompare ? " is-active" : ""}" type="button" data-product-action="compare" data-product-id="${escapeHtml(product.productId)}">对比</button>
                        <button class="mini-btn" type="button" data-product-action="cart" data-product-id="${escapeHtml(product.productId)}">加购</button>
                        <button class="mini-btn" type="button" data-product-action="ask" data-product-id="${escapeHtml(product.productId)}">追问</button>
                    </div>
                </div>
            </article>
        `;
    }).join("");
}

function renderCompare() {
    const products = state.compareIds.map(findProduct).filter(Boolean);
    if (!products.length) {
        elements.compareView.innerHTML = `<div class="empty-state">点击商品卡片上的“对比”，最多保留 3 个商品。</div>`;
        return;
    }
    const rows = [
        ["商品", (product) => `${product.productId} ${product.title}`],
        ["品牌", (product) => product.brand],
        ["类目", (product) => product.category],
        ["价格", (product) => `¥${formatNumber(product.price)}`],
        ["库存", (product) => `${product.stock}`],
        ["评分", (product) => `${product.rating}`],
        ["标签", (product) => product.tags.join(" / ")],
        ["导购依据", (product) => product.desc]
    ];
    elements.compareView.innerHTML = `
        <table class="compare-table">
            <thead>
                <tr>
                    <th>维度</th>
                    ${products.map((product) => `<th>${escapeHtml(product.productId)} <button class="mini-btn" type="button" data-remove-compare="${escapeHtml(product.productId)}">移除</button></th>`).join("")}
                </tr>
            </thead>
            <tbody>
                ${rows.map(([label, reader]) => `
                    <tr>
                        <th>${escapeHtml(label)}</th>
                        ${products.map((product) => `<td>${escapeHtml(reader(product))}</td>`).join("")}
                    </tr>
                `).join("")}
            </tbody>
        </table>
    `;
}

function renderCart() {
    const resolvedItems = state.cart.map((item) => {
        const product = findProduct(item.productId) || item;
        return { ...item, product };
    }).filter((item) => item.product);
    const totalQuantity = resolvedItems.reduce((sum, item) => sum + item.quantity, 0);
    const total = resolvedItems.reduce((sum, item) => sum + item.product.price * item.quantity, 0);
    elements.cartCount.textContent = String(totalQuantity);
    elements.cartTotal.textContent = `¥${formatNumber(total)}`;
    if (!resolvedItems.length) {
        elements.cartItems.innerHTML = `<div class="empty-state">购物车为空。可以从商品卡片加购，或让 Agent 帮你推荐。</div>`;
        return;
    }
    elements.cartItems.innerHTML = resolvedItems.map((item) => `
        <article class="cart-item">
            <div class="cart-item-head">
                <strong>${escapeHtml(item.product.title)}</strong>
                <button class="mini-btn" type="button" data-cart-action="remove" data-product-id="${escapeHtml(item.productId)}">移除</button>
            </div>
            <div class="cart-meta">${escapeHtml(item.product.skuId)} · ¥${formatNumber(item.product.price)}</div>
            <div class="qty-row">
                <button type="button" data-cart-action="dec" data-product-id="${escapeHtml(item.productId)}">-</button>
                <span>${item.quantity}</span>
                <button type="button" data-cart-action="inc" data-product-id="${escapeHtml(item.productId)}">+</button>
            </div>
        </article>
    `).join("");
}

function normalizeImportedProduct(product) {
    return {
        productId: String(product.productId || product.skuId || ""),
        skuId: String(product.skuId || ""),
        title: product.title || "未命名商品",
        brand: product.brand || "未设置",
        category: product.category || "未设置",
        price: Number(product.price || 0),
        stock: Number(product.stock || 0),
        rating: 4.5,
        tags: [product.category || "商品知识"],
        desc: "来自本地导入的 RAG 商品知识。"
    };
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
        const ids = new Set(state.catalog.items.map((item) => item.id));
        if (!ids.has(state.settings.modelId)) {
            state.settings.modelId = state.catalog.defaultModel || state.catalog.items[0]?.id || "";
        }
        renderModelOptions();
        persistState();
        if (showSuccessMessage) {
            announce(`模型列表已刷新，共 ${state.catalog.items.length} 个。`);
        }
    }
    catch (error) {
        renderModelOptions();
        if (showSuccessMessage) {
            announce(humanizeError(error), true);
        }
    }
}

function renderModelOptions() {
    const items = state.catalog.items || [];
    if (!items.length) {
        elements.modelSelect.innerHTML = `<option value="">使用后端默认模型</option>`;
        return;
    }
    elements.modelSelect.innerHTML = items.map((item) => {
        const label = `${escapeHtml(item.label || item.id)} (${escapeHtml(item.model)})`;
        return `<option value="${escapeHtml(item.id)}">${label}</option>`;
    }).join("");
    elements.modelSelect.value = resolveSelectedModelId();
}

function renderWebSearchToggle() {
    elements.webSearchToggle.classList.toggle("is-active", Boolean(state.settings.webSearchEnabled));
    elements.webSearchToggle.setAttribute("aria-pressed", String(Boolean(state.settings.webSearchEnabled)));
    elements.webSearchToggle.textContent = state.settings.webSearchEnabled ? "联网搜索：开" : "联网搜索";
}

function setCartOpen(open) {
    elements.cartDrawer.classList.toggle("is-open", open);
    elements.cartDrawer.setAttribute("aria-hidden", String(!open));
}

function setBusy(nextBusy) {
    isBusy = nextBusy;
    elements.sendButton.disabled = nextBusy;
    elements.stopButton.disabled = !nextBusy;
}

function hydrateSettings() {
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
    hydrateSettings();
    persistState();
}

function createMessage(role, content, modelId, pending = false, mediaUrls = []) {
    return {
        id: crypto.randomUUID(),
        role,
        content,
        modelId,
        pending,
        mediaUrls,
        createdAt: new Date().toISOString()
    };
}

function findProduct(productId) {
    return state.products.find((product) => product.productId === productId);
}

function dedupeProducts(products) {
    const seen = new Set();
    return products.filter((product) => {
        if (seen.has(product.productId)) {
            return false;
        }
        seen.add(product.productId);
        return true;
    });
}

function resolveSelectedModelId() {
    const ids = new Set((state.catalog.items || []).map((item) => item.id));
    if (ids.has(state.settings.modelId)) {
        return state.settings.modelId;
    }
    if (ids.has(state.catalog.defaultModel)) {
        return state.catalog.defaultModel;
    }
    return state.catalog.items[0]?.id || "";
}

function lookupModelLabel(modelId) {
    const item = (state.catalog.items || []).find((candidate) => candidate.id === modelId);
    return item ? item.label || item.id : modelId || "默认模型";
}

function createAuthHeaders() {
    const headers = {
        Authorization: `Basic ${encodeBase64(`${state.settings.username || ""}:${state.settings.password || ""}`)}`
    };
    return headers;
}

function announce(message, isError = false) {
    elements.statusBanner.textContent = message;
    elements.statusBanner.style.color = isError ? "var(--danger)" : "var(--muted)";
    elements.statusBanner.style.borderColor = isError ? "rgba(180, 35, 24, 0.28)" : "var(--border)";
}

function createSessionId() {
    return `shopping-${Math.random().toString(36).slice(2, 10)}`;
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
            settings: { ...defaultState.settings, ...(saved?.settings || {}) },
            catalog: {
                ...defaultState.catalog,
                ...(saved?.catalog || {}),
                items: Array.isArray(saved?.catalog?.items) ? saved.catalog.items : []
            },
            messages: Array.isArray(saved?.messages) ? saved.messages : [],
            products: [],
            compareIds: [],
            cart: []
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

function formatNumber(value) {
    return Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}
