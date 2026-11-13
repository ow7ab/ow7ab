/**
 * Pure, deterministic ERP calculation & ID normalization helpers shared between
 * `ErpDataService.ts`, UI modules, and automated Node.js unit tests.
 * Mirrors Android `PmpViewModel.kt` & `CloudErpSyncRepository.kt`.
 */

import {
  Cashbox,
  TreasuryMovement,
  Product,
  StockMove,
  FactoryItem,
  FactoryMove,
  SalesInvoice,
  InvoicePayment,
  Customer,
  PurchaseInvoice,
  Supplier,
  CurrencyCode
} from '../types/erp';

export interface DistinctCashboxSummary {
  id: number;
  name: string;
  branch: string;
  active: boolean;
  primaryCurrency: CurrencyCode;
  primaryBalance: number;
  balances: {
    SDG: number;
    EGP: number;
    USD: number;
  };
}

/**
 * Normalizes a display name or identifier into a canonical comparison key
 * to prevent duplicate cashboxes, warehouses, products, or accounts.
 */
export function normalizeEntityKey(raw?: unknown): string {
  return String(raw ?? '')
    .trim()
    .replace(/\s+/g, ' ')
    .toLowerCase();
}

function resolveRecordTimestamp(rec: Record<string, any>): number {
  const u = rec?.updatedAt ?? rec?.serverUpdatedAt ?? rec?.createdAt ?? 0;
  if (typeof u === 'number' && Number.isFinite(u)) return u;
  if (u && typeof u.seconds === 'number') return u.seconds * 1000;
  return 0;
}

/**
 * Deterministically deduplicates raw Firestore collection documents on read,
 * matching Android `CloudErpSyncRepository.kt` deduplication rules.
 */
