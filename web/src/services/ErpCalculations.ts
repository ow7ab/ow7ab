import {
  Cashbox,
  TreasuryMovement,
  Customer,
  SalesInvoice,
  InvoicePayment,
  Product,
  StockMove,
  FactoryItem,
  FactoryMove,
  CurrencyCode
} from '../types/erp';

export interface ComputedCashboxBalance {
  id: number;
  docId: string;
  name: string;
  currency: CurrencyCode;
  balance: number;
  branch: string;
}

export function inferCashboxCurrency(name: string): CurrencyCode {
  const n = (name || '').toLowerCase();
  if (n.includes('مصر') || n.includes('egp')) return 'EGP';
  if (n.includes('دولار') || n.includes('usd')) return 'USD';
  return 'SDG';
}

export function inferCashboxBranch(name: string): string {
  const n = name || '';
  if (n.includes('مصر')) return 'EG-001 (مصنع مصر)';
  if (n.includes('حلفا')) return 'HF-001 (حلفا)';
  if (n.includes('عطبرة')) return 'AT-002 (عطبرة)';
  if (n.includes('دولار')) return 'التصدير والاستيراد';
  return 'SD-001 (السودان)';
}

/**
 * Mirrors Kotlin `String.hashCode().toUInt().toString(16)` for deterministic cross-platform IDs.
 */
export function javaHashHex(input: string): string {
  let h = 0;
  for (let i = 0; i < input.length; i++) {
    h = (Math.imul(31, h) + input.charCodeAt(i)) | 0;
  }
  return (h >>> 0).toString(16);
}

/**
 * Mirrors `CloudErpSyncRepository.kt#safeId` producing Firestore-rule-compliant ASCII IDs (`^[a-zA-Z0-9_\-]+$`).
 */
export function safeId(raw: string, fallback: string): string {
  const cleaned = (raw || '')
    .trim()
    .replace(/[^a-zA-Z0-9_\-]/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-+|-+$/g, '');
  return cleaned.length > 0 ? cleaned.slice(0, 100) : fallback;
}

/**
 * Mirrors `DeletedRecordsRegistry.kt#cashboxDocId`.
 */
export function cashboxDocId(name: string, fallbackId: number = 0): string {
  const cleanName = (name || '').trim().toLowerCase();
  if (!cleanName) return `BOX-${fallbackId}`;
  const asciiPart = cleanName
    .replace(/[^a-zA-Z0-9_\-]/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-+|-+$/g, '');
  const hashHex = javaHashHex(cleanName);
  return asciiPart.length > 0 ? `BOX-${asciiPart.slice(0, 32)}-${hashHex}` : `BOX-H${hashHex}`;
}

/**
 * Deterministic ASCII-safe document ID for `poster_prices` while preserving the Arabic `id` field inside the doc.
 */
export function posterPriceDocId(weight: string, kind: string, currency: CurrencyCode): string {
  const canonical = currency === 'EGP' ? `${weight}_${kind}_EGP` : `${weight}_${kind}`;
  const ascii = safeId(canonical, '');
  const hashHex = javaHashHex(canonical.toLowerCase());
  return ascii && !ascii.includes('-') ? ascii : `PP-${weight}-${currency}-${hashHex}`;
}

export function computeCashboxBalances(
  cashboxes: Cashbox[],
  movements: TreasuryMovement[]
): ComputedCashboxBalance[] {
  const map = new Map<string, ComputedCashboxBalance>();

  for (const cb of cashboxes) {
    const name = (cb.name || '').trim();
    if (!name) continue;
    const currency = (cb.currency || inferCashboxCurrency(name)) as CurrencyCode;
    const key = `${name}__${currency}`;
    if (!map.has(key)) {
      map.set(key, {
        id: Number(cb.id) || 0,
        docId: cashboxDocId(name, Number(cb.id) || 0),
        name,
        currency,
        balance: 0,
        branch: cb.branch || inferCashboxBranch(name)
      });
    }
  }

  for (const mov of movements) {
    const name = (mov.cashbox || mov.cashboxName || '').trim();
    if (!name) continue;
    const currency = (mov.currency || inferCashboxCurrency(name)) as CurrencyCode;
    const key = `${name}__${currency}`;
    if (!map.has(key)) {
      map.set(key, {
        id: 0,
        docId: cashboxDocId(name, 0),
        name,
        currency,
        balance: 0,
        branch: inferCashboxBranch(name)
      });
    }
    const entry = map.get(key)!;
    const isIncome =
      mov.direction === 'وارد' || mov.type === 'INCOME' || mov.direction === ('IN' as any);
    const amt = Number(mov.amount) || 0;
    entry.balance += isIncome ? amt : -amt;
  }

  return Array.from(map.values());
}

