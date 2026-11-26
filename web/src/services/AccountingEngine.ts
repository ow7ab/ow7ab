import {
  BalanceSheetReport,
  ChartAccount,
  ConsolidatedFxSummary,
  CurrencyCode,
  CurrencyFxRates,
  IncomeStatementReport,
  InvoicePayment,
  JournalEntry,
  JournalLine,
  ReconciliationItem,
  SalesInvoice,
  TrialBalanceReport,
  TrialBalanceRow,
  WarehouseStockBalance
} from '../types/erp';
import {
  computeInvoiceFinancials,
  isCancelledOrReversedStatus,
  resolveCanonicalCurrency
} from './ErpCalculations';

export const BALANCE_EPSILON = 0.001;

const BASE_TIMESTAMP = 1700000000000;

export const DEFAULT_CHART_OF_ACCOUNTS: ChartAccount[] = [
  { syncUuid: 'ACC-1110', accountCode: '1110', nameAr: 'صندوق النقدية - السودان (SDG)', nameEn: 'Cashbox Sudan SDG', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1100', currencyHint: 'SDG', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1111', accountCode: '1111', nameAr: 'صندوق النقدية - مصر (EGP)', nameEn: 'Cashbox Egypt EGP', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1100', currencyHint: 'EGP', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1112', accountCode: '1112', nameAr: 'صندوق النقدية - الدولار (USD)', nameEn: 'Cashbox USD', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1100', currencyHint: 'USD', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1115', accountCode: '1115', nameAr: 'خزائن وعهد الفروع (حلفا/عطبرة)', nameEn: 'Branch Cashboxes', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1100', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1120', accountCode: '1120', nameAr: 'البنوك والتحويلات الوسيطة والعملات', nameEn: 'Banks & FX Clearing', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1100', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1130', accountCode: '1130', nameAr: 'ذمم العملاء المدينة (المبيعات الآجلة)', nameEn: 'Accounts Receivable', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1100', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1141', accountCode: '1141', nameAr: 'مخزون المواد الخام والتعبئة', nameEn: 'Raw Materials Inventory', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1140', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1142', accountCode: '1142', nameAr: 'مخزون الإنتاج تحت التشغيل (WIP)', nameEn: 'Work In Progress Inventory', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1140', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1143', accountCode: '1143', nameAr: 'مخزون الإنتاج التام والمبيعات', nameEn: 'Finished Goods Inventory', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1140', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-1210', accountCode: '1210', nameAr: 'الآلات ومعدات الخلط والتصنيع', nameEn: 'Factory Machinery & Equipment', accountType: 'ASSET', normalSide: 'DEBIT', parentCode: '1200', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-2110', accountCode: '2110', nameAr: 'ذمم الموردين الدائنة (المشتريات)', nameEn: 'Accounts Payable Suppliers', accountType: 'LIABILITY', normalSide: 'CREDIT', parentCode: '2100', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-2120', accountCode: '2120', nameAr: 'التزامات ومصروفات مستحقة', nameEn: 'Accrued Liabilities', accountType: 'LIABILITY', normalSide: 'CREDIT', parentCode: '2100', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-3100', accountCode: '3100', nameAr: 'رأس المال والأرصدة الافتتاحية والأرباح المحتجزة', nameEn: 'Capital & Retained Earnings', accountType: 'EQUITY', normalSide: 'CREDIT', parentCode: '3000', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-3900', accountCode: '3900', nameAr: 'حساب فروق التسوية ومطابقة الأرصدة', nameEn: 'Reconciliation & Settlement Adjustments', accountType: 'EQUITY', normalSide: 'CREDIT', parentCode: '3000', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-4100', accountCode: '4100', nameAr: 'إيرادات مبيعات الدهانات والبوهيات', nameEn: 'Paint Sales Revenue', accountType: 'REVENUE', normalSide: 'CREDIT', parentCode: '4000', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-4200', accountCode: '4200', nameAr: 'إيرادات أخرى وفروق عملة دائنة', nameEn: 'Other Income & FX Gain', accountType: 'REVENUE', normalSide: 'CREDIT', parentCode: '4000', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-5100', accountCode: '5100', nameAr: 'تكلفة البضاعة المباعة (COGS)', nameEn: 'Cost of Goods Sold', accountType: 'EXPENSE', normalSide: 'DEBIT', parentCode: '5000', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-5200', accountCode: '5200', nameAr: 'فاقد ومصروفات التصنيع والإنتاج', nameEn: 'Manufacturing Overhead & Waste', accountType: 'EXPENSE', normalSide: 'DEBIT', parentCode: '5000', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP },
  { syncUuid: 'ACC-5300', accountCode: '5300', nameAr: 'مصروفات التشغيل والعمومية', nameEn: 'Operating & General Expenses', accountType: 'EXPENSE', normalSide: 'DEBIT', parentCode: '5000', currencyHint: 'ALL', isSystem: true, active: true, recordVersion: 1, createdAt: BASE_TIMESTAMP, updatedAt: BASE_TIMESTAMP }
];

