import React, { useState } from 'react';
import {
  Tag,
  DollarSign,
  Plus,
  Edit2,
  Search,
  Filter,
  FileSpreadsheet,
  Printer,
  Sparkles,
  Layers,
  Percent
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { PosterPrice } from '../../types/erp';
import { ErpDataService } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export const PricesModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction } = useAuth();

  const [prices, setPrices] = useState<PosterPrice[]>(() => ErpDataService.getInitialPosterPrices());
  const [selectedCurrency, setSelectedCurrency] = useState<'SDG' | 'EGP'>('SDG');
  const [selectedWeight, setSelectedWeight] = useState<'ALL' | '250' | '900'>('ALL');
  const [discountPercent, setDiscountPercent] = useState<number>(15);
  const [searchTerm, setSearchTerm] = useState('');
  const [editingPrice, setEditingPrice] = useState<PosterPrice | null>(null);

  const filteredPrices = prices.filter(p => {
    const matchesCurrency = selectedCurrency === 'SDG' || p.priceEgp > 0;
    const matchesWeight = selectedWeight === 'ALL' || p.weight === selectedWeight;
    const matchesSearch = p.kind.toLowerCase().includes(searchTerm.toLowerCase());
    return matchesCurrency && matchesWeight && matchesSearch;
  });

  const handleSavePrice = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingPrice) return;

    setPrices(prev => prev.map(p => (p.id === editingPrice.id ? editingPrice : p)));
    await ErpDataService.saveRecord('poster_prices', editingPrice.id, editingPrice);
    setEditingPrice(null);
  };

  const handleExportCsv = () => {
    const headers = ['النوع / اللون', 'معيار العبوة', 'السعر الرسمي (SDG)', 'السعر الرسمي (EGP)', 'السعر بعد خصم التجار'];
    const rows = filteredPrices.map(p => {
      const activePrice = selectedCurrency === 'SDG' ? p.price : p.priceEgp;
      const discounted = activePrice * (1 - discountPercent / 100);
      return [p.kind, `${p.weight}جم`, p.price, p.priceEgp, discounted.toFixed(0)];
    });
    ReportExporter.exportToCsv('pmp_official_price_list', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['النوع', 'الوزن', 'السعر الرسمي', 'سعر الجملة المخفض'];
    const rows = filteredPrices.map(p => {
      const activePrice = selectedCurrency === 'SDG' ? p.price : p.priceEgp;
      const discounted = activePrice * (1 - discountPercent / 100);
      return [p.kind, `${p.weight}جم`, `${activePrice.toLocaleString()} ${selectedCurrency}`, `${discounted.toLocaleString()} ${selectedCurrency}`];
    });
    ReportExporter.exportToPrint('قائمة الأسعار المعتمدة للوكلاء والتجار - PMP PAINTS', headers, rows);
  };

  return (
    <div className="space-y-6">
      {/* Title Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'قوائم الأسعار والخصومات (Price Lists & Discounts)' : 'Official Price Lists'}
            </h2>
            <GlassBadge variant="gold">
              {language === 'ar' ? 'معتمدة للجمهورية ومصر' : 'Dual Currency'}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'أسعار المصنع المعتمدة لعلب 250جم و900جم بالعملتين (SDG وEGP) وحساب فوري لخصومات الجملة.'
              : 'Official factory prices per carton & can in SDG and EGP with live wholesale margin simulator.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
            {language === 'ar' ? 'تصدير CSV' : 'Export CSV'}
          </GlassButton>
          <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
            {language === 'ar' ? 'طباعة القائمة' : 'Print'}
          </GlassButton>
        </div>
      </div>

      {/* Calculator & Currency Switcher Bar */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Currency Switcher */}
        <GlassCard className="p-4">
          <span className="text-xs text-theme-muted block mb-2">{language === 'ar' ? 'عملة العرض والتسعير' : 'Currency'}</span>
          <div className="grid grid-cols-2 p-1 rounded-xl bg-theme-surface border border-theme">
            <button
              onClick={() => setSelectedCurrency('SDG')}
              className={`py-1.5 text-xs font-bold rounded-lg transition-all ${
                selectedCurrency === 'SDG' ? 'bg-pmp-primary text-white shadow' : 'text-theme-secondary'
              }`}
            >
              الجنيه السوداني (SDG)
            </button>
            <button
              onClick={() => setSelectedCurrency('EGP')}
              className={`py-1.5 text-xs font-bold rounded-lg transition-all ${
                selectedCurrency === 'EGP' ? 'bg-pmp-primary text-white shadow' : 'text-theme-secondary'
              }`}
            >
              الجنيه المصري (EGP)
            </button>
          </div>
        </GlassCard>

        {/* Weight Filter */}
        <GlassCard className="p-4">
          <span className="text-xs text-theme-muted block mb-2">{language === 'ar' ? 'تصفية حجم العبوة' : 'Can Weight'}</span>
          <div className="grid grid-cols-3 p-1 rounded-xl bg-theme-surface border border-theme">
            <button
              onClick={() => setSelectedWeight('ALL')}
              className={`py-1.5 text-xs font-bold rounded-lg transition-all ${selectedWeight === 'ALL' ? 'bg-pmp-accent text-white shadow' : 'text-theme-secondary'}`}
            >
              الكل
            </button>
            <button
              onClick={() => setSelectedWeight('250')}
              className={`py-1.5 text-xs font-bold rounded-lg transition-all ${selectedWeight === '250' ? 'bg-pmp-accent text-white shadow' : 'text-theme-secondary'}`}
            >
              250جم
            </button>
            <button
              onClick={() => setSelectedWeight('900')}
              className={`py-1.5 text-xs font-bold rounded-lg transition-all ${selectedWeight === '900' ? 'bg-pmp-accent text-white shadow' : 'text-theme-secondary'}`}
            >
              900جم
            </button>
          </div>
        </GlassCard>

        {/* Wholesale Discount Simulator */}
        <GlassCard className="p-4">
          <div className="flex justify-between items-center mb-1">
            <span className="text-xs text-theme-muted">{language === 'ar' ? 'محاكي خصم كبار الوكلاء' : 'Wholesale Discount'}</span>
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

      {/* Search Input */}
      <GlassCard className="p-4">
        <div className="relative">
          <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
          <input
            type="text"
            placeholder={language === 'ar' ? 'بحث بنوع الدهان والطلاء...' : 'Search paint kinds...'}
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
          />
        </div>
      </GlassCard>

      {/* Pricing Table */}
      <GlassCard className="p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-right border-collapse text-xs">
            <thead>
              <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                <th className="py-3 px-4">{language === 'ar' ? 'نوع الدهان والطلاء' : 'Paint Kind'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'حجم العبوة' : 'Weight'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'السعر الرسمي للكرتونة' : 'Official Price'}</th>
                <th className="py-3 px-4">{language === 'ar' ? `سعر الجملة بعد الخصم (${discountPercent}%)` : 'Discounted Price'}</th>
                <th className="py-3 px-4 text-center">{language === 'ar' ? 'تعديل السعر' : 'Edit'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {filteredPrices.map(p => {
                const official = selectedCurrency === 'SDG' ? p.price : p.priceEgp;
                const discounted = official * (1 - discountPercent / 100);

                return (
                  <tr key={p.id} className="hover:bg-theme-surface/40 transition-colors">
                    <td className="py-3.5 px-4 font-bold text-theme-primary text-sm">{p.kind}</td>
                    <td className="py-3.5 px-4 font-mono font-bold text-theme-primary">{p.weight}جم</td>
                    <td className="py-3.5 px-4 font-black text-sm text-theme-primary">
                      {official.toLocaleString()} {selectedCurrency}
                    </td>
                    <td className="py-3.5 px-4 font-black text-sm text-green-500">
                      {discounted.toLocaleString(undefined, { maximumFractionDigits: 0 })} {selectedCurrency}
                    </td>
                    <td className="py-3.5 px-4 text-center">
                      {canPerformAction('صلاحية التعديل') && (
                        <button
                          onClick={() => setEditingPrice(p)}
                          className="p-1.5 rounded-lg bg-theme-surface border border-theme text-theme-primary hover:border-pmp-primary hover:text-pmp-primary transition-all"
                        >
                          <Edit2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </GlassCard>

      {/* Edit Price Modal */}
      {editingPrice && (
        <Modal
          isOpen={Boolean(editingPrice)}
          onClose={() => setEditingPrice(null)}
          title={`تعديل سعر: ${editingPrice.kind} (${editingPrice.weight}جم)`}
          maxWidth="max-w-md"
        >
          <form onSubmit={handleSavePrice} className="space-y-4 text-xs">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">السعر بالجنيه السوداني (SDG)</label>
              <input
                type="number"
                required
                value={editingPrice.price}
                onChange={(e) => setEditingPrice({ ...editingPrice, price: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">السعر بالجنيه المصري (EGP)</label>
              <input
                type="number"
                required
                value={editingPrice.priceEgp}
                onChange={(e) => setEditingPrice({ ...editingPrice, priceEgp: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div className="flex justify-end gap-3 pt-3 border-t border-theme">
              <GlassButton variant="secondary" onClick={() => setEditingPrice(null)}>إلغاء</GlassButton>
              <GlassButton type="submit" variant="primary">حفظ واعتماد السعر</GlassButton>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
};
