import {
  Cashbox,
  CurrencyCode,
  Customer,
  FactoryItem,
  FactoryMove,
  InvoicePayment,
  Product,
  SalesInvoice,
  StockMove,
  TreasuryMovement,
  Warehouse
} from '../types/erp';

export function safeId(raw: string, fallback: string): string {
  const cleaned = String(raw ?? '')
    .trim()
    .replace(/[^a-zA-Z0-9_-]/g, '-')
    .replace(/^-+|-+$/g, '');
  return cleaned.length > 0 ? cleaned.slice(0, 100) : fallback;
}

export function cashboxDocId(name: string, id: number): string {
  const map: Record<string, string> = {
    'خزنة الخرطوم': 'CB-KHARTOUM',
    'خزنة السودان': 'CB-SUDAN',
    'خزنة مصر': 'CB-EGYPT',
    'خزنة القاهرة': 'CB-CAIRO',
    'خزنة حلفا': 'CB-HALFA',
    'مخزن عطبرة': 'CB-ATBARA',
    'خزنة الدولار': 'CB-USD'
  };
  const trimmed = String(name || '').trim();
  if (map[trimmed]) return map[trimmed];
  return safeId(`CB-${id || 1}`, `CB-${id || 1}`);
}

export function posterPriceDocId(weight: string, kind: string, currency: CurrencyCode = 'SDG'): string {
  const w = safeId(weight, '900');
  const k = encodeURIComponent(String(kind || '').trim()).replace(/%/g, '_');
  const suffix = currency === 'EGP' ? '_EGP' : '';
  return safeId(`PP_${w}_${k}${suffix}`, `PP_${w}_${currency}`);
}

export function normalizeArabicText(value: unknown): string {
  return String(value ?? '')
    .trim()
    .replace(/\s+/g, ' ')
    .replace(/[أإآ]/g, 'ا')
    .replace(/ة/g, 'ه')
    .replace(/ى/g, 'ي')
    .toLowerCase();
}

export function normalizeEntityKey(value: unknown): string {
  return normalizeArabicText(value).replace(/[^a-z0-9\u0600-\u06ff]/g, '');
}

export function resolveCanonicalCurrency(rawCurrency: unknown, fallbackName?: unknown): CurrencyCode {
  const clean = String(rawCurrency ?? '').trim().toUpperCase();
  if (clean === 'USD' || clean.includes('دولار') || clean.includes('$')) return 'USD';
  if (clean === 'EGP' || clean.includes('مصري')) return 'EGP';
  if (clean === 'SDG' || clean.includes('سودان')) return 'SDG';

  const nameHint = String(fallbackName ?? '').trim().toUpperCase();
  if (nameHint.includes('USD') || nameHint.includes('دولار')) return 'USD';
  if (nameHint.includes('EGP') || nameHint.includes('مصري') || nameHint.includes('قاهر') || nameHint.includes('مصر')) {
    return 'EGP';
  }
  return 'SDG';
}

export function isStockInMoveKind(rawKind: unknown): boolean {
  const kind = String(rawKind ?? '').trim().toUpperCase();
  return (
    kind === 'IN' ||
    kind === 'RETURN' ||
    kind === 'RETURN_IN' ||
    kind === 'CANCEL' ||
    kind === 'وارد' ||
    kind === 'مرتجع' ||
    kind === 'توريد' ||
    kind === 'إضافة' ||
    kind === 'اضافة'
  );
}

export function extractBareEntityId(rawId: unknown): string | null {
  const trimmed = String(rawId ?? '').trim();
  if (!trimmed) return null;
  const upper = trimmed.toUpperCase();
  const prefixes = ['MOV-', 'CUST-', 'INV-', 'TRF-', 'PROD-', 'PRD-', 'BOX-', 'WH-', 'SM-', 'FI-', 'FM-'];
  for (const prefix of prefixes) {
    if (upper.startsWith(prefix)) {
      const bare = trimmed.slice(prefix.length).trim();
      return bare.length > 0 ? bare : null;
    }
  }
  return null;
}

export function isPreEpochRecord(createdAtValue: unknown, clusterEpochTimestamp: number): boolean {
  if (!clusterEpochTimestamp || clusterEpochTimestamp <= 0) return false;
  const num = Number(createdAtValue ?? 0);
  if (!Number.isFinite(num) || num < 1_600_000_000_000) return false;
  return num < clusterEpochTimestamp;
}

const EPOCH_PROTECTED_COLLECTIONS = new Set<string>([
  'movements',
  'transfers',
  'daily_closings',
  'invoices',
  'invoice_items',
  'invoice_payments',
  'stock_moves',
  'factory_moves',
  'halfa_invoices',
  'lab_formulations',
  'paint_batches',
  'audit_logs'
]);

export function buildTombstoneKeySet(docs: Array<Record<string, any>>): Map<string, Set<string>> {
  const nextMap = new Map<string, Set<string>>();
  for (const data of docs) {
    if (!data || typeof data !== 'object') continue;
    const col = String(data.collection || '').trim().toLowerCase();
    if (!col) continue;
    let set = nextMap.get(col);
    if (!set) {
      set = new Set<string>();
      nextMap.set(col, set);
    }
    const rawValues: unknown[] = [
      data.docId,
      data._docId,
      data.entityId,
      data.numericId,
      data.reference,
      data.invoiceNo,
      data.syncUuid,
      ...(Array.isArray(data.aliases) ? data.aliases : [])
    ];
    for (const val of rawValues) {
      if (val === undefined || val === null) continue;
      const str = String(val).trim();
      if (!str) continue;
      set.add(str.toLowerCase());
      const norm = normalizeEntityKey(str);
      if (norm) set.add(norm);
      const bare = extractBareEntityId(str);
      if (bare) {
        set.add(bare.toLowerCase());
        const bareNorm = normalizeEntityKey(bare);
        if (bareNorm) set.add(bareNorm);
      }
    }
  }
  return nextMap;
}

export function isRecordTombstoned(
  collectionName: string,
  raw: Record<string, any> | null | undefined,
  tombstoneKeysByCollection: Map<string, Set<string>>,
  clusterEpochTimestamp: number = 0
): boolean {
  if (!raw || typeof raw !== 'object') return false;
  const col = String(collectionName || '').trim().toLowerCase();
  if (!col) return false;

  if (EPOCH_PROTECTED_COLLECTIONS.has(col)) {
    const ts = raw.createdAt ?? raw.timestamp;
    if (isPreEpochRecord(ts, clusterEpochTimestamp)) {
      return true;
    }
  }

  const keys = tombstoneKeysByCollection.get(col);
  if (!keys || keys.size === 0) return false;

  const hasId = raw.id !== undefined && raw.id !== null && String(raw.id).trim() !== '';
  const idStr = hasId ? String(raw.id).trim() : '';

  const candidates: unknown[] = [
    raw._docId,
    raw.docId,
    raw.cloudDocId,
    raw.id,
    raw.syncUuid,
    raw.name,
    raw.userCode,
    raw.invoiceNo,
    raw.invoiceNumber,
    raw.reference,
    col === 'movements' && hasId ? `MOV-${idStr}` : '',
    col === 'transfers' && hasId ? `TRF-${idStr}` : '',
    col === 'invoices' && hasId ? `INV-${idStr}` : '',
    col === 'customers' && hasId ? `CUST-${idStr}` : '',
    col === 'warehouses' && hasId ? `WH-${idStr}` : '',
    col === 'stock_moves' && hasId ? `SM-${idStr}` : '',
    col === 'factory_items' && hasId ? `FI-${idStr}` : '',
    col === 'factory_moves' && hasId ? `FM-${idStr}` : '',
    col === 'products' && hasId ? `PRD-${idStr}` : '',
    col === 'products' && hasId ? `PROD-${idStr}` : '',
    col === 'cashboxes' && hasId ? `BOX-${idStr}` : '',
    col === 'cashboxes' && raw.name ? cashboxDocId(String(raw.name), Number(raw.id || 0)) : ''
  ];

  for (const c of candidates) {
    if (c === undefined || c === null) continue;
    const str = String(c).trim();
    if (!str) continue;
    if (keys.has(str.toLowerCase()) || keys.has(normalizeEntityKey(str))) {
      return true;
    }
    const bare = extractBareEntityId(str);
    if (bare && (keys.has(bare.toLowerCase()) || keys.has(normalizeEntityKey(bare)))) {
      return true;
    }
  }
  return false;
}

/**
 * Deduplicates Firestore collection documents so repeated seeding or legacy sync IDs
 * never produce duplicate rows in UI tables or double-counted KPIs.
 */
