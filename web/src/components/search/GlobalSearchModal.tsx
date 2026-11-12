import React, { useState } from 'react';
import {
  Search,
  Filter,
  Package,
  Factory,
  Coins,
  FileText,
  Users,
  Building,
  ArrowUpRight,
  ShieldAlert
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { ErpDataService } from '../../services/ErpDataService';
import { AccountService } from '../../services/AccountService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

interface GlobalSearchModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectResult: (moduleId: string) => void;
}

export const GlobalSearchModal: React.FC<GlobalSearchModalProps> = ({ isOpen, onClose, onSelectResult }) => {
  const { language } = useLanguage();
  const { canAccessModule } = useAuth();
  const [queryText, setQueryText] = useState('');

  if (!isOpen) return null;

  const products = ErpDataService.getInitialProducts();
  const batches = ErpDataService.getInitialProductionBatches();
  const invoices = ErpDataService.getInitialSalesInvoices();
  const employees = AccountService.getDefaultEmployees();

  const cleanQuery = queryText.trim().toLowerCase();

  const matchedProducts = cleanQuery && canAccessModule('المخازن')
    ? products.filter(p => p.name.toLowerCase().includes(cleanQuery) || p.paintColor.toLowerCase().includes(cleanQuery))
    : [];

  const matchedBatches = cleanQuery && canAccessModule('المصنع')
    ? batches.filter(b => b.orderNumber.toLowerCase().includes(cleanQuery) || b.formulaName.toLowerCase().includes(cleanQuery))
    : [];

  const matchedInvoices = cleanQuery && canAccessModule('المبيعات')
    ? invoices.filter(i => i.invoiceNumber.toLowerCase().includes(cleanQuery) || i.customerName.toLowerCase().includes(cleanQuery))
    : [];

  const matchedEmployees = cleanQuery && canAccessModule('المستخدمين')
    ? employees.filter(e => e.fullName.toLowerCase().includes(cleanQuery) || e.username.toLowerCase().includes(cleanQuery) || e.userCode.toLowerCase().includes(cleanQuery))
    : [];

  const totalResults = matchedProducts.length + matchedBatches.length + matchedInvoices.length + matchedEmployees.length;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center pt-20 px-4 bg-black/60 backdrop-blur-sm animate-fadeIn">
      <div className="max-w-2xl w-full bg-theme-surface border border-theme rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[75vh]">
        {/* Search Input Bar */}
        <div className="p-4 border-b border-theme flex items-center gap-3">
          <Search className="w-5 h-5 text-pmp-primary shrink-0" />
          <input
            type="text"
            autoFocus
            placeholder={language === 'ar' ? 'بحث شامل في السجلات، الفواتير، أوامر الإنتاج، والموظفين...' : 'Global search across all permitted ERP records...'}
            value={queryText}
            onChange={(e) => setQueryText(e.target.value)}
            className="w-full bg-transparent text-sm text-theme-primary placeholder-theme-muted focus:outline-none"
          />
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-theme-muted hover:text-theme-primary text-xs"
          >
            ESC
          </button>
        </div>

        {/* Results List */}
        <div className="p-4 overflow-y-auto space-y-4 flex-1 text-xs">
          {!cleanQuery ? (
            <div className="text-center py-8 text-theme-muted space-y-1">
              <Search className="w-8 h-8 mx-auto opacity-30 mb-2" />
              <p>{language === 'ar' ? 'اكتب كلمة البحث للوصول الفوري إلى السجلات المصرح لك برؤيتها.' : 'Type to search across authorized ERP records.'}</p>
              <p className="text-[11px] text-theme-secondary">{language === 'ar' ? 'البحث يحترم تماماً صلاحيات المستخدم ولا يظهر أي بيانات محظورة.' : 'Results are strictly filtered by your assigned RBAC privileges.'}</p>
            </div>
          ) : totalResults === 0 ? (
            <div className="text-center py-8 text-theme-muted">
              {language === 'ar' ? 'لم يتم العثور على نتائج تطابق استعلامك.' : 'No records found matching your query.'}
            </div>
          ) : (
            <>
              {/* Products */}
              {matchedProducts.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 uppercase text-[10px]">
                    <Package className="w-3.5 h-3.5 text-pmp-accent" />
                    <span>{language === 'ar' ? 'أصناف ومخزون المنتجات' : 'Products & Stock'}</span>
                  </div>
                  {matchedProducts.map(p => (
                    <div
                      key={p.id}
                      onClick={() => { onSelectResult('WAREHOUSES'); onClose(); }}
                      className="p-2.5 rounded-xl bg-theme-base/60 border border-theme hover:border-pmp-primary cursor-pointer flex justify-between items-center transition-all"
                    >
                      <div>
                        <span className="font-bold text-theme-primary block">{p.name} ({p.packageWeight}جم)</span>
                        <span className="text-[10px] text-theme-muted">الرصيد: {p.currentStock} كرتونة • {p.salePriceSdg.toLocaleString()} SDG</span>
                      </div>
                      <ArrowUpRight className="w-4 h-4 text-pmp-primary" />
                    </div>
                  ))}
                </div>
              )}

              {/* Batches */}
              {matchedBatches.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 uppercase text-[10px]">
                    <Factory className="w-3.5 h-3.5 text-pmp-success" />
                    <span>{language === 'ar' ? 'أوامر تشغيل المصنع' : 'Factory Production Batches'}</span>
                  </div>
                  {matchedBatches.map(b => (
                    <div
                      key={b.id}
                      onClick={() => { onSelectResult('FACTORY'); onClose(); }}
                      className="p-2.5 rounded-xl bg-theme-base/60 border border-theme hover:border-pmp-primary cursor-pointer flex justify-between items-center transition-all"
                    >
                      <div>
                        <span className="font-bold text-theme-primary block">{b.orderNumber} - {b.formulaName}</span>
                        <span className="text-[10px] text-theme-muted">{b.actualProducedCans} علبة • {b.productionEngineer}</span>
                      </div>
                      <GlassBadge variant={b.status === 'COMPLETED' ? 'green' : 'gold'}>{b.status}</GlassBadge>
                    </div>
                  ))}
                </div>
              )}

              {/* Invoices */}
              {matchedInvoices.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 uppercase text-[10px]">
                    <FileText className="w-3.5 h-3.5 text-pmp-primary" />
                    <span>{language === 'ar' ? 'فواتير المبيعات' : 'Sales Invoices'}</span>
                  </div>
                  {matchedInvoices.map(i => (
                    <div
                      key={i.id}
                      onClick={() => { onSelectResult('SALES'); onClose(); }}
                      className="p-2.5 rounded-xl bg-theme-base/60 border border-theme hover:border-pmp-primary cursor-pointer flex justify-between items-center transition-all"
                    >
                      <div>
                        <span className="font-bold text-theme-primary block">{i.invoiceNumber} - {i.customerName}</span>
                        <span className="text-[10px] text-theme-muted">{i.finalAmount.toLocaleString()} {i.currency}</span>
                      </div>
                      <GlassBadge variant={i.status === 'PAID' ? 'green' : 'gold'}>{i.status}</GlassBadge>
                    </div>
                  ))}
                </div>
              )}

              {/* Employees */}
              {matchedEmployees.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 uppercase text-[10px]">
                    <Users className="w-3.5 h-3.5 text-pmp-warning" />
                    <span>{language === 'ar' ? 'سجل الموظفين والمستخدمين' : 'Employees'}</span>
                  </div>
                  {matchedEmployees.map(e => (
                    <div
                      key={e.userCode}
                      onClick={() => { onSelectResult('USERS'); onClose(); }}
                      className="p-2.5 rounded-xl bg-theme-base/60 border border-theme hover:border-pmp-primary cursor-pointer flex justify-between items-center transition-all"
                    >
                      <div>
                        <span className="font-bold text-theme-primary block">{e.fullName} (@{e.username})</span>
                        <span className="text-[10px] text-theme-muted">{e.role} • {e.branch}</span>
                      </div>
                      <span className="font-mono text-pmp-primary font-bold">{e.userCode}</span>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};
