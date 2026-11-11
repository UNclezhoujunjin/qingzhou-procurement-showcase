<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { LayoutDashboard, ClipboardList, ShoppingCart, Package, ArrowLeftRight, Building2, ShieldCheck, Users, Search, Plus, ArrowUpRight, ArrowRight, ChevronRight, LogOut, X, Check, AlertCircle, CheckCircle2, Clock3, Download, RefreshCw, Box, Layers, Menu, LoaderCircle, Eye, EyeOff, Bell, CircleHelp } from 'lucide-vue-next'
import { ApiError, api, post, put, csrf, requestTypeLabel, type RequestType, type Account, type Product, type PurchaseRequest, type Order, type Supplier, type Movement, type Audit, type User, type Overview } from './api'
import type {RequestItemInput,FulfillmentForm,Inbox,PageResult} from './api'
import PurchaseLines from './components/PurchaseLines.vue'
import InventoryAgent from './components/InventoryAgent.vue'
import InventoryAssistant from './components/InventoryAssistant.vue'
import RelatedOrders from './components/RelatedOrders.vue'
import FulfillmentEditor from './components/FulfillmentEditor.vue'
import PersonalCenter from './components/PersonalCenter.vue'
import NotificationCenter from './components/NotificationCenter.vue'
import StockTools from './components/StockTools.vue'
import BackupCenter from './components/BackupCenter.vue'
import RequestLinesTable from './components/RequestLinesTable.vue'
import GuestPreview from './components/GuestPreview.vue'
import SiteFiling from './components/SiteFiling.vue'
import ProductImport from './components/ProductImport.vue'
import DraftList,{type Draft} from './components/DraftList.vue'

const guestPreview=ref(false)
const account=ref<Account|null>(null), ready=ref(false), busy=ref(false), loading=ref(false), mobileMenu=ref(false)
const layoutMedia=window.matchMedia('(max-width:900px)'), narrow=ref(layoutMedia.matches)
function layoutChanged(event:MediaQueryListEvent) {narrow.value=event.matches;mobileMenu.value=false}
const page=ref(location.hash.slice(1)||'overview'), search=ref(''), statusFilter=ref('ALL'), categoryFilter=ref('ALL'), stockFilter=ref(false), pageNumber=ref(1)
const requestTypeFilter=ref('ALL')
const historyFrom=ref(''),historyTo=ref(''),historyOperator=ref('')
const total=ref(0), listCounts=ref<{status:string;total:number}[]>([]), catalogCategories=ref<string[]>([]), activeFilter=ref('ALL')
let loadSequence=0, listTimer:ReturnType<typeof setTimeout>|undefined
const totalPages=computed(()=>Math.max(1,Math.ceil(total.value/10)))
const username=ref(''), password=ref(''), showPassword=ref(false), loginError=ref(''), demoMode=ref(false)
const pickerProducts=ref<Product[]>([]), resumeProduct=ref(false)
const products=ref<Product[]>([]), requests=ref<PurchaseRequest[]>([]), orders=ref<Order[]>([]), suppliers=ref<Supplier[]>([]), movements=ref<Movement[]>([]), audits=ref<Audit[]>([]), users=ref<User[]>([])
const overview=ref<Overview>({pending:0,amount:0,requests:0,lowStock:0,openOrders:null,stockValue:null})
type TaskKind='approval'|'purchase'|'receipt'
type WorkTask={id:string;number:string;label:string;request_type?:RequestType;waiting_since:string;task_total:number}
const workQueue=ref<Partial<Record<TaskKind,WorkTask[]>>>({})
const queuePages=ref<Partial<Record<TaskKind,number>>>({})
const queueTotals=ref<Partial<Record<TaskKind,number>>>({})
const queueBusy=ref(false)
const taskTitles:Record<TaskKind,string>={approval:'待我审批',purchase:'待下单 · 采购团队',receipt:'待收货 · 仓库团队'}
const replenishmentLabels:Record<string,string>={PENDING:'待审批',APPROVED:'待下单',INBOUND:'待到货'}
const stockTools=ref<{reload:()=>Promise<void>}|null>(null)
const loadError=ref('')
const toast=ref<{message:string;error:boolean}|null>(null)
let toastTimer:ReturnType<typeof setTimeout>|undefined
const draftListVersion=ref(0),leavePrompt=ref(false),pendingNavigation=ref('')
const removalUsername=ref(''),removalReason=ref('')
const resetMember=ref<User|null>(null),resetPassword=ref(''),resetConfirm=ref('')
const draftId=ref(''),draftVersion=ref(0),transitionReason=ref(''),modalSnapshot=ref(''),uncertain=ref(false),conflict=ref(false),pendingKey=ref('')
const modal=ref(''), selectedRequest=ref<PurchaseRequest|null>(null), selectedOrder=ref<Order|null>(null), decision=ref('APPROVED'), reviewNote=ref('')
const approvers=ref<{id:number;display_name:string;username:string;department:string;role:string}[]>([]),approvalMessage=ref('')
const requestForm=ref<{lines:RequestItemInput[];reason:string;approverId:number|null;requestType?:PurchaseRequest['request_type']}>({lines:[{productId:1,quantity:1}],reason:'',approverId:null})
const fulfillmentForm=ref<FulfillmentForm>({lines:[],reason:''})
const unreadNotifications=ref(0)
let notificationTimer:ReturnType<typeof setInterval>|undefined
const issueForm=ref({productId:1,quantity:1,reason:''})
const productForm=ref({sku:'',name:'',category:'办公设备',unit:'件',price:1,minStock:5,supplierId:1})
const supplierForm=ref({name:'',contact:'',phone:''})
const userForm=ref({username:'',name:'',department:'',role:'EMPLOYEE',password:'',enabled:true})
const editing=ref<{id:number|string;version:number;status?:string}|null>(null)
const memberStatus=ref('ALL')
const filteredUsers=computed(()=>users.value.filter(u=>matches(u.display_name,u.username,u.department)&&(memberStatus.value==='ALL'||u.enabled===(memberStatus.value==='ENABLED'))))
const modalTitles:Record<string,string>={agent:'轻舟智能体',assistant:'AI 补货助手',request:'新建采购申请',reassign:'改派审批负责人',review:'审批采购申请',detail:'采购申请详情',issue:'领用出库',receive:'登记本次收货',purchaseReturn:'采购退货',orderDetail:'订单收货明细',product:'新增商品档案',supplier:'新增供应商',user:'新增团队成员',withdraw:'撤回采购申请',closeOrder:'结束采购订单',resetMember:'重置成员密码',removeMember:'删除团队成员',help:'工作空间使用指南'}
const editTitles:Record<string,string>={request:'修改采购申请',product:'编辑商品档案',supplier:'编辑供应商',user:'编辑团队成员'}
const submitTitles:Record<string,string>={request:'提交采购申请',withdraw:'确认撤回',closeOrder:'确认结束订单',resetMember:'确认重置成员密码',removeMember:'确认删除成员',reassign:'确认改派',review:'确认审批',receive:'确认本次收货',purchaseReturn:'确认采购退货',issue:'确认领用出库',product:'创建商品',supplier:'创建供应商',user:'创建成员'}
const restockRequest=ref(false)
const assistantProduct=ref<Product|null>(null)
const restockHint=ref('')
const reassignId=ref<number|null>(null)
const memberPending=ref<{number:string;total:number}[]>([])
const memberImpactError=ref('')
function restockGap(product:Product,inboundProduct=product){
 const gap=Math.max(0,product.min_stock-product.stock)
 const inbound=inboundProduct.replenishment?.find(item=>item.status==='INBOUND')?.quantity||0
 return {gap,inbound,suggested:Math.max(0,gap-inbound)}
}
const modalTitle=computed(()=>modal.value==='request'&&restockRequest.value?(editing.value?'修改补货申请':'发起补货申请'):editing.value?editTitles[modal.value]:modalTitles[modal.value])
const submitTitle=computed(()=>editing.value?(modal.value==='request'?(editing.value.status==='REJECTED'?'修改并重新提交':'保存申请修改'):'保存修改'):submitTitles[modal.value])
const formError=ref(''), idempotencyKey=ref('')
let returnFocus:HTMLElement|null=null