export function resolveCashAccountCode(cashboxName: string, currency: string): string {
  const cur = resolveCanonicalCurrency(currency, cashboxName);
  const box = String(cashboxName || '').trim();
  if (cur === 'USD' || box.includes('دولار')) return '1112';
  if (cur === 'EGP' || box.includes('مصر') || box.includes('قاهر')) return '1111';
  if (box.includes('سودان') || box.includes('خرطوم')) return '1110';
  if (box.includes('حلفا') || box.includes('عطبر')) return '1115';
  return '1110';
}

export interface JournalLineInput {
  accountCode: string;
  debit?: number;
  credit?: number;
  costCenter?: string;
  partyType?: string;
  partyName?: string;
  memo?: string;
}

export interface JournalPostRequest {
  entryDate?: string;
  sourceType?: string;
  sourceDocId?: number | null;
  sourceDocNo?: string;
  description: string;
  currency: CurrencyCode | string;
  fxRateToUsd?: number;
  fxRateToSdg?: number;
  lines: JournalLineInput[];
  createdBy?: string;
  approvedBy?: string;
  idempotencyKey?: string;
}

let nextMonotonicSeq = 1;

function nextUniqueNumericId(): number {
  const seq = nextMonotonicSeq++;
  return Date.now() * 1000 + (seq % 1000);
}

function generateUuid(prefix = 'JRN'): string {
  const rnd = Math.random().toString(36).slice(2, 8).toUpperCase();
  const seq = nextMonotonicSeq++;
  return `${prefix}-${Date.now()}-${seq}-${rnd}`;
}

export function createBalancedJournalPayload(
  request: JournalPostRequest,
  availableAccounts: ChartAccount[] = DEFAULT_CHART_OF_ACCOUNTS,
  existingEntries: JournalEntry[] = []
): { entry: JournalEntry; lines: JournalLine[] } {
  const desc = String(request.description || '').trim();
  if (!desc) {
    throw new Error('وصف القيد المحاسبي مطلوب.');
  }
  if (!Array.isArray(request.lines) || request.lines.length < 2) {
    throw new Error('يجب أن يحتوي القيد المحاسبي على طرفين (مدين ودائن) على الأقل.');
  }

  const cleanIdemp = String(request.idempotencyKey || '').trim();
  if (cleanIdemp) {
    const dup = existingEntries.find((e) => e.idempotencyKey === cleanIdemp);
    if (dup) {
      throw new Error(`DUPLICATE_IDEMPOTENCY:${dup.entryNo}`);
    }
  }

  const accountsMap = new Map<string, ChartAccount>();
  for (const acc of DEFAULT_CHART_OF_ACCOUNTS) accountsMap.set(acc.accountCode, acc);
  for (const acc of availableAccounts) {
    if (acc && acc.accountCode) accountsMap.set(acc.accountCode, acc);
  }

  const canonicalCurrency = resolveCanonicalCurrency(request.currency);
  let sumDebit = 0;
  let sumCredit = 0;

  const validatedLines = request.lines.map((raw, idx) => {
    const code = String(raw.accountCode || '').trim();
    if (!code) throw new Error(`رمز الحساب مطلوب في السطر رقم ${idx + 1}.`);
    const dr = Number(raw.debit || 0);
    const cr = Number(raw.credit || 0);
    if (!Number.isFinite(dr) || !Number.isFinite(cr) || dr < 0 || cr < 0) {
      throw new Error('المبالغ المالية في القيد يجب أن تكون موجبة أو صفرية.');
    }
    if (dr > BALANCE_EPSILON && cr > BALANCE_EPSILON) {
      throw new Error(`السطر المحاسبي للحساب (${code}) لا يمكن أن يحمل مبلغاً في المدين والدائن معاً.`);
    }
    if (dr <= BALANCE_EPSILON && cr <= BALANCE_EPSILON) {
      throw new Error(`السطر المحاسبي للحساب (${code}) يجب أن يحتوي على قيمة أكبر من صفر.`);
    }
    const meta = accountsMap.get(code);
    if (!meta) {
      throw new Error(`الحساب رقم (${code}) غير معرف في دليل الحسابات.`);
    }
    sumDebit += dr;
    sumCredit += cr;
    return { account: meta, raw, dr, cr };
  });

  if (Math.abs(sumDebit - sumCredit) >= BALANCE_EPSILON) {
    throw new Error(`قيد اليومية غير متوازن! مجموع المدين (${sumDebit}) لا يساوي مجموع الدائن (${sumCredit}).`);
  }

  const now = Date.now();
  const entryId = nextUniqueNumericId();
  const entryUuid = generateUuid(request.sourceType === 'REVERSAL' ? 'REV' : 'JRN');
  const entryNo = `${request.sourceType === 'REVERSAL' ? 'REV' : 'JRN'}-${String(now).slice(-6)}-${Math.floor(100 + Math.random() * 899)}`;
  const entryDate = request.entryDate?.trim() || new Date(now).toISOString().split('T')[0];

  const entry: JournalEntry = {
    id: entryId,
    syncUuid: entryUuid,
    entryNo,
    entryDate,
    sourceType: request.sourceType || 'MANUAL_ADJUSTMENT',
    sourceDocId: request.sourceDocId ?? null,
    sourceDocNo: request.sourceDocNo || '',
    description: desc,
    currency: canonicalCurrency,
    fxRateToUsd: Number(request.fxRateToUsd || 1),
    fxRateToSdg: Number(request.fxRateToSdg || 1),
    totalDebit: sumDebit,
    totalCredit: sumCredit,
    status: 'POSTED',
    reversedEntryId: null,
    reversedByEntryNo: '',
    reversalReason: '',
    idempotencyKey: cleanIdemp,
    createdBy: request.createdBy?.trim() || 'المدير',
    approvedBy: request.approvedBy?.trim() || request.createdBy?.trim() || 'المدير',
    recordVersion: 1,
    createdAt: now,
    updatedAt: now
  };

  const lines: JournalLine[] = validatedLines.map(({ account, raw, dr, cr }, idx) => ({
    id: nextUniqueNumericId(),
    syncUuid: `JL-${entryUuid}-${idx + 1}`,
    journalEntryId: entryId,
    journalEntryUuid: entryUuid,
    lineOrder: idx + 1,
    accountCode: account.accountCode,
    accountName: account.nameAr,
    accountType: account.accountType,
    debit: dr,
    credit: cr,
    currency: canonicalCurrency,
    costCenter: raw.costCenter || 'GENERAL',
    partyType: raw.partyType || '',
    partyName: raw.partyName || '',
    memo: raw.memo?.trim() || desc,
    createdAt: now
  }));

  return { entry, lines };
}

