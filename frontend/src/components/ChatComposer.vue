<script setup>
import { computed, onBeforeUnmount, ref } from "vue";
import { ImagePlus, Send, Square, X } from "lucide-vue-next";
import { ElMessage } from "element-plus";

const props = defineProps({ store: { type: Object, required: true } });
const message = ref("");
const imageUrl = ref("");
const files = ref([]);
const fileInput = ref(null);
const canSend = computed(() => Boolean(message.value.trim() || imageUrl.value.trim() || files.value.length));
const imageCount = computed(() => files.value.length + (imageUrl.value.trim() ? 1 : 0));

function chooseFiles(event) {
  const maxFileCount = 4 - (imageUrl.value.trim() ? 1 : 0);
  const selected = Array.from(event.target.files || [])
    .filter((file) => file.type.startsWith("image/"))
    .map((file) => ({ file, url: URL.createObjectURL(file) }));
  if (files.value.length + selected.length > maxFileCount) {
    ElMessage.warning("最多上传 4 张图片。");
  }
  const combined = [...files.value, ...selected];
  combined.slice(maxFileCount).forEach((item) => URL.revokeObjectURL(item.url));
  files.value = combined.slice(0, maxFileCount);
  event.target.value = "";
}

function openFilePicker() {
  fileInput.value?.click();
}

function removeFile(index) {
  const [removed] = files.value.splice(index, 1);
  if (removed) {
    URL.revokeObjectURL(removed.url);
  }
}

function clearFiles() {
  files.value.forEach((item) => URL.revokeObjectURL(item.url));
  files.value = [];
}

function isSupportedImageUrl(value) {
  if (!value) {
    return true;
  }
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

async function send() {
  if (!canSend.value || props.store.state.isStreaming) {
    return;
  }
  const normalizedUrl = imageUrl.value.trim();
  if (!isSupportedImageUrl(normalizedUrl)) {
    ElMessage.warning("图片 URL 只支持有效的 http 或 https 地址。");
    return;
  }
  if (files.value.length + (normalizedUrl ? 1 : 0) > 4) {
    ElMessage.warning("最多上传 4 张图片。");
    return;
  }
  const payload = {
    message: message.value,
    files: files.value.map((item) => item.file),
    imageUrl: normalizedUrl
  };
  try {
    await props.store.sendMessage(payload);
    message.value = "";
    imageUrl.value = "";
    clearFiles();
  } catch (error) {
    ElMessage.error(error?.message || "发送失败，请稍后重试。");
  }
}

onBeforeUnmount(() => {
  clearFiles();
});
</script>

<template>
  <form class="composer" @submit.prevent="send">
    <div v-if="files.length" class="composer-previews">
      <div v-for="(item, index) in files" :key="`${item.file.name}-${item.file.lastModified}`" class="composer-preview">
        <img :src="item.url" :alt="item.file.name">
        <el-tooltip content="移除图片">
          <button type="button" aria-label="移除图片" @click="removeFile(index)"><X :size="14" /></button>
        </el-tooltip>
      </div>
    </div>
    <el-input v-model="imageUrl" placeholder="可选：图片 URL" :disabled="store.state.isStreaming" />
    <div class="composer-row">
      <input ref="fileInput" class="sr-only" type="file" accept="image/*" multiple @change="chooseFiles">
      <el-tooltip content="上传图片">
        <el-button circle native-type="button" aria-label="上传图片" :disabled="store.state.isStreaming || imageCount >= 4" @click="openFilePicker">
          <ImagePlus :size="18" />
        </el-button>
      </el-tooltip>
      <el-input
        v-model="message"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 5 }"
        resize="none"
        placeholder="描述预算、品类、尺码、颜色或使用场景"
        :disabled="store.state.isStreaming"
        @keydown.ctrl.enter.prevent="send"
      />
      <el-tooltip :content="store.state.isStreaming ? '停止生成' : '发送'">
        <el-button v-if="store.state.isStreaming" circle native-type="button" aria-label="停止生成" @click="store.stopStreaming()">
          <Square :size="18" />
        </el-button>
        <el-button v-else type="primary" circle native-type="submit" aria-label="发送" :disabled="!canSend">
          <Send :size="18" />
        </el-button>
      </el-tooltip>
    </div>
  </form>
</template>