export function deduplicateCollectionRecords<T = any>(collectionName: string, items: T[]): T[] {
  if (!Array.isArray(items) || items.length <= 1) return items || [];

  const map = new Map<string, T>();

  for (const item of items) {
    if (!item || typeof item !== 'object') continue;
    const raw = item as Record<string, any>;
    let key = '';

    switch (collectionName) {
      case 'cashboxes': {
        const nameKey = normalizeEntityKey(raw.name || `cashbox_${raw.id}`);
        key = nameKey;
        break;
      }
      case 'warehouses': {
        const nameKey = normalizeEntityKey(raw.name);
        key = nameKey || (raw.id !== undefined && raw.id !== null ? `id:${Number(raw.id)}` : `wh_${raw._docId}`);
        if (key) {
          const existing = map.get(key) as Record<string, any> | undefined;
          const rawIdNum = Number(raw.id);
          const aliasSet = new Set<number>([
            ...(Array.isArray(existing?._aliasIds) ? existing._aliasIds : []),
            ...(Array.isArray(raw._aliasIds) ? raw._aliasIds : [])
          ]);
          if (existing && Number.isFinite(Number(existing.id))) aliasSet.add(Number(existing.id));
          if (Number.isFinite(rawIdNum)) aliasSet.add(rawIdNum);

          const docAliasSet = new Set<string>([
            ...(Array.isArray(existing?._aliasDocIds) ? existing._aliasDocIds : []),
            ...(Array.isArray(raw._aliasDocIds) ? raw._aliasDocIds : [])
          ]);
          if (existing?._docId) docAliasSet.add(String(existing._docId));
          if (raw._docId) docAliasSet.add(String(raw._docId));

          const existingTs = Number(existing?.updatedAt || existing?.timestamp || existing?.createdAt || existing?.id || 0);
          const candidateTs = Number(raw.updatedAt || raw.timestamp || raw.createdAt || raw.id || 0);
          const chosen = !existing || candidateTs >= existingTs ? { ...existing, ...raw } : existing;
          // Preserve lowest canonical numeric ID so stock_moves matching original ID remain primary, while keeping all _aliasIds & _aliasDocIds
          const sortedAliases = Array.from(aliasSet).filter((n) => Number.isFinite(n)).sort((a, b) => a - b);
          map.set(key, {
            ...chosen,
            id: sortedAliases.length > 0 ? sortedAliases[0] : chosen.id,
            _aliasIds: sortedAliases,
            _aliasDocIds: Array.from(docAliasSet)
          } as unknown as T);
          continue;
        }
        break;
      }
      case 'products': {
        if (raw.id !== undefined && raw.id !== null && String(raw.id).trim() !== '') {
          key = `id:${Number(raw.id)}`;
        } else {
          const nameKey = normalizeEntityKey(raw.name);
          const weightKey = normalizeEntityKey(raw.packageWeight);
          const colorKey = normalizeEntityKey(raw.paintColor);
          key = `${nameKey}__${weightKey}__${colorKey}` || `prod_${raw._docId}`;
        }
        break;
      }
      case 'stock_moves': {
        if (raw.id !== undefined && raw.id !== null && String(raw.id).trim() !== '') {
          key = `id:${raw.id}`;
        } else {
          key = `${raw.productId ?? 0}__${raw.warehouseId ?? 0}__${raw.kind ?? ''}__${raw.quantity ?? 0}__${raw.createdAt ?? raw.timestamp ?? 0}`;
        }
        break;
      }
      case 'customers': {
        if (raw.id !== undefined && raw.id !== null && String(raw.id).trim() !== '') {
          key = `id:${Number(raw.id)}`;
        } else {
          const nameKey = normalizeEntityKey(raw.name);
          const phoneKey = String(raw.phone ?? '').replace(/\D/g, '');
          key = nameKey ? `${nameKey}__${phoneKey}` : `cust_${raw._docId}`;
        }
        break;
      }
      case 'factory_items':
      case 'raw_materials': {
        if (raw.id !== undefined && raw.id !== null && String(raw.id).trim() !== '') {
          key = `id:${Number(raw.id)}`;
        } else {
          key = normalizeEntityKey(raw.name) || `fi_${raw._docId}`;
        }
        break;
      }
      case 'user_accounts': {
        const unameKey = normalizeEntityKey(raw.username);
        const codeKey = normalizeEntityKey(raw.userCode);
        key = unameKey || codeKey || `usr_${raw.id ?? raw._docId}`;
        break;
      }
      case 'invoices': {
        const invKey = normalizeEntityKey(raw.invoiceNo || raw.invoiceNumber);
        key = invKey || `inv_${raw.id ?? raw._docId}`;
        break;
      }
      case 'movements': {
        const uuid = String(raw.syncUuid ?? '').trim();
        const ref = normalizeEntityKey(raw.reference);
        const dir = String(raw.direction || raw.type || '').trim().toUpperCase();
        const boxKey = normalizeEntityKey(raw.cashbox || raw.cashboxName);
        const curr = resolveCanonicalCurrency(raw.currency, raw.cashbox || raw.cashboxName);
        const idPart = raw.id !== undefined && raw.id !== null ? String(raw.id).trim() : '';
        if (uuid) {
          key = `uuid:${uuid}`;
        } else if (ref && !ref.startsWith('mov')) {
          key = `ref:${ref}__${boxKey}__${dir}__${curr}__${Number(raw.amount || 0)}`;
        } else if (idPart) {
          key = `id:${idPart}`;
        } else {
          key = `${boxKey}__${dir}__${Number(raw.amount || 0)}__${normalizeEntityKey(raw.description || raw.note)}__${raw.createdAt || raw.timestamp || 0}`;
        }
        break;
      }
      case 'inventory_movements': {
        key = raw.syncUuid
          ? `uuid:${String(raw.syncUuid).trim()}`
          : raw.idempotencyKey
          ? `idemp:${String(raw.idempotencyKey).trim()}`
          : `doc:${raw._docId ?? raw.id}`;
        break;
      }
      case 'warehouse_stock_balances': {
        key = raw.syncUuid
          ? `uuid:${String(raw.syncUuid).trim()}`
          : `bal:${raw.warehouseId ?? 0}__${raw.itemCategory ?? ''}__${raw.itemId ?? 0}__${String(raw.batchNumber ?? '').trim()}`;
        break;
      }
      case 'warehouse_transfers': {
        key = raw.transferNo
          ? `trf:${String(raw.transferNo).trim()}`
          : raw.syncUuid
          ? `uuid:${String(raw.syncUuid).trim()}`
          : `doc:${raw._docId ?? raw.id}`;
        break;
      }
      case 'warehouse_transfer_items': {
        key = raw.transferUuid
          ? `whti:${String(raw.transferUuid)}__${raw.itemCategory ?? ''}__${raw.itemId ?? 0}__${String(raw.batchNumber ?? '').trim()}`
          : `doc:${raw._docId ?? raw.id}`;
        break;
      }
      case 'packaging_rules': {
        key = raw.weightGrams !== undefined && raw.weightGrams !== null
          ? `weight:${Number(raw.weightGrams)}`
          : `uuid:${raw.syncUuid ?? raw._docId}`;
        break;
      }
      case 'purchase_receipts': {
        key = raw.grnNo
          ? `grn:${String(raw.grnNo).trim()}`
          : raw.syncUuid
          ? `uuid:${String(raw.syncUuid).trim()}`
          : `doc:${raw._docId ?? raw.id}`;
        break;
      }
      case 'production_orders': {
        key = raw.orderNo
          ? `po:${String(raw.orderNo).trim()}`
          : raw.syncUuid
          ? `uuid:${String(raw.syncUuid).trim()}`
          : `doc:${raw._docId ?? raw.id}`;
        break;
      }
      case 'qc_inspections': {
        key = raw.qcNo
          ? `qc:${String(raw.qcNo).trim()}`
          : raw.syncUuid
          ? `uuid:${String(raw.syncUuid).trim()}`
          : `doc:${raw._docId ?? raw.id}`;
        break;
      }
      case 'accounting_accounts': {
        key = raw.accountCode
          ? `acc:${String(raw.accountCode).trim()}`
          : raw.syncUuid
          ? `uuid:${String(raw.syncUuid).trim()}`
          : `doc:${raw._docId ?? raw.id}`;
        break;
      }
      case 'journal_entries': {
        key = raw.syncUuid
          ? `jrn:${String(raw.syncUuid).trim()}`
          : raw.entryNo
          ? `jno:${String(raw.entryNo).trim()}`
          : `doc:${raw._docId ?? raw.id}`;
        break;
      }
      case 'journal_lines': {
        key = raw.syncUuid
          ? `jln:${String(raw.syncUuid).trim()}`
          : `jln:${raw.journalEntryUuid ?? raw.journalEntryId ?? ''}__${raw.lineOrder ?? 0}__${raw.accountCode ?? ''}`;
        break;
      }
      default: {
        key =
          raw.id !== undefined && raw.id !== null
            ? `id:${raw.id}`
            : raw._docId
            ? `doc:${raw._docId}`
            : JSON.stringify(raw);
      }
    }

    const existing = map.get(key) as Record<string, any> | undefined;
    if (!existing) {
      map.set(key, item);
    } else {
      const existingTs = Number(existing.updatedAt || existing.timestamp || existing.createdAt || 0);
      const candidateTs = Number(raw.updatedAt || raw.timestamp || raw.createdAt || 0);
      if (candidateTs >= existingTs) {
        map.set(key, { ...existing, ...raw } as T);
      }
    }
  }

  return Array.from(map.values());
}

