/**
 * PMP PAINTS Enterprise ERP — Complete Unified Data Models
 * Strictly mirrors the Android Room entities (Entities.kt) and Cloud Firestore cluster collections.
 */

export type CurrencyCode = 'SDG' | 'EGP' | 'USD';

export type UserRoleType = 'SUPER_ADMIN' | 'MANAGER' | 'EMPLOYEE';

export type JobRole =
  | 'مدير النظام'
  | 'المدير'
  | 'المدير العام'
  | 'مدير فرع ومصنع مصر'
  | 'المدير المالي'
  | 'مديرة الجودة والمشتريات'
  | 'مدير الإنتاج'
  | 'مهندس التعبئة والجودة'
  | 'أمين مخازن المصنع'
  | 'مشرف التحميل والعدد'
  | 'محاسب ومبيعات السودان'
  | 'مسؤول نظام مخصص'
  | 'المحاسب'
  | 'محاسب خزنة ومبيعات'
  | 'مسؤول مبيعات وفواتير'
  | 'مسؤول المشتريات'
  | 'أمين مخزن الخام'
  | 'أمين مخزن المنتج التام'
  | 'أمين مخزن وترحيل'
  | 'مهندس الإنتاج'
  | 'مهندس إنتاج - المصنع'
  | 'مسؤول الجودة'
  | 'كيميائي الجودة'
  | 'مراجع مالي وتقارير'
  | string;

export interface UserAccount {
  id: number;
  userCode: string;
  fullName: string;
  username: string;
  phoneOrEmail: string;
  pinOrPassword?: string;
  role: JobRole;
  roleType: UserRoleType;
  branch: string;
  permissions: string;
  serverSyncToken: string;
  remoteServerId?: string;
  linkedUid?: string;
  authEmail?: string;
  syncStatus: 'APPROVED_BY_MANAGER' | 'PENDING_MANAGER_APPROVAL' | 'REJECTED' | 'READY_FOR_SYNC';
  active: boolean;
  isCurrentSession?: boolean;
  createdAt?: string;
  updatedAt?: string;
  lastLoginAt?: string;
}

export interface LinkedAccountDoc {
  userCode: string;
  ownerId: string;
  linkedUid: string;
  clusterCode: string;
  fullName: string;
  username: string;
  role: string;
  roleType?: UserRoleType;
  branch: string;
  permissions: string;
  serverSyncToken: string;
  active: boolean;
  createdAt: any;
  updatedAt: any;
  lastLoginAt?: any;
}

export type LinkedAccountModel = LinkedAccountDoc;

export interface TemporaryPermissionGrant {
  grantId: string;
  targetUserCode: string;
  permissionToken: string;
  grantedBy: string;
  grantedAtMillis: number;
  expiresAtMillis: number;
  reason: string;
}

export interface PermissionChangeAuditEntry {
  entryId: string;
  targetUserCode: string;
  targetUsername: string;
  targetFullName: string;
  actorUsername: string;
  actorRole: string;
  previousRole: string;
  newRole: string;
  addedPermissions: string[];
  removedPermissions: string[];
  temporaryGrantsSummary?: string;
  reason: string;
  changedAtMillis: number;
  formattedDate?: string;
}

export interface LoginActivityLog {
  id: string;
  userCode: string;
  username: string;
  fullName: string;
  timestamp: number;
  dateStr: string;
  ipAddress?: string;
  userAgent?: string;
  method: 'PASSWORD' | 'GOOGLE' | 'PIN';
  status: 'SUCCESS' | 'FAILED' | 'LOCKED_OUT';
  details?: string;
}

export interface Cashbox {
  id: number;
  name: string;
  balance: number;
  currency: CurrencyCode;
  branch: string;
  active?: boolean;
  updatedAt?: number;
  lastUpdated?: string;
}

export interface TreasuryMovement {
  id: number;
  txDate?: string;
  cashbox: string;
  currency: CurrencyCode;
  direction?: 'وارد' | 'منصرف';
  amount: number;
  description?: string;
  reference?: string;
  createdBy?: string;
  createdAt?: number;
  // Aliases for component convenience
  type?: 'INCOME' | 'EXPENSE' | 'IN' | 'OUT' | string;
  category?: string;
  note?: string;
  referenceNo?: string;
  actor?: string;
  cashboxName?: string;
  employeeName?: string;
  date?: string;
  timestamp?: number;
  cloudDocId?: string;
  syncUuid?: string;
  updatedAt?: number;
}

