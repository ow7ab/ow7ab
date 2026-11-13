import React, { useEffect, useMemo, useState } from 'react';
import {
  Factory,
  Plus,
  Search,
  FileSpreadsheet,
  Printer,
  CheckCircle2,
  AlertCircle,
  Truck,
  Trash2
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import {
  Cashbox,
  FactoryItem,
  FactoryMachine,
  FactoryMove,
  HalfaInvoice,
  LabFormulation,
  Product,
  ProductionBatch,
  StockMove,
  Warehouse
} from '../../types/erp';
import { ErpDataService, safeId } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

type FactorySubTab = 'STORAGE' | 'BATCHES' | 'ORDERS' | 'HALFA_OUTBOUND' | 'LOG';

export const FactoryModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [subTab, setSubTab] = useState<FactorySubTab>('BATCHES');
  const [batches, setBatches] = useState<ProductionBatch[]>([]);
  const [formulations, setFormulations] = useState<LabFormulation[]>([]);
  const [factoryItems, setFactoryItems] = useState<FactoryItem[]>([]);
  const [factoryMoves, setFactoryMoves] = useState<FactoryMove[]>([]);
  const [machines, setMachines] = useState<FactoryMachine[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);
  const [halfaInvoices, setHalfaInvoices] = useState<HalfaInvoice[]>([]);

  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [isNewBatchOpen, setIsNewBatchOpen] = useState(false);
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  const [batchForm, setBatchForm] = useState({
    formulaId: 0,
    productName: '',
    baseKind: 'أبيض',
    packageUnit: '900 جم (12 علبة)',
    targetWeightKg: '1000',
    cartonsCount: '80',
    machineName: '',
    shift: 'صباحية',
    qualityStatus: 'تحت الفحص المخبري' as ProductionBatch['qualityStatus']
  });

  const [moveForm, setMoveForm] = useState({
    kind: 'صرف خامات' as FactoryMove['kind'],
    itemId: 0,
    qty: '',
    batch: '',
    note: ''
  });

  const [halfaForm, setHalfaForm] = useState({
    warehouseId: 0,
    productId: 0,
    cashbox: '',
    quantity: '',
    unitPrice: '',
    note: ''
  });

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<ProductionBatch>('paint_batches', setBatches),
      ErpDataService.subscribeToCollection<LabFormulation>('lab_formulations', setFormulations),
      ErpDataService.subscribeToCollection<FactoryItem>('factory_items', setFactoryItems),
      ErpDataService.subscribeToCollection<FactoryMove>('factory_moves', setFactoryMoves),
      ErpDataService.subscribeToCollection<FactoryMachine>('factory_machines', setMachines),
      ErpDataService.subscribeToCollection<Product>('products', setProducts),
      ErpDataService.subscribeToCollection<Warehouse>('warehouses', setWarehouses),
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setCashboxes),
      ErpDataService.subscribeToCollection<HalfaInvoice>('halfa_invoices', setHalfaInvoices)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  useEffect(() => {
    if (formulations.length > 0 && batchForm.formulaId === 0) {
      const f0 = formulations[0];
      setBatchForm((b) => ({
        ...b,
        formulaId: Number(f0.id),
        productName: f0.productName,
        baseKind: f0.baseKind || 'أبيض'
      }));
    }
    if (factoryItems.length > 0 && moveForm.itemId === 0) setMoveForm((m) => ({ ...m, itemId: Number(factoryItems[0].id) }));
    if (warehouses.length > 0 && halfaForm.warehouseId === 0) setHalfaForm((h) => ({ ...h, warehouseId: Number(warehouses[0].id) }));
    if (products.length > 0 && halfaForm.productId === 0) setHalfaForm((h) => ({ ...h, productId: Number(products[0].id) }));
    if (cashboxes.length > 0 && !halfaForm.cashbox) setHalfaForm((h) => ({ ...h, cashbox: cashboxes[0].name }));
  }, [formulations, factoryItems, warehouses, products, cashboxes]);

  const itemBalances = useMemo(
    () => ErpDataService.computeFactoryItemBalances(factoryItems, factoryMoves),
    [factoryItems, factoryMoves]
  );

  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE) || canPerformAction(GRANULAR_ACTIONS.INVENTORY_OPS);
  const canDelete = canPerformAction(GRANULAR_ACTIONS.DELETE);

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const handleCreateBatch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate) return;
    const now = Date.now();
    const formula = formulations.find((f) => Number(f.id) === Number(batchForm.formulaId));
    const batchNumber = `B-${new Date().getFullYear()}-${String(now).slice(-4)}`;
    const weight = Number(batchForm.targetWeightKg) || 1000;
    const cartons = Number(batchForm.cartonsCount) || 0;

    const record: ProductionBatch = {
      id: now,
      batchNumber,
      productionDate: new Date().toISOString().split('T')[0],
      formulaId: formula ? Number(formula.id) : null,
      formulaNo: formula?.formulaNo || 'CUSTOM',
      productName: batchForm.productName.trim() || formula?.productName || 'منتج دهان',
      paintType: formula?.paintType || 'مطاطي',
      baseKind: batchForm.baseKind,
      targetPvc: formula?.targetPvc || 40,
      targetWeightKg: weight,
      actualWeightKg: weight,
      targetVolumeLiters: Math.round(weight / (formula?.calculatedDensity || 1.35)),
      cartonsCount: cartons,
      packageUnit: batchForm.packageUnit,
      machineId: null,
      machineName: batchForm.machineName || machines[0]?.name || 'خلاط رئيسي 1',
      qualityStatus: batchForm.qualityStatus,
      measuredDensity: formula?.calculatedDensity || 1.35,
      measuredViscosity: '110 KU',
      scaledIngredientsJson: formula?.ingredientsJson || '[]',
      supervisorOrEngineer: userProfile?.fullName || userProfile?.username || 'مهندس الإنتاج',
      shift: batchForm.shift,
      notes: '',
      syncStatus: 'SYNCED',
      createdAt: now
    };

    try {
      await ErpDataService.saveRecord(
        'paint_batches',
        safeId(batchNumber, `BATCH-${now}`),
        record,
        userProfile?.username
      );
      setIsNewBatchOpen(false);
      showNotice('ok', `تم إصدار تشغيلة الإنتاج ${batchNumber} ومزامنتها`);
    } catch (err: any) {
      showNotice('err', err?.message || 'فشل حفظ تشغيلة الإنتاج');
    }
  };

  const handleSaveFactoryMove = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate) return;
    const item = factoryItems.find((i) => Number(i.id) === Number(moveForm.itemId));
    const q = Number(moveForm.qty);
    if (!item || !Number.isFinite(q) || q <= 0) return;

    const id = Date.now();
    const record: FactoryMove = {
      id,
      moveDate: new Date().toISOString().split('T')[0],
      kind: moveForm.kind,
      itemId: Number(item.id),
      itemName: item.name,
      qty: q,
      batch: moveForm.batch.trim() || `FM-${String(id).slice(-4)}`,
      note: moveForm.note.trim(),
      actor: userProfile?.fullName || userProfile?.username || 'WEB_FACTORY',
      counterparty: 'صالة الإنتاج',
      linked: Boolean(item.linkedToEgypt),
      createdAt: id
    };

    await ErpDataService.saveRecord('factory_moves', `FM-${id}`, record, userProfile?.username);
    setMoveForm((f) => ({ ...f, qty: '', batch: '', note: '' }));
    showNotice('ok', 'تم تسجيل حركة المصنع');
  };

  const handleCreateHalfaOutbound = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate) return;
    const prod = products.find((p) => Number(p.id) === Number(halfaForm.productId));
    const q = Number(halfaForm.quantity);
    const price = Number(halfaForm.unitPrice) || Number(prod?.salePrice || 0);
    if (!prod || q <= 0) return;

    const now = Date.now();
    const invNo = `HINV-${String(now).slice(-5)}`;
    const total = q * price;
    const actor = userProfile?.fullName || userProfile?.username || 'WEB_FACTORY';
    const wh = warehouses.find((w) => Number(w.id) === Number(halfaForm.warehouseId));

    const record: HalfaInvoice = {
      id: now,
      invoiceNo: invNo,
      invoiceDate: new Date().toISOString().split('T')[0],
      cashbox: halfaForm.cashbox || cashboxes[0]?.name || 'خزنة حلفا',
      currency: wh?.currency || 'SDG',
      warehouseId: Number(halfaForm.warehouseId) || 1,
      productId: Number(prod.id),
      productName: prod.name,
      quantity: q,
      unitPrice: price,
      total,
      note: halfaForm.note.trim() || 'ترحيل / خروج منتج تام عبر بوابة المصنع وحلفا',
      status: 'active',
      createdBy: actor,
      createdAt: now
    };

    const sm: StockMove = {
      id: now + 1,
      warehouseId: record.warehouseId,
      productId: record.productId,
      quantity: q,
      kind: 'IN',
      note: `توريد مخزن حلفا/المبيعات (${invNo})`,
      createdBy: actor,
      createdAt: now
    };

    await Promise.all([
      ErpDataService.saveRecord('halfa_invoices', safeId(invNo, `HINV-${now}`), record, userProfile?.username),
      ErpDataService.saveRecord('stock_moves', `SM-${sm.id}`, sm, userProfile?.username)
    ]);
    setHalfaForm((f) => ({ ...f, quantity: '', note: '' }));
    showNotice('ok', `تم إصدار بوليصة الخروج ${invNo} وتحديث مخزن المنتج التام`);
  };

  const handleDeleteBatch = async (b: ProductionBatch) => {
    if (!canDelete) return;
    const docId = (b as any)._docId || safeId(b.batchNumber, `BATCH-${b.id}`);
    await ErpDataService.deleteRecord('paint_batches', docId, b.id, userProfile?.username);
    showNotice('ok', `تم حذف التشغيلة ${b.batchNumber}`);
  };

  const filteredBatches = useMemo(() => {
    return [...batches]
      .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
      .filter((b) => {
        const matchesSearch =
          !searchTerm ||
          (b.batchNumber || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
          (b.productName || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
          (b.supervisorOrEngineer || '').toLowerCase().includes(searchTerm.toLowerCase());
        const matchesStatus = statusFilter === 'ALL' || b.qualityStatus === statusFilter;
        return matchesSearch && matchesStatus;
      });
  }, [batches, searchTerm, statusFilter]);

  const sortedMoves = useMemo(
    () => [...factoryMoves].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)),
    [factoryMoves]
  );

  const handleExportCsv = () => {
    const headers = ['رقم التشغيلة', 'المنتج', 'النوع', 'الوزن (كجم)', 'الكراتين', 'الحالة', 'المهندس', 'التاريخ'];
    const rows = filteredBatches.map((b) => [
      b.batchNumber,
      b.productName,
      b.baseKind,
      b.actualWeightKg || b.targetWeightKg,
      b.cartonsCount,
      b.qualityStatus,
      b.supervisorOrEngineer,
      b.productionDate
    ]);
    ReportExporter.exportToCsv('pmp_factory_batches', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['التشغيلة', 'المنتج', 'الوزن', 'الكراتين', 'الحالة', 'التاريخ'];
    const rows = filteredBatches.map((b) => [
      b.batchNumber,
      b.productName,
      `${b.actualWeightKg || b.targetWeightKg} كجم`,
      b.cartonsCount,
      b.qualityStatus,
      b.productionDate
    ]);
    ReportExporter.exportToPrint('تقرير تشغيلات الإنتاج بالمصنع - PMP PAINTS', headers, rows);
  };

  return (
    <div className="space-y-6">
      <GlassCard className="p-5 sm:p-6">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-black text-theme-primary">
                {language === 'ar' ? 'المصنع وصالة الإنتاج' : 'Factory & Production Lines'}
              </h2>
              <GlassBadge variant="red">{batches.length} تشغيلة</GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              نفس تبويبات الأندرويد: مخزن المصنع، تشغيلات الإنتاج، أوامر الصرف والخلط، خروج المنتج للعملاء، وسجل دورة المصنع.
            </p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
              Excel / CSV
            </GlassButton>
            <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
              طباعة
            </GlassButton>
            {canCreate && (
              <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={() => setIsNewBatchOpen(true)} className="text-xs">
                + تشغيلة إنتاج
              </GlassButton>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 mt-4 overflow-x-auto pb-1">
          {[
            { id: 'STORAGE' as FactorySubTab, label: `المخزن (${factoryItems.length})` },
            { id: 'BATCHES' as FactorySubTab, label: `تشغيلات الإنتاج (${batches.length})` },
            { id: 'ORDERS' as FactorySubTab, label: 'أوامر صرف الخامات والإنتاج' },
            { id: 'HALFA_OUTBOUND' as FactorySubTab, label: `خروج المنتج وبوالص حلفا (${halfaInvoices.length})` },
            { id: 'LOG' as FactorySubTab, label: `سجل دورة المصنع (${factoryMoves.length})` }
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

      {subTab === 'STORAGE' && (
        <GlassCard className="p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-right text-xs min-w-[520px]">
              <thead>
                <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                  <th className="py-3 px-4">الخامة / الصنف</th>
                  <th className="py-3 px-4">الفئة</th>
                  <th className="py-3 px-4">الوحدة</th>
                  <th className="py-3 px-4">الرصيد المحسوب</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {factoryItems.map((i) => (
                  <tr key={i.id}>
                    <td className="py-3 px-4 font-bold">{i.name}</td>
                    <td className="py-3 px-4"><GlassBadge variant="cyan">{i.category}</GlassBadge></td>
                    <td className="py-3 px-4">{i.unit}</td>
                    <td className="py-3 px-4 font-mono font-bold text-pmp-primary">
                      {(itemBalances.get(Number(i.id)) ?? 0).toLocaleString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>
      )}

      {subTab === 'BATCHES' && (
        <div className="space-y-4">
          <GlassCard className="p-4">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
              <div className="relative sm:col-span-2">
                <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
                <input
                  type="text"
                  placeholder="بحث برقم التشغيلة، اسم المنتج، أو المهندس..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                className="px-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary"
              >
                <option value="ALL">جميع حالات الجودة</option>
                <option value="معتمد ومطابق">معتمد ومطابق</option>
                <option value="تحت الفحص المخبري">تحت الفحص المخبري</option>
                <option value="مرفوض/معالجة">مرفوض / معالجة</option>
              </select>
            </div>
          </GlassCard>

          <GlassCard className="p-0 overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-right border-collapse text-xs min-w-[650px]">
                <thead>
                  <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                    <th className="py-3 px-4">التشغيلة</th>
                    <th className="py-3 px-4">المنتج والتركيبة</th>
                    <th className="py-3 px-4">الوزن والحجم</th>
                    <th className="py-3 px-4">التعبئة</th>
                    <th className="py-3 px-4">الجودة</th>
                    <th className="py-3 px-4">المهندس والتاريخ</th>
                    {canDelete && <th className="py-3 px-4">حذف</th>}
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {filteredBatches.map((b) => (
                    <tr key={b.id} className="hover:bg-theme-surface/40">
                      <td className="py-3 px-4 font-mono font-bold text-pmp-primary">{b.batchNumber}</td>
                      <td className="py-3 px-4">
                        <span className="font-bold text-theme-primary block">{b.productName}</span>
                        <span className="text-[10px] text-theme-muted">{b.formulaNo} • {b.baseKind}</span>
                      </td>
                      <td className="py-3 px-4 font-mono">
                        {b.actualWeightKg || b.targetWeightKg} كجم • {b.targetVolumeLiters} لتر
                      </td>
                      <td className="py-3 px-4">
                        <span className="font-bold block">{b.cartonsCount} كرتونة</span>
                        <span className="text-[10px] text-theme-muted">{b.packageUnit}</span>
                      </td>
                      <td className="py-3 px-4">
                        <GlassBadge
                          variant={
                            b.qualityStatus === 'معتمد ومطابق'
                              ? 'green'
                              : b.qualityStatus === 'مرفوض/معالجة'
                              ? 'red'
                              : 'gold'
                          }
                        >
                          {b.qualityStatus}
                        </GlassBadge>
                      </td>
                      <td className="py-3 px-4">
                        <span className="block">{b.supervisorOrEngineer}</span>
                        <span className="text-[10px] font-mono text-theme-muted">{b.productionDate}</span>
                      </td>
                      {canDelete && (
                        <td className="py-3 px-4">
                          <button
                            type="button"
                            onClick={() => handleDeleteBatch(b)}
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
        </div>
      )}

      {subTab === 'ORDERS' && (
        <GlassCard className="p-6">
          <h3 className="text-sm font-bold text-theme-primary mb-4">صرف خامات / تسجيل إنتاج تام بالمصنع</h3>
          <form onSubmit={handleSaveFactoryMove} className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
            <div>
              <label className="block font-bold text-theme-secondary mb-1">نوع الحركة</label>
              <select
                value={moveForm.kind}
                onChange={(e) => setMoveForm({ ...moveForm, kind: e.target.value as any })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                <option value="صرف خامات">صرف خامات للإنتاج</option>
                <option value="إنتاج تام">إنتاج تام منتج</option>
                <option value="توريد خامات">توريد خامات</option>
                <option value="تالف ومعالجة">تالف ومعالجة</option>
              </select>
            </div>
            <div>
              <label className="block font-bold text-theme-secondary mb-1">الصنف *</label>
              <select
                value={moveForm.itemId}
                onChange={(e) => setMoveForm({ ...moveForm, itemId: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                {factoryItems.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.name} (الرصيد: {itemBalances.get(Number(i.id)) ?? 0})
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block font-bold text-theme-secondary mb-1">الكمية *</label>
              <input
                type="number"
                step="any"
                required
                value={moveForm.qty}
                onChange={(e) => setMoveForm({ ...moveForm, qty: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block font-bold text-theme-secondary mb-1">رقم التشغيلة / الإذن</label>
              <input
                type="text"
                value={moveForm.batch}
                onChange={(e) => setMoveForm({ ...moveForm, batch: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
              />
            </div>
            <div className="sm:col-span-2">
              <label className="block font-bold text-theme-secondary mb-1">ملاحظات</label>
              <input
                type="text"
                value={moveForm.note}
                onChange={(e) => setMoveForm({ ...moveForm, note: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
            </div>
            <div className="sm:col-span-2 flex justify-end">
              <GlassButton type="submit" variant="primary" disabled={!canCreate}>
                تسجيل حركة المصنع
              </GlassButton>
            </div>
          </form>
        </GlassCard>
      )}

      {subTab === 'HALFA_OUTBOUND' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <GlassCard className="p-6 lg:col-span-5">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Truck className="w-4 h-4 text-pmp-cyan" />
              خروج المنتج وبوالص حلفا (Halfa Invoice)
            </h3>
            <form onSubmit={handleCreateHalfaOutbound} className="space-y-3 text-xs">
              <select
                value={halfaForm.productId}
                onChange={(e) => setHalfaForm({ ...halfaForm, productId: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                {products.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
              <select
                value={halfaForm.warehouseId}
                onChange={(e) => setHalfaForm({ ...halfaForm, warehouseId: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              >
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name} ({w.currency})
                  </option>
                ))}
              </select>
              <div className="grid grid-cols-2 gap-2">
                <input
                  type="number"
                  required
                  placeholder="الكمية *"
                  value={halfaForm.quantity}
                  onChange={(e) => setHalfaForm({ ...halfaForm, quantity: e.target.value })}
                  className="px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
                <input
                  type="number"
                  placeholder="سعر الوحدة"
                  value={halfaForm.unitPrice}
                  onChange={(e) => setHalfaForm({ ...halfaForm, unitPrice: e.target.value })}
                  className="px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <input
                type="text"
                placeholder="ملاحظات البوليصة / الترحيل"
                value={halfaForm.note}
                onChange={(e) => setHalfaForm({ ...halfaForm, note: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <GlassButton type="submit" variant="primary" className="w-full justify-center" disabled={!canCreate}>
                إصدار بوليصة خروج وتوريد للمخزن
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-7">
            <h4 className="text-sm font-bold text-theme-primary mb-3">بوالص خروج المنتج التام وحلفا</h4>
            <div className="overflow-x-auto">
              <table className="w-full text-right text-xs min-w-[460px]">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2">رقم البوليصة</th>
                    <th className="py-2">المنتج</th>
                    <th className="py-2">الكمية</th>
                    <th className="py-2">الإجمالي</th>
                    <th className="py-2">التاريخ</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {halfaInvoices
                    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
                    .map((h) => (
                      <tr key={h.id}>
                        <td className="py-2 font-mono font-bold text-pmp-cyan">{h.invoiceNo}</td>
                        <td className="py-2 font-bold">{h.productName}</td>
                        <td className="py-2 font-mono">{h.quantity}</td>
                        <td className="py-2 font-mono font-bold">
                          {Number(h.total || 0).toLocaleString()} {h.currency}
                        </td>
                        <td className="py-2 font-mono text-theme-muted">{h.invoiceDate}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {subTab === 'LOG' && (
        <GlassCard className="p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-right text-xs min-w-[600px]">
              <thead>
                <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                  <th className="py-3 px-4">التاريخ</th>
                  <th className="py-3 px-4">النوع</th>
                  <th className="py-3 px-4">الصنف</th>
                  <th className="py-3 px-4">الكمية</th>
                  <th className="py-3 px-4">الإذن / التشغيلة</th>
                  <th className="py-3 px-4">المسؤول</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {sortedMoves.map((m) => (
                  <tr key={m.id}>
                    <td className="py-3 px-4 font-mono text-theme-muted">{m.moveDate}</td>
                    <td className="py-3 px-4"><GlassBadge variant="cyan">{m.kind}</GlassBadge></td>
                    <td className="py-3 px-4 font-bold">{m.itemName}</td>
                    <td className="py-3 px-4 font-mono font-bold">{m.qty}</td>
                    <td className="py-3 px-4 font-mono">{m.batch}</td>
                    <td className="py-3 px-4 text-theme-muted">{m.actor}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>
      )}

      {/* New Production Batch Modal */}
      <Modal
        isOpen={isNewBatchOpen}
        onClose={() => setIsNewBatchOpen(false)}
        title="إصدار تشغيلة إنتاج جديدة (paint_batches)"
        maxWidth="max-w-lg"
      >
        <form onSubmit={handleCreateBatch} className="space-y-4 text-xs">
          <div>
            <label className="block font-bold text-theme-primary mb-1">التركيبة المختبرية المعتمدة</label>
            <select
              value={batchForm.formulaId}
              onChange={(e) => {
                const f = formulations.find((x) => Number(x.id) === Number(e.target.value));
                setBatchForm({
                  ...batchForm,
                  formulaId: Number(e.target.value),
                  productName: f?.productName || batchForm.productName,
                  baseKind: f?.baseKind || batchForm.baseKind
                });
              }}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
            >
              <option value={0}>-- تشغيلة مخصصة --</option>
              {formulations.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.formulaNo} — {f.productName} (PVC {f.calculatedPvc}%)
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-bold text-theme-primary mb-1">اسم المنتج *</label>
            <input
              type="text"
              required
              value={batchForm.productName}
              onChange={(e) => setBatchForm({ ...batchForm, productName: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-bold text-theme-primary mb-1">الوزن (كجم)</label>
              <input
                type="number"
                required
                value={batchForm.targetWeightKg}
                onChange={(e) => setBatchForm({ ...batchForm, targetWeightKg: e.target.value })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block font-bold text-theme-primary mb-1">عدد الكراتين</label>
              <input
                type="number"
                required
                value={batchForm.cartonsCount}
                onChange={(e) => setBatchForm({ ...batchForm, cartonsCount: e.target.value })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-bold text-theme-primary mb-1">معيار التعبئة</label>
              <input
                type="text"
                value={batchForm.packageUnit}
                onChange={(e) => setBatchForm({ ...batchForm, packageUnit: e.target.value })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
              />
            </div>
            <div>
              <label className="block font-bold text-theme-primary mb-1">حالة الجودة</label>
              <select
                value={batchForm.qualityStatus}
                onChange={(e) => setBatchForm({ ...batchForm, qualityStatus: e.target.value as any })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
              >
                <option value="تحت الفحص المخبري">تحت الفحص المخبري</option>
                <option value="معتمد ومطابق">معتمد ومطابق</option>
                <option value="مرفوض/معالجة">مرفوض / معالجة</option>
              </select>
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <GlassButton variant="secondary" onClick={() => setIsNewBatchOpen(false)}>
              إلغاء
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              اعتماد وبدء التشغيلة
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
