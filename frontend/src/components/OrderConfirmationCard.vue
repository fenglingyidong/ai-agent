<script setup>
const props = defineProps({
  confirmation: { type: Object, required: true },
  disabled: { type: Boolean, default: false }
});

const emit = defineEmits(["confirm", "cancel"]);

function money(value) {
  return `¥${value || "0.00"}`;
}
</script>

<template>
  <section class="order-confirmation-card">
    <header class="order-confirmation-head">
      <strong>确认下单</strong>
      <span>{{ confirmation.status === "pending" ? "待确认" : confirmation.status }}</span>
    </header>

    <div v-if="confirmation.empty" class="order-empty">购物车为空。</div>
    <div v-else class="order-items">
      <div v-for="item in confirmation.items" :key="item.skuId" class="order-item">
        <div>
          <strong>{{ item.name }}</strong>
          <small>SKU {{ item.skuId }}</small>
        </div>
        <span>{{ money(item.unitPrice) }} × {{ item.quantity }}</span>
        <b>{{ money(item.subtotal) }}</b>
      </div>
    </div>

    <footer class="order-confirmation-foot">
      <div>
        <small>合计</small>
        <strong>{{ money(confirmation.totalAmount) }}</strong>
      </div>
      <div class="order-actions" v-if="confirmation.status === 'pending' && !confirmation.empty">
        <el-button :disabled="disabled" @click="emit('cancel')">取消</el-button>
        <el-button type="primary" :disabled="disabled" @click="emit('confirm')">确认</el-button>
      </div>
    </footer>
  </section>
</template>
