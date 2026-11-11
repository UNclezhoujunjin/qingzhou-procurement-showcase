<script setup lang="ts">
import { ref } from 'vue'
import { post, type Product } from '../api'

const props=defineProps<{product:Product;canDraft:boolean}>()
const emit=defineEmits<{draft:[product:Product,reason:string];busy:[value:boolean]}>()
interface Result {
  product:Product
  facts:{stock:number;minimum:number;gap:number;inbound:number;pending:number;approved:number;suggested:number;unit:string}
  answer:string
  draftReason:string
  status:keyof typeof statusLabels
}
const question=ref('还需要补多少？已有多少待到货？')
const result=ref<Result|null>(null)
const busy=ref(false)
const error=ref('')
const statusLabels={
  ready:'',
  unconfigured:'模型尚未配置。下方显示系统计算结果，可继续准备补货草稿。',
  busy:'模型暂时达到调用上限。下方仍可查看系统计算结果。',
  authorization:'模型服务拒绝授权，请管理员检查 API Key 和模型权限。',
  billing:'模型服务拒绝计费请求，请管理员检查账户额度和模型价格。',
  provider_rejected:'模型服务拒绝本次请求，请管理员检查接口地址、模型名称和服务日志。',
  timeout:'模型回复超时，可以稍后再试。',
  connection:'未能完成与模型服务的通信，请管理员检查服务器网络和服务日志。',
  invalid_reply:'模型回复为空、被截断或格式异常，可以稍后再试。',
  unavailable:'模型暂时无法回复。下方仍可查看系统计算结果。'
}

async function ask(){
  if(busy.value||!question.value.trim())return
  busy.value=true
  emit('busy',true)
  result.value=null
  error.value=''
  try{
    result.value=await post<Result>('/inventory/assistant',{productId:props.product.id,question:question.value.trim()})
  }catch(e){
    error.value=(e as Error).message
  }finally{
    busy.value=false
    emit('busy',false)
  }
}
</script>

<template>
  <div class="modal-body">
    <div class="review-summary"><strong>{{product.name}}</strong><p>{{product.sku}} · 查询当前账号可见的库存与补货进展</p></div>
    <form @submit.prevent="ask">
      <label class="field-block">你想了解什么<textarea v-model="question" rows="2" maxlength="300" required :disabled="busy"/></label>
      <p class="small-copy muted">启用模型时，问题和库存汇总会发送给配置的模型服务。请勿输入账号、密码或个人资料。</p>
      <button class="btn primary" :disabled="busy||!question.trim()">{{busy?'正在查询…':'查询补货建议'}}</button>
    </form>
    <p v-if="error" class="form-error" role="alert">{{error}}</p>
    <div v-if="result" class="operation-history" aria-live="polite">
      <h3>系统计算结果</h3>
      <dl class="detail-grid">
        <div><dt>当前库存</dt><dd>{{result.facts.stock}} {{result.facts.unit}}</dd></div>
        <div><dt>安全库存</dt><dd>{{result.facts.minimum}} {{result.facts.unit}}</dd></div>
        <div><dt>库存缺口</dt><dd>{{result.facts.gap}} {{result.facts.unit}}</dd></div>
        <div><dt>可见待到货</dt><dd>{{result.facts.inbound}} {{result.facts.unit}}</dd></div>
        <div><dt>待审批 / 待下单</dt><dd>{{result.facts.pending}} / {{result.facts.approved}} {{result.facts.unit}}</dd></div>
        <div><dt>参考补货</dt><dd>{{result.facts.suggested}} {{result.facts.unit}}</dd></div>
      </dl>
      <p v-if="result.answer"><strong>AI 解读</strong><br>{{result.answer}}</p>
      <p v-else class="form-hint">{{statusLabels[result.status]}}</p>
      <p v-if="result.draftReason"><strong>建议用途</strong><br>{{result.draftReason}}</p>
      <button v-if="canDraft&&result.product.enabled&&result.facts.suggested>0" class="btn" @click="emit('draft',result.product,result.draftReason)">带入补货表单</button>
    </div>
  </div>
</template>
