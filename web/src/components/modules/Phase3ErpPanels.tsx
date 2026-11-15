import React, { useEffect, useMemo, useState } from 'react';
import {
  ArrowLeftRight,
  CheckCircle2,
  ClipboardCheck,
  Factory,
  FlaskConical,
  PackageCheck,
  RotateCcw,
  ShieldAlert,
  Truck,
  Warehouse as WarehouseIcon
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { useAuth } from '../../context/AuthContext';
import { useTransactionGuard } from '../../hooks/useTransactionGuard';
import { OPERATION_LOADING_LABELS } from '../../services/IdempotencyService';
import {
  ErpDataService,
  DEFAULT_PACKAGING_RULES,
  resolveWarehouseClass,
  getCansPerCarton,
  receivePurchaseToRawMaterialsInState,
  createProductionOrderModel,
  issueRawMaterialsForOrderInState,
  inspectQcAndPostToFinishedGoodsInState,
  validateTransferRoute,
  canApproveTransfer,
  postMovementInState,
  reverseMovementInState,
  buildPurchaseReceiptJournalPayload,
  buildProductionIssueJournalPayload,
  buildQcFinishedGoodsJournalPayload
} from '../../services/ErpDataService';
import { GRANULAR_ACTIONS } from '../../services/SecurityService';
import {
  FactoryItem,
  FactoryMachine,
  FactoryMove,
  InventoryMovement,
  LabFormulation,
  PackagingRule,
  Product,
  ProductionOrder,
  PurchaseReceipt,
  QcInspection,
  Warehouse,
  WarehouseClass,
  WarehouseStockBalance,
  WarehouseTransfer,
  WarehouseTransferItem
} from '../../types/erp';

function classLabel(cls: WarehouseClass): string {
  if (cls === 'RAW_MATERIALS') return 'مخزن المواد الخام';
  if (cls === 'FINISHED_GOODS') return 'مخزن الإنتاج التام';
  return 'مخزن المبيعات';
}

function classBadgeVariant(cls: WarehouseClass): 'gold' | 'cyan' | 'green' {
  if (cls === 'RAW_MATERIALS') return 'gold';
  if (cls === 'FINISHED_GOODS') return 'cyan';
  return 'green';
}

async function persistMovementLedgerDiff(
  prevMovements: InventoryMovement[],
  nextMovements: InventoryMovement[],
  prevBalances: WarehouseStockBalance[],
  nextBalances: WarehouseStockBalance[]
): Promise<void> {
  const prevByUuid = new Map(prevMovements.map((m) => [m.syncUuid, m]));
  const changedMovements = nextMovements.filter((m) => {
    const old = prevByUuid.get(m.syncUuid);
    return !old || old.status !== m.status || old.recordVersion !== m.recordVersion;
  });

  const prevBalByUuid = new Map(prevBalances.map((b) => [b.syncUuid, b]));
  const changedBalances = nextBalances.filter((b) => {
    const old = prevBalByUuid.get(b.syncUuid);
    return (
      !old ||
      old.onHandQty !== b.onHandQty ||
      old.reservedQty !== b.reservedQty ||
      old.inTransitQty !== b.inTransitQty ||
      old.quarantineQty !== b.quarantineQty ||
      old.availableQty !== b.availableQty ||
      old.updatedAt !== b.updatedAt
    );
  });

  await Promise.all([
    ...changedMovements.map((m) => ErpDataService.saveRecord('inventory_movements', m.syncUuid, m)),
    ...changedBalances.map((b) => ErpDataService.saveRecord('warehouse_stock_balances', b.syncUuid, b))
  ]);
}

// =============================================================================
// 1. PROCUREMENT GRN -> RAW MATERIALS PANEL (PurchasesModuleView)
// =============================================================================
export const WebProcurementGrnPanel: React.FC<{
  factoryItems: FactoryItem[];
  onNotice?: (type: 'ok' | 'err', text: string) => void;
}> = ({ factoryItems, onNotice }) => {
  const { userProfile, canPerformAction } = useAuth();
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [receipts, setReceipts] = useState<PurchaseReceipt[]>([]);
  const [movements, setMovements] = useState<InventoryMovement[]>([]);
  const [balances, setBalances] = useState<WarehouseStockBalance[]>([]);

  const [materialId, setMaterialId] = useState<number>(0);
  const [rawWarehouseId, setRawWarehouseId] = useState<number>(0);
  const [supplierName, setSupplierName] = useState('');
  const [poNo, setPoNo] = useState('');
  const [batchNumber, setBatchNumber] = useState('');
  const [expiryDate, setExpiryDate] = useState('');
  const [orderedQty, setOrderedQty] = useState('100');
  const [receivedQty, setReceivedQty] = useState('100');
  const [acceptedQty, setAcceptedQty] = useState('100');
  const [rejectedQty, setRejectedQty] = useState('0');
  const [unitCost, setUnitCost] = useState('0');
  const [currency, setCurrency] = useState<'EGP' | 'SDG' | 'USD'>('EGP');
  const [discrepancyReason, setDiscrepancyReason] = useState('');
  const [notes, setNotes] = useState('');
  const grnGuard = useTransactionGuard('phase3_grn_receipt', 'RAW_MATERIAL_SUPPLY');

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<Warehouse>('warehouses', setWarehouses),
      ErpDataService.subscribeToCollection<PurchaseReceipt>('purchase_receipts', setReceipts),
      ErpDataService.subscribeToCollection<InventoryMovement>('inventory_movements', setMovements),
      ErpDataService.subscribeToCollection<WarehouseStockBalance>('warehouse_stock_balances', setBalances)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  const rawWarehouses = useMemo(
    () => warehouses.filter((w) => resolveWarehouseClass(w) === 'RAW_MATERIALS'),
    [warehouses]
  );

  useEffect(() => {
    if (factoryItems.length > 0 && !factoryItems.some((i) => Number(i.id) === materialId)) {
      setMaterialId(Number(factoryItems[0].id));
    }
    if (rawWarehouses.length > 0 && !rawWarehouses.some((w) => Number(w.id) === rawWarehouseId)) {
      setRawWarehouseId(Number(rawWarehouses[0].id));
    }
  }, [factoryItems, rawWarehouses, materialId, rawWarehouseId]);

  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE) || canPerformAction(GRANULAR_ACTIONS.INVENTORY_OPS);

  const ensureRawWarehouse = async () => {
    const now = Date.now();
    const wh: Warehouse = {
      id: now,
      name: 'مخزن المواد الخام داخل المصنع',
      active: true,
      currency: 'EGP',
      warehouseType: 'مواد خام',
      warehouseClass: 'RAW_MATERIALS',
      code: `WH-RAW-${String(now).slice(-4)}`,
      branch: 'المصنع',
      allowDirectSales: false,
      allowProductionReceipt: false,
      updatedAt: now
    };
    await ErpDataService.saveRecord('warehouses', `WH-${now}`, wh);
    setRawWarehouseId(now);
    onNotice?.('ok', 'تم تهيئة مخزن المواد الخام داخل المصنع بنجاح');
  };

  const handleSubmitGrn = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || grnGuard.isSubmitting) return;
    const rawWh = rawWarehouses.find((w) => Number(w.id) === Number(rawWarehouseId));
    if (!rawWh) {
      onNotice?.('err', 'يجب وجود مخزن مصنف (RAW_MATERIALS) لاستلام المشتريات');
      return;
    }
    const item = factoryItems.find((i) => Number(i.id) === Number(materialId));
    if (!item) {
      onNotice?.('err', 'اختر المادة الخام المطلوب استلامها');
      return;
    }

    const actor = userProfile?.fullName || userProfile?.username || 'WEB_PROCUREMENT';
    const res = await grnGuard.runGuarded({
      guardKey: `grn_${rawWh.id}_${item.id}_${receivedQty}`,
      operationType: 'RAW_MATERIAL_SUPPLY',
      userId: userProfile?.username || actor,
      documentReference: `GRN-${item.id}-${receivedQty}`,
      targetCollection: 'purchase_receipts',
      payloadSummary: `استلام GRN ${item.name} (${acceptedQty} مقبول / ${rejectedQty} حجر)`,
      execute: async ({ idempotencyKey }) => {
        const { receipt, state } = receivePurchaseToRawMaterialsInState(
          { movements, balances },
          {
            poNo: poNo.trim() || undefined,
            supplierName: supplierName.trim() || 'مورد خامات معتمد',
            rawWarehouse: rawWh,
            materialId: Number(item.id),
            materialCode: item.itemCode || `RAW-${item.id}`,
            materialName: item.name,
            unit: item.unit || 'كجم',
            batchNumber: batchNumber.trim() || undefined,
            expiryDate: expiryDate.trim() || undefined,
            orderedQty: Number(orderedQty) || 0,
            receivedQty: Number(receivedQty) || 0,
            acceptedQty: Number(acceptedQty) || 0,
            rejectedQty: Number(rejectedQty) || 0,
            unitCost: Number(unitCost) || item.unitCostEgp || 0,
            currency,
            discrepancyReason,
            notes,
            performedBy: actor
          }
        );

        const safeGrnId = `GRN-${idempotencyKey}`;
        await ErpDataService.saveRecord('purchase_receipts', safeGrnId, { ...receipt, syncUuid: safeGrnId }, userProfile?.username);
        await persistMovementLedgerDiff(movements, state.movements, balances, state.balances);

        const grnJournal = buildPurchaseReceiptJournalPayload({
          grnSyncUuid: safeGrnId,
          grnNo: receipt.grnNo,
          receiptDate: receipt.receiptDate,
          supplierName: receipt.supplierName,
          materialName: receipt.materialName,
          unit: receipt.unit,
          acceptedQty: receipt.acceptedQty,
          unitCost: receipt.unitCost,
          currency: receipt.currency,
          performedBy: actor
        });
        await ErpDataService.persistJournalPayload(grnJournal).catch(() => {});

        if (receipt.acceptedQty > 0) {
          const fm: FactoryMove = {
            id: receipt.createdAt + 2,
            moveDate: receipt.receiptDate,
            kind: 'توريد خامات',
            itemId: Number(item.id),
            itemName: item.name,
            qty: receipt.acceptedQty,
            batch: receipt.batchNumber,
            note: `سند استلام GRN ${receipt.grnNo}${receipt.rejectedQty > 0 ? ` (مرفوض: ${receipt.rejectedQty})` : ''}`,
            actor,
            counterparty: receipt.supplierName,
            linked: Boolean(item.linkedToEgypt),
            createdAt: receipt.createdAt + 2
          };
          await ErpDataService.saveRecord('factory_moves', `FM-${idempotencyKey}`, fm, userProfile?.username);
        }

        await ErpDataService.logAudit(
          actor,
          'PURCHASE_GRN_RECEIPT',
          `سند استلام ${receipt.grnNo}: ${receipt.materialName} — مقبول ${receipt.acceptedQty}، مرفوض/حجر ${receipt.rejectedQty}`
        );
        return { resultReference: safeGrnId, result: receipt };
      }
    });

    if (res.ok) {
      const r = res.data as PurchaseReceipt | undefined;
      onNotice?.(
        'ok',
        res.replayed
          ? res.message
          : `تم اعتماد سند الاستلام ${r?.grnNo || ''} وترحيل ${r?.acceptedQty || acceptedQty} ${r?.unit || ''} إلى مخزن المواد الخام`
      );
      setBatchNumber('');
      setDiscrepancyReason('');
      setNotes('');
    } else {
      onNotice?.('err', res.message);
    }
  };

  return (
    <GlassCard className="p-5 border border-cyan-500/30 space-y-4">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
        <div className="flex items-center gap-2.5">
          <PackageCheck className="w-5 h-5 text-cyan-400" />
          <div>
            <h3 className="text-sm font-black text-theme-primary">
              استلام وفحص المشتريات الرسمي (GRN ← مخزن المواد الخام)
            </h3>
            <p className="text-[11px] text-theme-muted">
              الكمية المقبولة تُرحّل فوراً للرصيد المتاح بالمخزن الخام، والكمية المرفوضة تُعزل تحت الحجر (Quarantine).
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <GlassBadge variant="cyan">{receipts.length} سند استلام</GlassBadge>
          {rawWarehouses.length === 0 && canCreate && (
            <GlassButton size="sm" variant="secondary" onClick={ensureRawWarehouse}>
              + تهيئة مخزن المواد الخام
            </GlassButton>
          )}
        </div>
      </div>

      <form onSubmit={handleSubmitGrn} className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 text-xs">
        <div>
          <label className="block font-bold text-theme-secondary mb-1">المادة الخام *</label>
          <select
            value={materialId}
            onChange={(e) => setMaterialId(Number(e.target.value))}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          >
            {factoryItems.map((it) => (
              <option key={it.id} value={it.id}>
                {it.name} ({it.unit})
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block font-bold text-theme-secondary mb-1">مخزن المواد الخام المستلم *</label>
          <select
            value={rawWarehouseId}
            onChange={(e) => setRawWarehouseId(Number(e.target.value))}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          >
            {rawWarehouses.length === 0 && <option value={0}>لا يوجد مخزن مواد خام</option>}
            {rawWarehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block font-bold text-theme-secondary mb-1">المورد *</label>
          <input
            type="text"
            value={supplierName}
            onChange={(e) => setSupplierName(e.target.value)}
            placeholder="اسم المورد"
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          />
        </div>
        <div>
          <label className="block font-bold text-theme-secondary mb-1">رقم التشغيلة Batch *</label>
          <input
            type="text"
            value={batchNumber}
            onChange={(e) => setBatchNumber(e.target.value)}
            placeholder="BAT-RAW-01"
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
          />
        </div>

        <div>
          <label className="block font-bold text-theme-secondary mb-1">الكمية المطلوبة</label>
          <input
            type="number"
            step="any"
            value={orderedQty}
            onChange={(e) => setOrderedQty(e.target.value)}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
          />
        </div>
        <div>
          <label className="block font-bold text-theme-secondary mb-1">الكمية المستلمة *</label>
          <input
            type="number"
            step="any"
            required
            value={receivedQty}
            onChange={(e) => {
              const rec = Number(e.target.value) || 0;
              const rej = Number(rejectedQty) || 0;
              setReceivedQty(e.target.value);
              setAcceptedQty(String(Math.max(0, rec - rej)));
            }}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
          />
        </div>
        <div>
          <label className="block font-bold text-theme-secondary mb-1">المقبولة للرصيد المتاح *</label>
          <input
            type="number"
            step="any"
            required
            value={acceptedQty}
            onChange={(e) => {
              const acc = Number(e.target.value) || 0;
              const rec = Number(receivedQty) || 0;
              setAcceptedQty(e.target.value);
              setRejectedQty(String(Math.max(0, rec - acc)));
            }}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-emerald-400 font-mono font-bold"
          />
        </div>
        <div>
          <label className="block font-bold text-theme-secondary mb-1">المرفوضة (حجر)</label>
          <input
            type="number"
            step="any"
            value={rejectedQty}
            onChange={(e) => {
              const rej = Number(e.target.value) || 0;
              const rec = Number(receivedQty) || 0;
              setRejectedQty(e.target.value);
              setAcceptedQty(String(Math.max(0, rec - rej)));
            }}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-amber-400 font-mono font-bold"
          />
        </div>

        <div>
          <label className="block font-bold text-theme-secondary mb-1">تكلفة الوحدة ({currency})</label>
          <div className="flex gap-1.5">
            <input
              type="number"
              step="any"
              value={unitCost}
              onChange={(e) => setUnitCost(e.target.value)}
              className="flex-1 px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
            />
            <select
              value={currency}
              onChange={(e) => setCurrency(e.target.value as 'EGP' | 'SDG' | 'USD')}
              className="px-2 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
            >
              <option value="EGP">EGP</option>
              <option value="SDG">SDG</option>
              <option value="USD">USD</option>
            </select>
          </div>
        </div>
        <div>
          <label className="block font-bold text-theme-secondary mb-1">تاريخ الصلاحية</label>
          <input
            type="text"
            value={expiryDate}
            onChange={(e) => setExpiryDate(e.target.value)}
            placeholder="2027-12-31"
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono"
          />
        </div>
        <div>
          <label className="block font-bold text-theme-secondary mb-1">سبب الرفض أو العجز (إلزامي عند الاختلاف)</label>
          <input
            type="text"
            value={discrepancyReason}
            onChange={(e) => setDiscrepancyReason(e.target.value)}
            placeholder="تلف عبوات / فرق ميزان..."
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          />
        </div>
        <div className="flex items-end">
          <GlassButton
            type="submit"
            variant="primary"
            disabled={!canCreate || rawWarehouses.length === 0}
            isLoading={grnGuard.isSubmitting}
            loadingText={OPERATION_LOADING_LABELS.RAW_MATERIAL_SUPPLY}
            className="w-full justify-center"
          >
            اعتماد GRN للمخزن الخام
          </GlassButton>
        </div>
      </form>

      {receipts.length > 0 && (
        <div className="pt-2 border-t border-theme space-y-2">
          <p className="text-xs font-bold text-theme-secondary">أحدث سندات الاستلام والفحص الموثقة:</p>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
            {receipts
              .slice()
              .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
              .slice(0, 6)
              .map((pr) => (
                <div
                  key={pr.syncUuid}
                  className="p-3 rounded-xl bg-theme-base border border-theme flex justify-between items-center gap-2 text-xs"
                >
                  <div className="min-w-0">
                    <p className="font-bold text-theme-primary truncate">
                      {pr.grnNo} • {pr.materialName}
                    </p>
                    <p className="text-[10px] text-theme-muted font-mono">
                      مقبول: {pr.acceptedQty} {pr.unit} | حجر: {pr.rejectedQty} • Batch: {pr.batchNumber}
                    </p>
                    {pr.discrepancyReason && (
                      <p className="text-[10px] text-amber-400 truncate">السبب: {pr.discrepancyReason}</p>
                    )}
                  </div>
                  <GlassBadge
                    variant={pr.qcStatus === 'APPROVED' ? 'green' : pr.qcStatus === 'PARTIAL' ? 'gold' : 'red'}
                  >
                    {pr.qcStatus}
                  </GlassBadge>
                </div>
              ))}
          </div>
        </div>
      )}
    </GlassCard>
  );
};

// =============================================================================
// 2. PRODUCTION ORDER BOM + RAW ISSUE + QC GATE PANEL (FactoryModuleView)
// =============================================================================
export const WebProductionOrderQcPanel: React.FC<{
  formulations: LabFormulation[];
  factoryItems: FactoryItem[];
  products: Product[];
  warehouses: Warehouse[];
  machines: FactoryMachine[];
  onNotice?: (type: 'ok' | 'err', text: string) => void;
}> = ({ formulations, factoryItems, products, warehouses, machines, onNotice }) => {
  const { userProfile, canPerformAction } = useAuth();
  const [orders, setOrders] = useState<ProductionOrder[]>([]);
  const [qcInspections, setQcInspections] = useState<QcInspection[]>([]);
  const [movements, setMovements] = useState<InventoryMovement[]>([]);
  const [balances, setBalances] = useState<WarehouseStockBalance[]>([]);
  const [packagingRules, setPackagingRules] = useState<PackagingRule[]>([]);

  const [formulaId, setFormulaId] = useState<number>(0);
  const [productId, setProductId] = useState<number>(0);
  const [productName, setProductName] = useState('بوهية أبيض 900 جم');
  const [paintColor, setPaintColor] = useState('أبيض');
  const [weightGrams, setWeightGrams] = useState<number>(900);
  const [targetCartons, setTargetCartons] = useState('10');
  const [machineName, setMachineName] = useState('خلاط رئيسي 1');
  const [engineer, setEngineer] = useState('مهندس الإنتاج');

  const [orderToIssue, setOrderToIssue] = useState<ProductionOrder | null>(null);
  const [issueMatId, setIssueMatId] = useState<number>(0);
  const [issueQty, setIssueQty] = useState('25');
  const [returnQty, setReturnQty] = useState('0');
  const [wasteQty, setWasteQty] = useState('0');

  const [orderToInspect, setOrderToInspect] = useState<ProductionOrder | null>(null);
  const [qcDensity, setQcDensity] = useState('1.25');
  const [qcViscosity, setQcViscosity] = useState('110 KU');
  const [qcGoodCans, setQcGoodCans] = useState('120');
  const [qcRejectedCans, setQcRejectedCans] = useState('0');
  const [qcReworkCans, setQcReworkCans] = useState('0');
  const [qcColorMatch, setQcColorMatch] = useState(true);
  const [qcForceReject, setQcForceReject] = useState(false);
  const [qcNotes, setQcNotes] = useState('');

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<ProductionOrder>('production_orders', setOrders),
      ErpDataService.subscribeToCollection<QcInspection>('qc_inspections', setQcInspections),
      ErpDataService.subscribeToCollection<InventoryMovement>('inventory_movements', setMovements),
      ErpDataService.subscribeToCollection<WarehouseStockBalance>('warehouse_stock_balances', setBalances),
      ErpDataService.subscribeToCollection<PackagingRule>('packaging_rules', setPackagingRules)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  useEffect(() => {
    if (formulations.length > 0 && formulaId === 0) {
      setFormulaId(Number(formulations[0].id));
    }
    if (products.length > 0 && productId === 0) {
      setProductId(Number(products[0].id));
      setProductName(products[0].name);
    }
    if (factoryItems.length > 0 && issueMatId === 0) {
      setIssueMatId(Number(factoryItems[0].id));
    }
    if (machines.length > 0 && !machineName) {
      setMachineName(machines[0].name);
    }
  }, [formulations, products, factoryItems, machines]);

  const activeRules = packagingRules.length > 0 ? packagingRules : DEFAULT_PACKAGING_RULES;
  const cansPerCarton = getCansPerCarton(weightGrams, activeRules);
  const cartonsNum = Math.max(1, Math.floor(Number(targetCartons) || 1));
  const totalCans = cartonsNum * cansPerCarton;
  const expectedKg = (totalCans * weightGrams) / 1000;
  const rawWarehouse = useMemo(
    () => warehouses.find((w) => resolveWarehouseClass(w) === 'RAW_MATERIALS'),
    [warehouses]
  );
  const fgWarehouse = useMemo(
    () => warehouses.find((w) => resolveWarehouseClass(w) === 'FINISHED_GOODS'),
    [warehouses]
  );

  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE) || canPerformAction(GRANULAR_ACTIONS.INVENTORY_OPS);
  const createOrderGuard = useTransactionGuard('phase3_create_prod_order', 'CREATE');
  const issueGuard = useTransactionGuard('phase3_issue_raw', 'GOODS_ISSUE');
  const qcGuard = useTransactionGuard('phase3_submit_qc', 'APPROVE_INVOICE');

  const handleCreateOrder = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || createOrderGuard.isSubmitting) return;
    if (!rawWarehouse || !fgWarehouse) {
      onNotice?.('err', 'يجب توفر مخزن مواد خام (RAW_MATERIALS) ومخزن إنتاج تام (FINISHED_GOODS) قبل إنشاء أمر الإنتاج');
      return;
    }
    const formula = formulations.find((f) => Number(f.id) === Number(formulaId)) || null;
    const prod = products.find((p) => Number(p.id) === Number(productId)) || null;
    const actor = userProfile?.fullName || userProfile?.username || 'مهندس الإنتاج';

    const res = await createOrderGuard.runGuarded({
      guardKey: `prod_order_${productName.trim()}_${weightGrams}_${cartonsNum}`,
      operationType: 'CREATE',
      userId: userProfile?.username || actor,
      documentReference: `PO-${productName.trim()}-${cartonsNum}`,
      targetCollection: 'production_orders',
      payloadSummary: `أمر إنتاج ${productName.trim()} (${cartonsNum} كرتونة)`,
      execute: async () => {
        const ord = createProductionOrderModel({
          formula,
          productId: prod?.id ?? null,
          productName: productName.trim() || prod?.name || formula?.productName || 'منتج دهان',
          paintColor,
          packageWeightGrams: weightGrams,
          targetCartons: cartonsNum,
          rawWarehouseId: rawWarehouse.id,
          fgWarehouseId: fgWarehouse.id,
          machineName,
          productionEngineer: engineer || actor,
          approvedBy: actor,
          customRules: activeRules
        });

        await ErpDataService.saveRecord('production_orders', ord.syncUuid, ord);
        await ErpDataService.logAudit(actor, 'CREATE_PRODUCTION_ORDER', `إنشاء أمر إنتاج ${ord.orderNo}: ${ord.productName} (${ord.targetCartons} كرتونة)`);
        return { resultReference: ord.syncUuid, result: ord };
      }
    });

    if (res.ok) {
      const ord = res.data as ProductionOrder | undefined;
      onNotice?.('ok', res.replayed ? res.message : `تم اعتماد أمر الإنتاج ${ord?.orderNo || ''} (${cartonsNum} كرتونة × ${cansPerCarton} = ${totalCans} علبة)`);
    } else {
      onNotice?.('err', res.message);
    }
  };

  const handleIssueMaterials = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!orderToIssue || !canCreate || issueGuard.isSubmitting) return;
    const wh = warehouses.find((w) => Number(w.id) === Number(orderToIssue.rawWarehouseId)) || rawWarehouse;
    const mat = factoryItems.find((i) => Number(i.id) === Number(issueMatId));
    if (!wh || !mat) {
      onNotice?.('err', 'تحقق من مخزن المواد الخام والمادة المطلوب صرفها');
      return;
    }
    const actor = userProfile?.fullName || userProfile?.username || 'أمين مخزن الخام';
    const res = await issueGuard.runGuarded({
      guardKey: `prod_issue_${orderToIssue.syncUuid}_${mat.id}_${issueQty}`,
      operationType: 'GOODS_ISSUE',
      userId: userProfile?.username || actor,
      documentReference: `${orderToIssue.syncUuid}-ISSUE-${mat.id}`,
      targetCollection: 'production_orders',
      payloadSummary: `صرف ${issueQty} ${mat.unit} من ${mat.name} لأمر ${orderToIssue.orderNo}`,
      execute: async () => {
        const { order: updatedOrder, state } = issueRawMaterialsForOrderInState(
          { movements, balances },
          orderToIssue,
          wh,
          [
            {
              itemId: Number(mat.id),
              itemName: mat.name,
              qty: Number(issueQty) || 0,
              returnedQty: Number(returnQty) || 0,
              wastedQty: Number(wasteQty) || 0,
              unit: mat.unit || 'كجم',
              batchNumber: orderToIssue.batchNumber
            }
          ],
          actor
        );
        await persistMovementLedgerDiff(movements, state.movements, balances, state.balances);
        await ErpDataService.saveRecord('production_orders', updatedOrder.syncUuid, updatedOrder);

        const unitCostEgp = Number(mat.unitCostEgp || 0);
        const netUsedQty = Math.max(0, (Number(issueQty) || 0) - (Number(returnQty) || 0) - (Number(wasteQty) || 0));
        const issueJournal = buildProductionIssueJournalPayload({
          orderSyncUuid: updatedOrder.syncUuid,
          orderNo: updatedOrder.orderNo,
          orderDate: updatedOrder.orderDate,
          productName: updatedOrder.productName,
          netIssuedCost: netUsedQty * unitCostEgp,
          wasteCost: (Number(wasteQty) || 0) * unitCostEgp,
          actor,
          currency: 'EGP'
        });
        await ErpDataService.persistJournalPayload(issueJournal).catch(() => {});
        await ErpDataService.logAudit(actor, 'ISSUE_PRODUCTION_MATERIALS', `صرف ${issueQty} ${mat.unit} من ${mat.name} لأمر ${updatedOrder.orderNo}`);
        return { resultReference: updatedOrder.syncUuid, result: updatedOrder };
      }
    });

    if (res.ok) {
      onNotice?.('ok', res.replayed ? res.message : `تم صرف المواد الخام لأمر ${orderToIssue.orderNo} وتحديث رصيد مخزن المواد الخام`);
      setOrderToIssue(null);
    } else {
      onNotice?.('err', res.message);
    }
  };

  const handleSubmitQc = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!orderToInspect || !canCreate || qcGuard.isSubmitting) return;
    const wh = warehouses.find((w) => Number(w.id) === Number(orderToInspect.fgWarehouseId)) || fgWarehouse;
    if (!wh) {
      onNotice?.('err', 'لم يتم العثور على مخزن الإنتاج التام FINISHED_GOODS');
      return;
    }
    const matchingProductId =
      orderToInspect.productId ||
      products.find((p) => p.name.trim() === orderToInspect.productName.trim())?.id ||
      products[0]?.id ||
      Date.now();
    const formula = formulations.find((f) => Number(f.id) === Number(orderToInspect.formulaId));
    const actor = userProfile?.fullName || userProfile?.username || 'مسؤول الجودة';

    const res = await qcGuard.runGuarded({
      guardKey: `prod_qc_${orderToInspect.syncUuid}`,
      operationType: 'APPROVE_INVOICE',
      userId: userProfile?.username || actor,
      documentReference: `QC-${orderToInspect.syncUuid}`,
      targetCollection: 'qc_inspections',
      payloadSummary: `فحص جودة QC لأمر ${orderToInspect.orderNo}`,
      execute: async () => {
        const { qc, order: updatedOrder, state } = inspectQcAndPostToFinishedGoodsInState(
          { movements, balances },
          {
            order: orderToInspect,
            fgWarehouse: wh,
            productId: Number(matchingProductId),
            measuredDensity: Number(qcDensity) || 1.25,
            expectedDensity: formula?.calculatedDensity || undefined,
            measuredViscosity: qcViscosity,
            colorMatchPassed: qcColorMatch,
            goodCans: Number(qcGoodCans) || 0,
            rejectedCans: Number(qcRejectedCans) || 0,
            reworkCans: Number(qcReworkCans) || 0,
            forceReject: qcForceReject,
            inspectorName: actor,
            notes: qcNotes
          }
        );

        await ErpDataService.saveRecord('qc_inspections', qc.syncUuid, qc);
        await ErpDataService.saveRecord('production_orders', updatedOrder.syncUuid, updatedOrder);
        await persistMovementLedgerDiff(movements, state.movements, balances, state.balances);

        if (qc.postedToStock) {
          const matchedProd = products.find((p) => Number(p.id) === Number(matchingProductId));
          const unitCost = Number(matchedProd?.purchasePrice || matchedProd?.costPrice || formula?.costPerKg || 0);
          const fgJournal = buildQcFinishedGoodsJournalPayload({
            qcSyncUuid: qc.syncUuid,
            qcNo: qc.qcNo,
            orderNo: updatedOrder.orderNo,
            productName: updatedOrder.productName,
            producedFullCartons: updatedOrder.producedFullCartons,
            fgTotalCost: updatedOrder.producedFullCartons * unitCost,
            inspectorName: actor,
            currency: 'EGP'
          });
          await ErpDataService.persistJournalPayload(fgJournal).catch(() => {});
        }

        await ErpDataService.logAudit(
          actor,
          'QC_INSPECTION_GATE',
          `فحص الجودة ${qc.qcNo} لأمر ${updatedOrder.orderNo}: ${qc.status} (${qc.postedToStock ? 'مرحّل للإنتاج التام' : 'محجور'})`
        );
        return { resultReference: qc.syncUuid, result: { qc, updatedOrder } };
      }
    });

    if (res.ok) {
      const payload = res.data as { qc: QcInspection; updatedOrder: ProductionOrder } | undefined;
      if (payload?.qc) {
        onNotice?.(
          payload.qc.postedToStock ? 'ok' : 'err',
          payload.qc.postedToStock
            ? `اجتاز الفحص (${payload.qc.qcNo}): تم ترحيل ${payload.updatedOrder.producedFullCartons} كرتونة إلى مخزن الإنتاج التام`
            : `فشل الفحص أو حُجر (${payload.qc.qcNo}): المنتجات محجورة ولا يُسمح ببيعها أو تحويلها للمبيعات`
        );
      } else {
        onNotice?.('ok', res.message);
      }
      setOrderToInspect(null);
    } else {
      onNotice?.('err', res.message);
    }
  };

  return (
    <GlassCard className="p-5 border border-blue-500/30 space-y-5">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
        <div className="flex items-center gap-2.5">
          <FlaskConical className="w-5 h-5 text-blue-400" />
          <div>
            <h3 className="text-sm font-black text-theme-primary">
              دورة أوامر الإنتاج BOM، صرف الخامات، وبوابة مراقبة الجودة QC
            </h3>
            <p className="text-[11px] text-theme-muted">
              معدلات التعبئة المعتمدة: 250 جم = 48 علبة/كرتونة • 900 جم = 12 علبة/كرتونة • 4000 جم = 4 علب/كرتونة
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <GlassBadge variant="cyan">{orders.length} أمر إنتاج</GlassBadge>
          <GlassBadge variant="green">{qcInspections.length} فحص QC</GlassBadge>
        </div>
      </div>

      {(!rawWarehouse || !fgWarehouse) && (
        <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs flex items-center gap-2">
          <ShieldAlert className="w-4 h-4 shrink-0" />
          <span>
            تأكد من وجود مخزن مصنف <strong>مواد خام (RAW_MATERIALS)</strong> ومخزن مصنف <strong>إنتاج تام (FINISHED_GOODS)</strong> في دليل المستودعات لتفعيل الصرف والترحيل.
          </span>
        </div>
      )}

      {/* Order creation form */}
      <form onSubmit={handleCreateOrder} className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 text-xs">
        <div>
          <label className="block font-bold text-theme-secondary mb-1">التركيبة المختبرية BOM</label>
          <select
            value={formulaId}
            onChange={(e) => {
              const id = Number(e.target.value);
              setFormulaId(id);
              const f = formulations.find((x) => Number(x.id) === id);
              if (f) {
                setProductName(f.productName);
                setPaintColor(f.baseKind || 'أبيض');
              }
            }}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          >
            <option value={0}>-- إنتاج قياسي بدون ربط تركيبة --</option>
            {formulations.map((f) => (
              <option key={f.id} value={f.id}>
                {f.formulaNo} — {f.productName}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="block font-bold text-theme-secondary mb-1">المنتج التام *</label>
          <select
            value={productId}
            onChange={(e) => {
              const id = Number(e.target.value);
              setProductId(id);
              const p = products.find((x) => Number(x.id) === id);
              if (p) setProductName(p.name);
            }}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          >
            <option value={0}>صنف مخصص</option>
            {products.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} ({p.packageWeight})
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="block font-bold text-theme-secondary mb-1">اسم الصنف النهائي *</label>
          <input
            type="text"
            required
            value={productName}
            onChange={(e) => setProductName(e.target.value)}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          />
        </div>

        <div>
          <label className="block font-bold text-theme-secondary mb-1">حجم العبوة ومعيار الكرتونة</label>
          <div className="flex gap-1.5">
            {[
              { w: 250, label: '250 جم (48)' },
              { w: 900, label: '900 جم (12)' },
              { w: 4000, label: '4000 جم (4)' }
            ].map((opt) => (
              <button
                key={opt.w}
                type="button"
                onClick={() => setWeightGrams(opt.w)}
                className={`flex-1 py-2 rounded-xl text-[11px] font-bold border ${
                  weightGrams === opt.w
                    ? 'bg-blue-600 text-white border-blue-600'
                    : 'bg-theme-base text-theme-secondary border-theme'
                }`}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="block font-bold text-theme-secondary mb-1">عدد الكراتين المستهدف *</label>
          <input
            type="number"
            min="1"
            required
            value={targetCartons}
            onChange={(e) => setTargetCartons(e.target.value)}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary font-mono font-bold"
          />
        </div>

        <div>
          <label className="block font-bold text-theme-secondary mb-1">خط الإنتاج / الماكينة</label>
          <input
            type="text"
            value={machineName}
            onChange={(e) => setMachineName(e.target.value)}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          />
        </div>

        <div>
          <label className="block font-bold text-theme-secondary mb-1">المهندس المشرف</label>
          <input
            type="text"
            value={engineer}
            onChange={(e) => setEngineer(e.target.value)}
            className="w-full px-3 py-2 rounded-xl bg-theme-base border border-theme text-theme-primary"
          />
        </div>

        <div className="flex items-end">
          <GlassButton
            type="submit"
            variant="primary"
            disabled={!canCreate}
            isLoading={createOrderGuard.isSubmitting}
            loadingText={OPERATION_LOADING_LABELS.CREATE}
            className="w-full justify-center"
          >
            + اعتماد أمر إنتاج BOM ({totalCans} علبة / {expectedKg.toFixed(1)} كجم)
          </GlassButton>
        </div>
      </form>

      {/* Orders List */}
      {orders.length > 0 && (
        <div className="space-y-2.5 pt-2 border-t border-theme">
          <h4 className="text-xs font-bold text-theme-secondary">أوامر الإنتاج النشطة والمكتملة:</h4>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
            {orders
              .slice()
              .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
              .slice(0, 8)
              .map((ord) => (
                <div
                  key={ord.syncUuid}
                  className="p-3.5 rounded-xl bg-theme-base border border-theme space-y-2 text-xs"
                >
                  <div className="flex justify-between items-center gap-2">
                    <span className="font-black text-theme-primary">
                      {ord.orderNo} • {ord.productName}
                    </span>
                    <GlassBadge
                      variant={
                        ord.status === 'POSTED_TO_FG'
                          ? 'green'
                          : ord.status === 'QC_REJECTED'
                          ? 'red'
                          : ord.status === 'MATERIALS_ISSUED'
                          ? 'gold'
                          : 'cyan'
                      }
                    >
                      {ord.status}
                    </GlassBadge>
                  </div>
                  <p className="text-[11px] text-theme-muted font-mono">
                    المطلوب: {ord.targetCartons} كرتونة ({ord.targetCans} علبة × {ord.packageWeightGrams} جم) | المعتمد للإنتاج التام: {ord.producedFullCartons} كرتونة (+{ord.producedLooseCans} علبة)
                  </p>
                  {ord.status !== 'POSTED_TO_FG' && (
                    <div className="flex gap-2 pt-1">
                      <GlassButton
                        type="button"
                        size="sm"
                        variant="secondary"
                        disabled={!canCreate}
                        onClick={() => {
                          setOrderToIssue(ord);
                          setIssueQty(String(Math.max(1, Number(ord.targetWeightKg || 1))));
                          setReturnQty('0');
                          setWasteQty('0');
                        }}
                      >
                        صرف خامات من المخزن الخام
                      </GlassButton>
                      <GlassButton
                        type="button"
                        size="sm"
                        variant="primary"
                        disabled={!canCreate}
                        onClick={() => {
                          setOrderToInspect(ord);
                          setQcGoodCans(String(ord.targetCans));
                          setQcRejectedCans('0');
                          setQcReworkCans('0');
                          setQcForceReject(false);
                          setQcColorMatch(true);
                          setQcNotes('');
                        }}
                      >
                        فحص الجودة QC
                      </GlassButton>
                    </div>
                  )}
                </div>
              ))}
          </div>
        </div>
      )}

      {/* Latest QC records */}
      {qcInspections.length > 0 && (
        <div className="pt-2 border-t border-theme space-y-1.5 text-xs">
          <p className="font-bold text-theme-secondary flex items-center gap-1.5">
            <ClipboardCheck className="w-4 h-4 text-emerald-400" />
            أحدث قرارات بوابة الجودة QC:
          </p>
          {qcInspections
            .slice()
            .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
            .slice(0, 4)
            .map((qc) => (
              <div
                key={qc.syncUuid}
                className="flex justify-between items-center px-3 py-2 rounded-lg bg-theme-base border border-theme text-[11px]"
              >
                <span className="font-bold text-theme-primary">
                  {qc.qcNo} ({qc.sourceDocNo}) — مقبول: {qc.acceptedQty} | مرفوض: {qc.rejectedQty}
                </span>
                <span className={qc.postedToStock ? 'text-emerald-400 font-bold' : 'text-red-400 font-bold'}>
                  {qc.postedToStock ? 'مرحّل لمخزن الإنتاج التام' : 'محجور - ممنوع البيع والتحويل'}
                </span>
              </div>
            ))}
        </div>
      )}

      {/* Issue Materials Modal */}
      <Modal
        isOpen={Boolean(orderToIssue)}
        onClose={() => setOrderToIssue(null)}
        title={`صرف مواد خام لأمر ${orderToIssue?.orderNo || ''}`}
      >
        <form onSubmit={handleIssueMaterials} className="space-y-3 text-xs">
          <div>
            <label className="block font-bold text-theme-secondary mb-1">المادة الخام (مخزن المواد الخام) *</label>
            <select
              value={issueMatId}
              onChange={(e) => setIssueMatId(Number(e.target.value))}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              {factoryItems.map((it) => (
                <option key={it.id} value={it.id}>
                  {it.name} ({it.unit})
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-3 gap-2">
            <div>
              <label className="block font-bold text-theme-secondary mb-1">المصروفة *</label>
              <input
                type="number"
                step="any"
                required
                value={issueQty}
                onChange={(e) => setIssueQty(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block font-bold text-theme-secondary mb-1">مرتجع فائض</label>
              <input
                type="number"
                step="any"
                value={returnQty}
                onChange={(e) => setReturnQty(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block font-bold text-theme-secondary mb-1">فاقد</label>
              <input
                type="number"
                step="any"
                value={wasteQty}
                onChange={(e) => setWasteQty(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <GlassButton type="button" variant="secondary" onClick={() => setOrderToIssue(null)} disabled={issueGuard.isSubmitting}>
              إلغاء
            </GlassButton>
            <GlassButton
              type="submit"
              variant="primary"
              isLoading={issueGuard.isSubmitting}
              loadingText={OPERATION_LOADING_LABELS.GOODS_ISSUE}
            >
              تأكيد الصرف من المخزن الخام
            </GlassButton>
          </div>
        </form>
      </Modal>

      {/* QC Inspection Modal */}
      <Modal
        isOpen={Boolean(orderToInspect)}
        onClose={() => setOrderToInspect(null)}
        title={`بوابة فحص الجودة QC — ${orderToInspect?.orderNo || ''}`}
      >
        <form onSubmit={handleSubmitQc} className="space-y-3 text-xs">
          <div className="grid grid-cols-3 gap-2">
            <div>
              <label className="block font-bold text-theme-secondary mb-1">علب مطابقة *</label>
              <input
                type="number"
                value={qcGoodCans}
                onChange={(e) => setQcGoodCans(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-emerald-400 font-mono"
              />
            </div>
            <div>
              <label className="block font-bold text-theme-secondary mb-1">مرفوضة</label>
              <input
                type="number"
                value={qcRejectedCans}
                onChange={(e) => setQcRejectedCans(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-red-400 font-mono"
              />
            </div>
            <div>
              <label className="block font-bold text-theme-secondary mb-1">إعادة تشغيل</label>
              <input
                type="number"
                value={qcReworkCans}
                onChange={(e) => setQcReworkCans(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-amber-400 font-mono"
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="block font-bold text-theme-secondary mb-1">الكثافة المقاسة</label>
              <input
                type="number"
                step="any"
                value={qcDensity}
                onChange={(e) => setQcDensity(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block font-bold text-theme-secondary mb-1">اللزوجة KU</label>
              <input
                type="text"
                value={qcViscosity}
                onChange={(e) => setQcViscosity(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
              />
            </div>
          </div>
          <label className="flex items-center gap-2 cursor-pointer">
            <input type="checkbox" checked={qcColorMatch} onChange={(e) => setQcColorMatch(e.target.checked)} />
            <span>اجتاز مطابقة اللون والدرجة المعملية</span>
          </label>
          <label className="flex items-center gap-2 cursor-pointer text-red-400">
            <input type="checkbox" checked={qcForceReject} onChange={(e) => setQcForceReject(e.target.checked)} />
            <span>رفض وحجر كامل الكمية (منع الإضافة إلى مخزن الإنتاج التام)</span>
          </label>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">ملاحظات فحص الجودة</label>
            <input
              type="text"
              value={qcNotes}
              onChange={(e) => setQcNotes(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <GlassButton type="button" variant="secondary" onClick={() => setOrderToInspect(null)} disabled={qcGuard.isSubmitting}>
              إلغاء
            </GlassButton>
            <GlassButton
              type="submit"
              variant="primary"
              isLoading={qcGuard.isSubmitting}
              loadingText={OPERATION_LOADING_LABELS.APPROVE_INVOICE}
            >
              اعتماد قرار الجودة QC
            </GlassButton>
          </div>
        </form>
      </Modal>
    </GlassCard>
  );
};

// =============================================================================
// 3. THREE-WAREHOUSE BALANCES, TRANSFERS & APPEND-ONLY LEDGER (WarehousesModuleView)
// =============================================================================
export const WebThreeWarehouseTransferPanel: React.FC<{
  warehouses: Warehouse[];
  products: Product[];
  onNotice?: (type: 'ok' | 'err', text: string) => void;
}> = ({ warehouses, products, onNotice }) => {
  const { userProfile, canPerformAction } = useAuth();
  const [balances, setBalances] = useState<WarehouseStockBalance[]>([]);
  const [movements, setMovements] = useState<InventoryMovement[]>([]);
  const [transfers, setTransfers] = useState<WarehouseTransfer[]>([]);
  const [transferItems, setTransferItems] = useState<WarehouseTransferItem[]>([]);

  const [classFilter, setClassFilter] = useState<'ALL' | WarehouseClass>('ALL');
  const [showTransferForm, setShowTransferForm] = useState(false);
  const [srcWarehouseId, setSrcWarehouseId] = useState<number>(0);
  const [dstWarehouseId, setDstWarehouseId] = useState<number>(0);
  const [trfProductId, setTrfProductId] = useState<number>(0);
  const [trfQty, setTrfQty] = useState('10');
  const [trfNotes, setTrfNotes] = useState('');

  const [mvToReverse, setMvToReverse] = useState<InventoryMovement | null>(null);
  const [reverseReason, setReverseReason] = useState('');

  useEffect(() => {
    const unsubs = [
      ErpDataService.subscribeToCollection<WarehouseStockBalance>('warehouse_stock_balances', setBalances),
      ErpDataService.subscribeToCollection<InventoryMovement>('inventory_movements', setMovements),
      ErpDataService.subscribeToCollection<WarehouseTransfer>('warehouse_transfers', setTransfers),
      ErpDataService.subscribeToCollection<WarehouseTransferItem>('warehouse_transfer_items', setTransferItems)
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  useEffect(() => {
    const fg = warehouses.find((w) => resolveWarehouseClass(w) === 'FINISHED_GOODS') || warehouses[0];
    const sales = warehouses.find((w) => resolveWarehouseClass(w) === 'SALES') || warehouses[1] || warehouses[0];
    if (fg && srcWarehouseId === 0) setSrcWarehouseId(Number(fg.id));
    if (sales && dstWarehouseId === 0) setDstWarehouseId(Number(sales.id));
    if (products.length > 0 && trfProductId === 0) setTrfProductId(Number(products[0].id));
  }, [warehouses, products]);

  const canOperate = canPerformAction(GRANULAR_ACTIONS.CREATE) || canPerformAction(GRANULAR_ACTIONS.INVENTORY_OPS);
  const transferCreateGuard = useTransactionGuard('phase3_create_transfer', 'STOCK_TRANSFER');
  const transferAdvanceGuard = useTransactionGuard('phase3_advance_transfer', 'STOCK_TRANSFER');
  const reverseGuard = useTransactionGuard('phase3_reverse_movement', 'UPDATE');

  const filteredBalances = useMemo(
    () => (classFilter === 'ALL' ? balances : balances.filter((b) => b.warehouseClass === classFilter)),
    [balances, classFilter]
  );

  const handleCreateTransfer = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canOperate || transferCreateGuard.isSubmitting) return;
    const src = warehouses.find((w) => Number(w.id) === Number(srcWarehouseId));
    const dst = warehouses.find((w) => Number(w.id) === Number(dstWarehouseId));
    const prod = products.find((p) => Number(p.id) === Number(trfProductId));
    const qty = Number(trfQty) || 0;
    if (!src || !dst || !prod || qty <= 0) {
      onNotice?.('err', 'حدد مخزن المصدر والوجهة والصنف وكمية موجبة صحيحة');
      return;
    }
    const srcCls = resolveWarehouseClass(src);
    const dstCls = resolveWarehouseClass(dst);
    const routeErr = validateTransferRoute(srcCls, dstCls, [
      {
        itemCategory: 'FINISHED_GOOD',
        requestedQty: qty,
        qcApprovedRequired: srcCls === 'FINISHED_GOODS',
        qcApproved: true
      }
    ]);
    if (routeErr) {
      onNotice?.('err', routeErr);
      return;
    }

    const actor = userProfile?.username || 'WEB_USER';
    const res = await transferCreateGuard.runGuarded({
      guardKey: `trf_create_${src.id}_${dst.id}_${prod.id}_${qty}`,
      operationType: 'STOCK_TRANSFER',
      userId: actor,
      documentReference: `TRF-${src.id}-${dst.id}-${prod.id}`,
      targetCollection: 'warehouse_transfers',
      payloadSummary: `تحويل ${qty} ${prod.name} من ${src.name} إلى ${dst.name}`,
      execute: async ({ idempotencyKey }) => {
        const now = Date.now();
        const trfNo = `TRF-${String(now).slice(-6)}`;
        const trfUuid = `TRF-${idempotencyKey}`;
        const transfer: WarehouseTransfer = {
          id: now,
          syncUuid: trfUuid,
          transferNo: trfNo,
          transferDate: new Date(now).toISOString().split('T')[0],
          sourceWarehouseId: src.id,
          sourceWarehouseName: src.name,
          sourceWarehouseClass: srcCls,
          destinationWarehouseId: dst.id,
          destinationWarehouseName: dst.name,
          destinationWarehouseClass: dstCls,
          status: 'PENDING_APPROVAL',
          totalCost: 0,
          currency: dst.currency || 'SDG',
          notes: trfNotes.trim(),
          createdBy: actor,
          recordVersion: 1,
          createdAt: now,
          updatedAt: now
        };
        const item: WarehouseTransferItem = {
          id: now + 1,
          transferId: now,
          transferUuid: trfUuid,
          itemCategory: 'FINISHED_GOOD',
          itemId: Number(prod.id),
          itemCode: prod.sku,
          itemName: prod.name,
          unit: prod.unit || 'كرتونة',
          requestedQty: qty,
          approvedQty: qty,
          shippedQty: 0,
          receivedQty: 0,
          rejectedQty: 0,
          unitCost: Number(prod.costPrice || 0),
          totalCost: qty * Number(prod.costPrice || 0),
          batchNumber: '',
          qcApprovedRequired: srcCls === 'FINISHED_GOODS',
          qcApproved: true
        };
        await ErpDataService.saveRecord('warehouse_transfers', trfUuid, transfer);
        await ErpDataService.saveRecord('warehouse_transfer_items', `${trfUuid}-1`, item);
        return { resultReference: trfUuid, result: trfNo };
      }
    });

    if (res.ok) {
      onNotice?.('ok', res.replayed ? res.message : `تم إنشاء مستند التحويل ${res.data || ''} من (${src.name}) إلى (${dst.name})`);
      setShowTransferForm(false);
      setTrfNotes('');
    } else {
      onNotice?.('err', res.message);
    }
  };

  const handleAdvanceTransfer = async (trf: WarehouseTransfer, action: 'APPROVE' | 'DISPATCH' | 'RECEIVE') => {
    if (!canOperate || transferAdvanceGuard.isSubmitting) return;
    const items = transferItems.filter((it) => it.transferUuid === trf.syncUuid || it.transferId === trf.id);
    const actor = userProfile?.fullName || userProfile?.username || 'WEB_USER';

    const res = await transferAdvanceGuard.runGuarded({
      guardKey: `trf_advance_${trf.syncUuid}_${action}`,
      operationType: 'STOCK_TRANSFER',
      userId: userProfile?.username || actor,
      documentReference: `${trf.syncUuid}-${action}`,
      targetCollection: 'warehouse_transfers',
      payloadSummary: `تحويل مخزني ${trf.transferNo} (${action})`,
      execute: async () => {
        const now = Date.now();
        if (action === 'APPROVE') {
          const sod = canApproveTransfer(trf, userProfile, true);
          if (!sod.ok) throw new Error(sod.error || 'غير مسموح بالاعتماد');
          await ErpDataService.saveRecord('warehouse_transfers', trf.syncUuid, {
            ...trf,
            status: 'APPROVED',
            approvedBy: actor,
            recordVersion: trf.recordVersion + 1,
            updatedAt: now
          });
          return { resultReference: trf.syncUuid, result: `تم اعتماد مستند التحويل ${trf.transferNo}` };
        }

        if (action === 'DISPATCH') {
          const srcWh = warehouses.find((w) => Number(w.id) === Number(trf.sourceWarehouseId));
          if (!srcWh) throw new Error('لم يتم العثور على مخزن المصدر');
          let ledgerState = { movements, balances };
          for (const it of items) {
            ledgerState = postMovementInState(ledgerState, {
              warehouse: srcWh,
              movementType: 'WAREHOUSE_TRANSFER_OUT',
              itemCategory: it.itemCategory,
              itemId: it.itemId,
              itemName: it.itemName,
              quantity: it.approvedQty || it.requestedQty,
              unit: it.unit,
              referenceDocType: 'WAREHOUSE_TRANSFER',
              referenceDocId: trf.id,
              referenceDocNo: trf.transferNo,
              reason: `شحن تحويل مخزني ${trf.transferNo} إلى ${trf.destinationWarehouseName}`,
              qcApproved: it.qcApproved,
              performedBy: actor,
              idempotencyKey: `TRF-OUT-${trf.syncUuid}-${it.itemId}`
            }).state;
          }
          await persistMovementLedgerDiff(movements, ledgerState.movements, balances, ledgerState.balances);
          await ErpDataService.saveRecord('warehouse_transfers', trf.syncUuid, {
            ...trf,
            status: 'IN_TRANSIT',
            dispatchedBy: actor,
            dispatchedAt: now,
            recordVersion: trf.recordVersion + 1,
            updatedAt: now
          });
          return { resultReference: trf.syncUuid, result: `تم شحن التحويل ${trf.transferNo} وهو في الطريق الآن` };
        }

        const dstWh = warehouses.find((w) => Number(w.id) === Number(trf.destinationWarehouseId));
        if (!dstWh) throw new Error('لم يتم العثور على مخزن الوجهة');
        let ledgerState = { movements, balances };
        for (const it of items) {
          ledgerState = postMovementInState(ledgerState, {
            warehouse: dstWh,
            movementType: 'WAREHOUSE_TRANSFER_IN',
            itemCategory: it.itemCategory,
            itemId: it.itemId,
            itemName: it.itemName,
            quantity: it.approvedQty || it.requestedQty,
            unit: it.unit,
            referenceDocType: 'WAREHOUSE_TRANSFER',
            referenceDocId: trf.id,
            referenceDocNo: trf.transferNo,
            reason: `استلام تحويل مخزني ${trf.transferNo} من ${trf.sourceWarehouseName}`,
            qcApproved: true,
            performedBy: actor,
            idempotencyKey: `TRF-IN-${trf.syncUuid}-${it.itemId}`
          }).state;
        }
        await persistMovementLedgerDiff(movements, ledgerState.movements, balances, ledgerState.balances);
        await ErpDataService.saveRecord('warehouse_transfers', trf.syncUuid, {
          ...trf,
          status: 'RECEIVED',
          receivedBy: actor,
          receivedAt: now,
          recordVersion: trf.recordVersion + 1,
          updatedAt: now
        });
        return { resultReference: trf.syncUuid, result: `تم استلام التحويل ${trf.transferNo} في ${dstWh.name} بنجاح` };
      }
    });

    if (res.ok) {
      onNotice?.('ok', res.replayed ? res.message : String(res.data || res.message));
    } else {
      onNotice?.('err', res.message);
    }
  };

  const handleReverseMovement = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!mvToReverse || reverseGuard.isSubmitting) return;
    const actor = userProfile?.fullName || userProfile?.username || 'المدير';
    const res = await reverseGuard.runGuarded({
      guardKey: `rev_mv_${mvToReverse.syncUuid}`,
      operationType: 'UPDATE',
      userId: userProfile?.username || actor,
      documentReference: mvToReverse.syncUuid,
      targetCollection: 'inventory_movements',
      payloadSummary: `قيد عكسي للحركة ${mvToReverse.documentNo}`,
      execute: async () => {
        const { reversalMovement, state } = reverseMovementInState(
          { movements, balances },
          mvToReverse.id,
          reverseReason,
          actor
        );
        await persistMovementLedgerDiff(movements, state.movements, balances, state.balances);
        await ErpDataService.logAudit(actor, 'REVERSE_INVENTORY_MOVEMENT', `قيد عكسي ${reversalMovement.documentNo} للحركة ${mvToReverse.documentNo}`);
        return { resultReference: reversalMovement.syncUuid, result: reversalMovement.documentNo };
      }
    });
    if (res.ok) {
      onNotice?.('ok', res.replayed ? res.message : `تم تنفيذ القيد العكسي ${res.data || ''} بنجاح مع حفظ سجل التدقيق`);
      setMvToReverse(null);
      setReverseReason('');
    } else {
      onNotice?.('err', res.message);
    }
  };

  return (
    <GlassCard className="p-5 border border-cyan-500/30 space-y-4">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
        <div className="flex items-center gap-2.5">
          <WarehouseIcon className="w-5 h-5 text-cyan-400" />
          <div>
            <h3 className="text-sm font-black text-theme-primary">
              دفتر المخازن الثلاثة المستقلة، التحويلات الرسمية، والقيود العكسية (Append-Only)
            </h3>
            <p className="text-[11px] text-theme-muted">
              تصنيف المخازن (RAW_MATERIALS / FINISHED_GOODS / SALES) مع فصل تام للأرصدة الفعلية والمحجورة وفي الطريق.
            </p>
          </div>
        </div>
        {canOperate && (
          <GlassButton size="sm" variant="primary" onClick={() => setShowTransferForm((v) => !v)}>
            <ArrowLeftRight className="w-3.5 h-3.5 ml-1 inline" />
            {showTransferForm ? 'إخفاء نموذج التحويل' : '+ تحويل بين المخازن'}
          </GlassButton>
        )}
      </div>

      {/* Three-Warehouse Classification Filter Pills */}
      <div className="flex flex-wrap gap-2 text-xs">
        {(
          [
            { id: 'ALL', label: `كل المخازن (${balances.length})` },
            { id: 'RAW_MATERIALS', label: 'مخزن المواد الخام (RAW)' },
            { id: 'FINISHED_GOODS', label: 'مخزن الإنتاج التام (FG)' },
            { id: 'SALES', label: 'مخزن المبيعات (SALES)' }
          ] as const
        ).map((tab) => (
          <button
            key={tab.id}
            type="button"
            onClick={() => setClassFilter(tab.id)}
            className={`px-3 py-1.5 rounded-xl font-bold border transition-all ${
              classFilter === tab.id
                ? 'bg-cyan-500/20 border-cyan-500 text-cyan-300'
                : 'bg-theme-base border-theme text-theme-secondary'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {showTransferForm && (
        <form
          onSubmit={handleCreateTransfer}
          className="p-4 rounded-xl bg-theme-base border border-theme grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3 text-xs"
        >
          <div>
            <label className="block font-bold text-theme-secondary mb-1">من مخزن (المصدر)</label>
            <select
              value={srcWarehouseId}
              onChange={(e) => setSrcWarehouseId(Number(e.target.value))}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name} ({classLabel(resolveWarehouseClass(w))})
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">إلى مخزن (الوجهة)</label>
            <select
              value={dstWarehouseId}
              onChange={(e) => setDstWarehouseId(Number(e.target.value))}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name} ({classLabel(resolveWarehouseClass(w))})
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">الصنف التام</label>
            <select
              value={trfProductId}
              onChange={(e) => setTrfProductId(Number(e.target.value))}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              {products.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">الكمية (كرتونة)</label>
            <input
              type="number"
              step="any"
              required
              value={trfQty}
              onChange={(e) => setTrfQty(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
            />
          </div>
          <div className="flex items-end">
            <GlassButton
              type="submit"
              variant="primary"
              className="w-full justify-center"
              isLoading={transferCreateGuard.isSubmitting}
              loadingText={OPERATION_LOADING_LABELS.STOCK_TRANSFER}
            >
              إنشاء مستند تحويل
            </GlassButton>
          </div>
        </form>
      )}

      {/* Structured Balances Grid */}
      {filteredBalances.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2.5 text-xs">
          {filteredBalances.slice(0, 9).map((b) => (
            <div key={b.syncUuid} className="p-3 rounded-xl bg-theme-base border border-theme space-y-1">
              <div className="flex justify-between items-center gap-2">
                <span className="font-bold text-theme-primary truncate">{b.itemName}</span>
                <GlassBadge variant={classBadgeVariant(b.warehouseClass)}>{classLabel(b.warehouseClass)}</GlassBadge>
              </div>
              <p className="text-[10px] text-theme-muted font-mono">
                فعلي: {b.onHandQty} | محجوز: {b.reservedQty} | طريق: {b.inTransitQty} | حجر QC: {b.quarantineQty}
              </p>
              <p className="text-[11px] font-black font-mono text-emerald-400">
                المتاح الصافي: {b.availableQty} {b.unit}
              </p>
            </div>
          ))}
        </div>
      )}

      {/* Active Transfers Lifecycle */}
      {transfers.length > 0 && (
        <div className="space-y-2 pt-2 border-t border-theme text-xs">
          <p className="font-bold text-theme-secondary flex items-center gap-1.5">
            <Truck className="w-4 h-4 text-cyan-400" />
            أذون التحويل الرسمية بين المخازن ({transfers.length}):
          </p>
          <div className="space-y-2">
            {transfers
              .slice()
              .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
              .slice(0, 5)
              .map((trf) => (
                <div
                  key={trf.syncUuid}
                  className="p-3 rounded-xl bg-theme-base border border-theme flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2"
                >
                  <div>
                    <span className="font-bold text-theme-primary block">
                      {trf.transferNo}: {trf.sourceWarehouseName} ← {trf.destinationWarehouseName}
                    </span>
                    <span className="text-[10px] text-theme-muted">منشئ المستند: {trf.createdBy} • {trf.transferDate}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <GlassBadge variant={trf.status === 'RECEIVED' ? 'green' : 'cyan'}>{trf.status}</GlassBadge>
                    {(trf.status === 'DRAFT' || trf.status === 'PENDING_APPROVAL') && canOperate && (
                      <GlassButton
                        size="sm"
                        variant="secondary"
                        onClick={() => handleAdvanceTransfer(trf, 'APPROVE')}
                        isLoading={transferAdvanceGuard.isSubmitting}
                        loadingText={OPERATION_LOADING_LABELS.STOCK_TRANSFER}
                      >
                        <CheckCircle2 className="w-3.5 h-3.5 ml-1 inline" /> اعتماد
                      </GlassButton>
                    )}
                    {trf.status === 'APPROVED' && canOperate && (
                      <GlassButton
                        size="sm"
                        variant="secondary"
                        onClick={() => handleAdvanceTransfer(trf, 'DISPATCH')}
                        isLoading={transferAdvanceGuard.isSubmitting}
                        loadingText={OPERATION_LOADING_LABELS.STOCK_TRANSFER}
                      >
                        شحن
                      </GlassButton>
                    )}
                    {trf.status === 'IN_TRANSIT' && canOperate && (
                      <GlassButton
                        size="sm"
                        variant="primary"
                        onClick={() => handleAdvanceTransfer(trf, 'RECEIVE')}
                        isLoading={transferAdvanceGuard.isSubmitting}
                        loadingText={OPERATION_LOADING_LABELS.STOCK_TRANSFER}
                      >
                        استلام نهائي
                      </GlassButton>
                    )}
                  </div>
                </div>
              ))}
          </div>
        </div>
      )}

      {/* Append-Only Movements with Reverse Action */}
      {movements.length > 0 && (
        <div className="space-y-2 pt-2 border-t border-theme text-xs">
          <p className="font-bold text-theme-secondary">أحدث قيود دفتر المخزون غير القابلة للحذف ({movements.length}):</p>
          <div className="space-y-1.5">
            {movements
              .slice()
              .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
              .slice(0, 5)
              .map((mv) => (
                <div
                  key={mv.syncUuid}
                  className="px-3 py-2 rounded-lg bg-theme-base border border-theme flex justify-between items-center gap-2"
                >
                  <div className="min-w-0">
                    <span className="font-bold text-theme-primary block truncate">
                      {mv.documentNo} • {mv.movementType} • {mv.itemName}
                    </span>
                    <span className="text-[10px] text-theme-muted font-mono">
                      {mv.signedQuantity > 0 ? `+${mv.signedQuantity}` : mv.signedQuantity} {mv.unit} | {classLabel(mv.warehouseClass)} | {mv.status}
                    </span>
                  </div>
                  {mv.status === 'POSTED' && canOperate && (
                    <button
                      type="button"
                      onClick={() => {
                        setMvToReverse(mv);
                        setReverseReason('');
                      }}
                      className="px-2.5 py-1 rounded-lg bg-red-500/15 text-red-400 hover:bg-red-500/25 font-bold shrink-0 flex items-center gap-1"
                    >
                      <RotateCcw className="w-3 h-3" />
                      قيد عكسي
                    </button>
                  )}
                </div>
              ))}
          </div>
        </div>
      )}

      <Modal
        isOpen={Boolean(mvToReverse)}
        onClose={() => setMvToReverse(null)}
        title={`إنشاء قيد عكسي — ${mvToReverse?.documentNo || ''}`}
      >
        <form onSubmit={handleReverseMovement} className="space-y-3 text-xs">
          <p className="text-theme-muted">
            دفتر المخزون محمي بنظام Append-Only ولا يسمح بحذف القيود. سيتم قيد حركة عكسية وإبقاء الأثر التدقيقي كاملاً.
          </p>
          <div>
            <label className="block font-bold text-theme-secondary mb-1">سبب القيد العكسي *</label>
            <input
              type="text"
              required
              value={reverseReason}
              onChange={(e) => setReverseReason(e.target.value)}
              placeholder="مثال: تصحيح كمية مدخلة بالخطأ"
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <GlassButton type="button" variant="secondary" onClick={() => setMvToReverse(null)} disabled={reverseGuard.isSubmitting}>
              إلغاء
            </GlassButton>
            <GlassButton
              type="submit"
              variant="primary"
              isLoading={reverseGuard.isSubmitting}
              loadingText={OPERATION_LOADING_LABELS.UPDATE}
            >
              تنفيذ القيد العكسي
            </GlassButton>
          </div>
        </form>
      </Modal>
    </GlassCard>
  );
};
