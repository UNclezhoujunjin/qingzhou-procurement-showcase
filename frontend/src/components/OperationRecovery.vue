<script setup lang="ts">
import {ref} from 'vue'
import {api} from '../api'
const props=defineProps<{requestKey:string;busy:boolean}>()
const emit=defineEmits<{committed:[];retry:[]}>()
const message=ref(''),checking=ref(false)
async function check(){checking.value=true;try{const r=await api<{committed:boolean}>('/operations/'+props.requestKey);if(r.committed)emit('committed');else message.value='尚未发现提交记录，原请求仍可能处理中。请稍后再查，或使用保留的相同内容重试。'}catch(e){message.value=(e as Error).message}finally{checking.value=false}}
</script>
<template><div class="form-hint" role="status"><p>提交结果尚未确认，输入内容已保留并暂时锁定。</p><p v-if="message">{{message}}</p><button type="button" class="btn" :disabled="checking||busy" @click="check">查询提交结果</button><button type="button" class="btn" :disabled="checking||busy" @click="emit('retry')">使用相同内容重试</button></div></template>
