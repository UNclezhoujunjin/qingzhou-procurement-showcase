import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import ts from 'typescript'
const source=await readFile(new URL('../src/productCsv.ts',import.meta.url),'utf8')
const {outputText}=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ES2022,target:ts.ScriptTarget.ES2022}})
const {parseProductCsv:parse}=await import('data:text/javascript;base64,'+Buffer.from(outputText).toString('base64'))
const header='SKU,商品名称,分类,单位,单价,安全库存,供应商ID\n'
test('CSV handles BOM, CRLF, quoted commas, escaped quotes and multiline cells',()=>{
 const {rows}=parse('\uFEFF'+header+'T-1,"文件夹,大号",办公,个,10.50,2,1\r\nT-2,"带""引号""\n的名称",办公,个,20,0,2\r\n')
 assert.equal(rows[0].name,'文件夹,大号');assert.equal(rows[0].price,10.5);assert.equal(rows[1].name,'带"引号"\n的名称')
})
test('CSV rejects broken quotation and extra text after closing a quote',()=>{
 assert.throws(()=>parse(header+'T-1,"未闭合,办公,个,10,2,1'))
 assert.throws(()=>parse(header+'T-1,"名称"x,办公,个,10,2,1'))
})
test('CSV rejects wrong columns, nonnumeric IDs, unsafe IDs and invalid UTF-8 replacement',()=>{
 assert.throws(()=>parse('wrong\nT-1,名称,办公,个,10,2,1'))
 assert.throws(()=>parse(header+'T-1,名称,办公,个,10,2'))
 assert.throws(()=>parse(header+'T-1,名称,办公,个,10,2,abc'))
 assert.throws(()=>parse(header+'T-1,名称,办公,个,10,2,999999999999999999'))
 assert.throws(()=>parse(header+'T-1,损坏\uFFFD,办公,个,10,2,1'))
})
test('CSV enforces batch size and exact monetary decimals',()=>{
 assert.throws(()=>parse(header))
 assert.throws(()=>parse(header+Array(201).fill('T-1,名称,办公,个,10,2,1').join('\n')))
 assert.throws(()=>parse(header+'T-1,名称,办公,个,10.123,2,1'))
 assert.equal(parse(header+'\nT-1,名称,办公,个,10,2,1\n\n').rows.length,1)
})
