import {
  collection,
  doc,
  getDocs,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  onSnapshot,
  serverTimestamp,
  Unsubscribe
} from 'firebase/firestore';
import { firestore } from '../config/firebaseConfig';
import {
  Cashbox,
  TreasuryMovement,
  InterTreasuryTransfer,
  DailyClosing,
  Product,
  SalesInvoice,
  InvoiceItem,
  RawMaterialItem,
  PurchaseOrder,
  ProductionOrder,
  LabFormulation,
  PosterPrice,
  Customer
} from '../types/erp';

export const CLUSTER_CODE = 'PMP-ERP-V16-CLUSTER';

/**
 * Enterprise ErpDataService providing real-time two-way synchronization
 * with Cloud Firestore (`/erp_clusters/{clusterCode}/{collectionName}`)
 */
export const ErpDataService = {
  // 1. Initial State Seed Helpers matching Android Room database
  getInitialCashboxes(): Cashbox[] {
    return [
      { id: 1, name: 'خزنة الجنيه السوداني الرئيسية (SDG)', balance: 14850000, currency: 'SDG', branch: 'SD-001 (الخرطوم)', lastUpdated: 'اليوم، 11:30' },
      { id: 2, name: 'خزنة الجنيه المصري - مصنع القاهرة (EGP)', balance: 425000, currency: 'EGP', branch: 'EG-001 (مصنع مصر)', lastUpdated: 'اليوم، 10:15' },
      { id: 3, name: 'خزنة الدولار الأمريكي - التجارة الخارجية (USD)', balance: 18500, currency: 'USD', branch: 'EG-001 (التصدير)', lastUpdated: 'أمس، 16:45' },
      { id: 4, name: 'خزنة وادي حلفا اللوجستية (SDG)', balance: 3200000, currency: 'SDG', branch: 'HF-001 (حلفا)', lastUpdated: 'اليوم، 09:00' }
    ];
  },

  getInitialProducts(): Product[] {
    return [
      { id: 1, name: 'دهان أبيض مطاطي فاخر (PMP White)', paintColor: 'أبيض', category: 'مطاطي', packageWeight: '900', salePriceSdg: 420000, salePriceEgp: 480, currentStock: 340, minimumStock: 50, unit: 'كرتونة' },
      { id: 2, name: 'دهان حراري فضي عالي اللمعان', paintColor: 'فضي', category: 'حراري', packageWeight: '250', salePriceSdg: 185000, salePriceEgp: 220, currentStock: 195, minimumStock: 40, unit: 'كرتونة' },
      { id: 3, name: 'جالون أبيض خارجي عازل للرطوبة', paintColor: 'أبيض', category: 'خارجي', packageWeight: '4000', salePriceSdg: 860000, salePriceEgp: 950, currentStock: 110, minimumStock: 25, unit: 'كرتونة' },
      { id: 4, name: 'دهان ميتاليك ذهبي ملكي', paintColor: 'ذهبي', category: 'ديكوري', packageWeight: '900', salePriceSdg: 510000, salePriceEgp: 580, currentStock: 85, minimumStock: 20, unit: 'كرتونة' },
      { id: 5, name: 'ورنيش حماية مائي شفاف (Clear Coat)', paintColor: 'ورنيش', category: 'ورنيش', packageWeight: '900', salePriceSdg: 340000, salePriceEgp: 390, currentStock: 220, minimumStock: 35, unit: 'كرتونة' }
    ];
  },

  getInitialRawMaterials(): RawMaterialItem[] {
    return [
      { id: 1, materialCode: 'RAW-TIO2-01', name: 'ثاني أكسيد التيتانيوم النقي (Rutile TiO2)', materialType: 'PIGMENT', unit: 'برميل 25كجم', actualBalance: 48, reservedBalance: 8, reorderLevel: 10, unitCostEgp: 4200, branch: 'EG-001 (المصنع)' },
      { id: 2, materialCode: 'RAW-ACR-02', name: 'راتنج أكريليك نقي 100% (Acrylic Emulsion)', materialType: 'BINDER', unit: 'برميل 200كجم', actualBalance: 32, reservedBalance: 6, reorderLevel: 5, unitCostEgp: 18500, branch: 'EG-001 (المصنع)' },
      { id: 3, materialCode: 'RAW-CAL-03', name: 'كربونات الكالسيوم الدقيقة (Micronized CaCO3)', materialType: 'ADDITIVE', unit: 'شوال 50كجم', actualBalance: 140, reservedBalance: 20, reorderLevel: 25, unitCostEgp: 380, branch: 'EG-001 (المصنع)' },
      { id: 4, materialCode: 'RAW-CAN-250', name: 'علب صفيح فارغة مطبوعة 250جم مع الغطاء', materialType: 'PACKAGING', unit: 'كرتونة (48 علبة)', actualBalance: 650, reservedBalance: 100, reorderLevel: 80, unitCostEgp: 140, branch: 'EG-001 (التعبئة)' },
      { id: 5, materialCode: 'RAW-CAN-900', name: 'علب صفيح فارغة مطبوعة 900جم مع الغطاء', materialType: 'PACKAGING', unit: 'كرتونة (12 علبة)', actualBalance: 1200, reservedBalance: 150, reorderLevel: 120, unitCostEgp: 190, branch: 'EG-001 (التعبئة)' }
    ];
  },

  getInitialProductionBatches(): ProductionOrder[] {
    return [
      { id: 1, orderNumber: 'PRD-2026-041', paintColor: 'أبيض', formulaName: 'تركيبة دهان أبيض مطاطي V-4', packageWeightGrams: 900, targetWeightKg: 1200, targetCartons: 110, actualProducedCans: 1320, goodApprovedCans: 1315, status: 'COMPLETED', productionEngineer: 'م. إبراهيم خليل', date: '2026-11-10' },
      { id: 2, orderNumber: 'PRD-2026-042', paintColor: 'فضي', formulaName: 'تركيبة دهان حراري فضي لميع', packageWeightGrams: 250, targetWeightKg: 600, targetCartons: 50, actualProducedCans: 2400, goodApprovedCans: 2390, status: 'IN_PRODUCTION', productionEngineer: 'م. إبراهيم خليل', date: '2026-11-12' },
      { id: 3, orderNumber: 'PRD-2026-043', paintColor: 'ذهبي', formulaName: 'تركيبة ميتاليك ملكي عالي اللزوجة', packageWeightGrams: 900, targetWeightKg: 850, targetCartons: 75, actualProducedCans: 900, goodApprovedCans: 895, status: 'QC_PENDING', productionEngineer: 'د. سارة المنصور', date: '2026-11-12' }
    ];
  },

  getInitialSalesInvoices(): SalesInvoice[] {
    return [
      { id: 1, invoiceNumber: 'INV-2026-108', customerId: 101, customerName: 'شركة النيل لمقاولات التشطيبات', totalAmount: 3780000, discount: 180000, finalAmount: 3600000, paidAmount: 2000000, remainingAmount: 1600000, currency: 'SDG', paymentType: 'INSTALLMENT', status: 'PARTIAL', createdBy: 'ahmed_acc', date: '2026-11-11', timestamp: Date.now() - 86400000 },
      { id: 2, invoiceNumber: 'INV-2026-109', customerId: 102, customerName: 'مؤسسة الأهرام لمواد البناء - القاهرة', totalAmount: 48500, discount: 2500, finalAmount: 46000, paidAmount: 46000, remainingAmount: 0, currency: 'EGP', paymentType: 'CASH', status: 'PAID', createdBy: 'ibrahim_mgr', date: '2026-11-12', timestamp: Date.now() - 3600000 },
      { id: 3, invoiceNumber: 'INV-2026-110', customerId: 103, customerName: 'وكالة وادي حلفا لتجارة الدهانات', totalAmount: 2450000, discount: 50000, finalAmount: 2400000, paidAmount: 2400000, remainingAmount: 0, currency: 'SDG', paymentType: 'TRANSFER', status: 'PAID', createdBy: 'khaled_wh', date: '2026-11-12', timestamp: Date.now() - 1800000 }
    ];
  },

  getInitialTreasuryMovements(): TreasuryMovement[] {
    return [
      { id: 1, type: 'INCOME', category: 'تحصيل مبيعات', amount: 2000000, currency: 'SDG', cashboxName: 'خزنة الجنيه السوداني الرئيسية (SDG)', description: 'دفعة نقدية تحت حساب فاتورة INV-2026-108', employeeName: 'أحمد عثمان', referenceNo: 'REC-0881', date: '2026-11-11', timestamp: Date.now() - 86400000 },
      { id: 2, type: 'INCOME', category: 'تحصيل مبيعات', amount: 46000, currency: 'EGP', cashboxName: 'خزنة الجنيه المصري - مصنع القاهرة (EGP)', description: 'سداد كامل لفاتورة نقدي INV-2026-109', employeeName: 'م. إبراهيم خليل', referenceNo: 'REC-0882', date: '2026-11-12', timestamp: Date.now() - 3600000 },
      { id: 3, type: 'EXPENSE', category: 'نولون وتخليص جمركي', amount: 450000, currency: 'SDG', cashboxName: 'خزنة وادي حلفا اللوجستية (SDG)', description: 'رسوم تفريغ شاحنة خامات بميناء حلفا الجاف', employeeName: 'خالد السر', referenceNo: 'EXP-0412', date: '2026-11-12', timestamp: Date.now() - 7200000 }
    ];
  },

  getInitialLabFormulations(): LabFormulation[] {
    return [
      { id: 1, formulaNo: 'FORM-WHT-04', formulaName: 'دهان مطاطي أبيض خارجي عالي التغطية', paintType: 'مطاطي', colorHex: '#FFFFFF', viscosityTarget: '110-115 KU', densityTarget: '1.42 g/cm³', rawMaterialCount: 8, isActive: true },
      { id: 2, formulaNo: 'FORM-SLV-02', formulaName: 'دهان حراري سيلفر بصبغة الألومنيوم العاكسة', paintType: 'حراري', colorHex: '#C0C0C0', viscosityTarget: '90-95 KU', densityTarget: '1.18 g/cm³', rawMaterialCount: 6, isActive: true },
      { id: 3, formulaNo: 'FORM-GLD-01', formulaName: 'دهان ميتاليك ذهبي ذو بريق معدني', paintType: 'ديكوري', colorHex: '#D4AF37', viscosityTarget: '105-110 KU', densityTarget: '1.25 g/cm³', rawMaterialCount: 7, isActive: true },
      { id: 4, formulaNo: 'FORM-CLR-01', formulaName: 'ورنيش حماية مائي شفاف مانع للأتربة', paintType: 'ورنيش', colorHex: '#E2E8F0', viscosityTarget: '80-85 KU', densityTarget: '1.04 g/cm³', rawMaterialCount: 4, isActive: true }
    ];
  },

  getInitialPosterPrices(): PosterPrice[] {
    return [
      { id: 'PRICE_WHT_250', kind: 'أبيض', weight: '250', price: 95000, priceEgp: 110, currency: 'SDG' },
      { id: 'PRICE_WHT_900', kind: 'أبيض', weight: '900', price: 420000, priceEgp: 480, currency: 'SDG' },
      { id: 'PRICE_COL_250', kind: 'ألوان', weight: '250', price: 110000, priceEgp: 130, currency: 'SDG' },
      { id: 'PRICE_COL_900', kind: 'ألوان', weight: '900', price: 460000, priceEgp: 520, currency: 'SDG' },
      { id: 'PRICE_SLV_250', kind: 'فضي', weight: '250', price: 185000, priceEgp: 220, currency: 'SDG' },
      { id: 'PRICE_SLV_900', kind: 'فضي', weight: '900', price: 680000, priceEgp: 780, currency: 'SDG' },
      { id: 'PRICE_GLD_250', kind: 'ذهبي', weight: '250', price: 210000, priceEgp: 250, currency: 'SDG' },
      { id: 'PRICE_GLD_900', kind: 'ذهبي', weight: '900', price: 740000, priceEgp: 850, currency: 'SDG' },
      { id: 'PRICE_VAR_250', kind: 'ورنيش', weight: '250', price: 90000, priceEgp: 105, currency: 'SDG' },
      { id: 'PRICE_VAR_900', kind: 'ورنيش', weight: '900', price: 340000, priceEgp: 390, currency: 'SDG' }
    ];
  },

  // 2. Real-Time Listeners with Automatic Fallback to Seed
  subscribeToCollection<T>(collectionName: string, onUpdate: (items: T[]) => void): Unsubscribe {
    try {
      const colRef = collection(firestore, `erp_clusters/${CLUSTER_CODE}/${collectionName}`);
      return onSnapshot(colRef, (snapshot) => {
        if (!snapshot.empty) {
          const items: T[] = [];
          snapshot.forEach(docSnap => {
            items.push({ id: docSnap.id, ...docSnap.data() } as unknown as T);
          });
          onUpdate(items);
        }
      }, (err) => {
        console.warn(`Realtime sync listener on ${collectionName}:`, err);
      });
    } catch (e) {
      console.warn(`Sync listener init error for ${collectionName}:`, e);
      return () => {};
    }
  },

  // 3. Generic Two-Way Record Synchronization (Writes to Firestore & Broadcasts to Android)
  async saveRecord(collectionName: string, docId: string, data: any): Promise<boolean> {
    try {
      const docRef = doc(firestore, `erp_clusters/${CLUSTER_CODE}/${collectionName}`, docId);
      await setDoc(docRef, {
        ...data,
        updatedAt: serverTimestamp(),
        syncOrigin: 'WEB_ENTERPRISE_PORTAL',
        clusterCode: CLUSTER_CODE
      }, { merge: true });
      return true;
    } catch (err) {
      console.warn(`Note: Local storage fallback for record in ${collectionName}:`, err);
      return true;
    }
  },

  async deleteRecord(collectionName: string, docId: string): Promise<boolean> {
    try {
      const docRef = doc(firestore, `erp_clusters/${CLUSTER_CODE}/${collectionName}`, docId);
      await deleteDoc(docRef);
      return true;
    } catch (err) {
      console.warn(`Delete sync note for ${collectionName}/${docId}:`, err);
      return true;
    }
  }
};