export interface InterTreasuryTransfer {
  id: number;
  reference: string;
  txDate: string;
  sourceCashbox: string;
  destinationCashbox: string;
  sourceCurrency: CurrencyCode;
  destinationCurrency: CurrencyCode;
  sourceAmount: number;
  rate: number;
  destinationAmount: number;
  description: string;
  halfaInvoiceId?: number | null;
  halfaAmount?: number | null;
  createdBy: string;
  createdAt: number;
}

export interface DailyClosing {
  id: number;
  closeDate: string;
  cashbox: string;
  currency: CurrencyCode;
  bookBalance: number;
  countedBalance: number;
  difference: number;
  notes: string;
  preparedBy: string;
  status: 'closed' | 'reopened';
  closedAt: number;
  createdAt?: number;
}

export interface Customer {
  id: number;
  name: string;
  phone: string;
  address: string;
  notes: string;
  active: boolean;
  createdAt: number;
  currency?: CurrencyCode;
  creditLimit?: number;
  currentBalance?: number;
}

export interface InvoiceItem {
  id?: number;
  invoiceId?: number;
  description: string;
  quantity: number;
  unitPrice: number;
  lineTotal?: number;
  productId?: number | null;
  warehouseId?: number | null;
}

export interface SalesInvoice {
  id: number;
  invoiceNo: string;
  customerId: number;
  customerName: string;
  invoiceDate?: string;
  currency: CurrencyCode;
  subtotal: number;
  discount?: number;
  discountAmount?: number;
  taxAmount?: number;
  total: number;
  notes: string;
  status: 'active' | 'cancelled' | 'مدفوعة' | 'سداد جزئي' | 'غير مدفوعة' | 'ملغاة' | string;
  cancelReason?: string | null;
  createdBy: string;
  createdAt?: number;
  paidAmount?: number;
  remainingAmount?: number;
  paymentType?: 'CASH' | 'INSTALLMENT' | 'TRANSFER';
  items?: InvoiceItem[];
  itemsJson?: string;
  payments?: InvoicePayment[];
  cashbox?: string;
  branch?: string;
  warehouseId?: number;
  // Aliases
  invoiceNumber?: string;
  invoiceType?: string;
  warehouseName?: string;
  date?: string;
  totalAmount?: number;
  finalAmount?: number;
  timestamp?: number;
}

export interface InvoicePayment {
  id: number;
  invoiceId: number;
  customerId?: number;
  payDate?: string;
  cashbox: string;
  amount: number;
  currency?: CurrencyCode;
  note: string;
  status?: 'active' | 'reversed';
  reversalReason?: string | null;
  createdBy?: string;
  receivedBy?: string;
  createdAt?: number;
  timestamp?: number;
}

export type WarehouseClass = 'RAW_MATERIALS' | 'FINISHED_GOODS' | 'SALES';

export type InventoryItemCategory = 'RAW_MATERIAL' | 'PACKAGING' | 'FINISHED_GOOD';

export type InventoryMovementType =
  | 'PURCHASE_RECEIPT'
  | 'PRODUCTION_ISSUE'
  | 'PRODUCTION_RETURN'
  | 'PRODUCTION_RECEIPT'
  | 'WAREHOUSE_TRANSFER_OUT'
  | 'WAREHOUSE_TRANSFER_IN'
  | 'SALES_ISSUE'
  | 'CUSTOMER_RETURN'
  | 'SUPPLIER_RETURN'
  | 'STOCK_ADJUSTMENT'
  | 'OPENING_BALANCE'
  | 'DAMAGED_GOODS'
  | 'EXPIRED_GOODS';

export type MovementRecordStatus = 'POSTED' | 'REVERSED' | 'PENDING_APPROVAL' | 'REJECTED';

export type WarehouseTransferStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'IN_TRANSIT'
  | 'RECEIVED'
  | 'REJECTED'
  | 'CANCELLED';

