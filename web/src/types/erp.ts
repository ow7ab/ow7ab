/**
 * PMP PAINTS Enterprise ERP — Complete Unified Data Models
 * Strictly mirrors the Android Room entities (Entities.kt) and Cloud Firestore cluster collections.
 */

export type CurrencyCode = 'SDG' | 'EGP' | 'USD';

export type UserRoleType = 'SUPER_ADMIN' | 'MANAGER' | 'EMPLOYEE';

export type JobRole =
  | 'مدير النظام'
  | 'المدير'
  | 'مدير فرع ومصنع مصر'
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

export interface Warehouse {
  id: number;
  name: string;
  active: boolean;
  currency?: CurrencyCode;
  linkedCashbox?: string;
  warehouseType?: 'مبيعات' | 'داخل المصنع/إنتاج' | 'مواد خام' | string;
  code?: string;
  branch?: string;
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
  linkedToEgypt?: boolean;
  defaultCurrency?: CurrencyCode;
  unitCostEgp?: number;
  actualBalance?: number;
  reorderLevel?: number;
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
  createdAt: number;
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

export interface LabFormulation {
  id: number;
  formulaNo: string;
  productName: string;
  paintType: string;
  baseKind: string;
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
  ingredientsJson: string;
  technicalAnalysis: string;
  notes: string;
  approvedForProduction: boolean;
  createdBy: string;
  createdAt: number;
}

export interface FormulationIngredient {
  id: string;
  name: string;
  type: string;
  weight: number;
  density: number;
  solidsPercent: number;
  unitCost: number;
  notes: string;
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

