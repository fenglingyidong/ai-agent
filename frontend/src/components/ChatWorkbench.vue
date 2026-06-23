<script setup>
import SessionSidebar from "./SessionSidebar.vue";
import ChatHeader from "./ChatHeader.vue";
import MessageList from "./MessageList.vue";
import ChatComposer from "./ChatComposer.vue";

defineProps({ store: { type: Object, required: true } });
</script>

<template>
  <main class="workbench">
    <SessionSidebar :store="store" />
    <section class="chat-workspace">
      <ChatHeader :store="store" />
      <el-alert
        v-if="store.state.error"
        class="workspace-alert"
        :title="store.state.error"
        type="error"
        :closable="false"
      />
      <MessageList
        :messages="store.state.messages"
        :action-disabled="store.state.isStreaming"
        @confirm-checkout="store.confirmCheckout"
        @cancel-checkout="store.cancelCheckout"
      />
      <ChatComposer :store="store" />
    </section>
  </main>
</template>
