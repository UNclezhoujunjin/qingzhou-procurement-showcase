<script setup lang="ts">
import {computed,ref,watch,onMounted} from 'vue'
import {api,post,ApiError,type Supplier} from '../api'
import OperationRecovery from './OperationRecovery.vue'
import {parseProductCsv} from '../productCsv'
const text=ref(''),busy=ref(false),error=ref(''),message=ref(''),checked=ref(false),uncertain=ref(false),key=ref(crypto.randomUUID()),suppliers=ref<Supplier[]>([])
const errors=ref<{row:number;messages:string[]}[]>([]),total=ref(0)
const template='SKU,商品名称,分类,单位,单价,安全库存,供应商ID\nTEST-001,测试商品,办公用品,件,10.00,5,请替换为供应商ID\n'
const templateUrl=computed(()=> 'data:text/csv;charset=utf-8,'+encodeURIComponent('\uFEFF'+template))
onMounted(async()=>{try{suppliers.value=await api('/suppliers')}catch(e){error.value=(e as Error).message}})
watch(text,()=>{checked.value=false;errors.value=[];if(text.value)message.value='';key.value=crypto.randomUUID()})
function parse(){return parseProductCsv(text.value)}
async function fileChanged(e:Event){const f=(e.target as HTMLInputElement).files?.[0];if(!f)return;try{if(f.size>256*1024)throw new Error('CSV 文件不能超过 256 KB');text.value=await f.text()}catch(e){error.value=(e as Error).message}}
async function preview(){if(busy.value||uncertain.value)return;busy.value=true;error.value='';message.value='';checked.value=false;errors.value=[];try{const r=await post<{valid:boolean;total:number;errors:typeof errors.value}>('/products/import/preview',parse());checked.value=r.valid;total.value=r.total;errors.value=r.errors;if(r.valid)message.value='预检查通过，共 '+r.total+' 行。请确认后导入。'}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function commit(){if(busy.value||!checked.value)return;busy.value=true;error.value='';try{const r=await post<{imported:number}>('/products/import',parse(),key.value);confirmed(r.imported)}catch(e){error.value=(e as Error).message;if(e instanceof ApiError)uncertain.value=e.uncertain}finally{busy.value=false}}
function confirmed(n=total.value){uncertain.value=false;checked.value=false;text.value='';message.value='已导入 '+n+' 项商品，可在商品档案查看。'}
</script>
<template><section class="panel feature-panel"><div class="section-caption">商品批量导入<a class="btn" :href="templateUrl" download="商品导入模板.csv">下载 CSV 模板</a></div><p class="form-intro">先预检查，全部通过后再导入。每批最多 200 行，仅新增，不覆盖已有 SKU；库存默认 0。</p><p class="small-copy muted">供应商：<span v-for="s in suppliers" :key="s.id">{{s.id}} — {{s.name}}； </span><span v-if="!suppliers.length">请先创建供应商。</span></p><p v-if="error" class="form-error">{{error}}</p><p v-if="message" class="inline-success">{{message}}</p><OperationRecovery v-if="uncertain" :request-key="key" :busy="busy" @committed="confirmed()" @retry="commit"/><fieldset :disabled="busy||uncertain" style="border:0;padding:0;margin:0"><label class="field-block">选择 UTF-8 CSV 文件<input type="file" accept=".csv,text/csv" @change="fileChanged"/></label><label class="field-block">CSV 内容（也可直接粘贴）<textarea v-model="text" rows="10" placeholder="请粘贴包含模板表头的 CSV 内容"/></label><button class="btn" @click="preview">预检查</button><button class="btn primary" :disabled="!checked" @click="commit">确认导入 {{total}} 行</button></fieldset><div v-for="e in errors" :key="e.row" class="form-error">第 {{e.row}} 行：{{e.messages.join('；')}}</div></section></template>
