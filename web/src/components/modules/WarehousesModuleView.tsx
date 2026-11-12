import React, { useState } from 'react';
import {
  Package,
  Boxes,
  Plus,
  AlertTriangle,
  Search,
  Filter,
  Building,
  FileSpreadsheet,
  Printer,
  CheckCircle2,
  Layers
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { Product, RawMaterialItem } from '../../types/erp';
import { ErpDataService } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export const WarehousesModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [activeTab, setActiveTab] = useState<'FINISHED' | 'RAW'>('FINISHED');
  const [products, setProducts] = useState<Product[]>(() => ErpDataService.getInitialProducts());
  const [rawMaterials, setRawMaterials] = useState<RawMaterialItem[]>(() => ErpDataService.getInitialRawMaterials());
  const [searchTerm, setSearchTerm] = useState('');
  const [isNewItemOpen, setIsNewItemOpen] = useState(false);

  // New Item Form State
  const [newProduct, setNewProduct] = useState({
    name: '',
    paintColor: 'أبيض',
    category: 'مطاطي',
    packageWeight: '900' as '250' | '900' | '4000',
    salePriceSdg: 420000,
    salePriceEgp: 480,
    currentStock: 100,
    minimumStock: 20
  });

  const filteredProducts = products.filter(p =>
    p.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    p.paintColor.toLowerCase().includes(searchTerm.toLowerCase()) ||
    p.category.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const filteredRaw = rawMaterials.filter(r =>
    r.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    r.materialCode.toLowerCase().includes(searchTerm.toLowerCase()) ||
    r.branch.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const handleCreateProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    const created: Product = {
      id: Date.now(),
      name: newProduct.name,
      paintColor: newProduct.paintColor,
      category: newProduct.category,
      packageWeight: newProduct.packageWeight,
      salePriceSdg: Number(newProduct.salePriceSdg),
      salePriceEgp: Number(newProduct.salePriceEgp),
      currentStock: Number(newProduct.currentStock),
      minimumStock: Number(newProduct.minimumStock),
      unit: 'كرتونة'
    };

    setProducts(prev => [created, ...prev]);
    await ErpDataService.saveRecord('products', `PROD-${Date.now()}`, created);
    setIsNewItemOpen(false);
  };

  const handleExportCsv = () => {
    if (activeTab === 'FINISHED') {
      const headers = ['اسم المنتج', 'اللون', 'التصنيف', 'الوزن', 'سعر البيع (SDG)', 'سعر البيع (EGP)', 'الرصيد الفعلي (كرتونة)', 'حد الأمان'];
      const rows = filteredProducts.map(p => [
        p.name, p.paintColor, p.category, `${p.packageWeight}جم`, p.salePriceSdg, p.salePriceEgp, p.currentStock, p.minimumStock
      ]);
      ReportExporter.exportToCsv('pmp_finished_goods_inventory', headers, rows);
    } else {
      const headers = ['كود المادة', 'اسم الخامة', 'نوع المادة', 'الوحدة', 'الرصيد الفعلي', 'المحجوز', 'حد الطلب', 'تكلفة الوحدة (EGP)', 'الفرع'];
      const rows = filteredRaw.map(r => [
        r.materialCode, r.name, r.materialType, r.unit, r.actualBalance, r.reservedBalance, r.reorderLevel, r.unitCostEgp, r.branch
      ]);
      ReportExporter.exportToCsv('pmp_raw_materials_inventory', headers, rows);
    }
  };

  const handlePrint = () => {
    if (activeTab === 'FINISHED') {
      const headers = ['الصنف', 'اللون', 'الوزن', 'الرصيد الفعلي', 'سعر البيع SDG', 'سعر البيع EGP'];
      const rows = filteredProducts.map(p => [
        p.name, p.paintColor, `${p.packageWeight}جم`, p.currentStock, p.salePriceSdg.toLocaleString(), p.salePriceEgp.toLocaleString()
      ]);
      ReportExporter.exportToPrint('جرد مستودعات المنتج التام - PMP PAINTS', headers, rows);
    } else {
      const headers = ['الكود', 'الخامة', 'النوع', 'الرصيد', 'حد الطلب', 'الفرع'];
      const rows = filteredRaw.map(r => [
        r.materialCode, r.name, r.materialType, `${r.actualBalance} ${r.unit}`, r.reorderLevel, r.branch
      ]);
      ReportExporter.exportToPrint('جرد مستودعات المواد الخام والبراميل - PMP PAINTS', headers, rows);
    }
  };

  return (
    <div className="space-y-6">
      {/* Title Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'المخازن والمستودعات (Warehouses & Stock)' : 'Warehouses & Inventory Control'}
            </h2>
            <GlassBadge variant="cyan">
              {activeTab === 'FINISHED' ? `${products.length} صنف تام` : `${rawMaterials.length} مادة خام`}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'جرد المنتج التام بكراتين التعبئة الثلاث (250جم، 900جم، 4000جم)، براميل الخامات، وحدود الأمان.'
              : 'Finished products stock, 3 packaging standards, raw materials ledger, and reorder levels.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
            {language === 'ar' ? 'تصدير Excel/CSV' : 'Export CSV'}
          </GlassButton>
          <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
            {language === 'ar' ? 'طباعة' : 'Print'}
          </GlassButton>
          {canPerformAction('عمليات المخزون') && activeTab === 'FINISHED' && (
            <GlassButton variant="primary" icon={<Plus className="w-4 h-4" />} onClick={() => setIsNewItemOpen(true)} className="text-xs">
              {language === 'ar' ? 'إضافة منتج تام' : 'New Product'}
            </GlassButton>
          )}
        </div>
      </div>

      {/* Tabs Switch */}
      <div className="flex items-center gap-2 border-b border-theme pb-2">
        <button
          onClick={() => setActiveTab('FINISHED')}
          className={`px-4 py-2 rounded-xl text-xs font-bold transition-all ${
            activeTab === 'FINISHED'
              ? 'bg-pmp-primary text-white shadow-md'
              : 'bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary'
          }`}
        >
          {language === 'ar' ? 'مستودع المنتج التام (Finished Goods)' : 'Finished Goods'}
        </button>
        <button
          onClick={() => setActiveTab('RAW')}
          className={`px-4 py-2 rounded-xl text-xs font-bold transition-all ${
            activeTab === 'RAW'
              ? 'bg-pmp-primary text-white shadow-md'
              : 'bg-theme-surface border border-theme text-theme-secondary hover:text-theme-primary'
          }`}
        >
          {language === 'ar' ? 'مستودع المواد الخام والبراميل (Raw Materials)' : 'Raw Materials'}
        </button>
      </div>

      {/* Search Bar */}
      <GlassCard className="p-4">
        <div className="relative">
          <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
          <input
            type="text"
            placeholder={
              activeTab === 'FINISHED'
                ? (language === 'ar' ? 'بحث باسم الدهان، اللون، أو التصنيف...' : 'Search finished goods...')
                : (language === 'ar' ? 'بحث بكود الخامة، الاسم، أو الفرع...' : 'Search raw materials...')
            }
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
          />
        </div>
      </GlassCard>

      {/* Content Table */}
      {activeTab === 'FINISHED' ? (
        <GlassCard className="p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-right border-collapse text-xs">
              <thead>
                <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                  <th className="py-3 px-4">{language === 'ar' ? 'اسم المنتج والطلاء' : 'Product Name'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'اللون والتصنيف' : 'Color & Category'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'حجم العبوة' : 'Can Weight'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'الرصيد المتوفر' : 'In Stock'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'سعر البيع (SDG)' : 'Price (SDG)'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'سعر البيع (EGP)' : 'Price (EGP)'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'حالة المخزون' : 'Stock Level'}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {filteredProducts.map(p => (
                  <tr key={p.id} className="hover:bg-theme-surface/40 transition-colors">
                    <td className="py-3.5 px-4 font-bold text-theme-primary text-sm">{p.name}</td>
                    <td className="py-3.5 px-4">
                      <span className="font-semibold text-theme-primary block">{p.paintColor}</span>
                      <span className="text-[10px] text-theme-muted">{p.category}</span>
                    </td>
                    <td className="py-3.5 px-4 font-mono font-bold text-theme-primary">
                      {p.packageWeight}جم
                    </td>
                    <td className="py-3.5 px-4">
                      <span className="font-bold text-pmp-accent block text-sm">{p.currentStock} كرتونة</span>
                      <span className="text-[10px] text-theme-muted">حد الأمان: {p.minimumStock}</span>
                    </td>
                    <td className="py-3.5 px-4 font-black text-theme-primary">
                      {p.salePriceSdg.toLocaleString()} SDG
                    </td>
                    <td className="py-3.5 px-4 font-black text-theme-primary">
                      {p.salePriceEgp.toLocaleString()} EGP
                    </td>
                    <td className="py-3.5 px-4">
                      <GlassBadge variant={p.currentStock > p.minimumStock ? 'green' : 'gold'}>
                        {p.currentStock > p.minimumStock ? (language === 'ar' ? 'آمن وكافي' : 'Sufficient') : (language === 'ar' ? 'قريب من حد الطلب' : 'Low Stock')}
                      </GlassBadge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>
      ) : (
        <GlassCard className="p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-right border-collapse text-xs">
              <thead>
                <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                  <th className="py-3 px-4">{language === 'ar' ? 'كود الخامة' : 'Code'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'اسم المادة الخام والبرميل' : 'Material Name'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'النوع الكيميائي' : 'Chemical Type'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'الرصيد الفعلي' : 'Actual Balance'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'المحجوز للتشغيل' : 'Reserved'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'التكلفة المعيارية' : 'Standard Cost'}</th>
                  <th className="py-3 px-4">{language === 'ar' ? 'الموقع / الفرع' : 'Branch Hub'}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {filteredRaw.map(r => (
                  <tr key={r.id} className="hover:bg-theme-surface/40 transition-colors">
                    <td className="py-3.5 px-4 font-mono font-bold text-pmp-primary">{r.materialCode}</td>
                    <td className="py-3.5 px-4 font-bold text-theme-primary text-sm">{r.name}</td>
                    <td className="py-3.5 px-4">
                      <GlassBadge variant="cyan">{r.materialType}</GlassBadge>
                    </td>
                    <td className="py-3.5 px-4 font-black text-sm text-theme-primary">
                      {r.actualBalance} {r.unit}
                    </td>
                    <td className="py-3.5 px-4 text-theme-muted font-mono">{r.reservedBalance} {r.unit}</td>
                    <td className="py-3.5 px-4 font-bold text-theme-primary">
                      {r.unitCostEgp.toLocaleString()} EGP
                    </td>
                    <td className="py-3.5 px-4 text-theme-secondary text-[11px]">{r.branch}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>
      )}

      {/* New Product Modal */}
      <Modal
        isOpen={isNewItemOpen}
        onClose={() => setIsNewItemOpen(false)}
        title={language === 'ar' ? 'إضافة صنف منتج تام جديد للمستودع' : 'Add Finished Good Item'}
        maxWidth="max-w-lg"
      >
        <form onSubmit={handleCreateProduct} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-theme-primary mb-1">
              {language === 'ar' ? 'اسم الصنف أو الدهان *' : 'Product Name *'}
            </label>
            <input
              type="text"
              required
              value={newProduct.name}
              onChange={(e) => setNewProduct({ ...newProduct, name: e.target.value })}
              placeholder={language === 'ar' ? 'مثال: دهان ميتاليك نحاسي فاخر' : 'Product name...'}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'اللون' : 'Color'}
              </label>
              <input
                type="text"
                required
                value={newProduct.paintColor}
                onChange={(e) => setNewProduct({ ...newProduct, paintColor: e.target.value })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'معيار الحجم' : 'Can Weight'}
              </label>
              <select
                value={newProduct.packageWeight}
                onChange={(e) => setNewProduct({ ...newProduct, packageWeight: e.target.value as any })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              >
                <option value="250">250 جرام (48 علبة/كرتونة)</option>
                <option value="900">900 جرام (12 علبة/كرتونة)</option>
                <option value="4000">4000 جرام جالون (4 علب/كرتونة)</option>
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'سعر البيع (SDG)' : 'Sale Price (SDG)'}
              </label>
              <input
                type="number"
                required
                value={newProduct.salePriceSdg}
                onChange={(e) => setNewProduct({ ...newProduct, salePriceSdg: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>

            <div>
              <label className="block font-semibold text-theme-primary mb-1">
                {language === 'ar' ? 'سعر البيع (EGP)' : 'Sale Price (EGP)'}
              </label>
              <input
                type="number"
                required
                value={newProduct.salePriceEgp}
                onChange={(e) => setNewProduct({ ...newProduct, salePriceEgp: Number(e.target.value) })}
                className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary focus:outline-none focus:border-pmp-primary"
              />
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-theme">
            <GlassButton variant="secondary" onClick={() => setIsNewItemOpen(false)}>
              {language === 'ar' ? 'إلغاء' : 'Cancel'}
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              {language === 'ar' ? 'حفظ الصنف وتفعيل الرصيد' : 'Save Product'}
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
