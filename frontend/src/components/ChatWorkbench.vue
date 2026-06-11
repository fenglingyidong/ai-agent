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
      <MessageList :messages="store.state.messages" />
      <ChatComposer :store="store" />
    </section>
  </main>
</template>