export function buildReversalJournalPayloads(
  originalEntry: JournalEntry,
  originalLines: JournalLine[],
  reason: string,
  actor: string,
  availableAccounts: ChartAccount[] = DEFAULT_CHART_OF_ACCOUNTS
): { updatedOriginal: JournalEntry; reversalEntry: JournalEntry; reversalLines: JournalLine[] } {
  const cleanReason = String(reason || '').trim();
  if (!cleanReason) {
    throw new Error('سبب عكس القيد المحاسبي إلزامي.');
  }
  if (originalEntry.status === 'REVERSED') {
    throw new Error(`القيد (${originalEntry.entryNo}) تم عكسه مسبقاً بالقيد ${originalEntry.reversedByEntryNo || ''}.`);
  }
  const matchingLines = originalLines.filter(
    (l) => l.journalEntryUuid === originalEntry.syncUuid || Number(l.journalEntryId) === Number(originalEntry.id)
  );
  if (matchingLines.length < 2) {
    throw new Error('أسطر القيد الأصلي غير مكتملة ولا يمكن عكسها.');
  }

  const { entry: revEntry, lines: revLines } = createBalancedJournalPayload(
    {
      entryDate: new Date().toISOString().split('T')[0],
      sourceType: 'REVERSAL',
      sourceDocId: originalEntry.id ?? null,
      sourceDocNo: originalEntry.entryNo,
      description: `قيد عكسي للقيد ${originalEntry.entryNo}: ${cleanReason}`,
      currency: originalEntry.currency,
      fxRateToUsd: originalEntry.fxRateToUsd,
      fxRateToSdg: originalEntry.fxRateToSdg,
      lines: matchingLines.map((l) => ({
        accountCode: l.accountCode,
        debit: Number(l.credit || 0),
        credit: Number(l.debit || 0),
        costCenter: l.costCenter,
        partyType: l.partyType,
        partyName: l.partyName,
        memo: `عكس قيد ${originalEntry.entryNo}: ${cleanReason}`
      })),
      createdBy: actor || 'المدير',
      approvedBy: actor || 'المدير',
      idempotencyKey: `REV-${originalEntry.syncUuid}`
    },
    availableAccounts
  );

  revEntry.reversedEntryId = originalEntry.id ?? null;

  const updatedOriginal: JournalEntry = {
    ...originalEntry,
    status: 'REVERSED',
    reversedByEntryNo: revEntry.entryNo,
    reversalReason: cleanReason,
    recordVersion: (originalEntry.recordVersion || 1) + 1,
    updatedAt: Date.now()
  };

  return { updatedOriginal, reversalEntry: revEntry, reversalLines: revLines };
}

// --- Automated Business Event Journal Builders (Web + Desktop Parity) ---

