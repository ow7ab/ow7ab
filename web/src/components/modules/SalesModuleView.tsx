import React, { useEffect, useMemo, useState } from 'react';
import {
  FileText,
  Plus,
  Search,
  Printer,
  FileSpreadsheet,
  Users,
  Wallet,
  Trash2,
  XCircle,
  CheckCircle2,
  AlertCircle
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import {
  Cashbox,
  CurrencyCode,
  Customer,
  InvoiceItem,
  InvoicePayment,
  Product,
  SalesInvoice,
  StockMove,
  TreasuryMovement,
  Warehouse
} from '../../types/erp';
import { ErpDataService, safeId } from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

type SalesSubTab = 'INVOICES' | 'NEW_INVOICE' | 'CUSTOMER_LEDGER' | 'CUSTOMERS';

interface DraftLine {
  productId: number;
  warehouseId: number;
  description: string;
  quantity: number;
  unitPrice: number;
}

export const SalesModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [subTab, setSubTab] = useState<SalesSubTab>('INVOICES');
  const [invoices, setInvoices] = useState<SalesInvoice[]>([]);
  const [payments, setPayments] = useState<InvoicePayment[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [stockMoves, setStockMoves] = useState<StockMove[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);

  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'مدفوعة' | 'سداد جزئي' | 'غير مدفوعة' | 'ملغاة'>('ALL');
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // Payment Modal State
  const [payingInvoice, setPayingInvoice] = useState<SalesInvoice | null>(null);
  const [payAmount, setPayAmount] = useState('');
  const [payCashbox, setPayCashbox] = useState('');
  const [payNote, setPayNote] = useState('');

  // New Invoice Draft State
  const [selectedCustomerId, setSelectedCustomerId] = useState<number>(0);
  const [quickCustomerName, setQuickCustomerName] = useState('');
  const [invCurrency, setInvCurrency] = useState<CurrencyCode>('SDG');
  const [invWarehouseId, setInvWarehouseId] = useState<number>(0);
  const [invDiscount, setInvDiscount] = useState('0');
  const [invInitialPaid, setInvInitialPaid] = useState('0');
  const [invCashbox, setInvCashbox] = useState('');
  const [invNotes, setInvNotes] = useState('');
  const [draftLines, setDraftLines] = useState<DraftLine[]>([]);
  const [pickerProductId, setPickerProductId] = useState<number>(0);
  const [pickerQty, setPickerQty] = useState('1');

  // Customer Statement & Customers CRUD
  const [ledgerCustomerId, setLedgerCustomerId] = useState<number>(0);
  const [custForm, setCustForm] = useState({ name: '', phone: '', address: '', notes: '' });

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<SalesInvoice>('invoices', setInvoices),
      ErpDataService.subscribeToCollection<InvoicePayment>('invoice_payments', setPayments),
      ErpDataService.subscribeToCollection<Customer>('customers', setCustomers),
      ErpDataService.subscribeToCollection<Product>('products', setProducts),
      ErpDataService.subscribeToCollection<StockMove>('stock_moves', setStockMoves),
      ErpDataService.subscribeToCollection<Warehouse>('warehouses', setWarehouses),
      ErpDataService.subscribeToCollection<Cashbox>('cashboxes', setCashboxes)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  useEffect(() => {
    if (customers.length > 0 && selectedCustomerId === 0) setSelectedCustomerId(Number(customers[0].id));
    if (customers.length > 0 && ledgerCustomerId === 0) setLedgerCustomerId(Number(customers[0].id));
    if (products.length > 0 && pickerProductId === 0) setPickerProductId(Number(products[0].id));
    if (warehouses.length > 0 && invWarehouseId === 0) setInvWarehouseId(Number(warehouses[0].id));
    if (cashboxes.length > 0 && !invCashbox) setInvCashbox(cashboxes[0].name);
    if (cashboxes.length > 0 && !payCashbox) setPayCashbox(cashboxes[0].name);
  }, [customers, products, warehouses, cashboxes]);

  const productStocks = useMemo(
    () => ErpDataService.computeProductStocks(products, stockMoves),
    [products, stockMoves]
  );

  const customerDebts = useMemo(
    () => ErpDataService.computeCustomerDebts(customers, invoices, payments),
    [customers, invoices, payments]
  );

  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE);
  const canDelete = canPerformAction(GRANULAR_ACTIONS.DELETE);

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const handleAddDraftLine = () => {
    const prod = products.find((p) => Number(p.id) === Number(pickerProductId));
    const q = Number(pickerQty) || 0;
    if (!prod || q <= 0) return;
    const price = invCurrency === 'EGP' ? Number(prod.salePriceEgp || prod.salePrice) : Number(prod.salePrice || prod.salePriceSdg || 0);
    setDraftLines((prev) => [
      ...prev,
      {
        productId: Number(prod.id),
        warehouseId: invWarehouseId,
        description: prod.name,
        quantity: q,
        unitPrice: price
      }
    ]);
    setPickerQty('1');
  };

  const draftSubtotal = draftLines.reduce((s, l) => s + l.quantity * l.unitPrice, 0);
  const draftNetTotal = Math.max(0, draftSubtotal - (Number(invDiscount) || 0));

  const handleCreateInvoice = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate) {
      showNotice('err', 'لا تملك صلاحية إصدار الفواتير');
      return;
    }
    if (draftLines.length === 0) {
      showNotice('err', 'أضف صنفاً واحداً على الأقل قبل حفظ الفاتورة');
      return;
    }

    const existingCust = customers.find((c) => Number(c.id) === Number(selectedCustomerId));
    const custName = existingCust?.name || quickCustomerName.trim();
    if (!custName) {
      showNotice('err', 'اختر العميل أو اكتب اسماً لإنشاء عميل جديد');
      return;
    }

    const now = Date.now();
    let finalCustId = existingCust ? Number(existingCust.id) : now;
    if (!existingCust && quickCustomerName.trim()) {
      const newCust: Customer = {
        id: finalCustId,
        name: custName,
        phone: '',
        address: '',
        notes: 'أُنشئ من فاتورة المبيعات',
        active: true,
        createdAt: now
      };
      await ErpDataService.saveRecord('customers', `CUST-${finalCustId}`, newCust, userProfile?.username);
    }

    const invoiceNo = `INV-${new Date().getFullYear()}-${String(now).slice(-5)}`;
    const today = new Date().toISOString().split('T')[0];
    const actor = userProfile?.fullName || userProfile?.username || 'WEB_SALES';
    const paidAmt = Math.min(draftNetTotal, Math.max(0, Number(invInitialPaid) || 0));

    const invoiceItems: InvoiceItem[] = draftLines.map((l, idx) => ({
      id: now + idx + 1,
      invoiceId: now,
      description: l.description,
      quantity: l.quantity,
      unitPrice: l.unitPrice,
      lineTotal: l.quantity * l.unitPrice,
      productId: l.productId,
      warehouseId: l.warehouseId || null
    }));

    const initialPayments: InvoicePayment[] =
      paidAmt > 0
        ? [
            {
              id: now + 100,
              invoiceId: now,
              payDate: today,
              cashbox: invCashbox || cashboxes[0]?.name || 'الخزنة الرئيسية',
              amount: paidAmt,
              note: 'دفعة مقدمة عند الإصدار',
              status: 'active',
              createdBy: actor,
              createdAt: now
            }
          ]
        : [];

    const invoiceDoc: SalesInvoice = {
      id: now,
      invoiceNo,
      customerId: finalCustId,
      customerName: custName,
      invoiceDate: today,
      currency: invCurrency,
      subtotal: draftSubtotal,
      discount: Number(invDiscount) || 0,
      total: draftNetTotal,
      notes: invNotes.trim(),
      status: 'active',
      createdBy: actor,
      createdAt: now,
      paidAmount: paidAmt,
      remainingAmount: Math.max(0, draftNetTotal - paidAmt),
      items: invoiceItems,
      payments: initialPayments
    };

    try {
      const invDocId = safeId(invoiceNo, `INV-${now}`);
      await ErpDataService.saveRecord('invoices', invDocId, invoiceDoc, userProfile?.username);

      // Deduct stock per line in `stock_moves`
      await Promise.all(
        invoiceItems.map((item, idx) => {
          const smId = now + 200 + idx;
          const move: StockMove = {
            id: smId,
            warehouseId: item.warehouseId || invWarehouseId || 1,
            productId: Number(item.productId),
            quantity: item.quantity,
            kind: 'SALE',
            invoiceId: now,
            note: `بيع فاتورة ${invoiceNo}`,
            createdBy: actor,
            createdAt: now
          };
          return ErpDataService.saveRecord('stock_moves', `SM-${smId}`, move, userProfile?.username);
        })
      );

      if (paidAmt > 0 && initialPayments[0]) {
        const pay = initialPayments[0];
        const movId = now + 300;
        const mov: TreasuryMovement = {
          id: movId,
          txDate: today,
          cashbox: pay.cashbox,
          currency: invCurrency,
          direction: 'وارد',
          amount: paidAmt,
          description: `تحصيل فاتورة ${invoiceNo} — ${custName}`,
          reference: invoiceNo,
          createdBy: actor,
          createdAt: now
        };
        await Promise.all([
          ErpDataService.saveRecord('invoice_payments', `PAY-${pay.id}`, pay, userProfile?.username),
          ErpDataService.saveRecord('movements', `MOV-${movId}`, mov, userProfile?.username)
        ]);
      }

      await ErpDataService.logAudit(
        userProfile?.username || 'WEB',
        'CREATE_SALES_INVOICE',
        `فاتورة ${invoiceNo} للعميل ${custName} بقيمة ${draftNetTotal} ${invCurrency}`
      );
      setDraftLines([]);
      setInvDiscount('0');
      setInvInitialPaid('0');
      setInvNotes('');
      setQuickCustomerName('');
      setSubTab('INVOICES');
      showNotice('ok', `تم إصدار الفاتورة ${invoiceNo} وخصم المخزون وتحديث الخزينة`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر حفظ الفاتورة');
    }
  };

  const handleCollectPayment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!payingInvoice || !canCreate) return;
    const amt = Number(payAmount);
    if (!Number.isFinite(amt) || amt <= 0) return;

    const now = Date.now();
    const today = new Date().toISOString().split('T')[0];
    const actor = userProfile?.fullName || userProfile?.username || 'WEB_SALES';
    const invNo = payingInvoice.invoiceNo || payingInvoice.invoiceNumber || `INV-${payingInvoice.id}`;

    const payment: InvoicePayment = {
      id: now,
      invoiceId: payingInvoice.id,
      payDate: today,
      cashbox: payCashbox || cashboxes[0]?.name || 'الخزنة الرئيسية',
      amount: amt,
      note: payNote.trim() || `تحصيل دفعة من فاتورة ${invNo}`,
      status: 'active',
      createdBy: actor,
      createdAt: now
    };

    const mov: TreasuryMovement = {
      id: now + 1,
      txDate: today,
      cashbox: payment.cashbox,
      currency: payingInvoice.currency || 'SDG',
      direction: 'وارد',
      amount: amt,
      description: `تحصيل مديونية فاتورة ${invNo} — ${payingInvoice.customerName}`,
      reference: invNo,
      createdBy: actor,
      createdAt: now
    };

    try {
      const updatedInvPayments = [...(payingInvoice.payments || []), payment];
      const invDocId = (payingInvoice as any)._docId || safeId(invNo, `INV-${payingInvoice.id}`);
      await Promise.all([
        ErpDataService.saveRecord('invoice_payments', `PAY-${payment.id}`, payment, userProfile?.username),
        ErpDataService.saveRecord('movements', `MOV-${mov.id}`, mov, userProfile?.username),
        ErpDataService.saveRecord('invoices', invDocId, { payments: updatedInvPayments }, userProfile?.username)
      ]);
      setPayingInvoice(null);
      setPayAmount('');
      setPayNote('');
      showNotice('ok', `تم تحصيل ${amt.toLocaleString()} ${payingInvoice.currency} وإيداعها في الخزنة`);
    } catch (err: any) {
      showNotice('err', err?.message || 'تعذر تسجيل التحصيل');
    }
  };

  const handleCancelInvoice = async (inv: SalesInvoice) => {
    if (!canDelete || inv.status === 'cancelled') return;
    const invDocId = (inv as any)._docId || safeId(inv.invoiceNo || `INV-${inv.id}`, `INV-${inv.id}`);
    await ErpDataService.saveRecord(
      'invoices',
      invDocId,
      { status: 'cancelled', cancelReason: `إلغاء بواسطة ${userProfile?.username || 'WEB'}` },
      userProfile?.username
    );
    showNotice('ok', `تم إلغاء الفاتورة ${inv.invoiceNo}`);
  };

  const handleSaveCustomer = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || !custForm.name.trim()) return;
    const id = Date.now();
    const record: Customer = {
      id,
      name: custForm.name.trim(),
      phone: custForm.phone.trim(),
      address: custForm.address.trim(),
      notes: custForm.notes.trim(),
      active: true,
      createdAt: id
    };
    await ErpDataService.saveRecord('customers', `CUST-${id}`, record, userProfile?.username);
    setCustForm({ name: '', phone: '', address: '', notes: '' });
    showNotice('ok', `تمت إضافة العميل "${record.name}"`);
  };

  const filteredInvoices = useMemo(() => {
    return [...invoices]
      .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
      .filter((inv) => {
        const invNo = inv.invoiceNo || inv.invoiceNumber || '';
        const fin = ErpDataService.computeInvoiceFinancials(inv, payments);
        const matchesSearch =
          !searchTerm ||
          invNo.toLowerCase().includes(searchTerm.toLowerCase()) ||
          (inv.customerName || '').toLowerCase().includes(searchTerm.toLowerCase());
        const matchesStatus = statusFilter === 'ALL' || fin.statusLabel === statusFilter;
        return matchesSearch && matchesStatus;
      });
  }, [invoices, payments, searchTerm, statusFilter]);

  const handleExportCsv = () => {
    const headers = ['رقم الفاتورة', 'التاريخ', 'العميل', 'الصافي', 'المدفوع', 'المتبقي', 'العملة', 'الحالة'];
    const rows = filteredInvoices.map((inv) => {
      const fin = ErpDataService.computeInvoiceFinancials(inv, payments);
      return [
        inv.invoiceNo || inv.invoiceNumber || '-',
        inv.invoiceDate || inv.date || '-',
        inv.customerName,
        Number(inv.total ?? inv.finalAmount ?? 0),
        fin.paidAmount,
        fin.remainingAmount,
        inv.currency,
        fin.statusLabel
      ];
    });
    ReportExporter.exportToCsv('pmp_sales_invoices', headers, rows);
  };

  const handlePrint = () => {
    const headers = ['الفاتورة', 'العميل', 'الصافي', 'المدفوع', 'المتبقي', 'الحالة'];
    const rows = filteredInvoices.map((inv) => {
      const fin = ErpDataService.computeInvoiceFinancials(inv, payments);
      return [
        inv.invoiceNo || inv.invoiceNumber || '-',
        inv.customerName,
        `${Number(inv.total ?? inv.finalAmount ?? 0).toLocaleString()} ${inv.currency}`,
        fin.paidAmount.toLocaleString(),
        fin.remainingAmount.toLocaleString(),
        fin.statusLabel
      ];
    });
    ReportExporter.exportToPrint('سجل فواتير المبيعات - PMP PAINTS', headers, rows);
  };

  return (
    <div className="space-y-6">
      <GlassCard className="p-5 sm:p-6">
        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-4 border-b border-theme">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-black text-theme-primary">
                {language === 'ar' ? 'المبيعات والعملاء والفوترة' : 'Sales, Invoicing & Customers'}
              </h2>
              <GlassBadge variant="red">{invoices.length} فاتورة</GlassBadge>
            </div>
            <p className="text-xs text-theme-secondary mt-1">
              سجل الفواتير، إنشاء فاتورة جديدة بخصم المخزون، كشف حساب المديونيات، ودليل العملاء.
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

        {/* Exact 4 Android Sales Sub-Tabs */}
        <div className="flex items-center gap-2 mt-4 overflow-x-auto pb-1">
          {[
            { id: 'INVOICES' as SalesSubTab, label: `سجل الفواتير (${invoices.length})` },
            { id: 'NEW_INVOICE' as SalesSubTab, label: '+ فاتورة بيع جديدة' },
            { id: 'CUSTOMER_LEDGER' as SalesSubTab, label: 'كشف حساب عميل ومديونيات' },
            { id: 'CUSTOMERS' as SalesSubTab, label: `دليل العملاء (${customers.length})` }
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

      {/* Sub-tab 1: Invoices */}
      {subTab === 'INVOICES' && (
        <div className="space-y-4">
          <GlassCard className="p-4">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
              <div className="relative sm:col-span-2">
                <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
                <input
                  type="text"
                  placeholder="بحث برقم الفاتورة أو اسم العميل..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="w-full pr-9 pl-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value as any)}
                className="px-3 py-2 rounded-lg bg-theme-base border border-theme text-theme-primary"
              >
                <option value="ALL">كافة الحالات</option>
                <option value="مدفوعة">مدفوعة</option>
                <option value="سداد جزئي">سداد جزئي</option>
                <option value="غير مدفوعة">غير مدفوعة</option>
                <option value="ملغاة">ملغاة</option>
              </select>
            </div>
          </GlassCard>

          <GlassCard className="p-0 overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-right border-collapse text-xs min-w-[660px]">
                <thead>
                  <tr className="bg-theme-surface/80 border-b border-theme text-theme-muted">
                    <th className="py-3 px-4">رقم الفاتورة</th>
                    <th className="py-3 px-4">العميل</th>
                    <th className="py-3 px-4">الصافي</th>
                    <th className="py-3 px-4">المدفوع</th>
                    <th className="py-3 px-4">المتبقي</th>
                    <th className="py-3 px-4">الحالة</th>
                    <th className="py-3 px-4">التاريخ</th>
                    <th className="py-3 px-4">إجراءات</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {filteredInvoices.map((inv) => {
                    const fin = ErpDataService.computeInvoiceFinancials(inv, payments);
                    const total = Number(inv.total ?? inv.finalAmount ?? 0);
                    return (
                      <tr key={inv.id} className="hover:bg-theme-surface/40">
                        <td className="py-3 px-4 font-mono font-bold text-pmp-primary">
                          {inv.invoiceNo || inv.invoiceNumber}
                        </td>
                        <td className="py-3 px-4 font-bold text-theme-primary">{inv.customerName}</td>
                        <td className="py-3 px-4 font-mono font-bold">
                          {total.toLocaleString()} {inv.currency}
                        </td>
                        <td className="py-3 px-4 font-mono text-emerald-400">
                          {fin.paidAmount.toLocaleString()}
                        </td>
                        <td className="py-3 px-4 font-mono text-amber-400">
                          {fin.remainingAmount.toLocaleString()}
                        </td>
                        <td className="py-3 px-4">
                          <GlassBadge
                            variant={
                              fin.statusLabel === 'مدفوعة'
                                ? 'green'
                                : fin.statusLabel === 'سداد جزئي'
                                ? 'gold'
                                : 'red'
                            }
                          >
                            {fin.statusLabel}
                          </GlassBadge>
                        </td>
                        <td className="py-3 px-4 font-mono text-theme-muted">
                          {inv.invoiceDate || inv.date}
                        </td>
                        <td className="py-3 px-4 flex items-center gap-1.5">
                          {fin.remainingAmount > 0 && inv.status !== 'cancelled' && canCreate && (
                            <button
                              type="button"
                              onClick={() => {
                                setPayingInvoice(inv);
                                setPayAmount(String(fin.remainingAmount));
                              }}
                              className="px-2 py-1 rounded-lg bg-emerald-500/15 text-emerald-400 font-bold hover:bg-emerald-500/25"
                            >
                              تحصيل
                            </button>
                          )}
                          {canDelete && inv.status !== 'cancelled' && (
                            <button
                              type="button"
                              onClick={() => handleCancelInvoice(inv)}
                              className="p-1.5 text-red-400 hover:bg-red-500/10 rounded-lg"
                              title="إلغاء الفاتورة"
                            >
                              <XCircle className="w-4 h-4" />
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
        </div>
      )}

      {/* Sub-tab 2: New Invoice Builder */}
      {subTab === 'NEW_INVOICE' && (
        <GlassCard className="p-6">
          <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
            <Plus className="w-4 h-4 text-pmp-primary" />
            إصدار فاتورة مبيعات جديدة
          </h3>
          <form onSubmit={handleCreateInvoice} className="space-y-4 text-xs">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div>
                <label className="block font-bold text-theme-secondary mb-1">العميل المسجل</label>
                <select
                  value={selectedCustomerId}
                  onChange={(e) => setSelectedCustomerId(Number(e.target.value))}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  <option value={0}>-- أو اكتب اسم عميل جديد --</option>
                  {customers.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} {c.phone ? `(${c.phone})` : ''}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">أو عميل جديد</label>
                <input
                  type="text"
                  value={quickCustomerName}
                  onChange={(e) => setQuickCustomerName(e.target.value)}
                  placeholder="اسم العميل الجديد (عند عدم الاختيار)"
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">العملة والمخزن</label>
                <div className="grid grid-cols-2 gap-2">
                  <select
                    value={invCurrency}
                    onChange={(e) => setInvCurrency(e.target.value as CurrencyCode)}
                    className="px-2 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  >
                    <option value="SDG">SDG</option>
                    <option value="EGP">EGP</option>
                    <option value="USD">USD</option>
                  </select>
                  <select
                    value={invWarehouseId}
                    onChange={(e) => setInvWarehouseId(Number(e.target.value))}
                    className="px-2 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  >
                    {warehouses.map((w) => (
                      <option key={w.id} value={w.id}>
                        {w.name}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            </div>

            {/* Line Picker */}
            <div className="p-4 rounded-xl bg-theme-base border border-theme space-y-3">
              <div className="grid grid-cols-1 sm:grid-cols-12 gap-2 items-end">
                <div className="sm:col-span-7">
                  <label className="block font-bold text-theme-secondary mb-1">اختر المنتج التام</label>
                  <select
                    value={pickerProductId}
                    onChange={(e) => setPickerProductId(Number(e.target.value))}
                    className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
                  >
                    {products.map((p) => {
                      const stk = productStocks.get(Number(p.id)) ?? 0;
                      const price = invCurrency === 'EGP' ? p.salePriceEgp : p.salePrice;
                      return (
                        <option key={p.id} value={p.id}>
                          {p.name} — الرصيد: {stk} — السعر: {price} {invCurrency}
                        </option>
                      );
                    })}
                  </select>
                </div>
                <div className="sm:col-span-3">
                  <label className="block font-bold text-theme-secondary mb-1">الكمية</label>
                  <input
                    type="number"
                    min="1"
                    value={pickerQty}
                    onChange={(e) => setPickerQty(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary font-mono"
                  />
                </div>
                <div className="sm:col-span-2">
                  <GlassButton type="button" variant="secondary" onClick={handleAddDraftLine} className="w-full justify-center">
                    + إضافة
                  </GlassButton>
                </div>
              </div>

              {draftLines.length > 0 && (
                <div className="overflow-x-auto pt-2">
                  <table className="w-full text-right text-xs">
                    <thead>
                      <tr className="border-b border-theme text-theme-muted">
                        <th className="py-1.5">الصنف</th>
                        <th className="py-1.5">الكمية</th>
                        <th className="py-1.5">السعر</th>
                        <th className="py-1.5">الإجمالي</th>
                        <th className="py-1.5">حذف</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-theme">
                      {draftLines.map((line, i) => (
                        <tr key={i}>
                          <td className="py-2 font-bold">{line.description}</td>
                          <td className="py-2 font-mono">{line.quantity}</td>
                          <td className="py-2 font-mono">{line.unitPrice.toLocaleString()}</td>
                          <td className="py-2 font-mono font-bold text-pmp-primary">
                            {(line.quantity * line.unitPrice).toLocaleString()} {invCurrency}
                          </td>
                          <td className="py-2">
                            <button
                              type="button"
                              onClick={() => setDraftLines((prev) => prev.filter((_, idx) => idx !== i))}
                              className="text-red-400"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
              <div>
                <label className="block font-bold text-theme-secondary mb-1">الخصم</label>
                <input
                  type="number"
                  value={invDiscount}
                  onChange={(e) => setInvDiscount(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">المدفوع نقداً</label>
                <input
                  type="number"
                  value={invInitialPaid}
                  onChange={(e) => setInvInitialPaid(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                />
              </div>
              <div>
                <label className="block font-bold text-theme-secondary mb-1">الخزنة المستلمة</label>
                <select
                  value={invCashbox}
                  onChange={(e) => setInvCashbox(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
                >
                  {cashboxes.map((c) => (
                    <option key={c.name} value={c.name}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="p-2.5 rounded-xl bg-pmp-primary/10 border border-pmp-primary/30 flex flex-col justify-center">
                <span className="text-[10px] text-theme-muted">صافي الفاتورة</span>
                <span className="text-base font-black text-pmp-primary font-mono">
                  {draftNetTotal.toLocaleString()} {invCurrency}
                </span>
              </div>
            </div>

            <div className="flex justify-end">
              <GlassButton type="submit" variant="primary" disabled={!canCreate || draftLines.length === 0}>
                اعتماد الفاتورة وخصم المخزون
              </GlassButton>
            </div>
          </form>
        </GlassCard>
      )}

      {/* Sub-tab 3: Customer Ledger / Debts */}
      {subTab === 'CUSTOMER_LEDGER' && (
        <GlassCard className="p-6 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h3 className="text-sm font-bold text-theme-primary flex items-center gap-2">
              <Wallet className="w-4 h-4 text-pmp-gold" />
              كشف حساب العملاء والمديونيات القائمة
            </h3>
            <select
              value={ledgerCustomerId}
              onChange={(e) => setLedgerCustomerId(Number(e.target.value))}
              className="px-3 py-2 rounded-xl bg-theme-base border border-theme text-xs text-theme-primary"
            >
              {customers.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-right text-xs min-w-[500px]">
              <thead>
                <tr className="border-b border-theme text-theme-muted">
                  <th className="py-2">العميل</th>
                  <th className="py-2">إجمالي الفواتير</th>
                  <th className="py-2">إجمالي المسدد</th>
                  <th className="py-2">المديونية المتبقية</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {customers.map((c) => {
                  const debt = customerDebts.get(Number(c.id)) || {
                    totalInvoiced: 0,
                    totalPaid: 0,
                    remaining: 0,
                    primaryCurrency: 'SDG'
                  };
                  return (
                    <tr key={c.id}>
                      <td className="py-2.5 font-bold text-theme-primary">{c.name}</td>
                      <td className="py-2.5 font-mono">{debt.totalInvoiced.toLocaleString()} {debt.primaryCurrency}</td>
                      <td className="py-2.5 font-mono text-emerald-400">{debt.totalPaid.toLocaleString()}</td>
                      <td className={`py-2.5 font-mono font-bold ${debt.remaining > 0 ? 'text-amber-400' : 'text-emerald-400'}`}>
                        {debt.remaining.toLocaleString()} {debt.primaryCurrency}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </GlassCard>
      )}

      {/* Sub-tab 4: Customers Directory */}
      {subTab === 'CUSTOMERS' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <GlassCard className="p-6 lg:col-span-4">
            <h3 className="text-sm font-bold text-theme-primary mb-4 flex items-center gap-2">
              <Users className="w-4 h-4 text-pmp-primary" />
              إضافة عميل جديد
            </h3>
            <form onSubmit={handleSaveCustomer} className="space-y-3 text-xs">
              <input
                type="text"
                required
                placeholder="اسم العميل *"
                value={custForm.name}
                onChange={(e) => setCustForm({ ...custForm, name: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <input
                type="text"
                placeholder="رقم الهاتف"
                value={custForm.phone}
                onChange={(e) => setCustForm({ ...custForm, phone: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <input
                type="text"
                placeholder="العنوان / المدينة"
                value={custForm.address}
                onChange={(e) => setCustForm({ ...custForm, address: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <input
                type="text"
                placeholder="ملاحظات"
                value={custForm.notes}
                onChange={(e) => setCustForm({ ...custForm, notes: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
              />
              <GlassButton type="submit" variant="primary" className="w-full justify-center" disabled={!canCreate}>
                حفظ العميل
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-8">
            <h4 className="text-sm font-bold text-theme-primary mb-3">قائمة العملاء ({customers.length})</h4>
            <div className="overflow-x-auto">
              <table className="w-full text-right text-xs">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2">الاسم</th>
                    <th className="py-2">الهاتف</th>
                    <th className="py-2">العنوان</th>
                    <th className="py-2">المديونية</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {customers.map((c) => {
                    const debt = customerDebts.get(Number(c.id));
                    return (
                      <tr key={c.id}>
                        <td className="py-2.5 font-bold">{c.name}</td>
                        <td className="py-2.5 font-mono text-theme-secondary">{c.phone || '-'}</td>
                        <td className="py-2.5 text-theme-muted">{c.address || '-'}</td>
                        <td className="py-2.5 font-mono text-amber-400">
                          {(debt?.remaining || 0).toLocaleString()} {debt?.primaryCurrency || 'SDG'}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {/* Payment Collection Modal */}
      <Modal
        isOpen={Boolean(payingInvoice)}
        onClose={() => setPayingInvoice(null)}
        title={`تحصيل دفعة — ${payingInvoice?.invoiceNo || ''}`}
        maxWidth="max-w-md"
      >
        <form onSubmit={handleCollectPayment} className="space-y-4 text-xs">
          <div>
            <label className="block font-bold text-theme-primary mb-1">المبلغ المحصل ({payingInvoice?.currency})</label>
            <input
              type="number"
              step="any"
              required
              value={payAmount}
              onChange={(e) => setPayAmount(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary font-mono"
            />
          </div>
          <div>
            <label className="block font-bold text-theme-primary mb-1">الخزنة المستلمة</label>
            <select
              value={payCashbox}
              onChange={(e) => setPayCashbox(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
            >
              {cashboxes.map((c) => (
                <option key={c.name} value={c.name}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-bold text-theme-primary mb-1">ملاحظات</label>
            <input
              type="text"
              value={payNote}
              onChange={(e) => setPayNote(e.target.value)}
              className="w-full px-3 py-2 rounded-lg bg-theme-surface border border-theme text-theme-primary"
            />
          </div>
          <div className="flex justify-end gap-2">
            <GlassButton variant="secondary" onClick={() => setPayingInvoice(null)}>
              إلغاء
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              تأكيد التحصيل
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
