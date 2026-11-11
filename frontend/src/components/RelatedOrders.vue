<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'

const props = defineProps<{requestId:string; canOpen:boolean; statuses:Record<string,string>}>()
const emit = defineEmits<{open:[id:string]}>()
interface RelatedOrder {
  id:string
  number:string
  supplier_name:string
  fulfillment_status:string
  lifecycle_state:string
  lines:{product_name:string;unit:string;quantity:number;net_received:number;remaining:number}[]
}
const orders = ref<RelatedOrder[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value=true
  error.value=''
  try {
    orders.value=await api<RelatedOrder[]>(`/requests/${props.requestId}/orders`)
  } catch (e) {
    error.value=(e as Error).message
  } finally {
    loading.value=false
  }
}
onMounted(load)
</script>

<template>
  <section class="operation-history">
    <h3>关联采购订单</h3>
    <p v-if="loading" role="status">正在读取订单进度…</p>
    <p v-else-if="error" class="form-error" role="alert">{{error}} <button class="btn" @click="load">重试</button></p>
    <p v-else-if="!orders.length" class="muted">这笔申请尚未生成订单。</p>
    <article v-for="order in orders" :key="order.id" class="history-entry">
      <strong>{{order.number}} · {{order.supplier_name}}</strong>
      <p>{{statuses[order.fulfillment_status]}}</p>
      <p v-for="(line,index) in order.lines" :key="index">
        {{line.product_name}} · 订购 {{line.quantity}} {{line.unit}} · 净收 {{line.net_received}} {{line.unit}} ·
        {{order.lifecycle_state==='ACTIVE'?'待收':'未交付'}} {{line.remaining}} {{line.unit}}
      </p>
      <button v-if="canOpen" class="text-button" @click="emit('open',order.id)">查看订单和收退货记录</button>
    </article>
  </section>
</template>
