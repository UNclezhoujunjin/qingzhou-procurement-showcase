<script setup lang="ts">
import type {RequestLine} from '../api'
defineProps<{lines:RequestLine[]}>()
const money=(value:number)=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(value)
</script>
<template><div class="table-scroll"><table><thead><tr><th>采购商品</th><th>数量</th><th>单价</th><th>行金额</th></tr></thead><tbody><tr v-for="line in lines" :key="line.id"><td><strong>{{line.product_name}}</strong><small>{{line.sku}} · {{line.supplier_name}}</small></td><td>{{line.quantity}} {{line.unit}}</td><td>{{money(line.unit_price)}}</td><td>{{money(line.amount)}}</td></tr></tbody></table></div></template>