export function buildPurchaseReceiptJournalPayload(
  params: {
    grnSyncUuid: string;
    grnNo: string;
    receiptDate: string;
    supplierName: string;
    materialName: string;
    unit: string;
    acceptedQty: number;
    unitCost: number;
    currency: string;
    performedBy: string;
  },
  accounts: ChartAccount[] = DEFAULT_CHART_OF_ACCOUNTS,
  existingEntries: JournalEntry[] = []
): { entry: JournalEntry; lines: JournalLine[] } | null {
  const total = Number(params.acceptedQty || 0) * Number(params.unitCost || 0);
  if (total <= BALANCE_EPSILON) return null;
  return createBalancedJournalPayload(
    {
      entryDate: params.receiptDate,
      sourceType: 'PURCHASE_RECEIPT',
      sourceDocNo: params.grnNo,
      description: `استلام مشتريات خامات ${params.grnNo} (${params.materialName}) - ${params.supplierName}`,
      currency: params.currency,
      lines: [
        {
          accountCode: '1141',
          debit: total,
          costCenter: 'RAW_WAREHOUSE',
          partyType: 'SUPPLIER',
          partyName: params.supplierName,
          memo: `وارد مخزن الخامات: ${params.acceptedQty} ${params.unit}`
        },
        {
          accountCode: '2110',
          credit: total,
          costCenter: 'RAW_WAREHOUSE',
          partyType: 'SUPPLIER',
          partyName: params.supplierName,
          memo: `استحقاق مورد ${params.supplierName}`
        }
      ],
      createdBy: params.performedBy,
      idempotencyKey: `JRN-GRN-${params.grnSyncUuid}`
    },
    accounts,
    existingEntries
  );
}

export function buildProductionIssueJournalPayload(
  params: {
    orderSyncUuid: string;
    orderNo: string;
    orderDate: string;
    productName: string;
    netIssuedCost: number;
    wasteCost: number;
    actor: string;
    currency?: string;
  },
  accounts: ChartAccount[] = DEFAULT_CHART_OF_ACCOUNTS,
  existingEntries: JournalEntry[] = []
): { entry: JournalEntry; lines: JournalLine[] } | null {
  const net = Math.max(0, Number(params.netIssuedCost || 0));
  const waste = Math.max(0, Number(params.wasteCost || 0));
  const totalCr = net + waste;
  if (totalCr <= BALANCE_EPSILON) return null;

  const lines: JournalLineInput[] = [];
  if (net > BALANCE_EPSILON) {
    lines.push({
      accountCode: '1142',
      debit: net,
      costCenter: 'FACTORY',
      partyType: 'WAREHOUSE',
      partyName: params.orderNo,
      memo: `تحميل تشغيل تحت الإنتاج لأمر ${params.orderNo}`
    });
  }
  if (waste > BALANCE_EPSILON) {
    lines.push({
      accountCode: '5200',
      debit: waste,
      costCenter: 'FACTORY',
      partyType: 'WAREHOUSE',
      partyName: params.orderNo,
      memo: `فاقد خامات تشغيل لأمر ${params.orderNo}`
    });
  }
  lines.push({
    accountCode: '1141',
    credit: totalCr,
    costCenter: 'RAW_WAREHOUSE',
    partyType: 'WAREHOUSE',
    partyName: params.orderNo,
    memo: 'منصرف خامات من مخزن المواد الخام'
  });

  return createBalancedJournalPayload(
    {
      entryDate: params.orderDate,
      sourceType: 'PRODUCTION_ISSUE',
      sourceDocNo: params.orderNo,
      description: `صرف خامات للتشغيل لأمر الإنتاج ${params.orderNo} (${params.productName})`,
      currency: params.currency || 'EGP',
      lines,
      createdBy: params.actor,
      idempotencyKey: `JRN-PRD-ISS-${params.orderSyncUuid}`
    },
    accounts,
    existingEntries
  );
}

export function buildQcFinishedGoodsJournalPayload(
  params: {
    qcSyncUuid: string;
    qcNo: string;
    orderNo: string;
    productName: string;
    producedFullCartons: number;
    fgTotalCost: number;
    inspectorName: string;
    currency?: string;
  },
  accounts: ChartAccount[] = DEFAULT_CHART_OF_ACCOUNTS,
  existingEntries: JournalEntry[] = []
): { entry: JournalEntry; lines: JournalLine[] } | null {
  const cost = Number(params.fgTotalCost || 0);
  if (cost <= BALANCE_EPSILON) return null;
  return createBalancedJournalPayload(
    {
      sourceType: 'QC_FG_RECEIPT',
      sourceDocNo: params.qcNo,
      description: `ترحيل إنتاج تام معتمد جودة ${params.qcNo} لأمر ${params.orderNo}`,
      currency: params.currency || 'EGP',
      lines: [
        {
          accountCode: '1143',
          debit: cost,
          costCenter: 'FACTORY',
          partyType: 'WAREHOUSE',
          partyName: params.productName,
          memo: `استلام منتج تام: ${params.producedFullCartons} كرتونة`
        },
        {
          accountCode: '1142',
          credit: cost,
          costCenter: 'FACTORY',
          partyType: 'WAREHOUSE',
          partyName: params.orderNo,
          memo: 'إقفال تحت التشغيل إلى إنتاج تام'
        }
      ],
      createdBy: params.inspectorName,
      idempotencyKey: `JRN-QC-FG-${params.qcSyncUuid}`
    },
    accounts,
    existingEntries
  );
}