export interface DistinctCashboxSummary {
  id: number;
  name: string;
  branch: string;
  active: boolean;
  primaryCurrency: CurrencyCode;
  primaryBalance: number;
  balance?: number;
  balances: Record<CurrencyCode, number>;
}

function isMovementInflow(mv: Partial<TreasuryMovement> & Record<string, any>): boolean {
  const dir = String(mv.direction ?? mv.type ?? '').trim().toUpperCase();
  return (
    dir === 'وارد' ||
    dir === 'IN' ||
    dir === 'INCOME' ||
    dir === 'DEPOSIT' ||
    dir === 'قبض' ||
    dir === 'توريد' ||
    dir === 'إيداع'
  );
}

/**
 * Deduplicates cashboxes by normalized name and computes multi-currency balances (SDG, EGP, USD)
 * from `movements` matching Android `PmpDao.kt` (`getAggregatedCashboxBalances`).
 */
export function computeDistinctCashboxesWithBalances(
  cashboxes: Cashbox[],
  movements: TreasuryMovement[]
): DistinctCashboxSummary[] {
  const uniqueMovements = deduplicateCollectionRecords('movements', movements);
  const grouped = new Map<
    string,
    {
      id: number;
      name: string;
      branch: string;
      active: boolean;
      primaryCurrency: CurrencyCode;
      seedBalances: Record<CurrencyCode, number>;
      computedBalances: Record<CurrencyCode, number>;
      hasMoves: Record<CurrencyCode, boolean>;
    }
  >();

  for (const box of cashboxes) {
    if (!box || !box.name) continue;
    const nameKey = normalizeEntityKey(box.name);
    if (!nameKey) continue;
    const curr = resolveCanonicalCurrency(box.currency, box.name);

    const existing = grouped.get(nameKey);
    if (!existing) {
      const seedBalances: Record<CurrencyCode, number> = { SDG: 0, EGP: 0, USD: 0 };
      seedBalances[curr] = Number(box.balance || 0);
      grouped.set(nameKey, {
        id: Number(box.id || Date.now()),
        name: String(box.name).trim(),
        branch: box.branch || 'SD-001',
        active: box.active !== false,
        primaryCurrency: curr,
        seedBalances,
        computedBalances: { SDG: 0, EGP: 0, USD: 0 },
        hasMoves: { SDG: false, EGP: false, USD: false }
      });
    } else {
      existing.seedBalances[curr] = Math.max(existing.seedBalances[curr], Number(box.balance || 0));
    }
  }

  for (const mv of uniqueMovements) {
    if (!mv) continue;
    const rawName = String(mv.cashbox || mv.cashboxName || '').trim();
    const nameKey = normalizeEntityKey(rawName);
    if (!nameKey) continue;
    const curr = resolveCanonicalCurrency(mv.currency, rawName);

    let entry = grouped.get(nameKey);
    if (!entry) {
      entry = {
        id: Number(mv.id || Date.now()),
        name: rawName,
        branch: 'SD-001',
        active: true,
        primaryCurrency: curr,
        seedBalances: { SDG: 0, EGP: 0, USD: 0 },
        computedBalances: { SDG: 0, EGP: 0, USD: 0 },
        hasMoves: { SDG: false, EGP: false, USD: false }
      };
      grouped.set(nameKey, entry);
    }

    entry.hasMoves[curr] = true;
    const amt = Number(mv.amount || 0);
    if (isMovementInflow(mv)) {
      entry.computedBalances[curr] += amt;
    } else {
      entry.computedBalances[curr] -= amt;
    }
  }

  const out: DistinctCashboxSummary[] = [];
  for (const entry of grouped.values()) {
    const finalBalances: Record<CurrencyCode, number> = {
      SDG: entry.hasMoves.SDG ? entry.computedBalances.SDG : entry.seedBalances.SDG,
      EGP: entry.hasMoves.EGP ? entry.computedBalances.EGP : entry.seedBalances.EGP,
      USD: entry.hasMoves.USD ? entry.computedBalances.USD : entry.seedBalances.USD
    };
    const primaryBalance = finalBalances[entry.primaryCurrency] || 0;
    out.push({
      id: entry.id,
      name: entry.name,
      branch: entry.branch,
      active: entry.active,
      primaryCurrency: entry.primaryCurrency,
      primaryBalance,
      balance: primaryBalance,
      balances: finalBalances
    });
  }

  return out;
}

export function computeCashboxBalances(
  cashboxes: Cashbox[],
  movements: TreasuryMovement[]
): Cashbox[] {
  const summaries = computeDistinctCashboxesWithBalances(cashboxes, movements);
  return summaries.map((s) => ({
    id: s.id,
    name: s.name,
    branch: s.branch,
    currency: s.primaryCurrency,
    balance: s.primaryBalance,
    active: s.active
  }));
}

export function computeCashboxBalancesByCurrency(
  cashboxes: Cashbox[],
  movements: TreasuryMovement[]
): Record<CurrencyCode, number> {
  const summaries = computeDistinctCashboxesWithBalances(cashboxes, movements);
  const totals: Record<CurrencyCode, number> = { SDG: 0, EGP: 0, USD: 0 };
  for (const s of summaries) {
    totals.SDG += Number(s.balances.SDG || 0);
    totals.EGP += Number(s.balances.EGP || 0);
    totals.USD += Number(s.balances.USD || 0);
  }
  return totals;
}

/**
 * Builds a Set of all numeric IDs belonging to a warehouse (including merged duplicate IDs).
 */
export function resolveWarehouseFilterIds(
  warehouseIdFilter?: number | null,
  warehouses?: Array<Warehouse & { _aliasIds?: number[] }>
): Set<number> | null {
  if (warehouseIdFilter === undefined || warehouseIdFilter === null || Number(warehouseIdFilter) <= 0) {
    return null;
  }
  const targetId = Number(warehouseIdFilter);
  const ids = new Set<number>([targetId]);
  if (Array.isArray(warehouses)) {
    for (const w of warehouses) {
      const aliases: number[] = Array.isArray((w as any)._aliasIds)
        ? (w as any)._aliasIds
        : [Number(w.id)];
      if (Number(w.id) === targetId || aliases.includes(targetId)) {
        aliases.forEach((id) => {
          if (Number.isFinite(id)) ids.add(id);
        });
      }
    }
  }
  return ids;
}

/**
 * Resolves a single canonical warehouse ID for any alias ID (so moves across merged duplicate warehouse docs combine under one ID).
 */
function buildCanonicalWarehouseIdMap(
  warehouses?: Array<Warehouse & { _aliasIds?: number[] }>
): Map<number, number> {
  const map = new Map<number, number>();
  if (!Array.isArray(warehouses)) return map;
  for (const w of warehouses) {
    const canonical = Number(w.id);
    if (!Number.isFinite(canonical)) continue;
    map.set(canonical, canonical);
    const aliases: number[] = Array.isArray((w as any)._aliasIds) ? (w as any)._aliasIds : [];
    for (const a of aliases) {
      if (Number.isFinite(a)) map.set(Number(a), canonical);
    }
  }
  return map;
}

/**
 * Computes product stock balances as a `Map<number, number>` (`productId -> netQuantity`)
 * reconciled identically with per-warehouse totals:
 * - Only considers stock moves belonging to existing products (when `products` is non-empty) so deleted-product orphan moves never inflate stock.
 * - Normalizes merged duplicate warehouse IDs so alias IDs aggregate together cleanly.
 * - Clamped `>= 0` per `(canonicalWarehouseId, productId)`.
 */
export function computeProductStocks(
  products: Array<Partial<Product> & { id: number }>,
  stockMoves: Array<Partial<StockMove> & { productId: number; kind: string; quantity: number }>,
  warehouseIdFilter?: number | null,
  warehouses?: Array<Warehouse & { _aliasIds?: number[] }>
): Map<number, number> {
  const uniqueProducts = deduplicateCollectionRecords('products', products);
  const uniqueMoves = deduplicateCollectionRecords('stock_moves', stockMoves);
  const uniqueWarehouses = Array.isArray(warehouses)
    ? deduplicateCollectionRecords('warehouses', warehouses)
    : undefined;

  const knownProductIds = new Set<number>(uniqueProducts.map((p) => Number(p.id)).filter(Number.isFinite));
  const filterIdSet = resolveWarehouseFilterIds(warehouseIdFilter, uniqueWarehouses);
  const canonicalWhMap = buildCanonicalWarehouseIdMap(uniqueWarehouses);

  const filteredMoves = uniqueMoves.filter((sm) => {
    const pid = Number(sm.productId);
    if (!Number.isFinite(pid)) return false;
    if (knownProductIds.size > 0 && !knownProductIds.has(pid)) return false;
    if (filterIdSet !== null && !filterIdSet.has(Number(sm.warehouseId))) return false;
    return true;
  });

  // Group deltas by (productId -> (canonicalWarehouseId -> { inQty, outQty }))
  const byProdWarehouse = new Map<number, Map<number, { inQty: number; outQty: number }>>();

  for (const sm of filteredMoves) {
    const pid = Number(sm.productId);
    const rawWhId = Number(sm.warehouseId || 1);
    const canonicalWhId = canonicalWhMap.get(rawWhId) ?? rawWhId;
    const qty = Math.abs(Number(sm.quantity || 0));
    if (!Number.isFinite(qty) || qty <= 0) continue;

    let whMap = byProdWarehouse.get(pid);
    if (!whMap) {
      whMap = new Map();
      byProdWarehouse.set(pid, whMap);
    }
    const cur = whMap.get(canonicalWhId) || { inQty: 0, outQty: 0 };
    if (isStockInMoveKind(sm.kind)) {
      cur.inQty += qty;
    } else {
      cur.outQty += qty;
    }
    whMap.set(canonicalWhId, cur);
  }

  const sumProductNetAcrossWarehouses = (pid: number): number => {
    const whMap = byProdWarehouse.get(pid);
    if (!whMap) return 0;
    let totalNet = 0;
    for (const stats of whMap.values()) {
      totalNet += Math.max(0, stats.inQty - stats.outQty);
    }
    return totalNet;
  };

  const result = new Map<number, number>();

  for (const p of uniqueProducts) {
    const pid = Number(p.id);
    const hasWhMoves = byProdWarehouse.has(pid);
    const net = hasWhMoves
      ? sumProductNetAcrossWarehouses(pid)
      : filterIdSet === null && uniqueMoves.length === 0
      ? Math.max(0, Number(p.currentStock || 0))
      : 0;
    result.set(pid, net);
  }

  if (knownProductIds.size === 0) {
    for (const pid of byProdWarehouse.keys()) {
      if (!result.has(pid)) {
        result.set(pid, sumProductNetAcrossWarehouses(pid));
      }
    }
  }

  return result;
}