export function deduplicateCollectionRecords<T>(collectionName: string, rawItems: T[]): T[] {
  if (!Array.isArray(rawItems) || rawItems.length <= 1) return rawItems || [];

  const col = (collectionName || '').trim().toLowerCase();

  // Helper that merges items by a primary key (and optional secondary key), keeping the most recent / complete record
  const dedupeByKeys = (
    items: T[],
    getKey1: (item: any) => string,
    getKey2?: (item: any) => string
  ): T[] => {
    const map = new Map<string, T>();
    const secondaryToPrimary = new Map<string, string>();

    for (const item of items) {
      if (!item || typeof item !== 'object') continue;
      const k1 = getKey1(item);
      const k2 = getKey2 ? getKey2(item) : '';
      const resolvedKey = (k1 && map.has(k1))
        ? k1
        : (k2 && secondaryToPrimary.has(k2))
        ? secondaryToPrimary.get(k2)!
        : k1 || k2;

      if (!resolvedKey) continue;

      const existing = map.get(resolvedKey);
      if (!existing) {
        map.set(resolvedKey, item);
        if (k2) secondaryToPrimary.set(k2, resolvedKey);
      } else {
        const existingTs = resolveRecordTimestamp(existing as Record<string, any>);
        const newTs = resolveRecordTimestamp(item as Record<string, any>);
        const existingId = Number((existing as any).id || 0);
        const newId = Number((item as any).id || 0);
        // Prefer record with newer timestamp, or smaller canonical ID (1..1000) over synthetic timestamp ID
        const preferNew =
          newTs > existingTs ||
          (newTs === existingTs && newId > 0 && (existingId <= 0 || (newId < 100000 && existingId >= 100000)));
        if (preferNew) {
          map.set(resolvedKey, { ...existing, ...item });
        }
        if (k2) secondaryToPrimary.set(k2, resolvedKey);
      }
    }

    return Array.from(map.values());
  };

  switch (col) {
    case 'cashboxes':
      return dedupeByKeys(
        rawItems,
        (b) => normalizeEntityKey(b.name),
        (b) => (b.id !== undefined ? `id:${b.id}` : '')
      );

    case 'warehouses':
      return dedupeByKeys(
        rawItems,
        (w) => normalizeEntityKey(w.name),
        (w) => (w.id !== undefined ? `id:${w.id}` : '')
      );

    case 'products':
      return dedupeByKeys(
        rawItems,
        (p) =>
          `${normalizeEntityKey(p.name)}__${normalizeEntityKey(p.packageWeight)}__${normalizeEntityKey(
            p.paintColor
          )}`,
        (p) => (p.sku ? `sku:${normalizeEntityKey(p.sku)}` : p.id !== undefined ? `id:${p.id}` : '')
      );

    case 'customers':
    case 'suppliers':
    case 'factory_items':
    case 'machines':
      return dedupeByKeys(
        rawItems,
        (x) => normalizeEntityKey(x.name),
        (x) => (x.id !== undefined ? `id:${x.id}` : '')
      );

    case 'poster_prices':
      return dedupeByKeys(
        rawItems,
        (p) =>
          `${normalizeEntityKey(p.weightCode)}__${normalizeEntityKey(p.colorName)}__${normalizeEntityKey(
            p.currency || 'SDG'
          )}`,
        (p) => (p.id !== undefined ? `id:${p.id}` : '')
      );

    case 'user_accounts':
      return dedupeByKeys(
        rawItems,
        (u) => normalizeEntityKey(u.username || u.userCode),
        (u) => normalizeEntityKey(u.userCode || u.id)
      );

    case 'movements':
      return dedupeByKeys(
        rawItems,
        (m) => (m.id !== undefined ? `id:${m.id}` : ''),
        (m) =>
          m.reference
            ? `${normalizeEntityKey(m.cashbox)}__${normalizeEntityKey(
                m.currency || 'SDG'
              )}__${normalizeEntityKey(m.direction)}__${Number(m.amount || 0)}__${normalizeEntityKey(
                m.reference
              )}__${normalizeEntityKey(m.txDate)}`
            : ''
      );

    case 'transfers':
      return dedupeByKeys(
        rawItems,
        (t) => (t.id !== undefined ? `id:${t.id}` : ''),
        (t) =>
          t.reference
            ? `${normalizeEntityKey(t.sourceCashbox)}__${normalizeEntityKey(
                t.destinationCashbox
              )}__${Number(t.sourceAmount || 0)}__${normalizeEntityKey(t.reference)}`
            : ''
      );

    case 'daily_closings':
      return dedupeByKeys(
        rawItems,
        (c) => (c.id !== undefined ? `id:${c.id}` : ''),
        (c) =>
          `${normalizeEntityKey(c.cashbox)}__${normalizeEntityKey(c.currency || 'SDG')}__${normalizeEntityKey(
            c.closeDate
          )}`
      );

    case 'invoices':
    case 'sales_invoices':
    case 'purchases':
    case 'purchase_invoices':
      return dedupeByKeys(
        rawItems,
        (inv) => normalizeEntityKey(inv.invoiceNo || inv.invoiceNumber),
        (inv) => (inv.id !== undefined ? `id:${inv.id}` : '')
      );

    case 'paint_batches':
      return dedupeByKeys(
        rawItems,
        (b) => normalizeEntityKey(b.batchNumber),
        (b) => (b.id !== undefined ? `id:${b.id}` : '')
      );

    case 'lab_formulations':
      return dedupeByKeys(
        rawItems,
        (f) => normalizeEntityKey(f.formulaNo),
        (f) => (f.id !== undefined ? `id:${f.id}` : '')
      );

    case 'qc_inspections':
      return dedupeByKeys(
        rawItems,
        (q) => normalizeEntityKey(q.inspectionNumber),
        (q) => (q.id !== undefined ? `id:${q.id}` : '')
      );

    default:
      return dedupeByKeys(
        rawItems,
        (item) => (item?.id !== undefined ? `id:${item.id}` : JSON.stringify(item))
      );
  }
}

