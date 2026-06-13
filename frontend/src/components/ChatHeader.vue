<script setup>
import { LogOut } from "lucide-vue-next";

defineProps({ store: { type: Object, required: true } });
</script>

<template>
  <header class="chat-header">
    <div>
      <strong>{{ store.state.sessions.find((item) => item.sessionId === store.state.currentSessionId)?.title || "新会话" }}</strong>
      <small>{{ store.state.currentSessionId }}</small>
    </div>
    <div class="header-controls">
      <el-select
        v-model="store.state.selectedModelId"
        :disabled="store.state.isStreaming"
        placeholder="默认模型"
        @change="store.persistPreferences()"
      >
        <el-option v-for="model in store.state.models" :key="model.id" :label="model.label || model.id" :value="model.id" />
      </el-select>
      <el-switch
        v-model="store.state.webSearchEnabled"
        :disabled="store.state.isStreaming"
        inline-prompt
        active-text="联网"
        inactive-text="离线"
        @change="store.persistPreferences()"
      />
      <el-tooltip content="退出登录">
        <el-button circle aria-label="退出登录" :disabled="store.state.isStreaming" @click="store.logout()">
          <LogOut :size="17" />
        </el-button>
      </el-tooltip>
    </div>
  </header>
</template>
