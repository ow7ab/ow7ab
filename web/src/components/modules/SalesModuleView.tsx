import React, { useEffect, useMemo, useState } from 'react';
import {
  Plus,
  Search,
  Printer,
  FileSpreadsheet,
  Users,
  Wallet,
  Trash2,
  XCircle,
  CheckCircle2,
  AlertCircle,
  FileText
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
import {
  ErpDataService,
  safeId,
  isSalesWarehouse,
  buildSalesInvoiceJournalPayloads,
  resolveCashAccountCode,
  createBalancedJournalPayload
} from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';
import { useTransactionGuard } from '../../hooks/useTransactionGuard';
import { OPERATION_LOADING_LABELS } from '../../services/IdempotencyService';

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

  // Payment & Permanent Delete Modal State
  const [payingInvoice, setPayingInvoice] = useState<SalesInvoice | null>(null);
  const [deletingInvoice, setDeletingInvoice] = useState<SalesInvoice | null>(null);
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

  const invGuard = useTransactionGuard('sales_create_invoice', 'APPROVE_INVOICE');
  const payGuard = useTransactionGuard('sales_collect_payment', 'PAYMENT');
  const cancelGuard = useTransactionGuard('sales_cancel_invoice', 'UPDATE');
  const deleteGuard = useTransactionGuard('sales_delete_invoice', 'DELETE');
  const custGuard = useTransactionGuard('sales_save_customer', 'SAVE');
  const isSubmitting =
    invGuard.isSubmitting ||
    payGuard.isSubmitting ||
    cancelGuard.isSubmitting ||
    deleteGuard.isSubmitting ||
    custGuard.isSubmitting;

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

  const salesWarehouses = useMemo(
    () => warehouses.filter((w) => isSalesWarehouse(w)),
    [warehouses]
  );

  useEffect(() => {
    if (customers.length > 0 && selectedCustomerId === 0) setSelectedCustomerId(Number(customers[0].id));
    if (customers.length > 0 && ledgerCustomerId === 0) setLedgerCustomerId(Number(customers[0].id));
    if (products.length > 0 && pickerProductId === 0) setPickerProductId(Number(products[0].id));
    if (salesWarehouses.length > 0) {
      const currentIsSales = salesWarehouses.some((w) => Number(w.id) === Number(invWarehouseId));
      if (invWarehouseId === 0 || !currentIsSales) {
        setInvWarehouseId(Number(salesWarehouses[0].id));
      }
    }
    if (cashboxes.length > 0 && !invCashbox) setInvCashbox(cashboxes[0].name);
    if (cashboxes.length > 0 && !payCashbox) setPayCashbox(cashboxes[0].name);
  }, [customers, products, salesWarehouses, cashboxes, invWarehouseId]);

  const productStocks = useMemo(
    () => ErpDataService.computeProductStocksList(products, stockMoves, invWarehouseId > 0 ? invWarehouseId : null, warehouses),
    [products, stockMoves, invWarehouseId, warehouses]
  );

  const productStocksMap = useMemo(
    () => ErpDataService.computeProductStocks(products, stockMoves, invWarehouseId > 0 ? invWarehouseId : null, warehouses),
    [products, stockMoves, invWarehouseId, warehouses]
  );

  const customerDebts = useMemo(
    () => ErpDataService.computeCustomerDebtsList(customers, invoices, payments),
    [customers, invoices, payments]
  );

  const selectedLedgerCustomer = useMemo(
    () => customers.find((c) => Number(c.id) === Number(ledgerCustomerId)) || customers[0] || null,
    [customers, ledgerCustomerId]
  );

  const selectedCustomerStatement = useMemo(() => {
    if (!selectedLedgerCustomer) return null;
    return ErpDataService.computeCustomerStatement(selectedLedgerCustomer, invoices, payments);
  }, [selectedLedgerCustomer, invoices, payments]);

  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE) || canPerformAction(GRANULAR_ACTIONS.FINANCIAL_OPS);
  const canCollect = canPerformAction(GRANULAR_ACTIONS.CREATE) || canPerformAction(GRANULAR_ACTIONS.FINANCIAL_OPS);
  const canDelete = canPerformAction(GRANULAR_ACTIONS.DELETE);

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const handleExportSpecificCustomerPdf = (cust: Customer) => {
    const stmt = ErpDataService.computeCustomerStatement(cust, invoices, payments);
    ReportExporter.exportCustomerStatementToPrint(stmt);
  };

  const handleExportSpecificCustomerCsv = (cust: Customer) => {
    const stmt = ErpDataService.computeCustomerStatement(cust, invoices, payments);
    ReportExporter.exportCustomerStatementToCsv(stmt);
  };

  const handleAddDraftLine = () => {
    const selectedWh = warehouses.find((w) => Number(w.id) === Number(invWarehouseId));
    if (selectedWh && !isSalesWarehouse(selectedWh)) {
      showNotice('err', 'لا يُسمح بالبيع من مخزن المواد الخام أو الإنتاج التام؛ اختر مخزن مبيعات فقط.');
      return;
    }
    const prod =
      productStocks.find((p) => Number(p.id) === Number(pickerProductId)) ||
      products.find((p) => Number(p.id) === Number(pickerProductId));
    const q = Number(pickerQty);
    if (!prod || !Number.isFinite(q) || q <= 0) {
      showNotice('err', 'يرجى إدخال كمية موجبة صحيحة');
      return;
    }

    const availableStock = Number(prod.currentStock ?? productStocksMap.get(Number(prod.id)) ?? 0);
    const alreadyQueuedQty = draftLines
      .filter((l) => Number(l.productId) === Number(prod.id))
      .reduce((s, l) => s + Number(l.quantity || 0), 0);

    if (stockMoves.length > 0 && alreadyQueuedQty + q > availableStock) {
      showNotice(
        'err',
        `الكمية المطلوبة (${alreadyQueuedQty + q}) تتجاوز الرصيد المتاح في المستودع (${availableStock}) للصنف "${prod.name}"`
      );
      return;
    }

    const rawPrice =
      invCurrency === 'EGP'
        ? Number(prod.salePriceEgp || prod.salePrice || 0)
        : Number(prod.salePrice || prod.salePriceSdg || 0);
    const price = Number.isFinite(rawPrice) && rawPrice >= 0 ? rawPrice : 0;

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

  const draftSubtotal = draftLines.reduce((s, l) => s + Math.max(0, l.quantity) * Math.max(0, l.unitPrice), 0);
  const safeDraftDiscount = Math.min(draftSubtotal, Math.max(0, Number(invDiscount) || 0));
  const draftNetTotal = Math.max(0, draftSubtotal - safeDraftDiscount);

  const handleCreateInvoice = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isSubmitting) return;
    if (!canCreate) {
      showNotice('err', 'لا تملك صلاحية إصدار الفواتير');
      return;
    }
    if (draftLines.length === 0) {
      showNotice('err', 'أضف صنفاً واحداً على الأقل قبل حفظ الفاتورة');
      return;
    }
    const selectedWh = warehouses.find((w) => Number(w.id) === Number(invWarehouseId));
    if (!selectedWh || !isSalesWarehouse(selectedWh)) {
      showNotice('err', 'يُمنع إصدار فواتير المبيعات من غير مخزن مبيعات معتمد (SALES).');
      return;
    }

    // Strict financial & stock integrity re-validation on submit (prevents UI/state tampering)
    for (const line of draftLines) {
      if (!Number.isFinite(line.quantity) || line.quantity <= 0 || !Number.isFinite(line.unitPrice) || line.unitPrice < 0) {
        showNotice('err', 'توجد قيم غير صالحة في بنود الفاتورة');
        return;
      }
    }

    const totalQtyByProd = new Map<number, number>();
    for (const line of draftLines) {
      totalQtyByProd.set(line.productId, (totalQtyByProd.get(line.productId) || 0) + line.quantity);
    }
    if (stockMoves.length > 0) {
      for (const [pid, reqQty] of totalQtyByProd.entries()) {
        const available = Number(
          productStocks.find((p) => Number(p.id) === pid)?.currentStock ?? productStocksMap.get(pid) ?? 0
        );
        if (reqQty > available) {
          const pName = productStocks.find((p) => Number(p.id) === pid)?.name || `#${pid}`;
          showNotice('err', `الرصيد المتاح لا يكفي للصنف "${pName}" (المتاح: ${available}، المطلوب: ${reqQty})`);
          return;
        }
      }
    }

    const rawDiscount = Number(invDiscount);
    const rawPaid = Number(invInitialPaid);
    if ((invDiscount.trim() !== '' && (!Number.isFinite(rawDiscount) || rawDiscount < 0)) ||
        (invInitialPaid.trim() !== '' && (!Number.isFinite(rawPaid) || rawPaid < 0))) {
      showNotice('err', 'لا يُسمح بإدخال قيم خصم أو سداد سالبة أو غير صالحة');
      return;
    }

    const existingCust = customers.find((c) => Number(c.id) === Number(selectedCustomerId));
    const custName = (existingCust?.name || quickCustomerName).trim().slice(0, 120);
    if (!custName) {
      showNotice('err', 'اختر العميل أو اكتب اسماً لإنشاء عميل جديد');
      return;
    }

    const res = await invGuard.runGuarded({
      guardKey: 'sales_create_invoice',
      operationType: 'APPROVE_INVOICE',
      userId: userProfile?.username || 'sales',
      documentReference: `INV-${custName}-${draftLines.length}`,
      targetCollection: 'invoices',
      payloadSummary: `فاتورة مبيعات للعميل ${custName} (${invCurrency})`,
      execute: async ({ idempotencyKey }) => {
        const now = Date.now();
        const actorName = userProfile?.fullName || userProfile?.username || 'مبيعات';
        const ops: any[] = [];
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
          ops.push({
            kind: 'merge',
            collectionName: 'customers',
            docId: `CUST-${finalCustId}`,
            payload: newCust,
            checkDuplicateFinancial: false
          });
        }

        const authoritativeSubtotal = draftLines.reduce((s, l) => s + l.quantity * l.unitPrice, 0);
        const authoritativeDiscount = Math.min(authoritativeSubtotal, Math.max(0, rawDiscount || 0));
        const authoritativeNetTotal = Math.max(0, authoritativeSubtotal - authoritativeDiscount);
        const paidInit = Math.min(authoritativeNetTotal, Math.max(0, rawPaid || 0));

        const status =
          paidInit >= authoritativeNetTotal && authoritativeNetTotal > 0
            ? 'مدفوعة'
            : paidInit > 0
            ? 'سداد جزئي'
            : 'غير مدفوعة';

        const invoiceNo = `INV-${idempotencyKey.slice(-8)}`;
        const itemsList: InvoiceItem[] = draftLines.map((l) => ({
          productId: l.productId,
          warehouseId: l.warehouseId,
          description: l.description,
          quantity: l.quantity,
          unitPrice: l.unitPrice,
          lineTotal: l.quantity * l.unitPrice
        }));

        const resolvedCashbox = invCashbox || cashboxes[0]?.name || 'خزنة المبيعات';

        const newInv: SalesInvoice = {
          id: now,
          invoiceNo,
          customerId: finalCustId,
          customerName: custName,
          branch: userProfile?.branch || 'الخرطوم',
          warehouseId: invWarehouseId,
          subtotal: authoritativeSubtotal,
          discount: authoritativeDiscount,
          discountAmount: authoritativeDiscount,
          taxAmount: 0,
          total: authoritativeNetTotal,
          paidAmount: paidInit,
          currency: invCurrency,
          status,
          cashbox: resolvedCashbox,
          notes: invNotes.trim(),
          itemsJson: JSON.stringify(itemsList),
          createdBy: actorName,
          createdAt: now,
          timestamp: now
        };

        ops.push({
          kind: 'merge',
          collectionName: 'invoices',
          docId: invoiceNo,
          payload: newInv,
          checkDuplicateFinancial: true
        });

        const whObj = warehouses.find((w) => Number(w.id) === Number(invWarehouseId));
        for (let idx = 0; idx < draftLines.length; idx++) {
          const line = draftLines[idx];
          const moveTs = now + idx + 1;
          const sm: StockMove = {
            id: moveTs,
            productId: line.productId,
            productName: line.description,
            warehouseId: line.warehouseId,
            warehouseName: whObj?.name || 'المخزن الرئيسي',
            kind: 'SALE',
            quantity: line.quantity,
            invoiceId: now,
            note: `فاتورة مبيعات رقم ${invoiceNo} — العميل: ${custName}`,
            referenceNo: invoiceNo,
            createdBy: actorName,
            actor: actorName,
            createdAt: moveTs,
            timestamp: moveTs
          };
          ops.push({
            kind: 'merge',
            collectionName: 'stock_moves',
            docId: `SM-${idempotencyKey}-${idx}`,
            payload: sm,
            checkDuplicateFinancial: false
          });
        }

        if (paidInit > 0) {
          const payDoc: InvoicePayment = {
            id: now + 100,
            invoiceId: now,
            customerId: finalCustId,
            amount: paidInit,
            currency: invCurrency,
            cashbox: resolvedCashbox,
            note: `دفعة مقدمة عند إصدار الفاتورة ${invoiceNo}`,
            status: 'active',
            createdBy: actorName,
            receivedBy: actorName,
            createdAt: now,
            timestamp: now
          };
          ops.push({
            kind: 'merge',
            collectionName: 'invoice_payments',
            docId: `PAY-${idempotencyKey}`,
            payload: payDoc,
            checkDuplicateFinancial: true
          });

          const initMovDocId = `MOV-${idempotencyKey}`;
          const initPayRef = `INV:${invoiceNo}:P${payDoc.id}`;
          const tm: TreasuryMovement = {
            id: now + 101,
            txDate: new Date(now).toISOString().split('T')[0],
            cashbox: resolvedCashbox,
            direction: 'وارد',
            type: 'IN',
            amount: paidInit,
            currency: invCurrency,
            category: 'مبيعات نقدية',
            description: `تحصيل فاتورة مبيعات ${invoiceNo} — ${custName}`,
            note: `تحصيل فاتورة مبيعات ${invoiceNo} — ${custName}`,
            reference: initPayRef,
            referenceNo: initPayRef,
            cloudDocId: initMovDocId,
            syncUuid: initMovDocId,
            createdBy: actorName,
            actor: actorName,
            createdAt: now,
            timestamp: now
          };
          ops.push({
            kind: 'merge',
            collectionName: 'movements',
            docId: initMovDocId,
            payload: tm,
            checkDuplicateFinancial: true
          });
        }

        const totalCogs = draftLines.reduce((sum, l) => {
          const prod = products.find((p) => Number(p.id) === Number(l.productId));
          const unitCost = Number(prod?.purchasePrice || prod?.costPrice || 0);
          return sum + Math.max(0, l.quantity) * Math.max(0, unitCost);
        }, 0);
        const salesJournals = buildSalesInvoiceJournalPayloads({
          invoiceId: now,
          invoiceNo,
          invoiceDate: new Date(now).toISOString().split('T')[0],
          customerName: custName,
          currency: invCurrency,
          totalRevenue: authoritativeNetTotal,
          totalCogs,
          paidAmount: paidInit,
          cashbox: resolvedCashbox,
          actor: actorName
        });

        await ErpDataService.commitAtomicTransactionBundle({
          actorUsername: userProfile?.username,
          operations: ops,
          journals: salesJournals,
          auditLog: {
            username: userProfile?.username || 'sales',
            action: 'CREATE_INVOICE',
            detail: `إصدار فاتورة ذرية ${invoiceNo} للعميل ${custName} بقيمة ${authoritativeNetTotal.toLocaleString()} ${invCurrency}`
          }
        });

        return { resultReference: invoiceNo };
      }
    });

    if (res.ok) {
      setDraftLines([]);
      setInvDiscount('0');
      setInvInitialPaid('0');
      setInvNotes('');
      setQuickCustomerName('');
      setSubTab('INVOICES');
      showNotice('ok', res.replayed ? res.message : `تم إصدار الفاتورة ${res.resultReference} وخصم المخزون وتحديث الخزنة`);
    } else {
      showNotice('err', res.message);
    }
  };

  const handleCollectPayment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isSubmitting || !payingInvoice) return;
    if (!canCollect) {
      showNotice('err', 'لا تملك صلاحية تحصيل الدفعات المالية');
      return;
    }
    if (ErpDataService.isCancelledOrReversedStatus(payingInvoice.status)) {
      showNotice('err', 'لا يمكن تحصيل دفعة على فاتورة ملغاة');
      return;
    }

    const amt = Number(payAmount);
    const netTotal = Number(payingInvoice.total || 0);
    const invFinancials = ErpDataService.computeInvoiceFinancials(payingInvoice, payments);
    const currentPaid = invFinancials.paidAmount;
    const maxRemaining = Math.max(0, netTotal - currentPaid);

    if (!Number.isFinite(amt) || amt <= 0) {
      showNotice('err', 'يرجى إدخال مبلغ تحصيل صحيح أكبر من الصفر');
      return;
    }
    if (amt > maxRemaining + 0.01) {
      showNotice('err', `المبلغ المدخل (${amt}) يتجاوز الرصيد المتبقي على الفاتورة (${maxRemaining.toLocaleString()})`);
      return;
    }

    const res = await payGuard.runGuarded({
      guardKey: `sales_collect_payment_${payingInvoice.invoiceNo}`,
      operationType: 'PAYMENT',
      userId: userProfile?.username || 'treasury',
      documentReference: payingInvoice.invoiceNo,
      targetCollection: 'invoice_payments',
      payloadSummary: `تحصيل ${amt} ${payingInvoice.currency} على ${payingInvoice.invoiceNo}`,
      execute: async ({ idempotencyKey }) => {
        const now = Date.now();
        const actorName = userProfile?.fullName || userProfile?.username || 'أمين الخزنة';
        const newPaid = Math.min(netTotal, currentPaid + amt);
        const newStatus = newPaid >= netTotal && netTotal > 0 ? 'مدفوعة' : 'سداد جزئي';
        const invDocId = (payingInvoice as any)._docId || safeId(payingInvoice.invoiceNo, `INV-${payingInvoice.id}`);
        const targetCashbox = payCashbox || payingInvoice.cashbox || 'خزنة المبيعات';
        const payDocId = `PAY-${idempotencyKey}`;

        const payDoc: InvoicePayment = {
          id: now,
          invoiceId: Number(payingInvoice.id),
          customerId: Number(payingInvoice.customerId),
          amount: amt,
          currency: payingInvoice.currency,
          cashbox: targetCashbox,
          note: payNote.trim() || `تحصيل دفعة على فاتورة ${payingInvoice.invoiceNo}`,
          status: 'active',
          createdBy: actorName,
          receivedBy: actorName,
          createdAt: now,
          timestamp: now
        };

        const payMovDocId = `MOV-${idempotencyKey}`;
        const payRef = `INV:${payingInvoice.invoiceNo}:P${payDoc.id}`;
        const tm: TreasuryMovement = {
          id: now + 1,
          txDate: new Date(now).toISOString().split('T')[0],
          cashbox: targetCashbox,
          direction: 'وارد',
          type: 'IN',
          amount: amt,
          currency: payingInvoice.currency,
          category: 'تحصيل عملاء',
          description: `تحصيل فاتورة ${payingInvoice.invoiceNo} — ${payingInvoice.customerName}`,
          note: `تحصيل فاتورة ${payingInvoice.invoiceNo} — ${payingInvoice.customerName}`,
          reference: payRef,
          referenceNo: payRef,
          cloudDocId: payMovDocId,
          syncUuid: payMovDocId,
          createdBy: actorName,
          actor: actorName,
          createdAt: now,
          timestamp: now
        };

        const cashCode = resolveCashAccountCode(targetCashbox, payingInvoice.currency);
        const payJournal = createBalancedJournalPayload({
          entryDate: new Date(now).toISOString().split('T')[0],
          sourceType: 'CUSTOMER_PAYMENT',
          sourceDocId: now,
          sourceDocNo: payingInvoice.invoiceNo,
          description: `تحصيل دفعة من العميل ${payingInvoice.customerName} في ${targetCashbox}`,
          currency: payingInvoice.currency,
          lines: [
            { accountCode: cashCode, debit: amt, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: targetCashbox, memo: `وارد خزنة ${targetCashbox}` },
            { accountCode: '1130', credit: amt, costCenter: 'SALES', partyType: 'CUSTOMER', partyName: payingInvoice.customerName, memo: `تخفيض ذمة العميل ${payingInvoice.customerName}` }
          ],
          createdBy: actorName,
          idempotencyKey: `JRN-PAY-${idempotencyKey}`
        });

        await ErpDataService.commitAtomicTransactionBundle({
          actorUsername: userProfile?.username,
          operations: [
            {
              kind: 'merge',
              collectionName: 'invoice_payments',
              docId: payDocId,
              payload: payDoc,
              checkDuplicateFinancial: true
            },
            {
              kind: 'merge',
              collectionName: 'movements',
              docId: payMovDocId,
              payload: tm,
              checkDuplicateFinancial: true
            },
            {
              kind: 'invoice_summary',
              collectionName: 'invoices',
              docId: invDocId,
              payload: {
                paidAmount: newPaid,
                status: newStatus,
                updatedAt: now,
                serverUpdatedBy: userProfile?.username || actorName
              }
            }
          ],
          journals: [payJournal],
          auditLog: {
            username: userProfile?.username || 'treasury',
            action: 'INVOICE_PAYMENT',
            detail: `تحصيل ذري ${amt.toLocaleString()} ${payingInvoice.currency} على فاتورة ${payingInvoice.invoiceNo} (${targetCashbox})`
          }
        });
        return { resultReference: payDocId };
      }
    });

    if (res.ok) {
      setPayingInvoice(null);
      setPayAmount('');
      setPayNote('');
      showNotice('ok', res.replayed ? res.message : `تم تحصيل ${amt.toLocaleString()} ${payingInvoice.currency} وتحديث رصيد العميل والخزنة`);
    } else {
      showNotice('err', res.message);
    }
  };

  const handleCancelInvoice = async (inv: SalesInvoice) => {
    if (isSubmitting) return;
    if (!canDelete) {
      showNotice('err', 'لا تملك صلاحية إلغاء الفواتير');
      return;
    }
    if (ErpDataService.isCancelledOrReversedStatus(inv.status)) return;

    if (!window.confirm(`تأكيد إلغاء الفاتورة ${inv.invoiceNo} وإرجاع الكميات المباعة إلى المخزون وعكس الدفعات؟`)) {
      return;
    }

    const res = await cancelGuard.runGuarded({
      guardKey: `sales_cancel_${inv.invoiceNo}`,
      operationType: 'UPDATE',
      userId: userProfile?.username || 'admin',
      documentReference: inv.invoiceNo,
      targetCollection: 'invoices',
      payloadSummary: `إلغاء فاتورة ${inv.invoiceNo}`,
      execute: async () => {
        const actorName = userProfile?.fullName || userProfile?.username || 'المدير';
        await ErpDataService.cancelInvoiceAndReverseAtomic({
          invoice: inv,
          payments,
          warehouses,
          reason: `إلغاء معتمد من بوابة الويب بواسطة ${actorName}`,
          actorUsername: userProfile?.username || actorName,
          actorDisplayName: actorName
        });
        return { resultReference: inv.invoiceNo };
      }
    });
    if (res.ok) {
      showNotice('ok', `تم إلغاء الفاتورة ${inv.invoiceNo} ذرياً وإرجاع المخزون وعكس القيود المالية`);
    } else {
      showNotice('err', res.message);
    }
  };

  const handleDeleteInvoicePermanently = async (inv: SalesInvoice) => {
    if (isSubmitting) return;
    if (!canDelete) {
      showNotice('err', 'لا تملك صلاحية إلغاء أو أرشفة الفواتير');
      return;
    }

    const res = await deleteGuard.runGuarded({
      guardKey: `sales_perm_reverse_${inv.invoiceNo}`,
      operationType: 'UPDATE',
      userId: userProfile?.username || 'admin',
      documentReference: inv.invoiceNo,
      targetCollection: 'invoices',
      payloadSummary: `إلغاء وأرشفة الفاتورة بقيد عكسي ${inv.invoiceNo}`,
      execute: async () => {
        const actorName = userProfile?.fullName || userProfile?.username || 'المدير';
        if (!ErpDataService.isCancelledOrReversedStatus(inv.status)) {
          await ErpDataService.cancelInvoiceAndReverseAtomic({
            invoice: inv,
            payments,
            warehouses,
            reason: `إغلاق وأرشفة الفاتورة بقيد عكسي معتمد بواسطة ${actorName} (دفتر الأستاذ محصن ضد المسح المباشر)`,
            actorUsername: userProfile?.username || actorName,
            actorDisplayName: actorName
          });
        }
        return { resultReference: inv.invoiceNo };
      }
    });

    if (res.ok) {
      setDeletingInvoice(null);
      showNotice('ok', `تم إغلاق الفاتورة ${inv.invoiceNo} وعكس قيودها مالياً ومخزنياً بسجل غير قابل للتعديل`);
    } else {
      showNotice('err', res.message);
    }
  };

  const handleSaveCustomer = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || !custForm.name.trim() || custGuard.isSubmitting) return;
    const cleanName = custForm.name.trim();
    const res = await custGuard.runGuarded({
      guardKey: `sales_save_customer_${cleanName}`,
      operationType: 'SAVE',
      userId: userProfile?.username || 'sales',
      documentReference: cleanName,
      targetCollection: 'customers',
      payloadSummary: `إضافة عميل ${cleanName}`,
      execute: async () => {
        const now = Date.now();
        const cust: Customer = {
          id: now,
          name: cleanName,
          phone: custForm.phone.trim(),
          address: custForm.address.trim(),
          notes: custForm.notes.trim(),
          active: true,
          createdAt: now
        };
        const docId = `CUST-${now}`;
        await ErpDataService.upsertDocument('customers', docId, cust);
        return { resultReference: docId };
      }
    });
    if (res.ok) {
      setCustForm({ name: '', phone: '', address: '', notes: '' });
      showNotice('ok', `تم حفظ العميل "${cleanName}" في الدليل السحابي`);
    } else {
      showNotice('err', res.message);
    }
  };

  const filteredInvoices = useMemo(() => {
    return [...invoices]
      .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
      .filter((inv) => {
        const matchSearch =
          !searchTerm ||
          inv.invoiceNo?.toLowerCase().includes(searchTerm.toLowerCase()) ||
          inv.customerName?.toLowerCase().includes(searchTerm.toLowerCase());
        const matchStatus = statusFilter === 'ALL' || inv.status === statusFilter;
        return matchSearch && matchStatus;
      });
  }, [invoices, searchTerm, statusFilter]);

  const handleExportInvoicesCsv = () => {
    const headers = ['رقم الفاتورة', 'التاريخ', 'العميل', 'الصافي', 'المدفوع', 'المتبقي', 'العملة', 'الحالة'];
    const rows = filteredInvoices.map((inv) => {
      const total = Number(inv.total || 0);
      const paid = Number(inv.paidAmount || 0);
      const rem = Math.max(0, total - paid);
      return [
        inv.invoiceNo,
        new Date(inv.timestamp || Date.now()).toLocaleDateString('ar-EG'),
        inv.customerName,
        total,
        paid,
        rem,
        inv.currency,
        inv.status
      ];
    });
    ReportExporter.exportToCsv('سجل_فواتير_المبيعات', headers, rows);
  };

  const handlePrintInvoices = () => {
    const headers = ['رقم الفاتورة', 'التاريخ', 'العميل', 'الصافي', 'المدفوع', 'المتبقي', 'الحالة'];
    const rows = filteredInvoices.map((inv) => {
      const total = Number(inv.total || 0);
      const paid = Number(inv.paidAmount || 0);
      const rem = Math.max(0, total - paid);
      return [
        inv.invoiceNo,
        new Date(inv.timestamp || Date.now()).toLocaleDateString('ar-EG'),
        inv.customerName,
        `${total.toLocaleString()} ${inv.currency}`,
        paid.toLocaleString(),
        rem.toLocaleString(),
        inv.status
      ];
    });
    ReportExporter.exportToPrint(
      'سجل فواتير المبيعات — PMP PAINTS',
      `إجمالي الفواتير: ${filteredInvoices.length}`,
      headers,
      rows
    );
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
              سجل الفواتير، إنشاء فاتورة جديدة بخصم المخزون، كشف حساب العميل وتصديره، ودليل العملاء.
            </p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            <GlassButton variant="secondary" icon={<FileSpreadsheet className="w-4 h-4" />} onClick={handleExportInvoicesCsv} className="text-xs">
              تصدير الفواتير Excel
            </GlassButton>
            <GlassButton variant="secondary" icon={<Printer className="w-4 h-4" />} onClick={handlePrintInvoices} className="text-xs">
              طباعة السجل
            </GlassButton>
          </div>
        </div>

        {/* Exact 4 Android Sales Sub-Tabs */}
        <div className="flex items-center gap-2 mt-4 overflow-x-auto pb-1">
          {[
            { id: 'INVOICES' as SalesSubTab, label: `سجل الفواتير (${invoices.length})` },
            { id: 'NEW_INVOICE' as SalesSubTab, label: '+ فاتورة بيع جديدة' },
            { id: 'CUSTOMER_LEDGER' as SalesSubTab, label: 'كشف حساب العميل وتصديره' },
            { id: 'CUSTOMERS' as SalesSubTab, label: `دليل العملاء (${customers.length})` }
          ].map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setSubTab(t.id)}
              className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition-all ${
                subTab === t.id
                  ? 'bg-red-600 text-white shadow-md'
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
          className={`p-3.5 rounded-xl border text-xs flex items-center gap-2 font-bold ${
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
                    const total = Number(inv.total || 0);
                    const paid = Number(inv.paidAmount || 0);
                    const rem = Math.max(0, total - paid);
                    return (
                      <tr key={inv.id} className="hover:bg-theme-surface/40">
                        <td className="py-3 px-4 font-mono font-bold text-pmp-primary">
                          {inv.invoiceNo}
                        </td>
                        <td className="py-3 px-4 font-bold text-theme-primary">{inv.customerName}</td>
                        <td className="py-3 px-4 font-mono font-bold">
                          {total.toLocaleString()} {inv.currency}
                        </td>
                        <td className="py-3 px-4 font-mono text-emerald-400">
                          {paid.toLocaleString()}
                        </td>
                        <td className="py-3 px-4 font-mono text-amber-400">
                          {rem.toLocaleString()}
                        </td>
                        <td className="py-3 px-4">
                          <GlassBadge
                            variant={
                              inv.status === 'مدفوعة'
                                ? 'green'
                                : inv.status === 'سداد جزئي'
                                ? 'gold'
                                : 'red'
                            }
                          >
                            {inv.status}
                          </GlassBadge>
                        </td>
                        <td className="py-3 px-4 font-mono text-theme-muted">
                          {new Date(inv.timestamp || Date.now()).toLocaleDateString('ar-EG')}
                        </td>
                        <td className="py-3 px-4 flex items-center gap-1.5">
                          {rem > 0 && inv.status !== 'ملغاة' && canCreate && (
                            <button
                              type="button"
                              onClick={() => {
                                setPayingInvoice(inv);
                                setPayAmount(String(rem));
                              }}
                              className="px-2.5 py-1 rounded-lg bg-emerald-500/15 text-emerald-400 font-bold hover:bg-emerald-500/25"
                            >
                              تحصيل
                            </button>
                          )}
                          {canDelete && inv.status !== 'ملغاة' && (
                            <button
                              type="button"
                              onClick={() => handleCancelInvoice(inv)}
                              className="p-1.5 text-amber-400 hover:bg-amber-500/10 rounded-lg"
                              title="إلغاء الفاتورة وإرجاع المخزون"
                            >
                              <XCircle className="w-4 h-4" />
                            </button>
                          )}
                          {canDelete && (
                            <button
                              type="button"
                              onClick={() => setDeletingInvoice(inv)}
                              className="p-1.5 text-red-400 hover:bg-red-500/15 rounded-lg"
                              title="حذف نهائي من السيرفر"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                  {filteredInvoices.length === 0 && (
                    <tr>
                      <td colSpan={8} className="py-8 text-center text-theme-muted">
                        لا توجد فواتير مطابقة للبحث
                      </td>
                    </tr>
                  )}
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
                    {salesWarehouses.length === 0 && (
                      <option value={0}>لا يوجد مخزن مبيعات متاح</option>
                    )}
                    {salesWarehouses.map((w) => (
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
                    {productStocks.map((p) => {
                      const price = invCurrency === 'EGP' ? p.salePriceEgp : p.salePrice;
                      return (
                        <option key={p.id} value={p.id}>
                          {p.name} — الرصيد المتاح: {p.currentStock} — السعر: {price} {invCurrency}
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
              <div className="p-2.5 rounded-xl bg-red-600/10 border border-red-500/30 flex flex-col justify-center">
                <span className="text-[10px] text-theme-muted">صافي الفاتورة</span>
                <span className="text-base font-black text-red-400 font-mono">
                  {draftNetTotal.toLocaleString()} {invCurrency}
                </span>
              </div>
            </div>

            {invGuard.pendingRecord && (
              <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 flex items-center justify-between gap-2">
                <span>{invGuard.statusMessage || 'لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.'}</span>
                <GlassButton size="sm" variant="secondary" onClick={() => invGuard.verifyPending()}>
                  التحقق من حالة العملية
                </GlassButton>
              </div>
            )}

            <div className="flex justify-end">
              <GlassButton
                type="submit"
                variant="primary"
                disabled={!canCreate || draftLines.length === 0}
                isLoading={invGuard.isSubmitting}
                loadingText={OPERATION_LOADING_LABELS.APPROVE_INVOICE}
              >
                اعتماد الفاتورة وخصم المخزون
              </GlassButton>
            </div>
          </form>
        </GlassCard>
      )}

      {/* Sub-tab 3: Specific Customer Account Statement & Debts Ledger */}
      {subTab === 'CUSTOMER_LEDGER' && (
        <div className="space-y-6">
          {/* Specific Customer Account Statement Card */}
          <GlassCard className="p-6 space-y-5 border-r-4 border-r-red-500">
            <div className="flex flex-col lg:flex-row justify-between items-start lg:items-center gap-4 pb-4 border-b border-theme">
              <div className="space-y-1">
                <h3 className="text-base font-black text-theme-primary flex items-center gap-2">
                  <FileText className="w-5 h-5 text-red-500" />
                  <span>كشف حساب عميل محدد وتصدير رسمي (PDF / Excel)</span>
                </h3>
                <p className="text-xs text-theme-secondary">
                  اختر العميل لعرض جميع فواتيره المسحوبة ودفعاته المسددة والرصيد التراكمي وتصدير كشف حسابه المعتمد فوراً.
                </p>
              </div>

              <div className="flex flex-wrap items-center gap-2 w-full lg:w-auto">
                <select
                  value={ledgerCustomerId}
                  onChange={(e) => setLedgerCustomerId(Number(e.target.value))}
                  className="px-3.5 py-2 rounded-xl bg-theme-base border border-theme text-xs font-bold text-theme-primary min-w-[220px]"
                >
                  {customers.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} {c.phone ? `— ${c.phone}` : ''}
                    </option>
                  ))}
                </select>

                {selectedCustomerStatement && (
                  <>
                    <GlassButton
                      variant="primary"
                      icon={<Printer className="w-4 h-4" />}
                      onClick={() => ReportExporter.exportCustomerStatementToPrint(selectedCustomerStatement)}
                      className="text-xs"
                    >
                      طباعة / تصدير كشف حساب العميل PDF
                    </GlassButton>
                    <GlassButton
                      variant="secondary"
                      icon={<FileSpreadsheet className="w-4 h-4" />}
                      onClick={() => ReportExporter.exportCustomerStatementToCsv(selectedCustomerStatement)}
                      className="text-xs"
                    >
                      تصدير كشف العميل Excel / CSV
                    </GlassButton>
                  </>
                )}
              </div>
            </div>

            {selectedCustomerStatement ? (
              <div className="space-y-5">
                {/* Customer Info & 4 KPI Cards */}
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                  <div className="p-4 rounded-xl bg-theme-base border border-theme">
                    <span className="text-[11px] text-theme-muted block">بيانات العميل المختار</span>
                    <span className="text-sm font-black text-theme-primary block mt-1">
                      {selectedCustomerStatement.customer.name}
                    </span>
                    <span className="text-[11px] text-theme-secondary block mt-0.5 font-mono">
                      {selectedCustomerStatement.customer.phone || 'بدون هاتف'} •{' '}
                      {selectedCustomerStatement.customer.address || 'العنوان غير محدد'}
                    </span>
                  </div>

                  <div className="p-4 rounded-xl bg-theme-base border border-theme">
                    <span className="text-[11px] text-theme-muted block">إجمالي الفواتير المسحوبة (مدين +)</span>
                    <span className="text-lg font-black text-amber-400 font-mono block mt-1">
                      {selectedCustomerStatement.totalInvoiced.toLocaleString()} {selectedCustomerStatement.currency}
                    </span>
                    <span className="text-[10px] text-theme-muted">
                      عدد الفواتير: {selectedCustomerStatement.invoiceCount}
                    </span>
                  </div>

                  <div className="p-4 rounded-xl bg-theme-base border border-theme">
                    <span className="text-[11px] text-theme-muted block">إجمالي الدفعات المسددة (دائن -)</span>
                    <span className="text-lg font-black text-emerald-400 font-mono block mt-1">
                      {selectedCustomerStatement.totalPaid.toLocaleString()} {selectedCustomerStatement.currency}
                    </span>
                    <span className="text-[10px] text-theme-muted">
                      عدد الدفعات: {selectedCustomerStatement.paymentCount}
                    </span>
                  </div>

                  <div className="p-4 rounded-xl bg-red-600/10 border border-red-500/30">
                    <span className="text-[11px] text-theme-secondary block">صافي المديونية المتبقية على العميل</span>
                    <span className="text-lg font-black text-red-400 font-mono block mt-1">
                      {selectedCustomerStatement.remainingDebt.toLocaleString()} {selectedCustomerStatement.currency}
                    </span>
                    <span className="text-[10px] text-theme-muted">
                      {selectedCustomerStatement.remainingDebt > 0 ? 'مستحق السداد' : 'خالص الذمة المالية'}
                    </span>
                  </div>
                </div>

                {/* Chronological Ledger Table for Selected Customer */}
                <div className="overflow-x-auto">
                  <table className="w-full text-right text-xs min-w-[700px]">
                    <thead>
                      <tr className="border-b border-theme text-theme-muted bg-theme-base/60">
                        <th className="py-2.5 px-3">#</th>
                        <th className="py-2.5 px-3">التاريخ</th>
                        <th className="py-2.5 px-3">نوع الحركة</th>
                        <th className="py-2.5 px-3">المرجع / الفاتورة</th>
                        <th className="py-2.5 px-3">البيان والتفاصيل</th>
                        <th className="py-2.5 px-3">مدين (+)</th>
                        <th className="py-2.5 px-3">دائن (-)</th>
                        <th className="py-2.5 px-3">الرصيد التراكمي</th>
                        <th className="py-2.5 px-3">الحالة / الخزنة</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-theme">
                      {selectedCustomerStatement.entries.map((entry, idx) => (
                        <tr key={entry.id} className="hover:bg-white/[0.02]">
                          <td className="py-2.5 px-3 font-mono text-theme-muted">{idx + 1}</td>
                          <td className="py-2.5 px-3 font-mono text-theme-secondary">{entry.dateFormatted}</td>
                          <td className="py-2.5 px-3">
                            <GlassBadge variant={entry.kind === 'INVOICE' ? 'gold' : 'green'}>
                              {entry.typeLabel}
                            </GlassBadge>
                          </td>
                          <td className="py-2.5 px-3 font-mono font-bold text-theme-primary">{entry.referenceNo}</td>
                          <td className="py-2.5 px-3 text-theme-secondary">{entry.description}</td>
                          <td className="py-2.5 px-3 font-mono font-bold text-amber-400">
                            {entry.debit > 0 ? `${entry.debit.toLocaleString()} ${entry.currency}` : '—'}
                          </td>
                          <td className="py-2.5 px-3 font-mono font-bold text-emerald-400">
                            {entry.credit > 0 ? `${entry.credit.toLocaleString()} ${entry.currency}` : '—'}
                          </td>
                          <td className="py-2.5 px-3 font-mono font-black text-red-400">
                            {entry.runningBalance.toLocaleString()} {entry.currency}
                          </td>
                          <td className="py-2.5 px-3 text-theme-muted">{entry.statusOrCashbox}</td>
                        </tr>
                      ))}
                      {selectedCustomerStatement.entries.length === 0 && (
                        <tr>
                          <td colSpan={9} className="py-8 text-center text-theme-muted">
                            لا توجد حركات مالية (فواتير أو دفعات سداد) مسجلة لهذا العميل حتى الآن.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <div className="py-8 text-center text-xs text-theme-muted">
                يرجى اختيار عميل لعرض وتصدير كشف الحساب التفصيلي.
              </div>
            )}
          </GlassCard>

          {/* All Customers Summary Table with 1-Click Statement Export per Customer */}
          <GlassCard className="p-6 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h3 className="text-sm font-bold text-theme-primary flex items-center gap-2">
                <Wallet className="w-4 h-4 text-amber-400" />
                <span>ملخص أرصدة ومديونيات جميع العملاء ({customerDebts.length})</span>
              </h3>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-right text-xs min-w-[640px]">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2.5 px-3">العميل</th>
                    <th className="py-2.5 px-3">الهاتف</th>
                    <th className="py-2.5 px-3">إجمالي الفواتير</th>
                    <th className="py-2.5 px-3">إجمالي المسدد</th>
                    <th className="py-2.5 px-3">المديونية المتبقية</th>
                    <th className="py-2.5 px-3">تصدير كشف حساب العميل</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {customerDebts.map((c) => {
                    const isSelected = Number(c.id) === Number(ledgerCustomerId);
                    return (
                      <tr
                        key={c.id}
                        className={isSelected ? 'bg-red-600/10' : 'hover:bg-white/[0.02]'}
                      >
                        <td className="py-2.5 px-3 font-bold text-theme-primary">{c.name}</td>
                        <td className="py-2.5 px-3 font-mono text-theme-secondary">{c.phone || '-'}</td>
                        <td className="py-2.5 px-3 font-mono">
                          {c.totalInvoiced.toLocaleString()} {c.currency}
                        </td>
                        <td className="py-2.5 px-3 font-mono text-emerald-400">
                          {c.totalPaid.toLocaleString()} {c.currency}
                        </td>
                        <td
                          className={`py-2.5 px-3 font-mono font-bold ${
                            c.remainingDebt > 0 ? 'text-amber-400' : 'text-emerald-400'
                          }`}
                        >
                          {c.remainingDebt.toLocaleString()} {c.currency}
                        </td>
                        <td className="py-2.5 px-3">
                          <div className="flex items-center gap-1.5">
                            <button
                              type="button"
                              onClick={() => setLedgerCustomerId(Number(c.id))}
                              className="px-2.5 py-1 rounded-lg bg-theme-base border border-theme text-theme-primary hover:border-red-500 text-[11px] font-bold"
                            >
                              عرض الكشف
                            </button>
                            <button
                              type="button"
                              onClick={() => handleExportSpecificCustomerPdf(c)}
                              className="px-2.5 py-1 rounded-lg bg-red-600/15 hover:bg-red-600/25 text-red-400 border border-red-500/30 text-[11px] font-bold flex items-center gap-1"
                              title="طباعة وتصدير كشف حساب هذا العميل PDF"
                            >
                              <Printer className="w-3 h-3" />
                              <span>PDF</span>
                            </button>
                            <button
                              type="button"
                              onClick={() => handleExportSpecificCustomerCsv(c)}
                              className="px-2.5 py-1 rounded-lg bg-emerald-500/15 hover:bg-emerald-500/25 text-emerald-400 border border-emerald-500/30 text-[11px] font-bold flex items-center gap-1"
                              title="تصدير كشف حساب هذا العميل Excel / CSV"
                            >
                              <FileSpreadsheet className="w-3 h-3" />
                              <span>Excel</span>
                            </button>
                          </div>
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
              <GlassButton
                type="submit"
                variant="primary"
                className="w-full justify-center"
                disabled={!canCreate}
                isLoading={custGuard.isSubmitting}
                loadingText={OPERATION_LOADING_LABELS.SAVE}
              >
                حفظ العميل
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-6 lg:col-span-8">
            <h4 className="text-sm font-bold text-theme-primary mb-3">قائمة العملاء ({customerDebts.length})</h4>
            <div className="overflow-x-auto">
              <table className="w-full text-right text-xs">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2">الاسم</th>
                    <th className="py-2">الهاتف</th>
                    <th className="py-2">العنوان</th>
                    <th className="py-2">المديونية</th>
                    <th className="py-2">كشف الحساب</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-theme">
                  {customerDebts.map((c) => (
                    <tr key={c.id}>
                      <td className="py-2.5 font-bold">{c.name}</td>
                      <td className="py-2.5 font-mono text-theme-secondary">{c.phone || '-'}</td>
                      <td className="py-2.5 text-theme-muted">{c.address || '-'}</td>
                      <td className="py-2.5 font-mono text-amber-400">
                        {c.remainingDebt.toLocaleString()} {c.currency}
                      </td>
                      <td className="py-2.5">
                        <div className="flex items-center gap-1.5">
                          <button
                            type="button"
                            onClick={() => {
                              setLedgerCustomerId(Number(c.id));
                              setSubTab('CUSTOMER_LEDGER');
                            }}
                            className="px-2.5 py-1 rounded-lg bg-theme-base border border-theme text-theme-primary hover:border-red-500 text-[11px] font-bold"
                          >
                            فتح الكشف
                          </button>
                          <button
                            type="button"
                            onClick={() => handleExportSpecificCustomerPdf(c)}
                            className="px-2 py-1 rounded-lg bg-red-600/15 text-red-400 border border-red-500/30 text-[11px] font-bold"
                          >
                            PDF
                          </button>
                          <button
                            type="button"
                            onClick={() => handleExportSpecificCustomerCsv(c)}
                            className="px-2 py-1 rounded-lg bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 text-[11px] font-bold"
                          >
                            Excel
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
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
          {payGuard.pendingRecord && (
            <div className="p-2.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 flex items-center justify-between gap-2">
              <span>{payGuard.statusMessage || 'لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.'}</span>
              <GlassButton size="sm" variant="secondary" onClick={() => payGuard.verifyPending()}>
                التحقق من حالة العملية
              </GlassButton>
            </div>
          )}
          <div className="flex justify-end gap-2">
            <GlassButton variant="secondary" disabled={payGuard.isSubmitting} onClick={() => setPayingInvoice(null)}>
              إلغاء
            </GlassButton>
            <GlassButton
              type="submit"
              variant="primary"
              isLoading={payGuard.isSubmitting}
              loadingText={OPERATION_LOADING_LABELS.PAYMENT}
            >
              تأكيد التحصيل
            </GlassButton>
          </div>
        </form>
      </Modal>

      {/* Permanent Server Invoice Delete Confirmation Modal */}
      <Modal
        isOpen={Boolean(deletingInvoice)}
        onClose={() => !isSubmitting && setDeletingInvoice(null)}
        title={`تأكيد الحذف النهائي — ${deletingInvoice?.invoiceNo || ''}`}
        maxWidth="max-w-md"
      >
        <div className="space-y-4 text-xs">
          <p className="text-theme-primary leading-relaxed">
            هل أنت متأكد من حذف فاتورة العميل{' '}
            <strong className="text-red-400">{deletingInvoice?.customerName}</strong> (رقم{' '}
            <span className="font-mono font-bold">{deletingInvoice?.invoiceNo}</span>) نهائياً من السيرفر؟
          </p>
          <p className="text-theme-muted bg-red-500/10 border border-red-500/30 rounded-xl p-3">
            سيتم حذف الفاتورة والدفعات المرتبطة بها وتسجيل شاهد حذف في السيرفر لمنع استعادتها من أي جهاز آخر.
          </p>
          <div className="flex justify-end gap-2">
            <GlassButton
              variant="secondary"
              disabled={deleteGuard.isSubmitting}
              onClick={() => setDeletingInvoice(null)}
            >
              إلغاء
            </GlassButton>
            <GlassButton
              variant="primary"
              isLoading={deleteGuard.isSubmitting}
              loadingText={OPERATION_LOADING_LABELS.DELETE}
              onClick={() => deletingInvoice && handleDeleteInvoicePermanently(deletingInvoice)}
            >
              حذف نهائي من السيرفر
            </GlassButton>
          </div>
        </div>
      </Modal>
    </div>
  );
};
