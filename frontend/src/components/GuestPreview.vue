<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowLeft, ArrowRight, Check, Layers, RotateCcw } from 'lucide-vue-next'
const emit=defineEmits<{close:[]}>()
const step=ref(0), quantity=ref<number|string>(12)
const stages=[
 {name:'提交申请',role:'申请人',description:'办公纸库存低于安全线。填写补货数量，提交给示例审批人林主管。',action:'提交给林主管'},
 {name:'审批申请',role:'林主管 · 审批负责人',description:'核对申请数量和金额。审批通过后交给采购专员。',action:'通过审批'},
 {name:'采购下单',role:'陈采购 · 采购专员',description:'根据已批准的申请向示例供应商创建采购订单。',action:'生成采购订单'},
 {name:'收货入库',role:'周仓管 · 仓库管理员',description:'确认实际收到的货物，登记入库后库存增加。',action:'确认全量收货'}
]
const current=computed(()=>stages[step.value])
const valid=computed(()=>typeof quantity.value==='number'&&Number.isInteger(quantity.value)&&quantity.value>=1&&quantity.value<=100)
const stock=computed(()=>8+(step.value===4?Number(quantity.value):0))
function advance(){if(valid.value&&step.value<4)step.value++}
function reset(){step.value=0;quantity.value=12}
</script>

<template>
 <main class="guest-preview">
  <header class="guest-header"><span><Layers :size="20"/> 轻舟 · 公开演示</span><button class="btn" @click="emit('close')"><ArrowLeft :size="16"/>返回登录</button></header>
  <section class="guest-intro"><span class="guest-pill">无需账号 · 示例数据</span><h1>一笔采购，如何从需求走到入库？</h1><p>按步骤体验申请、审批、下单与收货。操作只保存在当前页面，刷新后重置。</p></section>
  <ol class="guest-steps" aria-label="演示进度"><li v-for="(stage,index) in stages" :key="stage.name" :aria-current="step===index?'step':undefined" :class="{current:step===index,done:step>index}"><b>{{step>index?'✓':index+1}}</b>{{stage.name}}</li></ol>
  <div class="guest-grid">
   <section class="panel guest-panel" aria-live="polite">
    <template v-if="current"><span class="guest-pill">{{current.role}}</span><h2>{{current.name}}</h2><p>{{current.description}}</p>
     <form @submit.prevent="advance"><label v-if="step===0" class="field-block">申请数量（包）<input v-model.number="quantity" type="number" min="1" max="100" step="1" required/></label>
      <button class="btn primary" :disabled="!valid">{{current.action}}<ArrowRight :size="16"/></button>
     </form>
    </template>
    <template v-else><span class="guest-pill"><Check :size="15"/>流程完成</span><h2>办公纸已入库</h2><p>本次入库 {{quantity}} 包，库存从 8 包增加到 {{stock}} 包。</p><button class="btn" @click="reset"><RotateCcw :size="16"/>重新体验</button></template>
   </section>
   <aside class="panel guest-panel"><span class="mini-label">示例商品 · OF-001</span><h2>A4 办公纸</h2><dl class="guest-facts"><div><dt>当前库存</dt><dd>{{stock}} 包</dd></div><div><dt>安全库存</dt><dd>20 包</dd></div><div><dt>参考单价</dt><dd>¥25.00 / 包</dd></div><div><dt>申请金额</dt><dd>{{valid?'¥'+(Number(quantity)*25).toFixed(2):'—'}}</dd></div></dl></aside>
  </div>
 </main>
</template>