const roleNames:Record<string,string>={ADMIN:'系统管理员',EMPLOYEE:'普通员工',MANAGER:'部门主管',BUYER:'采购专员',WAREHOUSE:'仓库管理员'}
const statusNames:Record<string,string>={PENDING:'待审批',APPROVED:'待下单',REJECTED:'已驳回',ORDERED:'收货中',WITHDRAWN:'已撤回',CLOSED:'已结束（含终止）',CANCELLED:'已取消',TERMINATED:'已终止余量',RECEIVED:'已收齐',OPEN:'待收货',PARTIAL:'部分收货',RETURNED:'已退货待补货'}
const movementNames:Record<string,string>={OPENING:'期初建账',ISSUE:'领用出库',PURCHASE_RECEIPT:'采购入库',PURCHASE_RETURN:'采购退货',ISSUE_RETURN:'领用归还',COUNT_ADJUSTMENT:'盘点调整'}
const navItems=[
 {id:'overview',name:'工作台',icon:LayoutDashboard,group:'工作空间'},
 {id:'notifications',name:'通知中心',icon:Bell},
 {id:'requests',name:'采购申请',icon:ClipboardList,group:'业务管理'},
 {id:'drafts',name:'我的草稿',icon:ClipboardList,roles:['ADMIN','EMPLOYEE','MANAGER']},
 {id:'orders',name:'采购订单',icon:ShoppingCart,roles:['ADMIN','BUYER','WAREHOUSE']},
 {id:'inventory',name:'库存管理',icon:Package},
 {id:'stock-tools',name:'盘点与归还',icon:ArrowLeftRight,roles:['ADMIN','WAREHOUSE']},
 {id:'movements',name:'出入库流水',icon:ArrowLeftRight,roles:['ADMIN','BUYER','WAREHOUSE']},
 {id:'products',name:'商品档案',icon:Layers},
 {id:'product-import',name:'商品批量导入',icon:Layers,roles:['ADMIN','BUYER']},
 {id:'suppliers',name:'供应商',icon:Building2,roles:['ADMIN','BUYER']},
 {id:'users',name:'组织与成员',icon:Users,group:'系统管理',roles:['ADMIN']},
 {id:'audit',name:'操作审计',icon:ShieldCheck,roles:['ADMIN']},
 {id:'backups',name:'运行与备份',icon:ShieldCheck,roles:['ADMIN']},
 {id:'profile',name:'个人中心',icon:Users}
]
const nav=computed(()=>navItems.filter(i=>(!i.roles || has(...i.roles))&&(!account.value?.mustChangePassword||i.id==='profile')))
const currentNav=computed(()=>nav.value.find(i=>i.id===page.value)||navItems[0])
const subtitle:Record<string,string>={overview:'从采购需求到库存交付，让每一步都有迹可循。',requests:'集中管理采购需求，跟进每一笔申请的处理进度。',orders:'连接采购与仓储，跟进订单交付。',inventory:'掌握现有库存，及时补充短缺物资。',movements:'每一笔库存变化，都有清晰的来源。',products:'统一商品信息，让采购与仓储使用同一份档案。',suppliers:'维护合作伙伴与联系信息。',users:'查看团队成员、部门与角色分工。',audit:'追踪关键操作，还原业务处理过程。'}
const today=new Intl.DateTimeFormat('zh-CN',{year:'numeric',month:'long',day:'numeric',weekday:'long'}).format(new Date())
function has(...roles:string[]) { return !!account.value && roles.includes(account.value.role) }
function money(value:number) { return new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY',maximumFractionDigits:2}).format(value) }
function date(value:string,full=false) { if(!value)return '—'; return value.replace('T',' ').slice(0,full?16:10) }
function notify(message:string,error=false) { toast.value={message,error};clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.value=null,4500) }
function navigate(id:string) { if(account.value?.mustChangePassword&&id!=='profile'){notify('请先在个人中心修改密码',true);return} if(modal.value&&!closeModal()){pendingNavigation.value=id;history.replaceState(null,'','#'+page.value);return}page.value=id;activeFilter.value='ALL';location.hash=id;search.value='';statusFilter.value='ALL';requestTypeFilter.value='ALL';historyFrom.value='';historyTo.value='';historyOperator.value='';categoryFilter.value='ALL';stockFilter.value=false;pageNumber.value=1;mobileMenu.value=false;void nextTick(()=>window.scrollTo(0,0)) }
function matches(...values:unknown[]) { return values.some(v=>String(v??'').toLowerCase().includes(search.value.trim().toLowerCase())) }
const filteredRequests=computed(()=>requests.value)
const filteredProducts=computed(()=>products.value)
const filteredOrders=computed(()=>orders.value)
const categories=computed(()=>catalogCategories.value)
const lowProducts=computed(()=>products.value.filter(p=>p.stock<p.min_stock))
const displayedRequests=computed(()=>requests.value)
const issueProduct=computed(()=>pickerProducts.value.find(p=>p.id===issueForm.value.productId))
const counts=computed(()=>['PENDING','APPROVED','ORDERED','RECEIVED'].map(status=>({status,count:Number(overview.value.counts?.find(c=>c.status===status)?.total||0)})))
const categorySpending=computed(()=>{
 const items=overview.value.categories||[],sum=items.reduce((s,x)=>s+Number(x.amount),0)
 return items.map(x=>({...x,percent:sum?Math.round(Number(x.amount)/sum*100):0}))
})
function statusCount(status:string){return status==='ALL'?listCounts.value.reduce((s,x)=>s+Number(x.total),0):Number(listCounts.value.find(x=>x.status===status)?.total||0)}
async function loadList(kind:string, extra:Record<string,string>={}){
 const query=new URLSearchParams({page:String(pageNumber.value),size:'10',q:search.value,status:statusFilter.value,requestType:requestTypeFilter.value,fromDate:historyFrom.value,toDate:historyTo.value,operator:historyOperator.value,category:categoryFilter.value,active:activeFilter.value,lowStock:String(stockFilter.value),...extra})
 return api<PageResult<any>>('/browse/'+kind+'?'+query)
}
async function refresh(quiet=false) {
 if(!account.value)return
 const seq=++loadSequence,current=page.value
 if(!quiet)loading.value=true
 loadError.value=''
 try {
  if(pagedPages.includes(current)){
   const kind=current==='inventory'?'products':current
   const result=await loadList(kind)
   if(seq!==loadSequence)return
   total.value=result.total;pageNumber.value=result.page;listCounts.value=result.counts||[]
   if(result.categories)catalogCategories.value=result.categories
   if(kind==='products')products.value=result.items
   else if(kind==='requests')requests.value=result.items
   else if(kind==='audit')audits.value=result.items
   else if(kind==='movements')movements.value=result.items
   else orders.value=result.items
  }else if(current==='overview'){
   const [summary,recent,stock,tasks]=await Promise.all([
    api<Overview>('/overview'),
    api<PageResult<PurchaseRequest>>('/browse/requests?size=5'),
    api<PageResult<Product>>('/browse/products?size=5&lowStock=true'),
    api<typeof workQueue.value>('/work-queue')
   ])
   if(seq!==loadSequence)return
   overview.value=summary;requests.value=recent.items;products.value=stock.items;workQueue.value=tasks
   queuePages.value={}
   queueTotals.value={}
   for(const kind of Object.keys(tasks) as TaskKind[])queueTotals.value[kind]=tasks[kind]?.[0]?.task_total||0
  }else if(current==='suppliers'){const data=await api<Supplier[]>('/suppliers');if(seq===loadSequence)suppliers.value=data}
  else if(current==='users'){const data=await api<User[]>('/users');if(seq===loadSequence)users.value=data}
  else if(current==='stock-tools'){const data=await api<Product[]>('/products');if(seq===loadSequence){products.value=data;await stockTools.value?.reload()}}
 } catch(e) {if(seq===loadSequence){loadError.value=(e as Error).message;notify(loadError.value,true)}} finally {if(seq===loadSequence)loading.value=false}
}
const pagedPages=['products','inventory','requests','orders','audit','movements']
watch(page,()=>{clearTimeout(listTimer);products.value=[];requests.value=[];orders.value=[];suppliers.value=[];users.value=[];audits.value=[];movements.value=[];total.value=0;listCounts.value=[];void refresh()})
watch([search,statusFilter,requestTypeFilter,historyFrom,historyTo,historyOperator,categoryFilter,stockFilter,activeFilter],()=>{
 clearTimeout(listTimer);if(!pagedPages.includes(page.value))return;listTimer=setTimeout(()=>{if(pageNumber.value!==1)pageNumber.value=1;else void refresh()},250)
})
watch(pageNumber,()=>{if(!pagedPages.includes(page.value))return;clearTimeout(listTimer);listTimer=setTimeout(()=>void refresh(),0)})
async function toggleProduct(p:Product){
 if(busy.value)return
 busy.value=true
 try{await put('/browse/products/'+p.id+'/state',{enabled:!p.enabled,version:p.version});notify(p.enabled?'商品已停用，历史和库存保留':'商品已恢复');await refresh(true);await refreshSummary()}catch(e){notify((e as Error).message,true)}finally{busy.value=false}
}
async function refreshNotifications(){if(!account.value||account.value?.mustChangePassword)return;try{unreadNotifications.value=(await api<Inbox>('/notifications?size=1')).unread}catch{/* API reports expired sessions through the shared handler. */}}
function signedOut(message:string){account.value=null;password.value='';modal.value='';notify(message)}
async function login() {
 if(busy.value)return
 busy.value=true;loginError.value=''
 try {
  await csrf()
  await api('/auth/login',{method:'POST',body:new URLSearchParams({username:username.value,password:password.value})})
  await csrf();account.value=await api<Account>('/auth/me');navigate(account.value?.mustChangePassword?'profile':'overview');await refresh()
 } catch(e) { loginError.value=(e as Error).message } finally { busy.value=false }
}
async function logout() {
 try { await post('/auth/logout');account.value=null;modal.value='';await csrf() }
 catch(e) {notify((e as Error).message,true)}
}
function demo(name:string) { username.value=name;password.value='Qingzhou@2026' }
async function refreshApprovers(){try{const r=await api<{items:typeof approvers.value;message:string}>('/requests/approvers');approvers.value=r.items;approvalMessage.value=r.message}catch(e){approvers.value=[];approvalMessage.value=(e as Error).message}}
function canReview(r:PurchaseRequest){return has('MANAGER','ADMIN')&&r.applicant_id!==account.value?.id&&r.status==='PENDING'&&(!r.approver_id||r.approver_id===account.value?.id)&&(account.value?.role==='ADMIN'||r.department===account.value?.department)}
function requestNext(r:PurchaseRequest){
 switch(r.status){
 case 'PENDING': return (canReview(r)?'轮到你审批：':'等待审批：')+(r.approver_name||(r.approver_id?'指定负责人':'同部门主管或其他管理员'))
 case 'APPROVED': return has('BUYER','ADMIN')?'轮到你生成采购订单':'等待采购专员或管理员下单'
 case 'ORDERED': return has('WAREHOUSE','ADMIN')?'请到采购订单登记实际收货':'等待仓库管理员或管理员登记收货'
 case 'REJECTED': return '申请人可按审批意见修改后重提'
 case 'WITHDRAWN': return '申请人可修改后重新提交'
 case 'RECEIVED': return '已收齐，无需继续处理'
 case 'CLOSED': return '采购已结束，无待处理步骤'
 default: return '请查看申请详情'
 }
}
function canRequestStock(){return has('EMPLOYEE','MANAGER','ADMIN')}
async function openModal(type:string, item?:PurchaseRequest|Order|Product|Supplier|User, restock?:Product) {
 if(type==='product'){
  try{suppliers.value=await api<Supplier[]>('/suppliers')}catch(e){notify((e as Error).message,true);return}
  if(!suppliers.value.length){resumeProduct.value=true;notify('请先创建供应商，再添加商品');navigate('suppliers');await openModal('supplier');return}
 }
 if(type==='request'||type==='issue'){
  try{pickerProducts.value=await api<Product[]>('/products')}catch(e){notify((e as Error).message,true);return}
  if(restock&&!pickerProducts.value.some(p=>p.id===restock.id&&p.enabled)){notify('该商品已停用，无法发起补货申请',true);return}
  if(!pickerProducts.value.some(p=>type==='issue'?p.stock>0:p.enabled)){notify(type==='issue'?'暂无可领用库存':'暂无启用商品，请先创建商品',true);return}
 }
 leavePrompt.value=false;pendingNavigation.value='';resetPassword.value='';resetConfirm.value='';uncertain.value=false;conflict.value=false;pendingKey.value='';transitionReason.value='';draftId.value='';draftVersion.value=0;
 returnFocus=document.activeElement as HTMLElement;formError.value='';idempotencyKey.value=crypto.randomUUID();modal.value=type
 editing.value=item&&['request','product','supplier','user'].includes(type)?{id:item.id,version:(item as Product).version,status:(item as PurchaseRequest).status}:null
 restockHint.value='';reassignId.value=null;memberPending.value=[];memberImpactError.value=''
 if(type==='assistant')assistantProduct.value=item as Product
 restockRequest.value=type==='request'&&(!!restock||(item as PurchaseRequest|undefined)?.request_type==='RESTOCK')
 if(type==='request')await refreshApprovers()
 if(type==='request')requestForm.value={lines:[{productId:pickerProducts.value.find(p=>p.enabled)?.id||0,quantity:1}],reason:'',approverId:null,requestType:restock?'RESTOCK':'PURCHASE'}
 if(type==='request'&&restock){
  const product=pickerProducts.value.find(p=>p.id===restock.id&&p.enabled)
  if(product){
   const {gap,inbound,suggested}=restockGap(product,restock)
   requestForm.value.lines=[{productId:product.id,quantity:suggested}]
   requestForm.value.reason='库存补货：'+product.name
   restockHint.value=`库存缺口 ${gap} ${product.unit}，可见待到货 ${inbound} ${product.unit}，参考补货 ${suggested} ${product.unit}。`+
    (suggested===0?' 如仍需补货，请手动填写数量。':' 请根据实际需求核对数量。')
  }
 }
 if(type==='review'||type==='detail'||type==='withdraw'||type==='reassign') { selectedRequest.value=item as PurchaseRequest;reviewNote.value='';decision.value='APPROVED' }
 if(['receive','purchaseReturn','orderDetail','closeOrder'].includes(type)){const order=item as Order;selectedOrder.value=order;fulfillmentForm.value={lines:order.lines.map(l=>({orderLineId:l.id,quantity:0})),reason:''}}
 if(type==='issue')issueForm.value={productId:item?(item as Product).id:pickerProducts.value.find(p=>p.stock>0)?.id||0,quantity:1,reason:''}
 if(type==='product')productForm.value={sku:'',name:'',category:'办公设备',unit:'件',price:1,minStock:5,supplierId:suppliers.value[0]?.id||0}
 if(type==='supplier')supplierForm.value={name:'',contact:'',phone:''}
 if(type==='resetMember'||type==='removeMember'){resetMember.value=item as User;removalUsername.value='';removalReason.value=''}
 if(type==='user')userForm.value={username:'',name:'',department:'',role:'EMPLOYEE',password:'',enabled:true}
 if(item&&type==='request'){const r=item as PurchaseRequest;selectedRequest.value=r;requestForm.value={lines:r.lines.map(l=>({productId:l.product_id,quantity:l.quantity})),reason:r.reason,approverId:r.approver_id||null,requestType:r.request_type}}
 if(item&&type==='product'){const p=item as Product;productForm.value={sku:p.sku,name:p.name,category:p.category,unit:p.unit,price:Number(p.price),minStock:p.min_stock,supplierId:p.supplier_id}}
 if(item&&type==='supplier'){const s=item as Supplier;supplierForm.value={name:s.name,contact:s.contact,phone:s.phone}}
 if(item&&type==='user'){const u=item as User;userForm.value={username:u.username,name:u.display_name,department:u.department,role:u.role,password:'',enabled:u.enabled}}
 if(type==='reassign'&&item){
  approvers.value=[];approvalMessage.value='正在读取可改派人员…'
  try{approvers.value=await api<typeof approvers.value>('/requests/'+item.id+'/reassign-candidates');approvalMessage.value=approvers.value.length?'请选择新的审批负责人。':'暂无可改派人员。'}
  catch(e){approvalMessage.value=(e as Error).message}
 }
 if(type==='user'&&item){
  const userId=item.id
  void api<typeof memberPending.value>('/users/'+userId+'/pending-approvals').then(rows=>{
   if(modal.value==='user'&&editing.value?.id===userId)memberPending.value=rows
  }).catch(e=>{if(modal.value==='user'&&editing.value?.id===userId)memberImpactError.value=(e as Error).message})
 }
 modalSnapshot.value=formSnapshot();
 await nextTick();document.querySelector<HTMLElement>('.modal input, .modal select, .modal button')?.focus()
}
async function fillAssistantDraft(product:Product,reason:string){
 await openModal('request',undefined,product)
 if(modal.value!=='request')return
 if(reason.trim())requestForm.value.reason=reason.trim()
 modalSnapshot.value=formSnapshot()
}
function formSnapshot(){return JSON.stringify(modal.value==='reassign'?{id:reassignId.value,reason:transitionReason.value}:modal.value==='removeMember'?{username:removalUsername.value,reason:removalReason.value}:modal.value==='resetMember'?{password:resetPassword.value,confirm:resetConfirm.value}:modal.value==='request'?requestForm.value:modal.value==='product'?productForm.value:modal.value==='supplier'?supplierForm.value:modal.value==='user'?userForm.value:modal.value==='issue'?issueForm.value:['receive','purchaseReturn'].includes(modal.value)?fulfillmentForm.value:['withdraw','closeOrder'].includes(modal.value)?transitionReason.value:modal.value==='review'?{decision:decision.value,note:reviewNote.value}:null)}
function dirty(){return !!modal.value&&formSnapshot()!==modalSnapshot.value}
function beforeLeave(e:BeforeUnloadEvent){if(dirty()||uncertain.value){e.preventDefault();e.returnValue=''}}
function closeModal() {
 if(busy.value)return false
 if(dirty()||uncertain.value){leavePrompt.value=true;void nextTick(()=>document.querySelector<HTMLElement>('.leave-warning button')?.focus());return false}
 resetPassword.value='';resetConfirm.value='';resumeProduct.value=false;modal.value='';uncertain.value=false;returnFocus?.focus();return true
}
function discardEdit(){resetPassword.value='';resetConfirm.value='';const target=pendingNavigation.value;leavePrompt.value=false;pendingNavigation.value='';modal.value='';uncertain.value=false;resumeProduct.value=false;returnFocus?.focus();if(target)navigate(target)}
async function profileUpdated(){try{account.value=await api<Account>('/auth/me');notify('个人资料已保存')}catch(e){notify((e as Error).message,true)}}
async function openSourceRequest(id:string){
 try{await openModal('detail',await api<PurchaseRequest>('/requests/'+id))}
 catch(e){notify((e as Error).message,true)}
}
async function openLinkedOrder(id:string){
 try{await openModal('orderDetail',await api<Order>('/orders/'+id))}
 catch(e){notify((e as Error).message,true)}
}
function waitingDays(value:string){
 const started=new Date(value).getTime()
 if(!Number.isFinite(started))return ''
 const days=Math.max(0,Math.floor((Date.now()-started)/86400000))
 return days===0?'等待不足 1 天':`已等待 ${days} 天`
}
async function changeQueuePage(kind:TaskKind,next:number){
 if(queueBusy.value||next<1)return
 queueBusy.value=true
 const sequence=loadSequence
 try{
  const result=await api<typeof workQueue.value>('/work-queue?page='+next)
  if(page.value!=='overview'||sequence!==loadSequence)return
  const tasks=result[kind]||[]
  if(!tasks.length&&next>1){await refresh(true);return}
  workQueue.value[kind]=tasks
  queuePages.value[kind]=next
  queueTotals.value[kind]=tasks[0]?.task_total||0
 }catch(e){notify((e as Error).message,true)}finally{queueBusy.value=false}
}
async function openTask(kind:TaskKind,id:string){
 try{
  if(kind==='receipt'){
   const order=await api<Order>('/orders/'+id)
   const receivable=order.lifecycle_state==='ACTIVE'&&order.lines.some(line=>line.remaining>0)
   await openModal(receivable?'receive':'orderDetail',order)
   return
  }
  const request=await api<PurchaseRequest>('/requests/'+id)
  await openModal(kind==='approval'&&canReview(request)?'review':'detail',request)
 }catch(e){notify((e as Error).message,true)}
}
async function openNotification(path:string,id?:string){if(!nav.value.some(n=>n.id===path)){notify('当前角色无法查看该业务页面',true);return}navigate(path);if(!id||!['requests','orders'].includes(path))return;try{if(path==='requests')await openModal('detail',await api<PurchaseRequest>('/requests/'+id));else await openModal('orderDetail',await api<Order>('/orders/'+id))}catch(e){notify((e as Error).message,true)}}
async function refreshSummary(){try{overview.value=await api<Overview>('/overview')}catch{/* Main page retains its own retry control. */}}
async function openDraft(d:Draft){await openModal('request');if(modal.value!=='request')return;try{requestForm.value={approverId:null,...JSON.parse(d.payload)};restockRequest.value=requestForm.value.requestType==='RESTOCK';draftId.value=d.id;draftVersion.value=d.version;modalSnapshot.value=formSnapshot()}catch{formError.value='草稿内容无法读取'}}
async function saveDraft(){
 if(busy.value||uncertain.value)return
 busy.value=true;formError.value=''
 if(!draftId.value)draftId.value=crypto.randomUUID()
 try{const d=await put<{version:number}>('/drafts/'+draftId.value,{payload:JSON.stringify(requestForm.value),version:draftVersion.value});draftVersion.value=d.version;modalSnapshot.value=formSnapshot();notify('草稿已保存，仅本人可见')}catch(e){formError.value=(e as Error).message;conflict.value=e instanceof ApiError&&e.status===409}finally{busy.value=false}
}
async function checkOutcome(){
 if(!pendingKey.value){formError.value='该操作请先读取最新记录核对保存结果，再决定是否重新编辑';conflict.value=true;return}
 try{
  const result=await api<{committed:boolean}>('/operations/'+pendingKey.value)
  if(result.committed){uncertain.value=false;modal.value='';draftListVersion.value++;notify('已确认操作成功，数据已保存');await refresh(true);await refreshSummary()}
  else formError.value='尚未找到已提交记录，原请求仍可能处理中。可稍后查询；原内容和标识已保留，可使用相同内容重试。'
 }catch(e){formError.value=(e as Error).message}
}
async function reloadConflict(){
 try{
  if(draftId.value&&modal.value==='request'&&!editing.value){const d=await api<{version:number;submitted_request_id:string|null}>('/drafts/'+draftId.value);if(d.submitted_request_id){modal.value='';uncertain.value=false;notify('该草稿已提交，请在采购申请查看');await refresh(true);return}draftVersion.value=d.version}
  else if(editing.value&&modal.value==='request'){const r=await api<PurchaseRequest>('/requests/'+editing.value.id);editing.value.version=r.version;selectedRequest.value=r}
  else if(selectedOrder.value&&['receive','purchaseReturn','closeOrder'].includes(modal.value)){selectedOrder.value=await api<Order>('/orders/'+selectedOrder.value.id)}
  else if(selectedRequest.value&&['review','withdraw','reassign'].includes(modal.value)){selectedRequest.value=await api<PurchaseRequest>('/requests/'+selectedRequest.value.id)}
  else if(['resetMember','removeMember'].includes(modal.value)&&resetMember.value){const members=await api<User[]>('/users');const member=members.find(m=>m.id===resetMember.value!.id);if(!member)throw new Error('成员已删除，请关闭弹窗并刷新成员列表');resetMember.value=member}
  else if(editing.value&&modal.value==='product'){const rows=await api<Product[]>('/products');const p=rows.find(p=>p.id===editing.value!.id);if(p)editing.value.version=p.version}
  else if(editing.value&&modal.value==='supplier'){const rows=await api<Supplier[]>('/suppliers');const s=rows.find(s=>s.id===editing.value!.id);if(s)editing.value.version=s.version}
  else if(editing.value&&modal.value==='user'){const rows=await api<User[]>('/users');const u=rows.find(u=>u.id===editing.value!.id);if(u)editing.value.version=u.version}
  uncertain.value=false;conflict.value=false;idempotencyKey.value=crypto.randomUUID();formError.value='最新版本已读取，当前输入已保留，请核对状态、数量和资料后再提交。'
 }catch(e){formError.value=(e as Error).message}
}
async function submit() {
 if(busy.value)return
 busy.value=true;formError.value='';conflict.value=false
 try {
  let message='操作已完成'
  if(modal.value==='request') { if(editing.value)await put(`/requests/${editing.value.id}`,{data:requestForm.value,version:editing.value.version});else if(draftId.value){if(!uncertain.value){const d=await put<{version:number}>('/drafts/'+draftId.value,{payload:JSON.stringify(requestForm.value),version:draftVersion.value});draftVersion.value=d.version}await post('/drafts/'+draftId.value+'/submit',{version:draftVersion.value},idempotencyKey.value)}else await post('/requests',requestForm.value,idempotencyKey.value);message='采购申请已提交，等待指定负责人审批' }
  if(modal.value==='resetMember'){if(resetPassword.value!==resetConfirm.value)throw new Error('两次密码输入不一致');await post('/users/'+resetMember.value!.id+'/reset-password',{password:resetPassword.value,version:resetMember.value!.version},idempotencyKey.value);resetPassword.value='';resetConfirm.value='';message='成员密码已重置，下次登录必须修改密码'}
  if(modal.value==='removeMember'){if(removalUsername.value.trim()!==resetMember.value!.username)throw new Error('请输入该成员的登录账号以确认删除');await post('/users/'+resetMember.value!.id+'/remove',{version:resetMember.value!.version,username:removalUsername.value.trim(),reason:removalReason.value},idempotencyKey.value);message='成员已删除，历史申请、审批和库存记录已保留'}
  if(modal.value==='reassign'){
   if(!reassignId.value)throw new Error('请选择新的审批负责人')
   await post('/requests/'+selectedRequest.value!.id+'/reassign',{approverId:reassignId.value,reason:transitionReason.value,version:selectedRequest.value!.version},idempotencyKey.value)
   message='审批已改派，新负责人已收到通知'
  }
  if(modal.value==='withdraw')await post('/requests/'+selectedRequest.value!.id+'/withdraw',{reason:transitionReason.value,version:selectedRequest.value!.version},idempotencyKey.value)
  if(modal.value==='closeOrder')await post('/orders/'+selectedOrder.value!.id+'/close',{reason:transitionReason.value,version:selectedOrder.value!.version},idempotencyKey.value)
  if(modal.value==='review') { await post(`/requests/${selectedRequest.value!.id}/review`,{decision:decision.value,note:reviewNote.value,version:selectedRequest.value!.version},idempotencyKey.value);message='审批已完成' }
  if(modal.value==='receive'||modal.value==='purchaseReturn') {const lines=fulfillmentForm.value.lines.filter(l=>l.quantity>0);if(!lines.length)throw new Error('请至少填写一项本次处理数量');await post(`/orders/${selectedOrder.value!.id}/${modal.value==='receive'?'receive':'return'}`,{lines,reason:fulfillmentForm.value.reason,version:selectedOrder.value!.version},idempotencyKey.value);message=modal.value==='receive'?'本次收货已入库，剩余数量继续跟进':'采购退货已记录，库存与待补货数量已更新'}
  if(modal.value==='issue') { await post('/inventory/issue',issueForm.value,idempotencyKey.value);message='领用出库成功，流水已记录' }
  if(modal.value==='product') { if(editing.value)await put(`/products/${editing.value.id}`,{data:productForm.value,version:editing.value.version});else await post('/products',productForm.value,idempotencyKey.value);message=editing.value?'商品资料已保存，历史申请金额保持不变':'商品档案已创建' }
  if(modal.value==='supplier') { if(editing.value)await put(`/suppliers/${editing.value.id}`,{data:supplierForm.value,version:editing.value.version});else await post('/suppliers',supplierForm.value,idempotencyKey.value);message=editing.value?'供应商资料已保存':'供应商已创建' }
  if(modal.value==='user') { if(editing.value)await put(`/users/${editing.value.id}`,{name:userForm.value.name,department:userForm.value.department,role:userForm.value.role,enabled:userForm.value.enabled,version:editing.value.version});else await post('/users',userForm.value,idempotencyKey.value);message=editing.value?'成员资料已保存，该成员需重新登录':'成员已创建' }
  uncertain.value=false;leavePrompt.value=false;pendingNavigation.value='';draftListVersion.value++;const resume=modal.value==='supplier'&&resumeProduct.value;modal.value='';notify(message);await refresh(true);await refreshSummary();await refreshNotifications();returnFocus?.focus();if(resume){resumeProduct.value=false;navigate('products');await openModal('product')}
 } catch(e) {formError.value=(e as Error).message;if(e instanceof ApiError){uncertain.value=e.uncertain;conflict.value=e.status===409;pendingKey.value=e.key}} finally {busy.value=false}
}
async function createOrder(r:PurchaseRequest) {
 if(busy.value)return;busy.value=true
 try {const result=await post<{orders:Order[]}>(`/requests/${r.id}/order`);notify(`已按供应商生成 ${result.orders.length} 笔采购订单`);await refresh(true);await refreshSummary()
  if(modal.value==='detail'&&selectedRequest.value?.id===r.id)selectedRequest.value=await api<PurchaseRequest>('/requests/'+r.id)
 }catch(e){notify((e as Error).message,true)}finally{busy.value=false}
}
async function exportCsv() {
 const current=page.value
 let exportRequests=filteredRequests.value,exportOrders=filteredOrders.value,exportProducts=filteredProducts.value
 let exportAudits=audits.value,exportMovements=movements.value
 if(pagedPages.includes(current)){
  if(busy.value)return
  busy.value=true
  try{
   const kind=current==='inventory'?'products':current
   const query=new URLSearchParams({size:'100',q:search.value,status:statusFilter.value,requestType:requestTypeFilter.value,fromDate:historyFrom.value,toDate:historyTo.value,operator:historyOperator.value,category:categoryFilter.value,active:activeFilter.value,lowStock:String(stockFilter.value)})
   let records:any[]=[]
   for(let n=1;;n++){
    query.set('page',String(n))
    const result=await api<PageResult<any>>('/browse/'+kind+'?'+query)
    records.push(...result.items)
    if(n*100>=result.total)break
   }
   if(current==='requests')exportRequests=records
   else if(current==='orders')exportOrders=records
   else if(current==='audit')exportAudits=records
   else if(current==='movements')exportMovements=records
   else exportProducts=records
  }catch(e){notify((e as Error).message,true);return}finally{busy.value=false}
 }
 let rows:unknown[][]=[]
 if(current==='requests')rows=[['申请编号','申请类型','商品','SKU','申请人','部门','数量','单位','单价','行金额','单据总额','状态','时间'],...exportRequests.flatMap(r=>r.lines.map(l=>[r.number,requestTypeLabel(r.request_type),l.product_name,l.sku,r.applicant_name,r.department,l.quantity,l.unit,l.unit_price,l.amount,r.amount,statusNames[r.status],r.created_at]))]
 else if(current==='orders') rows=[['订单编号','来源申请','申请类型','供应商','商品','SKU','订购数量','单位','单价','行金额','订单总额','已收数量','已退数量','净收数量','待收数量','状态','采购人','创建时间'],...exportOrders.flatMap(o=>o.lines.map(l=>[o.number,o.request_number||'',requestTypeLabel(o.request_type),o.supplier_name,l.product_name,l.sku,l.quantity,l.unit,l.unit_price,l.amount,o.amount,l.received_quantity,l.returned_quantity,l.net_received,l.remaining,statusNames[o.fulfillment_status],o.buyer_name,o.created_at]))]
 else if(current==='audit') rows=[['操作人','操作','关联记录','详情','时间'],...exportAudits.map(a=>[a.actor_name,a.action,a.target,a.detail,a.created_at])]
 else if(current==='movements') rows=[['商品','业务类型','变动数量','结存','来源','操作人','时间'],...exportMovements.map(m=>[m.product_name,movementNames[m.business_type]||m.type,m.quantity,m.balance,m.reference,m.operator_name,m.created_at])]
 else rows=[['商品编号','商品名称','分类','库存','安全库存','单位','参考单价'],...exportProducts.map(p=>[p.sku,p.name,p.category,p.stock,p.min_stock,p.unit,p.price])]
 const csv='\uFEFF'+rows.map(row=>row.map(v=>{let s=String(v??'');if(/^[=+\-@\t\r]/.test(s))s="'"+s;return '"'+s.replace(/"/g,'""')+'"'}).join(',')).join('\r\n')
 const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8;'}));const a=document.createElement('a');a.href=url;a.download=`轻舟-${currentNav.value.name}-${new Date().toISOString().slice(0,10)}.csv`;a.click();URL.revokeObjectURL(url);notify('已导出当前筛选结果')
}
function expired(event:Event) {resetPassword.value='';resetConfirm.value='';account.value=null;modal.value='';notify(event instanceof CustomEvent&&event.detail?event.detail:'登录已过期，请重新登录',true)}
function hashChange() {const id=location.hash.slice(1);if(nav.value.some(i=>i.id===id)&&id!==page.value)navigate(id)}
function keydown(e:KeyboardEvent) {
 if(e.key==='Escape'&&mobileMenu.value){mobileMenu.value=false;document.querySelector<HTMLElement>('[aria-label="展开导航"]')?.focus()}
 if(!modal.value)return
 if(e.key==='Escape')closeModal()
 if(e.key==='Tab') {
  const elements=[...document.querySelectorAll<HTMLElement>('.modal button:not(:disabled),.modal input:not(:disabled),.modal select:not(:disabled),.modal textarea:not(:disabled)')].filter(e=>e.offsetParent!==null)
  const first=elements[0],last=elements[elements.length-1]
  if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus()}
  if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus()}
 }
}
onMounted(async()=>{
 notificationTimer=setInterval(()=>void refreshNotifications(),30000)
 layoutMedia.addEventListener('change',layoutChanged)
 window.addEventListener('beforeunload',beforeLeave);window.addEventListener('session-expired',expired);window.addEventListener('hashchange',hashChange);window.addEventListener('keydown',keydown)
 try {await csrf();demoMode.value=(await api<{demo:boolean}>('/auth/config')).demo;if(demoMode.value)demo('admin');const res=await fetch('/api/auth/me',{signal:AbortSignal.timeout(15000)});if(res.ok){account.value=await res.json();if(!nav.value.some(n=>n.id===page.value))navigate(account.value?.mustChangePassword?'profile':'overview');await refresh()}}catch{loginError.value='无法连接服务，请先启动后端'}finally{ready.value=true}
})
onUnmounted(()=>{clearTimeout(listTimer);clearInterval(notificationTimer);layoutMedia.removeEventListener('change',layoutChanged);window.removeEventListener('beforeunload',beforeLeave);window.removeEventListener('session-expired',expired);window.removeEventListener('hashchange',hashChange);window.removeEventListener('keydown',keydown);clearTimeout(toastTimer)})
</script>

<template>
 <GuestPreview v-if="guestPreview" @close="guestPreview=false"/>
 <div v-else-if="!ready" class="boot"><LoaderCircle class="spin"/> 正在连接轻舟工作空间…</div>
 <div v-else-if="!account" class="login-page">
  <section class="login-story">
   <a class="brand" href="#"><span class="brand-mark"><Layers :size="25"/></span><span>轻舟<span class="brand-en">QINGZHOU</span></span></a>
   <div class="story-copy"><span class="eyebrow">PROCUREMENT, CONNECTED.</span><h1>让每一份需求，<br>有序抵达。</h1><p>从采购申请到物资入库，<br>连接团队、采购与仓储的每一步。</p></div>
   <div class="story-flow"><div><ClipboardList/><span>提出需求</span></div><i></i><div><ShieldCheck/><span>协同审批</span></div><i></i><div><Package/><span>交付入库</span></div></div>
   <div class="story-bottom"><span>轻舟 · 企业采购与库存协同</span><span>让协作自然发生</span></div>
  </section>
  <main class="login-main"><div class="login-card"><span class="mini-label">轻舟采购协同</span><h2>登录工作空间</h2><p class="muted">使用团队管理员分配的账号登录。</p>
   <form @submit.prevent="login"><label>账号<input v-model="username" name="username" autocomplete="username" required placeholder="请输入账号"/></label><label>密码<div class="password-field"><input v-model="password" :type="showPassword?'text':'password'" name="password" autocomplete="current-password" required placeholder="请输入密码"/><button type="button" :aria-label="showPassword?'隐藏密码':'显示密码'" @click="showPassword=!showPassword"><EyeOff v-if="showPassword" :size="18"/><Eye v-else :size="18"/></button></div></label><p v-if="loginError" class="form-error" role="alert">{{loginError}}</p><button class="btn primary login-submit" :disabled="busy"><LoaderCircle v-if="busy" class="spin" :size="17"/>登录工作空间<ArrowRight v-if="!busy" :size="17"/></button></form>
   <section class="login-preview"><div><strong>第一次了解轻舟？</strong><p>用示例数据体验采购、审批与入库。</p></div><button class="btn" :disabled="busy" @click="password='';showPassword=false;guestPreview=true">免登录体验<ArrowRight :size="16"/></button></section>
   <div v-if="demoMode" class="demo-accounts"><span>本地演示 · 选择角色体验</span><div><button v-for="(name,key) in {admin:'管理员',employee:'员工',manager:'主管',buyer:'采购',warehouse:'仓管'}" :key="key" :class="{selected:username===key}" @click="demo(String(key))">{{name}}</button></div><small>演示密码：Qingzhou@2026</small></div>
  </div><SiteFiling/></main>
 </div>
 <div v-else class="app-shell">
  <div v-if="mobileMenu" class="sidebar-scrim" @click="mobileMenu=false"></div>
  <aside class="sidebar" :class="{open:mobileMenu}" :inert="narrow&&!mobileMenu">
   <a class="brand" href="#overview" @click.prevent="navigate('overview')"><span class="brand-mark"><Layers :size="24"/></span><span>轻舟<span class="brand-en">QINGZHOU</span></span></a>
   <div class="workspace-label"><span class="workspace-avatar">舟</span><div>轻舟协同工作空间<small>采购与库存管理</small></div><ChevronRight :size="14"/></div>
   <nav aria-label="主导航"><template v-for="item in nav" :key="item.id"><span v-if="item.group" class="nav-group">{{item.group}}</span><button :class="{active:page===item.id}" @click="navigate(item.id)"><component :is="item.icon" :size="19"/><span>{{item.name}}</span><span v-if="item.id==='notifications'&&unreadNotifications" class="nav-count">{{unreadNotifications}}</span><span v-if="item.id==='requests'&&overview.pending" class="nav-count">{{overview.pending}}</span></button></template></nav>
   <div class="sidebar-note"><span class="live-dot"></span>业务数据已连接<small>每一步操作，实时留痕。</small></div>
   <div class="user-card"><span class="avatar">{{account.name.slice(-2)}}</span><div><strong>{{account.name}}</strong><small>{{roleNames[account.role]}}</small></div><button title="退出登录" aria-label="退出登录" @click="logout"><LogOut :size="17"/></button></div>
  </aside>
  <div class="main-shell">
   <header class="topbar"><div class="breadcrumbs"><button class="icon-button mobile-only" aria-label="展开导航" @click="mobileMenu=true"><Menu :size="20"/></button><span>工作空间</span><ChevronRight :size="14"/><strong>{{currentNav.name}}</strong></div><div class="topbar-right"><button v-if="!account.mustChangePassword" class="btn compact" @click="openModal('agent')">轻舟智能体</button><span class="local-tag"><span class="live-dot"></span>{{demoMode?'本地工作空间':'团队工作空间'}}</span><button class="icon-button" aria-label="使用指南" @click="openModal('help')"><CircleHelp :size="19"/></button><button class="icon-button" aria-label="查看待办" @click="navigate('notifications')"><Bell :size="19"/><span v-if="unreadNotifications" class="notification-dot"></span></button><button class="avatar small icon-button" aria-label="打开个人中心" @click="navigate('profile')">{{account.name.slice(-2)}}</button></div></header>
   <main id="main" class="page-content">
    <div class="page-heading"><div><span class="mini-label">{{page==='overview'?today:'轻舟 / '+currentNav.name}}</span><h1>{{page==='overview'?account.name+'，工作顺利':currentNav.name}}<span v-if="page==='overview'" class="greeting-dot">.</span></h1><p>{{subtitle[page]||({profile:'维护个人资料和登录密码。',notifications:'采购进展、库存提醒与待办通知。','stock-tools':'实物盘点、库存差异与领用归还。',backups:'本机稳定运行、备份与恢复。'} as Record<string,string>)[page]}}</p></div><div class="heading-actions"><button v-if="['overview','requests','orders','products','inventory','suppliers','users','audit','movements','stock-tools'].includes(page)" class="btn" aria-label="刷新数据" :disabled="loading" @click="refresh()"><RefreshCw :size="16" :class="{spin:loading}"/><span class="refresh-label">刷新数据</span></button><button v-if="(page==='overview'||page==='requests')&&has('EMPLOYEE','MANAGER','ADMIN')" class="btn primary" @click="openModal('request')"><Plus :size="17"/>新建采购申请</button><button v-if="page==='inventory'&&has('WAREHOUSE','ADMIN')" class="btn primary" @click="openModal('issue')"><ArrowUpRight :size="17"/>领用出库</button><button v-if="page==='products'&&has('BUYER','ADMIN')" class="btn primary" @click="openModal('product')"><Plus :size="17"/>新增商品</button><button v-if="page==='suppliers'" class="btn primary" @click="openModal('supplier')"><Plus :size="17"/>新增供应商</button><button v-if="page==='users'" class="btn primary" @click="openModal('user')"><Plus :size="17"/>新增成员</button></div></div>

    <p v-if="loading" class="form-hint" role="status">正在读取最新数据…</p><div v-if="loadError" class="form-error" role="alert">数据读取失败：{{loadError}}。当前列表可能尚未更新。<button class="btn" @click="refresh()">重新加载</button></div>
    <template v-if="page==='overview'">
     <div class="metric-grid"><article class="metric"><div><span>有效采购申请金额</span><ShoppingCart :size="18"/></div><strong>{{money(overview.amount)}}<small>CNY</small></strong><p>当前可见申请 · 不含已驳回</p></article><article class="metric"><div><span>待审批申请</span><Clock3 :size="18"/></div><strong>{{overview.pending}}<small>笔</small></strong><p><span class="tiny-dot amber"></span>等待指定负责人确认采购需求</p></article><article class="metric"><div><span>{{overview.openOrders===null?'采购申请总数':'待收货订单'}}</span><Box :size="18"/></div><strong>{{overview.openOrders??overview.requests}}<small>笔</small></strong><p>{{overview.openOrders===null?'当前账号可见的采购申请':'已下单，等待仓库确认入库'}}</p></article><article class="metric warning-metric"><div><span>库存预警</span><AlertCircle :size="18"/></div><strong>{{overview.lowStock}}<small>项</small></strong><button class="text-button" @click="navigate('inventory');stockFilter=true">低于安全库存，查看详情<ArrowRight :size="13"/></button></article></div>
     <section class="panel feature-panel" aria-label="我的待办">
      <h2>我的待办</h2>
      <p class="muted">按当前角色显示，优先处理等待最久的单据。</p>
      <p v-if="!Object.keys(workQueue).length" class="muted">当前角色无需处理审批、下单或收货。</p>
      <div v-for="(tasks,kind) in workQueue" :key="kind" class="operation-history">
       <h3>{{taskTitles[kind]}} · {{queueTotals[kind]||0}} 笔</h3>
       <p v-if="!tasks?.length" class="muted">暂无待办。</p>
       <ul><li v-for="task in tasks" :key="task.id"><button class="text-button" @click="openTask(kind,task.id)">{{task.number}} · {{requestTypeLabel(task.request_type)}} · {{task.label}} · {{waitingDays(task.waiting_since)}}</button></li></ul>
       <div v-if="(queueTotals[kind]||0)>5" class="table-footer">
        <button class="btn compact" :disabled="queueBusy||(queuePages[kind]||1)===1" @click="changeQueuePage(kind,(queuePages[kind]||1)-1)">上一页</button>
        <span>{{queuePages[kind]||1}} / {{Math.ceil((queueTotals[kind]||0)/5)}}</span>
        <button class="btn compact" :disabled="queueBusy||(queuePages[kind]||1)*5>=(queueTotals[kind]||0)" @click="changeQueuePage(kind,(queuePages[kind]||1)+1)">{{queuePages[kind]?'下一页':'查看其余待办'}}</button>
       </div>
      </div>
     </section>
     <section class="panel flow-panel"><div class="panel-title"><div><h2>采购协作，一目了然</h2><p>从需求到交付的完整业务进度</p></div><span class="subtle-tag">当前可见申请</span></div><div class="procurement-flow"><template v-for="(item,index) in counts" :key="item.status"><button :class="'flow-step step-'+index" @click="navigate('requests');statusFilter=item.status"><span class="flow-icon"><ClipboardList v-if="index===0" :size="21"/><CheckCircle2 v-else-if="index===1" :size="21"/><ShoppingCart v-else-if="index===2" :size="21"/><Package v-else :size="21"/></span><div><span>{{statusNames[item.status]}}</span><strong>{{item.count}}<small>笔申请</small></strong></div><ArrowUpRight class="flow-link" :size="16"/></button><ChevronRight v-if="index<3" class="flow-arrow" :size="18"/></template></div></section>
     <div class="dashboard-columns"><section class="panel recent-panel"><div class="panel-title"><div><h2>最近采购申请<span class="count-label">{{requests.length}}</span></h2><p>关注团队最新需求与处理进展</p></div><button class="text-button" @click="navigate('requests')">查看全部<ArrowRight :size="14"/></button></div><div class="table-scroll"><table><thead><tr><th>采购内容</th><th>申请人</th><th class="align-right">金额</th><th>状态</th></tr></thead><tbody><tr v-for="r in requests.slice(0,5)" :key="r.id" class="clickable" tabindex="0" @click="openModal('detail',r)" @keydown.enter="openModal('detail',r)"><td><strong>{{r.product_name}}</strong><small class="document-number">{{r.number}} · {{requestTypeLabel(r.request_type)}}</small></td><td>{{r.applicant_name}}<small>{{r.department}}</small></td><td class="align-right numeric">{{money(r.amount)}}</td><td><span class="status" :class="r.status">{{statusNames[r.status]}}</span><small>{{requestNext(r)}}</small></td></tr><tr v-if="!requests.length"><td colspan="4" class="empty">还没有采购申请，创建第一笔需求吧。</td></tr></tbody></table></div></section>
      <section class="panel stock-alerts"><div class="panel-title"><div><h2><span class="tiny-dot amber"></span>需要补货</h2><p>由采购专员或管理员下单，仓库确认收货后更新库存</p></div><span class="count-label">{{lowProducts.length}}</span></div><div v-for="p in lowProducts.slice(0,4)" :key="p.id" class="stock-alert"><div class="product-symbol"><Package :size="20"/></div><div><strong>{{p.name}}</strong><p>剩余 <b>{{p.stock}}</b> {{p.unit}}<span>安全库存 {{p.min_stock}}</span></p><p v-for="progress in p.replenishment" :key="progress.status">{{replenishmentLabels[progress.status]}} {{progress.quantity}} {{p.unit}}</p><p v-if="p.replenishment?.length===0">可见范围内暂无补货在途或待办</p><div class="stock-track"><i :style="{width:Math.min(100,p.stock/p.min_stock*100)+'%'}"></i></div></div></div><div v-if="!lowProducts.length" class="empty">库存充足，暂无补货提醒。</div><button class="stock-more" @click="navigate('inventory');stockFilter=true">查看库存预警<ArrowRight :size="15"/></button></section></div>
     <section class="panel spending-panel"><div class="panel-title"><div><h2>采购投入分布</h2><p>按商品分类汇总有效采购申请金额</p></div><span class="subtle-tag">{{categorySpending.length}} 个商品分类</span></div><div class="spending-row" v-for="(c,index) in categorySpending" :key="c.name"><span><i :class="'category-dot dot-'+index"></i>{{c.name}}</span><div class="spending-track"><i :class="'bar-'+index" :style="{width:c.percent+'%'}"></i></div><b class="numeric">{{money(c.amount)}}</b><small>{{c.percent}}%</small></div></section>
    </template>

    <DraftList :key="draftListVersion" v-else-if="page==='drafts'" @open="openDraft"/><ProductImport v-else-if="page==='product-import'"/><PersonalCenter v-else-if="page==='profile'" @signed-out="signedOut" @updated="profileUpdated"/><NotificationCenter v-else-if="page==='notifications'" @navigate="openNotification" @changed="refreshNotifications"/><StockTools ref="stockTools" v-else-if="page==='stock-tools'" :products="products" @changed="refresh(true)"/><BackupCenter v-else-if="page==='backups'"/>
    <div v-if="['products','inventory','requests','orders'].includes(page)" class="panel" style="padding:12px 18px;margin-bottom:16px;display:flex;gap:12px;align-items:center;flex-wrap:wrap">
 <button class="btn compact" :disabled="pageNumber===1||loading" @click="pageNumber--">上一页</button>
 <span>{{pageNumber}} / {{totalPages}} 页 · 共 {{total}} 条</span>
 <button class="btn compact" :disabled="pageNumber>=totalPages||loading" @click="pageNumber++">下一页</button>
 <select v-if="page==='products'||page==='inventory'" v-model="activeFilter" aria-label="商品状态"><option value="ALL">全部状态</option><option value="true">启用</option><option value="false">停用</option></select>
 <span v-if="loading" role="status">正在加载…</span>
 </div>
 <section v-if="page==='requests'" class="panel list-panel"><p class="form-hint">申请人提交 → 指定负责人审批 → 采购专员或管理员下单 → 仓库管理员或管理员确认收货</p><p class="small-copy muted">待审批时，申请人可撤回自己的申请。已提交的单据会保留历史记录。未提交的草稿可在「我的草稿」删除。</p><div class="filter-tabs"><button :class="{active:statusFilter==='ALL'}" @click="statusFilter='ALL';pageNumber=1">全部申请<span>{{statusCount('ALL')}}</span></button><button v-for="s in ['PENDING','APPROVED','ORDERED','RECEIVED','REJECTED','WITHDRAWN','CLOSED']" :key="s" :class="{active:statusFilter===s}" @click="statusFilter=s;pageNumber=1">{{statusNames[s]}}<span>{{statusCount(s)}}</span></button></div><div class="table-toolbar"><label class="search-field"><Search :size="17"/><input v-model="search" aria-label="搜索采购申请" placeholder="搜索商品、单号或申请人…" @input="pageNumber=1"/></label><select v-model="requestTypeFilter" aria-label="申请类型"><option value="ALL">全部类型</option><option value="PURCHASE">普通采购</option><option value="RESTOCK">库存补货</option><option value="LEGACY">历史未分类</option></select><button class="btn" :disabled="loading||!!loadError" @click="exportCsv"><Download :size="15"/>导出</button></div><div class="table-scroll"><table><thead><tr><th>申请编号 / 采购内容</th><th>申请人 / 部门</th><th class="align-right">数量</th><th class="align-right">申请金额</th><th>提交时间</th><th>状态</th><th class="align-right">操作</th></tr></thead><tbody><tr v-for="r in displayedRequests" :key="r.id"><td><button class="table-link" @click="openModal('detail',r)">{{r.product_name}}</button><small class="document-number">{{r.number}} · {{requestTypeLabel(r.request_type)}}</small></td><td>{{r.applicant_name}}<small>{{r.department}}</small></td><td class="align-right numeric">{{r.lines.length>1?r.lines.length+' 项明细':r.quantity+' '+r.unit}}</td><td class="align-right numeric">{{money(r.amount)}}</td><td class="date-cell">{{date(r.created_at)}}</td><td><span class="status" :class="r.status">{{statusNames[r.status]}}</span><small>{{requestNext(r)}}</small></td><td class="align-right"><button v-if="r.applicant_id===account.id&&['PENDING','REJECTED','WITHDRAWN'].includes(r.status)" class="text-button" @click="openModal('request',r)">{{r.status==='PENDING'?'修改申请':'修改重提'}}</button><button v-if="r.applicant_id===account.id&&r.status==='PENDING'" class="text-button" @click="openModal('withdraw',r)">撤回</button><button v-else-if="canReview(r)" class="text-button" @click="openModal('review',r)">审批<ArrowRight :size="13"/></button><button v-else-if="r.status==='APPROVED'&&has('BUYER','ADMIN')" class="text-button" :disabled="busy" @click="createOrder(r)">生成订单</button><button v-else class="text-button muted" @click="openModal('detail',r)">详情</button></td></tr><tr v-if="!filteredRequests.length&&!loading&&!loadError"><td colspan="7" class="empty"><ClipboardList :size="30"/><p>{{search?'没有找到匹配的申请，试试其他关键词。':'当前状态下暂无申请。'}}</p></td></tr></tbody></table></div><div class="table-footer"><span>共 {{total}} 条 · {{roleNames[account.role]}}可见范围</span><div><button class="btn compact" :disabled="pageNumber===1" @click="pageNumber--">上一页</button><span>{{pageNumber}} / {{totalPages}}</span><button class="btn compact" :disabled="pageNumber>=totalPages" @click="pageNumber++">下一页</button></div></div></section>

    <template v-else-if="page==='inventory'||page==='products'"><div v-if="page==='inventory'" class="inventory-banner"><div><Package :size="24"/><div><strong>{{total}} 项符合筛选的商品</strong><p>补货流程：提交采购申请，经审批后由采购专员或管理员下单，仓库管理员或管理员确认入库。</p></div></div><div v-if="overview.stockValue!==null"><span>当前库存参考价值</span><b class="numeric">{{money(overview.stockValue)}}</b></div></div><section class="panel list-panel"><div class="table-toolbar"><div class="toolbar-filters"><label class="search-field"><Search :size="17"/><input v-model="search" aria-label="搜索商品" placeholder="搜索商品名称、SKU…"/></label><select v-model="categoryFilter" aria-label="商品分类"><option value="ALL">全部分类</option><option v-for="c in categories" :key="c">{{c}}</option></select><label v-if="page==='inventory'" class="checkbox-label"><input type="checkbox" v-model="stockFilter"/>仅看库存预警</label></div><button class="btn" :disabled="loading||!!loadError" @click="exportCsv"><Download :size="15"/>导出</button></div><div class="table-scroll"><table><thead><tr><th>商品名称 / SKU</th><th>分类</th><th class="align-right">参考单价</th><th class="align-right">可用库存</th><th class="align-right">安全库存</th><th>{{page==='products'?'供应商':'库存状态'}}</th><th v-if="(page==='inventory'&&has('WAREHOUSE','ADMIN'))||(page==='products'&&has('BUYER','ADMIN'))" class="align-right">操作</th></tr></thead><tbody><tr v-for="p in filteredProducts" :key="p.id"><td><div class="product-cell"><span class="product-symbol"><Package :size="19"/></span><div><strong>{{p.name}}</strong><small>{{p.sku}} · {{p.enabled?'启用':'已停用'}}</small></div></div></td><td><span class="category-tag">{{p.category}}</span></td><td class="align-right numeric">{{money(p.price)}}</td><td class="align-right numeric" :class="{shortage:p.stock<p.min_stock}"><b>{{p.stock}}</b> {{p.unit}}</td><td class="align-right numeric">{{p.min_stock}} {{p.unit}}</td><td v-if="page==='products'">{{p.supplier_name}}</td><td v-else><span class="status" :class="p.stock<p.min_stock?'PENDING':'RECEIVED'">{{p.stock<p.min_stock?'需要补货':'库存充足'}}</span><template v-if="p.stock<p.min_stock"><small>距安全库存差 {{p.min_stock-p.stock}} {{p.unit}}</small><small>扣除可见待到货后参考缺口 {{restockGap(p).suggested}} {{p.unit}}</small><small v-for="progress in p.replenishment" :key="progress.status">{{replenishmentLabels[progress.status]}} {{progress.quantity}} {{p.unit}}</small><small v-if="p.replenishment?.length===0">可见范围内暂无补货在途或待办</small><small v-if="!p.enabled||!canRequestStock()">{{p.enabled?(has('BUYER')?'请跟进已审批申请并下单；没有申请时联系申请人或管理员':'请联系申请人或管理员发起采购申请'):'商品已停用，恢复启用后可申请补货'}}</small></template><div class="row-actions inventory-actions"><button v-if="p.stock<p.min_stock&&p.enabled&&canRequestStock()" class="btn row-action" @click="openModal('request',undefined,p)">发起补货申请</button><button class="btn row-action" @click="openModal('assistant',p)">AI 补货助手</button></div></td><td v-if="page==='products'&&has('BUYER','ADMIN')" class="align-right"><button class="text-button" @click="openModal('product',p)">编辑资料</button><button class="text-button" :disabled="busy" @click="toggleProduct(p)">{{p.enabled?'停用':'恢复'}}</button></td><td v-if="page==='inventory'&&has('WAREHOUSE','ADMIN')" class="align-right"><button class="text-button" :disabled="!p.stock" @click="openModal('issue',p)">领用出库</button></td></tr><tr v-if="!filteredProducts.length&&!loading&&!loadError"><td colspan="7" class="empty">没有符合筛选条件的商品。</td></tr></tbody></table></div><div class="table-footer"><span>共 {{total}} 项商品</span><span>参考价值按商品当前单价计算</span></div></section></template>

    <section v-else-if="page==='orders'" class="panel list-panel"><div class="filter-tabs"><button v-for="s in ['ALL','OPEN','PARTIAL','RETURNED','RECEIVED','CANCELLED','TERMINATED']" :key="s" :class="{active:statusFilter===s}" @click="statusFilter=s">{{s==='ALL'?'全部订单':statusNames[s]}}<span>{{statusCount(s)}}</span></button></div><div class="table-toolbar"><label class="search-field"><Search :size="17"/><input v-model="search" aria-label="搜索订单" placeholder="搜索订单、商品或供应商…"/></label><select v-model="requestTypeFilter" aria-label="订单来源类型"><option value="ALL">全部来源</option><option value="PURCHASE">普通采购</option><option value="RESTOCK">库存补货</option><option value="LEGACY">历史未分类</option></select><button class="btn" :disabled="loading||!!loadError" @click="exportCsv"><Download :size="15"/>导出订单明细</button></div><div class="table-scroll"><table><thead><tr><th>订单 / 商品</th><th>供应商</th><th class="align-right">数量</th><th class="align-right">金额</th><th>采购人</th><th>状态</th><th class="align-right">操作</th></tr></thead><tbody><tr v-for="o in filteredOrders" :key="o.id"><td><button class="table-link" @click="openModal('orderDetail',o)">{{o.product_name}}</button><small class="document-number">{{o.number}} · {{requestTypeLabel(o.request_type)}}</small></td><td>{{o.supplier_name}}</td><td class="align-right">{{o.lines.length>1?o.lines.length+' 项明细':o.quantity+' '+o.unit}}</td><td class="align-right numeric">{{money(o.amount)}}</td><td>{{o.buyer_name}}</td><td><span class="status" :class="o.fulfillment_status">{{statusNames[o.fulfillment_status]}}</span></td><td class="align-right"><div class="toolbar-filters" v-if="has('WAREHOUSE','ADMIN','BUYER')"><button v-if="o.lifecycle_state==='ACTIVE'&&has('WAREHOUSE','ADMIN')&&o.lines.some(l=>l.remaining>0)" class="text-button" @click="openModal('receive',o)">分批收货</button><button v-if="has('WAREHOUSE','ADMIN')&&o.lines.some(l=>l.net_received>0)" class="text-button" @click="openModal('purchaseReturn',o)">采购退货</button><button v-if="has('BUYER','ADMIN')&&o.lifecycle_state==='ACTIVE'&&o.lines.some(l=>l.remaining>0)" class="text-button" @click="openModal('closeOrder',o)">{{o.lines.some(l=>l.received_quantity>0)?'终止余量':'取消订单'}}</button></div><span v-else class="muted">{{o.status==='RECEIVED'?'已收齐':'等待仓库收货'}}</span></td></tr><tr v-if="!filteredOrders.length&&!loading&&!loadError"><td colspan="7" class="empty">暂无订单，采购人员可从已通过的申请生成订单。</td></tr></tbody></table></div><div class="table-footer">共 {{total}} 笔订单</div></section>

    <section v-else-if="page==='movements'" class="panel list-panel"><div class="table-toolbar"><label class="search-field"><Search :size="17"/><input v-model="search" aria-label="搜索库存流水" placeholder="搜索商品或来源单据…"/></label><label>开始日期 <input type="date" v-model="historyFrom"/></label><label>结束日期 <input type="date" v-model="historyTo"/></label><input v-model="historyOperator" aria-label="操作人" placeholder="操作人姓名或账号"/><button class="btn" :disabled="loading||!!loadError" @click="exportCsv"><Download :size="15"/>导出</button></div><div class="table-scroll"><table><thead><tr><th>商品</th><th>类型</th><th class="align-right">变动数量</th><th class="align-right">变动后结存</th><th>来源 / 领用说明</th><th>操作人</th><th>时间</th></tr></thead><tbody><tr v-for="m in movements" :key="m.id"><td><strong>{{m.product_name}}</strong><small>{{m.sku}}</small></td><td><span class="status" :class="m.type==='OUT'?'PENDING':'RECEIVED'">{{movementNames[m.business_type]||m.type}}</span></td><td class="align-right numeric" :class="m.quantity<0?'shortage':'positive'">{{m.quantity>0?'+':''}}{{m.quantity}} {{m.unit}}</td><td class="align-right numeric">{{m.balance}} {{m.unit}}</td><td class="reference-cell">{{m.reference}}</td><td>{{m.operator_name}}</td><td class="date-cell">{{date(m.created_at,true)}}</td></tr><tr v-if="!movements.length&&!loading&&!loadError"><td colspan="7" class="empty">暂无匹配的出入库流水。</td></tr></tbody></table></div><div class="table-footer"><span>共 {{total}} 条</span><div><button class="btn compact" :disabled="loading||pageNumber<=1" @click="pageNumber--">上一页</button><span>{{pageNumber}} / {{totalPages}}</span><button class="btn compact" :disabled="loading||pageNumber>=totalPages" @click="pageNumber++">下一页</button></div></div></section>

    <section v-else-if="page==='suppliers'" class="panel list-panel"><div class="table-toolbar"><label class="search-field"><Search :size="17"/><input v-model="search" aria-label="搜索供应商" placeholder="搜索供应商或联系人…"/></label><span class="muted">{{suppliers.length}} 家合作伙伴</span></div><div class="table-scroll"><table><thead><tr><th>供应商名称</th><th>联系人</th><th>联系电话</th><th>关联商品</th><th class="align-right">操作</th></tr></thead><tbody><tr v-for="s in suppliers.filter(s=>matches(s.name,s.contact,s.phone))" :key="s.id"><td><div class="product-cell"><span class="product-symbol"><Building2 :size="19"/></span><strong>{{s.name}}</strong></div></td><td>{{s.contact}}</td><td class="numeric">{{s.phone}}</td><td>{{s.product_count}} 项</td><td class="align-right"><button class="text-button" @click="openModal('supplier',s)">编辑资料</button></td></tr><tr v-if="!suppliers.filter(s=>matches(s.name,s.contact,s.phone)).length&&!loading&&!loadError"><td colspan="5" class="empty">暂无匹配的供应商。</td></tr></tbody></table></div></section>

    <section v-else-if="page==='users'" class="panel list-panel"><div class="table-toolbar"><label class="search-field"><Search :size="17"/><input v-model="search" aria-label="搜索成员" placeholder="搜索姓名、账号或部门…"/></label><div class="toolbar-filters"><select v-model="memberStatus" aria-label="成员状态"><option value="ALL">全部状态</option><option value="ENABLED">仅看启用</option><option value="DISABLED">仅看停用</option></select><span class="muted">{{filteredUsers.length}} 位团队成员</span></div></div><div class="table-scroll"><table><thead><tr><th>团队成员</th><th>账号</th><th>所属部门</th><th>角色</th><th>账号状态</th><th class="align-right">操作</th></tr></thead><tbody><tr v-for="u in filteredUsers" :key="u.id"><td><div class="product-cell"><span class="avatar small">{{u.display_name.slice(-2)}}</span><strong>{{u.display_name}}</strong><span v-if="u.id===account.id" class="subtle-tag">我</span></div></td><td class="numeric">{{u.username}}</td><td>{{u.department}}</td><td><span class="category-tag">{{roleNames[u.role]}}</span></td><td><span class="status" :class="u.enabled?'RECEIVED':'REJECTED'">{{u.enabled?'启用':'已停用'}}</span></td><td class="align-right"><div class="row-actions member-actions"><button class="btn row-action" @click="openModal('user',u)">编辑成员</button><button v-if="u.role!=='ADMIN'" class="btn row-action" @click="openModal('resetMember',u)">重置密码</button><button v-if="u.id!==account.id" class="btn row-action row-action-danger" @click="openModal('removeMember',u)">删除成员</button></div></td></tr><tr v-if="!filteredUsers.length&&!loading&&!loadError"><td colspan="6" class="empty">暂无匹配的成员。</td></tr></tbody></table></div></section>

    <section v-else-if="page==='audit'" class="panel list-panel"><div class="table-toolbar"><label class="search-field"><Search :size="17"/><input v-model="search" aria-label="搜索操作日志" placeholder="搜索操作、成员或记录…"/></label><label>开始日期 <input type="date" v-model="historyFrom"/></label><label>结束日期 <input type="date" v-model="historyTo"/></label><input v-model="historyOperator" aria-label="操作人" placeholder="操作人姓名或账号"/><button class="btn" :disabled="loading||!!loadError" @click="exportCsv"><Download :size="15"/>导出筛选记录</button></div><div class="table-scroll"><table><thead><tr><th>操作人</th><th>操作</th><th>关联记录</th><th>操作详情</th><th>时间</th></tr></thead><tbody><tr v-for="a in audits" :key="a.id"><td>{{a.actor_name}}</td><td><span class="audit-action"><ShieldCheck :size="15"/>{{a.action}}</span></td><td class="reference-cell">{{a.target}}</td><td>{{a.detail||'—'}}</td><td class="date-cell">{{date(a.created_at,true)}}</td></tr><tr v-if="!audits.length&&!loading&&!loadError"><td colspan="5" class="empty">暂无匹配的操作记录。</td></tr></tbody></table></div><div class="table-footer"><span>共 {{total}} 条</span><div><button class="btn compact" :disabled="loading||pageNumber<=1" @click="pageNumber--">上一页</button><span>{{pageNumber}} / {{totalPages}}</span><button class="btn compact" :disabled="loading||pageNumber>=totalPages" @click="pageNumber++">下一页</button></div></div></section>
    <footer class="page-footer"><span>轻舟 · 让采购与库存有序协同</span><span>数据权限与操作记录已启用<ShieldCheck :size="13"/></span></footer>
    <SiteFiling/>
   </main>
  </div>
 </div>

 <Teleport to="body"><div v-if="modal" class="modal-overlay" @click.self="closeModal"><section class="modal" :class="{'wide-modal':['agent','assistant','detail','receive','purchaseReturn','orderDetail','request'].includes(modal)}" role="dialog" aria-modal="true" aria-labelledby="modal-title"><header><div><span class="mini-label">轻舟采购协同</span><h2 id="modal-title">{{modalTitle}}</h2></div><button class="icon-button" :disabled="busy" aria-label="关闭弹窗" @click="closeModal"><X :size="20"/></button></header>
  <div v-if="leavePrompt" class="form-hint leave-warning" role="alert" style="margin:16px 24px"><p>{{uncertain?'提交结果尚未确认，建议先查询提交结果。':'内容尚未保存，离开会丢失未保存的编辑。'}}</p><button class="btn" type="button" @click="leavePrompt=false;pendingNavigation=''">继续编辑</button><button class="btn" type="button" @click="discardEdit">放弃编辑并离开</button></div>
  <div v-if="modal==='help'" class="modal-body help-content"><p>按角色体验一笔完整采购：</p><ol><li><b>员工</b>：在采购申请中创建需求。</li><li><b>主管</b>：审批本部门其他成员提交的申请。</li><li><b>采购</b>：为已通过的申请生成采购订单。</li><li><b>仓管</b>：确认订单收货，商品库存自动增加。</li><li><b>仓管</b>：登记领用出库，在流水中查看库存变化。</li></ol><p>新申请必须指定审批负责人；只有指定人可以审批，不能审批自己提交的申请。退出登录后，可在登录页选择其他演示角色。</p><button class="btn primary" @click="closeModal">知道了</button></div>
  <InventoryAgent v-else-if="modal==='agent'" :can-draft="canRequestStock()" @draft="fillAssistantDraft" @task="openTask" @busy="busy=$event"/>
  <InventoryAssistant v-else-if="modal==='assistant'&&assistantProduct" :key="assistantProduct.id" :product="assistantProduct" :can-draft="canRequestStock()" @draft="fillAssistantDraft" @busy="busy=$event"/>
  <div v-else-if="modal==='detail'&&selectedRequest" class="modal-body"><div class="detail-banner"><div><strong>{{selectedRequest.product_name}}</strong><span class="document-number">{{selectedRequest.number}}</span></div><span class="status" :class="selectedRequest.status">{{statusNames[selectedRequest.status]}}</span></div><dl class="detail-grid"><div><dt>申请人</dt><dd>{{selectedRequest.applicant_name}}</dd></div><div><dt>所属部门</dt><dd>{{selectedRequest.department}}</dd></div><div><dt>申请类型</dt><dd>{{requestTypeLabel(selectedRequest.request_type)}}</dd></div><div><dt>当前待办</dt><dd>{{requestNext(selectedRequest)}}</dd></div><div><dt>审批负责人</dt><dd>{{selectedRequest.approver_name||'历史申请未指定负责人'}}<small v-if="selectedRequest.approver_username">（{{selectedRequest.approver_username}}）</small></dd></div><div><dt>商品明细</dt><dd>{{selectedRequest.lines.length}} 项商品</dd></div><div><dt>申请金额</dt><dd class="numeric">{{money(selectedRequest.amount)}}</dd></div><div><dt>提交时间</dt><dd>{{date(selectedRequest.created_at,true)}}</dd></div><div><dt>最近更新</dt><dd>{{date(selectedRequest.updated_at,true)}}</dd></div></dl><div class="table-scroll"><table><thead><tr><th>商品</th><th>数量</th><th>单价</th><th>金额</th></tr></thead><tbody><tr v-for="line in selectedRequest.lines" :key="line.id"><td>{{line.product_name}}</td><td>{{line.quantity}} {{line.unit}}</td><td>{{money(line.unit_price)}}</td><td>{{money(line.amount)}}</td></tr></tbody></table></div><div class="detail-reason"><span>采购用途</span><p>{{selectedRequest.reason}}</p></div><div v-if="selectedRequest.review_note" class="detail-reason"><span>审批意见</span><p>{{selectedRequest.review_note}}</p></div><RelatedOrders v-if="['ORDERED','RECEIVED','CLOSED'].includes(selectedRequest.status)" :key="selectedRequest.id" :request-id="selectedRequest.id" :statuses="statusNames" :can-open="has('BUYER','WAREHOUSE','ADMIN')" @open="openLinkedOrder"/><footer class="modal-footer"><button class="btn" @click="closeModal">关闭详情</button><button v-if="selectedRequest.status==='APPROVED'&&has('BUYER','ADMIN')" class="btn primary" :disabled="busy" @click="createOrder(selectedRequest)">生成采购订单</button><button v-if="selectedRequest.applicant_id===account?.id&&['PENDING','REJECTED'].includes(selectedRequest.status)" class="btn primary" @click="openModal('request',selectedRequest)">{{selectedRequest.status==='REJECTED'?'修改并重新提交':'修改申请'}}</button><button v-if="selectedRequest.status==='PENDING'&&selectedRequest.applicant_id===account?.id" class="btn" @click="openModal('withdraw',selectedRequest)">撤回申请</button><button v-if="has('ADMIN')&&selectedRequest.status==='PENDING'" class="btn" @click="openModal('reassign',selectedRequest)">改派审批</button><button v-if="canReview(selectedRequest)" class="btn primary" @click="openModal('review',selectedRequest)">处理审批<ArrowRight :size="16"/></button></footer></div>
  <div v-else-if="modal==='orderDetail'&&selectedOrder" class="modal-body"><FulfillmentEditor :key="selectedOrder.id" v-model="fulfillmentForm" :order="selectedOrder" readonly/><footer class="modal-footer"><button v-if="selectedOrder.request_id" class="btn" @click="openSourceRequest(selectedOrder.request_id)">查看原申请</button><button class="btn" @click="closeModal">关闭详情</button></footer></div><form v-else class="modal-body" @submit.prevent="submit"><fieldset :disabled="busy||uncertain||leavePrompt" style="border:0;padding:0;margin:0;min-width:0">
   <template v-if="modal==='removeMember'&&resetMember"><p class="form-intro">确定删除 {{resetMember.display_name}}（{{resetMember.username}}）？删除后该账号不能登录，也不会出现在成员列表；历史申请、审批和库存记录会保留。</p><div class="form-hint">若只是暂时离开团队，请在“编辑成员”中选择停用。删除后不提供页面恢复，原登录账号也不能重新注册。待审批任务需先处理。</div><label>删除原因<textarea v-model="removalReason" maxlength="300" required rows="2" placeholder="例如：成员离职或测试账号清理"/></label><label>输入登录账号 {{resetMember.username}} 确认删除<input v-model="removalUsername" maxlength="64" required autocomplete="off"/></label></template>
   <template v-if="modal==='resetMember'&&resetMember"><p class="form-intro">重置 {{resetMember.display_name}}（{{resetMember.username}}）的登录密码。旧登录立即失效，成员下次登录必须修改密码。</p><label>新临时密码<input v-model="resetPassword" type="password" autocomplete="new-password" minlength="8" maxlength="64" required/></label><label>确认临时密码<input v-model="resetConfirm" type="password" autocomplete="new-password" minlength="8" maxlength="64" required/></label></template>
   <template v-if="modal==='reassign'&&selectedRequest">
    <p class="form-intro">{{selectedRequest.number}} · 当前负责人 {{selectedRequest.approver_name||'未指定'}}</p>
    <p class="form-hint">{{approvalMessage}}</p>
    <label>新审批负责人<select v-model="reassignId" required><option :value="null">请选择</option><option v-for="person in approvers" :key="person.id" :value="person.id">{{person.display_name}} · {{person.department}}（{{person.username}}）</option></select></label>
    <label>改派原因<textarea v-model="transitionReason" maxlength="300" required rows="3"/></label>
   </template><template v-if="modal==='withdraw'||modal==='closeOrder'"><p class="form-intro">{{modal==='withdraw'?'撤回后停止审批，可修改后重新提交。':'未收货订单将取消；有收货记录则终止剩余交付。已收退数量、库存和历史记录保留。'}}</p><label>操作原因<textarea v-model="transitionReason" maxlength="300" required rows="3"/></label></template>
   <template v-if="modal==='request'"><p class="form-intro">{{editing?'保存后按当前商品单价重新计算金额，并进入待审批状态。':'填写物资需求，提交后通知你选择的审批负责人。'}}</p><div v-if="editing?.status==='REJECTED'&&selectedRequest?.review_note" class="review-summary"><strong>上次驳回原因</strong><p>{{selectedRequest.review_note}}</p></div><p v-if="restockHint" class="form-hint">{{restockHint}}</p><p v-if="restockRequest" class="form-hint">补货使用采购审批流程，通过后由采购下单，仓库收货后增加库存。</p><p class="small-copy muted">草稿仅自己可见。提交后，申请人、同部门主管及有业务查看权限的角色可按权限查看，审批待办只发给指定负责人。</p><PurchaseLines v-model="requestForm.lines" :products="pickerProducts"/><label>审批负责人<select v-model="requestForm.approverId" required><option :value="null" disabled>请选择一位审批负责人</option><option v-for="p in approvers" :key="p.id" :value="p.id">{{p.display_name}}（{{p.username}}） · {{roleNames[p.role]}} · {{p.department}}</option></select></label><p class="form-hint" role="status">{{approvalMessage}}<span v-if="requestForm.approverId&&!approvers.some(p=>p.id===requestForm.approverId)"> 原审批人已不可选，请重新指定。</span></p><button type="button" class="btn" @click="refreshApprovers">刷新审批人员</button><label>采购用途<textarea v-model="requestForm.reason" required maxlength="500" rows="3" placeholder="例如：新增研发工位，供新同事入职使用"></textarea></label><div class="form-hint"><ShieldCheck :size="16"/>申请将以当前账号提交，价格以提交时商品单价为准。</div></template>
   <template v-if="modal==='review'&&selectedRequest"><div class="review-summary"><strong>{{selectedRequest.product_name}} · {{requestTypeLabel(selectedRequest.request_type)}}</strong><p>{{selectedRequest.applicant_name}} · {{selectedRequest.department}} · {{selectedRequest.lines.length}} 项商品</p><b>{{money(selectedRequest.amount)}}</b><p>{{selectedRequest.reason}}</p></div><RequestLinesTable :lines="selectedRequest.lines"/><label>审批结果<select v-model="decision"><option value="APPROVED">通过申请</option><option value="REJECTED">驳回申请</option></select></label><label>审批意见<span class="muted">{{decision==='REJECTED'?'（必填）':'（选填）'}}</span><textarea v-model="reviewNote" :required="decision==='REJECTED'" maxlength="500" rows="3" placeholder="填写审批意见，方便申请人跟进"></textarea></label></template>
   <template v-if="(modal==='receive'||modal==='purchaseReturn')&&selectedOrder"><FulfillmentEditor v-model="fulfillmentForm" :order="selectedOrder" :returning="modal==='purchaseReturn'"/></template>
   <template v-if="modal==='issue'"><p class="form-intro">登记实际领用情况，库存与流水将同步更新。</p><label>领用商品<select v-model="issueForm.productId" required><option v-for="p in pickerProducts" :key="p.id" :value="p.id">{{p.name}} · 可用 {{p.stock}} {{p.unit}}</option></select></label><label>领用数量<input v-model.number="issueForm.quantity" type="number" min="1" :max="issueProduct?.stock||0" required/></label><label>领用人 / 用途<input v-model="issueForm.reason" maxlength="100" required placeholder="例如：林小满 / 产品研发部新员工工位"/></label><div class="form-hint"><AlertCircle :size="16"/>出库后即时扣减库存，请核对领用信息。</div></template>
   <template v-if="modal==='product'"><div v-if="editing" class="form-hint"><ShieldCheck :size="16"/>修改参考价只影响后续申请；已有业务记录的计量单位不能更改。</div><label>商品名称<input v-model="productForm.name" maxlength="120" required placeholder="输入完整商品名称"/></label><div class="form-row"><label>商品 SKU<input v-model="productForm.sku" pattern="[A-Za-z0-9_-]+" maxlength="40" required placeholder="例如 IT-004"/></label><label>分类<select v-model="productForm.category"><option>办公设备</option><option>办公耗材</option><option>数码设备</option><option>其他物资</option></select></label></div><div class="form-row"><label>参考单价（元）<input v-model.number="productForm.price" type="number" step="0.01" min="0.01" max="99999999.99" required/></label><label>计量单位<input v-model="productForm.unit" maxlength="16" required/></label></div><label>安全库存<input v-model.number="productForm.minStock" type="number" min="0" max="100000" required/></label><label>供应商<select v-model="productForm.supplierId" required><option v-for="s in suppliers" :key="s.id" :value="s.id">{{s.name}}</option></select></label><div v-if="!suppliers.length" class="form-hint">请先在供应商页面创建合作伙伴。</div></template>
   <template v-if="modal==='supplier'"><label>供应商名称<input v-model="supplierForm.name" maxlength="120" required placeholder="输入公司完整名称"/></label><label>联系人<input v-model="supplierForm.contact" maxlength="64" required/></label><label>联系电话<input v-model="supplierForm.phone" maxlength="32" type="tel" required/></label></template>
   <template v-if="modal==='user'"><p v-if="memberImpactError" class="form-error">未能读取待审批关联：{{memberImpactError}}</p><div v-if="memberPending.length" class="form-hint">关联 {{memberPending[0]?.total}} 笔待审批申请。若此次变更会让审批人失去资格，请先在申请详情改派。<p>{{memberPending.map(row=>row.number).join('、')}}</p></div><div class="form-row"><label>成员姓名<input v-model="userForm.name" maxlength="64" required/></label><label>所属部门<input v-model="userForm.department" maxlength="64" required placeholder="例如 产品研发部"/></label></div><label>登录账号<input v-model="userForm.username" :readonly="!!editing" pattern="[a-zA-Z0-9_-]{3,64}" minlength="3" maxlength="64" required placeholder="3～64 位字母、数字、下划线" autocomplete="off"/></label><label>角色<select v-model="userForm.role" :disabled="editing?.id===account?.id"><option v-for="(name,key) in roleNames" :key="key" :value="key">{{name}}</option></select></label><label v-if="!editing">初始密码<input v-model="userForm.password" type="password" minlength="8" maxlength="64" required placeholder="至少 8 位" autocomplete="new-password"/></label><label v-if="editing">账号状态<select v-model="userForm.enabled" :disabled="editing.id===account?.id"><option :value="true">启用</option><option :value="false">停用</option></select></label><div class="form-hint"><ShieldCheck :size="16"/>{{editing?'保存后该成员的已有登录将失效；不能停用自己或移除自己的管理员角色。':'主管只能审批部门名称相同的成员申请。'}}</div></template>
   </fieldset><p v-if="formError" class="form-error" role="alert"><AlertCircle :size="16"/>{{formError}}</p><div v-if="uncertain" class="form-hint"><button type="button" class="btn" @click="checkOutcome">查询提交结果</button><button type="button" class="btn" :disabled="busy||!pendingKey" @click="submit">使用相同内容重试</button></div><button v-if="conflict" type="button" class="btn" @click="reloadConflict">读取最新版本并保留输入</button><footer class="modal-footer"><button class="btn" type="button" :disabled="busy" @click="closeModal">取消</button><button v-if="modal==='request'&&!editing" type="button" class="btn" :disabled="busy||uncertain||leavePrompt" @click="saveDraft">保存草稿</button><button class="btn primary" :disabled="busy||uncertain||conflict||leavePrompt||(modal==='request'&&(!requestForm.approverId||!approvers.some(p=>p.id===requestForm.approverId)))||(modal==='product'&&!suppliers.length)||(modal==='removeMember'&&removalUsername.trim()!==resetMember?.username)"><LoaderCircle v-if="busy" class="spin" :size="16"/><Check v-else :size="16"/>{{submitTitle}}</button></footer>
  </form>
 </section></div><Transition name="toast"><div v-if="toast" class="toast-message" :class="{error:toast.error}" role="status"><AlertCircle v-if="toast.error" :size="19"/><CheckCircle2 v-else :size="19"/>{{toast.message}}<button class="icon-button" aria-label="关闭提示" @click="toast=null"><X :size="16"/></button></div></Transition></Teleport>
</template>