/**
 * Computes `{ inQty, outQty, netStock }` for each product ID and merged signature group
 * so warehouse table inflow/outflow columns always reconcile 1:1 with `currentStock`.
 */
export function computeProductMoveBreakdown(
  products: Product[],
  stockMoves: StockMove[],
  warehouseIdFilter?: number | null,
  warehouses?: Array<Warehouse & { _aliasIds?: number[] }>
): Map<number, { inQty: number; outQty: number; netStock: number }> {
  const uniqueProducts = deduplicateCollectionRecords('products', products);
  const uniqueMoves = deduplicateCollectionRecords('stock_moves', stockMoves);
  const uniqueWarehouses = Array.isArray(warehouses)
    ? deduplicateCollectionRecords('warehouses', warehouses)
    : undefined;
  const stockMap = computeProductStocks(uniqueProducts, uniqueMoves, warehouseIdFilter, uniqueWarehouses);

  const knownProductIds = new Set<number>(uniqueProducts.map((p) => Number(p.id)).filter(Number.isFinite));
  const filterIdSet = resolveWarehouseFilterIds(warehouseIdFilter, uniqueWarehouses);

  const filteredMoves = uniqueMoves.filter((sm) => {
    const pid = Number(sm.productId);
    if (knownProductIds.size > 0 && !knownProductIds.has(pid)) return false;
    if (filterIdSet !== null && !filterIdSet.has(Number(sm.warehouseId))) return false;
    return true;
  });

  const rawBreakdown = new Map<number, { inQty: number; outQty: number }>();
  for (const m of filteredMoves) {
    const pid = Number(m.productId);
    const qty = Math.abs(Number(m.quantity || 0));
    if (!Number.isFinite(qty) || qty <= 0) continue;
    const cur = rawBreakdown.get(pid) || { inQty: 0, outQty: 0 };
    if (isStockInMoveKind(m.kind)) cur.inQty += qty;
    else cur.outQty += qty;
    rawBreakdown.set(pid, cur);
  }

  // Merge breakdown sums across duplicate signature IDs matching computeProductStocksList
  const sigPrimaryId = new Map<string, number>();
  const mergedTotals = new Map<number, { inQty: number; outQty: number; netStock: number }>();

  for (const p of uniqueProducts) {
    const pid = Number(p.id);
    const sig = `${normalizeEntityKey(p.name)}__${normalizeEntityKey(p.paintColor)}__${normalizeEntityKey(p.packageWeight)}`;
    const targetId = sigPrimaryId.get(sig) ?? pid;
    if (!sigPrimaryId.has(sig)) sigPrimaryId.set(sig, pid);

    const own = rawBreakdown.get(pid) || { inQty: 0, outQty: 0 };
    const prev = mergedTotals.get(targetId) || { inQty: 0, outQty: 0, netStock: 0 };
    mergedTotals.set(targetId, {
      inQty: prev.inQty + own.inQty,
      outQty: prev.outQty + own.outQty,
      netStock: prev.netStock + (stockMap.get(pid) ?? 0)
    });
  }

  return mergedTotals;
}

/**
 * Returns `Product[]` with `currentStock` populated from `computeProductStocks(products, stockMoves, warehouseIdFilter, warehouses)`.
 */
export function computeProductStocksList(
  products: Product[],
  stockMoves: StockMove[],
  warehouseIdFilter?: number | null,
  warehouses?: Array<Warehouse & { _aliasIds?: number[] }>
): Product[] {
  const uniqueProducts = deduplicateCollectionRecords('products', products);
  const stockMap = computeProductStocks(uniqueProducts, stockMoves, warehouseIdFilter, warehouses);

  const mergedBySignature = new Map<string, Product>();
  for (const p of uniqueProducts) {
    const pid = Number(p.id);
    const computedStock = stockMap.get(pid) ?? 0;
    const sig = `${normalizeEntityKey(p.name)}__${normalizeEntityKey(p.paintColor)}__${normalizeEntityKey(p.packageWeight)}`;
    const existing = mergedBySignature.get(sig);
    if (!existing) {
      mergedBySignature.set(sig, {
        ...p,
        currentStock: computedStock
      });
    } else {
      mergedBySignature.set(sig, {
        ...existing,
        currentStock: Number(existing.currentStock || 0) + computedStock
      });
    }
  }

  return Array.from(mergedBySignature.values());
}

export interface WarehouseStockSummaryItem {
  warehouse: Warehouse & { _aliasIds?: number[] };
  inQty: number;
  outQty: number;
  netCartons: number;
  distinctProductsCount: number;
  totalValueSdg: number;
}

export function computeWarehouseStockSummary(
  warehouses: Warehouse[],
  products: Product[],
  stockMoves: StockMove[]
): WarehouseStockSummaryItem[] {
  const uniqueWarehouses = deduplicateCollectionRecords('warehouses', warehouses) as Array<
    Warehouse & { _aliasIds?: number[] }
  >;
  const uniqueProducts = deduplicateCollectionRecords('products', products);
  const uniqueMoves = deduplicateCollectionRecords('stock_moves', stockMoves);

  const productById = new Map<number, Product>();
  for (const p of uniqueProducts) {
    productById.set(Number(p.id), p);
  }
  const knownProductIds = new Set<number>(Array.from(productById.keys()));

  return uniqueWarehouses.map((wh) => {
    const aliasIds = new Set<number>(
      Array.isArray(wh._aliasIds) && wh._aliasIds.length > 0 ? wh._aliasIds : [Number(wh.id)]
    );
    const whMoves = uniqueMoves.filter((m) => {
      if (!aliasIds.has(Number(m.warehouseId))) return false;
      // Only include moves linked to existing catalog products so orphan deleted-product moves never desync KPIs
      if (knownProductIds.size > 0 && !knownProductIds.has(Number(m.productId))) return false;
      return true;
    });

    let inQty = 0;
    let outQty = 0;
    const perProdNet = new Map<number, number>();

    for (const m of whMoves) {
      const pid = Number(m.productId);
      const qty = Math.abs(Number(m.quantity || 0));
      if (!Number.isFinite(qty) || qty <= 0) continue;
      if (isStockInMoveKind(m.kind)) {
        inQty += qty;
        perProdNet.set(pid, (perProdNet.get(pid) || 0) + qty);
      } else {
        outQty += qty;
        perProdNet.set(pid, (perProdNet.get(pid) || 0) - qty);
      }
    }

    let netCartons = 0;
    let distinctProductsCount = 0;
    let totalValueSdg = 0;

    for (const [pid, rawNet] of perProdNet.entries()) {
      const clamped = Math.max(0, rawNet);
      if (clamped > 0) {
        distinctProductsCount += 1;
        netCartons += clamped;
        const prod = productById.get(pid);
        const unitPrice = Number(prod?.salePrice || prod?.salePriceSdg || prod?.purchasePrice || 0);
        totalValueSdg += clamped * unitPrice;
      }
    }

    return {
      warehouse: wh,
      inQty,
      outQty,
      netCartons,
      distinctProductsCount,
      totalValueSdg
    };
  });
}

export interface ColorWeightStockRow {
  colorName: string;
  weightLabel: string;
  inQty: number;
  outQty: number;
  netCount: number;
  primaryProduct: Product | null;
}