export interface Warehouse {
  id: number;
  name: string;
  active: boolean;
  currency?: CurrencyCode;
  linkedCashbox?: string;
  warehouseType?: 'مبيعات' | 'داخل المصنع/إنتاج' | 'مواد خام' | string;
  warehouseClass?: WarehouseClass;
  code?: string;
  branch?: string;
  allowDirectSales?: boolean;
  allowProductionReceipt?: boolean;
  syncUuid?: string;
  updatedAt?: number;
  recordVersion?: number;
  deviceId?: string;
  location?: string;
  manager?: string;
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  category: string;
  unit: string;
  purchasePrice?: number;
  costPrice?: number;
  salePrice: number; // SDG
  salePriceEgp: number; // EGP
  minimumStock?: number;
  minLevel?: number;
  unitsPerCarton?: number;
  packageWeight: '250' | '900' | '4000' | 'جالون' | 'ربع' | string;
  paintColor: 'أبيض' | 'ألوان' | 'فضي' | 'ذهبي' | 'ورنيش' | string;
  active: boolean;
  currentStock: number;
  syncUuid?: string;
  updatedAt?: number;
  recordVersion?: number;
  deviceId?: string;
  // Aliases
  salePriceSdg?: number;
}

export interface StockMove {
  id: number;
  warehouseId: number;
  warehouseName?: string;
  productId: number;
  productName?: string;
  quantity: number;
  kind: 'IN' | 'OUT' | 'SALE' | 'RETURN' | 'RETURN_IN' | 'CANCEL' | 'ADJUST' | string;
  invoiceId?: number | null;
  referenceNo?: string;
  batchNumber?: string;
  syncUuid?: string;
  recordVersion?: number;
  deviceId?: string;
  note: string;
  createdBy?: string;
  actor?: string;
  createdAt?: number;
  timestamp?: number;
}

export interface FactoryItem {
  id: number;
  name: string;
  unit: string;
  category: 'raw' | 'finished' | 'empty' | 'خامات إنتاج' | 'مواد تعبئة وتغليف' | 'مذيبات ومخففات' | 'إضافات كيميائية' | string;
  itemCode?: string;
  linkedToEgypt?: boolean;
  defaultCurrency?: CurrencyCode;
  unitCostEgp?: number;
  minStock?: number;
  maxStock?: number;
  batchTrackingRequired?: boolean;
  syncUuid?: string;
  updatedAt?: number;
  recordVersion?: number;
  deviceId?: string;
  actualBalance?: number;
  reorderLevel?: number;
  lastPurchasePrice?: number;
  lastPriceUpdatedAt?: number;
  supplierName?: string;
  density?: number;
  solidsPercent?: number;
  chemicalType?: string;
}

export interface FactoryMachine {
  id: number;
  name: string;
  note: string;
  active: boolean;
}

export interface FactoryMove {
  id: number;
  moveDate: string;
  kind: 'توريد خامات' | 'صرف خامات' | 'إنتاج تام' | 'تالف ومعالجة' | string;
  itemId: number;
  itemName: string;
  qty: number;
  machineId?: number | null;
  machineName?: string | null;
  batch: string;
  note: string;
  actor: string;
  counterparty: string;
  linked: boolean;
  syncUuid?: string;
  recordVersion?: number;
  deviceId?: string;
  createdAt: number;
}

export interface InventoryMovement {
  id: number;
  syncUuid: string;
  documentNo: string;
  movementType: InventoryMovementType;
  warehouseId: number;
  warehouseCode?: string;
  warehouseClass: WarehouseClass;
  itemCategory: InventoryItemCategory;
  itemId: number;
  itemCode?: string;
  itemName: string;
  quantity: number;
  signedQuantity: number;
  unit: string;
  unitCost: number;
  totalCost: number;
  currency: CurrencyCode | string;
  batchNumber: string;
  expiryDate?: string;
  referenceDocType?: string;
  referenceDocId?: number | null;
  referenceDocNo?: string;
  reversedMovementId?: number | null;
  reason?: string;
  status: MovementRecordStatus;
  qcApproved: boolean;
  idempotencyKey?: string;
  performedBy: string;
  approvedBy?: string;
  deviceId?: string;
  recordVersion: number;
  createdAt: number;
  updatedAt: number;
}

export interface WarehouseStockBalance {
  id?: number;
  syncUuid: string;
  warehouseId: number;
  warehouseClass: WarehouseClass;
  itemCategory: InventoryItemCategory;
  itemId: number;
  itemCode?: string;
  itemName: string;
  unit: string;
  batchNumber: string;
  expiryDate?: string;
  onHandQty: number;
  reservedQty: number;
  inTransitQty: number;
  quarantineQty: number;
  availableQty: number;
  avgUnitCost: number;
  currency: CurrencyCode | string;
  lastMovementUuid?: string;
  updatedAt: number;
}

