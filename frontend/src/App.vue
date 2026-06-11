<script setup>
import { onMounted } from "vue";
import LoginView from "./components/LoginView.vue";
import ChatWorkbench from "./components/ChatWorkbench.vue";
import { appStore } from "./stores/appStore.js";

onMounted(() => appStore.bootstrap());
</script>

<template>
  <LoginView v-if="!appStore.state.isAuthenticated" :store="appStore" />
  <ChatWorkbench v-else :store="appStore" />
</template>
