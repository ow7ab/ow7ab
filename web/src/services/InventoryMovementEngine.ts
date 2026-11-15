import {
  InventoryItemCategory,
  InventoryMovement,
  InventoryMovementType,
  LabFormulation,
  PackagingRule,
  ProductionOrder,
  PurchaseReceipt,
  QcInspection,
  UserAccount,
  Warehouse,
  WarehouseClass,
  WarehouseStockBalance,
  WarehouseTransfer,
  WarehouseTransferItem
} from '../types/erp';

const EPSILON = 1e-6;

export const DEFAULT_PACKAGING_RULES: PackagingRule[] = [
  {
    syncUuid: 'PKG-RULE-250',
    weightGrams: 250,
    label: '250 جرام (48 علبة/كرتونة)',
    cansPerCarton: 48,
    defaultEmptyCanCostEgp: 3.2,
    defaultLidCostEgp: 0.9,
    defaultLabelCostEgp: 0.6,
    defaultCartonBoxCostEgp: 16.0,
    active: true,
    updatedBy: 'SYSTEM',
    recordVersion: 1,
    updatedAt: 0
  },
  {
    syncUuid: 'PKG-RULE-900',
    weightGrams: 900,
    label: '900 جرام (12 علبة/كرتونة)',
    cansPerCarton: 12,
    defaultEmptyCanCostEgp: 5.5,
    defaultLidCostEgp: 1.4,
    defaultLabelCostEgp: 0.9,
    defaultCartonBoxCostEgp: 18.0,
    active: true,
    updatedBy: 'SYSTEM',
    recordVersion: 1,
    updatedAt: 0
  },
  {
    syncUuid: 'PKG-RULE-4000',
    weightGrams: 4000,
    label: '4000 جرام - جالون (4 علب/كرتونة)',
    cansPerCarton: 4,
    defaultEmptyCanCostEgp: 14.0,
    defaultLidCostEgp: 3.5,
    defaultLabelCostEgp: 1.8,
    defaultCartonBoxCostEgp: 22.0,
    active: true,
    updatedBy: 'SYSTEM',
    recordVersion: 1,
    updatedAt: 0
  }
];

const MOVEMENT_DEFAULT_SIGN: Record<InventoryMovementType, number> = {
  PURCHASE_RECEIPT: +1,
  PRODUCTION_ISSUE: -1,
  PRODUCTION_RETURN: +1,
  PRODUCTION_RECEIPT: +1,
  WAREHOUSE_TRANSFER_OUT: -1,
  WAREHOUSE_TRANSFER_IN: +1,
  SALES_ISSUE: -1,
  CUSTOMER_RETURN: +1,
  SUPPLIER_RETURN: -1,
  STOCK_ADJUSTMENT: 0,
  OPENING_BALANCE: +1,
  DAMAGED_GOODS: -1,
  EXPIRED_GOODS: -1
};

export function resolveWarehouseClass(warehouse: Partial<Warehouse> | null | undefined): WarehouseClass {
  const explicit = String(warehouse?.warehouseClass || '').trim().toUpperCase();
  if (explicit === 'RAW_MATERIALS' || explicit === 'FINISHED_GOODS' || explicit === 'SALES') {
    return explicit as WarehouseClass;
  }
  const combined = `${warehouse?.warehouseType || ''} ${warehouse?.name || ''}`.toLowerCase();
  if (combined.includes('خام') || combined.includes('raw')) return 'RAW_MATERIALS';
  if (combined.includes('إنتاج') || combined.includes('انتاج') || combined.includes('finished')) return 'FINISHED_GOODS';
  return 'SALES';
}

export function signedQuantityFor(
  movementType: InventoryMovementType,
  quantity: number,
  adjustmentDirection: 1 | -1 = 1
): number {
  const absQty = Math.abs(Number(quantity || 0));
  if (movementType === 'STOCK_ADJUSTMENT') {
    return absQty * (adjustmentDirection < 0 ? -1 : 1);
  }
  return absQty * (MOVEMENT_DEFAULT_SIGN[movementType] ?? 1);
}