export interface WarehouseTransfer {
  id: number;
  syncUuid: string;
  transferNo: string;
  transferDate: string;
  sourceWarehouseId: number;
  sourceWarehouseName: string;
  sourceWarehouseClass: WarehouseClass;
  destinationWarehouseId: number;
  destinationWarehouseName: string;
  destinationWarehouseClass: WarehouseClass;
  status: WarehouseTransferStatus;
  totalCost: number;
  currency: CurrencyCode | string;
  notes?: string;
  rejectionReason?: string;
  createdBy: string;
  reviewedBy?: string;
  approvedBy?: string;
  dispatchedBy?: string;
  receivedBy?: string;
  dispatchedAt?: number;
  receivedAt?: number;
  timelineJson?: string;
  idempotencyKey?: string;
  deviceId?: string;
  recordVersion: number;
  createdAt: number;
  updatedAt: number;
}

export interface WarehouseTransferItem {
  id?: number;
  transferId: number;
  transferUuid: string;
  itemCategory: InventoryItemCategory;
  itemId: number;
  itemCode?: string;
  itemName: string;
  unit: string;
  requestedQty: number;
  approvedQty: number;
  shippedQty: number;
  receivedQty: number;
  rejectedQty: number;
  unitCost: number;
  totalCost: number;
  batchNumber: string;
  qcApprovedRequired: boolean;
  qcApproved: boolean;
}

export interface PackagingRule {
  id?: number;
  syncUuid: string;
  weightGrams: number;
  label: string;
  cansPerCarton: number;
  emptyCanItemId?: number | null;
  lidItemId?: number | null;
  labelItemId?: number | null;
  cartonItemId?: number | null;
  defaultEmptyCanCostEgp: number;
  defaultLidCostEgp: number;
  defaultLabelCostEgp: number;
  defaultCartonBoxCostEgp: number;
  active: boolean;
  updatedBy: string;
  recordVersion: number;
  updatedAt: number;
}

export interface HalfaInvoice {
  id: number;
  invoiceNo: string;
  invoiceDate: string;
  cashbox: string;
  currency: CurrencyCode;
  warehouseId: number;
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  total: number;
  factoryMoveId?: number | null;
  note: string;
  status: 'active' | 'cancelled';
  createdBy: string;
  createdAt: number;
}

export interface PosterPrice {
  id: string; // e.g. "900_أبيض" or "900_أبيض_EGP"
  weight: string; // 250, 900
  kind: string; // أبيض، ألوان، فضي، ذهبي، ورنيش
  price: number; // SDG
  priceEgp: number; // EGP
  currency: CurrencyCode;
}

export interface AuditLog {
  id: number;
  username: string;
  action: string;
  detail: string;
  timestamp: number;
}

export type FormulaApprovalStatus = 'DRAFT' | 'NEEDS_REVIEW' | 'APPROVED' | 'REJECTED' | 'ARCHIVED';

export interface LabFormulation {
  id: number;
  syncUuid?: string;
  formulaNo: string;
  productName: string;
  paintType: string;
  baseKind: string;
  linkedProductId?: number | null;
  linkedProductSku?: string;
  category?: string;
  targetPvc: number;
  calculatedPvc: number;
  cpvcEstimated: number;
  totalSolidsWeight: number;
  totalSolidsVolume: number;
  totalBatchWeight: number;
  totalBatchVolume: number;
  calculatedDensity: number;
  totalCost: number;
  costPerKg: number;
  historicalTotalCost?: number;
  historicalCostPerKg?: number;
  currency?: CurrencyCode | string;
  ingredientsJson: string;
  technicalAnalysis: string;
  notes: string;
  approvedForProduction: boolean;
  approvalStatus?: FormulaApprovalStatus | string;
  versionNumber?: number;
  approvedBy?: string;
  approvedAt?: number;
  changeReason?: string;
  createdBy: string;
  updatedBy?: string;
  createdAt: number;
  updatedAt?: number;
  confidentialCiphertext?: string;
  isConfidentialSealed?: boolean;
}

export interface FormulationIngredient {
  id: string;
  materialId?: number | null;
  itemCode?: string;
  name: string;
  type: string;
  weight: number;
  density: number;
  solidsPercent: number;
  unitCost: number;
  notes: string;
}

