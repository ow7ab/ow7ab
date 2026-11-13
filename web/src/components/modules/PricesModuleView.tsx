import React, { useEffect, useMemo, useState } from 'react';
import { Edit2, Search, FileSpreadsheet, Printer, Plus, CheckCircle2 } from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { CurrencyCode, PosterPrice } from '../../types/erp';
import { ErpDataService, posterPriceDocId } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

/**
 * Fallback base matrix from Android `PosterPriceScreen.kt` (merged live with Firestore `poster_prices`).
 */
const ANDROID_DEFAULT_MATRIX: PosterPrice[] = [
  { id: '900_أبيض', weight: '900', kind: 'أبيض', price: 385000, priceEgp: 0, currency: 'SDG' },
  { id: '900_ألوان', weight: '900', kind: 'ألوان', price: 375000, priceEgp: 0, currency: 'SDG' },
  { id: '900_فضي', weight: '900', kind: 'فضي', price: 390000, priceEgp: 0, currency: 'SDG' },
  { id: '900_ذهبي', weight: '900', kind: 'ذهبي', price: 400000, priceEgp: 0, currency: 'SDG' },
  { id: '900_ورنيش', weight: '900', kind: 'ورنيش', price: 375000, priceEgp: 0, currency: 'SDG' },
  { id: '250_أبيض', weight: '250', kind: 'أبيض', price: 500000, priceEgp: 0, currency: 'SDG' },
  { id: '250_ألوان', weight: '250', kind: 'ألوان', price: 490000, priceEgp: 0, currency: 'SDG' },
  { id: '250_فضي', weight: '250', kind: 'فضي', price: 510000, priceEgp: 0, currency: 'SDG' },
  { id: '250_ذهبي', weight: '250', kind: 'ذهبي', price: 520000, priceEgp: 0, currency: 'SDG' },
  { id: '250_ورنيش', weight: '250', kind: 'ورنيش', price: 495000, priceEgp: 0, currency: 'SDG' },
  { id: '900_أبيض_EGP', weight: '900', kind: 'أبيض', price: 0, priceEgp: 1925, currency: 'EGP' },
  { id: '900_ألوان_EGP', weight: '900', kind: 'ألوان', price: 0, priceEgp: 1875, currency: 'EGP' },
  { id: '900_فضي_EGP', weight: '900', kind: 'فضي', price: 0, priceEgp: 1950, currency: 'EGP' },
  { id: '900_ذهبي_EGP', weight: '900', kind: 'ذهبي', price: 0, priceEgp: 2000, currency: 'EGP' },
  { id: '900_ورنيش_EGP', weight: '900', kind: 'ورنيش', price: 0, priceEgp: 1875, currency: 'EGP' },
  { id: '250_أبيض_EGP', weight: '250', kind: 'أبيض', price: 0, priceEgp: 2500, currency: 'EGP' },
  { id: '250_ألوان_EGP', weight: '250', kind: 'ألوان', price: 0, priceEgp: 2450, currency: 'EGP' },
  { id: '250_فضي_EGP', weight: '250', kind: 'فضي', price: 0, priceEgp: 2550, currency: 'EGP' },
  { id: '250_ذهبي_EGP', weight: '250', kind: 'ذهبي', price: 0, priceEgp: 2600, currency: 'EGP' },
  { id: '250_ورنيش_EGP', weight: '250', kind: 'ورنيش', price: 0, priceEgp: 2475, currency: 'EGP' }
];

