<script setup>
import { reactive, ref } from "vue";
import { LogIn } from "lucide-vue-next";

const props = defineProps({ store: { type: Object, required: true } });
const form = reactive({ ...props.store.state.auth });
const hasSubmitted = ref(false);

async function submit() {
  hasSubmitted.value = true;
  try {
    await props.store.login(form);
  }
  catch {
    // store 已设置可见错误。
  }
}
</script>

<template>
  <main class="login-view">
    <el-form class="login-panel" label-position="top" @submit.prevent="submit">
      <div class="login-brand">电商智能导购</div>
      <h1>登录工作台</h1>
      <el-form-item label="后端地址">
        <el-input v-model="form.apiBase" placeholder="http://localhost:18082" />
      </el-form-item>
      <el-form-item label="用户名">
        <el-input v-model="form.username" autocomplete="username" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-alert v-if="hasSubmitted && store.state.error" :title="store.state.error" type="error" :closable="false" />
      <el-button type="primary" native-type="submit" :loading="store.state.isBootstrapping">
        <LogIn :size="16" />
        登录
      </el-button>
    </el-form>
  </main>
</template>