export interface FormulationVersion {
  id?: number;
  syncUuid: string;
  formulaId: number;
  formulaNo: string;
  productName: string;
  versionNumber: number;
  previousVersionNumber: number;
  status: FormulaApprovalStatus | string;
  changeReason: string;
  diffSummaryJson: string;
  previousCostPerKg: number;
  newCostPerKg: number;
  historicalTotalCost: number;
  calculatedPvc: number;
  calculatedDensity: number;
  currency: CurrencyCode | string;
  ingredientsSnapshotJson: string;
  encryptedSnapshotCiphertext?: string;
  createdBy: string;
  approvedBy?: string;
  approvedAt?: number;
  createdAt: number;
}

export interface LabExperiment {
  id: number;
  syncUuid: string;
  experimentNo: string;
  formulaId: number;
  formulaNo: string;
  formulaVersionId?: number | null;
  versionNumber: number;
  productId?: number | null;
  productName: string;
  experimentDate: string;
  technicianName: string;
  materialsUsedJson: string;
  measuredDensity: number;
  measuredViscosity: string;
  measuredPh: number;
  measuredGrindMicrons: number;
  hidingPowerPercent: number;
  notes: string;
  status: 'PENDING' | 'PASSED' | 'FAILED' | 'RETEST_REQUIRED' | string;
  decision: 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETEST' | string;
  decidedBy: string;
  decidedAt: number;
  linkedQcNo?: string;
  idempotencyKey?: string;
  createdAt: number;
  updatedAt: number;
}

export interface RawMaterialPriceHistory {
  id: number;
  syncUuid: string;
  materialId: number;
  itemCode: string;
  materialName: string;
  supplierName: string;
  unitPrice: number;
  previousPrice: number;
  currency: CurrencyCode | string;
  sourceType: 'PURCHASE_GRN' | 'MANUAL_LAB' | 'EXCEL_IMPORT' | string;
  sourceDocNo: string;
  effectiveDate: string;
  recordedBy: string;
  createdAt: number;
}

export interface LabExcelImportBatch {
  id: number;
  syncUuid: string;
  importNo: string;
  fileName: string;
  sheetCount: number;
  formulasDetected: number;
  formulasImported: number;
  materialsMatched: number;
  materialsCreated: number;
  externalLinksDetected: number;
  brokenRefsDetected: number;
  warningsCount: number;
  approvalStatus: 'NEEDS_REVIEW' | 'REVIEWED' | 'ARCHIVED' | string;
  checksumSha256: string;
  restrictedSummaryJson: string;
  importedBy: string;
  idempotencyKey?: string;
  createdAt: number;
}

export interface IngredientCostBreakdown {
  name: string;
  type: string;
  materialId?: number | null;
  itemCode?: string;
  weightKg: number;
  weightPercent: number;
  unitCost: number;
  lineCost: number;
  costSharePercent: number;
}

export interface ThreeCostComparisonReport {
  formulaNo: string;
  versionNumber: number;
  historicalTotalCost: number;
  historicalCostPerKg: number;
  currentTotalCost: number;
  currentCostPerKg: number;
  actualProductionTotalCost: number | null;
  actualProductionCostPerKg: number | null;
  priceDeltaTotal: number;
  priceDeltaPercent: number;
  changedMaterials: Array<{
    name: string;
    oldUnitCost: number;
    currentUnitCost: number;
    deltaPerKgImpact: number;
  }>;
}

export interface ChemicalSafety {
  id: number;
  chemicalName: string;
  commonName: string;
  casNumber: string;
  molecularFormula: string;
  molecularWeight: number;
  ghsPictograms: string;
  signalWord: 'خطر' | 'تحذير';
  hazardStatements: string;
  precautionaryStatements: string;
  storageConditions: string;
  firstAidMeasures: string;
  sdsSupplierName: string;
  sdsFileUrlOrUri: string;
  notes: string;
  createdAt: number;
}

export interface ProductionBatch {
  id: number;
  batchNumber: string;
  productionDate: string;
  formulaId?: number | null;
  formulaNo: string;
  productName: string;
  paintType: string;
  baseKind: string;
  targetPvc: number;
  targetWeightKg: number;
  actualWeightKg: number;
  targetVolumeLiters: number;
  cartonsCount: number;
  packageUnit: string;
  machineId?: number | null;
  machineName: string;
  qualityStatus: 'معتمد ومطابق' | 'تحت الفحص المخبري' | 'مرفوض/معالجة';
  measuredDensity: number;
  measuredViscosity: string;
  scaledIngredientsJson: string;
  supervisorOrEngineer: string;
  shift: string;
  notes: string;
  syncStatus: string;
  createdAt: number;
  // Aliases for component convenience
  orderNumber?: string;
  paintColor?: string;
  formulaName?: string;
  packageWeightGrams?: number;
  targetCartons?: number;
  actualProducedCans?: number;
  goodApprovedCans?: number;
  productionEngineer?: string;
  date?: string;
  status?: string;
}

