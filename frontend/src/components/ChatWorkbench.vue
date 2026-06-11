<script setup>
import SessionSidebar from "./SessionSidebar.vue";
import ChatHeader from "./ChatHeader.vue";

defineProps({ store: { type: Object, required: true } });
</script>

<template>
  <main class="workbench">
    <SessionSidebar :store="store" />
    <section class="chat-workspace">
      <ChatHeader :store="store" />
      <div class="chat-placeholder">消息区将在下一任务接入</div>
    </section>
  </main>
</template>
