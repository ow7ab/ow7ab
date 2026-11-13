import React, { useState, useEffect, useMemo } from 'react';
import {
  Search,
  Package,
  Factory,
  FileText,
  Users,
  ArrowUpRight
} from 'lucide-react';
import { GlassBadge } from '../ui/GlassBadge';
import { ErpDataService } from '../../services/ErpDataService';
import { AccountService } from '../../services/AccountService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';
import {
  Product,
  StockMove,
  ProductionBatch,
  SalesInvoice,
  UserAccount
} from '../../types/erp';

interface GlobalSearchModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectResult: (moduleId: string) => void;
}

export const GlobalSearchModal: React.FC<GlobalSearchModalProps> = ({ isOpen, onClose, onSelectResult }) => {
  const { language } = useLanguage();
  const { canAccessModule, isSuperAdmin } = useAuth();
  const [queryText, setQueryText] = useState('');
  const [loading, setLoading] = useState(false);

  const [products, setProducts] = useState<Product[]>([]);
  const [stockMoves, setStockMoves] = useState<StockMove[]>([]);
  const [batches, setBatches] = useState<ProductionBatch[]>([]);
  const [invoices, setInvoices] = useState<SalesInvoice[]>([]);
  const [employees, setEmployees] = useState<UserAccount[]>([]);

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    setLoading(true);

    const loadData = async () => {
      try {
        const tasks: Promise<void>[] = [];
        if (canAccessModule('المخازن')) {
          tasks.push(
            ErpDataService.fetchCollectionOnce<Product>('products').then((d) => {
              if (!cancelled) setProducts(d);
            }),
            ErpDataService.fetchCollectionOnce<StockMove>('stock_moves').then((d) => {
              if (!cancelled) setStockMoves(d);
            })
          );
        }
        if (canAccessModule('المصنع') || canAccessModule('الإنتاج')) {
          tasks.push(
            ErpDataService.fetchCollectionOnce<ProductionBatch>('paint_batches').then((d) => {
              if (!cancelled) setBatches(d);
            })
          );
        }
        if (canAccessModule('المبيعات') || canAccessModule('العملاء')) {
          tasks.push(
            ErpDataService.fetchCollectionOnce<SalesInvoice>('invoices').then((d) => {
              if (!cancelled) setInvoices(d);
            })
          );
        }
        if (canAccessModule('المستخدمين') || isSuperAdmin) {
          tasks.push(
            AccountService.getAllEmployees().then((d) => {
              if (!cancelled) setEmployees(d);
            })
          );
        }
        await Promise.allSettled(tasks);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    loadData();
    return () => {
      cancelled = true;
    };
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isOpen, onClose]);

  const productStocks = useMemo(
    () => ErpDataService.computeProductStocks(products, stockMoves),
    [products, stockMoves]
  );

  if (!isOpen) return null;

  const cleanQuery = queryText.trim().toLowerCase();

  const matchedProducts =
    cleanQuery && canAccessModule('المخازن')
      ? products.filter(
          (p) =>
            p.name.toLowerCase().includes(cleanQuery) ||
            (p.paintColor || '').toLowerCase().includes(cleanQuery) ||
            (p.sku || '').toLowerCase().includes(cleanQuery)
        )
      : [];

  const matchedBatches =
    cleanQuery && (canAccessModule('المصنع') || canAccessModule('الإنتاج'))
      ? batches.filter(
          (b) =>
            (b.batchNumber || '').toLowerCase().includes(cleanQuery) ||
            (b.productName || '').toLowerCase().includes(cleanQuery) ||
            (b.formulaNo || '').toLowerCase().includes(cleanQuery)
        )
      : [];

  const matchedInvoices =
    cleanQuery && (canAccessModule('المبيعات') || canAccessModule('العملاء'))
      ? invoices.filter(
          (i) =>
            (i.invoiceNo || '').toLowerCase().includes(cleanQuery) ||
            (i.customerName || '').toLowerCase().includes(cleanQuery)
        )
      : [];

  const matchedEmployees =
    cleanQuery && (canAccessModule('المستخدمين') || isSuperAdmin)
      ? employees.filter(
          (e) =>
            e.fullName.toLowerCase().includes(cleanQuery) ||
            e.username.toLowerCase().includes(cleanQuery) ||
            e.userCode.toLowerCase().includes(cleanQuery)
        )
      : [];

  const totalResults =
    matchedProducts.length + matchedBatches.length + matchedInvoices.length + matchedEmployees.length;

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center pt-16 sm:pt-20 px-4 bg-black/70 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="max-w-2xl w-full bg-theme-surface border border-theme rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[75vh]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-4 border-b border-theme flex items-center gap-3">
          <Search className="w-5 h-5 text-pmp-primary shrink-0" />
          <input
            type="text"
            autoFocus
            placeholder={
              language === 'ar'
                ? 'بحث حي في السجلات السحابية (المنتجات، الفواتير، التشغيلات، الموظفين)...'
                : 'Search live Firestore ERP records...'
            }
            value={queryText}
            onChange={(e) => setQueryText(e.target.value)}
            className="w-full bg-transparent text-sm text-theme-primary placeholder-theme-muted focus:outline-none"
          />
          <button
            type="button"
            onClick={onClose}
            className="px-2 py-1 rounded-lg bg-theme-base border border-theme text-theme-muted hover:text-theme-primary text-xs font-mono"
          >
            ESC
          </button>
        </div>

        <div className="p-4 overflow-y-auto space-y-4 flex-1 text-xs">
          {loading && (
            <div className="text-center py-4 text-theme-muted">
              {language === 'ar' ? 'جاري تحميل السجلات المصرحة من Firestore...' : 'Loading live records...'}
            </div>
          )}

          {!cleanQuery ? (
            <div className="text-center py-8 text-theme-muted space-y-1">
              <Search className="w-8 h-8 mx-auto opacity-30 mb-2" />
              <p>
                {language === 'ar'
                  ? 'اكتب كلمة البحث للوصول الفوري إلى السجلات المصرح لك برؤيتها.'
                  : 'Type to search across authorized ERP records.'}
              </p>
              <p className="text-[11px] text-theme-secondary">
                {language === 'ar'
                  ? 'تُصفّى النتائج تلقائياً وفق صلاحيات حسابك في النظام.'
                  : 'Results strictly respect your RBAC permissions.'}
              </p>
            </div>
          ) : totalResults === 0 && !loading ? (
            <div className="text-center py-8 text-theme-muted">
              {language === 'ar' ? 'لم يتم العثور على نتائج مطابقة.' : 'No matching records found.'}
            </div>
          ) : (
            <>
              {matchedProducts.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <Package className="w-3.5 h-3.5 text-pmp-accent" />
                    <span>{language === 'ar' ? 'أصناف المخازن' : 'Inventory Products'}</span>
                  </div>
                  {matchedProducts.slice(0, 8).map((p) => {
                    const qty = productStocks.get(Number(p.id)) ?? 0;
                    const price = Number(p.salePrice ?? p.salePriceSdg ?? 0);
                    return (
                      <div
                        key={p.id}
                        onClick={() => {
                          onSelectResult('INVENTORY');
                          onClose();
                        }}
                        className="p-2.5 rounded-xl bg-theme-base/60 border border-theme hover:border-pmp-primary cursor-pointer flex justify-between items-center transition-all"
                      >
                        <div>
                          <span className="font-bold text-theme-primary block">
                            {p.name} ({p.packageWeight})
                          </span>
                          <span className="text-[10px] text-theme-muted">
                            الرصيد: {qty} • {price.toLocaleString()} SDG
                          </span>
                        </div>
                        <ArrowUpRight className="w-4 h-4 text-pmp-primary" />
                      </div>
                    );
                  })}
                </div>
              )}

              {matchedBatches.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <Factory className="w-3.5 h-3.5 text-pmp-success" />
                    <span>{language === 'ar' ? 'تشغيلات الإنتاج' : 'Production Batches'}</span>
                  </div>
                  {matchedBatches.slice(0, 8).map((b) => (
                    <div
                      key={b.id}
                      onClick={() => {
                        onSelectResult('FACTORY');
                        onClose();
                      }}
                      className="p-2.5 rounded-xl bg-theme-base/60 border border-theme hover:border-pmp-primary cursor-pointer flex justify-between items-center transition-all"
                    >
                      <div>
                        <span className="font-bold text-theme-primary block">
                          {b.batchNumber} — {b.productName}
                        </span>
                        <span className="text-[10px] text-theme-muted">
                          {b.cartonsCount} كرتونة • {b.supervisorOrEngineer}
                        </span>
                      </div>
                      <GlassBadge variant={b.qualityStatus === 'معتمد ومطابق' ? 'green' : 'gold'}>
                        {b.qualityStatus}
                      </GlassBadge>
                    </div>
                  ))}
                </div>
              )}

              {matchedInvoices.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <FileText className="w-3.5 h-3.5 text-pmp-primary" />
                    <span>{language === 'ar' ? 'فواتير المبيعات' : 'Sales Invoices'}</span>
                  </div>
                  {matchedInvoices.slice(0, 8).map((i) => (
                    <div
                      key={i.id}
                      onClick={() => {
                        onSelectResult('SALES');
                        onClose();
                      }}
                      className="p-2.5 rounded-xl bg-theme-base/60 border border-theme hover:border-pmp-primary cursor-pointer flex justify-between items-center transition-all"
                    >
                      <div>
                        <span className="font-bold text-theme-primary block">
                          {i.invoiceNo} — {i.customerName}
                        </span>
                        <span className="text-[10px] text-theme-muted">
                          {Number(i.total ?? i.finalAmount ?? 0).toLocaleString()} {i.currency} • {i.invoiceDate}
                        </span>
                      </div>
                      <GlassBadge variant={i.status === 'cancelled' ? 'red' : 'green'}>
                        {i.status === 'cancelled' ? 'ملغاة' : 'نشطة'}
                      </GlassBadge>
                    </div>
                  ))}
                </div>
              )}

              {matchedEmployees.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <Users className="w-3.5 h-3.5 text-pmp-warning" />
                    <span>{language === 'ar' ? 'حسابات الموظفين' : 'Employees'}</span>
                  </div>
                  {matchedEmployees.slice(0, 8).map((e) => (
                    <div
                      key={e.userCode}
                      onClick={() => {
                        onSelectResult('USERS');
                        onClose();
                      }}
                      className="p-2.5 rounded-xl bg-theme-base/60 border border-theme hover:border-pmp-primary cursor-pointer flex justify-between items-center transition-all"
                    >
                      <div>
                        <span className="font-bold text-theme-primary block">
                          {e.fullName} (@{e.username})
                        </span>
                        <span className="text-[10px] text-theme-muted">
                          {e.role} • {e.branch}
                        </span>
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