export function buildSalesInvoiceJournalPayloads(
  params: {
    invoiceId: number;
    invoiceNo: string;
    invoiceDate: string;
    customerName: string;
    currency: string;
    totalRevenue: number;
    totalCogs: number;
    paidAmount?: number;
    cashbox?: string;
    actor: string;
  },
  accounts: ChartAccount[] = DEFAULT_CHART_OF_ACCOUNTS,
  existingEntries: JournalEntry[] = []
): Array<{ entry: JournalEntry; lines: JournalLine[] }> {
  const out: Array<{ entry: JournalEntry; lines: JournalLine[] }> = [];
  if (params.totalRevenue > BALANCE_EPSILON) {
    out.push(
      createBalancedJournalPayload(
        {
          entryDate: params.invoiceDate,
          sourceType: 'SALES_INVOICE',
          sourceDocId: params.invoiceId,
          sourceDocNo: params.invoiceNo,
          description: `إثبات مبيعات فاتورة ${params.invoiceNo} - العميل: ${params.customerName}`,
          currency: params.currency,
          lines: [
            {
              accountCode: '1130',
              debit: params.totalRevenue,
              costCenter: 'SALES',
              partyType: 'CUSTOMER',
              partyName: params.customerName,
              memo: `استحقاق فاتورة مبيعات ${params.invoiceNo}`
            },
            {
              accountCode: '4100',
              credit: params.totalRevenue,
              costCenter: 'SALES',
              partyType: 'CUSTOMER',
              partyName: params.customerName,
              memo: 'إيراد مبيعات دهانات'
            }
          ],
          createdBy: params.actor,
          idempotencyKey: `JRN-INV-REV-${params.invoiceNo}`
        },
        accounts,
        existingEntries
      )
    );
  }
  if (params.totalCogs > BALANCE_EPSILON) {
    out.push(
      createBalancedJournalPayload(
        {
          entryDate: params.invoiceDate,
          sourceType: 'SALES_INVOICE',
          sourceDocId: params.invoiceId,
          sourceDocNo: params.invoiceNo,
          description: `إثبات تكلفة بضاعة مباعة لفاتورة ${params.invoiceNo}`,
          currency: params.currency,
          lines: [
            {
              accountCode: '5100',
              debit: params.totalCogs,
              costCenter: 'SALES',
              partyType: 'CUSTOMER',
              partyName: params.customerName,
              memo: `تكلفة المبيعات ${params.invoiceNo}`
            },
            {
              accountCode: '1143',
              credit: params.totalCogs,
              costCenter: 'SALES',
              partyType: 'WAREHOUSE',
              partyName: params.customerName,
              memo: 'صرف مخزون إنتاج تام'
            }
          ],
          createdBy: params.actor,
          idempotencyKey: `JRN-INV-COGS-${params.invoiceNo}`
        },
        accounts,
        existingEntries
      )
    );
  }
  if ((params.paidAmount || 0) > BALANCE_EPSILON && params.cashbox) {
    const cashCode = resolveCashAccountCode(params.cashbox, params.currency);
    out.push(
      createBalancedJournalPayload(
        {
          entryDate: params.invoiceDate,
          sourceType: 'CUSTOMER_PAYMENT',
          sourceDocId: params.invoiceId,
          sourceDocNo: params.invoiceNo,
          description: `تحصيل دفعة مقدمة من العميل ${params.customerName} في ${params.cashbox}`,
          currency: params.currency,
          lines: [
            {
              accountCode: cashCode,
              debit: params.paidAmount,
              costCenter: 'TREASURY',
              partyType: 'CASHBOX',
              partyName: params.cashbox,
              memo: `وارد خزنة ${params.cashbox}`
            },
            {
              accountCode: '1130',
              credit: params.paidAmount,
              costCenter: 'SALES',
              partyType: 'CUSTOMER',
              partyName: params.customerName,
              memo: `تخفيض ذمة العميل ${params.customerName}`
            }
          ],
          createdBy: params.actor,
          idempotencyKey: `JRN-PAY-INIT-${params.invoiceNo}`
        },
        accounts,
        existingEntries
      )
    );
  }
  return out;
}

