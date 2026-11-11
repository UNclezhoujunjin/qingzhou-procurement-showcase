export function parseProductCsv(source:string){
 const input=source.replace(/^\uFEFF/,'')
 if(input.includes('\uFFFD'))throw new Error('文件编码无法识别，请另存为 UTF-8 CSV')
 const rows:string[][]=[];let row:string[]=[],cell='',quoted=false,closed=false
 function endCell(){row.push(cell);cell='';closed=false}
 function endRow(){endCell();if(row.some(x=>x.trim()))rows.push(row);row=[]}
 for(let i=0;i<input.length;i++){
  const c=input[i]
  if(quoted){if(c==='"'){if(input[i+1]==='"'){cell+='"';i++}else{quoted=false;closed=true}}else cell+=c;continue}
  if(closed&&c!==','&&c!=='\n'&&c!=='\r'){if(c===' '||c==='\t')continue;throw new Error('CSV 闭合引号后存在多余字符')}
  if(c==='"'){if(cell)throw new Error('CSV 引号格式不正确');quoted=true}
  else if(c===',')endCell()
  else if(c==='\n'||c==='\r'){if(c==='\r'&&input[i+1]==='\n')i++;endRow()}
  else cell+=c
 }
 if(quoted)throw new Error('CSV 引号没有闭合')
 endRow()
 if(rows.shift()?.map(x=>x.trim()).join(',')!=='SKU,商品名称,分类,单位,单价,安全库存,供应商ID')throw new Error('请使用模板表头与列顺序')
 if(rows.length<1||rows.length>200)throw new Error('每次导入 1～200 行')
 return {rows:rows.map((r,i)=>{
  if(r.length!==7)throw new Error('第 '+(i+2)+' 行应有 7 列')
  if(r.some(x=>!x.trim()))throw new Error('第 '+(i+2)+' 行不能有空列')
  if(!/^\d+(\.\d{1,2})?$/.test(r[4].trim())||!/^\d+$/.test(r[5].trim())||!/^\d+$/.test(r[6].trim())||!Number.isSafeInteger(Number(r[5]))||!Number.isSafeInteger(Number(r[6])))throw new Error('第 '+(i+2)+' 行的价格、库存或供应商 ID 格式不正确')
  return {sku:r[0].trim(),name:r[1].trim(),category:r[2].trim(),unit:r[3].trim(),price:Number(r[4]),minStock:Number(r[5]),supplierId:Number(r[6])}
 })}
}
