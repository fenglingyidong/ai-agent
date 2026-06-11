<script setup>
import { Plus, Trash2 } from "lucide-vue-next";
import { ElMessageBox } from "element-plus";

const props = defineProps({ store: { type: Object, required: true } });

async function remove(session) {
  try {
    await ElMessageBox.confirm(`删除会话“${session.title || session.sessionId}”？`, "删除会话", {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      type: "warning"
    });
  }
  catch {
    return;
  }
  await props.store.removeSession(session.sessionId);
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }) : "";
}
</script>

<template>
  <aside class="session-sidebar">
    <div class="sidebar-head">
      <strong>电商智能导购</strong>
      <el-tooltip content="新会话">
        <el-button circle aria-label="新会话" :disabled="store.state.isStreaming" @click="store.newSession()">
          <Plus :size="17" />
        </el-button>
      </el-tooltip>
    </div>
    <el-scrollbar class="session-scroll">
      <el-empty v-if="!store.state.sessions.length" description="暂无历史会话" :image-size="64" />
      <div
        v-for="session in store.state.sessions"
        :key="session.sessionId"
        class="session-item"
        :class="{ active: session.sessionId === store.state.currentSessionId }"
      >
        <button
          type="button"
          class="session-select"
          :disabled="store.state.isStreaming"
          @click="store.selectSession(session.sessionId)"
        >
          <strong>{{ session.title || "新会话" }}</strong>
          <small>{{ session.latestUserText || "开始对话" }}</small>
          <small>{{ formatTime(session.updatedAt) }}</small>
        </button>
        <el-tooltip content="删除会话">
          <el-button text circle aria-label="删除会话" :disabled="store.state.isStreaming" @click="remove(session)">
            <Trash2 :size="15" />
          </el-button>
        </el-tooltip>
      </div>
    </el-scrollbar>
    <div class="sidebar-user">{{ store.state.auth.username }}</div>
  </aside>
</template>