export function computeColorWeightStockRows(
  products: Product[],
  stockMoves: StockMove[],
  selectedWeight: string,
  warehouseIdFilter?: number | null,
  warehouses?: Array<Warehouse & { _aliasIds?: number[] }>
): ColorWeightStockRow[] {
  const uniqueProducts = deduplicateCollectionRecords('products', products);
  const uniqueMoves = deduplicateCollectionRecords('stock_moves', stockMoves);
  const uniqueWarehouses = Array.isArray(warehouses)
    ? deduplicateCollectionRecords('warehouses', warehouses)
    : undefined;
  const stockMap = computeProductStocks(uniqueProducts, uniqueMoves, warehouseIdFilter, uniqueWarehouses);

  const filterIdSet = resolveWarehouseFilterIds(warehouseIdFilter, uniqueWarehouses);

  const filteredMoves =
    filterIdSet !== null
      ? uniqueMoves.filter((sm) => filterIdSet.has(Number(sm.warehouseId)))
      : uniqueMoves;

  const cleanWeight = normalizeEntityKey(selectedWeight.replace(/جم|كجم/g, ''));

  const matchesSelectedWeight = (pw: unknown) => {
    if (selectedWeight === 'ALL') return true;
    const normPw = normalizeEntityKey(String(pw ?? '').replace(/جم|كجم/g, ''));
    return normPw === cleanWeight || normalizeArabicText(pw) === normalizeArabicText(selectedWeight);
  };

  const weightProds = uniqueProducts.filter((p) => matchesSelectedWeight(p.packageWeight));

  const distinctColors = Array.from(
    new Set(
      (weightProds.length > 0 ? weightProds : uniqueProducts)
        .map((p) => String(p.paintColor || p.name || '').trim())
        .filter(Boolean)
    )
  );

  return distinctColors.map((colorName) => {
    const matchingProducts = uniqueProducts.filter(
      (p) =>
        normalizeArabicText(p.paintColor || p.name) === normalizeArabicText(colorName) &&
        matchesSelectedWeight(p.packageWeight)
    );
    const matchingIds = new Set(matchingProducts.map((p) => Number(p.id)));
    const prodMoves = filteredMoves.filter((m) => matchingIds.has(Number(m.productId)));

    let inQty = 0;
    let outQty = 0;
    for (const m of prodMoves) {
      const q = Math.abs(Number(m.quantity || 0));
      if (!Number.isFinite(q) || q <= 0) continue;
      if (isStockInMoveKind(m.kind)) {
        inQty += q;
      } else {
        outQty += q;
      }
    }

    const netCount = matchingProducts.reduce((sum, p) => sum + (stockMap.get(Number(p.id)) ?? 0), 0);

    return {
      colorName,
      weightLabel: selectedWeight === 'ALL' ? matchingProducts[0]?.packageWeight || 'قياسي' : selectedWeight,
      inQty,
      outQty,
      netCount,
      primaryProduct: matchingProducts[0] || null
    };
  });
}

export function computeFactoryItemBalances(
  factoryItems: Array<Partial<FactoryItem> & { id: number }>,
  factoryMoves: Array<Partial<FactoryMove> & { itemId: number; kind: string; qty: number }>
): Map<number, number> {
  const result = new Map<number, number>();
  for (const item of factoryItems) {
    result.set(Number(item.id), Number(item.actualBalance || 0));
  }

  const moveDelta = new Map<number, { inQty: number; outQty: number; count: number }>();
  for (const m of factoryMoves) {
    const iid = Number(m.itemId);
    const qty = Math.abs(Number(m.qty || 0));
    const cur = moveDelta.get(iid) || { inQty: 0, outQty: 0, count: 0 };
    const k = String(m.kind || '').trim();
    if (k.includes('توريد') || k.includes('إنتاج') || k.includes('وارد') || k.toUpperCase() === 'IN') {
      cur.inQty += qty;
    } else {
      cur.outQty += qty;
    }
    cur.count += 1;
    moveDelta.set(iid, cur);
  }

  for (const [iid, stats] of moveDelta.entries()) {
    if (stats.count > 0) {
      result.set(iid, Math.max(0, stats.inQty - stats.outQty));
    }
  }

  return result;
}

export function computeInvoiceFinancials(
  inv: Partial<SalesInvoice> & Record<string, any>,
  externalPayments: InvoicePayment[] = []
): {
  paidAmount: number;
  remainingAmount: number;
  statusLabel: 'مدفوعة' | 'سداد جزئي' | 'غير مدفوعة' | 'ملغاة';
} {
  const total = Number(inv.total ?? inv.finalAmount ?? inv.totalAmount ?? 0);
  const rawStatus = String(inv.status || '').trim().toLowerCase();
  if (rawStatus === 'cancelled' || rawStatus === 'ملغاة') {
    return { paidAmount: 0, remainingAmount: 0, statusLabel: 'ملغاة' };
  }

  const paymentMap = new Map<string, number>();
  const embedded: any[] = Array.isArray(inv.payments) ? inv.payments : [];
  for (const p of embedded) {
    if (!p || p.status === 'reversed') continue;
    const pid = String(p.id ?? `${p.invoiceId}_${p.amount}_${p.payDate || p.createdAt || ''}`);
    paymentMap.set(pid, Number(p.amount || 0));
  }

  for (const p of externalPayments) {
    if (!p || p.status === 'reversed') continue;
    if (Number(p.invoiceId) !== Number(inv.id)) continue;
    const pid = String(p.id ?? `${p.invoiceId}_${p.amount}_${p.payDate || p.createdAt || ''}`);
    paymentMap.set(pid, Number(p.amount || 0));
  }

  let sumPayments = 0;
  for (const amt of paymentMap.values()) {
    sumPayments += amt;
  }

  const paidAmount = Math.max(sumPayments, Number(inv.paidAmount || 0));
  const remainingAmount = Math.max(0, total - paidAmount);
  const statusLabel =
    paidAmount >= total && total > 0
      ? 'مدفوعة'
      : paidAmount > 0
      ? 'سداد جزئي'
      : 'غير مدفوعة';

  return { paidAmount, remainingAmount, statusLabel };
}

/**
 * Returns `Map<number, { totalInvoiced, totalPaid, remaining, primaryCurrency }>` keyed by `customer.id`.
 */
export function computeCustomerDebts(
  customers: Array<Partial<Customer> & { id: number }>,
  invoices: SalesInvoice[],
  payments: InvoicePayment[]
): Map<
  number,
  {
    totalInvoiced: number;
    totalPaid: number;
    remaining: number;
    primaryCurrency: CurrencyCode;
  }
> {
  const uniqueInvoices = deduplicateCollectionRecords('invoices', invoices);
  const uniquePayments = deduplicateCollectionRecords('invoice_payments', payments);
  const map = new Map<
    number,
    {
      totalInvoiced: number;
      totalPaid: number;
      remaining: number;
      primaryCurrency: CurrencyCode;
    }
  >();

  for (const c of customers) {
    const cid = Number(c.id);
    const custNameKey = normalizeEntityKey(c.name);
    const custInvoices = uniqueInvoices.filter((inv) => {
      const st = String(inv.status || '').toLowerCase();
      if (st === 'cancelled' || st === 'ملغاة') return false;
      return (
        Number(inv.customerId) === cid ||
        Boolean(custNameKey && normalizeEntityKey(inv.customerName) === custNameKey)
      );
    });

    let totalInvoiced = 0;
    let totalPaid = 0;
    for (const inv of custInvoices) {
      const fin = computeInvoiceFinancials(inv, uniquePayments);
      totalInvoiced += Number(inv.total ?? inv.finalAmount ?? 0);
      totalPaid += fin.paidAmount;
    }

    const remaining = Math.max(0, totalInvoiced - totalPaid);
    const primaryCurrency: CurrencyCode = custInvoices[0]?.currency || c.currency || 'SDG';
    map.set(cid, {
      totalInvoiced,
      totalPaid,
      remaining,
      primaryCurrency
    });
  }

  return map;
}

export function computeCustomerDebtsList(
  customers: Customer[],
  invoices: SalesInvoice[],
  payments: InvoicePayment[]
): Array<Customer & { totalInvoiced: number; totalPaid: number; remainingDebt: number; currency: CurrencyCode }> {
  const uniqueCustomers = deduplicateCollectionRecords('customers', customers);
  const debtMap = computeCustomerDebts(uniqueCustomers, invoices, payments);

  return uniqueCustomers.map((c) => {
    const d = debtMap.get(Number(c.id)) || {
      totalInvoiced: 0,
      totalPaid: 0,
      remaining: 0,
      primaryCurrency: (c.currency || 'SDG') as CurrencyCode
    };
    return {
      ...c,
      totalInvoiced: d.totalInvoiced,
      totalPaid: d.totalPaid,
      remainingDebt: d.remaining,
      currency: d.primaryCurrency
    };
  });
}

export interface CustomerStatementLedgerEntry {
  id: string;
  timestamp: number;
  dateFormatted: string;
  kind: 'INVOICE' | 'PAYMENT';
  typeLabel: string;
  referenceNo: string;
  description: string;
  debit: number;      // مدين (+) - فاتورة مبيعات
  credit: number;     // دائن (-) - دفعة / تحصيل
  runningBalance: number;
  currency: CurrencyCode;
  statusOrCashbox: string;
  actor: string;
}

export interface CustomerStatementResult {
  customer: Customer;
  totalInvoiced: number;
  totalPaid: number;
  remainingDebt: number;
  invoiceCount: number;
  paymentCount: number;
  currency: CurrencyCode;
  entries: CustomerStatementLedgerEntry[];
}