export function buildTreasuryMovementJournalPayload(
  params: {
    id: number;
    txDate: string;
    cashbox: string;
    currency: string;
    direction: string;
    amount: number;
    description: string;
    reference: string;
    createdBy: string;
  },
  accounts: ChartAccount[] = DEFAULT_CHART_OF_ACCOUNTS,
  existingEntries: JournalEntry[] = []
): { entry: JournalEntry; lines: JournalLine[] } | null {
  if (params.amount <= BALANCE_EPSILON) return null;
  const cashCode = resolveCashAccountCode(params.cashbox, params.currency);
  const dir = String(params.direction || '').trim().toUpperCase();
  const isIncoming = dir === 'وارد' || dir === 'IN' || dir === 'INCOME';
  const isSupplierGrn =
    !isIncoming &&
    (params.reference.startsWith('GRN-') ||
      params.description.includes('مورد') ||
      params.description.includes('مشتريات'));

  const lines: JournalLineInput[] = isIncoming
    ? [
        { accountCode: cashCode, debit: params.amount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.cashbox, memo: params.description },
        { accountCode: '4200', credit: params.amount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.cashbox, memo: params.description }
      ]
    : [
        { accountCode: isSupplierGrn ? '2110' : '5300', debit: params.amount, costCenter: isSupplierGrn ? 'RAW_WAREHOUSE' : 'TREASURY', partyType: isSupplierGrn ? 'SUPPLIER' : 'CASHBOX', partyName: params.cashbox, memo: params.description },
        { accountCode: cashCode, credit: params.amount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.cashbox, memo: params.description }
      ];

  return createBalancedJournalPayload(
    {
      entryDate: params.txDate,
      sourceType: 'TREASURY_MOVEMENT',
      sourceDocId: params.id,
      sourceDocNo: params.reference,
      description: params.description,
      currency: params.currency,
      lines,
      createdBy: params.createdBy,
      idempotencyKey: `JRN-MOV-${params.id}-${params.reference}`
    },
    accounts,
    existingEntries
  );
}

export function buildTreasuryTransferJournalPayloads(
  params: {
    id: number;
    txDate: string;
    reference: string;
    sourceCashbox: string;
    destinationCashbox: string;
    sourceCurrency: string;
    destinationCurrency: string;
    sourceAmount: number;
    destinationAmount: number;
    createdBy: string;
  },
  accounts: ChartAccount[] = DEFAULT_CHART_OF_ACCOUNTS,
  existingEntries: JournalEntry[] = []
): Array<{ entry: JournalEntry; lines: JournalLine[] }> {
  const srcCur = resolveCanonicalCurrency(params.sourceCurrency);
  const dstCur = resolveCanonicalCurrency(params.destinationCurrency);
  const srcCode = resolveCashAccountCode(params.sourceCashbox, srcCur);
  const dstCode = resolveCashAccountCode(params.destinationCashbox, dstCur);

  if (srcCur === dstCur) {
    return [
      createBalancedJournalPayload(
        {
          entryDate: params.txDate,
          sourceType: 'TREASURY_TRANSFER',
          sourceDocId: params.id,
          sourceDocNo: params.reference,
          description: `تحويل خزنة ${params.sourceCashbox} -> ${params.destinationCashbox}`,
          currency: srcCur,
          lines: [
            { accountCode: dstCode, debit: params.destinationAmount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.destinationCashbox, memo: `تحويل وارد إلى ${params.destinationCashbox}` },
            { accountCode: srcCode, credit: params.sourceAmount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.sourceCashbox, memo: `تحويل صادر من ${params.sourceCashbox}` }
          ],
          createdBy: params.createdBy,
          idempotencyKey: `JRN-TRF-${params.id}-${params.reference}`
        },
        accounts,
        existingEntries
      )
    ];
  }

  return [
    createBalancedJournalPayload(
      {
        entryDate: params.txDate,
        sourceType: 'TREASURY_TRANSFER',
        sourceDocId: params.id,
        sourceDocNo: params.reference,
        description: `تحويل صادر (عملات أجنبية) ${params.sourceCashbox} -> وسيط العملات`,
        currency: srcCur,
        lines: [
          { accountCode: '1120', debit: params.sourceAmount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.destinationCashbox, memo: `وسيط صرف تحويل إلى ${dstCur}` },
          { accountCode: srcCode, credit: params.sourceAmount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.sourceCashbox, memo: `صرف من ${params.sourceCashbox}` }
        ],
        createdBy: params.createdBy,
        idempotencyKey: `JRN-TRF-OUT-${params.id}-${params.reference}`
      },
      accounts,
      existingEntries
    ),
    createBalancedJournalPayload(
      {
        entryDate: params.txDate,
        sourceType: 'TREASURY_TRANSFER',
        sourceDocId: params.id,
        sourceDocNo: params.reference,
        description: `تحويل وارد (عملات أجنبية) وسيط العملات -> ${params.destinationCashbox}`,
        currency: dstCur,
        lines: [
          { accountCode: dstCode, debit: params.destinationAmount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.destinationCashbox, memo: `استلام في ${params.destinationCashbox}` },
          { accountCode: '1120', credit: params.destinationAmount, costCenter: 'TREASURY', partyType: 'CASHBOX', partyName: params.sourceCashbox, memo: `مقابل تحويل من ${srcCur}` }
        ],
        createdBy: params.createdBy,
        idempotencyKey: `JRN-TRF-IN-${params.id}-${params.reference}`
      },
      accounts,
      existingEntries
    )
  ];
}