export const PricesModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [cloudPrices, setCloudPrices] = useState<PosterPrice[]>([]);
  const [selectedCurrency, setSelectedCurrency] = useState<'SDG' | 'EGP'>('SDG');
  const [selectedWeight, setSelectedWeight] = useState<'ALL' | '250' | '900'>('ALL');
  const [discountPercent, setDiscountPercent] = useState<number>(0);
  const [searchTerm, setSearchTerm] = useState('');
  const [editingPrice, setEditingPrice] = useState<PosterPrice | null>(null);
  const [isAddOpen, setIsAddOpen] = useState(false);
  const [newItem, setNewItem] = useState({ weight: '900', kind: '', amount: '' });
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const unsub = ErpDataService.subscribeToCollection<PosterPrice>('poster_prices', setCloudPrices);
    return () => unsub();
  }, []);

  // Merge cloud prices with Android default keys keeping SDG & EGP strictly separated per PosterPriceScreen.kt
  const mergedPrices = useMemo(() => {
    const map = new Map<string, PosterPrice>();
    ANDROID_DEFAULT_MATRIX.forEach((p) => map.set(p.id, p));
    cloudPrices.forEach((cp) => {
      const curr: CurrencyCode = cp.currency || (String(cp.id).endsWith('_EGP') ? 'EGP' : 'SDG');
      const canonicalId = curr === 'EGP' ? `${cp.weight}_${cp.kind}_EGP` : `${cp.weight}_${cp.kind}`;
      map.set(canonicalId, {
        ...cp,
        id: canonicalId,
        currency: curr,
        price: Number(cp.price || 0),
        priceEgp: Number(cp.priceEgp || 0)
      });
    });
    return Array.from(map.values());
  }, [cloudPrices]);

  const filteredPrices = useMemo(() => {
    return mergedPrices.filter((p) => {
      const curr: CurrencyCode = p.currency || (String(p.id).endsWith('_EGP') ? 'EGP' : 'SDG');
      const matchesCurrency = curr === selectedCurrency;
      const matchesWeight = selectedWeight === 'ALL' || p.weight === selectedWeight;
      const matchesSearch = !searchTerm || p.kind.toLowerCase().includes(searchTerm.toLowerCase());
      return matchesCurrency && matchesWeight && matchesSearch;
    });
  }, [mergedPrices, selectedCurrency, selectedWeight, searchTerm]);

  const canEdit = canPerformAction(GRANULAR_ACTIONS.EDIT) || canPerformAction(GRANULAR_ACTIONS.CREATE);

  const handleSavePrice = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingPrice || !canEdit) return;
    const curr: CurrencyCode = editingPrice.currency || selectedCurrency;
    const canonicalId = curr === 'EGP' ? `${editingPrice.weight}_${editingPrice.kind}_EGP` : `${editingPrice.weight}_${editingPrice.kind}`;
    const docId = posterPriceDocId(editingPrice.weight, editingPrice.kind, curr);

    const payload: PosterPrice = {
      id: canonicalId,
      weight: editingPrice.weight,
      kind: editingPrice.kind,
      price: curr === 'SDG' ? Number(editingPrice.price) : 0,
      priceEgp: curr === 'EGP' ? Number(editingPrice.priceEgp) : 0,
      currency: curr
    };

    await ErpDataService.saveRecord('poster_prices', docId, payload, userProfile?.username);
    setEditingPrice(null);
    setNotice(`تم تحديث سعر ${payload.kind} (${payload.weight}جم - ${curr}) ومزامنته مع الأندرويد`);
    setTimeout(() => setNotice(null), 3500);
  };

  const handleAddKind = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canEdit || !newItem.kind.trim()) return;
    const val = Number(newItem.amount) || 0;
    const canonicalId =
      selectedCurrency === 'EGP'
        ? `${newItem.weight}_${newItem.kind.trim()}_EGP`
        : `${newItem.weight}_${newItem.kind.trim()}`;
    const docId = posterPriceDocId(newItem.weight, newItem.kind.trim(), selectedCurrency);

    const payload: PosterPrice = {
      id: canonicalId,
      weight: newItem.weight,
      kind: newItem.kind.trim(),
      price: selectedCurrency === 'SDG' ? val : 0,
      priceEgp: selectedCurrency === 'EGP' ? val : 0,
      currency: selectedCurrency
    };

    await ErpDataService.saveRecord('poster_prices', docId, payload, userProfile?.username);
    setIsAddOpen(false);
    setNewItem({ weight: '900', kind: '', amount: '' });
  };

  const handleExportCsv = () => {
    const headers = ['النوع', 'الوزن', 'العملة', 'السعر الرسمي', `بعد خصم ${discountPercent}%`];
    const rows = filteredPrices.map((p) => {
      const amt = selectedCurrency === 'SDG' ? p.price : p.priceEgp;
      return [p.kind, `${p.weight}جم`, selectedCurrency, amt, Math.round(amt * (1 - discountPercent / 100))];
    });
    ReportExporter.exportToCsv(`pmp_poster_prices_${selectedCurrency}`, headers, rows);
  };

  const handlePrint = () => {
    const headers = ['النوع', 'الوزن', 'السعر الرسمي', 'بعد الخصم'];
    const rows = filteredPrices.map((p) => {
      const amt = selectedCurrency === 'SDG' ? p.price : p.priceEgp;
      return [
        p.kind,
        `${p.weight}جم`,
        `${amt.toLocaleString()} ${selectedCurrency}`,
        `${Math.round(amt * (1 - discountPercent / 100)).toLocaleString()} ${selectedCurrency}`
      ];
    });
    ReportExporter.exportToPrint(`بوستر الأسعار المعتمد (${selectedCurrency}) - PMP PAINTS`, headers, rows);
  };

  return (
    <div className="space-y-6">
      <GlassCard className="p-5 sm:p-6">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-black text-theme-primary">
                {language === 'ar' ? 'بوستر وقوائم الأسعار' : 'Official Poster Prices'}
              </h2>
              <GlassBadge variant="gold">
                {cloudPrices.length > 0 ? `${cloudPrices.length} سعر سحابي` : 'الأسعار القياسية'}
              </GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              قوائم أسعار المصنع المفصولة بدقة حسب العملة (SDG للسودان / EGP لمصر) لعلب 250جم و 900جم.
            </p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
              CSV
            </GlassButton>
            <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
              طباعة البوستر
            </GlassButton>
            {canEdit && (
              <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={() => setIsAddOpen(true)} className="text-xs">
                + نوع سعر جديد
              </GlassButton>
            )}
          </div>
        </div>
      </GlassCard>

      {notice && (
        <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 text-xs flex items-center gap-2">
          <CheckCircle2 className="w-4 h-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <GlassCard className="p-4">
          <span className="text-xs text-theme-muted block mb-2">العملة (مفصولة عن بعضها تماماً)</span>
          <div className="grid grid-cols-2 p-1 rounded-xl bg-theme-base border border-theme">
            <button
              type="button"
              onClick={() => setSelectedCurrency('SDG')}
              className={`py-1.5 text-xs font-bold rounded-lg ${selectedCurrency === 'SDG' ? 'bg-pmp-primary text-white shadow' : 'text-theme-secondary'}`}
            >
              SDG (السودان)
            </button>
            <button
              type="button"
              onClick={() => setSelectedCurrency('EGP')}
              className={`py-1.5 text-xs font-bold rounded-lg ${selectedCurrency === 'EGP' ? 'bg-pmp-primary text-white shadow' : 'text-theme-secondary'}`}
            >
              EGP (مصر)
            </button>
          </div>
        </GlassCard>

        <GlassCard className="p-4">
          <span className="text-xs text-theme-muted block mb-2">الوزن القياسي</span>
          <div className="grid grid-cols-3 p-1 rounded-xl bg-theme-base border border-theme">
            {(['ALL', '250', '900'] as const).map((w) => (
              <button
                key={w}
                type="button"
                onClick={() => setSelectedWeight(w)}
                className={`py-1.5 text-xs font-bold rounded-lg ${selectedWeight === w ? 'bg-pmp-accent text-white shadow' : 'text-theme-secondary'}`}
              >
                {w === 'ALL' ? 'الكل' : `${w}جم`}
              </button>
            ))}
          </div>
        </GlassCard>

        <GlassCard className="p-4">
          <div className="flex justify-between items-center mb-1">
            <span className="text-xs text-theme-muted">خصم الجملة</span>
            <span className="font-bold text-pmp-primary text-xs">{discountPercent}%</span>
          </div>
          <input
            type="range"
            min={0}
            max={35}
            step={1}
            value={discountPercent}
            onChange={(e) => setDiscountPercent(Number(e.target.value))}
            className="w-full accent-pmp-primary cursor-pointer mt-2"
          />
        </GlassCard>
      </div>

      <GlassCard className="p-4">
        <div className="relative">
          <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
          <input
            type="text"
            placeholder="بحث بنوع الدهان..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary text-xs"
          />
        </div>
      </GlassCard>

      <GlassCard className="p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-right border-collapse text-xs min-w-[520px]">
            <thead>
              <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                <th className="py-3 px-4">النوع / اللون</th>
                <th className="py-3 px-4">الوزن</th>
                <th className="py-3 px-4">السعر الرسمي ({selectedCurrency})</th>
                <th className="py-3 px-4">بعد الخصم ({discountPercent}%)</th>
                {canEdit && <th className="py-3 px-4 text-center">تعديل</th>}
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {filteredPrices.map((p) => {
                const official = selectedCurrency === 'SDG' ? p.price : p.priceEgp;
                const discounted = official * (1 - discountPercent / 100);
                return (
                  <tr key={p.id} className="hover:bg-theme-surface/40">
                    <td className="py-3.5 px-4 font-bold text-theme-primary text-sm">{p.kind}</td>
                    <td className="py-3.5 px-4 font-mono font-bold">{p.weight}جم</td>
                    <td className="py-3.5 px-4 font-mono font-black text-sm">
                      {official.toLocaleString()} {selectedCurrency}
                    </td>
                    <td className="py-3.5 px-4 font-mono font-black text-sm text-emerald-400">
                      {Math.round(discounted).toLocaleString()} {selectedCurrency}
                    </td>
                    {canEdit && (
                      <td className="py-3.5 px-4 text-center">
                        <button
                          type="button"
                          onClick={() => setEditingPrice(p)}
                          className="p-1.5 rounded-lg bg-theme-base border border-theme hover:border-pmp-primary"
                        >
                          <Edit2 className="w-3.5 h-3.5" />
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

      {editingPrice && (
        <Modal
          isOpen={Boolean(editingPrice)}
          onClose={() => setEditingPrice(null)}
          title={`تعديل سعر (${selectedCurrency}): ${editingPrice.kind} - ${editingPrice.weight}جم`}
          maxWidth="max-w-md"
        >
          <form onSubmit={handleSavePrice} className="space-y-4 text-xs">
            <div>
              <label className="block font-bold text-theme-primary mb-1">
                السعر ({selectedCurrency})
              </label>
              <input
                type="number"
                required
                value={selectedCurrency === 'SDG' ? editingPrice.price : editingPrice.priceEgp}
                onChange={(e) => {
                  const v = Number(e.target.value);
                  setEditingPrice(
                    selectedCurrency === 'SDG'
                      ? { ...editingPrice, price: v }
                      : { ...editingPrice, priceEgp: v }
                  );
                }}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div className="flex justify-end gap-2">
              <GlassButton variant="secondary" onClick={() => setEditingPrice(null)}>
                إلغاء
              </GlassButton>
              <GlassButton type="submit" variant="primary">
                حفظ السعر بالسحابة
              </GlassButton>
            </div>
          </form>
        </Modal>
      )}

      <Modal
        isOpen={isAddOpen}
        onClose={() => setIsAddOpen(false)}
        title={`إضافة صنف بوستر جديد (${selectedCurrency})`}
        maxWidth="max-w-md"
      >
        <form onSubmit={handleAddKind} className="space-y-4 text-xs">
          <select
            value={newItem.weight}
            onChange={(e) => setNewItem({ ...newItem, weight: e.target.value })}
            className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
          >
            <option value="250">250 جرام</option>
            <option value="900">900 جرام</option>
          </select>
          <input
            type="text"
            required
            placeholder="النوع أو اللون (مثال: نحاسي)"
            value={newItem.kind}
            onChange={(e) => setNewItem({ ...newItem, kind: e.target.value })}
            className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
          />
          <input
            type="number"
            required
            placeholder={`السعر الرسمي (${selectedCurrency})`}
            value={newItem.amount}
            onChange={(e) => setNewItem({ ...newItem, amount: e.target.value })}
            className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary font-mono"
          />
          <div className="flex justify-end gap-2">
            <GlassButton variant="secondary" onClick={() => setIsAddOpen(false)}>
              إلغاء
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              إضافة
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
