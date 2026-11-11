export type RequestType='PURCHASE'|'RESTOCK'|null
export function requestTypeLabel(type?:RequestType){
  if(type==='RESTOCK')return '库存补货'
  if(type==='PURCHASE')return '普通采购'
  return '历史未分类'
}
export interface Account { id:number; username:string; name:string; department:string; role:string; mustChangePassword?:boolean }
export interface Product { replenishment?:{status:string;quantity:number}[]; id:number; sku:string; name:string; category:string; unit:string; price:number; min_stock:number; stock:number; supplier_id:number; supplier_name:string; version:number; stock_version:number; enabled:boolean }
export interface RequestLine { id:string; product_id:number; supplier_id:number; product_name:string; sku:string; unit:string; quantity:number; unit_price:number; amount:number; supplier_name:string }
export interface OrderLine extends Omit<RequestLine,'supplier_id'|'supplier_name'> { order_id:string; request_line_id:string; received_quantity:number; returned_quantity:number; net_received:number; remaining:number }
export interface PurchaseRequest { request_type?:RequestType; approver_id:number|null;approver_name:string|null;approver_username:string|null; lifecycle_state:string; id:string; number:string; applicant_id:number; product_id:number; quantity:number; unit_price:number; reason:string; status:string; review_note:string|null; created_at:string; updated_at:string; product_name:string; sku:string; unit:string; applicant_name:string; department:string; amount:number; version:number; lines:RequestLine[] }
export interface Order { request_number?:string; request_type?:RequestType; lifecycle_state:string;closure_reason:string; id:string; number:string; request_id:string; status:string; fulfillment_status:string; product_name:string; quantity:number; unit:string; supplier_name:string; buyer_name:string; created_at:string; amount:number; version:number; lines:OrderLine[] }
export interface Supplier { product_count:number; id:number; name:string; contact:string; phone:string; version:number }
export interface Movement { id:string; product_name:string; sku:string; unit:string; type:string; quantity:number; balance:number; reference:string; operator_name:string; created_at:string; business_type:string; returned_quantity:number; remaining:number }
export interface Audit { id:number; actor_name:string; action:string; target:string; detail:string; created_at:string }
export interface User { id:number; username:string; display_name:string; department:string; role:string; enabled:boolean; version:number }
export interface Overview { pending:number; amount:number; requests:number; lowStock:number; openOrders:number|null; stockValue:number|null; counts?:{status:string;total:number}[]; categories?:{name:string;amount:number}[] }
export interface RequestItemInput {productId:number;quantity:number}
export interface FulfillmentForm {lines:{orderLineId:string;quantity:number}[];reason:string}
export interface Notice {id:string;title:string;body:string;path:string;target_id?:string;target_type?:string;read_at:string|null;created_at:string}
export interface Inbox {items:Notice[];total:number;unread:number;page:number;size:number}
export interface Profile {id:number;username:string;display_name:string;department:string;role:string;email:string;phone:string;version:number;must_change_password?:boolean}
export interface PageResult<T> {items:T[];total:number;page:number;size:number;categories?:string[];counts?:{status:string;total:number}[]}
export class ApiError extends Error {constructor(message:string, public status=0, public uncertain=false, public key=''){super(message)}}
let csrfToken=''
export async function csrf() {
  const res=await fetch('/api/auth/csrf',{credentials:'same-origin',signal:AbortSignal.timeout(15000)})
  if(!res.ok) throw new Error('暂时无法连接服务，请确认后端已启动')
  csrfToken=(await res.json()).token
}
export async function api<T>(path:string, options:RequestInit={}):Promise<T> {
  const headers=new Headers(options.headers)
  if(options.body && !(options.body instanceof URLSearchParams)) headers.set('Content-Type','application/json')
  if(options.method && options.method!=='GET') {
    if(!csrfToken) await csrf()
    headers.set('X-CSRF-TOKEN',csrfToken)
  }
  let res:Response
  try {res=await fetch('/api'+path,{...options,headers,credentials:'same-origin',signal:AbortSignal.timeout(20000)})}
  catch {throw new ApiError(options.method&&options.method!=='GET'?'连接中断，提交结果尚未确认。请查询结果，勿修改内容后重复提交。':'读取超时，请检查网络后重试',0,!!options.method&&options.method!=='GET',headers.get('Idempotency-Key')||'')}
  if(!res.ok) {
    const data=await res.json().catch(()=>({message:'服务暂时不可用，请稍后重试'}))
    if(res.status===401 && path!=='/auth/login') window.dispatchEvent(new CustomEvent('session-expired',{detail:data.message}))
    throw new ApiError(data.message || ('操作失败：'+res.status),res.status,res.status>=500&&!!options.method&&options.method!=='GET',headers.get('Idempotency-Key')||'')
  }
  if(res.status===204)return undefined as T
  try{return await res.json()}catch{throw new ApiError('响应读取中断，请核对操作结果',0,!!options.method&&options.method!=='GET',headers.get('Idempotency-Key')||'')}
}
export async function post<T>(path:string, body?:unknown, key?:string):Promise<T> {
  return api<T>(path,{method:'POST',body:body===undefined?undefined:JSON.stringify(body),headers:key?{'Idempotency-Key':key}:undefined})
}
export async function put<T>(path:string, body:unknown):Promise<T> {
  return api<T>(path,{method:'PUT',body:JSON.stringify(body)})
}
