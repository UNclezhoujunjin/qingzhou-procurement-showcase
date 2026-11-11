-- PostgreSQL: run this WHOLE file against qingzhou. Safe to repeat: existing names/SKUs are preserved.
-- Fictional suppliers and reference prices, for project demonstrations only. No stock is fabricated.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $$
BEGIN
    IF current_database() <> 'qingzhou' THEN
        RAISE EXCEPTION 'Select the qingzhou database before running this file';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.app_user WHERE username='admin' AND role='ADMIN' AND enabled) THEN
        RAISE EXCEPTION 'An enabled admin account is required for the audit trail';
    END IF;
END $$;

WITH inserted AS (
    INSERT INTO public.supplier(name,contact,phone) VALUES
        ('办公物资供应商（演示）','演示联系人','未提供（演示）'),
        ('电脑外设供应商（演示）','演示联系人','未提供（演示）'),
        ('数码配件供应商（演示）','演示联系人','未提供（演示）')
    ON CONFLICT(name) DO NOTHING
    RETURNING name
)
INSERT INTO public.audit_log(actor_id,action,target,detail)
SELECT a.id,'新增供应商',i.name,'批量添加项目演示供应商；非真实商家，未提供真实联系方式'
FROM inserted i CROSS JOIN public.app_user a WHERE a.username='admin';

WITH items(sku,name,category,unit,price,min_stock,supplier_name) AS (VALUES
    ('DEMO-OFF-001','A4 打印纸 80g（500 张）','办公用品','包',25.00,10,'办公物资供应商（演示）'),
    ('DEMO-OFF-002','黑色中性笔 0.5mm（12 支）','办公用品','盒',18.00,10,'办公物资供应商（演示）'),
    ('DEMO-OFF-003','便利贴组合装','办公用品','套',8.50,20,'办公物资供应商（演示）'),
    ('DEMO-OFF-004','A4 文件夹','办公用品','个',6.80,20,'办公物资供应商（演示）'),
    ('DEMO-OFF-005','办公订书机','办公用品','个',15.00,5,'办公物资供应商（演示）'),
    ('DEMO-PC-001','无线办公鼠标','电脑外设','个',69.00,5,'电脑外设供应商（演示）'),
    ('DEMO-PC-002','全尺寸办公键盘','电脑外设','个',159.00,5,'电脑外设供应商（演示）'),
    ('DEMO-PC-003','24 英寸办公显示器','电脑外设','台',899.00,2,'电脑外设供应商（演示）'),
    ('DEMO-DIG-001','64GB USB 3.0 U 盘','数码配件','个',49.00,10,'数码配件供应商（演示）'),
    ('DEMO-DIG-002','USB-C 多功能扩展坞','数码配件','个',129.00,5,'数码配件供应商（演示）'),
    ('DEMO-DIG-003','六类网线 3 米','数码配件','条',19.90,10,'数码配件供应商（演示）'),
    ('DEMO-DIG-004','铝合金笔记本支架','数码配件','个',89.00,5,'数码配件供应商（演示）')
), inserted AS (
    INSERT INTO public.product(sku,name,category,unit,price,min_stock,supplier_id)
    SELECT i.sku,i.name,i.category,i.unit,i.price,i.min_stock,s.id
    FROM items i JOIN public.supplier s ON s.name=i.supplier_name
    ON CONFLICT(sku) DO NOTHING
    RETURNING sku,name
)
INSERT INTO public.audit_log(actor_id,action,target,detail)
SELECT a.id,'新增商品',i.sku,i.name || '；批量添加演示商品，价格为参考值，初始库存为 0'
FROM inserted i CROSS JOIN public.app_user a WHERE a.username='admin';
COMMIT;

SELECT p.sku,p.name,p.category,p.unit,p.price,p.stock,p.min_stock,s.name AS supplier
FROM public.product p JOIN public.supplier s ON s.id=p.supplier_id
WHERE p.sku IN ('DEMO-OFF-001','DEMO-OFF-002','DEMO-OFF-003','DEMO-OFF-004','DEMO-OFF-005',
    'DEMO-PC-001','DEMO-PC-002','DEMO-PC-003','DEMO-DIG-001','DEMO-DIG-002','DEMO-DIG-003','DEMO-DIG-004')
ORDER BY p.sku;
