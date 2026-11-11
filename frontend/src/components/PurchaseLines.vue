<script setup lang="ts">
import {computed,ref} from 'vue'
const query=ref('')
const visible=computed(()=>props.products.filter(p=>(p.enabled||lines.value.some(l=>l.productId===p.id))&&(!query.value||[p.name,p.sku].some(v=>v.toLowerCase().includes(query.value.toLowerCase()))||lines.value.some(l=>l.productId===p.id))))
import {Plus,Trash2} from 'lucide-vue-next'
import type {Product,RequestItemInput} from '../api'
const props=defineProps<{products:Product[]}>()
const lines=defineModel<RequestItemInput[]>({required:true})
const total=computed(()=>lines.value.reduce((sum,line)=>sum+(props.products.find(p=>p.id===line.productId)?.price||0)*Number(line.quantity),0))
const money=(n:number)=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(n)
const canAdd=computed(()=>lines.value.length<100&&visible.value.some(p=>p.enabled&&!lines.value.some(l=>l.productId===p.id)))
function add(){const product=visible.value.find(p=>p.enabled&&!lines.value.some(l=>l.productId===p.id));if(product)lines.value.push({productId:product.id,quantity:1})}
</script>
<template><div class="line-editor"><label>搜索商品名称或 SKU<input v-model="query" placeholder="输入名称或 SKU 筛选商品"/></label><div class="section-caption">采购明细<span>{{lines.length}} / 100 项</span></div><div v-for="(line,index) in lines" :key="index" class="purchase-line"><label>商品 {{index+1}}<select v-model="line.productId" :aria-label="'采购商品 '+(index+1)" required><option v-for="p in visible" :key="p.id" :value="p.id" :disabled="!p.enabled||lines.some((l,i)=>i!==index&&l.productId===p.id)">{{p.name}}{{p.enabled?'':'（已停用）'}} · {{p.sku}} · {{money(Number(p.price))}} / {{p.unit}}</option></select></label><label>数量<input v-model.number="line.quantity" :aria-label="'采购数量 '+(index+1)" type="number" min="1" max="100000" required/></label><button type="button" class="icon-button" :disabled="lines.length===1" :aria-label="'删除商品 '+(index+1)" @click="lines.splice(index,1)"><Trash2 :size="17"/></button></div><button class="btn" type="button" :disabled="!canAdd" @click="add"><Plus :size="15"/>添加商品</button><p v-if="!products.some(p=>p.enabled)" class="form-hint">暂无启用的商品，请联系管理员或采购人员添加或恢复商品后再提交。可以先保存草稿。</p><p v-else-if="query&&!canAdd" class="small-copy muted">没有更多可添加的匹配商品，可以修改搜索条件。</p><div class="purchase-total"><span>预计申请金额</span><strong>{{money(total)}}</strong></div><p class="muted small-copy">审批通过后，按供应商拆分采购订单；每项商品保留提交时的单价。</p></div></template>