export function validateWarehousePolicy(params: {
  warehouseClass: WarehouseClass;
  movementType: InventoryMovementType;
  itemCategory: InventoryItemCategory;
  qcApproved?: boolean;
  quarantineHold?: boolean;
}): string | null {
  const { warehouseClass, movementType, itemCategory, qcApproved = true, quarantineHold = false } = params;

  if (warehouseClass === 'RAW_MATERIALS') {
    if (itemCategory === 'FINISHED_GOOD') {
      return 'لا يُسمح بتخزين منتجات تامة الصنع داخل مخزن المواد الخام.';
    }
    if (movementType === 'SALES_ISSUE') {
      return 'لا يُسمح ببيع المواد الخام مباشرة في فواتير المبيعات من هذا المخزن.';
    }
    if (movementType === 'PRODUCTION_RECEIPT') {
      return 'مخرجات الإنتاج التام تُستلم في مخزن الإنتاج التام وليس مخزن المواد الخام.';
    }
    if (movementType === 'CUSTOMER_RETURN') {
      return 'مرتجعات العملاء تُستلم في مخزن المبيعات وليس مخزن المواد الخام.';
    }
    return null;
  }

  if (warehouseClass === 'FINISHED_GOODS') {
    if (itemCategory !== 'FINISHED_GOOD') {
      return 'مخزن الإنتاج التام مخصص للمنتجات التامة الصنع فقط.';
    }
    if (movementType === 'SALES_ISSUE') {
      return 'لا يُسمح بالبيع المباشر من مخزن الإنتاج التام؛ يجب التحويل أولاً إلى مخزن المبيعات بمستند تحويل معتمد.';
    }
    if (
      movementType === 'PURCHASE_RECEIPT' ||
      movementType === 'SUPPLIER_RETURN' ||
      movementType === 'PRODUCTION_ISSUE' ||
      movementType === 'PRODUCTION_RETURN'
    ) {
      return 'هذه الحركة مخصصة لمخزن المواد الخام فقط وليست لمخزن الإنتاج التام.';
    }
    if (movementType === 'PRODUCTION_RECEIPT' && !qcApproved && !quarantineHold) {
      return 'لا يُسمح بإضافة إنتاج تام إلى الرصيد المتاح قبل اجتياز مراقبة الجودة QC أو وضعه في الحجر.';
    }
    if (movementType === 'WAREHOUSE_TRANSFER_OUT' && !qcApproved) {
      return 'لا يُسمح بتحويل منتج من مخزن الإنتاج التام قبل اعتماد مراقبة الجودة QC.';
    }
    return null;
  }

  // SALES
  if (itemCategory !== 'FINISHED_GOOD') {
    return 'مخزن المبيعات مخصص للمنتجات التامة الجاهزة للبيع فقط.';
  }
  if (movementType === 'PRODUCTION_RECEIPT') {
    return 'لا يُسمح باستلام الإنتاج التام مباشرة في مخزن المبيعات؛ يجب الاستلام في مخزن الإنتاج التام بعد الجودة ثم التحويل الرسمي.';
  }
  if (
    movementType === 'PURCHASE_RECEIPT' ||
    movementType === 'SUPPLIER_RETURN' ||
    movementType === 'PRODUCTION_ISSUE' ||
    movementType === 'PRODUCTION_RETURN'
  ) {
    return 'هذه الحركة خاصة بمخزن المواد الخام ولا تنطبق على مخزن المبيعات.';
  }
  return null;
}

export function validateTransferRoute(
  sourceClass: WarehouseClass,
  destClass: WarehouseClass,
  items: Array<Pick<WarehouseTransferItem, 'itemCategory' | 'requestedQty' | 'qcApprovedRequired' | 'qcApproved'>>
): string | null {
  if (!items || items.length === 0) {
    return 'يجب إضافة صنف واحد على الأقل لمستند التحويل.';
  }
  if (items.some((i) => Number(i.requestedQty || 0) <= 0)) {
    return 'جميع الكميات المطلوبة في التحويل يجب أن تكون أكبر من صفر.';
  }
  if (sourceClass === 'RAW_MATERIALS') {
    if (destClass !== 'RAW_MATERIALS') {
      return 'التحويل من مخزن المواد الخام يُسمح به فقط إلى مخزن مواد خام آخر.';
    }
    if (items.some((i) => i.itemCategory === 'FINISHED_GOOD')) {
      return 'مخزن المواد الخام لا يحول منتجات تامة الصنع.';
    }
    return null;
  }
  if (sourceClass === 'FINISHED_GOODS') {
    if (destClass === 'RAW_MATERIALS') {
      return 'لا يُسمح بتحويل المنتجات التامة إلى مخزن المواد الخام.';
    }
    if (items.some((i) => i.qcApprovedRequired && !i.qcApproved)) {
      return 'يوجد صنف لم يجتز اعتماد مراقبة الجودة QC ولا يمكن تحويله إلى مخزن المبيعات.';
    }
    return null;
  }
  if (destClass === 'RAW_MATERIALS') {
    return 'لا يُسمح بالتحويل من مخزن المبيعات إلى مخزن المواد الخام.';
  }
  return null;
}

export function balanceKey(
  warehouseId: number,
  itemCategory: InventoryItemCategory,
  itemId: number,
  batchNumber = ''
): string {
  return `BAL-${warehouseId}-${itemCategory}-${itemId}-${String(batchNumber || '').trim()}`;
}

function computeWeightedAvgCost(
  existingQty: number,
  existingCost: number,
  addedQty: number,
  addedCost: number
): number {
  if (addedCost <= 0) return existingCost;
  const safeExisting = Math.max(0, existingQty);
  if (safeExisting <= EPSILON || existingCost <= 0) return addedCost;
  const total = safeExisting + addedQty;
  return total > EPSILON ? ((safeExisting * existingCost) + (addedQty * addedCost)) / total : addedCost;
}

export interface PostMovementInput {
  warehouse: Warehouse;
  movementType: InventoryMovementType;
  itemCategory: InventoryItemCategory;
  itemId: number;
  itemCode?: string;
  itemName: string;
  quantity: number;
  adjustmentDirection?: 1 | -1;
  unit?: string;
  unitCost?: number;
  currency?: string;
  batchNumber?: string;
  expiryDate?: string;
  referenceDocType?: string;
  referenceDocId?: number | null;
  referenceDocNo?: string;
  reason?: string;
  qcApproved?: boolean;
  quarantineHold?: boolean;
  performedBy?: string;
  approvedBy?: string;
  idempotencyKey?: string;
  syncUuid?: string;
  deviceId?: string;
  documentNo?: string;
  now?: number;
}