/**
 * Builds a complete chronological Customer Account Statement (كشف حساب عميل تفصيلي)
 * matching Android `SalesScreen.kt` Tab 2 (`StatementPdfAndExcelExporter`) 1:1.
 */
export function computeCustomerStatement(
  customer: Customer,
  invoices: SalesInvoice[],
  payments: InvoicePayment[]
): CustomerStatementResult {
  const uniqueInvoices = deduplicateCollectionRecords('invoices', invoices);
  const uniquePayments = deduplicateCollectionRecords('invoice_payments', payments);

  const custNameKey = normalizeEntityKey(customer.name);
  const custInvoices = uniqueInvoices.filter((inv) => {
    const st = String(inv.status || '').toLowerCase();
    if (st === 'cancelled' || st === 'ملغاة') return false;
    return (
      Number(inv.customerId) === Number(customer.id) ||
      Boolean(custNameKey && normalizeEntityKey(inv.customerName) === custNameKey)
    );
  });

  const invoiceIds = new Set(custInvoices.map((i) => Number(i.id)));
  const invoiceById = new Map<number, SalesInvoice>();
  for (const inv of custInvoices) {
    invoiceById.set(Number(inv.id), inv);
  }

  // Deduplicate embedded invoice payments and external payment documents
  const mergedPaymentDocs = new Map<string, InvoicePayment & Record<string, any>>();
  for (const inv of custInvoices) {
    const embedded: any[] = Array.isArray(inv.payments) ? inv.payments : [];
    for (const p of embedded) {
      if (!p || p.status === 'reversed') continue;
      const key = String(p.id ?? `${inv.id}_${p.amount}_${p.payDate || p.createdAt || ''}`);
      mergedPaymentDocs.set(key, { ...p, invoiceId: Number(inv.id) });
    }
  }
  for (const pay of uniquePayments) {
    const rawPay = pay as InvoicePayment & Record<string, any>;
    if (!rawPay || rawPay.status === 'reversed') continue;
    if (Number(rawPay.customerId) === Number(customer.id) || invoiceIds.has(Number(rawPay.invoiceId))) {
      const key = String(rawPay.id ?? `${rawPay.invoiceId}_${rawPay.amount}_${rawPay.payDate || rawPay.createdAt || ''}`);
      mergedPaymentDocs.set(key, rawPay);
    }
  }

  const custPayments = Array.from(mergedPaymentDocs.values());
  const rawEvents: Array<Omit<CustomerStatementLedgerEntry, 'runningBalance'>> = [];

  // 1. Add all invoices as Debit (مدين +)
  let sumInvoicePaidField = 0;
  for (const inv of custInvoices) {
    const rawInv = inv as SalesInvoice & Record<string, any>;
    const ts = Number(rawInv.createdAt || rawInv.timestamp || Date.now());
    const netTotal = Number(rawInv.total ?? rawInv.finalAmount ?? 0);
    sumInvoicePaidField += Number(rawInv.paidAmount || 0);

    let itemsDesc = '';
    if (Array.isArray(rawInv.items) && rawInv.items.length > 0) {
      itemsDesc = rawInv.items
        .map((it: any) => `${it.description || 'صنف'} (${it.quantity || 1})`)
        .join(' + ');
    } else if (typeof rawInv.itemsJson === 'string' && rawInv.itemsJson) {
      try {
        const parsed = JSON.parse(rawInv.itemsJson);
        if (Array.isArray(parsed) && parsed.length > 0) {
          itemsDesc = parsed
            .map((it: any) => `${it.description || it.productName || 'صنف'} (${it.quantity || 1})`)
            .join(' + ');
        }
      } catch {
        // ignore JSON parse issues
      }
    }

    const fin = computeInvoiceFinancials(inv, custPayments);
    rawEvents.push({
      id: `INV-${inv.id}`,
      timestamp: ts,
      dateFormatted: inv.invoiceDate || new Date(ts).toLocaleDateString('ar-EG'),
      kind: 'INVOICE',
      typeLabel: 'فاتورة مبيعات (مدين)',
      referenceNo: inv.invoiceNo || inv.invoiceNumber || `INV-${inv.id}`,
      description: itemsDesc || inv.notes || 'فاتورة مبيعات معتمدة',
      debit: netTotal,
      credit: 0,
      currency: inv.currency || 'SDG',
      statusOrCashbox: fin.statusLabel,
      actor: inv.createdBy || 'مبيعات'
    });
  }

  // 2. Add payment records as Credit (دائن -)
  let sumPaymentRecords = 0;
  for (const pay of custPayments) {
    const ts = Number(pay.createdAt || pay.timestamp || Date.now());
    const amt = Number(pay.amount || 0);
    sumPaymentRecords += amt;
    const linkedInv = invoiceById.get(Number(pay.invoiceId));
    rawEvents.push({
      id: `PAY-${pay.id}`,
      timestamp: ts,
      dateFormatted: pay.payDate || new Date(ts).toLocaleDateString('ar-EG'),
      kind: 'PAYMENT',
      typeLabel: 'سند تحصيل / دفعة سداد (دائن)',
      referenceNo: linkedInv?.invoiceNo ? `سداد ${linkedInv.invoiceNo}` : `PAY-${pay.id}`,
      description: pay.note || `توريد نقدي إلى ${pay.cashbox || 'الخزنة'}`,
      debit: 0,
      credit: amt,
      currency: (pay.currency as CurrencyCode) || linkedInv?.currency || 'SDG',
      statusOrCashbox: pay.cashbox || 'الخزنة الرئيسية',
      actor: pay.createdBy || pay.receivedBy || 'أمين الخزنة'
    });
  }

  // If an invoice had initial paidAmount greater than recorded payment docs, add the difference
  if (sumInvoicePaidField > sumPaymentRecords) {
    for (const inv of custInvoices) {
      const rawInv = inv as SalesInvoice & Record<string, any>;
      const invPaid = Number(rawInv.paidAmount || 0);
      if (invPaid <= 0) continue;
      const recordedForInv = custPayments
        .filter((p) => Number(p.invoiceId) === Number(inv.id))
        .reduce((s, p) => s + Number(p.amount || 0), 0);
      const diff = invPaid - recordedForInv;
      if (diff > 0) {
        const ts = Number(rawInv.createdAt || rawInv.timestamp || Date.now()) + 1;
        rawEvents.push({
          id: `INVPAY-${inv.id}`,
          timestamp: ts,
          dateFormatted: inv.invoiceDate || new Date(ts).toLocaleDateString('ar-EG'),
          kind: 'PAYMENT',
          typeLabel: 'دفعة مقدمة مع الفاتورة (دائن)',
          referenceNo: inv.invoiceNo || `INV-${inv.id}`,
          description: `سداد نقدي مباشر عند إصدار الفاتورة (${rawInv.cashbox || 'الخزنة'})`,
          debit: 0,
          credit: diff,
          currency: inv.currency || 'SDG',
          statusOrCashbox: String(rawInv.cashbox || 'الخزنة الرئيسية'),
          actor: inv.createdBy || 'مبيعات'
        });
      }
    }
  }

  rawEvents.sort((a, b) => a.timestamp - b.timestamp);

  let running = 0;
  const entries: CustomerStatementLedgerEntry[] = rawEvents.map((ev) => {
    running = Math.max(0, running + ev.debit - ev.credit);
    return {
      ...ev,
      runningBalance: running
    };
  });

  const totalInvoiced = custInvoices.reduce((s, i) => s + Number(i.total ?? i.finalAmount ?? 0), 0);
  const totalPaid = Math.max(sumInvoicePaidField, sumPaymentRecords);
  const remainingDebt = Math.max(0, totalInvoiced - totalPaid);
  const currency: CurrencyCode = custInvoices[0]?.currency || customer.currency || 'SDG';

  return {
    customer,
    totalInvoiced,
    totalPaid,
    remainingDebt,
    invoiceCount: custInvoices.length,
    paymentCount: entries.filter((e) => e.kind === 'PAYMENT').length,
    currency,
    entries
  };
}

/**
 * Parses a human date string (`YYYY-MM-DD`, `DD/MM/YYYY`, `YYYY/MM/DD`, or ISO string) into a UTC day timestamp (ms).
 */
export function parseDateStringToEpochMs(rawDate: unknown): number {
  const clean = String(rawDate ?? '').trim();
  if (!clean) return 0;

  // YYYY-MM-DD or YYYY/MM/DD
  const ymd = clean.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})/);
  if (ymd) {
    const y = Number(ymd[1]);
    const m = Number(ymd[2]);
    const d = Number(ymd[3]);
    if (y >= 2000 && m >= 1 && m <= 12 && d >= 1 && d <= 31) {
      return Date.UTC(y, m - 1, d, 12, 0, 0);
    }
  }

  // DD-MM-YYYY or DD/MM/YYYY
  const dmy = clean.match(/^(\d{1,2})[-/](\d{1,2})[-/](\d{4})/);
  if (dmy) {
    const d = Number(dmy[1]);
    const m = Number(dmy[2]);
    const y = Number(dmy[3]);
    if (y >= 2000 && m >= 1 && m <= 12 && d >= 1 && d <= 31) {
      return Date.UTC(y, m - 1, d, 12, 0, 0);
    }
  }

  const parsed = Date.parse(clean);
  return Number.isFinite(parsed) ? parsed : 0;
}

