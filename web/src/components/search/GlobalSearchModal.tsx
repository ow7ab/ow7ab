import React, { useState, useEffect, useMemo } from 'react';
import { renderInBodyPortal } from '../ui/Modal';
import {
  Search,
  Package,
  Factory,
  FileText,
  Users,
  Coins,
  ArrowRightLeft,
  UserCheck,
  ArrowUpRight,
  X
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
  UserAccount,
  Customer,
  TreasuryMovement,
  InterTreasuryTransfer
} from '../../types/erp';

type SearchScope = 'ALL' | 'PRODUCTS' | 'INVOICES' | 'CUSTOMERS' | 'TREASURY' | 'FACTORY' | 'USERS';

interface GlobalSearchModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectResult: (moduleId: string) => void;
}

export const GlobalSearchModal: React.FC<GlobalSearchModalProps> = ({ isOpen, onClose, onSelectResult }) => {
  const { language } = useLanguage();
  const { canAccessModule, isSuperAdmin } = useAuth();
  const [queryText, setQueryText] = useState('');
  const [activeScope, setActiveScope] = useState<SearchScope>('ALL');
  const [highlightedIdx, setHighlightedIdx] = useState(0);
  const [loading, setLoading] = useState(false);

  const [products, setProducts] = useState<Product[]>([]);
  const [stockMoves, setStockMoves] = useState<StockMove[]>([]);
  const [batches, setBatches] = useState<ProductionBatch[]>([]);
  const [invoices, setInvoices] = useState<SalesInvoice[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [movements, setMovements] = useState<TreasuryMovement[]>([]);
  const [transfers, setTransfers] = useState<InterTreasuryTransfer[]>([]);
  const [employees, setEmployees] = useState<UserAccount[]>([]);

  const canInventory = canAccessModule('المخازن');
  const canFactory = canAccessModule('المصنع') || canAccessModule('الإنتاج');
  const canSales = canAccessModule('المبيعات') || canAccessModule('العملاء');
  const canTreasury = canAccessModule('الخزن') || canAccessModule('التحويلات') || canAccessModule('المحاسبة');
  const canUsers = canAccessModule('المستخدمين') || isSuperAdmin;

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    setLoading(true);
    setHighlightedIdx(0);

    const loadData = async () => {
      try {
        const tasks: Promise<void>[] = [];
        if (canInventory) {
          tasks.push(
            ErpDataService.fetchCollectionOnce<Product>('products').then((d) => {
              if (!cancelled) setProducts(d);
            }),
            ErpDataService.fetchCollectionOnce<StockMove>('stock_moves').then((d) => {
              if (!cancelled) setStockMoves(d);
            })
          );
        }
        if (canFactory) {
          tasks.push(
            ErpDataService.fetchCollectionOnce<ProductionBatch>('paint_batches').then((d) => {
              if (!cancelled) setBatches(d);
            })
          );
        }
        if (canSales) {
          tasks.push(
            ErpDataService.fetchCollectionOnce<SalesInvoice>('invoices').then((d) => {
              if (!cancelled) setInvoices(d);
            }),
            ErpDataService.fetchCollectionOnce<Customer>('customers').then((d) => {
              if (!cancelled) setCustomers(d);
            })
          );
        }
        if (canTreasury) {
          tasks.push(
            ErpDataService.fetchCollectionOnce<TreasuryMovement>('movements').then((d) => {
              if (!cancelled) setMovements(d);
            }),
            ErpDataService.fetchCollectionOnce<InterTreasuryTransfer>('transfers').then((d) => {
              if (!cancelled) setTransfers(d);
            })
          );
        }
        if (canUsers) {
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

    void loadData();
    return () => {
      cancelled = true;
    };
  }, [isOpen, canInventory, canFactory, canSales, canTreasury, canUsers]);

  const productStocks = useMemo(
    () => ErpDataService.computeProductStocks(products, stockMoves),
    [products, stockMoves]
  );

  const cleanQuery = queryText.trim().toLowerCase();

  const matchedProducts = useMemo(
    () =>
      cleanQuery && canInventory && (activeScope === 'ALL' || activeScope === 'PRODUCTS')
        ? products
            .filter(
              (p) =>
                p.name.toLowerCase().includes(cleanQuery) ||
                (p.paintColor || '').toLowerCase().includes(cleanQuery) ||
                (p.sku || '').toLowerCase().includes(cleanQuery)
            )
            .slice(0, 8)
        : [],
    [cleanQuery, canInventory, activeScope, products]
  );

  const matchedInvoices = useMemo(
    () =>
      cleanQuery && canSales && (activeScope === 'ALL' || activeScope === 'INVOICES')
        ? invoices
            .filter(
              (i) =>
                (i.invoiceNo || '').toLowerCase().includes(cleanQuery) ||
                (i.customerName || '').toLowerCase().includes(cleanQuery)
            )
            .slice(0, 8)
        : [],
    [cleanQuery, canSales, activeScope, invoices]
  );

  const matchedCustomers = useMemo(
    () =>
      cleanQuery && canSales && (activeScope === 'ALL' || activeScope === 'CUSTOMERS')
        ? customers
            .filter(
              (c) =>
                (c.name || '').toLowerCase().includes(cleanQuery) ||
                (c.phone || '').toLowerCase().includes(cleanQuery) ||
                (c.address || '').toLowerCase().includes(cleanQuery)
            )
            .slice(0, 8)
        : [],
    [cleanQuery, canSales, activeScope, customers]
  );

  const matchedMovements = useMemo(
    () =>
      cleanQuery && canTreasury && (activeScope === 'ALL' || activeScope === 'TREASURY')
        ? movements
            .filter(
              (m) =>
                (m.reference || m.referenceNo || '').toLowerCase().includes(cleanQuery) ||
                (m.cashbox || '').toLowerCase().includes(cleanQuery) ||
                (m.description || m.note || '').toLowerCase().includes(cleanQuery)
            )
            .slice(0, 8)
        : [],
    [cleanQuery, canTreasury, activeScope, movements]
  );

  const matchedTransfers = useMemo(
    () =>
      cleanQuery && canTreasury && (activeScope === 'ALL' || activeScope === 'TREASURY')
        ? transfers
            .filter(
              (t) =>
                (t.reference || '').toLowerCase().includes(cleanQuery) ||
                (t.sourceCashbox || '').toLowerCase().includes(cleanQuery) ||
                (t.destinationCashbox || '').toLowerCase().includes(cleanQuery) ||
                (t.description || '').toLowerCase().includes(cleanQuery)
            )
            .slice(0, 6)
        : [],
    [cleanQuery, canTreasury, activeScope, transfers]
  );

  const matchedBatches = useMemo(
    () =>
      cleanQuery && canFactory && (activeScope === 'ALL' || activeScope === 'FACTORY')
        ? batches
            .filter(
              (b) =>
                (b.batchNumber || '').toLowerCase().includes(cleanQuery) ||
                (b.productName || '').toLowerCase().includes(cleanQuery) ||
                (b.formulaNo || '').toLowerCase().includes(cleanQuery)
            )
            .slice(0, 8)
        : [],
    [cleanQuery, canFactory, activeScope, batches]
  );

  const matchedEmployees = useMemo(
    () =>
      cleanQuery && canUsers && (activeScope === 'ALL' || activeScope === 'USERS')
        ? employees
            .filter(
              (e) =>
                e.fullName.toLowerCase().includes(cleanQuery) ||
                e.username.toLowerCase().includes(cleanQuery) ||
                e.userCode.toLowerCase().includes(cleanQuery)
            )
            .slice(0, 8)
        : [],
    [cleanQuery, canUsers, activeScope, employees]
  );

  const flatNavigationTargets = useMemo(
    () => [
      ...matchedProducts.map(() => 'INVENTORY'),
      ...matchedInvoices.map(() => 'SALES'),
      ...matchedCustomers.map(() => 'SALES'),
      ...matchedMovements.map(() => 'TREASURY'),
      ...matchedTransfers.map(() => 'TREASURY'),
      ...matchedBatches.map(() => 'FACTORY'),
      ...matchedEmployees.map(() => 'USERS')
    ],
    [matchedProducts, matchedInvoices, matchedCustomers, matchedMovements, matchedTransfers, matchedBatches, matchedEmployees]
  );

  useEffect(() => {
    setHighlightedIdx(0);
  }, [cleanQuery, activeScope]);

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      } else if (e.key === 'ArrowDown' && flatNavigationTargets.length > 0) {
        e.preventDefault();
        setHighlightedIdx((prev) => (prev + 1) % flatNavigationTargets.length);
      } else if (e.key === 'ArrowUp' && flatNavigationTargets.length > 0) {
        e.preventDefault();
        setHighlightedIdx((prev) => (prev - 1 + flatNavigationTargets.length) % flatNavigationTargets.length);
      } else if (e.key === 'Enter' && flatNavigationTargets.length > 0) {
        e.preventDefault();
        const targetMod = flatNavigationTargets[highlightedIdx] || flatNavigationTargets[0];
        if (targetMod) {
          onSelectResult(targetMod);
          onClose();
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isOpen, onClose, flatNavigationTargets, highlightedIdx, onSelectResult]);

  if (!isOpen) return null;

  const totalResults = flatNavigationTargets.length;
  let runningIdx = 0;

  const scopeChips: Array<{ id: SearchScope; labelAr: string; labelEn: string; allowed: boolean }> = [
    { id: 'ALL', labelAr: 'الكل', labelEn: 'All', allowed: true },
    { id: 'PRODUCTS', labelAr: 'المخازن', labelEn: 'Inventory', allowed: canInventory },
    { id: 'INVOICES', labelAr: 'الفواتير', labelEn: 'Invoices', allowed: canSales },
    { id: 'CUSTOMERS', labelAr: 'العملاء', labelEn: 'Customers', allowed: canSales },
    { id: 'TREASURY', labelAr: 'الخزينة والتحويلات', labelEn: 'Treasury', allowed: canTreasury },
    { id: 'FACTORY', labelAr: 'الإنتاج', labelEn: 'Factory', allowed: canFactory },
    { id: 'USERS', labelAr: 'الموظفين', labelEn: 'Staff', allowed: canUsers }
  ];

  return renderInBodyPortal(
    <div
      className="fixed inset-0 z-[9998] flex items-start justify-center overflow-y-auto overscroll-contain pt-10 pb-6 px-4 bg-black/70 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        className="max-w-2xl w-full my-auto bg-theme-surface border border-theme rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[calc(100vh-4rem)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-4 border-b border-theme space-y-3">
          <div className="flex items-center gap-3">
            <Search className="w-5 h-5 text-pmp-primary shrink-0" />
            <input
              type="text"
              autoFocus
              placeholder={
                language === 'ar'
                  ? 'بحث حي (المنتجات، الفواتير، العملاء، الخزينة، التشغيلات، الموظفين)...'
                  : 'Search live Firestore ERP records (products, invoices, customers, treasury)...'
              }
              value={queryText}
              onChange={(e) => setQueryText(e.target.value)}
              className="w-full bg-transparent text-sm text-theme-primary placeholder-theme-muted focus:outline-none"
            />
            {queryText && (
              <button
                type="button"
                onClick={() => setQueryText('')}
                className="p-1.5 rounded-lg bg-theme-base border border-theme text-theme-muted hover:text-theme-primary"
                aria-label="Clear search"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              className="px-2 py-1 rounded-lg bg-theme-base border border-theme text-theme-muted hover:text-theme-primary text-xs font-mono shrink-0"
            >
              ESC
            </button>
          </div>

          <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
            {scopeChips
              .filter((c) => c.allowed)
              .map((chip) => {
                const active = activeScope === chip.id;
                return (
                  <button
                    key={chip.id}
                    type="button"
                    onClick={() => setActiveScope(chip.id)}
                    className={`px-2.5 py-1 rounded-lg text-[11px] font-bold whitespace-nowrap transition-all ${
                      active
                        ? 'bg-pmp-primary text-white shadow-sm'
                        : 'bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary'
                    }`}
                  >
                    {language === 'ar' ? chip.labelAr : chip.labelEn}
                  </button>
                );
              })}
          </div>
        </div>

        <div className="p-4 min-h-0 overflow-y-auto overscroll-contain space-y-4 flex-1 text-xs">
          {loading && (
            <div className="text-center py-4 text-theme-muted">
              {language === 'ar' ? 'جاري تحميل السجلات المصرحة من Firestore...' : 'Loading live records...'}
            </div>
          )}

          {!cleanQuery ? (
            <div className="text-center py-8 text-theme-muted space-y-1.5">
              <Search className="w-8 h-8 mx-auto opacity-30 mb-2" />
              <p className="font-semibold text-theme-primary">
                {language === 'ar'
                  ? 'اكتب كلمة البحث للوصول الفوري إلى السجلات المصرح لك برؤيتها.'
                  : 'Type to search across authorized ERP records.'}
              </p>
              <p className="text-[11px] text-theme-secondary">
                {language === 'ar'
                  ? 'استخدم الأسهم ↑↓ للتنقل ثم Enter للفتح الفوري • تُصفّى النتائج وفق صلاحيات RBAC.'
                  : 'Use ↑↓ arrows and Enter to jump directly • Strictly filtered by RBAC.'}
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
                  {matchedProducts.map((p) => {
                    const idx = runningIdx++;
                    const qty = productStocks.get(Number(p.id)) ?? 0;
                    const price = Number(p.salePrice ?? p.salePriceSdg ?? 0);
                    return (
                      <div
                        key={p.id}
                        onClick={() => {
                          onSelectResult('INVENTORY');
                          onClose();
                        }}
                        className={`p-2.5 rounded-xl border cursor-pointer flex justify-between items-center transition-all ${
                          highlightedIdx === idx
                            ? 'bg-pmp-primary/10 border-pmp-primary'
                            : 'bg-theme-base/60 border-theme hover:border-pmp-primary'
                        }`}
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

              {matchedInvoices.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <FileText className="w-3.5 h-3.5 text-pmp-primary" />
                    <span>{language === 'ar' ? 'فواتير المبيعات' : 'Sales Invoices'}</span>
                  </div>
                  {matchedInvoices.map((i) => {
                    const idx = runningIdx++;
                    return (
                      <div
                        key={i.id}
                        onClick={() => {
                          onSelectResult('SALES');
                          onClose();
                        }}
                        className={`p-2.5 rounded-xl border cursor-pointer flex justify-between items-center transition-all ${
                          highlightedIdx === idx
                            ? 'bg-pmp-primary/10 border-pmp-primary'
                            : 'bg-theme-base/60 border-theme hover:border-pmp-primary'
                        }`}
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
                    );
                  })}
                </div>
              )}

              {matchedCustomers.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <UserCheck className="w-3.5 h-3.5 text-emerald-400" />
                    <span>{language === 'ar' ? 'دليل العملاء' : 'Customers'}</span>
                  </div>
                  {matchedCustomers.map((c) => {
                    const idx = runningIdx++;
                    return (
                      <div
                        key={c.id}
                        onClick={() => {
                          onSelectResult('SALES');
                          onClose();
                        }}
                        className={`p-2.5 rounded-xl border cursor-pointer flex justify-between items-center transition-all ${
                          highlightedIdx === idx
                            ? 'bg-pmp-primary/10 border-pmp-primary'
                            : 'bg-theme-base/60 border-theme hover:border-pmp-primary'
                        }`}
                      >
                        <div>
                          <span className="font-bold text-theme-primary block">{c.name}</span>
                          <span className="text-[10px] text-theme-muted">
                            {c.phone || 'بدون هاتف'} • {c.address || 'غير محدد'}
                          </span>
                        </div>
                        <ArrowUpRight className="w-4 h-4 text-emerald-400" />
                      </div>
                    );
                  })}
                </div>
              )}

              {matchedMovements.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <Coins className="w-3.5 h-3.5 text-amber-400" />
                    <span>{language === 'ar' ? 'الحركات المالية بالخزن' : 'Treasury Movements'}</span>
                  </div>
                  {matchedMovements.map((m) => {
                    const idx = runningIdx++;
                    const isIn = m.direction === 'وارد' || m.type === 'INCOME' || m.type === 'IN';
                    return (
                      <div
                        key={`${m.id}-${m.reference || ''}`}
                        onClick={() => {
                          onSelectResult('TREASURY');
                          onClose();
                        }}
                        className={`p-2.5 rounded-xl border cursor-pointer flex justify-between items-center transition-all ${
                          highlightedIdx === idx
                            ? 'bg-pmp-primary/10 border-pmp-primary'
                            : 'bg-theme-base/60 border-theme hover:border-pmp-primary'
                        }`}
                      >
                        <div>
                          <span className="font-bold text-theme-primary block">
                            {m.reference || m.referenceNo || `#${m.id}`} — {m.cashbox}
                          </span>
                          <span className="text-[10px] text-theme-muted">
                            {m.description || m.note || 'حركة خزينة'} • {m.txDate || ''}
                          </span>
                        </div>
                        <GlassBadge variant={isIn ? 'green' : 'red'}>
                          {isIn ? '+' : '-'}{Number(m.amount || 0).toLocaleString()} {m.currency}
                        </GlassBadge>
                      </div>
                    );
                  })}
                </div>
              )}

              {matchedTransfers.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <ArrowRightLeft className="w-3.5 h-3.5 text-pmp-cyan" />
                    <span>{language === 'ar' ? 'التحويلات بين الخزن' : 'Treasury Transfers'}</span>
                  </div>
                  {matchedTransfers.map((t) => {
                    const idx = runningIdx++;
                    return (
                      <div
                        key={`${t.id}-${t.reference}`}
                        onClick={() => {
                          onSelectResult('TREASURY');
                          onClose();
                        }}
                        className={`p-2.5 rounded-xl border cursor-pointer flex justify-between items-center transition-all ${
                          highlightedIdx === idx
                            ? 'bg-pmp-primary/10 border-pmp-primary'
                            : 'bg-theme-base/60 border-theme hover:border-pmp-primary'
                        }`}
                      >
                        <div>
                          <span className="font-bold text-theme-primary block">
                            {t.reference} — {t.sourceCashbox} ← {t.destinationCashbox}
                          </span>
                          <span className="text-[10px] text-theme-muted">
                            {Number(t.sourceAmount || 0).toLocaleString()} {t.sourceCurrency} →{' '}
                            {Number(t.destinationAmount || 0).toLocaleString()} {t.destinationCurrency}
                          </span>
                        </div>
                        <ArrowUpRight className="w-4 h-4 text-pmp-cyan" />
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
                  {matchedBatches.map((b) => {
                    const idx = runningIdx++;
                    return (
                      <div
                        key={b.id}
                        onClick={() => {
                          onSelectResult('FACTORY');
                          onClose();
                        }}
                        className={`p-2.5 rounded-xl border cursor-pointer flex justify-between items-center transition-all ${
                          highlightedIdx === idx
                            ? 'bg-pmp-primary/10 border-pmp-primary'
                            : 'bg-theme-base/60 border-theme hover:border-pmp-primary'
                        }`}
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
                    );
                  })}
                </div>
              )}

              {matchedEmployees.length > 0 && (
                <div className="space-y-2">
                  <div className="font-bold text-theme-muted flex items-center gap-1.5 text-[10px]">
                    <Users className="w-3.5 h-3.5 text-pmp-warning" />
                    <span>{language === 'ar' ? 'حسابات الموظفين' : 'Employees'}</span>
                  </div>
                  {matchedEmployees.map((e) => {
                    const idx = runningIdx++;
                    return (
                      <div
                        key={e.userCode}
                        onClick={() => {
                          onSelectResult('USERS');
                          onClose();
                        }}
                        className={`p-2.5 rounded-xl border cursor-pointer flex justify-between items-center transition-all ${
                          highlightedIdx === idx
                            ? 'bg-pmp-primary/10 border-pmp-primary'
                            : 'bg-theme-base/60 border-theme hover:border-pmp-primary'
                        }`}
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
                    );
                  })}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};
