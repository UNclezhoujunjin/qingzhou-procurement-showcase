import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import ts from 'typescript'
const source=await readFile(new URL('../src/api.ts',import.meta.url),'utf8')
const {outputText:code}=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ES2022,target:ts.ScriptTarget.ES2022}})
let sequence=0
async function client(){return import('data:text/javascript;base64,'+Buffer.from(code).toString('base64')+'#'+(++sequence))}
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
function network(handler){const calls=[];globalThis.fetch=async(url,options)=>{calls.push({url,options});if(url==='/api/auth/csrf')return json({token:'test-token'});return handler(url,options)};return calls}
test('write timeout preserves its key, reports uncertainty and never automatically retries',async()=>{
 const c=await client(),calls=network(()=>{throw new TypeError('connection lost')})
 await assert.rejects(c.post('/products',{name:'sample'},'request-key-123'),e=>e instanceof c.ApiError&&e.uncertain&&e.key==='request-key-123'&&!e.message.includes('不会重复'))
 assert.equal(calls.filter(x=>x.url==='/api/products').length,1)
})
test('read timeout is not reported as an unconfirmed write',async()=>{
 const c=await client();network(()=>{throw new TypeError('offline')})
 await assert.rejects(c.api('/products'),e=>e instanceof c.ApiError&&!e.uncertain)
})
test('version conflict is actionable and is not an automatic retry',async()=>{
 const c=await client(),calls=network(()=>json({message:'记录已更新'},409))
 await assert.rejects(c.put('/products/1',{version:0}),e=>e.status===409&&!e.uncertain&&e.message==='记录已更新')
 assert.equal(calls.filter(x=>x.url==='/api/products/1').length,1)
})
test('server error on a write requires outcome verification',async()=>{
 const c=await client();network(()=>json({message:'服务暂不可用'},503))
 await assert.rejects(c.post('/orders/1/close',{version:0},'close-key-123'),e=>e.status===503&&e.uncertain&&e.key==='close-key-123')
})
test('truncated successful write response is also uncertain',async()=>{
 const c=await client();network(()=>new Response('{"message":',{status:200}))
 await assert.rejects(c.post('/requests',{reason:'sample'},'create-key-123'),e=>e.uncertain&&e.key==='create-key-123')
})
test('successful write sends CSRF and idempotency headers and returns result',async()=>{
 const c=await client(),calls=network(()=>json({id:'record-1'}))
 assert.deepEqual(await c.post('/requests',{reason:'sample'},'create-key-123'),{id:'record-1'})
 const write=calls.find(x=>x.url==='/api/requests')
 assert.equal(write.options.headers.get('X-CSRF-TOKEN'),'test-token')
 assert.equal(write.options.headers.get('Idempotency-Key'),'create-key-123')
})
