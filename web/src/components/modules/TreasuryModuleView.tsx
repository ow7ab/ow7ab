import React, { useState, useEffect, useMemo } from 'react';
import {
  Coins,
  ArrowUpRight,
  ArrowDownLeft,
  ArrowRightLeft,
  Plus,
  Lock,
  Search,
  CheckCircle,
  AlertTriangle,
  Trash2,
  Edit3,
  FileSpreadsheet,
  RotateCcw,
  BookOpen,
  Wallet
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';
import {
  ErpDataService,
  cashboxDocId,
  Cashbox,
  TreasuryMovement,
  TreasuryTransfer,
  DailyClosing,
  SalesInvoice
} from '../../services/ErpDataService';
import { DistinctCashboxSummary, normalizeEntityKey } from '../../services/ErpCalculations';
import { GRANULAR_ACTIONS, isSystemManager } from '../../services/SecurityService';
import { CurrencyCode } from '../../types/erp';
import { ReportExporter } from '../../utils/ReportExporter';

/**
 * Exact 5 Android Treasury Sub-tabs from `TreasuryScreen.kt`:
 * 1. "حركة مالية" (MOVEMENT)
 * 2. "تحويل خزن" (TRANSFER)
 * 3. "إقفال اليومية" (CLOSING)
 * 4. "كشف الحساب" (STATEMENT)
 * 5. "إدارة الخزن" (MANAGE_BOXES)
 */
type TreasurySubTab = 'MOVEMENT' | 'TRANSFER' | 'CLOSING' | 'STATEMENT' | 'MANAGE_BOXES';

const CURRENCIES: CurrencyCode[] = ['SDG', 'EGP', 'USD'];

export const TreasuryModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { userProfile, canAccessModule, canPerformAction } = useAuth();

  const canAccessTreasury = canAccessModule('الخزن') || canAccessModule('المحاسبة');
  const canAccessTransfers = canAccessModule('التحويلات') || canAccessTreasury;
  const isManager = isSystemManager(userProfile);
  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE) || canPerformAction(GRANULAR_ACTIONS.FINANCIAL_OPS);
  const canDeleteOrZero = isManager || canPerformAction(GRANULAR_ACTIONS.DELETE_RESET);

  const [activeSubTab, setActiveSubTab] = useState<TreasurySubTab>('MOVEMENT');
  const [defaultCurrency, setDefaultCurrency] = useState<CurrencyCode>('SDG');

  const [cashboxes, setCashboxes] = useState<Cashbox[]>([]);
  const [movements, setMovements] = useState<TreasuryMovement[]>([]);
  const [transfers, setTransfers] = useState<TreasuryTransfer[]>([]);
  const [closings, setClosings] = useState<DailyClosing[]>([]);
  const [invoices, setInvoices] = useState<SalesInvoice[]>([]);
  const [loading, setLoading] = useState(true);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  // Filters for Tab 1 (Movements)
  const [txSearch, setTxSearch] = useState('');
  const [txDirFilter, setTxDirFilter] = useState<'ALL' | 'وارد' | 'منصرف'>('ALL');
  const [txCashboxFilter, setTxCashboxFilter] = useState<string>('ALL');

  // Form 1: New Movement
  const [movForm, setMovForm] = useState({
    cashbox: 'خزنة السودان',
    currency: 'SDG' as CurrencyCode,
    direction: 'وارد' as 'وارد' | 'منصرف',
    amount: '',
    reference: `REC-${Date.now().toString().slice(-6)}`,
    description: ''
  });

  // Form 2: New Transfer (matching Android TreasuryScreen.kt lines 640-855)
  const [trfForm, setTrfForm] = useState({
    sourceCashbox: 'خزنة السودان',
    destinationCashbox: 'خزنة مصر',
    sourceCurrency: 'SDG' as CurrencyCode,
    destinationCurrency: 'EGP' as CurrencyCode,
    sourceAmount: '',
    rate: '1.0',
    isInvertedRate: false,
    reference: `TRF-${Date.now().toString().slice(-6)}`,
    description: '',
    halfaInvoiceId: ''
  });

  // Form 3: Daily Closing
  const [closeForm, setCloseForm] = useState({
    cashbox: 'خزنة السودان',
    currency: 'SDG' as CurrencyCode,
    countedBalance: '',
    notes: ''
  });

  // Tab 4: Statement filters
  const [statementBox, setStatementBox] = useState('خزنة السودان');
  const [statementCur, setStatementCur] = useState<CurrencyCode>('SDG');

  // Tab 5: Manage Cashboxes modals
  const [boxModalOpen, setBoxModalOpen] = useState(false);
  const [editingBox, setEditingBox] = useState<DistinctCashboxSummary | null>(null);
  const [boxNameInput, setBoxNameInput] = useState('');
  const [boxCurrencyInput, setBoxCurrencyInput] = useState<CurrencyCode>('SDG');
  const [boxBranchInput, setBoxBranchInput] = useState('SD-001');
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    setLoading(true);
    const u1 = ErpDataService.subscribeToCollection<Cashbox>('cashboxes', (items) => {
      setCashboxes(items);
      setLoading(false);
    });
    const u2 = ErpDataService.subscribeToCollection<TreasuryMovement>('movements', (items) => {
      setMovements([...items].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)));
    });
    const u3 = ErpDataService.subscribeToCollection<TreasuryTransfer>('transfers', (items) => {
      setTransfers([...items].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)));
    });
    const u4 = ErpDataService.subscribeToCollection<DailyClosing>('daily_closings', (items) => {
      setClosings([...items].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)));
    });
    const u5 = ErpDataService.subscribeToCollection<SalesInvoice>('invoices', setInvoices);

    return () => {
      u1();
      u2();
      u3();
      u4();
      u5();
    };
  }, []);

  // Compute unique, deduplicated cashboxes with their SDG / EGP / USD balances
  const distinctCashboxes = useMemo<DistinctCashboxSummary[]>(() => {
    const computed = ErpDataService.computeDistinctCashboxesWithBalances(cashboxes, movements);
    if (computed.length > 0) return computed;
    return [
      {
        id: 1,
        name: 'خزنة السودان',
        branch: 'SD-001',
        active: true,
        primaryCurrency: 'SDG',
        primaryBalance: 0,
        balances: { SDG: 0, EGP: 0, USD: 0 }
      },
      {
        id: 2,
        name: 'خزنة مصر',
        branch: 'EG-001',
        active: true,
        primaryCurrency: 'EGP',
        primaryBalance: 0,
        balances: { SDG: 0, EGP: 0, USD: 0 }
      },
      {
        id: 3,
        name: 'مخزن عطبرة',
        branch: 'AT-002',
        active: true,
        primaryCurrency: 'SDG',
        primaryBalance: 0,
        balances: { SDG: 0, EGP: 0, USD: 0 }
      },
      {
        id: 4,
        name: 'مخزن حلفا',
        branch: 'HF-001',
        active: true,
        primaryCurrency: 'EGP',
        primaryBalance: 0,
        balances: { SDG: 0, EGP: 0, USD: 0 }
      }
    ];
  }, [cashboxes, movements]);

  // Unique cashbox names for dropdowns (never duplicated)
  const uniqueBoxNames = useMemo(
    () => distinctCashboxes.map((b) => b.name),
    [distinctCashboxes]
  );

  const getBalanceForBoxAndCurrency = (boxName: string, cur: CurrencyCode): number => {
    const norm = normalizeEntityKey(boxName);
    const match = distinctCashboxes.find((b) => normalizeEntityKey(b.name) === norm);
    return match ? match.balances[cur] || 0 : 0;
  };

  const resolveCashboxForCurrency = (cur: CurrencyCode): string => {
    const match = distinctCashboxes.find((b) => b.primaryCurrency === cur);
    if (match) return match.name;
    if (cur === 'EGP') return 'خزنة مصر';
    return distinctCashboxes[0]?.name || 'خزنة السودان';
  };

  const handleSelectDefaultCurrency = (cur: CurrencyCode) => {
    setDefaultCurrency(cur);
    const targetBox = resolveCashboxForCurrency(cur);
    setMovForm((prev) => ({ ...prev, currency: cur, cashbox: targetBox }));
    setCloseForm((prev) => ({ ...prev, currency: cur, cashbox: targetBox }));
    setStatementCur(cur);
    setStatementBox(targetBox);
  };

  const notify = (type: 'success' | 'error', text: string) => {
    setFeedback({ type, text });
    setTimeout(() => setFeedback(null), 4500);
  };

  // Halfa invoices eligible for linking in Transfers (matching TreasuryScreen.kt line 810)
  const halfaInvoices = useMemo(
    () =>
      invoices.filter(
        (inv) =>
          (inv.invoiceType || '').includes('حلفا') ||
          (inv.warehouseName || '').includes('حلفا') ||
          (inv.customerName || '').includes('حلفا')
      ),
    [invoices]
  );

  // Tab 1: Add Movement
  const handleAddMovement = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate) return;
    const amt = Number(movForm.amount);
    if (!amt || amt <= 0) {
      notify('error', 'الرجاء إدخال مبلغ صحيح أكبر من الصفر');
      return;
    }

    setIsSubmitting(true);
    try {
      const id = Date.now();
      const today = new Date().toISOString().split('T')[0];
      const ref = movForm.reference.trim() || `REC-${id.toString().slice(-6)}`;
      const desc = movForm.description.trim() || `حركة ${movForm.direction} نقدية في ${movForm.cashbox}`;

      const newMov: TreasuryMovement = {
        id,
        txDate: today,
        cashbox: movForm.cashbox.trim(),
        currency: movForm.currency,
        direction: movForm.direction,
        amount: amt,
        description: desc,
        reference: ref,
        createdBy: userProfile?.fullName || userProfile?.username || 'المدير',
        createdAt: id
      };

      await ErpDataService.saveDocument('movements', `MOV-${id}`, newMov);

      // Update or create cashbox balance snapshot without creating a duplicate cashbox
      const matchedBox = distinctCashboxes.find(
        (c) => normalizeEntityKey(c.name) === normalizeEntityKey(movForm.cashbox)
      );
      const boxId = matchedBox?.id || 1;
      const newBal =
        getBalanceForBoxAndCurrency(movForm.cashbox, movForm.currency) +
        (movForm.direction === 'وارد' ? amt : -amt);

      await ErpDataService.saveDocument('cashboxes', cashboxDocId(movForm.cashbox, boxId), {
        id: boxId,
        name: movForm.cashbox.trim(),
        balance: newBal,
        currency: matchedBox?.primaryCurrency || movForm.currency,
        branch: matchedBox?.branch || userProfile?.branch || 'SD-001',
        active: true,
        updatedAt: Date.now()
      });

      await ErpDataService.logAudit(
        userProfile?.username || 'admin',
        'ADD_TREASURY_MOVEMENT',
        `${movForm.direction} بقيمة ${amt.toLocaleString()} ${movForm.currency} في ${movForm.cashbox} (${ref})`
      );

      setMovForm((prev) => ({
        ...prev,
        amount: '',
        reference: `REC-${Date.now().toString().slice(-6)}`,
        description: ''
      }));
      notify('success', `تم تسجيل حركة ${movForm.direction} بنجاح وتحديث الرصيد السحابي`);
    } catch (err: any) {
      notify('error', err?.message || 'فشل حفظ الحركة المالية');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDeleteMovement = async (mov: TreasuryMovement) => {
    if (!canDeleteOrZero) return;
    if (!window.confirm(`حذف الحركة المالية رقم ${mov.reference} بقيمة ${mov.amount.toLocaleString()} ${mov.currency}؟`)) {
      return;
    }
    try {
      const docId = (mov as any)._docId || `MOV-${mov.id}`;
      await ErpDataService.deleteDocument('movements', docId);
      await ErpDataService.logAudit(
        userProfile?.username || 'admin',
        'DELETE_TREASURY_MOVEMENT',
        `حذف حركة ${mov.reference} (${mov.amount} ${mov.currency}) من ${mov.cashbox}`
      );
      notify('success', `تم حذف الحركة ${mov.reference} بنجاح`);
    } catch (err: any) {
      notify('error', err?.message || 'تعذر حذف الحركة');
    }
  };

  // Tab 2: Execute Transfer (creates Transfer + Outflow Movement + Inflow Movement matching Android PmpViewModel.addTransfer)
  const calculatedTransferDestAmount = useMemo(() => {
    const srcAmt = Number(trfForm.sourceAmount) || 0;
    const rate = Number(trfForm.rate) || 1;
    if (srcAmt <= 0 || rate <= 0) return 0;
    const raw = trfForm.isInvertedRate ? srcAmt / rate : srcAmt * rate;
    return Math.round(raw * 100) / 100;
  }, [trfForm.sourceAmount, trfForm.rate, trfForm.isInvertedRate]);

  const handleAddTransfer = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate) return;
    const srcAmt = Number(trfForm.sourceAmount);
    const rate = Number(trfForm.rate) || 1;
    if (!srcAmt || srcAmt <= 0 || rate <= 0) {
      notify('error', 'الرجاء إدخال مبلغ وسعر صرف صحيحين');
      return;
    }

    setIsSubmitting(true);
    try {
      const now = Date.now();
      const today = new Date().toISOString().split('T')[0];
      const ref = trfForm.reference.trim() || `TRF-${now.toString().slice(-6)}`;
      const destAmt = calculatedTransferDestAmount;
      const actor = userProfile?.fullName || userProfile?.username || 'المدير';

      const newTrf: TreasuryTransfer = {
        id: now,
        txDate: today,
        sourceCashbox: trfForm.sourceCashbox,
        destinationCashbox: trfForm.destinationCashbox,
        sourceCurrency: trfForm.sourceCurrency,
        destinationCurrency: trfForm.destinationCurrency,
        sourceAmount: srcAmt,
        rate,
        destinationAmount: destAmt,
        description: trfForm.description.trim() || `تحويل من ${trfForm.sourceCashbox} إلى ${trfForm.destinationCashbox}`,
        reference: ref,
        halfaInvoiceId: trfForm.halfaInvoiceId ? Number(trfForm.halfaInvoiceId) : null,
        createdBy: actor,
        createdAt: now
      };

      const outMov: TreasuryMovement = {
        id: now + 1,
        txDate: today,
        cashbox: trfForm.sourceCashbox,
        currency: trfForm.sourceCurrency,
        direction: 'منصرف',
        amount: srcAmt,
        description: `تحويل صادر إلى ${trfForm.destinationCashbox} (${trfForm.description || ref})`,
        reference: `${ref}-OUT`,
        createdBy: actor,
        createdAt: now + 1
      };

      const inMov: TreasuryMovement = {
        id: now + 2,
        txDate: today,
        cashbox: trfForm.destinationCashbox,
        currency: trfForm.destinationCurrency,
        direction: 'وارد',
        amount: destAmt,
        description: `تحويل وارد من ${trfForm.sourceCashbox} بسعر صرف ${rate} (${trfForm.description || ref})`,
        reference: `${ref}-IN`,
        createdBy: actor,
        createdAt: now + 2
      };

      await Promise.all([
        ErpDataService.saveDocument('transfers', `TRF-${now}`, newTrf),
        ErpDataService.saveDocument('movements', `MOV-${now + 1}`, outMov),
        ErpDataService.saveDocument('movements', `MOV-${now + 2}`, inMov)
      ]);

      await ErpDataService.logAudit(
        userProfile?.username || 'admin',
        'TREASURY_TRANSFER',
        `تحويل ${ref}: ${srcAmt} ${trfForm.sourceCurrency} (${trfForm.sourceCashbox}) -> ${destAmt} ${trfForm.destinationCurrency} (${trfForm.destinationCashbox})`
      );

      setTrfForm((prev) => ({
        ...prev,
        sourceAmount: '',
        reference: `TRF-${Date.now().toString().slice(-6)}`,
        description: ''
      }));
      notify('success', `تم تنفيذ التحويل المالي ${ref} وتسجيل حركتي الصادر والوارد`);
    } catch (err: any) {
      notify('error', err?.message || 'فشل تنفيذ التحويل');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Tab 3: Daily Closing
  const closeExpectedBookBalance = useMemo(
    () => getBalanceForBoxAndCurrency(closeForm.cashbox, closeForm.currency),
    [distinctCashboxes, closeForm.cashbox, closeForm.currency]
  );

  const handlePerformClosing = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate) return;
    const counted = Number(closeForm.countedBalance);
    if (Number.isNaN(counted) || closeForm.countedBalance.trim() === '') {
      notify('error', 'الرجاء إدخال الرصيد الفعلي بعد العد');
      return;
    }

    setIsSubmitting(true);
    try {
      const now = Date.now();
      const today = new Date().toISOString().split('T')[0];
      const diff = Math.round((counted - closeExpectedBookBalance) * 100) / 100;

      const closingEntry: DailyClosing = {
        id: now,
        closeDate: today,
        cashbox: closeForm.cashbox,
        currency: closeForm.currency,
        bookBalance: closeExpectedBookBalance,
        countedBalance: counted,
        difference: diff,
        notes: closeForm.notes.trim() || 'إقفال ومطابقة الجرد اليومي',
        preparedBy: userProfile?.fullName || userProfile?.username || 'المدير',
        createdAt: now
      };

      await ErpDataService.saveDocument('daily_closings', `CLS-${now}`, closingEntry);
      await ErpDataService.logAudit(
        userProfile?.username || 'admin',
        'DAILY_CLOSING',
        `إقفال يومية ${closeForm.cashbox} (${closeForm.currency}): دفتري ${closeExpectedBookBalance} | فعلي ${counted} | الفارق ${diff}`
      );

      setCloseForm((prev) => ({ ...prev, countedBalance: '', notes: '' }));
      notify('success', `تم حفظ إقفال اليومية لخزنة ${closeForm.cashbox} بنجاح`);
    } catch (err: any) {
      notify('error', err?.message || 'فشل تسجيل إقفال اليومية');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Tab 4: Statement filtered movements
  const statementMovements = useMemo(
    () =>
      movements.filter(
        (m) =>
          normalizeEntityKey(m.cashbox) === normalizeEntityKey(statementBox) &&
          (m.currency || '').trim().toUpperCase() === statementCur
      ),
    [movements, statementBox, statementCur]
  );

  const statementClosingBalance = useMemo(
    () => getBalanceForBoxAndCurrency(statementBox, statementCur),
    [distinctCashboxes, statementBox, statementCur]
  );

  const handleExportStatementCsv = () => {
    const headers = ['التاريخ', 'رقم المستند', 'النوع', 'المبلغ', 'العملة', 'البيان', 'المنفذ'];
    const rows = statementMovements.map((m) => [
      m.txDate || '',
      m.reference,
      m.direction,
      m.amount,
      m.currency,
      m.description,
      m.createdBy || ''
    ]);
    ReportExporter.exportToCsv(
      `pmp_treasury_${statementBox.replace(/\s+/g, '_')}_${statementCur}.csv`,
      headers,
      rows
    );
  };

  // Tab 5: Manage Cashboxes (Add, Edit, Zero Out, Delete)
  const handleSaveCashbox = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!boxNameInput.trim()) return;
    setIsSubmitting(true);
    try {
      const cleanName = boxNameInput.trim();
      const existingByName = distinctCashboxes.find(
        (b) => normalizeEntityKey(b.name) === normalizeEntityKey(cleanName)
      );
      const id = editingBox ? editingBox.id : existingByName ? existingByName.id : Date.now();
      const docId = cashboxDocId(cleanName, id);
      const payload: Cashbox = {
        id,
        name: cleanName,
        currency: boxCurrencyInput,
        balance: editingBox
          ? editingBox.primaryBalance
          : existingByName
          ? existingByName.primaryBalance
          : 0,
        branch: boxBranchInput,
        active: true,
        updatedAt: Date.now()
      };
      await ErpDataService.saveDocument('cashboxes', docId, payload);
      await ErpDataService.logAudit(
        userProfile?.username || 'admin',
        editingBox ? 'EDIT_CASHBOX' : 'ADD_CASHBOX',
        `${editingBox ? 'تعديل' : 'إضافة'} خزنة: ${cleanName} (${boxCurrencyInput})`
      );
      setBoxModalOpen(false);
      setEditingBox(null);
      setBoxNameInput('');
      notify('success', `تم حفظ الخزنة "${cleanName}" بنجاح`);
    } catch (err: any) {
      notify('error', err?.message || 'فشل حفظ الخزنة');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleZeroOutCashbox = async (box: DistinctCashboxSummary, currencyToZero: CurrencyCode) => {
    if (!canDeleteOrZero) return;
    const currentBal = box.balances[currencyToZero] || 0;
    if (currentBal === 0) {
      notify('error', `رصيد الخزنة (${box.name} - ${currencyToZero}) صفر بالفعل`);
      return;
    }
    if (!window.confirm(`تأكيد تصفير رصيد "${box.name}" (${currentBal.toLocaleString()} ${currencyToZero})؟`)) {
      return;
    }
    try {
      const now = Date.now();
      const today = new Date().toISOString().split('T')[0];
      const offsetMov: TreasuryMovement = {
        id: now,
        txDate: today,
        cashbox: box.name,
        currency: currencyToZero,
        direction: currentBal > 0 ? 'منصرف' : 'وارد',
        amount: Math.abs(currentBal),
        description: `قيد تصفير رصيد الخزنة بواسطة ${userProfile?.fullName || 'المدير'}`,
        reference: `ZERO-${now.toString().slice(-6)}`,
        createdBy: userProfile?.fullName || userProfile?.username || 'المدير',
        createdAt: now
      };
      await ErpDataService.saveDocument('movements', `MOV-${now}`, offsetMov);
      await ErpDataService.saveDocument('cashboxes', cashboxDocId(box.name, box.id), {
        id: box.id,
        name: box.name,
        currency: box.primaryCurrency,
        balance: 0,
        branch: box.branch,
        active: box.active,
        updatedAt: now
      });
      await ErpDataService.logAudit(
        userProfile?.username || 'admin',
        'ZERO_CASHBOX',
        `تصفير رصيد خزنة ${box.name} (${currencyToZero}) من ${currentBal}`
      );
      notify('success', `تم تصفير رصيد خزنة ${box.name} (${currencyToZero}) بنجاح`);
    } catch (err: any) {
      notify('error', err?.message || 'فشل تصفير الخزنة');
    }
  };

  const handleDeleteCashbox = async (box: DistinctCashboxSummary) => {
    if (!canDeleteOrZero) return;
    if (!window.confirm(`حذف الخزنة "${box.name}" نهائياً؟`)) return;
    try {
      // Delete all matching cashbox docs in Firestore that share the same normalized name
      const matchingRawDocs = cashboxes.filter(
        (raw) => normalizeEntityKey(raw.name) === normalizeEntityKey(box.name)
      );
      await Promise.all(
        matchingRawDocs.map((raw) => {
          const docId = (raw as any)._docId || cashboxDocId(raw.name, raw.id);
          return ErpDataService.deleteDocument('cashboxes', docId);
        })
      );
      if (matchingRawDocs.length === 0) {
        await ErpDataService.deleteDocument('cashboxes', cashboxDocId(box.name, box.id));
      }
      await ErpDataService.logAudit(
        userProfile?.username || 'admin',
        'DELETE_CASHBOX',
        `حذف الخزنة ${box.name}`
      );
      notify('success', `تم حذف الخزنة ${box.name}`);
    } catch (err: any) {
      notify('error', err?.message || 'تعذر حذف الخزنة');
    }
  };

  const filteredMovements = useMemo(() => {
    return movements.filter((m) => {
      const q = txSearch.trim().toLowerCase();
      const matchQ =
        !q ||
        (m.reference || '').toLowerCase().includes(q) ||
        (m.description || '').toLowerCase().includes(q) ||
        (m.cashbox || '').toLowerCase().includes(q) ||
        (m.createdBy || '').toLowerCase().includes(q);
      const matchDir = txDirFilter === 'ALL' || m.direction === txDirFilter;
      const matchBox =
        txCashboxFilter === 'ALL' ||
        normalizeEntityKey(m.cashbox) === normalizeEntityKey(txCashboxFilter);
      return matchQ && matchDir && matchBox;
    });
  }, [movements, txSearch, txDirFilter, txCashboxFilter]);

  return (
    <div className="space-y-6">
      {/* Header + Unified Default Account Currency Selector (matching TreasuryScreen.kt lines 223-282) */}
      <GlassCard className="p-5 sm:p-6">
        <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-2xl bg-pmp-gold/20 border border-pmp-gold/40 flex items-center justify-center text-pmp-gold shrink-0">
              <Coins className="w-6 h-6" />
            </div>
            <div>
              <h2 className="text-xl font-black text-theme-primary">
                {language === 'ar'
                  ? 'الخزن والحسابات والتحويلات'
                  : 'Treasury, Vaults & Transfers'}
              </h2>
              <p className="text-xs text-theme-secondary">
                {language === 'ar'
                  ? 'حركة مالية، تحويل خزن، إقفال اليومية، كشف الحساب، وإدارة الخزن'
                  : 'Movements, Transfers, Daily Closing, Statement of Account & Vaults'}
              </p>
            </div>
          </div>

          {/* Unified Default Account Currency Bar */}
          <div className="flex items-center gap-2 bg-theme-base border border-theme px-3 py-2 rounded-xl self-stretch md:self-auto justify-between">
            <span className="text-xs font-bold text-theme-secondary">عملة الحساب الموحدة:</span>
            <div className="flex items-center gap-1.5">
              {CURRENCIES.map((cur) => (
                <button
                  key={cur}
                  type="button"
                  onClick={() => handleSelectDefaultCurrency(cur)}
                  className={`px-3 py-1 rounded-lg text-xs font-bold transition-all ${
                    defaultCurrency === cur
                      ? 'bg-pmp-primary text-white shadow'
                      : 'bg-theme-surface text-theme-secondary hover:text-theme-primary border border-theme'
                  }`}
                >
                  {cur}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* 5 Sub-tabs Bar */}
        <div className="flex flex-wrap gap-2 mt-5 pt-4 border-t border-theme">
          {[
            { id: 'MOVEMENT' as TreasurySubTab, label: 'حركة مالية', visible: canAccessTreasury },
            { id: 'TRANSFER' as TreasurySubTab, label: 'تحويل خزن', visible: canAccessTransfers },
            { id: 'CLOSING' as TreasurySubTab, label: 'إقفال اليومية', visible: canAccessTreasury },
            { id: 'STATEMENT' as TreasurySubTab, label: 'كشف الحساب', visible: canAccessTreasury },
            { id: 'MANAGE_BOXES' as TreasurySubTab, label: 'إدارة الخزن', visible: canAccessTreasury || isManager }
          ]
            .filter((t) => t.visible)
            .map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => setActiveSubTab(t.id)}
                className={`px-4 py-2 rounded-xl text-xs font-bold transition-all min-h-[38px] ${
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

      {feedback && (
        <div
          className={`p-3.5 rounded-xl border text-xs font-bold flex items-center gap-2 ${
            feedback.type === 'success'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
              : 'bg-red-500/10 border-red-500/30 text-red-400'
          }`}
        >
          {feedback.type === 'success' ? <CheckCircle className="w-4 h-4 shrink-0" /> : <AlertTriangle className="w-4 h-4 shrink-0" />}
          <span>{feedback.text}</span>
        </div>
      )}

      {/* Live Cashbox Cards — Strictly ONE card per unique Cashbox (Zero Duplication) */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {distinctCashboxes.map((box) => (
          <GlassCard
            key={`box_card_${box.id}_${box.name}`}
            className="p-4 border-r-4 border-r-pmp-gold"
          >
            <div className="flex justify-between items-start gap-2">
              <span className="font-bold text-sm text-theme-primary truncate">{box.name}</span>
              <GlassBadge variant="gold">{box.primaryCurrency}</GlassBadge>
            </div>
            <div className="text-xl font-black font-mono text-theme-primary mt-2">
              {box.balances[box.primaryCurrency].toLocaleString()}{' '}
              <span className="text-xs font-normal text-theme-muted">{box.primaryCurrency}</span>
            </div>
            <div className="mt-2 pt-2 border-t border-theme/60 flex flex-wrap items-center justify-between gap-1 text-[10px] font-mono text-theme-secondary">
              <span>SDG: {box.balances.SDG.toLocaleString()}</span>
              <span>•</span>
              <span>EGP: {box.balances.EGP.toLocaleString()}</span>
              <span>•</span>
              <span>USD: {box.balances.USD.toLocaleString()}</span>
            </div>
          </GlassCard>
        ))}
      </div>

      {/* SUB-TAB 1: حركة مالية */}
      {activeSubTab === 'MOVEMENT' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          {canCreate && (
            <GlassCard className="lg:col-span-5 p-5 sm:p-6">
              <h3 className="text-sm font-bold text-pmp-cyan mb-4">تسجيل حركة وارد أو منصرف</h3>
              <form onSubmit={handleAddMovement} className="space-y-3.5 text-xs">
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-theme-secondary mb-1 font-bold">الخزنة *</label>
                    <select
                      value={movForm.cashbox}
                      onChange={(e) => setMovForm({ ...movForm, cashbox: e.target.value })}
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                    >
                      {uniqueBoxNames.map((name) => (
                        <option key={`mov_opt_${name}`} value={name}>
                          {name}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="block text-theme-secondary mb-1 font-bold">العملة *</label>
                    <select
                      value={movForm.currency}
                      onChange={(e) => {
                        const cur = e.target.value as CurrencyCode;
                        setMovForm({
                          ...movForm,
                          currency: cur,
                          cashbox: resolveCashboxForCurrency(cur)
                        });
                      }}
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                    >
                      {CURRENCIES.map((c) => (
                        <option key={c} value={c}>{c}</option>
                      ))}
                    </select>
                  </div>
                </div>

                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">نوع الحركة المالية *</label>
                  <div className="grid grid-cols-2 gap-2">
                    {(['وارد', 'منصرف'] as const).map((dir) => (
                      <button
                        key={dir}
                        type="button"
                        onClick={() => setMovForm({ ...movForm, direction: dir })}
                        className={`py-2.5 rounded-xl font-bold flex items-center justify-center gap-1.5 border transition-all ${
                          movForm.direction === dir
                            ? dir === 'وارد'
                              ? 'bg-emerald-600 text-white border-emerald-500'
                              : 'bg-red-600 text-white border-red-500'
                            : 'bg-theme-base border-theme text-theme-secondary'
                        }`}
                      >
                        {dir === 'وارد' ? <ArrowDownLeft className="w-4 h-4" /> : <ArrowUpRight className="w-4 h-4" />}
                        <span>{dir}</span>
                      </button>
                    ))}
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-theme-secondary mb-1 font-bold">المبلغ ({movForm.currency}) *</label>
                    <input
                      type="number"
                      step="any"
                      required
                      value={movForm.amount}
                      onChange={(e) => setMovForm({ ...movForm, amount: e.target.value })}
                      placeholder="0.00"
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                    />
                  </div>
                  <div>
                    <label className="block text-theme-secondary mb-1 font-bold">رقم المستند / الإذن *</label>
                    <input
                      type="text"
                      required
                      value={movForm.reference}
                      onChange={(e) => setMovForm({ ...movForm, reference: e.target.value })}
                      className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
                    />
                  </div>
                </div>

                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">البيان وملاحظات الحركة *</label>
                  <input
                    type="text"
                    required
                    value={movForm.description}
                    onChange={(e) => setMovForm({ ...movForm, description: e.target.value })}
                    placeholder="اكتب تفاصيل المعاملة..."
                    className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
                  />
                </div>

                <GlassButton type="submit" variant="primary" disabled={isSubmitting} className="w-full justify-center">
                  {isSubmitting ? 'جاري الحفظ...' : 'إضافة معاملة'}
                </GlassButton>
              </form>
            </GlassCard>
          )}

          <GlassCard className={`${canCreate ? 'lg:col-span-7' : 'lg:col-span-12'} p-5 sm:p-6 space-y-4`}>
            <div className="flex flex-wrap justify-between items-center gap-2">
              <h3 className="text-sm font-bold text-theme-primary">
                سجل الحركات المالية ({filteredMovements.length})
              </h3>
              <div className="flex flex-wrap items-center gap-2">
                <select
                  value={txDirFilter}
                  onChange={(e) => setTxDirFilter(e.target.value as any)}
                  className="px-2.5 py-1.5 rounded-lg bg-theme-base border border-theme text-xs"
                >
                  <option value="ALL">الكل (وارد + منصرف)</option>
                  <option value="وارد">وارد فقط</option>
                  <option value="منصرف">منصرف فقط</option>
                </select>
                <select
                  value={txCashboxFilter}
                  onChange={(e) => setTxCashboxFilter(e.target.value)}
                  className="px-2.5 py-1.5 rounded-lg bg-theme-base border border-theme text-xs"
                >
                  <option value="ALL">كل الخزن</option>
                  {uniqueBoxNames.map((name) => (
                    <option key={`filter_opt_${name}`} value={name}>
                      {name}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="relative">
              <Search className="w-4 h-4 text-theme-muted absolute right-3 top-2.5" />
              <input
                type="text"
                value={txSearch}
                onChange={(e) => setTxSearch(e.target.value)}
                placeholder="بحث برقم المستند أو البيان أو الخزنة..."
                className="w-full pr-9 pl-3 py-2 rounded-xl bg-theme-base border border-theme text-xs"
              />
            </div>

            <div className="space-y-2.5 max-h-[520px] overflow-y-auto pr-1">
              {loading ? (
                <div className="py-10 text-center text-xs text-theme-muted">جاري تحميل الحركات...</div>
              ) : filteredMovements.length === 0 ? (
                <div className="py-10 text-center text-xs text-theme-muted">لا توجد حركات مالية مطابقة</div>
              ) : (
                filteredMovements.slice(0, 60).map((m) => (
                  <div
                    key={m.id}
                    className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-3 text-xs"
                  >
                    <div className="space-y-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <GlassBadge variant={m.direction === 'وارد' ? 'green' : 'red'}>{m.direction}</GlassBadge>
                        <span className="font-bold text-theme-primary truncate">{m.description}</span>
                      </div>
                      <div className="text-[11px] text-theme-muted">
                        {m.cashbox} • مستند: <span className="font-mono">{m.reference}</span> • {m.txDate} • بواسطة:{' '}
                        <b>{m.createdBy || 'المدير'}</b>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 shrink-0">
                      <span
                        className={`font-mono font-black text-sm ${
                          m.direction === 'وارد' ? 'text-emerald-400' : 'text-red-400'
                        }`}
                      >
                        {m.direction === 'وارد' ? '+' : '-'}
                        {Number(m.amount).toLocaleString()} {m.currency}
                      </span>
                      {canDeleteOrZero && (
                        <button
                          type="button"
                          onClick={() => handleDeleteMovement(m)}
                          className="p-1.5 rounded-lg bg-red-500/10 text-red-400 hover:bg-red-500/20"
                          title="حذف الحركة"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </div>
                ))
              )}
            </div>
          </GlassCard>
        </div>
      )}

      {/* SUB-TAB 2: تحويل خزن */}
      {activeSubTab === 'TRANSFER' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          <GlassCard className="lg:col-span-5 p-5 sm:p-6">
            <h3 className="text-sm font-bold text-pmp-gold mb-4 flex items-center gap-2">
              <ArrowRightLeft className="w-4 h-4" />
              <span>تحويل سيولة بين الخزن وتغيير العملة</span>
            </h3>
            <form onSubmit={handleAddTransfer} className="space-y-3.5 text-xs">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">من خزنة (المصدر)</label>
                  <select
                    value={trfForm.sourceCashbox}
                    onChange={(e) => setTrfForm({ ...trfForm, sourceCashbox: e.target.value })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme"
                  >
                    {uniqueBoxNames.map((name) => (
                      <option key={`src_${name}`} value={name}>{name}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">عملة المصدر</label>
                  <select
                    value={trfForm.sourceCurrency}
                    onChange={(e) => setTrfForm({ ...trfForm, sourceCurrency: e.target.value as CurrencyCode })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                  >
                    {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">إلى خزنة (الوجهة)</label>
                  <select
                    value={trfForm.destinationCashbox}
                    onChange={(e) => setTrfForm({ ...trfForm, destinationCashbox: e.target.value })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme"
                  >
                    {uniqueBoxNames.map((name) => (
                      <option key={`dst_${name}`} value={name}>{name}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">عملة الوجهة</label>
                  <select
                    value={trfForm.destinationCurrency}
                    onChange={(e) => setTrfForm({ ...trfForm, destinationCurrency: e.target.value as CurrencyCode })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                  >
                    {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">المبلغ المحول ({trfForm.sourceCurrency}) *</label>
                  <input
                    type="number"
                    step="any"
                    required
                    value={trfForm.sourceAmount}
                    onChange={(e) => setTrfForm({ ...trfForm, sourceAmount: e.target.value })}
                    placeholder="0.00"
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                  />
                </div>
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">سعر الصرف *</label>
                  <input
                    type="number"
                    step="any"
                    required
                    value={trfForm.rate}
                    onChange={(e) => setTrfForm({ ...trfForm, rate: e.target.value })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                  />
                </div>
              </div>

              <label className="flex items-center gap-2 cursor-pointer text-theme-secondary">
                <input
                  type="checkbox"
                  checked={trfForm.isInvertedRate}
                  onChange={(e) => setTrfForm({ ...trfForm, isInvertedRate: e.target.checked })}
                />
                <span>معادلة سعر صرف معكوسة (القسمة ÷ بدلاً من الضرب ×)</span>
              </label>

              <div className="p-3 rounded-xl bg-pmp-cyan/10 border border-pmp-cyan/30 flex justify-between items-center">
                <span className="text-theme-secondary">المبلغ الناتج في {trfForm.destinationCashbox}:</span>
                <span className="font-mono font-black text-sm text-pmp-cyan">
                  {calculatedTransferDestAmount.toLocaleString()} {trfForm.destinationCurrency}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">رقم المرجع *</label>
                  <input
                    type="text"
                    required
                    value={trfForm.reference}
                    onChange={(e) => setTrfForm({ ...trfForm, reference: e.target.value })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                  />
                </div>
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">سبب التحويل</label>
                  <input
                    type="text"
                    value={trfForm.description}
                    onChange={(e) => setTrfForm({ ...trfForm, description: e.target.value })}
                    placeholder="تغذية خزنة..."
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme"
                  />
                </div>
              </div>

              {halfaInvoices.length > 0 && (
                <div>
                  <label className="block text-pmp-gold mb-1 font-bold">ربط بسداد فاتورة حلفا (اختياري)</label>
                  <select
                    value={trfForm.halfaInvoiceId}
                    onChange={(e) => setTrfForm({ ...trfForm, halfaInvoiceId: e.target.value })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme"
                  >
                    <option value="">بدون ربط</option>
                    {halfaInvoices.map((inv) => (
                      <option key={inv.id} value={inv.id}>
                        {inv.invoiceNo || inv.invoiceNumber} ({Number(inv.total || 0).toLocaleString()} {inv.currency})
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <GlassButton type="submit" variant="primary" disabled={isSubmitting} className="w-full justify-center">
                تنفيذ التحويل
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="lg:col-span-7 p-5 sm:p-6 space-y-3">
            <h3 className="text-sm font-bold text-theme-primary">سجل التحويلات المنفذة ({transfers.length})</h3>
            <div className="space-y-2.5 max-h-[500px] overflow-y-auto">
              {transfers.length === 0 ? (
                <div className="py-10 text-center text-xs text-theme-muted">لا توجد تحويلات مسجلة بعد</div>
              ) : (
                transfers.map((t) => (
                  <div key={t.id} className="p-3.5 rounded-xl bg-theme-base border border-theme text-xs space-y-1">
                    <div className="flex justify-between items-center">
                      <span className="font-mono font-bold text-pmp-gold">تحويل {t.reference}</span>
                      <span className="text-theme-muted font-mono">{t.txDate}</span>
                    </div>
                    <div className="font-semibold text-theme-primary">
                      من: {t.sourceCashbox} ({Number(t.sourceAmount).toLocaleString()} {t.sourceCurrency}) ➔ إلى:{' '}
                      {t.destinationCashbox} ({Number(t.destinationAmount).toLocaleString()} {t.destinationCurrency})
                    </div>
                    <div className="text-[11px] text-theme-muted">
                      سعر الصرف: {t.rate} • المنفذ: {t.createdBy || 'المدير'} {t.description ? `• ${t.description}` : ''}
                    </div>
                  </div>
                ))
              )}
            </div>
          </GlassCard>
        </div>
      )}

      {/* SUB-TAB 3: إقفال اليومية */}
      {activeSubTab === 'CLOSING' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          <GlassCard className="lg:col-span-5 p-5 sm:p-6">
            <h3 className="text-sm font-bold text-pmp-primary mb-4 flex items-center gap-2">
              <Lock className="w-4 h-4" />
              <span>إقفال اليومية ومطابقة الجرد</span>
            </h3>
            <form onSubmit={handlePerformClosing} className="space-y-3.5 text-xs">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">الخزنة المراد إقفالها</label>
                  <select
                    value={closeForm.cashbox}
                    onChange={(e) => setCloseForm({ ...closeForm, cashbox: e.target.value })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme"
                  >
                    {uniqueBoxNames.map((name) => (
                      <option key={`cls_${name}`} value={name}>{name}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-theme-secondary mb-1 font-bold">العملة</label>
                  <select
                    value={closeForm.currency}
                    onChange={(e) => setCloseForm({ ...closeForm, currency: e.target.value as CurrencyCode })}
                    className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme font-mono"
                  >
                    {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
                  </select>
                </div>
              </div>

              <div className="p-3.5 rounded-xl bg-theme-base border border-theme space-y-1.5">
                <div className="flex justify-between">
                  <span className="text-theme-muted">الرصيد الدفتري المتوقع:</span>
                  <span className="font-mono font-bold text-theme-primary">
                    {closeExpectedBookBalance.toLocaleString()} {closeForm.currency}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-theme-muted">الفارق المحسوب:</span>
                  <span
                    className={`font-mono font-bold ${
                      (Number(closeForm.countedBalance) || 0) - closeExpectedBookBalance === 0
                        ? 'text-emerald-400'
                        : 'text-amber-400'
                    }`}
                  >
                    {((Number(closeForm.countedBalance) || 0) - closeExpectedBookBalance).toLocaleString()}{' '}
                    {closeForm.currency}
                  </span>
                </div>
              </div>

              <div>
                <label className="block text-theme-secondary mb-1 font-bold">الرصيد الفعلي بعد العد النقدي *</label>
                <input
                  type="number"
                  step="any"
                  required
                  value={closeForm.countedBalance}
                  onChange={(e) => setCloseForm({ ...closeForm, countedBalance: e.target.value })}
                  placeholder="0.00"
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme font-mono"
                />
              </div>

              <div>
                <label className="block text-theme-secondary mb-1 font-bold">ملاحظات وتوقيع الجرد</label>
                <input
                  type="text"
                  value={closeForm.notes}
                  onChange={(e) => setCloseForm({ ...closeForm, notes: e.target.value })}
                  placeholder="تم الجرد بواسطة..."
                  className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme"
                />
              </div>

              <GlassButton type="submit" variant="primary" disabled={isSubmitting} className="w-full justify-center">
                إقفال اليومية
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="lg:col-span-7 p-5 sm:p-6 space-y-3">
            <h3 className="text-sm font-bold text-theme-primary">سجل الإقفالات اليومية السابقة ({closings.length})</h3>
            <div className="space-y-2.5 max-h-[500px] overflow-y-auto">
              {closings.length === 0 ? (
                <div className="py-10 text-center text-xs text-theme-muted">لا توجد إقفالات مسجلة بعد</div>
              ) : (
                closings.map((c) => (
                  <div
                    key={c.id}
                    className="p-3.5 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-3 text-xs"
                  >
                    <div className="space-y-1">
                      <div className="font-bold text-theme-primary">
                        {c.cashbox} • <span className="font-mono text-theme-muted">{c.closeDate}</span>
                      </div>
                      <div className="text-theme-secondary font-mono">
                        دفتري: {Number(c.bookBalance).toLocaleString()} | فعلي: {Number(c.countedBalance).toLocaleString()}{' '}
                        {c.currency}
                      </div>
                      <div className="text-[11px] text-theme-muted">
                        بواسطة: {c.preparedBy || 'المدير'} {c.notes ? `• ${c.notes}` : ''}
                      </div>
                    </div>
                    <GlassBadge variant={Number(c.difference) === 0 ? 'green' : 'red'}>
                      {Number(c.difference) === 0 ? 'مطابق' : `${Number(c.difference).toLocaleString()} ${c.currency}`}
                    </GlassBadge>
                  </div>
                ))
              )}
            </div>
          </GlassCard>
        </div>
      )}

      {/* SUB-TAB 4: كشف الحساب */}
      {activeSubTab === 'STATEMENT' && (
        <GlassCard className="p-5 sm:p-6 space-y-4">
          <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
            <div className="flex items-center gap-2">
              <BookOpen className="w-5 h-5 text-pmp-cyan" />
              <h3 className="text-base font-bold text-theme-primary">كشف حساب مفصل للخزنة</h3>
              <GlassBadge variant="cyan">{statementMovements.length} حركة</GlassBadge>
            </div>
            <GlassButton
              variant="secondary"
              onClick={handleExportStatementCsv}
              icon={<FileSpreadsheet className="w-4 h-4 text-emerald-400" />}
              className="text-xs"
            >
              تصدير كشف الحساب إكسيل (Excel)
            </GlassButton>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
            <div>
              <label className="block text-theme-secondary mb-1 font-bold">الخزنة</label>
              <select
                value={statementBox}
                onChange={(e) => setStatementBox(e.target.value)}
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme"
              >
                {uniqueBoxNames.map((name) => (
                  <option key={`st_${name}`} value={name}>{name}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-theme-secondary mb-1 font-bold">العملة</label>
              <select
                value={statementCur}
                onChange={(e) => setStatementCur(e.target.value as CurrencyCode)}
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme font-mono"
              >
                {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            <div className="p-3 rounded-xl bg-theme-base border border-theme flex flex-col justify-center">
              <span className="text-theme-muted text-[11px]">إجمالي الرصيد الختامي:</span>
              <span className="font-mono font-black text-base text-emerald-400">
                {statementClosingBalance.toLocaleString()} {statementCur}
              </span>
            </div>
          </div>

          <div className="space-y-2 max-h-[500px] overflow-y-auto">
            {statementMovements.length === 0 ? (
              <div className="py-10 text-center text-xs text-theme-muted">
                لا توجد معاملات مسجلة لخزنة ({statementBox}) بالعملة ({statementCur}) حتى الآن
              </div>
            ) : (
              statementMovements.map((m) => (
                <div
                  key={m.id}
                  className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-3 text-xs"
                >
                  <div>
                    <div className="flex items-center gap-2">
                      <GlassBadge variant={m.direction === 'وارد' ? 'green' : 'red'}>{m.direction}</GlassBadge>
                      <span className="font-bold text-theme-primary">{m.description}</span>
                    </div>
                    <div className="text-[11px] text-theme-muted mt-1">
                      {m.txDate} • مستند: {m.reference} • موظف الحساب: {m.createdBy || 'المدير'}
                    </div>
                  </div>
                  <span
                    className={`font-mono font-bold text-sm ${
                      m.direction === 'وارد' ? 'text-emerald-400' : 'text-red-400'
                    }`}
                  >
                    {m.direction === 'وارد' ? '+' : '-'}
                    {Number(m.amount).toLocaleString()} {m.currency}
                  </span>
                </div>
              ))
            )}
          </div>
        </GlassCard>
      )}

      {/* SUB-TAB 5: إدارة الخزن (Matching Android TreasuryScreen.kt lines 1318-1418) */}
      {activeSubTab === 'MANAGE_BOXES' && (
        <GlassCard className="p-5 sm:p-6 space-y-4">
          <div className="flex justify-between items-center">
            <h3 className="text-base font-bold text-theme-primary flex items-center gap-2">
              <Wallet className="w-5 h-5 text-pmp-gold" />
              <span>الخزن النقدية المسجلة ({distinctCashboxes.length})</span>
            </h3>
            {isManager && (
              <GlassButton
                variant="primary"
                onClick={() => {
                  setEditingBox(null);
                  setBoxNameInput('');
                  setBoxCurrencyInput('SDG');
                  setBoxBranchInput('SD-001');
                  setBoxModalOpen(true);
                }}
                icon={<Plus className="w-4 h-4" />}
                className="text-xs"
              >
                إضافة خزنة جديدة
              </GlassButton>
            )}
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {distinctCashboxes.map((b) => (
              <div
                key={`manage_box_${b.id}_${b.name}`}
                className="p-4 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-3"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-bold text-sm text-theme-primary">{b.name}</span>
                    <GlassBadge variant="gold">خزنة معتمدة</GlassBadge>
                  </div>
                  <div className="text-xs text-theme-secondary font-mono mt-1.5">
                    الأرصدة: SDG {b.balances.SDG.toLocaleString()} | EGP {b.balances.EGP.toLocaleString()} | USD{' '}
                    {b.balances.USD.toLocaleString()}
                  </div>
                </div>

                {canDeleteOrZero && (
                  <div className="flex items-center gap-1.5 shrink-0">
                    <button
                      type="button"
                      onClick={() => handleZeroOutCashbox(b, b.primaryCurrency)}
                      className="p-2 rounded-lg bg-amber-500/10 text-amber-400 hover:bg-amber-500/20"
                      title="تصفير رصيد الخزنة"
                    >
                      <RotateCcw className="w-4 h-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setEditingBox(b);
                        setBoxNameInput(b.name);
                        setBoxCurrencyInput(b.primaryCurrency);
                        setBoxBranchInput(b.branch || 'SD-001');
                        setBoxModalOpen(true);
                      }}
                      className="p-2 rounded-lg bg-pmp-cyan/10 text-pmp-cyan hover:bg-pmp-cyan/20"
                      title="تعديل الخزنة"
                    >
                      <Edit3 className="w-4 h-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDeleteCashbox(b)}
                      className="p-2 rounded-lg bg-red-500/10 text-red-400 hover:bg-red-500/20"
                      title="حذف الخزنة"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
        </GlassCard>
      )}

      {/* Modal: Add / Edit Cashbox */}
      <Modal
        isOpen={boxModalOpen}
        onClose={() => setBoxModalOpen(false)}
        title={editingBox ? 'تعديل بيانات الخزنة' : 'إضافة خزنة نقدية جديدة'}
      >
        <form onSubmit={handleSaveCashbox} className="space-y-4 text-xs">
          <div>
            <label className="block text-theme-secondary mb-1 font-bold">اسم الخزنة *</label>
            <input
              type="text"
              required
              value={boxNameInput}
              onChange={(e) => setBoxNameInput(e.target.value)}
              placeholder="مثال: خزنة بورتسودان"
              className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme text-theme-primary"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-theme-secondary mb-1 font-bold">العملة الافتراضية</label>
              <select
                value={boxCurrencyInput}
                onChange={(e) => setBoxCurrencyInput(e.target.value as CurrencyCode)}
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme font-mono"
              >
                {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-theme-secondary mb-1 font-bold">الفرع</label>
              <input
                type="text"
                value={boxBranchInput}
                onChange={(e) => setBoxBranchInput(e.target.value)}
                className="w-full px-3 py-2.5 rounded-xl bg-theme-base border border-theme font-mono"
              />
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-3 border-t border-theme">
            <GlassButton type="button" variant="secondary" onClick={() => setBoxModalOpen(false)}>
              إلغاء
            </GlassButton>
            <GlassButton type="submit" variant="primary" disabled={isSubmitting}>
              حفظ الخزنة
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
