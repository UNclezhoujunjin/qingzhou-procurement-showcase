<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {api,post,requestTypeLabel} from '../api'
export interface Draft {id:string;payload:string;version:number;updated_at:string}
const emit=defineEmits<{open:[draft:Draft]}>()
const items=ref<Draft[]>([]),error=ref(''),busy=ref(false)
function title(d:Draft){try{const data=JSON.parse(d.payload);return requestTypeLabel(data.requestType)+' · '+(data.reason||'未填写采购用途')}catch{return '采购草稿'}}
async function load(){if(busy.value)return;busy.value=true;error.value='';try{items.value=await api('/drafts')}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
const confirming=ref('')
async function discard(draft:Draft){
 if(busy.value)return
 busy.value=true
 error.value=''
 try{
  await post('/drafts/'+draft.id+'/discard',{version:draft.version})
  items.value=items.value.filter(item=>item.id!==draft.id)
  confirming.value=''
 }catch(e){error.value=(e as Error).message}
 finally{busy.value=false}
}
onMounted(load)
</script>
<template><section class="panel feature-panel"><div class="section-caption">我的采购草稿<button class="btn" :disabled="busy" @click="load">刷新</button></div><p class="small-copy muted">草稿仅本人可见，不发起审批。最多展示最近 100 份未提交草稿。</p><p v-if="error" class="form-error">{{error}}</p><p v-if="busy" role="status">正在加载…</p><div v-for="d in items" :key="d.id" class="count-record"><strong>{{title(d)}}</strong><p class="small-copy muted">{{d.updated_at.replace('T',' ').slice(0,16)}}</p><button class="btn" :disabled="busy" @click="emit('open',d)">继续编辑</button><button v-if="confirming!==d.id" class="btn" :disabled="busy" @click="confirming=d.id">删除草稿</button><template v-else><button class="btn" :disabled="busy" @click="discard(d)">确认删除此草稿</button><button class="btn" :disabled="busy" @click="confirming=''">取消</button></template></div><p v-if="!items.length&&!busy&&!error" class="empty">暂无草稿。新建采购申请时可选择“保存草稿”。</p></section></template>