export function computeProductStocks(products: Product[], stockMoves: StockMove[]): Map<number, number> {
  const map = new Map<number, number>();
  for (const p of products) {
    map.set(Number(p.id), 0);
  }
  const positiveKinds = new Set(['IN', 'RETURN', 'RETURN_IN', 'CANCEL', 'وارد', 'مرتجع']);
  for (const sm of stockMoves) {
    const pid = Number(sm.productId);
    if (!Number.isFinite(pid)) continue;
    const qty = Number(sm.quantity) || 0;
    const isPlus =
      positiveKinds.has(String(sm.kind || '').trim().toUpperCase()) ||
      positiveKinds.has(String(sm.kind || '').trim());
    map.set(pid, (map.get(pid) || 0) + (isPlus ? qty : -qty));
  }
  return map;
}

export function computeFactoryItemBalances(items: FactoryItem[], moves: FactoryMove[]): Map<number, number> {
  const map = new Map<number, number>();
  for (const item of items) {
    map.set(Number(item.id), 0);
  }
  for (const m of moves) {
    const itemId = Number(m.itemId);
    if (!Number.isFinite(itemId)) continue;
    const qty = Number(m.qty) || 0;
    const kind = String(m.kind || '').trim();
    const isIn = kind === 'توريد خامات' || kind === 'إنتاج تام' || kind.includes('توريد');
    map.set(itemId, (map.get(itemId) || 0) + (isIn ? qty : -qty));
  }
  return map;
}

export function computeInvoiceFinancials(
  invoice: SalesInvoice,
  externalPayments: InvoicePayment[]
): { paidAmount: number; remainingAmount: number; statusLabel: 'مدفوعة' | 'سداد جزئي' | 'غير مدفوعة' | 'ملغاة' } {
  if (invoice.status === 'cancelled') {
    return { paidAmount: 0, remainingAmount: 0, statusLabel: 'ملغاة' };
  }
  const total = Number(invoice.total ?? invoice.finalAmount ?? 0);
  const mergedPayments = new Map<number, number>();

  if (Array.isArray(invoice.payments)) {
    for (const p of invoice.payments) {
      if (p.status !== 'reversed') {
        mergedPayments.set(Number(p.id), Number(p.amount) || 0);
      }
    }
  }
  for (const p of externalPayments) {
    if (Number(p.invoiceId) === Number(invoice.id) && p.status !== 'reversed') {
      mergedPayments.set(Number(p.id), Number(p.amount) || 0);
    }
  }

  let paid = 0;
  mergedPayments.forEach((amt) => {
    paid += amt;
  });
  if (paid === 0 && typeof invoice.paidAmount === 'number' && invoice.paidAmount > 0) {
    paid = invoice.paidAmount;
  }

  const remaining = Math.max(0, total - paid);
  const statusLabel =
    remaining <= 0 && total > 0 ? 'مدفوعة' : paid > 0 ? 'سداد جزئي' : 'غير مدفوعة';
  return { paidAmount: paid, remainingAmount: remaining, statusLabel };
}

export function computeCustomerDebts(
  customers: Customer[],
  invoices: SalesInvoice[],
  payments: InvoicePayment[]
): Map<number, { totalInvoiced: number; totalPaid: number; remaining: number; primaryCurrency: CurrencyCode }> {
  const result = new Map<number, { totalInvoiced: number; totalPaid: number; remaining: number; primaryCurrency: CurrencyCode }>();
  for (const c of customers) {
    result.set(Number(c.id), { totalInvoiced: 0, totalPaid: 0, remaining: 0, primaryCurrency: 'SDG' });
  }
  for (const inv of invoices) {
    if (inv.status === 'cancelled') continue;
    const cid = Number(inv.customerId);
    if (!result.has(cid)) {
      result.set(cid, { totalInvoiced: 0, totalPaid: 0, remaining: 0, primaryCurrency: inv.currency || 'SDG' });
    }
    const fin = computeInvoiceFinancials(inv, payments);
    const entry = result.get(cid)!;
    entry.totalInvoiced += Number(inv.total ?? inv.finalAmount ?? 0);
    entry.totalPaid += fin.paidAmount;
    entry.remaining += fin.remainingAmount;
    if (inv.currency) entry.primaryCurrency = inv.currency;
  }
  return result;
}
