import React, { useState } from 'react';
import {
  Truck,
  Ship,
  Plus,
  Search,
  Filter,
  CheckCircle2,
  Clock,
  MapPin,
  FileSpreadsheet,
  Printer,
  ShieldCheck,
  Building
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { ReportExporter } from '../../services/ReportExporter';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

export interface TransitCargoRecord {
  id: string;
  trackingNo: string;
  driverName: string;
  truckPlate: string;
  originHub: string;
  destinationHub: string;
  cargoDescription: string;
  totalCartons: number;
  customsStatus: 'IN_TRANSIT' | 'CUSTOMS_CLEARED' | 'RECEIVED' | 'INSPECTION';
  dispatchDate: string;
  arrivalEstimate: string;
}

export const HalfaTransitModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction } = useAuth();

  const [cargoList, setCargoList] = useState<TransitCargoRecord[]>([
    {
      id: 'TR-01',
      trackingNo: 'HLF-TR-881',
      driverName: 'عثمان عبد الله التوم',
      truckPlate: 'ش ق د 4812',
      originHub: 'مصنع القاهرة (EG-001)',
      destinationHub: 'مستودع حلفا الجاف (HF-001)',
      cargoDescription: '1200 كرتونة دهان أبيض مطاطي 900جم',
      totalCartons: 1200,
      customsStatus: 'IN_TRANSIT',
      dispatchDate: '2026-11-10',
      arrivalEstimate: '2026-11-13'
    },
    {
      id: 'TR-02',
      trackingNo: 'HLF-TR-882',
      driverName: 'سيد محمد علي',
      truckPlate: 'م ص ر 9015',
      originHub: 'ميناء السويس الجاف',
      destinationHub: 'مستودع حلفا الجاف (HF-001)',
      cargoDescription: '45 برميل راتنج أكريليك نقي 200كجم',
      totalCartons: 45,
      customsStatus: 'CUSTOMS_CLEARED',
      dispatchDate: '2026-11-08',
      arrivalEstimate: '2026-11-12'
    },
    {
      id: 'TR-03',
      trackingNo: 'HLF-TR-880',
      driverName: 'عمر صديق بشير',
      truckPlate: 'ش ق د 1120',
      originHub: 'مستودع حلفا الجاف (HF-001)',
      destinationHub: 'مستودع عطبرة (AT-002)',
      cargoDescription: '800 كرتونة دهان حراري وفضي',
      totalCartons: 800,
      customsStatus: 'RECEIVED',
      dispatchDate: '2026-11-06',
      arrivalEstimate: '2026-11-09'
    }
  ]);

  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  const filtered = cargoList.filter(c => {
    const matchesSearch =
      c.trackingNo.toLowerCase().includes(searchTerm.toLowerCase()) ||
      c.driverName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      c.cargoDescription.toLowerCase().includes(searchTerm.toLowerCase());

    const matchesStatus = statusFilter === 'ALL' || c.customsStatus === statusFilter;
    return matchesSearch && matchesStatus;
  });

  const handleExportCsv = () => {
    const headers = ['رقم البوليصة', 'السائق', 'رقم الشاحنة', 'المنشأ', 'الوجهة', 'البيان', 'الكمية', 'حالة التخليص والجمرك', 'تاريخ التحرك'];
    const rows = filtered.map(c => [
      c.trackingNo, c.driverName, c.truckPlate, c.originHub, c.destinationHub, c.cargoDescription, c.totalCartons, c.customsStatus, c.dispatchDate
    ]);
    ReportExporter.exportToCsv('pmp_halfa_transit_cargo', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['البوليصة', 'السائق', 'الشاحنة', 'البيان', 'الكمية', 'الحالة'];
    const rows = filtered.map(c => [
      c.trackingNo, c.driverName, c.truckPlate, c.cargoDescription, c.totalCartons, c.customsStatus
    ]);
    ReportExporter.exportToPrint('بيان شحنات العبور وبضاعة حلفا - PMP PAINTS', headers, rows);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-black text-theme-primary">
              {language === 'ar' ? 'بضاعة العبور ومستودع حلفا (Transit Logistics Hub)' : 'Halfa Transit Logistics'}
            </h2>
            <GlassBadge variant="cyan">
              {cargoList.length} {language === 'ar' ? 'شحنات عابرة' : 'Shipments'}
            </GlassBadge>
          </div>
          <p className="text-xs text-theme-secondary mt-1">
            {language === 'ar'
              ? 'تتبع قوافل الشاحنات العابرة للحدود بين مصر والسودان، التخليص الجمركي، والتفريغ بميناء حلفا الجاف.'
              : 'Cross-border cargo tracking between Egypt and Sudan, customs clearance, and dry port releases.'}
          </p>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportCsv} className="text-xs">
            {language === 'ar' ? 'تصدير CSV' : 'Export CSV'}
          </GlassButton>
          <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrint} className="text-xs">
            {language === 'ar' ? 'طباعة' : 'Print'}
          </GlassButton>
        </div>
      </div>

      {/* Filter and Search */}
      <GlassCard className="p-4">
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
          <div className="relative sm:col-span-2">
            <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
            <input
              type="text"
              placeholder={language === 'ar' ? 'بحث برقم البوليصة، السائق، أو تفاصيل الشحنة...' : 'Search transit cargo...'}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            />
          </div>

          <div>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary text-xs focus:outline-none focus:border-pmp-primary"
            >
              <option value="ALL">{language === 'ar' ? 'جميع الحالات' : 'All Statuses'}</option>
              <option value="IN_TRANSIT">{language === 'ar' ? 'في الطريق (In Transit)' : 'In Transit'}</option>
              <option value="CUSTOMS_CLEARED">{language === 'ar' ? 'تم التخليص الجمركي' : 'Customs Cleared'}</option>
              <option value="RECEIVED">{language === 'ar' ? 'تم الاستلام والتفريغ' : 'Received'}</option>
            </select>
          </div>
        </div>
      </GlassCard>

      {/* Cargo Table */}
      <GlassCard className="p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-right border-collapse text-xs">
            <thead>
              <tr className="bg-theme-surface/70 border-b border-theme text-theme-muted font-bold">
                <th className="py-3 px-4">{language === 'ar' ? 'رقم البوليصة' : 'Tracking #'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'بيان الشحنة والكمية' : 'Cargo Description'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'مسار الشاحنة' : 'Route Hubs'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'بيانات السائق واللوحة' : 'Driver & Truck'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'الحالة الجمركية' : 'Customs Status'}</th>
                <th className="py-3 px-4">{language === 'ar' ? 'تاريخ التحرك' : 'Dispatch Date'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {filtered.map(c => (
                <tr key={c.id} className="hover:bg-theme-surface/40 transition-colors">
                  <td className="py-3.5 px-4 font-mono font-bold text-pmp-primary">{c.trackingNo}</td>
                  <td className="py-3.5 px-4">
                    <span className="font-bold text-theme-primary block">{c.cargoDescription}</span>
                    <span className="text-[11px] text-pmp-accent font-bold">{c.totalCartons} وحدة</span>
                  </td>
                  <td className="py-3.5 px-4 text-theme-secondary text-[11px]">
                    <div>من: {c.originHub}</div>
                    <div className="text-pmp-primary font-bold">إلى: {c.destinationHub}</div>
                  </td>
                  <td className="py-3.5 px-4">
                    <span className="font-semibold text-theme-primary block">{c.driverName}</span>
                    <span className="font-mono text-[10px] text-theme-muted">{c.truckPlate}</span>
                  </td>
                  <td className="py-3.5 px-4">
                    <GlassBadge variant={c.customsStatus === 'RECEIVED' ? 'green' : c.customsStatus === 'CUSTOMS_CLEARED' ? 'cyan' : 'gold'}>
                      {c.customsStatus === 'RECEIVED' ? 'تم الاستلام' : c.customsStatus === 'CUSTOMS_CLEARED' ? 'مخلص جمركياً' : 'في الطريق'}
                    </GlassBadge>
                  </td>
                  <td className="py-3.5 px-4 font-mono text-theme-muted">{c.dispatchDate}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </GlassCard>
    </div>
  );
};
