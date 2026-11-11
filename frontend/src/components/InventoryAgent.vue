<script setup lang="ts">
import { nextTick,ref } from 'vue'
import { post,requestTypeLabel,type Product,type RequestType } from '../api'

type Tool='inspect'|'shortages'|'tasks'|'guide'
type TaskKind='approval'|'purchase'|'receipt'
type Candidate={id:number;name:string;sku:string}
type Shortage=Candidate&{stock:number;min_stock:number;unit:string}
type Task={id:string;number:string;label:string;request_type?:RequestType}
type Result=
 | {tool:'inspect';product?:Product;facts?:{stock:number;gap:number;inbound:number;pending:number;approved:number;suggested:number;unit:string};candidates?:Candidate[];message?:string}
 | {tool:'shortages';products:Shortage[]}
 | {tool:'tasks';tasks:Partial<Record<TaskKind,Task[]>>}
 | {tool:'guide';title:string;steps:string[]}
type Answer={status:string;results:Result[]}
const props=defineProps<{canDraft:boolean}>()
const emit=defineEmits<{draft:[product:Product,reason:string];task:[kind:TaskKind,id:string];busy:[value:boolean]}>()
const text=ref('')
const turns=ref<{question:string;answer?:Answer;error?:string}[]>([])
const selected=ref<Product|null>(null)
const previousQuestion=ref('')
const busy=ref(false)
const latest=ref<HTMLElement|null>(null)
const titles:Record<TaskKind,string>={approval:'待审批',purchase:'待下单',receipt:'待收货'}
const failures:Record<string,string>={
 unconfigured:'模型尚未配置。你可以使用下面的快捷查询。',
 authorization:'模型服务拒绝授权，请检查 API Key 或模型权限。',
 billing:'模型服务拒绝计费请求，请检查账户额度和模型价格。',
 busy:'模型调用暂时受限，请稍后重试。',
 timeout:'模型理解问题超时，请稍后重试，或使用快捷查询。',
 provider_rejected:'模型服务拒绝了请求，请检查模型和接口配置。',
 invalid_reply:'模型回复格式异常，请换一种问法，或使用快捷查询。'
}

async function ask(question:string,tool?:Tool,productId=selected.value?.id){
 const content=question.trim()
 if(!content||busy.value)return
 busy.value=true
 emit('busy',true)
 const entry={question:content} as (typeof turns.value)[number]
 turns.value.push(entry)
 const index=turns.value.length-1
 text.value=''
 try{
  const answer=await post<Answer>('/inventory/agent',{question:content,tool,productId,previousQuestion:previousQuestion.value})
  turns.value[index].answer=answer
  if(!tool&&answer.status==='ready'&&answer.results.length)previousQuestion.value=content
  for(const result of answer.results)if(result.tool==='inspect'&&result.product)selected.value=result.product
 }catch{
  turns.value[index].error='未能完成本次查询，请稍后再试。'
 }finally{
  busy.value=false
  emit('busy',false)
  await nextTick()
  latest.value?.scrollIntoView({block:'nearest'})
 }
}

function selectProduct(product:Candidate){
 void ask('查看 '+product.name,'inspect',product.id)
}
</script>

<template>
 <div class="modal-body agent-body">
  <p class="muted">可以问「轻舟是做什么的？」「我该去哪里审批？」或「A4 办公纸还需要补多少？」。</p>
  <div class="row-actions">
   <button class="btn row-action" :disabled="busy" @click="ask('查看低库存商品','shortages')">低库存商品</button>
   <button class="btn row-action" :disabled="busy" @click="ask('查看我的待办','tasks')">我的待办</button>
   <button class="btn row-action" :disabled="busy" @click="ask('轻舟是做什么的？','guide')">轻舟使用指南</button>
  </div>
  <p v-if="selected" class="small-copy muted">当前商品：{{selected.name}} · {{selected.sku}}</p>
  <div class="agent-turns" aria-live="polite">
   <section v-for="(turn,index) in turns" :key="index" class="agent-turn">
    <strong>{{turn.question}}</strong>
    <p v-if="turn.error" class="form-error">{{turn.error}}</p>
    <p v-else-if="!turn.answer" class="muted">正在理解问题或读取数据…</p>
    <template v-else>
     <p v-if="!turn.answer.results.length" class="form-hint">{{turn.answer.status==='ready'?'暂未识别出可用操作。可以提供商品名称或 SKU，或问当前有哪些待办。':failures[turn.answer.status]||'暂时无法连接模型，可以使用快捷查询。'}}</p>
     <div v-for="(result,n) in turn.answer.results" :key="n" class="history-entry">
      <template v-if="result.tool==='inspect'">
       <p v-if="result.message">{{result.message}}</p>
       <template v-if="result.candidates">
        <p>找到多件商品，请选择要查询的一件。</p>
        <div class="row-actions"><button v-for="product in result.candidates" :key="product.id" class="btn row-action" :disabled="busy" @click="selectProduct(product)">{{product.name}} · {{product.sku}}</button></div>
       </template>
       <template v-if="result.product&&result.facts">
        <h3>{{result.product.name}} · {{result.product.sku}}</h3>
        <p>当前库存 {{result.facts.stock}} {{result.facts.unit}}，距安全库存差 {{result.facts.gap}} {{result.facts.unit}}。</p>
        <p>可见待到货 {{result.facts.inbound}}，待审批 {{result.facts.pending}}，待下单 {{result.facts.approved}} {{result.facts.unit}}。</p>
        <p><b>参考补货 {{result.facts.suggested}} {{result.facts.unit}}</b>。建议量只扣减可见待到货。</p>
        <button v-if="props.canDraft&&result.product.enabled&&result.facts.suggested>0" class="btn" :disabled="busy" @click="emit('draft',result.product,'')">带入补货表单</button>
       </template>
      </template>
      <template v-else-if="result.tool==='shortages'">
       <h3>库存缺口最大的商品 · 最多 5 件</h3>
       <p v-if="!result.products.length">暂无低库存商品。</p>
       <p v-for="product in result.products" :key="product.id"><button class="text-button" :disabled="busy" @click="selectProduct(product)">{{product.name}} · {{product.sku}}</button> · 缺 {{product.min_stock-product.stock}} {{product.unit}}</p>
      </template>
      <template v-else-if="result.tool==='guide'">
       <h3>{{result.title}}</h3>
       <p v-for="step in result.steps" :key="step">{{step}}</p>
      </template>
      <template v-else>
       <h3>当前账号待办 · 每类最多 5 笔</h3>
       <p v-if="!Object.values(result.tasks).some(tasks=>tasks?.length)">暂无待办。</p>
       <div v-for="(tasks,kind) in result.tasks" :key="kind">
        <template v-if="tasks?.length"><b>{{titles[kind]}}</b>
         <p v-for="task in tasks" :key="task.id"><button class="text-button" :disabled="busy" @click="emit('task',kind,task.id)">{{task.number}} · {{requestTypeLabel(task.request_type)}} · {{task.label}}</button></p>
        </template>
       </div>
      </template>
     </div>
    </template>
   </section>
   <div ref="latest"/>
  </div>
  <form @submit.prevent="ask(text)">
   <label class="field-block">给轻舟智能体发消息<textarea v-model="text" rows="2" maxlength="300" :disabled="busy" placeholder="例如：查一下办公纸库存和在途数量"/></label>
   <p class="small-copy muted">自然语言问题会发送给配置的模型服务。请勿输入密码或个人资料。</p>
   <button class="btn primary" :disabled="busy||!text.trim()">{{busy?'查询中…':'发送'}}</button>
  </form>
 </div>
</template>