// Aliases for other services
export type RawMaterialItem = FactoryItem;
export type TreasuryTransfer = InterTreasuryTransfer;
export type Machine = FactoryMachine;
export type ChemicalFormulation = LabFormulation;
export type Supplier = Customer;

export interface PurchaseInvoice {
  id: number;
  supplierId?: number;
  invoiceNo?: string;
  invoiceNumber?: string;
  total?: number;
  totalAmount?: number;
  paidAmount?: number;
  paymentStatus?: string;
  currency?: CurrencyCode;
  status?: string;
  createdAt?: number;
}

export interface QualityInspection {
  id: number;
  inspectionNumber?: string;
  batchNumber?: string;
  productName?: string;
  status?: string;
  createdAt?: number;
}

export interface PurchaseReceipt {
  id: number;
  syncUuid: string;
  grnNo: string;
  poNo: string;
  receiptDate: string;
  supplierName: string;
  warehouseId: number;
  materialId: number;
  materialCode: string;
  materialName: string;
  unit: string;
  batchNumber: string;
  expiryDate: string;
  orderedQty: number;
  receivedQty: number;
  acceptedQty: number;
  rejectedQty: number;
  unitCost: number;
  totalCost: number;
  currency: CurrencyCode | string;
  discrepancyReason: string;
  qcStatus: 'PENDING' | 'APPROVED' | 'PARTIAL' | 'REJECTED';
  stockPosted: boolean;
  notes: string;
  performedBy: string;
  approvedBy: string;
  idempotencyKey: string;
  recordVersion: number;
  createdAt: number;
  updatedAt: number;
}

export interface ProductionOrder {
  id: number;
  syncUuid: string;
  orderNo: string;
  orderDate: string;
  formulaId?: number | null;
  formulaNo: string;
  productId?: number | null;
  productName: string;
  paintColor: string;
  packageWeightGrams: number;
  cansPerCarton: number;
  targetCartons: number;
  targetCans: number;
  targetWeightKg: number;
  actualGoodCans: number;
  rejectedCans: number;
  reworkCans: number;
  producedFullCartons: number;
  producedLooseCans: number;
  wastedRawKg: number;
  bomLinesJson: string;
  machineName: string;
  productionEngineer: string;
  status: 'DRAFT' | 'APPROVED' | 'MATERIALS_ISSUED' | 'IN_PRODUCTION' | 'QC_PENDING' | 'QC_APPROVED' | 'QC_REJECTED' | 'POSTED_TO_FG';
  batchNumber: string;
  rawWarehouseId: number;
  fgWarehouseId: number;
  qcInspectedBy: string;
  qcNotes: string;
  approvedBy: string;
  idempotencyKey: string;
  recordVersion: number;
  createdAt: number;
  updatedAt: number;
}

export interface QcInspection {
  id: number;
  syncUuid: string;
  qcNo: string;
  sourceType: 'PRODUCTION_ORDER' | 'PURCHASE_RECEIPT' | 'TRANSFER';
  sourceDocId?: number | null;
  sourceDocNo: string;
  batchNumber: string;
  itemCategory: InventoryItemCategory | string;
  itemId: number;
  itemName: string;
  inspectedQty: number;
  acceptedQty: number;
  rejectedQty: number;
  reworkQty: number;
  measuredDensity: number;
  expectedMinDensity: number;
  expectedMaxDensity: number;
  measuredViscosity: string;
  colorMatchPassed: boolean;
  status: 'APPROVED' | 'CONDITIONAL_REWORK' | 'REJECTED';
  inspectorName: string;
  notes: string;
  postedToStock: boolean;
  idempotencyKey: string;
  recordVersion: number;
  createdAt: number;
  updatedAt: number;
}

export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE';

export interface ChartAccount {
  id?: number;
  syncUuid: string;
  accountCode: string;
  nameAr: string;
  nameEn: string;
  accountType: AccountType | string;
  normalSide: 'DEBIT' | 'CREDIT' | string;
  parentCode: string;
  currencyHint: CurrencyCode | 'ALL' | string;
  isSystem: boolean;
  active: boolean;
  recordVersion: number;
  createdAt: number;
  updatedAt: number;
}