/**
 * Computes a deterministic sort key (primary: calendar day from txDate/closeDate, secondary: exact createdAt/timestamp/id)
 * so Android and Web movements always sort strictly in chronological order without scattered dates.
 */
export function resolveChronologicalSortKey(record: Record<string, any> | null | undefined): {
  dayMs: number;
  timeMs: number;
  idFallback: number;
} {
  if (!record) return { dayMs: 0, timeMs: 0, idFallback: 0 };

  let rawTime = Number(record.createdAt || record.timestamp || 0);
  if (!Number.isFinite(rawTime) || rawTime <= 0) {
    const srv = record.serverUpdatedAt;
    if (srv && typeof srv === 'object' && typeof srv.seconds === 'number') {
      rawTime = srv.seconds * 1000;
    }
  }
  if (!Number.isFinite(rawTime)) rawTime = 0;

  const dateStr = record.txDate || record.closeDate || record.invoiceDate || record.payDate || '';
  let dayMs = parseDateStringToEpochMs(dateStr);
  if (dayMs <= 0 && rawTime > 0) {
    const d = new Date(rawTime);
    dayMs = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), 12, 0, 0);
  }

  const idFallback = Number(record.id || 0);
  return {
    dayMs,
    timeMs: rawTime,
    idFallback: Number.isFinite(idFallback) ? idFallback : 0
  };
}

export function compareRecordsChronologically(
  a: Record<string, any>,
  b: Record<string, any>,
  order: 'desc' | 'asc' = 'desc'
): number {
  const ka = resolveChronologicalSortKey(a);
  const kb = resolveChronologicalSortKey(b);
  const dir = order === 'desc' ? -1 : 1;

  if (ka.dayMs !== kb.dayMs) {
    return (ka.dayMs - kb.dayMs) * dir;
  }
  if (ka.timeMs !== kb.timeMs) {
    return (ka.timeMs - kb.timeMs) * dir;
  }
  if (ka.idFallback !== kb.idFallback) {
    return (ka.idFallback - kb.idFallback) * dir;
  }
  return 0;
}

export type TransactionCommitViolationType = 'NONE' | 'DUPLICATE' | 'TAMPER';

export interface TransactionValidationOutcome {
  valid: boolean;
  violationType: TransactionCommitViolationType;
  reason?: string;
  matchedDocId?: string;
}

const VALID_CURRENCIES = new Set(['SDG', 'EGP', 'USD']);
const VALID_MOVEMENT_DIRECTIONS = new Set([
  'وارد',
  'منصرف',
  'IN',
  'OUT',
  'INCOME',
  'EXPENSE',
  'TRANSFER_IN',
  'TRANSFER_OUT',
  'قبض',
  'صرف'
]);
const FINANCIAL_REALISTIC_MAX = 1_000_000_000_000;

export function buildFinancialTransactionFingerprint(
  collectionName: string,
  payload: Record<string, any>
): string {
  const col = String(collectionName || '').trim().toLowerCase();
  if (col === 'movements') {
    const ref = normalizeEntityKey(String(payload.reference || payload.referenceNo || ''));
    const box = normalizeEntityKey(String(payload.cashbox || ''));
    const dir = normalizeEntityKey(String(payload.direction || ''));
    const cur = String(payload.currency || 'SDG').trim().toUpperCase();
    const amt = Math.round((Number(payload.amount) || 0) * 100);
    return `MOV|${ref}|${box}|${dir}|${cur}|${amt}`;
  }
  if (col === 'transfers') {
    const ref = normalizeEntityKey(String(payload.reference || ''));
    const src = normalizeEntityKey(String(payload.sourceCashbox || ''));
    const dst = normalizeEntityKey(String(payload.destinationCashbox || ''));
    const sCur = String(payload.sourceCurrency || 'SDG').trim().toUpperCase();
    const dCur = String(payload.destinationCurrency || 'EGP').trim().toUpperCase();
    const sAmt = Math.round((Number(payload.sourceAmount) || 0) * 100);
    return `TRF|${ref}|${src}|${dst}|${sCur}|${dCur}|${sAmt}`;
  }
  if (col === 'invoices') {
    const invNo = normalizeEntityKey(String(payload.invoiceNo || payload.reference || ''));
    const cust = normalizeEntityKey(String(payload.customerName || ''));
    const cur = String(payload.currency || 'SDG').trim().toUpperCase();
    const total = Math.round((Number(payload.total) || 0) * 100);
    return `INV|${invNo}|${cust}|${cur}|${total}`;
  }
  if (col === 'invoice_payments') {
    const invId = String(payload.invoiceId || '').trim();
    const box = normalizeEntityKey(String(payload.cashbox || ''));
    const cur = String(payload.currency || 'SDG').trim().toUpperCase();
    const amt = Math.round((Number(payload.amount) || 0) * 100);
    return `PAY|${invId}|${box}|${cur}|${amt}`;
  }
  return `${col.toUpperCase()}|${normalizeEntityKey(String(payload.reference || payload.id || ''))}`;
}

export function validateFinancialCommitPayload(
  collectionName: string,
  docId: string,
  payload: Record<string, any>
): TransactionValidationOutcome {
  const col = String(collectionName || '').trim().toLowerCase();
  if (!docId || !String(docId).trim()) {
    return { valid: false, violationType: 'TAMPER', reason: 'معرف المستند المالي فارغ أو غير صالح.' };
  }
  if (!payload || typeof payload !== 'object') {
    return { valid: false, violationType: 'TAMPER', reason: 'بيانات المعاملة المالية فارغة أو تالفة.' };
  }

  if (col === 'movements') {
    const amt = Number(payload.amount);
    const cur = String(payload.currency || '').trim().toUpperCase();
    const dir = String(payload.direction || '').trim();
    const cashbox = String(payload.cashbox || '').trim();
    if (!Number.isFinite(amt) || amt <= 0 || amt > FINANCIAL_REALISTIC_MAX) {
      return {
        valid: false,
        violationType: 'TAMPER',
        reason: `قيمة الحركة المالية غير صالحة أو خارج النطاق المسموح (${payload.amount}).`
      };
    }
    if (!VALID_CURRENCIES.has(cur)) {
      return {
        valid: false,
        violationType: 'TAMPER',
        reason: `عملة غير معتمدة في المعاملة المالية: "${payload.currency}".`
      };
    }
    if (!VALID_MOVEMENT_DIRECTIONS.has(dir)) {
      return {
        valid: false,
        violationType: 'TAMPER',
        reason: `اتجاه حركة مالية غير مشروع: "${payload.direction}".`
      };
    }
    if (!cashbox) {
      return {
        valid: false,
        violationType: 'TAMPER',
        reason: 'محاولة تسجيل حركة مالية بدون تحديد الخزنة المعتمدة.'
      };
    }
  } else if (col === 'transfers') {
    const srcAmt = Number(payload.sourceAmount);
    const dstAmt = Number(payload.destinationAmount);
    const rate = Number(payload.rate);
    const srcBox = normalizeEntityKey(String(payload.sourceCashbox || ''));
    const dstBox = normalizeEntityKey(String(payload.destinationCashbox || ''));
    const srcCur = String(payload.sourceCurrency || '').trim().toUpperCase();
    const dstCur = String(payload.destinationCurrency || '').trim().toUpperCase();

    if (!Number.isFinite(srcAmt) || srcAmt <= 0 || srcAmt > FINANCIAL_REALISTIC_MAX) {
      return { valid: false, violationType: 'TAMPER', reason: `مبلغ المصدر غير صالح (${payload.sourceAmount}).` };
    }
    if (!Number.isFinite(dstAmt) || dstAmt <= 0 || dstAmt > FINANCIAL_REALISTIC_MAX) {
      return { valid: false, violationType: 'TAMPER', reason: `مبلغ الوجهة غير صالح (${payload.destinationAmount}).` };
    }
    if (!Number.isFinite(rate) || rate <= 0 || rate > 1_000_000) {
      return { valid: false, violationType: 'TAMPER', reason: `سعر الصرف غير صالح (${payload.rate}).` };
    }
    if (!VALID_CURRENCIES.has(srcCur) || !VALID_CURRENCIES.has(dstCur)) {
      return { valid: false, violationType: 'TAMPER', reason: `عملة تحويل غير معتمدة (${srcCur} -> ${dstCur}).` };
    }
    if (!srcBox || !dstBox) {
      return { valid: false, violationType: 'TAMPER', reason: 'خزنة المصدر أو الوجهة مفقودة في التحويل.' };
    }
    if (srcBox === dstBox && srcCur === dstCur) {
      return {
        valid: false,
        violationType: 'TAMPER',
        reason: 'لا يمكن التحويل من وإلى نفس الخزنة وبنفس العملة.'
      };
    }
    const expectedMul = Math.round(srcAmt * rate * 100) / 100;
    const expectedDiv = Math.round((srcAmt / rate) * 100) / 100;
    const tol = Math.max(0.1, Math.max(expectedMul, expectedDiv) * 0.005);
    if (Math.abs(dstAmt - expectedMul) > tol && Math.abs(dstAmt - expectedDiv) > tol) {
      return {
        valid: false,
        violationType: 'TAMPER',
        reason: `عدم تطابق حساب التحويل (${srcAmt} بسعر ${rate} لا يطابق المستلم ${dstAmt}) — احتمال تلاعب بالقيم.`
      };
    }
  } else if (col === 'invoices') {
    const subtotal = payload.subtotal !== undefined ? Number(payload.subtotal) : Number(payload.total || 0);
    const discount = Number(payload.discount ?? payload.discountAmount ?? 0);
    const total = Number(payload.total ?? 0);
    const paid = Number(payload.paidAmount ?? 0);
    const cur = String(payload.currency || 'SDG').trim().toUpperCase();

    if (
      !Number.isFinite(subtotal) ||
      !Number.isFinite(discount) ||
      !Number.isFinite(total) ||
      !Number.isFinite(paid) ||
      subtotal < 0 ||
      discount < 0 ||
      total < 0 ||
      paid < 0 ||
      total > FINANCIAL_REALISTIC_MAX
    ) {
      return { valid: false, violationType: 'TAMPER', reason: 'قيم مالية سالبة أو غير صالحة في بيانات الفاتورة.' };
    }
    if (!VALID_CURRENCIES.has(cur)) {
      return { valid: false, violationType: 'TAMPER', reason: `عملة فاتورة غير معتمدة: "${cur}".` };
    }
    if (paid > total + 0.05) {
      return {
        valid: false,
        violationType: 'TAMPER',
        reason: `المبلغ المدفوع (${paid}) يتجاوز إجمالي الفاتورة (${total}).`
      };
    }
    if (payload.subtotal !== undefined && Math.abs(Math.max(0, subtotal - discount) - total) > 0.5) {
      return {
        valid: false,
        violationType: 'TAMPER',
        reason: `عدم تطابق صافي الفاتورة (${total}) مع الإجمالي الفرعي (${subtotal}) والخصم (${discount}).`
      };
    }
  } else if (col === 'invoice_payments') {
    const amt = Number(payload.amount);
    const cur = String(payload.currency || 'SDG').trim().toUpperCase();
    if (!Number.isFinite(amt) || amt <= 0 || amt > FINANCIAL_REALISTIC_MAX) {
      return { valid: false, violationType: 'TAMPER', reason: `قيمة دفعة السداد غير صالحة (${payload.amount}).` };
    }
    if (!VALID_CURRENCIES.has(cur)) {
      return { valid: false, violationType: 'TAMPER', reason: `عملة سداد غير معتمدة: "${cur}".` };
    }
  }

  return { valid: true, violationType: 'NONE' };
}