// --- Financial Statements & Reconciliation Calculators ---

export function computeTrialBalance(
  accounts: ChartAccount[],
  entries: JournalEntry[],
  lines: JournalLine[],
  currencyFilter: CurrencyCode | string = 'SDG'
): TrialBalanceReport {
  const targetCurrency = resolveCanonicalCurrency(currencyFilter);
  const matchingEntries = entries.filter((e) => resolveCanonicalCurrency(e.currency) === targetCurrency);
  const matchingIds = new Set(matchingEntries.map((e) => Number(e.id)).filter(Number.isFinite));
  const matchingUuids = new Set(matchingEntries.map((e) => e.syncUuid).filter(Boolean));

  const filteredLines = lines.filter((l) => {
    if (l.journalEntryUuid) return matchingUuids.has(l.journalEntryUuid);
    return matchingIds.has(Number(l.journalEntryId));
  });

  const accountByCode = new Map<string, ChartAccount>();
  for (const acc of DEFAULT_CHART_OF_ACCOUNTS) accountByCode.set(acc.accountCode, acc);
  for (const acc of accounts) {
    if (acc && acc.accountCode) accountByCode.set(acc.accountCode, acc);
  }

  const totalsByCode = new Map<string, { dr: number; cr: number }>();
  for (const line of filteredLines) {
    const prev = totalsByCode.get(line.accountCode) || { dr: 0, cr: 0 };
    totalsByCode.set(line.accountCode, {
      dr: prev.dr + Number(line.debit || 0),
      cr: prev.cr + Number(line.credit || 0)
    });
  }

  const rows: TrialBalanceRow[] = Array.from(totalsByCode.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .filter(([, tot]) => tot.dr >= 0.0001 || tot.cr >= 0.0001)
    .map(([code, tot]) => {
      const meta = accountByCode.get(code);
      const accType =
        meta?.accountType ||
        (code.startsWith('1')
          ? 'ASSET'
          : code.startsWith('2')
          ? 'LIABILITY'
          : code.startsWith('3')
          ? 'EQUITY'
          : code.startsWith('4')
          ? 'REVENUE'
          : 'EXPENSE');
      const normalSide =
        meta?.normalSide || (accType === 'ASSET' || accType === 'EXPENSE' ? 'DEBIT' : 'CREDIT');
      const diff = tot.dr - tot.cr;
      return {
        accountCode: code,
        accountName: meta?.nameAr || `حساب #${code}`,
        accountType: accType,
        normalSide,
        totalDebit: tot.dr,
        totalCredit: tot.cr,
        netDebit: diff > 0 ? diff : 0,
        netCredit: diff < 0 ? -diff : 0,
        signedBalance: normalSide === 'DEBIT' ? tot.dr - tot.cr : tot.cr - tot.dr
      };
    });

  const totalDebit = rows.reduce((s, r) => s + r.totalDebit, 0);
  const totalCredit = rows.reduce((s, r) => s + r.totalCredit, 0);

  return {
    currency: targetCurrency,
    rows,
    totalDebit,
    totalCredit,
    isBalanced: Math.abs(totalDebit - totalCredit) < 0.01
  };
}

export function computeIncomeStatement(trialBalance: TrialBalanceReport): IncomeStatementReport {
  const revenueRows = trialBalance.rows.filter((r) => r.accountType === 'REVENUE');
  const cogsRows = trialBalance.rows.filter((r) => r.accountType === 'EXPENSE' && r.accountCode.startsWith('51'));
  const operatingExpenseRows = trialBalance.rows.filter(
    (r) => r.accountType === 'EXPENSE' && !r.accountCode.startsWith('51')
  );

  const totalRevenue = revenueRows.reduce((s, r) => s + r.signedBalance, 0);
  const totalCogs = cogsRows.reduce((s, r) => s + r.signedBalance, 0);
  const grossProfit = totalRevenue - totalCogs;
  const totalOperatingExpenses = operatingExpenseRows.reduce((s, r) => s + r.signedBalance, 0);
  const netIncome = grossProfit - totalOperatingExpenses;

  return {
    currency: trialBalance.currency,
    revenueRows,
    cogsRows,
    operatingExpenseRows,
    totalRevenue,
    totalCogs,
    grossProfit,
    totalOperatingExpenses,
    netIncome
  };
}

export function computeBalanceSheet(
  trialBalance: TrialBalanceReport,
  netIncome: number
): BalanceSheetReport {
  const assetRows = trialBalance.rows.filter((r) => r.accountType === 'ASSET');
  const liabilityRows = trialBalance.rows.filter((r) => r.accountType === 'LIABILITY');
  const equityRows = trialBalance.rows.filter((r) => r.accountType === 'EQUITY');

  const totalAssets = assetRows.reduce((s, r) => s + r.signedBalance, 0);
  const totalLiabilities = liabilityRows.reduce((s, r) => s + r.signedBalance, 0);
  const totalEquityBeforeNetIncome = equityRows.reduce((s, r) => s + r.signedBalance, 0);
  const totalLiabilitiesAndEquity = totalLiabilities + totalEquityBeforeNetIncome + netIncome;
  const imbalanceDiff = Math.abs(totalAssets - totalLiabilitiesAndEquity);

  return {
    currency: trialBalance.currency,
    assetRows,
    liabilityRows,
    equityRows,
    totalAssets,
    totalLiabilities,
    totalEquityBeforeNetIncome,
    currentNetIncome: netIncome,
    totalLiabilitiesAndEquity,
    isBalanced: imbalanceDiff < 0.05,
    imbalanceDiff
  };
}

export function convertToSdg(amount: number, currency: string, rates: CurrencyFxRates): number {
  const c = resolveCanonicalCurrency(currency);
  if (c === 'USD') return amount * Number(rates.usdToSdg || 0);
  if (c === 'EGP') return amount * Number(rates.egpToSdg || 0);
  return amount;
}

export function computeConsolidatedFxSummary(
  accounts: ChartAccount[],
  entries: JournalEntry[],
  lines: JournalLine[],
  rates: CurrencyFxRates
): ConsolidatedFxSummary {
  const currencies: CurrencyCode[] = ['SDG', 'EGP', 'USD'];
  let totalRevenueConsolidated = 0;
  let totalExpensesConsolidated = 0;
  let totalAssetsConsolidated = 0;
  let totalLiabilitiesConsolidated = 0;
  let totalEquityConsolidated = 0;

  for (const cur of currencies) {
    const tb = computeTrialBalance(accounts, entries, lines, cur);
    const pnl = computeIncomeStatement(tb);
    const bs = computeBalanceSheet(tb, pnl.netIncome);
    totalRevenueConsolidated += convertToSdg(pnl.totalRevenue, cur, rates);
    totalExpensesConsolidated += convertToSdg(pnl.totalCogs + pnl.totalOperatingExpenses, cur, rates);
    totalAssetsConsolidated += convertToSdg(bs.totalAssets, cur, rates);
    totalLiabilitiesConsolidated += convertToSdg(bs.totalLiabilities, cur, rates);
    totalEquityConsolidated += convertToSdg(bs.totalEquityBeforeNetIncome + bs.currentNetIncome, cur, rates);
  }

  return {
    baseCurrency: 'SDG',
    rates,
    totalRevenueConsolidated,
    totalExpensesConsolidated,
    netIncomeConsolidated: totalRevenueConsolidated - totalExpensesConsolidated,
    totalAssetsConsolidated,
    totalLiabilitiesConsolidated,
    totalEquityConsolidated
  };
}

export function computeReconciliationReport(
  currency: CurrencyCode | string,
  cashboxTotalForCurrency: number,
  invoices: SalesInvoice[],
  payments: InvoicePayment[],
  stockBalances: WarehouseStockBalance[],
  trialBalance: TrialBalanceReport
): ReconciliationItem[] {
  const targetCur = resolveCanonicalCurrency(currency);
  const glCash = trialBalance.rows
    .filter((r) => ['1110', '1111', '1112', '1115'].includes(r.accountCode))
    .reduce((s, r) => s + r.signedBalance, 0);

  const activeInvoices = invoices.filter(
    (inv) =>
      !isCancelledOrReversedStatus(inv.status) &&
      resolveCanonicalCurrency(inv.currency) === targetCur
  );
  const operationalAr = activeInvoices.reduce((sum, inv) => {
    const fin = computeInvoiceFinancials(inv, payments);
    return sum + fin.remainingAmount;
  }, 0);
  const glAr = trialBalance.rows.find((r) => r.accountCode === '1130')?.signedBalance || 0;

  const operationalInvVal = stockBalances
    .filter((b) => resolveCanonicalCurrency(b.currency) === targetCur)
    .reduce((s, b) => s + Math.max(0, Number(b.onHandQty || 0)) * Math.max(0, Number(b.avgUnitCost || 0)), 0);
  const glInvVal = trialBalance.rows
    .filter((r) => ['1141', '1142', '1143'].includes(r.accountCode))
    .reduce((s, r) => s + r.signedBalance, 0);

  const mkRow = (label: string, op: number, gl: number): ReconciliationItem => {
    const difference = op - gl;
    return {
      label,
      operationalValue: op,
      glValue: gl,
      difference,
      status: Math.abs(difference) < 1.0 ? 'MATCHED' : 'VARIANCE'
    };
  };

  return [
    mkRow(`النقدية والخزائن (${targetCur})`, cashboxTotalForCurrency, glCash),
    mkRow(`ذمم العملاء والمبيعات (${targetCur})`, operationalAr, glAr),
    mkRow(`تقييم المخزون (خام + WIP + تام) (${targetCur})`, operationalInvVal, glInvVal)
  ];
}
