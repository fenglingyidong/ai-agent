<script setup>
import { nextTick, ref, watch } from "vue";

const props = defineProps({
  messages: { type: Array, required: true }
});
const viewport = ref(null);

watch(() => props.messages.map((message) => message.content).join(""), async () => {
  await nextTick();
  if (viewport.value) {
    viewport.value.scrollTop = viewport.value.scrollHeight;
  }
});

function time(value) {
  return value ? new Date(value).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }) : "";
}
</script>

<template>
  <section ref="viewport" class="message-list" aria-live="polite">
    <el-empty v-if="!messages.length" description="描述预算、品类和偏好，开始一次导购对话" />
    <article v-for="message in messages" :key="message.id" class="message" :class="`message-${message.role}`">
      <div class="message-meta">
        <span>{{ message.role === "user" ? "你" : "导购 Agent" }}</span>
        <span>{{ time(message.createdAt) }}</span>
      </div>
      <div v-if="message.mediaUrls?.length" class="message-media">
        <el-image v-for="url in message.mediaUrls" :key="url" :src="url" fit="cover" :preview-src-list="message.mediaUrls" />
      </div>
      <div class="message-bubble">{{ message.content || (message.status === "processing" ? "正在思考..." : "") }}</div>
      <small v-if="message.errorMessage" class="message-error">{{ message.errorMessage }}</small>
    </article>
  </section>
</template>