export function checkExistingFinancialConflict(
  collectionName: string,
  candidateDocId: string,
  candidatePayload: Record<string, any>,
  existingDocs: Array<Record<string, any>>
): TransactionValidationOutcome {
  if (!Array.isArray(existingDocs) || existingDocs.length === 0) {
    return { valid: true, violationType: 'NONE' };
  }

  const col = String(collectionName || '').trim().toLowerCase();
  const candDoc = String(candidateDocId || '').trim();
  const candUuid = String(candidatePayload.syncUuid || '').trim();
  const candRef = String(candidatePayload.reference || candidatePayload.referenceNo || candidatePayload.invoiceNo || '').trim();

  for (const existing of existingDocs) {
    if (!existing || typeof existing !== 'object') continue;
    const exDocId = String(existing._docId || '').trim();
    const exUuid = String(existing.syncUuid || '').trim();
    const sameDocId = Boolean(candDoc && exDocId && candDoc === exDocId);
    const sameUuid = Boolean(candUuid && exUuid && candUuid === exUuid);

    if (col === 'movements') {
      const exRef = String(existing.reference || existing.referenceNo || '').trim();
      const sameRefAndLeg =
        Boolean(candRef && exRef && candRef.toLowerCase() === exRef.toLowerCase()) &&
        normalizeEntityKey(String(existing.cashbox || '')) === normalizeEntityKey(String(candidatePayload.cashbox || '')) &&
        normalizeEntityKey(String(existing.direction || '')) === normalizeEntityKey(String(candidatePayload.direction || ''));

      if (sameDocId || sameUuid || sameRefAndLeg) {
        const sameAmount = Math.abs((Number(existing.amount) || 0) - (Number(candidatePayload.amount) || 0)) < 0.01;
        const sameCurrency =
          String(existing.currency || 'SDG').trim().toUpperCase() ===
          String(candidatePayload.currency || 'SDG').trim().toUpperCase();
        const sameBox =
          normalizeEntityKey(String(existing.cashbox || '')) ===
          normalizeEntityKey(String(candidatePayload.cashbox || ''));

        if (!sameAmount || !sameCurrency || !sameBox) {
          return {
            valid: false,
            violationType: 'TAMPER',
            matchedDocId: exDocId,
            reason: `رُصدت محاولة تعديل أو تلاعب في معاملة مالية مسجلة مسبقاً (${exDocId || exRef}): المبلغ أو الخزنة أو العملة لا تطابق السجل المعتمد في Firestore.`
          };
        }
        return {
          valid: false,
          violationType: 'DUPLICATE',
          matchedDocId: exDocId,
          reason: `المعاملة المالية مسجلة مسبقاً في Firestore (${exDocId || exRef}) — تم منع التكرار.`
        };
      }
    } else if (col === 'transfers') {
      const exRef = String(existing.reference || '').trim();
      const sameRef = Boolean(candRef && exRef && candRef.toLowerCase() === exRef.toLowerCase());
      if (sameDocId || sameUuid || sameRef) {
        const sameSrc = Math.abs((Number(existing.sourceAmount) || 0) - (Number(candidatePayload.sourceAmount) || 0)) < 0.01;
        const sameDst = Math.abs((Number(existing.destinationAmount) || 0) - (Number(candidatePayload.destinationAmount) || 0)) < 0.01;
        const sameBoxes =
          normalizeEntityKey(String(existing.sourceCashbox || '')) === normalizeEntityKey(String(candidatePayload.sourceCashbox || '')) &&
          normalizeEntityKey(String(existing.destinationCashbox || '')) === normalizeEntityKey(String(candidatePayload.destinationCashbox || ''));

        if (!sameSrc || !sameDst || !sameBoxes) {
          return {
            valid: false,
            violationType: 'TAMPER',
            matchedDocId: exDocId,
            reason: `رُصدت محاولة تعديل أو تلاعب في تحويل خزينة مسجل مسبقاً (${exDocId || exRef}).`
          };
        }
        return {
          valid: false,
          violationType: 'DUPLICATE',
          matchedDocId: exDocId,
          reason: `عملية التحويل مسجلة مسبقاً في Firestore (${exDocId || exRef}) — تم منع التكرار.`
        };
      }
    } else if (col === 'invoices') {
      const exNo = String(existing.invoiceNo || '').trim();
      const sameNo = Boolean(candRef && exNo && candRef.toLowerCase() === exNo.toLowerCase());
      if (sameDocId || sameUuid || sameNo) {
        const sameTotal = Math.abs((Number(existing.total) || 0) - (Number(candidatePayload.total) || 0)) < 0.01;
        const sameCurrency =
          String(existing.currency || 'SDG').trim().toUpperCase() ===
          String(candidatePayload.currency || 'SDG').trim().toUpperCase();
        // Updating invoice paidAmount/status after recording a customer payment is a legitimate lifecycle update when docId & total match
        if (sameDocId && sameTotal && sameCurrency && Number(candidatePayload.paidAmount ?? 0) >= Number(existing.paidAmount ?? 0)) {
          continue;
        }
        if (!sameTotal || !sameCurrency) {
          return {
            valid: false,
            violationType: 'TAMPER',
            matchedDocId: exDocId,
            reason: `رُصدت محاولة تلاعب بإجمالي أو عملة فاتورة معتمدة (${exDocId || exNo}).`
          };
        }
        return {
          valid: false,
          violationType: 'DUPLICATE',
          matchedDocId: exDocId,
          reason: `الفاتورة مسجلة مسبقاً في Firestore (${exDocId || exNo}) — تم منع التكرار.`
        };
      }
    } else if (col === 'invoice_payments') {
      if (sameDocId || sameUuid) {
        const sameAmount = Math.abs((Number(existing.amount) || 0) - (Number(candidatePayload.amount) || 0)) < 0.01;
        if (!sameAmount) {
          return {
            valid: false,
            violationType: 'TAMPER',
            matchedDocId: exDocId,
            reason: `محاولة تلاعب بقيمة دفعة سداد مسجلة مسبقاً (${exDocId}).`
          };
        }
        return {
          valid: false,
          violationType: 'DUPLICATE',
          matchedDocId: exDocId,
          reason: `دفعة السداد مسجلة مسبقاً في Firestore (${exDocId}) — تم منع التكرار.`
        };
      }
    }
  }

  return { valid: true, violationType: 'NONE' };
}


