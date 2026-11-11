import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import {createRenderer,nextTick} from 'vue'

// Mount actual component setup code with Vue's renderer; no browser or live API is used.
const url=code=>'data:text/javascript;base64,'+Buffer.from(code).toString('base64')
const mockUrl=url(`export class ApiError extends Error {constructor(status){super('记录已更新');this.status=status}}
export const api=(...a)=>globalThis.componentApi(...a);
export const post=(...a)=>globalThis.componentPost(...a);
export const put=(...a)=>globalThis.componentPut(...a);
export const csrf=async()=>{};`)
const {ApiError}=await import(mockUrl)
const renderer=createRenderer({
 insert(){},remove(){},patchProp(){},createElement:()=>({}),createText:()=>({}),createComment:()=>({}),
 setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null
})
const settle=async()=>{await new Promise(resolve=>setImmediate(resolve));await nextTick()}
async function mount(name,props={}){
 const source=await readFile(new URL(`../src/components/${name}.vue`,import.meta.url),'utf8')
 const {descriptor}=parse(source)
 const script=compileScript(descriptor,{id:name}).content
 let code=ts.transpileModule(script,{compilerOptions:{module:ts.ModuleKind.ES2022,target:ts.ScriptTarget.ES2022}}).outputText
 code=code.replace(/from ['"]vue['"]/g,`from '${import.meta.resolve('vue')}'`)
  .replace(/from ['"]\.\.\/api['"]/g,`from '${mockUrl}'`)
  .replace(/from ['"][^'"]+\.vue['"]/g,`from '${url('export default {}')}'`)
 const component=(await import(url(code))).default
 component.render=()=>null
 const app=renderer.createApp(component,props),vm=app.mount({})
 await settle()
 return {state:vm.$.setupState,unmount:()=>app.unmount()}
}
test('notification mutations ignore double clicks and refresh after marking all read',async()=>{
 let unread=21,writes=0,finish
 globalThis.componentApi=async()=>({items:[],total:unread,unread,page:unread?2:1,size:20})
 globalThis.componentPost=()=>{writes++;return new Promise(resolve=>{finish=()=>{unread=0;resolve()}})}
 const {state:s,unmount}=await mount('NotificationCenter')
 const operation=s.readAll();await s.readAll()
 assert.equal(writes,1);assert.equal(s.busy,true)
 finish();await operation;await settle()
 assert.equal(s.result.unread,0);assert.equal(s.page,1);assert.equal(s.busy,false)
 unmount()
})
test('profile load failure can retry and version conflict preserves typed input',async()=>{
 let fail=true
 globalThis.componentApi=async()=>{if(fail)throw new Error('网络中断');return {display_name:'旧姓名',email:'',phone:'',version:2}}
 globalThis.componentPut=async()=>{throw new ApiError(409)}
 const {state:s,unmount}=await mount('PersonalCenter')
 assert.equal(s.profile,null);assert.equal(s.error,'网络中断')
 fail=false;await s.load();s.name='我正在编辑的姓名';await s.save()
 assert.equal(s.conflict,true)
 await s.load(true)
 assert.equal(s.name,'我正在编辑的姓名');assert.equal(s.profile.version,2);assert.equal(s.conflict,false)
 unmount()
})
test('stocktake refresh keeps physical count and subsequent selection uses fresh snapshot',async()=>{
 const old={id:1,stock:5,stock_version:1},latest={id:1,stock:8,stock_version:2}
 globalThis.componentApi=async path=>path==='/products'?[latest]:[]
 const {state:s,unmount}=await mount('StockTools',{products:[old]})
 s.add();s.countLines[0].countedQuantity=4
 await s.reloadCountSnapshot()
 assert.equal(s.countLines[0].expected,8);assert.equal(s.countLines[0].stockVersion,2)
 assert.equal(s.countLines[0].countedQuantity,4)
 s.select(0)
 assert.equal(s.countLines[0].expected,8);assert.equal(s.countLines[0].stockVersion,2)
 s.quantity=9;s.rotateReturnKey();assert.equal(s.quantity,1)
 unmount()
})
