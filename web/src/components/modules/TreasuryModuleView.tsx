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
  Printer,
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
  SalesInvoice,
  buildTreasuryMovementJournalPayload,
  buildTreasuryTransferJournalPayloads
} from '../../services/ErpDataService';
import { DistinctCashboxSummary, isMovementInflow, normalizeEntityKey } from '../../services/ErpCalculations';
import { GRANULAR_ACTIONS, isSystemManager } from '../../services/SecurityService';
import { CurrencyCode } from '../../types/erp';
import { ReportExporter } from '../../services/ReportExporter';
import { useTransactionGuard } from '../../hooks/useTransactionGuard';
import { OPERATION_LOADING_LABELS } from '../../services/IdempotencyService';

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
  const canDeleteOrZero = isManager || canPerformAction(GRANULAR_ACTIONS.DELETE);

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

  const movGuard = useTransactionGuard(
    'treasury_movement',
    movForm.direction === 'وارد' ? 'RECEIPT_VOUCHER' : 'PAYMENT_VOUCHER'
  );
  const trfGuard = useTransactionGuard('treasury_transfer', 'STOCK_TRANSFER');
  const closeGuard = useTransactionGuard('treasury_daily_closing', 'SAVE');
  const boxGuard = useTransactionGuard('treasury_save_cashbox', 'SAVE');
  const actionGuard = useTransactionGuard('treasury_admin_op', 'DELETE');
  const isSubmitting =
    movGuard.isSubmitting ||
    trfGuard.isSubmitting ||
    closeGuard.isSubmitting ||
    boxGuard.isSubmitting ||
    actionGuard.isSubmitting;

  useEffect(() => {
    setLoading(true);
    const u1 = ErpDataService.subscribeToCollection<Cashbox>('cashboxes', (items) => {
      setCashboxes(items);
      setLoading(false);
    });
    const u2 = ErpDataService.subscribeToCollection<TreasuryMovement>('movements', (items) => {
      setMovements([...items].sort((a, b) => ErpDataService.compareRecordsChronologically(a, b, 'desc')));
    });
    const u3 = ErpDataService.subscribeToCollection<TreasuryTransfer>('transfers', (items) => {
      setTransfers([...items].sort((a, b) => ErpDataService.compareRecordsChronologically(a, b, 'desc')));
    });
    const u4 = ErpDataService.subscribeToCollection<DailyClosing>('daily_closings', (items) => {
      setClosings([...items].sort((a, b) => ErpDataService.compareRecordsChronologically(a, b, 'desc')));
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
    if (!canCreate || movGuard.isSubmitting) return;
    const amt = Number(movForm.amount);
    if (!amt || amt <= 0) {
      notify('error', 'الرجاء إدخال مبلغ صحيح أكبر من الصفر');
      return;
    }

    const opType = movForm.direction === 'وارد' ? 'RECEIPT_VOUCHER' : 'PAYMENT_VOUCHER';
    const refPreview = movForm.reference.trim() || `REC-${Date.now().toString().slice(-6)}`;

    const res = await movGuard.runGuarded({
      guardKey: 'treasury_movement',
      operationType: opType,
      userId: userProfile?.username || 'WEB_USER',
      documentReference: refPreview,
      targetCollection: 'movements',
      payloadSummary: `${movForm.direction} ${amt} ${movForm.currency} — ${movForm.cashbox}`,
      execute: async ({ idempotencyKey }) => {
        const id = Date.now();
        const today = new Date().toISOString().split('T')[0];
        const ref = movForm.reference.trim() || `REC-${id.toString().slice(-6)}`;
        const desc = movForm.description.trim() || `حركة ${movForm.direction} نقدية في ${movForm.cashbox}`;
        const movDocId = `MOV-${idempotencyKey}`;

        const newMov: TreasuryMovement = {
          id,
          txDate: today,
          cashbox: movForm.cashbox.trim(),
          currency: movForm.currency,
          direction: movForm.direction,
          amount: amt,
          description: desc,
          reference: ref,
          cloudDocId: movDocId,
          syncUuid: movDocId,
          createdBy: userProfile?.fullName || userProfile?.username || 'المدير',
          createdAt: id
        };

        const matchedBox = distinctCashboxes.find(
          (c) => normalizeEntityKey(c.name) === normalizeEntityKey(movForm.cashbox)
        );
        const boxId = matchedBox?.id || 1;
        const boxPrimaryCurrency = matchedBox?.primaryCurrency || movForm.currency;
        const movJournal = buildTreasuryMovementJournalPayload({
          id,
          txDate: today,
          cashbox: movForm.cashbox.trim(),
          currency: movForm.currency,
          direction: movForm.direction,
          amount: amt,
          description: desc,
          reference: ref,
          createdBy: userProfile?.fullName || userProfile?.username || 'المدير'
        });

        const ops: any[] = [
          {
            kind: 'merge',
            collectionName: 'movements',
            docId: movDocId,
            payload: newMov,
            checkDuplicateFinancial: true
          }
        ];
        if (!matchedBox) {
          ops.push({
            kind: 'merge',
            collectionName: 'cashboxes',
            docId: cashboxDocId(movForm.cashbox, boxId),
            payload: {
              id: boxId,
              name: movForm.cashbox.trim(),
              balance: 0,
              currency: boxPrimaryCurrency,
              branch: userProfile?.branch || 'SD-001',
              active: true,
              updatedAt: Date.now()
            },
            checkDuplicateFinancial: false
          });
        }

        await ErpDataService.commitAtomicTransactionBundle({
          actorUsername: userProfile?.username,
          operations: ops,
          journals: [movJournal],
          auditLog: {
            username: userProfile?.username || 'admin',
            action: 'ADD_TREASURY_MOVEMENT',
            detail: `${movForm.direction} ذري بقيمة ${amt.toLocaleString()} ${movForm.currency} في ${movForm.cashbox} (${ref})`
          }
        });
        return { resultReference: movDocId };
      }
    });

    if (res.ok) {
      setMovForm((prev) => ({
        ...prev,
        amount: '',
        reference: `REC-${Date.now().toString().slice(-6)}`,
        description: ''
      }));
      notify('success', res.replayed ? res.message : `تم تسجيل حركة ${movForm.direction} بنجاح وتحديث الرصيد السحابي`);
    } else {
      notify('error', res.message);
    }
  };

  const handleDeleteMovement = async (mov: TreasuryMovement) => {
    if (!canDeleteOrZero || actionGuard.isSubmitting) return;
    if (ErpDataService.isCancelledOrReversedStatus((mov as any).status)) {
      notify('error', 'تم عكس هذه الحركة مسبقاً في دفتر الأستاذ ولا يمكن عكسها مرتين.');
      return;
    }
    if (
      !window.confirm(
        `إنشاء قيد عكسي موثق لإلغاء أثر الحركة رقم ${mov.reference} بقيمة ${mov.amount.toLocaleString()} ${mov.currency}؟\n(السجل الأصلي يظل محفوظاً في دفتر الأستاذ غير القابل للحذف)`
      )
    ) {
      return;
    }
    const docId = (mov as any)._docId || `MOV-${mov.id}`;
    const actor = userProfile?.username || 'admin';
    const res = await actionGuard.runGuarded({
      guardKey: `treasury_reverse_mov_${docId}`,
      operationType: 'UPDATE',
      userId: actor,
      documentReference: docId,
      targetCollection: 'movements',
      payloadSummary: `قيد عكسي لحركة ${mov.reference}`,
      execute: async () => {
        const revJournalRef = await ErpDataService.reverseTreasuryMovementAtomic({
          movement: mov,
          reason: `عكس حركة مالية معتمد من الخزينة (${mov.reference || docId})`,
          actorUsername: actor,
          actorDisplayName: userProfile?.fullName || actor
        });
        return { resultReference: revJournalRef };
      }
    });
    if (res.ok) {
      notify('success', `تم تنفيذ القيد العكسي الموثق للحركة ${mov.reference} وإعادة احتساب الرصيد بدقة`);
    } else {
      notify('error', res.message);
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
    if (!canCreate || trfGuard.isSubmitting) return;
    const srcAmt = Number(trfForm.sourceAmount);
    const rate = Number(trfForm.rate) || 1;
    if (!srcAmt || srcAmt <= 0 || rate <= 0) {
      notify('error', 'الرجاء إدخال مبلغ وسعر صرف صحيحين');
      return;
    }
    if (
      trfForm.sourceCashbox.trim().toLowerCase() === trfForm.destinationCashbox.trim().toLowerCase() &&
      trfForm.sourceCurrency === trfForm.destinationCurrency
    ) {
      notify('error', 'لا يمكن التحويل من نفس الخزنة وإليها بنفس العملة');
      return;
    }

    const refPreview = trfForm.reference.trim() || `TRF-${Date.now().toString().slice(-6)}`;
    const destAmt = calculatedTransferDestAmount;
    const actor = userProfile?.fullName || userProfile?.username || 'المدير';

    const res = await trfGuard.runGuarded({
      guardKey: 'treasury_transfer',
      operationType: 'STOCK_TRANSFER',
      userId: userProfile?.username || actor,
      documentReference: refPreview,
      targetCollection: 'transfers',
      payloadSummary: `تحويل ${srcAmt} ${trfForm.sourceCurrency} من ${trfForm.sourceCashbox} إلى ${trfForm.destinationCashbox}`,
      execute: async ({ idempotencyKey }) => {
        const now = Date.now();
        const today = new Date().toISOString().split('T')[0];
        const trfDocId = `TRF-${idempotencyKey}`;

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
          reference: refPreview,
          halfaInvoiceId: trfForm.halfaInvoiceId ? Number(trfForm.halfaInvoiceId) : null,
          createdBy: actor,
          createdAt: now
        };

        const outDocId = `MOV-${idempotencyKey}-OUT`;
        const inDocId = `MOV-${idempotencyKey}-IN`;

        const outMov: TreasuryMovement = {
          id: now + 1,
          txDate: today,
          cashbox: trfForm.sourceCashbox,
          currency: trfForm.sourceCurrency,
          direction: 'منصرف',
          amount: srcAmt,
          description: `تحويل صادر إلى ${trfForm.destinationCashbox} (${trfForm.description || refPreview})`,
          reference: `${refPreview}-OUT`,
          cloudDocId: outDocId,
          syncUuid: outDocId,
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
          description: `تحويل وارد من ${trfForm.sourceCashbox} بسعر صرف ${rate} (${trfForm.description || refPreview})`,
          reference: `${refPreview}-IN`,
          cloudDocId: inDocId,
          syncUuid: inDocId,
          createdBy: actor,
          createdAt: now + 2
        };

        const trfJournals = buildTreasuryTransferJournalPayloads({
          id: now,
          txDate: today,
          reference: refPreview,
          sourceCashbox: trfForm.sourceCashbox,
          destinationCashbox: trfForm.destinationCashbox,
          sourceCurrency: trfForm.sourceCurrency,
          destinationCurrency: trfForm.destinationCurrency,
          sourceAmount: srcAmt,
          destinationAmount: destAmt,
          createdBy: actor
        });

        await ErpDataService.commitAtomicTransactionBundle({
          actorUsername: userProfile?.username,
          operations: [
            {
              kind: 'merge',
              collectionName: 'transfers',
              docId: trfDocId,
              payload: newTrf,
              checkDuplicateFinancial: true
            },
            {
              kind: 'merge',
              collectionName: 'movements',
              docId: outDocId,
              payload: outMov,
              checkDuplicateFinancial: true
            },
            {
              kind: 'merge',
              collectionName: 'movements',
              docId: inDocId,
              payload: inMov,
              checkDuplicateFinancial: true
            }
          ],
          journals: trfJournals,
          auditLog: {
            username: userProfile?.username || 'admin',
            action: 'TREASURY_TRANSFER',
            detail: `تحويل ذري ${refPreview}: ${srcAmt} ${trfForm.sourceCurrency} (${trfForm.sourceCashbox}) -> ${destAmt} ${trfForm.destinationCurrency} (${trfForm.destinationCashbox})`
          }
        });
        return { resultReference: trfDocId };
      }
    });

    if (res.ok) {
      setTrfForm((prev) => ({
        ...prev,
        sourceAmount: '',
        reference: `TRF-${Date.now().toString().slice(-6)}`,
        description: ''
      }));
      notify('success', res.replayed ? res.message : `تم تنفيذ التحويل المالي ${refPreview} وتسجيل حركتي الصادر والوارد`);
    } else {
      notify('error', res.message);
    }
  };

  // Tab 3: Daily Closing
  const closeExpectedBookBalance = useMemo(
    () => getBalanceForBoxAndCurrency(closeForm.cashbox, closeForm.currency),
    [distinctCashboxes, closeForm.cashbox, closeForm.currency]
  );

  const handlePerformClosing = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || closeGuard.isSubmitting) return;
    const counted = Number(closeForm.countedBalance);
    if (Number.isNaN(counted) || closeForm.countedBalance.trim() === '') {
      notify('error', 'الرجاء إدخال الرصيد الفعلي بعد العد');
      return;
    }

    const res = await closeGuard.runGuarded({
      guardKey: `treasury_daily_closing_${closeForm.cashbox}_${closeForm.currency}`,
      operationType: 'SAVE',
      userId: userProfile?.username || 'admin',
      documentReference: `CLS-${closeForm.cashbox}-${closeForm.currency}`,
      targetCollection: 'daily_closings',
      payloadSummary: `إقفال يومية ${closeForm.cashbox} (${closeForm.currency})`,
      execute: async ({ idempotencyKey }) => {
        const now = Date.now();
        const today = new Date().toISOString().split('T')[0];
        const diff = Math.round((counted - closeExpectedBookBalance) * 100) / 100;
        const clsDocId = `CLS-${idempotencyKey}`;

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
          status: 'closed',
          closedAt: now,
          createdAt: now
        };

        await ErpDataService.saveDocument('daily_closings', clsDocId, closingEntry);
        await ErpDataService.logAudit(
          userProfile?.username || 'admin',
          'DAILY_CLOSING',
          `إقفال يومية ${closeForm.cashbox} (${closeForm.currency}): دفتري ${closeExpectedBookBalance} | فعلي ${counted} | الفارق ${diff}`
        );
        return { resultReference: clsDocId };
      }
    });

    if (res.ok) {
      setCloseForm((prev) => ({ ...prev, countedBalance: '', notes: '' }));
      notify('success', `تم حفظ إقفال اليومية لخزنة ${closeForm.cashbox} بنجاح`);
    } else {
      notify('error', res.message);
    }
  };

  // Tab 4: Statement filtered movements (chronological ascending with running balance)
  const statementMovements = useMemo(
    () =>
      movements
        .filter(
          (m) =>
            normalizeEntityKey(m.cashbox) === normalizeEntityKey(statementBox) &&
            (m.currency || '').trim().toUpperCase() === statementCur
        )
        .sort((a, b) => ErpDataService.compareRecordsChronologically(a, b, 'asc')),
    [movements, statementBox, statementCur]
  );

  const statementEntriesWithBalance = useMemo(() => {
    let running = 0;
    return statementMovements.map((m) => {
      const amt = Math.abs(Number(m.amount || 0));
      if (isMovementInflow(m)) running += amt;
      else running -= amt;
      return {
        movement: m,
        runningBalance: running
      };
    });
  }, [statementMovements]);

  const statementClosingBalance = useMemo(
    () => getBalanceForBoxAndCurrency(statementBox, statementCur),
    [distinctCashboxes, statementBox, statementCur]
  );

  const handleExportStatementCsv = () => {
    const headers = ['التاريخ', 'رقم المستند', 'النوع', 'المبلغ', 'العملة', 'الرصيد التراكمي', 'البيان', 'المنفذ'];
    const rows = statementEntriesWithBalance.map(({ movement: m, runningBalance }) => [
      m.txDate || '',
      m.reference,
      m.direction,
      m.amount,
      m.currency,
      runningBalance,
      m.description,
      m.createdBy || ''
    ]);
    ReportExporter.exportToCsv(
      `كشف_حساب_خزنة_${statementBox.replace(/\s+/g, '_')}_${statementCur}.csv`,
      headers,
      rows
    );
  };

  const handleExportStatementPdf = () => {
    ReportExporter.exportTreasuryStatementToPrint({
      cashboxName: statementBox,
      currency: statementCur,
      closingBalance: statementClosingBalance,
      movements: statementMovements
    });
  };

  // Tab 5: Manage Cashboxes (Add, Edit, Zero Out, Delete)
  const handleSaveCashbox = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!boxNameInput.trim() || boxGuard.isSubmitting) return;
    const cleanName = boxNameInput.trim();
    const existingByName = distinctCashboxes.find(
      (b) => normalizeEntityKey(b.name) === normalizeEntityKey(cleanName)
    );
    const id = editingBox ? editingBox.id : existingByName ? existingByName.id : Date.now();
    const docId = cashboxDocId(cleanName, id);

    const res = await boxGuard.runGuarded({
      guardKey: `treasury_save_cashbox_${docId}`,
      operationType: editingBox ? 'UPDATE' : 'SAVE',
      userId: userProfile?.username || 'admin',
      documentReference: docId,
      targetCollection: 'cashboxes',
      payloadSummary: `${editingBox ? 'تعديل' : 'إضافة'} خزنة ${cleanName}`,
      execute: async () => {
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
        return { resultReference: docId };
      }
    });

    if (res.ok) {
      setBoxModalOpen(false);
      setEditingBox(null);
      setBoxNameInput('');
      notify('success', `تم حفظ الخزنة "${cleanName}" بنجاح`);
    } else {
      notify('error', res.message);
    }
  };

  const handleZeroOutCashbox = async (box: DistinctCashboxSummary, currencyToZero: CurrencyCode) => {
    if (!canDeleteOrZero || actionGuard.isSubmitting) return;
    const currentBal = box.balances[currencyToZero] || 0;
    if (currentBal === 0) {
      notify('error', `رصيد الخزنة (${box.name} - ${currencyToZero}) صفر بالفعل`);
      return;
    }
    if (!window.confirm(`تأكيد تصفير رصيد "${box.name}" (${currentBal.toLocaleString()} ${currencyToZero})؟`)) {
      return;
    }
    const boxDocId = cashboxDocId(box.name, box.id);
    const res = await actionGuard.runGuarded({
      guardKey: `treasury_zero_cashbox_${boxDocId}_${currencyToZero}`,
      operationType: 'UPDATE',
      userId: userProfile?.username || 'admin',
      documentReference: `${boxDocId}-${currencyToZero}`,
      targetCollection: 'movements',
      payloadSummary: `تصفير خزنة ${box.name} (${currencyToZero})`,
      execute: async ({ idempotencyKey }) => {
        const now = Date.now();
        const today = new Date().toISOString().split('T')[0];
        const movDocId = `MOV-ZERO-${idempotencyKey}`;
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
        await ErpDataService.saveDocument('movements', movDocId, offsetMov);
        await ErpDataService.saveDocument('cashboxes', boxDocId, {
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
        return { resultReference: movDocId };
      }
    });
    if (res.ok) {
      notify('success', res.replayed ? res.message : `تم تصفير رصيد خزنة ${box.name} (${currencyToZero}) بنجاح`);
    } else {
      notify('error', res.message || 'فشل تصفير الخزنة');
    }
  };

  const handleDeleteCashbox = async (box: DistinctCashboxSummary) => {
    if (!canDeleteOrZero || actionGuard.isSubmitting) return;
    if (!window.confirm(`حذف الخزنة "${box.name}" نهائياً؟`)) return;
    const primaryDocId = cashboxDocId(box.name, box.id);
    const actor = userProfile?.username || 'admin';
    const res = await actionGuard.runGuarded({
      guardKey: `treasury_delete_cashbox_${primaryDocId}`,
      operationType: 'DELETE',
      userId: actor,
      documentReference: primaryDocId,
      targetCollection: 'cashboxes',
      payloadSummary: `حذف خزنة ${box.name}`,
      execute: async () => {
        const matchingRawDocs = cashboxes.filter(
          (raw) => normalizeEntityKey(raw.name) === normalizeEntityKey(box.name)
        );
        await Promise.all(
          matchingRawDocs.map((raw) => {
            const docId = (raw as any)._docId || cashboxDocId(raw.name, raw.id);
            return ErpDataService.deleteDocumentWithTombstone(
              'cashboxes',
              docId,
              box.name,
              actor,
              [box.id, `BOX-${box.id}`, raw.id]
            );
          })
        );
        if (matchingRawDocs.length === 0) {
          await ErpDataService.deleteDocumentWithTombstone(
            'cashboxes',
            primaryDocId,
            box.name,
            actor,
            [box.id, `BOX-${box.id}`]
          );
        }
        await ErpDataService.logAudit(
          actor,
          'DELETE_CASHBOX',
          `حذف الخزنة ${box.name}`
        );
        return { resultReference: primaryDocId };
      }
    });
    if (res.ok) {
      notify('success', `تم حذف الخزنة ${box.name} وتسجيل شاهد الحذف`);
    } else {
      notify('error', res.message || 'تعذر حذف الخزنة');
    }
  };

  const filteredMovements = useMemo(() => {
    return movements
      .filter((m) => {
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
      })
      .sort((a, b) => ErpDataService.compareRecordsChronologically(a, b, 'desc'));
  }, [movements, txSearch, txDirFilter, txCashboxFilter]);

  const handleExportFilteredMovementsPdf = () => {
    const targetBoxLabel = txCashboxFilter === 'ALL' ? 'كل الخزن' : txCashboxFilter;
    const ascMoves = [...filteredMovements].sort((a, b) =>
      ErpDataService.compareRecordsChronologically(a, b, 'asc')
    );
    const closingBal =
      txCashboxFilter === 'ALL'
        ? distinctCashboxes.reduce((s, b) => s + Number(b.balances[defaultCurrency] || 0), 0)
        : getBalanceForBoxAndCurrency(txCashboxFilter, defaultCurrency);
    ReportExporter.exportTreasuryStatementToPrint({
      cashboxName: targetBoxLabel,
      currency: txCashboxFilter === 'ALL' ? defaultCurrency : distinctCashboxes.find((b) => normalizeEntityKey(b.name) === normalizeEntityKey(txCashboxFilter))?.primaryCurrency || defaultCurrency,
      closingBalance: closingBal,
      movements: ascMoves
    });
  };

  const handleExportFilteredMovementsCsv = () => {
    const targetBoxLabel = txCashboxFilter === 'ALL' ? 'كل_الخزن' : txCashboxFilter.replace(/\s+/g, '_');
    const ascMoves = [...filteredMovements].sort((a, b) =>
      ErpDataService.compareRecordsChronologically(a, b, 'asc')
    );
    let running = 0;
    const headers = ['التاريخ', 'رقم المستند', 'الخزنة', 'النوع', 'المبلغ', 'العملة', 'الرصيد التراكمي', 'البيان', 'المنفذ'];
    const rows = ascMoves.map((m) => {
      const amt = Math.abs(Number(m.amount || 0));
      running += isMovementInflow(m) ? amt : -amt;
      return [
        m.txDate || '',
        m.reference || '',
        m.cashbox || '',
        m.direction || '',
        amt,
        m.currency || defaultCurrency,
        running,
        m.description || '',
        m.createdBy || ''
      ];
    });
    ReportExporter.exportToCsv(`كشف_حركة_الخزنة_${targetBoxLabel}.csv`, headers, rows);
  };

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
            { id: 'MOVEMENT' as TreasurySubTab, label: 'حركة مالية', count: movements.length, visible: canAccessTreasury },
            { id: 'TRANSFER' as TreasurySubTab, label: 'تحويل خزن', count: transfers.length, visible: canAccessTransfers },
            { id: 'CLOSING' as TreasurySubTab, label: 'إقفال اليومية', count: closings.length, visible: canAccessTreasury },
            { id: 'STATEMENT' as TreasurySubTab, label: 'كشف الحساب', count: null as number | null, visible: canAccessTreasury },
            { id: 'MANAGE_BOXES' as TreasurySubTab, label: 'إدارة الخزن', count: distinctCashboxes.length, visible: canAccessTreasury || isManager }
          ]
            .filter((t) => t.visible)
            .map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => setActiveSubTab(t.id)}
                className={`px-4 py-2 rounded-xl text-xs font-bold transition-all duration-200 min-h-[40px] flex items-center gap-2 ${
                  activeSubTab === t.id
                    ? 'bg-pmp-primary text-white shadow-md scale-[1.01]'
                    : 'bg-theme-base border border-theme text-theme-secondary hover:text-theme-primary hover:border-pmp-primary/40'
                }`}
              >
                <span>{t.label}</span>
                {t.count !== null && (
                  <span
                    className={`px-1.5 py-0.5 rounded-md text-[10px] font-mono ${
                      activeSubTab === t.id ? 'bg-white/20 text-white' : 'bg-theme-surface text-theme-muted'
                    }`}
                  >
                    {t.count}
                  </span>
                )}
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

                {movGuard.pendingRecord && (
                  <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 flex items-center justify-between gap-2">
                    <span>{movGuard.statusMessage || 'لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.'}</span>
                    <GlassButton size="sm" variant="secondary" onClick={() => movGuard.verifyPending()}>
                      التحقق من حالة العملية
                    </GlassButton>
                  </div>
                )}

                <GlassButton
                  type="submit"
                  variant="primary"
                  isLoading={movGuard.isSubmitting}
                  loadingText={
                    movForm.direction === 'وارد'
                      ? OPERATION_LOADING_LABELS.RECEIPT_VOUCHER
                      : OPERATION_LOADING_LABELS.PAYMENT_VOUCHER
                  }
                  className="w-full justify-center"
                >
                  إضافة معاملة
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
                <GlassButton
                  type="button"
                  size="sm"
                  variant="primary"
                  onClick={handleExportFilteredMovementsPdf}
                  icon={<Printer className="w-3.5 h-3.5" />}
                  className="text-xs"
                >
                  تصدير كشف حساب PDF
                </GlassButton>
                <GlassButton
                  type="button"
                  size="sm"
                  variant="secondary"
                  onClick={handleExportFilteredMovementsCsv}
                  icon={<FileSpreadsheet className="w-3.5 h-3.5 text-emerald-400" />}
                  className="text-xs"
                >
                  Excel
                </GlassButton>
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
                          disabled={actionGuard.isSubmitting}
                          onClick={() => handleDeleteMovement(m)}
                          className="p-1.5 rounded-lg bg-red-500/10 text-red-400 hover:bg-red-500/20 disabled:opacity-50"
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

              {trfGuard.pendingRecord && (
                <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 flex items-center justify-between gap-2">
                  <span>{trfGuard.statusMessage || 'لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.'}</span>
                  <GlassButton size="sm" variant="secondary" onClick={() => trfGuard.verifyPending()}>
                    التحقق من حالة العملية
                  </GlassButton>
                </div>
              )}

              <GlassButton
                type="submit"
                variant="primary"
                isLoading={trfGuard.isSubmitting}
                loadingText={OPERATION_LOADING_LABELS.STOCK_TRANSFER}
                className="w-full justify-center"
              >
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

              {closeGuard.pendingRecord && (
                <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 flex items-center justify-between gap-2">
                  <span>{closeGuard.statusMessage || 'لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.'}</span>
                  <GlassButton size="sm" variant="secondary" onClick={() => closeGuard.verifyPending()}>
                    التحقق من حالة العملية
                  </GlassButton>
                </div>
              )}

              <GlassButton
                type="submit"
                variant="primary"
                isLoading={closeGuard.isSubmitting}
                loadingText={OPERATION_LOADING_LABELS.SAVE}
                className="w-full justify-center"
              >
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
            <div className="flex items-center gap-2 flex-wrap">
              <GlassButton
                variant="primary"
                onClick={handleExportStatementPdf}
                icon={<Printer className="w-4 h-4" />}
                className="text-xs"
              >
                طباعة / تصدير PDF
              </GlassButton>
              <GlassButton
                variant="secondary"
                onClick={handleExportStatementCsv}
                icon={<FileSpreadsheet className="w-4 h-4 text-emerald-400" />}
                className="text-xs"
              >
                تصدير إكسيل (Excel)
              </GlassButton>
            </div>
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
            {statementEntriesWithBalance.length === 0 ? (
              <div className="py-10 text-center text-xs text-theme-muted">
                لا توجد معاملات مسجلة لخزنة ({statementBox}) بالعملة ({statementCur}) حتى الآن
              </div>
            ) : (
              [...statementEntriesWithBalance].reverse().map(({ movement: m, runningBalance }) => (
                <div
                  key={m.id}
                  className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-3 text-xs"
                >
                  <div>
                    <div className="flex items-center gap-2 flex-wrap">
                      <GlassBadge variant={m.direction === 'وارد' ? 'green' : 'red'}>{m.direction}</GlassBadge>
                      <span className="font-bold text-theme-primary">{m.description}</span>
                    </div>
                    <div className="text-[11px] text-theme-muted mt-1">
                      {m.txDate} • مستند: {m.reference} • موظف الحساب: {m.createdBy || 'المدير'}
                    </div>
                  </div>
                  <div className="text-left shrink-0 space-y-1">
                    <div
                      className={`font-mono font-bold text-sm ${
                        m.direction === 'وارد' ? 'text-emerald-400' : 'text-red-400'
                      }`}
                    >
                      {m.direction === 'وارد' ? '+' : '-'}
                      {Number(m.amount).toLocaleString()} {m.currency}
                    </div>
                    <div className="text-[10px] font-mono text-theme-secondary">
                      الرصيد التراكمي: <strong>{runningBalance.toLocaleString()} {statementCur}</strong>
                    </div>
                  </div>
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
                      disabled={actionGuard.isSubmitting}
                      onClick={() => handleZeroOutCashbox(b, b.primaryCurrency)}
                      className="p-2 rounded-lg bg-amber-500/10 text-amber-400 hover:bg-amber-500/20 disabled:opacity-50"
                      title="تصفير رصيد الخزنة"
                    >
                      <RotateCcw className="w-4 h-4" />
                    </button>
                    <button
                      type="button"
                      disabled={actionGuard.isSubmitting}
                      onClick={() => {
                        setEditingBox(b);
                        setBoxNameInput(b.name);
                        setBoxCurrencyInput(b.primaryCurrency);
                        setBoxBranchInput(b.branch || 'SD-001');
                        setBoxModalOpen(true);
                      }}
                      className="p-2 rounded-lg bg-pmp-cyan/10 text-pmp-cyan hover:bg-pmp-cyan/20 disabled:opacity-50"
                      title="تعديل الخزنة"
                    >
                      <Edit3 className="w-4 h-4" />
                    </button>
                    <button
                      type="button"
                      disabled={actionGuard.isSubmitting}
                      onClick={() => handleDeleteCashbox(b)}
                      className="p-2 rounded-lg bg-red-500/10 text-red-400 hover:bg-red-500/20 disabled:opacity-50"
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
            <GlassButton type="button" variant="secondary" disabled={boxGuard.isSubmitting} onClick={() => setBoxModalOpen(false)}>
              إلغاء
            </GlassButton>
            <GlassButton
              type="submit"
              variant="primary"
              isLoading={boxGuard.isSubmitting}
              loadingText={editingBox ? OPERATION_LOADING_LABELS.UPDATE : OPERATION_LOADING_LABELS.SAVE}
            >
              حفظ الخزنة
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