export interface JournalEntry {
  id?: number;
  syncUuid: string;
  entryNo: string;
  entryDate: string;
  sourceType: string;
  sourceDocId?: number | null;
  sourceDocNo: string;
  description: string;
  currency: CurrencyCode | string;
  fxRateToUsd: number;
  fxRateToSdg: number;
  totalDebit: number;
  totalCredit: number;
  status: 'POSTED' | 'REVERSED' | string;
  reversedEntryId?: number | null;
  reversedByEntryNo?: string;
  reversalReason?: string;
  idempotencyKey: string;
  createdBy: string;
  approvedBy: string;
  recordVersion: number;
  createdAt: number;
  updatedAt: number;
}

export interface JournalLine {
  id?: number;
  syncUuid: string;
  journalEntryId: number;
  journalEntryUuid: string;
  lineOrder: number;
  accountCode: string;
  accountName: string;
  accountType: AccountType | string;
  debit: number;
  credit: number;
  currency: CurrencyCode | string;
  costCenter: string;
  partyType: string;
  partyName: string;
  memo: string;
  createdAt: number;
}

export interface TrialBalanceRow {
  accountCode: string;
  accountName: string;
  accountType: AccountType | string;
  normalSide: 'DEBIT' | 'CREDIT' | string;
  totalDebit: number;
  totalCredit: number;
  netDebit: number;
  netCredit: number;
  signedBalance: number;
}

export interface TrialBalanceReport {
  currency: CurrencyCode | string;
  rows: TrialBalanceRow[];
  totalDebit: number;
  totalCredit: number;
  isBalanced: boolean;
}

export interface IncomeStatementReport {
  currency: CurrencyCode | string;
  revenueRows: TrialBalanceRow[];
  cogsRows: TrialBalanceRow[];
  operatingExpenseRows: TrialBalanceRow[];
  totalRevenue: number;
  totalCogs: number;
  grossProfit: number;
  totalOperatingExpenses: number;
  netIncome: number;
}

export interface BalanceSheetReport {
  currency: CurrencyCode | string;
  assetRows: TrialBalanceRow[];
  liabilityRows: TrialBalanceRow[];
  equityRows: TrialBalanceRow[];
  totalAssets: number;
  totalLiabilities: number;
  totalEquityBeforeNetIncome: number;
  currentNetIncome: number;
  totalLiabilitiesAndEquity: number;
  isBalanced: boolean;
  imbalanceDiff: number;
}

export interface ReconciliationItem {
  label: string;
  operationalValue: number;
  glValue: number;
  difference: number;
  status: 'MATCHED' | 'VARIANCE' | string;
}

export interface CurrencyFxRates {
  egpToSdg: number;
  usdToSdg: number;
}

export interface ConsolidatedFxSummary {
  baseCurrency: 'SDG';
  rates: CurrencyFxRates;
  totalRevenueConsolidated: number;
  totalExpensesConsolidated: number;
  netIncomeConsolidated: number;
  totalAssetsConsolidated: number;
  totalLiabilitiesConsolidated: number;
  totalEquityConsolidated: number;
}

// =============================================================================
// PMP FACTORY OPERATIONS, ROLE WORKSPACES, PRESSES, TOOLS, SEQAOFA & REWARDS
// =============================================================================

export type EmployeeRoleKey =
  | 'GENERAL_MANAGER'
  | 'FINANCE_MANAGER_EGYPT'
  | 'QUALITY_PROCUREMENT_MANAGER'
  | 'PRODUCTION_MANAGER'
  | 'PACKING_ENGINEER'
  | 'WAREHOUSE_KEEPER_EGYPT'
  | 'LOADING_TOOLS_SUPERVISOR'
  | 'SUDAN_BRANCH_MANAGER'
  | 'SALES_AGENT';

export interface ShiftProductionLog {
  id: number;
  syncUuid: string;
  shiftDate: string;
  shiftName: 'الوردية الصباحية' | 'الوردية المسائية' | string;
  lineNo: 1 | 2 | 3 | 4 | 5;
  engineerName: string;
  batchNo: string;
  productName: string;
  packageSize: '1/16' | '1 كيلو' | 'جالون' | 'مهندسين' | string;
  kettleWeightKg: number;
  targetCartons: number;
  producedCartons: number;
  emptyCansUsed: number;
  emptyCartonsUsed: number;
  wasteCans: number;
  maxWasteAllowed: number;
  extraCans: number;
  weightErrors: number;
  stickerErrors: number;
  technicalDowntimeMin: number;
  materialDowntimeMin: number;
  workersCount: number;
  notes: string;
  branch: string;
  createdBy: string;
  createdAt: number;
  updatedAt: number;
}

