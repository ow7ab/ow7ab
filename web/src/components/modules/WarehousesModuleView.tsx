import React, { useEffect, useMemo, useState } from 'react';
import {
  Package,
  Plus,
  Search,
  Building,
  FileSpreadsheet,
  Printer,
  Trash2,
  ArrowUpDown,
  CheckCircle2,
  AlertCircle
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Cashbox, CurrencyCode, Product, StockMove, Warehouse } from '../../types/erp';
import { ErpDataService } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

type WarehouseSubTab = 'BALANCES' | 'NEW_PRODUCT_SUPPLY' | 'WAREHOUSES_DIR' | 'STOCK_MOVES';

export const WarehousesModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [subTab, setSubTab] = useState<WarehouseSubTab>('BALANCES');
  const [products, setProducts] = useState<Product[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [stockMoves, setStockMoves] = useState<StockMove[]>([]);
  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);

  const [searchTerm, setSearchTerm] = useState('');
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // Sub-tab 2: New Product & Initial Supply
  const [prodForm, setProdForm] = useState({
    name: '',
    sku: '',
    category: 'بوهية',
    unit: 'كرتونة',
    paintColor: 'أبيض',
    packageWeight: '900',
    salePrice: '',
    salePriceEgp: '',
    purchasePrice: '',
    minimumStock: '10',
    initialQty: '0',
    warehouseId: 0
  });

  // Quick Stock Adjustment Form
  const [moveForm, setMoveForm] = useState({
    warehouseId: 0,
    productId: 0,
    kind: 'IN' as 'IN' | 'OUT' | 'RETURN_IN',
    quantity: '',
    note: ''
  });

  // Sub-tab 3: New Warehouse Form
  const [whForm, setWhForm] = useState({
    name: '',
    currency: 'SDG' as CurrencyCode,
    linkedCashbox: '',
    warehouseType: 'مبيعات' as Warehouse['warehouseType']
  });

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<Product>('products', setProducts),
      ErpDataService.subscribeToCollection<Warehouse>('warehouses', setWarehouses),
      ErpDataService.subscribeToCollection<StockMove>('stock_moves', setStockMoves),
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setCashboxes)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  useEffect(() => {
    if (warehouses.length > 0 && prodForm.warehouseId === 0) {
      setProdForm((f) => ({ ...f, warehouseId: Number(warehouses[0].id) }));
      setMoveForm((f) => ({ ...f, warehouseId: Number(warehouses[0].id) }));
    }
    if (products.length > 0 && moveForm.productId === 0) {
      setMoveForm((f) => ({ ...f, productId: Number(products[0].id) }));
    }
    if (cashboxes.length > 0 && !whForm.linkedCashbox) {
      setWhForm((f) => ({ ...f, linkedCashbox: cashboxes[0].name }));
    }
  }, [warehouses, products, cashboxes]);

  const productStocks = useMemo(
    () => ErpDataService.computeProductStocks(products, stockMoves),
    [products, stockMoves]
  );

  const canModifyInventory =
    canPerformAction(GRANULAR_ACTIONS.INVENTORY_OPS) || canPerformAction(GRANULAR_ACTIONS.CREATE);
  const canDelete = canPerformAction(GRANULAR_ACTIONS.DELETE);

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const handleCreateProductAndSupply = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canModifyInventory || !prodForm.name.trim()) return;

    const id = Date.now();
    const initialQty = Number(prodForm.initialQty) || 0;
    const actor = userProfile?.fullName || userProfile?.username || 'WEB_WAREHOUSE';

    const newProd: Product = {
      id,
      sku: prodForm.sku.trim() || `PMP-${String(id).slice(-4)}`,
      name: prodForm.name.trim(),
      category: prodForm.category,
      unit: prodForm.unit.trim() || 'كرتونة',
      purchasePrice: Number(prodForm.purchasePrice) || 0,
      salePrice: Number(prodForm.salePrice) || 0,
      salePriceEgp: Number(prodForm.salePriceEgp) || 0,
      minimumStock: Number(prodForm.minimumStock) || 10,
      packageWeight: prodForm.packageWeight,
      paintColor: prodForm.paintColor,
      active: true,
      currentStock: initialQty
    };

    try {
      await ErpDataService.saveRecord('products', `PROD-${id}`, newProd, userProfile?.username);
      if (initialQty > 0) {
        const smId = id + 1;
        const sm: StockMove = {
          id: smId,
          warehouseId: prodForm.warehouseId || 1,
          productId: id,
          quantity: initialQty,
          kind: 'IN',
          invoiceId: null,
          note: 'رصيد افتتاحي / توريد مبدئي',
          createdBy: actor,
          createdAt: id
        };
        await ErpDataService.saveRecord('stock_moves', `SM-${smId}`, sm, userProfile?.username);
      }
      setProdForm((f) => ({
        ...f,
        name: '',
        sku: '',
        salePrice: '',
        salePriceEgp: '',
        purchasePrice: '',
        initialQty: '0'
      }));
      showNotice('ok', `تمت إضافة "${newProd.name}" وتحديث رصيد المخزن`);
      setSubTab('BALANCES');
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر حفظ الصنف');
    }
  };

  const handleRecordStockMove = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canModifyInventory) return;
    const q = Number(moveForm.quantity);
    if (!moveForm.productId || !Number.isFinite(q) || q <= 0) return;

    const id = Date.now();
    const sm: StockMove = {
      id,
      warehouseId: moveForm.warehouseId || warehouses[0]?.id || 1,
      productId: Number(moveForm.productId),
      quantity: q,
      kind: moveForm.kind,
      invoiceId: null,
      note: moveForm.note.trim() || `حركة مخزنية (${moveForm.kind})`,
      createdBy: userProfile?.fullName || userProfile?.username || 'WEB_WAREHOUSE',
      createdAt: id
    };

    try {
      await ErpDataService.saveRecord('stock_moves', `SM-${id}`, sm, userProfile?.username);
      setMoveForm((f) => ({ ...f, quantity: '', note: '' }));
      showNotice('ok', 'تم تسجيل الحركة المخزنية ومزامنتها');
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر تسجيل الحركة');
    }
  };

  const handleCreateWarehouse = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canModifyInventory || !whForm.name.trim()) return;
    const id = Date.now();
    const wh: Warehouse = {
      id,
      name: whForm.name.trim(),
      active: true,
      currency: whForm.currency,
      linkedCashbox: whForm.linkedCashbox,
      warehouseType: whForm.warehouseType
    };
    await ErpDataService.saveRecord('warehouses', `WH-${id}`, wh, userProfile?.username);
    setWhForm((f) => ({ ...f, name: '' }));
    showNotice('ok', `تم إنشاء المخزن "${wh.name}"`);
  };

  const handleDeleteProduct = async (p: Product) => {
    if (!canDelete) return;
    const docId = (p as any)._docId || `PROD-${p.id}`;
    await ErpDataService.deleteRecord('products', docId, p.id, userProfile?.username);
    showNotice('ok', `تم حذف الصنف ${p.name}`);
  };

  const handleDeleteStockMove = async (sm: StockMove) => {
    if (!canDelete) return;
    const docId = (sm as any)._docId || `SM-${sm.id}`;
    await ErpDataService.deleteRecord('stock_moves', docId, sm.id, userProfile?.username);
    showNotice('ok', 'تم حذف الحركة المخزنية');
  };

  const filteredProducts = useMemo(() => {
    return products.filter(
      (p) =>
        !searchTerm ||
        (p.name || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
        (p.sku || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
        (p.paintColor || '').toLowerCase().includes(searchTerm.toLowerCase())
    );
  }, [products, searchTerm]);

  const productNameMap = useMemo(() => {
    const m = new Map<number, string>();
    products.forEach((p) => m.set(Number(p.id), p.name));
    return m;
  }, [products]);

  const warehouseNameMap = useMemo(() => {
    const m = new Map<number, string>();
    warehouses.forEach((w) => m.set(Number(w.id), w.name));
    return m;
  }, [warehouses]);

  const sortedMoves = useMemo(
    () => [...stockMoves].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)),
    [stockMoves]
  );

  const handleExportCsv = () => {
    const headers = ['الكود SKU', 'الاسم', 'النوع/اللون', 'الوزن', 'السعر (SDG)', 'السعر (EGP)', 'الرصيد المحسوب', 'حد الأمان'];
    const rows = filteredProducts.map((p) => [
      p.sku,
      p.name,
      `${p.paintColor} (${p.category})`,
      `${p.packageWeight}جم`,
      p.salePrice ?? p.salePriceSdg ?? 0,
      p.salePriceEgp ?? 0,
      productStocks.get(Number(p.id)) ?? 0,
      p.minimumStock
    ]);
    ReportExporter.exportToCsv('pmp_inventory_balances', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['SKU', 'الصنف', 'اللون/الوزن', 'الرصيد الفعلي', 'SDG', 'EGP'];
    const rows = filteredProducts.map((p) => [
      p.sku,
      p.name,
      `${p.paintColor} • ${p.packageWeight}جم`,
      productStocks.get(Number(p.id)) ?? 0,
      (p.salePrice ?? p.salePriceSdg ?? 0).toLocaleString(),
      (p.salePriceEgp ?? 0).toLocaleString()
    ]);
    ReportExporter.exportToPrint('أرصدة المخازن والمنتج التام - PMP PAINTS', headers, rows);
  };

  return (
    <div className="space-y-6">
      <GlassCard className="p-5 sm:p-6">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-black text-theme-primary">
                {language === 'ar' ? 'المخازن والمستودعات' : 'Warehouses & Inventory'}
              </h2>
              <GlassBadge variant="cyan">{products.length} صنف</GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              نفس تبويبات الأندرويد الأربعة: أرصدة المخزون المحسوبة من stock_moves، التوريد، إدارة المخازن، وسجل الحركات.
            </p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
              Excel / CSV
            </GlassButton>
            <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
              طباعة
            </GlassButton>
          </div>
        </div>

        <div className="flex items-center gap-2 mt-4 overflow-x-auto pb-1">
          {[
            { id: 'BALANCES' as WarehouseSubTab, label: `أرصدة المخزون (${products.length})` },
            { id: 'NEW_PRODUCT_SUPPLY' as WarehouseSubTab, label: '+ إضافة صنف وتوريد للمخزن' },
            { id: 'WAREHOUSES_DIR' as WarehouseSubTab, label: `الأصناف والمخازن (${warehouses.length})` },
            { id: 'STOCK_MOVES' as WarehouseSubTab, label: `سجل الحركات (${stockMoves.length})` }
          ].map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setSubTab(t.id)}
              className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition-all ${
                subTab === t.id
                  ? 'bg-pmp-primary text-white shadow-md'
                  : 'bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </GlassCard>

      {notice && (
        <div
          className={`p-3.5 rounded-xl border text-xs flex items-center gap-2 ${
            notice.type === 'ok'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {notice.type === 'ok' ? <CheckCircle2 className="w-4 h-4 shrink-0" /> : <AlertCircle className="w-4 h-4 shrink-0" />}
          <span>{notice.text}</span>
        </div>
      )}

      {subTab === 'BALANCES' && (
        <div className="space-y-4">
          <GlassCard className="p-4">
            <div className="relative">
              <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
              <input
                type="text"
                placeholder="بحث باسم الصنف، SKU، أو اللون..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary text-xs"
              />
            </div>
          </GlassCard>

          <GlassCard className="p-0 overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-right border-collapse text-xs min-w-[650px]">
                <thead>
                  <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                    <th className="py-3 px-4">الكود</th>
                    <th className="py-3 px-4">المنتج</th>
                    <th className="py-3 px-4">النوع / الوزن</th>
                    <th className="py-3 px-4">الرصيد الفعلي</th>
                    <th className="py-3 px-4">SDG</th>
                    <th className="py-3 px-4">EGP</th>
                    <th className="py-3 px-4">الحالة</th>
                    {canDelete && <th className="py-3 px-4">حذف</th>}
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {filteredProducts.map((p) => {
                    const liveQty = productStocks.get(Number(p.id)) ?? 0;
                    const priceSdg = p.salePrice ?? p.salePriceSdg ?? 0;
                    return (
                      <tr key={p.id} className="hover:bg-theme-surface/40">
                        <td className="py-3 px-4 font-mono text-pmp-cyan font-bold">{p.sku}</td>
                        <td className="py-3 px-4 font-bold text-theme-primary">{p.name}</td>
                        <td className="py-3 px-4 text-theme-secondary">
                          {p.paintColor} • {p.packageWeight}جم
                        </td>
                        <td className="py-3 px-4 font-mono font-black text-pmp-primary">
                          {liveQty.toLocaleString()} {p.unit}
                        </td>
                        <td className="py-3 px-4 font-mono">{Number(priceSdg).toLocaleString()}</td>
                        <td className="py-3 px-4 font-mono">{Number(p.salePriceEgp || 0).toLocaleString()}</td>
                        <td className="py-3 px-4">
                          <GlassBadge variant={liveQty > (p.minimumStock || 5) ? 'green' : 'gold'}>
                            {liveQty > (p.minimumStock || 5) ? 'متوفر' : 'حد الطلب'}
                          </GlassBadge>
                        </td>
                        {canDelete && (
                          <td className="py-3 px-4">
                            <button
                              type="button"
                              onClick={() => handleDeleteProduct(p)}
                              className="p-1.5 text-red-400 hover:bg-red-500/10 rounded-lg"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          </td>
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {subTab === 'NEW_PRODUCT_SUPPLY' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <GlassCard className="p-6 lg:col-span-7">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Plus className="w-4 h-4 text-pmp-primary" />
              إضافة صنف منتج تام جديد + رصيد افتتاحي
            </h3>
            <form onSubmit={handleCreateProductAndSupply} className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
              <div className="sm:col-span-2">
                <label className="block font-bold text-theme-secondary mb-1">اسم الصنف *</label>
                <input
                  type="text"
                  required
                  value={prodForm.name}
                  onChange={(e) => setProdForm({ ...prodForm, name: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">SKU / الكود</label>
                <input
                  type="text"
                  value={prodForm.sku}
                  onChange={(e) => setProdForm({ ...prodForm, sku: e.target.value })}
                  placeholder="تلقائي عند الترك فارغاً"
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">اللون والوزن</label>
                <div className="grid grid-cols-2 gap-2">
                  <select
                    value={prodForm.paintColor}
                    onChange={(e) => setProdForm({ ...prodForm, paintColor: e.target.value })}
                    className="px-2 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  >
                    <option value="أبيض">أبيض</option>
                    <option value="ألوان">ألوان</option>
                    <option value="فضي">فضي</option>
                    <option value="ذهبي">ذهبي</option>
                    <option value="ورنيش">ورنيش</option>
                  </select>
                  <select
                    value={prodForm.packageWeight}
                    onChange={(e) => setProdForm({ ...prodForm, packageWeight: e.target.value })}
                    className="px-2 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  >
                    <option value="250">250جم</option>
                    <option value="900">900جم</option>
                    <option value="4000">4000جم</option>
                  </select>
                </div>
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">سعر البيع (SDG)</label>
                <input
                  type="number"
                  required
                  value={prodForm.salePrice}
                  onChange={(e) => setProdForm({ ...prodForm, salePrice: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">سعر البيع (EGP)</label>
                <input
                  type="number"
                  required
                  value={prodForm.salePriceEgp}
                  onChange={(e) => setProdForm({ ...prodForm, salePriceEgp: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">الكمية الافتتاحية</label>
                <input
                  type="number"
                  value={prodForm.initialQty}
                  onChange={(e) => setProdForm({ ...prodForm, initialQty: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">حد إعادة الطلب</label>
                <input
                  type="number"
                  value={prodForm.minimumStock}
                  onChange={(e) => setProdForm({ ...prodForm, minimumStock: e.target.value })}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div className="sm:col-span-2 flex justify-end pt-2">
                <GlassButton type="submit" variant="primary" disabled={!canModifyInventory}>
                  حفظ الصنف وتوريد الرصيد
                </GlassButton>
              </div>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-5">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <ArrowUpDown className="w-4 h-4 text-pmp-cyan" />
              حركة توريد / صرف مخزني سريع
            </h3>
            <form onSubmit={handleRecordStockMove} className="space-y-3 text-xs">
              <select
                value={moveForm.productId}
                onChange={(e) => setMoveForm({ ...moveForm, productId: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                {products.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} (الحالي: {productStocks.get(Number(p.id)) ?? 0})
                  </option>
                ))}
              </select>
              <select
                value={moveForm.warehouseId}
                onChange={(e) => setMoveForm({ ...moveForm, warehouseId: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name}
                  </option>
                ))}
              </select>
              <div className="grid grid-cols-2 gap-2">
                <select
                  value={moveForm.kind}
                  onChange={(e) => setMoveForm({ ...moveForm, kind: e.target.value as any })}
                  className="px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  <option value="IN">توريد للمخزن (IN)</option>
                  <option value="OUT">صرف من المخزن (OUT)</option>
                  <option value="RETURN_IN">مرتجع مبيعات (RETURN_IN)</option>
                </select>
                <input
                  type="number"
                  required
                  placeholder="الكمية"
                  value={moveForm.quantity}
                  onChange={(e) => setMoveForm({ ...moveForm, quantity: e.target.value })}
                  className="px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <input
                type="text"
                placeholder="ملاحظة الحركة"
                value={moveForm.note}
                onChange={(e) => setMoveForm({ ...moveForm, note: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <GlassButton type="submit" variant="primary" className="w-full justify-center" disabled={!canModifyInventory}>
                ترحيل الحركة المخزنية
              </GlassButton>
            </form>
          </GlassCard>
        </div>
      )}

      {subTab === 'WAREHOUSES_DIR' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <GlassCard className="p-6 lg:col-span-5">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Building className="w-4 h-4 text-pmp-primary" />
              إضافة مستودع / مخزن جديد
            </h3>
            <form onSubmit={handleCreateWarehouse} className="space-y-3 text-xs">
              <input
                type="text"
                required
                placeholder="اسم المخزن *"
                value={whForm.name}
                onChange={(e) => setWhForm({ ...whForm, name: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <select
                value={whForm.warehouseType}
                onChange={(e) => setWhForm({ ...whForm, warehouseType: e.target.value as any })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                <option value="مبيعات">مخزن مبيعات</option>
                <option value="داخل المصنع/إنتاج">داخل المصنع / إنتاج</option>
                <option value="مواد خام">مخزن مواد خام</option>
              </select>
              <select
                value={whForm.currency}
                onChange={(e) => setWhForm({ ...whForm, currency: e.target.value as CurrencyCode })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                <option value="SDG">SDG</option>
                <option value="EGP">EGP</option>
                <option value="USD">USD</option>
              </select>
              <select
                value={whForm.linkedCashbox}
                onChange={(e) => setWhForm({ ...whForm, linkedCashbox: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                <option value="">-- الخزنة المرتبطة --</option>
                {cashboxes.map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name}
                  </option>
                ))}
              </select>
              <GlassButton type="submit" variant="primary" className="w-full justify-center" disabled={!canModifyInventory}>
                حفظ المخزن
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-7">
            <h4 className="text-sm font-bold text-theme-primary mb-3">المخازن المسجلة ({warehouses.length})</h4>
            <div className="space-y-2 text-xs">
              {warehouses.map((w) => (
                <div key={w.id} className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center">
                  <div>
                    <span className="font-bold text-theme-primary block">{w.name}</span>
                    <span className="text-[11px] text-theme-muted">
                      {w.warehouseType} • الخزنة: {w.linkedCashbox || '-'}
                    </span>
                  </div>
                  <GlassBadge variant="cyan">{w.currency}</GlassBadge>
                </div>
              ))}
            </div>
          </GlassCard>
        </div>
      )}

      {subTab === 'STOCK_MOVES' && (
        <GlassCard className="p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-right border-collapse text-xs min-w-[600px]">
              <thead>
                <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                  <th className="py-3 px-4">النوع</th>
                  <th className="py-3 px-4">الصنف</th>
                  <th className="py-3 px-4">المخزن</th>
                  <th className="py-3 px-4">الكمية</th>
                  <th className="py-3 px-4">البيان</th>
                  <th className="py-3 px-4">بواسطة</th>
                  {canDelete && <th className="py-3 px-4">حذف</th>}
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {sortedMoves.map((sm) => (
                  <tr key={sm.id}>
                    <td className="py-3 px-4">
                      <GlassBadge variant={sm.kind === 'IN' || sm.kind === 'RETURN_IN' ? 'green' : 'red'}>
                        {sm.kind}
                      </GlassBadge>
                    </td>
                    <td className="py-3 px-4 font-bold">{productNameMap.get(Number(sm.productId)) || `#${sm.productId}`}</td>
                    <td className="py-3 px-4 text-theme-secondary">{warehouseNameMap.get(Number(sm.warehouseId)) || `#${sm.warehouseId}`}</td>
                    <td className="py-3 px-4 font-mono font-bold">{sm.quantity}</td>
                    <td className="py-3 px-4 text-theme-muted">{sm.note}</td>
                    <td className="py-3 px-4 text-theme-muted">{sm.createdBy}</td>
                    {canDelete && (
                      <td className="py-3 px-4">
                        <button
                          type="button"
                          onClick={() => handleDeleteStockMove(sm)}
                          className="p-1.5 text-red-400 hover:bg-red-500/10 rounded-lg"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>
      )}
    </div>
  );
};
