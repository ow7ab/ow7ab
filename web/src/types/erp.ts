/**
 * PMP PAINTS Enterprise ERP — Complete TypeScript Data Models & Schemas
 * Directly mirrors the Android Room entities, Firestore collections, and RBAC contracts.
 */

export type CurrencyCode = 'SDG' | 'EGP' | 'USD';

export type JobRole =
  | 'مدير النظام'
  | 'المدير'
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
  | 'مراجع مالي وتقارير'
  | string;

export interface UserAccount {
  id: number;
  userCode: string;
  fullName: string;
  username: string;
  phoneOrEmail: string;
  role: JobRole;
  branch: string;
  permissions: string;
  serverSyncToken: string;
  remoteServerId?: string;
  syncStatus: 'APPROVED_BY_MANAGER' | 'PENDING_MANAGER_APPROVAL' | 'REJECTED';
  active: boolean;
  isCurrentSession?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface LinkedAccountDoc {
  userCode: string;
  ownerId: string;
  linkedUid: string;
  clusterCode: string;
  fullName: string;
  username: string;
  role: string;
  branch: string;
  permissions: string;
  serverSyncToken: string;
  active: boolean;
  createdAt: any;
  updatedAt: any;
}

export type LinkedAccountModel = LinkedAccountDoc;

export interface Cashbox {
  id: number;
  name: string;
  balance: number;
  currency: CurrencyCode;
  branch: string;
  lastUpdated: string;
}

export interface TreasuryMovement {
  id: number;
  type: 'INCOME' | 'EXPENSE';
  category: string;
  amount: number;
  currency: CurrencyCode;
  cashboxName: string;
  description: string;
  employeeName: string;
  referenceNo?: string;
  date: string;
  timestamp: number;
}

export interface InterTreasuryTransfer {
  id: number;
  transferNumber: string;
  fromCashbox: string;
  toCashbox: string;
  amountSent: number;
  currencySent: CurrencyCode;
  amountReceived: number;
  currencyReceived: CurrencyCode;
  exchangeRate: number;
  transferFee: number;
  status: 'PENDING' | 'COMPLETED' | 'CANCELLED';
  createdBy: string;
  date: string;
  timestamp: number;
}

export interface DailyClosing {
  id: number;
  closingNumber: string;
  cashboxName: string;
  closingBalance: number;
  systemBalance: number;
  difference: number;
  currency: CurrencyCode;
  closingDate: string;
  closedBy: string;
  notes?: string;
  timestamp: number;
}

export interface Customer {
  id: number;
  name: string;
  phone: string;
  address: string;
  branch: string;
  creditLimit: number;
  currentBalance: number;
  currency: CurrencyCode;
  notes?: string;
}

export interface Product {
  id: number;
  name: string;
  paintColor: string;
  category: string;
  packageWeight: '250' | '900' | '4000'; // grams
  salePriceSdg: number;
  salePriceEgp: number;
  currentStock: number;
  minimumStock: number;
  unit: string;
}

export interface InvoiceItem {
  id: number;
  invoiceId: number;
  productId: number;
  productName: string;
  packageWeight: string;
  quantity: number;
  unitPrice: number;
  totalPrice: number;
  currency: CurrencyCode;
}

export interface SalesInvoice {
  id: number;
  invoiceNumber: string;
  customerId: number;
  customerName: string;
  totalAmount: number;
  discount: number;
  finalAmount: number;
  paidAmount: number;
  remainingAmount: number;
  currency: CurrencyCode;
  paymentType: 'CASH' | 'INSTALLMENT' | 'TRANSFER';
  status: 'PAID' | 'PARTIAL' | 'UNPAID';
  createdBy: string;
  date: string;
  timestamp: number;
  items?: InvoiceItem[];
}

export interface RawMaterialItem {
  id: number;
  materialCode: string;
  name: string;
  materialType: 'BINDER' | 'PIGMENT' | 'SOLVENT' | 'ADDITIVE' | 'PACKAGING' | string;
  unit: string;
  actualBalance: number;
  reservedBalance: number;
  reorderLevel: number;
  unitCostEgp: number;
  branch: string;
}

export interface PurchaseOrder {
  id: number;
  poNumber: string;
  supplierName: string;
  materialName: string;
  orderedQty: number;
  receivedQty: number;
  unit: string;
  unitPrice: number;
  currency: CurrencyCode;
  totalCost: number;
  status: 'APPROVED' | 'PARTIAL' | 'COMPLETED' | 'CANCELLED';
  createdBy: string;
  date: string;
}

export interface GoodsReceiptNote {
  id: number;
  grnNumber: string;
  poNumber: string;
  supplierName: string;
  materialName: string;
  expectedQty: number;
  actualReceivedQty: number;
  acceptedQty: number;
  rejectedQty: number;
  discrepancyReason?: string;
  status: 'UNDER_INSPECTION' | 'ACCEPTED' | 'REJECTED' | 'COMPLETED';
  storekeeperName: string;
  date: string;
}

export interface ProductionOrder {
  id: number;
  orderNumber: string;
  paintColor: string;
  formulaName: string;
  packageWeightGrams: 250 | 900 | 4000;
  targetWeightKg: number;
  targetCartons: number;
  actualProducedCans: number;
  goodApprovedCans: number;
  status: 'DRAFT' | 'APPROVED' | 'IN_PRODUCTION' | 'QC_PENDING' | 'COMPLETED';
  productionEngineer: string;
  date: string;
}

export interface LabFormulation {
  id: number;
  formulaNo: string;
  formulaName: string;
  paintType: string;
  colorHex: string;
  viscosityTarget: string;
  densityTarget: string;
  rawMaterialCount: number;
  isActive: boolean;
}

export interface PosterPrice {
  id: string;
  weight: string;
  kind: string;
  price: number;
  priceEgp: number;
  currency: CurrencyCode;
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