export interface MovementLedgerState {
  movements: InventoryMovement[];
  balances: WarehouseStockBalance[];
}

export function postMovementInState(
  state: MovementLedgerState,
  input: PostMovementInput
): { movement: InventoryMovement; balance: WarehouseStockBalance; state: MovementLedgerState } {
  const absQty = Math.abs(Number(input.quantity || 0));
  if (absQty <= 0) throw new Error('الكمية يجب أن تكون أكبر من صفر.');
  if (!String(input.itemName || '').trim()) throw new Error('اسم الصنف مطلوب.');

  const cleanIdemp = String(input.idempotencyKey || '').trim();
  const cleanUuid = String(input.syncUuid || '').trim() || `IM-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const existingDup = state.movements.find(
    (m) => (cleanIdemp && m.idempotencyKey === cleanIdemp) || m.syncUuid === cleanUuid
  );
  if (existingDup) {
    const existingBal = state.balances.find(
      (b) => b.syncUuid === balanceKey(existingDup.warehouseId, existingDup.itemCategory, existingDup.itemId, existingDup.batchNumber)
    )!;
    return { movement: existingDup, balance: existingBal, state };
  }

  const whClass = resolveWarehouseClass(input.warehouse);
  const qcApproved = input.qcApproved ?? true;
  const quarantineHold = input.quarantineHold ?? false;
  const policyError = validateWarehousePolicy({
    warehouseClass: whClass,
    movementType: input.movementType,
    itemCategory: input.itemCategory,
    qcApproved,
    quarantineHold
  });
  if (policyError) throw new Error(policyError);

  const now = input.now ?? Date.now();
  const batchKey = String(input.batchNumber || '').trim();
  const key = balanceKey(input.warehouse.id, input.itemCategory, input.itemId, batchKey);
  const currentBalance = state.balances.find((b) => b.syncUuid === key);

  const currentOnHand = currentBalance?.onHandQty ?? 0;
  const currentReserved = currentBalance?.reservedQty ?? 0;
  const currentInTransit = currentBalance?.inTransitQty ?? 0;
  const currentQuarantine = currentBalance?.quarantineQty ?? 0;
  const currentAvailable = currentBalance?.availableQty ?? 0;
  const currentAvgCost = currentBalance?.avgUnitCost ?? (input.unitCost ?? 0);

  const signedQty = signedQuantityFor(input.movementType, absQty, input.adjustmentDirection ?? 1);
  let newOnHand: number;
  let newQuarantine: number;
  let newAvailable: number;
  let newAvgCost: number;

  if (quarantineHold && signedQty > 0) {
    newOnHand = currentOnHand + absQty;
    newQuarantine = currentQuarantine + absQty;
    newAvailable = Math.max(0, newOnHand - currentReserved - newQuarantine);
    newAvgCost = computeWeightedAvgCost(currentOnHand, currentAvgCost, absQty, input.unitCost ?? 0);
  } else {
    if (signedQty < 0 && currentAvailable + signedQty < -EPSILON) {
      throw new Error(
        `الرصيد المتاح غير كافٍ للصرف (${input.warehouse.name} - ${input.itemName}): المتاح ${currentAvailable}، المطلوب ${absQty}.`
      );
    }
    newOnHand = currentOnHand + signedQty;
    if (newOnHand < -EPSILON) {
      throw new Error(`لا يُسمح برصيد مخزني سالب (${input.itemName}).`);
    }
    newQuarantine = currentQuarantine;
    newAvailable = Math.max(0, newOnHand - currentReserved - newQuarantine);
    newAvgCost =
      signedQty > 0 && (input.unitCost ?? 0) > 0
        ? computeWeightedAvgCost(currentOnHand, currentAvgCost, absQty, input.unitCost ?? 0)
        : currentAvgCost;
  }

  const effectiveUnitCost = (input.unitCost ?? 0) > 0 ? input.unitCost! : currentAvgCost;
  const nextId = state.movements.reduce((max, m) => Math.max(max, Number(m.id || 0)), 0) + 1;

  const movement: InventoryMovement = {
    id: nextId,
    syncUuid: cleanUuid,
    documentNo: input.documentNo || `IM-${now}-${nextId}`,
    movementType: input.movementType,
    warehouseId: input.warehouse.id,
    warehouseCode: input.warehouse.code || `WH-${input.warehouse.id}`,
    warehouseClass: whClass,
    itemCategory: input.itemCategory,
    itemId: input.itemId,
    itemCode: input.itemCode || '',
    itemName: input.itemName.trim(),
    quantity: absQty,
    signedQuantity: signedQty,
    unit: input.unit || 'كرتونة',
    unitCost: effectiveUnitCost,
    totalCost: absQty * effectiveUnitCost,
    currency: input.currency || input.warehouse.currency || 'EGP',
    batchNumber: batchKey,
    expiryDate: input.expiryDate || '',
    referenceDocType: input.referenceDocType || input.movementType,
    referenceDocId: input.referenceDocId ?? null,
    referenceDocNo: input.referenceDocNo || '',
    reversedMovementId: null,
    reason: (input.reason || '').trim(),
    status: 'POSTED',
    qcApproved,
    idempotencyKey: cleanIdemp,
    performedBy: input.performedBy || 'النظام',
    approvedBy: input.approvedBy || '',
    deviceId: input.deviceId || 'WEB',
    recordVersion: 1,
    createdAt: now,
    updatedAt: now
  };

  const nextBalance: WarehouseStockBalance = {
    ...(currentBalance || {
      syncUuid: key,
      warehouseId: input.warehouse.id,
      warehouseClass: whClass,
      itemCategory: input.itemCategory,
      itemId: input.itemId,
      itemCode: input.itemCode || '',
      itemName: input.itemName.trim(),
      unit: input.unit || 'كرتونة',
      batchNumber: batchKey,
      expiryDate: input.expiryDate || '',
      onHandQty: 0,
      reservedQty: 0,
      inTransitQty: 0,
      quarantineQty: 0,
      availableQty: 0,
      avgUnitCost: 0,
      currency: input.currency || input.warehouse.currency || 'EGP',
      updatedAt: now
    }),
    onHandQty: Math.max(0, newOnHand),
    reservedQty: currentReserved,
    inTransitQty: currentInTransit,
    quarantineQty: newQuarantine,
    availableQty: newAvailable,
    avgUnitCost: newAvgCost,
    lastMovementUuid: movement.syncUuid,
    updatedAt: now
  };

  const nextBalances = currentBalance
    ? state.balances.map((b) => (b.syncUuid === key ? nextBalance : b))
    : [...state.balances, nextBalance];

  return {
    movement,
    balance: nextBalance,
    state: {
      movements: [...state.movements, movement],
      balances: nextBalances
    }
  };
}

export function attemptDeleteMovement(_movementId: number): never {
  throw new Error('لا يُسمح بحذف الحركات المخزنية المعتمدة (Append-Only). يجب استخدام القيد العكسي فقط.');
}

export function reverseMovementInState(
  state: MovementLedgerState,
  movementId: number,
  reason: string,
  actor: string,
  now = Date.now()
): { reversalMovement: InventoryMovement; state: MovementLedgerState } {
  const cleanReason = String(reason || '').trim();
  if (!cleanReason) throw new Error('يجب كتابة سبب واضح عند إنشاء القيد العكسي.');

  const original = state.movements.find((m) => m.id === movementId);
  if (!original) throw new Error(`الحركة المخزنية رقم ${movementId} غير موجودة.`);
  if (original.status === 'REVERSED') {
    throw new Error('تم عكس هذه الحركة مسبقاً ولا يمكن عكسها مرتين.');
  }

  const key = balanceKey(original.warehouseId, original.itemCategory, original.itemId, original.batchNumber);
  const currentBalance = state.balances.find((b) => b.syncUuid === key);
  if (!currentBalance) throw new Error('لا يوجد سجل رصيد مطابق للحركة المطلوب عكسها.');

  const reversingSignedQty = -original.signedQuantity;
  if (reversingSignedQty < 0 && currentBalance.availableQty + reversingSignedQty < -EPSILON) {
    throw new Error(
      `لا يمكن عكس حركة الإدخال لأن الكمية المتاحة (${currentBalance.availableQty}) قد تم صرف جزء منها بالفعل.`
    );
  }

  const newOnHand = Math.max(0, currentBalance.onHandQty + reversingSignedQty);
  const newAvailable = Math.max(0, newOnHand - currentBalance.reservedQty - currentBalance.quarantineQty);
  const nextId = state.movements.reduce((max, m) => Math.max(max, Number(m.id || 0)), 0) + 1;

  const reversalMovement: InventoryMovement = {
    ...original,
    id: nextId,
    syncUuid: `REV-${original.syncUuid}-${nextId}`,
    documentNo: `REV-${now}-${nextId}`,
    movementType: 'STOCK_ADJUSTMENT',
    signedQuantity: reversingSignedQty,
    referenceDocType: 'REVERSAL',
    referenceDocId: original.id,
    referenceDocNo: original.documentNo,
    reversedMovementId: original.id,
    reason: `قيد عكسي للحركة ${original.documentNo}: ${cleanReason}`,
    status: 'POSTED',
    idempotencyKey: `REV-${original.syncUuid}`,
    performedBy: actor || 'المدير',
    approvedBy: actor || 'المدير',
    recordVersion: 1,
    createdAt: now,
    updatedAt: now
  };

  const nextMovements = state.movements
    .map((m) =>
      m.id === original.id
        ? { ...m, status: 'REVERSED' as const, recordVersion: m.recordVersion + 1, updatedAt: now }
        : m
    )
    .concat(reversalMovement);

  const nextBalance: WarehouseStockBalance = {
    ...currentBalance,
    onHandQty: newOnHand,
    availableQty: newAvailable,
    lastMovementUuid: reversalMovement.syncUuid,
    updatedAt: now
  };

  return {
    reversalMovement,
    state: {
      movements: nextMovements,
      balances: state.balances.map((b) => (b.syncUuid === key ? nextBalance : b))
    }
  };
}

export function canApproveTransfer(
  transfer: Pick<WarehouseTransfer, 'status' | 'createdBy'>,
  approver: Pick<UserAccount, 'username' | 'userCode' | 'role' | 'roleType' | 'active'> | null | undefined,
  allowCreatorOverride = false
): { ok: boolean; error?: string } {
  if (!approver || !approver.active) {
    return { ok: false, error: 'يجب تسجيل دخول مستخدم نشط للاعتماد.' };
  }
  if (transfer.status !== 'PENDING_APPROVAL' && transfer.status !== 'DRAFT') {
    return { ok: false, error: `لا يمكن اعتماد التحويل في حالته الحالية (${transfer.status}).` };
  }
  const isSuperAdmin =
    approver.roleType === 'SUPER_ADMIN' ||
    String(approver.username || '').toLowerCase() === 'admin' ||
    String(approver.userCode || '').toUpperCase() === 'USR-ADMIN' ||
    String(approver.role || '').includes('مدير النظام');

  if (
    !isSuperAdmin &&
    !allowCreatorOverride &&
    String(approver.username || '').trim().toLowerCase() === String(transfer.createdBy || '').trim().toLowerCase()
  ) {
    return {
      ok: false,
      error: `فصل المهام (SoD): لا يُسمح لمنشئ التحويل (${transfer.createdBy}) باعتماد مستنده الشخصي.`
    };
  }
  return { ok: true };
}

export function getCansPerCarton(weightGrams: number, customRules: PackagingRule[] = DEFAULT_PACKAGING_RULES): number {
  const matched = customRules.find((r) => r.weightGrams === weightGrams && r.active);
  if (matched && matched.cansPerCarton > 0) return matched.cansPerCarton;
  if (weightGrams === 250) return 48;
  if (weightGrams === 4000) return 4;
  return 12;
}

export function convertCansToCartons(
  weightGrams: number,
  totalCans: number,
  customRules: PackagingRule[] = DEFAULT_PACKAGING_RULES
): { weightGrams: number; cansPerCarton: number; totalCans: number; fullCartons: number; looseCans: number } {
  const cansPerCarton = getCansPerCarton(weightGrams, customRules);
  const safeCans = Math.max(0, Math.floor(totalCans));
  return {
    weightGrams,
    cansPerCarton,
    totalCans: safeCans,
    fullCartons: Math.floor(safeCans / cansPerCarton),
    looseCans: safeCans % cansPerCarton
  };
}

export function convertCartonsToCans(
  weightGrams: number,
  cartons: number,
  customRules: PackagingRule[] = DEFAULT_PACKAGING_RULES
): number {
  return Math.max(0, Math.floor(cartons)) * getCansPerCarton(weightGrams, customRules);
}

export function isSalesWarehouse(warehouse: Partial<Warehouse> | null | undefined): boolean {
  return resolveWarehouseClass(warehouse) === 'SALES';
}

export interface WebPurchaseReceiptInput {
  poNo?: string;
  supplierName: string;
  rawWarehouse: Warehouse;
  materialId: number;
  materialCode?: string;
  materialName: string;
  unit?: string;
  batchNumber?: string;
  expiryDate?: string;
  orderedQty?: number;
  receivedQty: number;
  acceptedQty: number;
  rejectedQty?: number;
  unitCost?: number;
  currency?: string;
  discrepancyReason?: string;
  notes?: string;
  performedBy?: string;
  now?: number;
}

export function receivePurchaseToRawMaterialsInState(
  state: MovementLedgerState,
  input: WebPurchaseReceiptInput
): { receipt: PurchaseReceipt; state: MovementLedgerState } {
  const whClass = resolveWarehouseClass(input.rawWarehouse);
  if (whClass !== 'RAW_MATERIALS') {
    throw new Error('استلام المشتريات يجب أن يوجه حصراً إلى مخزن المواد الخام.');
  }
  const received = Number(input.receivedQty || 0);
  const accepted = Number(input.acceptedQty || 0);
  const rejected = Number(input.rejectedQty || 0);
  if (received <= 0 || accepted < 0 || rejected < 0 || Math.abs(received - (accepted + rejected)) > 0.001) {
    throw new Error('الكمية المستلمة يجب أن تساوي مجموع الكمية المقبولة والمرفوضة.');
  }
  const ordered = Number(input.orderedQty || received);
  const hasDiscrepancy = rejected > 0 || (ordered > 0 && Math.abs(received - ordered) > 0.001);
  if (hasDiscrepancy && !String(input.discrepancyReason || '').trim()) {
    throw new Error('يجب كتابة سبب الرفض أو العجز/الزيادة عند اختلاف الاستلام.');
  }

  const now = input.now ?? Date.now();
  const grnNo = `GRN-${String(now).slice(-6)}`;
  const batchNumber = String(input.batchNumber || '').trim() || `BAT-RAW-${String(now).slice(-5)}`;
  let nextState = state;

  if (accepted > 0) {
    nextState = postMovementInState(nextState, {
      warehouse: input.rawWarehouse,
      movementType: 'PURCHASE_RECEIPT',
      itemCategory: 'RAW_MATERIAL',
      itemId: input.materialId,
      itemCode: input.materialCode || `RAW-${input.materialId}`,
      itemName: input.materialName,
      quantity: accepted,
      unit: input.unit || 'كجم',
      unitCost: input.unitCost || 0,
      currency: input.currency || 'EGP',
      batchNumber,
      expiryDate: input.expiryDate || '',
      referenceDocType: 'PURCHASE_RECEIPT',
      referenceDocNo: grnNo,
      reason: `استلام مشتريات مقبول (${grnNo}) — ${input.supplierName}`,
      qcApproved: true,
      quarantineHold: false,
      performedBy: input.performedBy || 'أمين مخزن الخام',
      idempotencyKey: `GRN-ACC-${grnNo}`,
      now
    }).state;
  }

  if (rejected > 0) {
    nextState = postMovementInState(nextState, {
      warehouse: input.rawWarehouse,
      movementType: 'PURCHASE_RECEIPT',
      itemCategory: 'RAW_MATERIAL',
      itemId: input.materialId,
      itemCode: input.materialCode || `RAW-${input.materialId}`,
      itemName: input.materialName,
      quantity: rejected,
      unit: input.unit || 'كجم',
      unitCost: input.unitCost || 0,
      currency: input.currency || 'EGP',
      batchNumber: `${batchNumber}-REJ`,
      expiryDate: input.expiryDate || '',
      referenceDocType: 'PURCHASE_QUARANTINE',
      referenceDocNo: grnNo,
      reason: `حجر كمية مرفوضة (${grnNo}): ${input.discrepancyReason || ''}`,
      qcApproved: false,
      quarantineHold: true,
      performedBy: input.performedBy || 'أمين مخزن الخام',
      idempotencyKey: `GRN-REJ-${grnNo}`,
      now: now + 1
    }).state;
  }

  const receipt: PurchaseReceipt = {
    id: now,
    syncUuid: `PR-${grnNo}`,
    grnNo,
    poNo: input.poNo || `PO-${String(now).slice(-5)}`,
    receiptDate: new Date(now).toISOString().split('T')[0],
    supplierName: input.supplierName.trim() || 'مورد معتمد',
    warehouseId: input.rawWarehouse.id,
    materialId: input.materialId,
    materialCode: input.materialCode || `RAW-${input.materialId}`,
    materialName: input.materialName.trim(),
    unit: input.unit || 'كجم',
    batchNumber,
    expiryDate: input.expiryDate || '',
    orderedQty: ordered,
    receivedQty: received,
    acceptedQty: accepted,
    rejectedQty: rejected,
    unitCost: input.unitCost || 0,
    totalCost: accepted * (input.unitCost || 0),
    currency: input.currency || 'EGP',
    discrepancyReason: String(input.discrepancyReason || '').trim(),
    qcStatus: accepted <= 0 && rejected > 0 ? 'REJECTED' : rejected > 0 ? 'PARTIAL' : 'APPROVED',
    stockPosted: accepted > 0,
    notes: String(input.notes || '').trim(),
    performedBy: input.performedBy || 'أمين مخزن الخام',
    approvedBy: hasDiscrepancy ? '' : (input.performedBy || 'أمين مخزن الخام'),
    idempotencyKey: `PR-${grnNo}`,
    recordVersion: 1,
    createdAt: now,
    updatedAt: now
  };

  return { receipt, state: nextState };
}

export function createProductionOrderModel(params: {
  formula?: LabFormulation | null;
  productId?: number | null;
  productName: string;
  paintColor?: string;
  packageWeightGrams: number;
  targetCartons: number;
  rawWarehouseId: number;
  fgWarehouseId: number;
  machineName?: string;
  productionEngineer?: string;
  approvedBy?: string;
  customRules?: PackagingRule[];
  now?: number;
}): ProductionOrder {
  const now = params.now ?? Date.now();
  const cartons = Math.max(1, Math.floor(params.targetCartons));
  const cansPerCarton = getCansPerCarton(params.packageWeightGrams, params.customRules || DEFAULT_PACKAGING_RULES);
  const targetCans = cartons * cansPerCarton;
  const targetWeightKg = (targetCans * params.packageWeightGrams) / 1000;
  const orderNo = `PRD-${String(now).slice(-6)}`;
  const batchNumber = `PB-${new Date(now).toISOString().split('T')[0].replace(/-/g, '')}-${String(now).slice(-3)}`;

  let bomLinesJson = '[]';
  if (params.formula && params.formula.ingredientsJson) {
    try {
      const parsed = JSON.parse(params.formula.ingredientsJson);
      if (Array.isArray(parsed) && params.formula.totalBatchWeight > 0) {
        const scale = targetWeightKg / params.formula.totalBatchWeight;
        const scaled = parsed.map((ing: any) => ({
          materialName: ing.name || 'خامة',
          materialType: ing.type || 'مادة خام',
          unit: 'كجم',
          theoreticalRequiredKg: Math.round(Number(ing.weight || 0) * scale * 1000) / 1000,
          unitCostEgp: Number(ing.unitCost || 0)
        }));
        bomLinesJson = JSON.stringify(scaled);
      }
    } catch {
      bomLinesJson = '[]';
    }
  }

  return {
    id: now,
    syncUuid: `PO-${orderNo}`,
    orderNo,
    orderDate: new Date(now).toISOString().split('T')[0],
    formulaId: params.formula?.id ?? null,
    formulaNo: params.formula?.formulaNo || 'STD-BOM',
    productId: params.productId ?? null,
    productName: params.productName.trim() || params.formula?.productName || 'منتج دهان',
    paintColor: params.paintColor || params.formula?.baseKind || 'أبيض',
    packageWeightGrams: params.packageWeightGrams,
    cansPerCarton,
    targetCartons: cartons,
    targetCans,
    targetWeightKg,
    actualGoodCans: 0,
    rejectedCans: 0,
    reworkCans: 0,
    producedFullCartons: 0,
    producedLooseCans: 0,
    wastedRawKg: 0,
    bomLinesJson,
    machineName: params.machineName || 'خلاط رئيسي 1',
    productionEngineer: params.productionEngineer || 'مهندس الإنتاج',
    status: 'APPROVED',
    batchNumber,
    rawWarehouseId: params.rawWarehouseId,
    fgWarehouseId: params.fgWarehouseId,
    qcInspectedBy: '',
    qcNotes: '',
    approvedBy: params.approvedBy || 'المدير',
    idempotencyKey: `PO-${orderNo}`,
    recordVersion: 1,
    createdAt: now,
    updatedAt: now
  };
}

export function issueRawMaterialsForOrderInState(
  state: MovementLedgerState,
  order: ProductionOrder,
  rawWarehouse: Warehouse,
  lines: Array<{
    itemId: number;
    itemName: string;
    qty: number;
    returnedQty?: number;
    wastedQty?: number;
    unit?: string;
    batchNumber?: string;
  }>,
  actor = 'أمين مخزن الخام',
  now = Date.now()
): { order: ProductionOrder; state: MovementLedgerState } {
  if (!lines || lines.length === 0) throw new Error('يجب تحديد مادة خام واحدة على الأقل.');
  let nextState = state;
  let wasteAdded = 0;

  for (const line of lines) {
    nextState = postMovementInState(nextState, {
      warehouse: rawWarehouse,
      movementType: 'PRODUCTION_ISSUE',
      itemCategory: 'RAW_MATERIAL',
      itemId: line.itemId,
      itemName: line.itemName,
      quantity: line.qty,
      unit: line.unit || 'كجم',
      currency: 'EGP',
      batchNumber: line.batchNumber || '',
      referenceDocType: 'PRODUCTION_ORDER',
      referenceDocId: order.id,
      referenceDocNo: order.orderNo,
      reason: `صرف خامات لأمر الإنتاج ${order.orderNo}`,
      performedBy: actor,
      idempotencyKey: `PRD-ISS-${order.syncUuid}-${line.itemId}-${line.batchNumber || ''}`,
      now
    }).state;

    if ((line.returnedQty || 0) > 0) {
      nextState = postMovementInState(nextState, {
        warehouse: rawWarehouse,
        movementType: 'PRODUCTION_RETURN',
        itemCategory: 'RAW_MATERIAL',
        itemId: line.itemId,
        itemName: line.itemName,
        quantity: line.returnedQty!,
        unit: line.unit || 'كجم',
        currency: 'EGP',
        batchNumber: line.batchNumber || '',
        referenceDocType: 'PRODUCTION_RETURN',
        referenceDocId: order.id,
        referenceDocNo: order.orderNo,
        reason: `مرتجع فائض خامات ${order.orderNo}`,
        performedBy: actor,
        idempotencyKey: `PRD-RET-${order.syncUuid}-${line.itemId}-${line.batchNumber || ''}`,
        now: now + 1
      }).state;
    }
    wasteAdded += Math.max(0, Number(line.wastedQty || 0));
  }

  return {
    order: {
      ...order,
      status: 'MATERIALS_ISSUED',
      wastedRawKg: order.wastedRawKg + wasteAdded,
      recordVersion: order.recordVersion + 1,
      updatedAt: now
    },
    state: nextState
  };
}

export function inspectQcAndPostToFinishedGoodsInState(
  state: MovementLedgerState,
  params: {
    order: ProductionOrder;
    fgWarehouse: Warehouse;
    productId: number;
    measuredDensity: number;
    expectedDensity?: number;
    measuredViscosity?: string;
    colorMatchPassed: boolean;
    goodCans: number;
    rejectedCans?: number;
    reworkCans?: number;
    forceReject?: boolean;
    inspectorName?: string;
    notes?: string;
    now?: number;
  }
): { qc: QcInspection; order: ProductionOrder; state: MovementLedgerState } {
  const now = params.now ?? Date.now();
  const goodCans = Math.max(0, Math.floor(params.goodCans));
  const rejectedCans = Math.max(0, Math.floor(params.rejectedCans || 0));
  const reworkCans = Math.max(0, Math.floor(params.reworkCans || 0));
  const totalInspected = goodCans + rejectedCans + reworkCans;
  if (totalInspected <= 0) throw new Error('الكمية المفحوصة يجب أن تكون أكبر من صفر.');
  if (params.order.status === 'POSTED_TO_FG') {
    throw new Error('تم ترحيل إنتاج هذا الأمر مسبقاً.');
  }

  const targetDensity = (params.expectedDensity && params.expectedDensity > 0) ? params.expectedDensity : (params.measuredDensity || 1.25);
  const minD = Math.round(targetDensity * 0.97 * 1000) / 1000;
  const maxD = Math.round(targetDensity * 1.03 * 1000) / 1000;
  const densityPass = params.measuredDensity <= 0 || (params.measuredDensity >= minD && params.measuredDensity <= maxD);
  const isPassed = !params.forceReject && densityPass && params.colorMatchPassed && goodCans > 0;

  const cansPerCarton = Math.max(1, params.order.cansPerCarton || getCansPerCarton(params.order.packageWeightGrams));
  const fullCartons = isPassed ? Math.floor(goodCans / cansPerCarton) : 0;
  const looseCans = isPassed ? goodCans % cansPerCarton : 0;
  const qcNo = `QC-${String(now).slice(-6)}`;
  let nextState = state;

  if (isPassed) {
    const qtyToPost = fullCartons > 0 ? fullCartons : Math.round((goodCans / cansPerCarton) * 1000) / 1000;
    nextState = postMovementInState(nextState, {
      warehouse: params.fgWarehouse,
      movementType: 'PRODUCTION_RECEIPT',
      itemCategory: 'FINISHED_GOOD',
      itemId: params.productId,
      itemName: params.order.productName,
      quantity: qtyToPost,
      unit: 'كرتونة',
      currency: 'EGP',
      batchNumber: params.order.batchNumber,
      referenceDocType: 'QC_PRODUCTION_RECEIPT',
      referenceDocNo: qcNo,
      reason: `إنتاج تام معتمد جودة ${qcNo} (${fullCartons} كرتونة + ${looseCans} علبة)`,
      qcApproved: true,
      quarantineHold: false,
      performedBy: params.order.productionEngineer,
      approvedBy: params.inspectorName || 'مسؤول الجودة',
      idempotencyKey: `FG-POST-${params.order.syncUuid}`,
      now
    }).state;
  }

  const quarantineCans = isPassed ? rejectedCans + reworkCans : totalInspected;
  if (quarantineCans > 0) {
    const qCartons = Math.max(0.001, Math.round((quarantineCans / cansPerCarton) * 1000) / 1000);
    nextState = postMovementInState(nextState, {
      warehouse: params.fgWarehouse,
      movementType: 'PRODUCTION_RECEIPT',
      itemCategory: 'FINISHED_GOOD',
      itemId: params.productId,
      itemName: params.order.productName,
      quantity: qCartons,
      unit: 'كرتونة',
      currency: 'EGP',
      batchNumber: `${params.order.batchNumber}-QC-HOLD`,
      referenceDocType: 'QC_QUARANTINE',
      referenceDocNo: qcNo,
      reason: `حجر منتجات غير مطابقة للمواصفات (${qcNo})`,
      qcApproved: false,
      quarantineHold: true,
      performedBy: params.inspectorName || 'مسؤول الجودة',
      idempotencyKey: `FG-HOLD-${params.order.syncUuid}`,
      now: now + 1
    }).state;
  }

  const qc: QcInspection = {
    id: now,
    syncUuid: `QC-${qcNo}`,
    qcNo,
    sourceType: 'PRODUCTION_ORDER',
    sourceDocId: params.order.id,
    sourceDocNo: params.order.orderNo,
    batchNumber: params.order.batchNumber,
    itemCategory: 'FINISHED_GOOD',
    itemId: params.productId,
    itemName: params.order.productName,
    inspectedQty: totalInspected,
    acceptedQty: isPassed ? goodCans : 0,
    rejectedQty: isPassed ? rejectedCans : goodCans + rejectedCans,
    reworkQty: reworkCans,
    measuredDensity: params.measuredDensity,
    expectedMinDensity: minD,
    expectedMaxDensity: maxD,
    measuredViscosity: params.measuredViscosity || '110 KU',
    colorMatchPassed: params.colorMatchPassed,
    status: isPassed ? 'APPROVED' : reworkCans > 0 ? 'CONDITIONAL_REWORK' : 'REJECTED',
    inspectorName: params.inspectorName || 'مسؤول الجودة',
    notes: String(params.notes || '').trim(),
    postedToStock: isPassed,
    idempotencyKey: `QC-${params.order.syncUuid}`,
    recordVersion: 1,
    createdAt: now,
    updatedAt: now
  };

  const updatedOrder: ProductionOrder = {
    ...params.order,
    productId: params.productId,
    actualGoodCans: qc.acceptedQty,
    rejectedCans: qc.rejectedQty,
    reworkCans,
    producedFullCartons: fullCartons,
    producedLooseCans: looseCans,
    status: isPassed ? 'POSTED_TO_FG' : 'QC_REJECTED',
    qcInspectedBy: qc.inspectorName,
    qcNotes: qc.notes,
    recordVersion: params.order.recordVersion + 1,
    updatedAt: now
  };

  return { qc, order: updatedOrder, state: nextState };
}