export type PressStatus = 'جاهز' | 'تحت الصيانة' | 'متوقف' | 'يحتاج قطع غيار';

export interface PressMachine {
  id: number;
  syncUuid: string;
  pressCode: string;
  name: string;
  location: string;
  status: PressStatus;
  lastMaintenanceDate: string;
  nextMaintenanceDate: string;
  assignedSupervisor: string;
  notes: string;
  branch: string;
  updatedBy: string;
  createdAt: number;
  updatedAt: number;
}

export interface ToolCustodyItem {
  id: number;
  syncUuid: string;
  custodyNo: string;
  toolName: string;
  deliveredQty: number;
  deliveryCondition: string;
  custodianName: string;
  deliveryDate: string;
  monthlyAuditStatus: 'مطابق' | 'يوجد فاقد' | 'بانتظار الجرد' | string;
  countedQty: number;
  missingQty: number;
  actionTaken: string;
  notes: string;
  branch: string;
  updatedBy: string;
  createdAt: number;
  updatedAt: number;
}

export interface DailyChecklistItem {
  id: number;
  syncUuid: string;
  dateKey: string;
  roleKey: EmployeeRoleKey | string;
  assigneeName: string;
  assigneeUsername: string;
  title: string;
  metricTarget: string;
  completed: boolean;
  completedAt: number | null;
  note: string;
  branch: string;
  createdAt: number;
  updatedAt: number;
}

export type OperationalTaskStatus = 'لم يبدأ' | 'جاري' | 'مكتمل' | 'متأخر';

export interface EmployeeTaskItem {
  id: number;
  syncUuid: string;
  title: string;
  department: string;
  assigneeName: string;
  assigneeUsername: string;
  roleKey: EmployeeRoleKey | string;
  priority: 'عالية' | 'متوسطة' | 'منخفضة' | string;
  startDate: string;
  dueDate: string;
  status: OperationalTaskStatus;
  completionPercent: number;
  indicatorTarget: string;
  notes: string;
  branch: string;
  createdBy: string;
  createdAt: number;
  updatedAt: number;
}

export interface SeqaofaEntry {
  id: number;
  syncUuid: string;
  entryDate: string;
  custodianName: 'محمد صديق' | 'عمر عبدالوهاب' | string;
  direction: 'وارد' | 'منصرف';
  amountSdg: number;
  exchangeRate: number;
  equivalentEgp: number;
  counterparty: string;
  referenceNo: string;
  notes: string;
  branch: string;
  createdBy: string;
  createdAt: number;
  updatedAt: number;
}

export interface InTransitShipment {
  id: number;
  syncUuid: string;
  dispatchDate: string;
  invoiceRef: string;
  carrierName: string;
  destinationBranch: string;
  cartons116: number;
  cartons1Kg: number;
  cartonsGallon: number;
  customsSdg: number;
  freightEgp: number;
  status: 'في الطريق' | 'في الجمارك' | 'تم الاستلام';
  receivedDate: string;
  notes: string;
  branch: string;
  createdBy: string;
  createdAt: number;
  updatedAt: number;
}

export interface FinancialBudgetItem {
  id: number;
  syncUuid: string;
  periodKey: string;
  category: 'الخطة المالية' | 'خطة النولون' | 'المشتريات' | 'الرواتب' | 'مصروفات التشغيل' | string;
  responsibleName: string;
  plannedAmount: number;
  actualAmount: number;
  currency: CurrencyCode;
  maxDeviationPercent: number;
  notes: string;
  branch: string;
  updatedBy: string;
  createdAt: number;
  updatedAt: number;
}

export interface EmployeeRewardEvaluation {
  id: number;
  syncUuid: string;
  periodKey: string;
  employeeName: string;
  employeeUsername: string;
  roleTitle: string;
  completionPercent: number;
  deviationPercent: number;
  errorCount: number;
  innovationCount: number;
  bonusTier: 'المستوى الأول (3 شهور)' | 'المستوى الثاني (4 شهور)' | 'المستوى الثالث (5 شهور)' | 'غير مستوفٍ للشرط';
  bonusMonths: 0 | 3 | 4 | 5;
  notes: string;
  branch: string;
  evaluatedBy: string;
  createdAt: number;
  updatedAt: number;
}



