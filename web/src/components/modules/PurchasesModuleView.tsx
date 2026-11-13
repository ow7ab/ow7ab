import React, { useEffect, useMemo, useState } from 'react';
import {
  ShoppingCart,
  Plus,
  Package,
  CheckCircle,
  Truck,
  Trash2,
  AlertCircle
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';
import { ErpDataService } from '../../services/ErpDataService';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { Cashbox, CurrencyCode, FactoryItem, FactoryMove, TreasuryMovement } from '../../types/erp';

export const PurchasesModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { userProfile, canPerformAction } = useAuth();

  const [activeSubTab, setActiveSubTab] = useState<'RECORD_PURCHASE' | 'ITEMS_DIRECTORY' | 'PURCHASE_HISTORY'>('RECORD_PURCHASE');
  const [factoryItems, setFactoryItems] = useState<FactoryItem[]>([]);
  const [factoryMoves, setFactoryMoves] = useState<FactoryMove[]>([]);
  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);

  const [selectedItemId, setSelectedItemId] = useState<number>(0);
  const [qty, setQty] = useState<string>('');
  const [purchaseAmount, setPurchaseAmount] = useState<string>('');
  const [currency, setCurrency] = useState<CurrencyCode>('EGP');
  const [paidCashbox, setPaidCashbox] = useState<string>('');
  const [supplier, setSupplier] = useState<string>('');
  const [note, setNote] = useState<string>('');
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  const [newItemName, setNewItemName] = useState('');
  const [newItemCategory, setNewItemCategory] = useState('raw');
  const [newItemUnit, setNewItemUnit] = useState('كجم');
  const [newItemCost, setNewItemCost] = useState('0');
  const [newItemLinkedEgypt, setNewItemLinkedEgypt] = useState(true);

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<FactoryItem>('factory_items', setFactoryItems),
      ErpDataService.subscribeToCollection<FactoryMove>('factory_moves', setFactoryMoves),
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setCashboxes)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  useEffect(() => {
    if (factoryItems.length > 0 && !factoryItems.some((i) => Number(i.id) === selectedItemId)) {
      setSelectedItemId(Number(factoryItems[0].id));
    }
  }, [factoryItems, selectedItemId]);

  const liveBalances = useMemo(
    () => ErpDataService.computeFactoryItemBalances(factoryItems, factoryMoves),
    [factoryItems, factoryMoves]
  );

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE) || canPerformAction(GRANULAR_ACTIONS.INVENTORY_OPS);
  const canDelete = canPerformAction(GRANULAR_ACTIONS.DELETE);

  const handleSavePurchase = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate) {
      showNotice('err', 'لا تملك صلاحية تسجيل المشتريات وتوريد الخامات');
      return;
    }
    const item = factoryItems.find((i) => Number(i.id) === Number(selectedItemId));
    const numQty = Number(qty);
    const numAmount = Number(purchaseAmount) || 0;
    if (!item || !Number.isFinite(numQty) || numQty <= 0) {
      showNotice('err', 'اختر المادة الخام وأدخل الكمية الموردة بشكل صحيح');
      return;
    }

    const moveId = Date.now();
    const actor = userProfile?.fullName || userProfile?.username || 'WEB_USER';
    const newMove: FactoryMove = {
      id: moveId,
      moveDate: new Date().toISOString().split('T')[0],
      kind: 'توريد خامات',
      itemId: Number(item.id),
      itemName: item.name,
      qty: numQty,
      batch: `PO-${String(moveId).slice(-5)}`,
      note: `${note.trim() || 'شراء وتوريد خامات'}${numAmount > 0 ? ` • التكلفة: ${numAmount} ${currency}` : ''}`,
      actor,
      counterparty: supplier.trim() || 'مورد خامات',
      linked: Boolean(item.linkedToEgypt),
      createdAt: moveId
    };

    try {
      await ErpDataService.saveRecord('factory_moves', `FM-${moveId}`, newMove, userProfile?.username);

      if (paidCashbox && numAmount > 0) {
        const boxObj = cashboxes.find((c) => c.name === paidCashbox);
        const movId = moveId + 1;
        const cashMov: TreasuryMovement = {
          id: movId,
          txDate: newMove.moveDate,
          cashbox: paidCashbox,
          currency: boxObj?.currency || currency,
          direction: 'منصرف',
          amount: numAmount,
          description: `شراء ${item.name} (${numQty} ${item.unit}) — ${newMove.counterparty}`,
          reference: newMove.batch,
          createdBy: actor,
          createdAt: movId
        };
        await ErpDataService.saveRecord('movements', `MOV-${movId}`, cashMov, userProfile?.username);
      }

      await ErpDataService.logAudit(
        userProfile?.username || 'WEB',
        'PURCHASE_SUPPLY',
        `توريد ${numQty} ${item.unit} من ${item.name} — ${newMove.counterparty}`
      );
      setQty('');
      setPurchaseAmount('');
      setNote('');
      showNotice('ok', 'تم تسجيل توريد المشتريات ومزامنته مع المخزن والخزينة بنجاح');
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر حفظ إذن التوريد');
    }
  };

  const handleCreateItem = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || !newItemName.trim()) return;

    const itemId = Date.now();
    const newItem: FactoryItem = {
      id: itemId,
      name: newItemName.trim(),
      unit: newItemUnit.trim() || 'كجم',
      category: newItemCategory,
      linkedToEgypt: newItemLinkedEgypt,
      defaultCurrency: currency,
      unitCostEgp: Number(newItemCost) || 0,
      reorderLevel: 10
    };

    try {
      await ErpDataService.saveRecord('factory_items', `FI-${itemId}`, newItem, userProfile?.username);
      setNewItemName('');
      showNotice('ok', `تمت إضافة الصنف "${newItem.name}" إلى دليل خامات المصنع`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر حفظ الصنف');
    }
  };

  const handleDeleteMove = async (move: FactoryMove) => {
    if (!canDelete) return;
    const docId = (move as any)._docId || `FM-${move.id}`;
    await ErpDataService.deleteRecord('factory_moves', docId, move.id, userProfile?.username);
    showNotice('ok', 'تم حذف حركة التوريد');
  };

  const purchaseMoves = useMemo(
    () =>
      [...factoryMoves]
        .filter((m) => m.kind === 'توريد خامات' || (m.kind || '').includes('توريد'))
        .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)),
    [factoryMoves]
  );

  return (
    <div className="space-y-6">
      <GlassCard className="p-5 sm:p-6 bg-gradient-to-l from-theme-surface to-red-600/10 border-theme">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
          <div className="flex items-center gap-3">
            <div className="w-11 h-11 rounded-2xl bg-red-600/20 text-red-500 border border-red-500/30 flex items-center justify-center shrink-0">
              <ShoppingCart className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-xl font-black text-theme-primary">
                  {language === 'ar' ? 'المشتريات وتوريدات الخامات' : 'Purchases & Procurement'}
                </h2>
                <GlassBadge variant="red">{factoryItems.length} صنف</GlassBadge>
              </div>
              <p className="text-xs text-theme-secondary mt-1">
                {language === 'ar'
                  ? 'توريد خامات المصنع، العبوات الفارغة، الربط الاختياري بسداد الخزينة، وإدارة دليل المواد الخام.'
                  : 'Raw material procurement synced 1:1 with Android FactoryItem & FactoryMove.'}
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2 mt-5 pt-4 border-t border-theme overflow-x-auto pb-1">
          {[
            { id: 'RECORD_PURCHASE' as const, label: 'توريد خامات جديدة' },
            { id: 'ITEMS_DIRECTORY' as const, label: `دليل الخامات (${factoryItems.length})` },
            { id: 'PURCHASE_HISTORY' as const, label: `سجل التوريدات (${purchaseMoves.length})` }
          ].map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setActiveSubTab(t.id)}
              className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition-all ${
                activeSubTab === t.id
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
          {notice.type === 'ok' ? <CheckCircle className="w-4 h-4 shrink-0" /> : <AlertCircle className="w-4 h-4 shrink-0" />}
          <span>{notice.text}</span>
        </div>
      )}

      {activeSubTab === 'RECORD_PURCHASE' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <div className="lg:col-span-7">
            <GlassCard className="p-6">
              <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
                <Plus className="w-4 h-4 text-pmp-primary" />
                تسجيل إذن توريد / فاتورة مشتريات خامات
              </h3>

              <form onSubmit={handleSavePurchase} className="space-y-4 text-xs">
                <div>
                  <label className="block font-bold text-theme-secondary mb-1">الصنف / المادة الخام *</label>
                  <select
                    required
                    value={selectedItemId}
                    onChange={(e) => setSelectedItemId(Number(e.target.value))}
                    className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  >
                    {factoryItems.length === 0 && <option value={0}>أضف مادة خام أولاً من الدليل</option>}
                    {factoryItems.map((item) => {
                      const bal = liveBalances.get(Number(item.id)) ?? 0;
                      return (
                        <option key={item.id} value={item.id}>
                          {item.name} ({item.unit}) — الرصيد: {bal}
                        </option>
                      );
                    })}
                  </select>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label className="block font-bold text-theme-secondary mb-1">الكمية الموردة *</label>
                    <input
                      type="number"
                      step="any"
                      required
                      value={qty}
                      onChange={(e) => setQty(e.target.value)}
                      placeholder="0"
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                    />
                  </div>
                  <div>
                    <label className="block font-bold text-theme-secondary mb-1">قيمة الفاتورة (اختياري)</label>
                    <input
                      type="number"
                      step="any"
                      value={purchaseAmount}
                      onChange={(e) => setPurchaseAmount(e.target.value)}
                      placeholder="0"
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                    />
                  </div>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label className="block font-bold text-theme-secondary mb-1">خصم القيمة من خزنة</label>
                    <select
                      value={paidCashbox}
                      onChange={(e) => setPaidCashbox(e.target.value)}
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                    >
                      <option value="">آجل / بدون خصم مباشر من الخزنة</option>
                      {cashboxes.map((c) => (
                        <option key={c.name} value={c.name}>
                          {c.name} ({c.currency})
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="block font-bold text-theme-secondary mb-1">العملة</label>
                    <select
                      value={currency}
                      onChange={(e) => setCurrency(e.target.value as CurrencyCode)}
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                    >
                      <option value="EGP">EGP — جنيه مصري</option>
                      <option value="SDG">SDG — جنيه سوداني</option>
                      <option value="USD">USD — دولار أمريكي</option>
                    </select>
                  </div>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label className="block font-bold text-theme-secondary mb-1">اسم المورد *</label>
                    <input
                      type="text"
                      required
                      value={supplier}
                      onChange={(e) => setSupplier(e.target.value)}
                      placeholder="اسم المورد أو الشركة الموردة"
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                    />
                  </div>
                  <div>
                    <label className="block font-bold text-theme-secondary mb-1">ملاحظات التوريد</label>
                    <input
                      type="text"
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                      placeholder="رقم البوليصة / تفاصيل"
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                    />
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={!canCreate}
                  className="w-full py-2.5 rounded-xl bg-red-600 hover:bg-red-700 text-white font-bold text-xs shadow-md transition-all flex items-center justify-center gap-2 disabled:opacity-50"
                >
                  <CheckCircle className="w-4 h-4" />
                  <span>حفظ إذن التوريد وتحديث الأرصدة السحابية</span>
                </button>
              </form>
            </GlassCard>
          </div>

          <div className="lg:col-span-5">
            <GlassCard className="p-6">
              <h3 className="text-sm font-bold text-theme-primary mb-3 flex items-center gap-2">
                <Package className="w-4 h-4 text-pmp-gold" />
                الأرصدة الفعلية الحالية بالمخزن الخام
              </h3>
              <div className="space-y-2.5 max-h-[380px] overflow-y-auto">
                {factoryItems.length === 0 ? (
                  <p className="text-xs text-theme-muted">لا توجد مواد خام مسجلة بعد.</p>
                ) : (
                  factoryItems.map((item) => {
                    const bal = liveBalances.get(Number(item.id)) ?? 0;
                    const reorder = item.reorderLevel ?? 10;
                    return (
                      <div
                        key={item.id}
                        className="p-2.5 rounded-xl bg-theme-base border border-theme flex justify-between items-center text-xs gap-2"
                      >
                        <div className="min-w-0">
                          <span className="font-bold text-theme-primary block truncate">{item.name}</span>
                          <span className="text-[10px] text-theme-muted">{item.category} • {item.unit}</span>
                        </div>
                        <div className="text-left shrink-0">
                          <span className={`font-mono font-bold block ${bal <= reorder ? 'text-amber-400' : 'text-emerald-400'}`}>
                            {bal.toLocaleString()}
                          </span>
                          <span className="text-[9px] text-theme-muted">حد الطلب: {reorder}</span>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </GlassCard>
          </div>
        </div>
      )}

      {activeSubTab === 'ITEMS_DIRECTORY' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <GlassCard className="p-6 lg:col-span-4">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Plus className="w-4 h-4 text-pmp-primary" />
              إضافة مادة خام / عبوة جديدة
            </h3>
            <form onSubmit={handleCreateItem} className="space-y-3 text-xs">
              <div>
                <label className="block font-bold text-theme-secondary mb-1">اسم الصنف *</label>
                <input
                  type="text"
                  required
                  value={newItemName}
                  onChange={(e) => setNewItemName(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">الفئة</label>
                <select
                  value={newItemCategory}
                  onChange={(e) => setNewItemCategory(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  <option value="raw">مادة خام (raw)</option>
                  <option value="empty">عبوات فارغة وتعبئة (empty)</option>
                  <option value="finished">منتج تام مصنع (finished)</option>
                </select>
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">الوحدة</label>
                <input
                  type="text"
                  required
                  value={newItemUnit}
                  onChange={(e) => setNewItemUnit(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">التكلفة التقريبية (EGP)</label>
                <input
                  type="number"
                  step="any"
                  value={newItemCost}
                  onChange={(e) => setNewItemCost(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <label className="flex items-center gap-2 cursor-pointer pt-1">
                <input
                  type="checkbox"
                  checked={newItemLinkedEgypt}
                  onChange={(e) => setNewItemLinkedEgypt(e.target.checked)}
                />
                <span>توريد ومرتبط بمصنع مصر</span>
              </label>
              <button
                type="submit"
                disabled={!canCreate}
                className="w-full py-2.5 rounded-xl bg-red-600 hover:bg-red-700 text-white font-bold text-xs shadow-md disabled:opacity-50"
              >
                حفظ الصنف في السحابة
              </button>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-8">
            <h3 className="text-sm font-bold text-theme-primary mb-4">دليل خامات المصنع ومواد التعبئة</h3>
            <div className="overflow-x-auto">
              <table className="w-full text-right text-xs min-w-[480px]">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2.5">الاسم</th>
                    <th className="py-2.5">الفئة</th>
                    <th className="py-2.5">الوحدة</th>
                    <th className="py-2.5">التكلفة</th>
                    <th className="py-2.5">الرصيد المحسوب</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {factoryItems.map((item) => (
                    <tr key={item.id}>
                      <td className="py-2.5 font-bold text-theme-primary">{item.name}</td>
                      <td className="py-2.5"><GlassBadge variant="cyan">{item.category}</GlassBadge></td>
                      <td className="py-2.5">{item.unit}</td>
                      <td className="py-2.5 font-mono">{item.unitCostEgp || 0} {item.defaultCurrency || 'EGP'}</td>
                      <td className="py-2.5 font-mono font-bold text-pmp-primary">
                        {(liveBalances.get(Number(item.id)) ?? 0).toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {activeSubTab === 'PURCHASE_HISTORY' && (
        <GlassCard className="p-6">
          <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
            <Truck className="w-4 h-4 text-pmp-cyan" />
            سجل أذونات التوريد والمشتريات
          </h3>
          <div className="overflow-x-auto">
            <table className="w-full text-right text-xs min-w-[540px]">
              <thead>
                <tr className="border-b border-theme text-theme-muted">
                  <th className="py-2.5">التاريخ</th>
                  <th className="py-2.5">الصنف</th>
                  <th className="py-2.5">الكمية</th>
                  <th className="py-2.5">المورد</th>
                  <th className="py-2.5">الإذن / الملاحظات</th>
                  <th className="py-2.5">المسؤول</th>
                  {canDelete && <th className="py-2.5">حذف</th>}
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {purchaseMoves.map((move) => (
                  <tr key={move.id}>
                    <td className="py-2.5 font-mono text-theme-secondary">{move.moveDate}</td>
                    <td className="py-2.5 font-bold text-theme-primary">{move.itemName}</td>
                    <td className="py-2.5 font-mono font-bold text-emerald-400">+{move.qty}</td>
                    <td className="py-2.5">{move.counterparty || '-'}</td>
                    <td className="py-2.5 text-theme-muted">
                      <span className="font-mono text-pmp-gold block">{move.batch}</span>
                      <span>{move.note}</span>
                    </td>
                    <td className="py-2.5 text-theme-muted">{move.actor}</td>
                    {canDelete && (
                      <td className="py-2.5">
                        <button
                          type="button"
                          onClick={() => handleDeleteMove(move)}
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