export function utf8HashHex(str: string): string {
  let h1 = 0xdeadbeef ^ str.length;
  let h2 = 0x41c6ce57 ^ str.length;
  for (let i = 0, ch; i < str.length; i++) {
    ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (h1 >>> 0).toString(16).padStart(8, '0') + (h2 >>> 0).toString(16).padStart(8, '0');
}

/**
 * Mirrors `CloudErpSyncRepository.kt#safeId` producing Firestore-rule-compliant ASCII IDs (`^[a-zA-Z0-9_\-]+$`).
 */
export function safeId(raw: string, fallback: string): string {
  const cleaned = (raw || '')
    .trim()
    .replace(/[^a-zA-Z0-9_-]/g, '_')
    .replace(/^_+|_+$/g, '');
  const hasAlnum = /[a-zA-Z0-9]/.test(cleaned);
  return hasAlnum && cleaned.length > 0 ? cleaned.slice(0, 96) : fallback;
}

export function cashboxDocId(name: string, id: number): string {
  const cleanName = (name || '').trim();
  return id > 0 ? `BOX-${id}` : safeId(cleanName, `BOX-H${utf8HashHex(cleanName)}`);
}

export function posterPriceDocId(weightCode: string, colorName: string, currency = 'SDG'): string {
  const rawKey = `${weightCode.trim()}_${colorName.trim()}_${currency.trim().toUpperCase()}`;
  const fallback = `PP-${safeId(weightCode, 'W')}-${utf8HashHex(rawKey)}-${safeId(currency, 'SDG')}`;
  const candidate = safeId(`PP_${rawKey}`, fallback);
  return candidate.split('_').filter(Boolean).length >= 3 ? candidate : fallback;
}

function inferPrimaryCurrencyForCashbox(name: string, explicitCurrency?: string): CurrencyCode {
  const upper = String(explicitCurrency || '').trim().toUpperCase();
  if (upper === 'SDG' || upper === 'EGP' || upper === 'USD') {
    return upper as CurrencyCode;
  }
  const cleanName = (name || '').trim();
  if (cleanName.includes('مصر') || cleanName.includes('القاهرة')) return 'EGP';
  if (cleanName.includes('دولار') || cleanName.toUpperCase().includes('USD')) return 'USD';
  return 'SDG';
}

/**
 * Computes one unified summary record per distinct cashbox name (matching Android's `TreasuryScreen.kt`),
 * with its SDG, EGP, and USD balances aggregated cleanly so cashboxes never duplicate in UI cards or dropdowns.
 */
export function computeDistinctCashboxesWithBalances(
  cashboxes: Cashbox[],
  movements: TreasuryMovement[]
): DistinctCashboxSummary[] {
  const uniqueBoxes = deduplicateCollectionRecords('cashboxes', cashboxes);
  const uniqueMovements = deduplicateCollectionRecords('movements', movements);

  // Ledger totals keyed by `${normalizedBoxName}__${currency}`
  const ledgerSums = new Map<string, number>();
  const ledgerCounts = new Map<string, number>();
  // Preserve first-seen display name for any cashbox referenced in movements
  const displayNamesByNorm = new Map<string, string>();

  uniqueBoxes.forEach((b) => {
    const clean = (b.name || '').trim();
    if (clean) displayNamesByNorm.set(normalizeEntityKey(clean), clean);
  });

  uniqueMovements.forEach((m) => {
    const rawName = (m.cashbox || '').trim();
    if (!rawName) return;
    const normName = normalizeEntityKey(rawName);
    if (!displayNamesByNorm.has(normName)) {
      displayNamesByNorm.set(normName, rawName);
    }
    const cur = (m.currency || 'SDG').trim().toUpperCase();
    const key = `${normName}__${cur}`;
    const amt = Number(m.amount) || 0;
    const isInflow = m.direction === 'وارد' || (m.direction as string) === 'IN';
    const delta = isInflow ? amt : -amt;
    ledgerSums.set(key, (ledgerSums.get(key) || 0) + delta);
    ledgerCounts.set(key, (ledgerCounts.get(key) || 0) + 1);
  });

  const resultByNorm = new Map<string, DistinctCashboxSummary>();

  uniqueBoxes.forEach((b, idx) => {
    const cleanName = (b.name || '').trim();
    if (!cleanName) return;
    const normName = normalizeEntityKey(cleanName);
    const primaryCur = inferPrimaryCurrencyForCashbox(cleanName, b.currency);

    const getBal = (cur: CurrencyCode): number => {
      const k = `${normName}__${cur}`;
      if ((ledgerCounts.get(k) || 0) > 0) {
        return ledgerSums.get(k) || 0;
      }
      return cur === primaryCur ? Number(b.balance) || 0 : 0;
    };

    const balances = {
      SDG: getBal('SDG'),
      EGP: getBal('EGP'),
      USD: getBal('USD')
    };

    const primaryBalance =
      balances[primaryCur] !== 0
        ? balances[primaryCur]
        : balances.SDG !== 0
        ? balances.SDG
        : balances.EGP !== 0
        ? balances.EGP
        : balances.USD;

    resultByNorm.set(normName, {
      id: Number(b.id) || idx + 1,
      name: cleanName,
      branch: b.branch || (primaryCur === 'EGP' ? 'EG-001' : 'SD-001'),
      active: b.active !== false,
      primaryCurrency: primaryCur,
      primaryBalance,
      balances
    });
  });

  // Also include any cashbox referenced in movements that wasn't in `cashboxes` collection
  let extraIdx = uniqueBoxes.length + 100;
  displayNamesByNorm.forEach((displayName, normName) => {
    if (resultByNorm.has(normName)) return;
    const primaryCur = inferPrimaryCurrencyForCashbox(displayName);
    const balances = {
      SDG: ledgerSums.get(`${normName}__SDG`) || 0,
      EGP: ledgerSums.get(`${normName}__EGP`) || 0,
      USD: ledgerSums.get(`${normName}__USD`) || 0
    };
    const primaryBalance =
      balances[primaryCur] !== 0
        ? balances[primaryCur]
        : balances.SDG !== 0
        ? balances.SDG
        : balances.EGP !== 0
        ? balances.EGP
        : balances.USD;

    resultByNorm.set(normName, {
      id: extraIdx++,
      name: displayName,
      branch: primaryCur === 'EGP' ? 'EG-001' : 'SD-001',
      active: true,
      primaryCurrency: primaryCur,
      primaryBalance,
      balances
    });
  });

  return Array.from(resultByNorm.values());
}

/**
 * Mirrors Android `PmpViewModel.cashboxBalances`:
 * Computes live balance per (cashboxName, currency) from deduplicated `movements` ledger.
 * Only emits a secondary currency entry for a cashbox if that secondary currency has actual non-zero balance,
 * preventing duplicate empty cashbox entries.
 */
export function computeCashboxBalances(
  cashboxes: Cashbox[],
  movements: TreasuryMovement[]
): Cashbox[] {
  const summaries = computeDistinctCashboxesWithBalances(cashboxes, movements);
  const result: Cashbox[] = [];

  for (const s of summaries) {
    const currencies: CurrencyCode[] = ['SDG', 'EGP', 'USD'];
    let emittedAny = false;

    for (const cur of currencies) {
      const bal = s.balances[cur];
      if (cur === s.primaryCurrency || bal !== 0) {
        result.push({
          id: s.id,
          name: s.name,
          currency: cur,
          balance: bal,
          branch: s.branch,
          active: s.active,
          updatedAt: Date.now()
        });
        emittedAny = true;
      }
    }

    if (!emittedAny) {
      result.push({
        id: s.id,
        name: s.name,
        currency: s.primaryCurrency,
        balance: 0,
        branch: s.branch,
        active: s.active,
        updatedAt: Date.now()
      });
    }
  }

  return result;
}

/**
 * Mirrors Android `PmpViewModel.productStocks`:
 * Aggregates `stock_moves` per `productId`.
 */
export function computeProductStocks(
  products: Pick<Product, 'id' | 'currentStock'>[],
  stockMoves: Pick<StockMove, 'productId' | 'kind' | 'quantity'>[]
): Map<number, number> {
  const uniqueMoves = deduplicateCollectionRecords('stock_moves', stockMoves as any[]);
  const moveSums = new Map<number, number>();
  const moveCounts = new Map<number, number>();

  uniqueMoves.forEach((sm) => {
    const pid = Number(sm.productId);
    if (!pid) return;
    const qty = Number(sm.quantity) || 0;
    const k = (sm.kind || '').trim().toUpperCase();
    const isOut =
      k === 'OUT' ||
      k === 'SALE' ||
      k === 'ISSUE' ||
      k === 'صرف' ||
      k === 'مبيعات' ||
      k === 'تحويل صادر';
    const delta = isOut ? -qty : qty;
    moveSums.set(pid, (moveSums.get(pid) || 0) + delta);
    moveCounts.set(pid, (moveCounts.get(pid) || 0) + 1);
  });

  const stockMap = new Map<number, number>();
  products.forEach((p) => {
    const pid = Number(p.id);
    const hasMoves = (moveCounts.get(pid) || 0) > 0;
    stockMap.set(pid, hasMoves ? (moveSums.get(pid) || 0) : (Number(p.currentStock) || 0));
  });

  moveSums.forEach((val, pid) => {
    if (!stockMap.has(pid)) {
      stockMap.set(pid, val);
    }
  });

  return stockMap;
}

/**
 * Mirrors Android `PmpViewModel.factoryItemBalances`:
 * Aggregates `factory_moves` per `itemId`.
 */
export function computeFactoryItemBalances(
  items: Pick<FactoryItem, 'id' | 'actualBalance'>[],
  moves: Pick<FactoryMove, 'itemId' | 'kind' | 'qty'>[]
): Map<number, number> {
  const uniqueMoves = deduplicateCollectionRecords('factory_moves', moves as any[]);
  const moveSums = new Map<number, number>();
  const moveCounts = new Map<number, number>();

  uniqueMoves.forEach((fm) => {
    const itemId = Number(fm.itemId);
    if (!itemId) return;
    const qty = Number(fm.qty) || 0;
    const k = (fm.kind || '').trim();
    const isAddition =
      k === 'RAW_PURCHASE' ||
      k === 'PURCHASE' ||
      k === 'IN' ||
      k === 'RETURN' ||
      k.includes('توريد') ||
      k.includes('شراء') ||
      k.includes('إضافة') ||
      k.includes('وارد') ||
      k.includes('مرتجع');
    const delta = isAddition ? qty : -qty;
    moveSums.set(itemId, (moveSums.get(itemId) || 0) + delta);
    moveCounts.set(itemId, (moveCounts.get(itemId) || 0) + 1);
  });

  const result = new Map<number, number>();
  items.forEach((item) => {
    const id = Number(item.id);
    const hasMoves = (moveCounts.get(id) || 0) > 0;
    result.set(id, hasMoves ? (moveSums.get(id) || 0) : (Number(item.actualBalance) || 0));
  });

  moveSums.forEach((val, id) => {
    if (!result.has(id)) {
      result.set(id, val);
    }
  });

  return result;
}

/**
 * Computes invoice paid & remaining balance from `invoice_payments` + embedded `invoice.payments`.
 */
export function computeInvoiceFinancials(
  invoice: SalesInvoice,
  externalPayments: InvoicePayment[]
): { paidAmount: number; remainingAmount: number; statusLabel: 'مدفوعة' | 'سداد جزئي' | 'غير مدفوعة' } {
  const invTotal = Number(invoice.total ?? invoice.finalAmount ?? 0);
  const embedded = Array.isArray(invoice.payments) ? invoice.payments : [];
  const matchingExternal = externalPayments.filter((p) => Number(p.invoiceId) === Number(invoice.id));

  const byId = new Map<number, InvoicePayment>();
  [...embedded, ...matchingExternal].forEach((p, idx) => {
    if ((p.status || 'active') === 'reversed') return;
    const key = Number(p.id) || idx + 1;
    byId.set(key, p);
  });

  const computedPaid =
    byId.size > 0
      ? Array.from(byId.values()).reduce((acc, p) => acc + (Number(p.amount) || 0), 0)
      : Number(invoice.paidAmount) || 0;

  const remaining = Math.max(0, invTotal - computedPaid);
  const statusLabel: 'مدفوعة' | 'سداد جزئي' | 'غير مدفوعة' =
    remaining <= 0.01 && invTotal > 0
      ? 'مدفوعة'
      : computedPaid > 0
      ? 'سداد جزئي'
      : 'غير مدفوعة';

  return { paidAmount: computedPaid, remainingAmount: remaining, statusLabel };
}

export function computeCustomerDebts(
  customers: Customer[],
  invoices: SalesInvoice[],
  payments: InvoicePayment[]
): Map<number, { totalInvoiced: number; totalPaid: number; remaining: number; currency: CurrencyCode; primaryCurrency: CurrencyCode }> {
  const map = new Map<number, { totalInvoiced: number; totalPaid: number; remaining: number; currency: CurrencyCode; primaryCurrency: CurrencyCode }>();
  const uniqueInvoices = deduplicateCollectionRecords('invoices', invoices);

  customers.forEach((c) => {
    const cur = (c.currency || 'SDG') as CurrencyCode;
    map.set(Number(c.id), {
      totalInvoiced: 0,
      totalPaid: 0,
      remaining: 0,
      currency: cur,
      primaryCurrency: cur
    });
  });

  uniqueInvoices.forEach((inv) => {
    if ((inv.status || 'active') === 'cancelled') return;
    const cid = Number(inv.customerId);
    const fin = computeInvoiceFinancials(inv, payments);
    const invTotal = Number(inv.total ?? inv.finalAmount ?? 0);
    const cur = (inv.currency || 'SDG') as CurrencyCode;
    const existing = map.get(cid) || {
      totalInvoiced: 0,
      totalPaid: 0,
      remaining: 0,
      currency: cur,
      primaryCurrency: cur
    };
    existing.totalInvoiced += invTotal;
    existing.totalPaid += fin.paidAmount;
    existing.remaining += fin.remainingAmount;
    existing.currency = cur;
    existing.primaryCurrency = cur;
    map.set(cid, existing);
  });

  return map;
}

export function computeSupplierBalances(
  suppliers: Supplier[],
  purchases: PurchaseInvoice[]
): Map<number, { totalPurchases: number; totalPaid: number; remaining: number; currency: CurrencyCode }> {
  const map = new Map<number, { totalPurchases: number; totalPaid: number; remaining: number; currency: CurrencyCode }>();
  const uniquePurchases = deduplicateCollectionRecords('purchases', purchases);

  suppliers.forEach((s) => {
    map.set(Number(s.id), {
      totalPurchases: 0,
      totalPaid: 0,
      remaining: 0,
      currency: (s.currency || 'SDG') as CurrencyCode
    });
  });

  uniquePurchases.forEach((po) => {
    const sid = Number(po.supplierId);
    const total = Number(po.total ?? po.totalAmount ?? 0);
    const paid = Number(po.paidAmount ?? (po.paymentStatus === 'مدفوعة' ? total : 0));
    const rem = Math.max(0, total - paid);
    const existing = map.get(sid) || {
      totalPurchases: 0,
      totalPaid: 0,
      remaining: 0,
      currency: (po.currency || 'SDG') as CurrencyCode
    };
    existing.totalPurchases += total;
    existing.totalPaid += paid;
    existing.remaining += rem;
    map.set(sid, existing);
  });

  return map;
}
