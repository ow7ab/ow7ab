import React, { useEffect, useMemo, useState } from 'react';
import {
  Package,
  Warehouse as WarehouseIcon,
  ArrowUpDown,
  Plus,
  Search,
  Printer,
  FileSpreadsheet,
  Trash2,
  AlertTriangle,
  Edit3,
  Filter,
  RotateCcw,
  Sparkles
} from 'lucide-react';
import { GlassCard } from '../ui/GlassCard';
import { GlassBadge } from '../ui/GlassBadge';
import { GlassButton } from '../ui/GlassButton';
import { Modal } from '../ui/Modal';
import { Product, StockMove, Warehouse } from '../../types/erp';
import {
  ErpDataService,
  isStockInMoveKind,
  resolveWarehouseFilterIds,
  compareRecordsChronologically
} from '../../services/ErpDataService';
import { ReportExporter } from '../../services/ReportExporter';
import { GRANULAR_ACTIONS, isSystemManager } from '../../services/SecurityService';
import { useLanguage } from '../../context/LanguageContext';
import { useAuth } from '../../context/AuthContext';

type InventorySubTab = 'BALANCES' | 'WAREHOUSES_DIR' | 'STOCK_MOVES' | 'ADD_PRODUCT';

export const WarehousesModuleView: React.FC = () => {
  const { language } = useLanguage();
  const { canPerformAction, userProfile } = useAuth();

  const [subTab, setSubTab] = useState<InventorySubTab>('BALANCES');
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [stockMoves, setStockMoves] = useState<StockMove[]>([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedWarehouseFilter, setSelectedWarehouseFilter] = useState<number>(0); // 0 = كل المخازن
  const [selectedWeightFilter, setSelectedWeightFilter] = useState<string>('ALL');
  const [notice, setNotice] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // Stock Move Form
  const [moveProductId, setMoveProductId] = useState<number>(0);
  const [moveWarehouseId, setMoveWarehouseId] = useState<number>(0);
  const [moveKind, setMoveKind] = useState<'IN' | 'OUT' | 'SET'>('IN');
  const [moveQty, setMoveQty] = useState('');
  const [moveNote, setMoveNote] = useState('');
  const [isQuickAdjustOpen, setIsQuickAdjustOpen] = useState(false);

  // New Warehouse Form
  const [whName, setWhName] = useState('');
  const [whBranch, setWhBranch] = useState('الخرطوم');
  const [whLocation, setWhLocation] = useState('');

  // New Product Form
  const [prodForm, setProdForm] = useState({
    sku: '',
    name: '',
    category: 'معجون حراري',
    paintColor: 'أبيض',
    packageWeight: '900 جم',
    unit: 'كرتونة',
    unitsPerCarton: '12',
    costPrice: '0',
    salePrice: '0',
    salePriceEgp: '0',
    minLevel: '20',
    initialStock: '0'
  });

  useEffect(() => {
    const unsubWh = ErpDataService.subscribeToCollection<Warehouse>('warehouses', setWarehouses);
    const unsubPr = ErpDataService.subscribeToCollection<Product>('products', setProducts);
    const unsubSm = ErpDataService.subscribeToCollection<StockMove>('stock_moves', setStockMoves);
    return () => {
      unsubWh();
      unsubPr();
      unsubSm();
    };
  }, []);

  useEffect(() => {
    if (products.length > 0 && moveProductId === 0) {
      setMoveProductId(Number(products[0].id));
    }
    if (warehouses.length > 0 && moveWarehouseId === 0) {
      setMoveWarehouseId(Number(warehouses[0].id));
    }
  }, [products, warehouses]);

  const activeWhFilterId = selectedWarehouseFilter > 0 ? selectedWarehouseFilter : null;

  const computedProducts = useMemo(
    () => ErpDataService.computeProductStocksList(products, stockMoves, activeWhFilterId, warehouses),
    [products, stockMoves, activeWhFilterId, warehouses]
  );

  const warehouseSummaries = useMemo(
    () => ErpDataService.computeWarehouseStockSummary(warehouses, products, stockMoves),
    [warehouses, products, stockMoves]
  );

  const colorWeightRows = useMemo(
    () => ErpDataService.computeColorWeightStockRows(products, stockMoves, selectedWeightFilter, activeWhFilterId, warehouses),
    [products, stockMoves, selectedWeightFilter, activeWhFilterId, warehouses]
  );

  // Per-product inQty and outQty reconciled 1:1 with merged product signatures and warehouse filter
  const productMoveBreakdown = useMemo(
    () => ErpDataService.computeProductMoveBreakdown(products, stockMoves, activeWhFilterId, warehouses),
    [products, stockMoves, activeWhFilterId, warehouses]
  );

  const productByIdMap = useMemo(() => {
    const map = new Map<number, Product>();
    for (const p of products) map.set(Number(p.id), p);
    return map;
  }, [products]);

  const warehouseByIdMap = useMemo(() => {
    const map = new Map<number, Warehouse>();
    for (const w of warehouses) {
      const aliases: number[] = Array.isArray((w as any)._aliasIds) ? (w as any)._aliasIds : [Number(w.id)];
      aliases.forEach((id) => map.set(Number(id), w));
      map.set(Number(w.id), w);
    }
    return map;
  }, [warehouses]);

  const orphanStockMoves = useMemo(() => {
    return stockMoves.filter((m) => {
      const pid = Number(m.productId);
      const wid = Number(m.warehouseId);
      const missingProduct = products.length > 0 && !productByIdMap.has(pid);
      const missingWarehouse = warehouses.length > 0 && !warehouseByIdMap.has(wid);
      return missingProduct || missingWarehouse;
    });
  }, [stockMoves, products.length, warehouses.length, productByIdMap, warehouseByIdMap]);

  const duplicateWarehouseDocsCount = useMemo(
    () =>
      warehouses.reduce((sum, w) => {
        const docIds: string[] = Array.isArray((w as any)._aliasDocIds) ? (w as any)._aliasDocIds : [];
        return sum + Math.max(0, docIds.length - 1);
      }, 0),
    [warehouses]
  );

  const availableWeights = useMemo(() => {
    const set = new Set<string>(['250 جم', '900 جم', '4000 جم']);
    products.forEach((p) => {
      if (p.packageWeight && String(p.packageWeight).trim()) {
        set.add(String(p.packageWeight).trim());
      }
    });
    return Array.from(set);
  }, [products]);

  const canCreate = canPerformAction(GRANULAR_ACTIONS.CREATE);
  const canEdit = canPerformAction(GRANULAR_ACTIONS.EDIT);
  const canDelete = isSystemManager(userProfile) || canPerformAction(GRANULAR_ACTIONS.DELETE);

  const showNotice = (type: 'ok' | 'err', text: string) => {
    setNotice({ type, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const handleRecordStockMove = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate && !canEdit) {
      showNotice('err', 'لا تملك صلاحية تسجيل حركات المخزون');
      return;
    }
    const rawQty = Number(moveQty);
    const prod = computedProducts.find((p) => Number(p.id) === Number(moveProductId)) || products.find((p) => Number(p.id) === Number(moveProductId));
    const wh = warehouses.find((w) => Number(w.id) === Number(moveWarehouseId)) || warehouses[0];
    if (!prod || !wh || isNaN(rawQty) || rawQty < 0 || (moveKind !== 'SET' && rawQty <= 0)) {
      showNotice('err', 'يرجى تحديد الصنف والمستودع وإدخال كمية صحيحة');
      return;
    }

    let finalKind: 'IN' | 'OUT' = moveKind === 'OUT' ? 'OUT' : 'IN';
    let finalDeltaQty = rawQty;

    if (moveKind === 'SET') {
      // Matches Android PmpViewModel.kt updateProductInventoryCount: computes exact delta against current stock in that warehouse (including merged alias IDs)
      const whAliasSet = resolveWarehouseFilterIds(Number(wh.id), warehouses) || new Set([Number(wh.id)]);
      const whMoves = stockMoves.filter(
        (m) => Number(m.productId) === Number(prod.id) && whAliasSet.has(Number(m.warehouseId))
      );
      let curIn = 0;
      let curOut = 0;
      for (const m of whMoves) {
        const q = Math.abs(Number(m.quantity || 0));
        if (isStockInMoveKind(m.kind)) curIn += q;
        else curOut += q;
      }
      const currentWhStock = Math.max(0, curIn - curOut);
      const diff = rawQty - currentWhStock;
      if (Math.abs(diff) < 0.001) {
        showNotice('ok', 'الرصيد الفعلي مطابق للرصيد الحالي بالفعل');
        setIsQuickAdjustOpen(false);
        return;
      }
      finalKind = diff > 0 ? 'IN' : 'OUT';
      finalDeltaQty = Math.abs(diff);
    }

    const now = Date.now();
    const actorName = userProfile?.fullName || userProfile?.username || 'أمين المخزن';
    const move: StockMove = {
      id: now,
      productId: Number(prod.id),
      productName: prod.name,
      warehouseId: Number(wh.id),
      warehouseName: wh.name,
      kind: finalKind,
      quantity: finalDeltaQty,
      note:
        moveNote.trim() ||
        (moveKind === 'SET'
          ? `تعديل جرد مباشر للرصيد الفعلي (${rawQty}) بواسطة ${actorName}`
          : finalKind === 'IN'
          ? 'توريد مخزني'
          : 'صرف مخزني'),
      referenceNo: `SM-${now.toString().slice(-6)}`,
      createdBy: actorName,
      actor: actorName,
      createdAt: now,
      timestamp: now
    };

    await ErpDataService.upsertDocument('stock_moves', `SM-${now}`, move);
    await ErpDataService.logAudit(
      userProfile?.username || 'user',
      moveKind === 'SET' ? 'INVENTORY_SET_STOCK' : `STOCK_${finalKind}`,
      `${moveKind === 'SET' ? `تعديل رصيد فعلي إلى (${rawQty})` : finalKind === 'IN' ? 'وارد مخزني' : 'منصرف مخزني'} (${finalDeltaQty} ${prod.unit}) — ${prod.name} @ ${wh.name}`
    );

    setMoveQty('');
    setMoveNote('');
    setIsQuickAdjustOpen(false);
    showNotice('ok', 'تم تسجيل حركة المخزون وتحديث الرصيد الفوري بنجاح');
  };

  const openQuickAdjustModal = (prod: Product, defaultMode: 'IN' | 'OUT' | 'SET' = 'SET') => {
    setMoveProductId(Number(prod.id));
    setMoveWarehouseId(selectedWarehouseFilter > 0 ? selectedWarehouseFilter : Number(warehouses[0]?.id || 0));
    setMoveKind(defaultMode);
    setMoveQty(defaultMode === 'SET' ? String(prod.currentStock ?? 0) : '');
    setMoveNote('');
    setIsQuickAdjustOpen(true);
  };

  const handleAddWarehouse = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || !whName.trim()) return;
    const now = Date.now();
    const wh: Warehouse = {
      id: now,
      code: `WH-${now.toString().slice(-4)}`,
      name: whName.trim(),
      branch: whBranch,
      location: whLocation.trim() || whBranch,
      manager: userProfile?.fullName || 'أمين المستودع',
      active: true
    };
    await ErpDataService.upsertDocument('warehouses', `WH-${now}`, wh);
    setWhName('');
    setWhLocation('');
    showNotice('ok', `تم إنشاء المستودع "${wh.name}"`);
  };

  const handleAddProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canCreate || !prodForm.name.trim()) return;
    const now = Date.now();
    const actorName = userProfile?.fullName || userProfile?.username || 'النظام';
    const initQty = Math.max(0, Number(prodForm.initialStock) || 0);
    const minVal = Math.max(0, Number(prodForm.minLevel) || 10);
    const costVal = Math.max(0, Number(prodForm.costPrice) || 0);
    const newProd: Product = {
      id: now,
      sku: prodForm.sku.trim() || `SKU-${now.toString().slice(-5)}`,
      name: prodForm.name.trim(),
      category: prodForm.category,
      paintColor: prodForm.paintColor,
      packageWeight: prodForm.packageWeight,
      unit: prodForm.unit,
      unitsPerCarton: Math.max(1, Number(prodForm.unitsPerCarton) || 12),
      purchasePrice: costVal,
      costPrice: costVal,
      salePrice: Math.max(0, Number(prodForm.salePrice) || 0),
      salePriceSdg: Math.max(0, Number(prodForm.salePrice) || 0),
      salePriceEgp: Math.max(0, Number(prodForm.salePriceEgp) || 0),
      minimumStock: minVal,
      minLevel: minVal,
      currentStock: initQty,
      active: true
    };

    await ErpDataService.upsertDocument('products', `PRD-${now}`, newProd);

    if (initQty > 0 && warehouses.length > 0) {
      const targetWh =
        warehouses.find((w) => Number(w.id) === Number(selectedWarehouseFilter)) || warehouses[0];
      const sm: StockMove = {
        id: now + 1,
        productId: now,
        productName: newProd.name,
        warehouseId: Number(targetWh.id),
        warehouseName: targetWh.name,
        kind: 'IN',
        quantity: initQty,
        note: 'رصيد افتتاحي عند تعريف الصنف',
        referenceNo: `INIT-${now.toString().slice(-5)}`,
        createdBy: actorName,
        actor: actorName,
        createdAt: now,
        timestamp: now
      };
      await ErpDataService.upsertDocument('stock_moves', `SM-${now + 1}`, sm);
    }

    setProdForm({
      ...prodForm,
      sku: '',
      name: '',
      initialStock: '0'
    });
    setSubTab('BALANCES');
    showNotice('ok', `تمت إضافة الصنف "${newProd.name}" إلى الكتالوج والمخزون`);
  };

  const handleDeleteProduct = async (prod: Product) => {
    if (!canDelete) return;
    if (!window.confirm(`تأكيد حذف الصنف "${prod.name}" نهائياً مع كافة حركاته المخزنية؟`)) return;
    const actor = userProfile?.username || 'admin';
    const docId = (prod as any)._docId || `PRD-${prod.id}`;
    await ErpDataService.deleteDocumentWithTombstone('products', docId, prod.id, actor);
    // Also clean any stock moves linked to this deleted product so no phantom balances remain
    const linkedMoves = stockMoves.filter((m) => Number(m.productId) === Number(prod.id));
    await Promise.all(
      linkedMoves.map((m) =>
        ErpDataService.deleteDocumentWithTombstone('stock_moves', (m as any)._docId || `SM-${m.id}`, m.id, actor)
      )
    );
    await ErpDataService.logAudit(actor, 'DELETE_PRODUCT', `حذف الصنف "${prod.name}" (#${prod.id}) وتنظيف ${linkedMoves.length} حركة مرتبطة`);
    showNotice('ok', `تم حذف الصنف "${prod.name}" وتنظيف حركاته بنجاح`);
  };

  const handleDeleteWarehouse = async (wh: Warehouse) => {
    if (!canDelete) {
      showNotice('err', 'لا تملك صلاحية حذف المستودعات');
      return;
    }
    if (!window.confirm(`هل تريد حذف المستودع "${wh.name}" نهائياً من النظام السحابي والموبايل وتنظيف حركاته؟`)) {
      return;
    }
    const actor = userProfile?.username || 'admin';
    const aliasIds: number[] = Array.isArray((wh as any)._aliasIds) && (wh as any)._aliasIds.length > 0
      ? (wh as any)._aliasIds
      : [Number(wh.id)];
    const aliasDocIds: string[] = Array.isArray((wh as any)._aliasDocIds) && (wh as any)._aliasDocIds.length > 0
      ? (wh as any)._aliasDocIds
      : [(wh as any)._docId || `WH-${wh.id}`];

    // Delete all duplicate Firestore documents for this warehouse + write tombstones using both name & IDs
    await Promise.all([
      ...aliasDocIds.map((dId) => ErpDataService.deleteDocumentWithTombstone('warehouses', dId, wh.name, actor)),
      ...aliasIds.map((wid) => ErpDataService.deleteDocumentWithTombstone('warehouses', `WH-${wid}`, wid, actor))
    ]);

    const aliasSet = new Set(aliasIds.map(Number));
    const linkedMoves = stockMoves.filter((m) => aliasSet.has(Number(m.warehouseId)));
    await Promise.all(
      linkedMoves.map((m) =>
        ErpDataService.deleteDocumentWithTombstone('stock_moves', (m as any)._docId || `SM-${m.id}`, m.id, actor)
      )
    );

    if (aliasSet.has(Number(selectedWarehouseFilter))) {
      setSelectedWarehouseFilter(0);
    }
    await ErpDataService.logAudit(actor, 'DELETE_WAREHOUSE', `حذف المستودع "${wh.name}" مع ${linkedMoves.length} حركة مخزنية`);
    showNotice('ok', `تم حذف المستودع "${wh.name}" ومزامنة الحذف مع تطبيق الموبايل`);
  };

  const handleResetWarehouseStock = async (wh: Warehouse) => {
    if (!canDelete) {
      showNotice('err', 'لا تملك صلاحية تصفير أرصدة المستودعات');
      return;
    }
    if (!window.confirm(`تصفير كامل أرصدة وحركات مستودع "${wh.name}"؟`)) return;
    const actor = userProfile?.username || 'admin';
    const aliasIds = resolveWarehouseFilterIds(Number(wh.id), warehouses) || new Set([Number(wh.id)]);
    const linkedMoves = stockMoves.filter((m) => aliasIds.has(Number(m.warehouseId)));
    await Promise.all(
      linkedMoves.map((m) =>
        ErpDataService.deleteDocumentWithTombstone('stock_moves', (m as any)._docId || `SM-${m.id}`, m.id, actor)
      )
    );
    await ErpDataService.logAudit(actor, 'ZERO_WAREHOUSE', `تصفير أرصدة وحركات المستودع "${wh.name}" (${linkedMoves.length} حركة)`);
    showNotice('ok', `تم تصفير رصيد مستودع "${wh.name}" وحذف ${linkedMoves.length} حركة`);
  };

  const handleDeduplicateWarehouses = async () => {
    if (!canDelete) return;
    const actor = userProfile?.username || 'admin';
    let removedDocs = 0;
    for (const w of warehouses) {
      const canonicalDocId = (w as any)._docId || `WH-${w.id}`;
      const docIds: string[] = Array.isArray((w as any)._aliasDocIds) ? (w as any)._aliasDocIds : [];
      const extras = docIds.filter((d) => d && d !== canonicalDocId);
      for (const extraDocId of extras) {
        await ErpDataService.deleteDocument('warehouses', extraDocId);
        removedDocs++;
      }
    }
    await ErpDataService.logAudit(actor, 'DEDUPLICATE_WAREHOUSES', `تنظيف وتوحيد المستودعات المكررة (حذف ${removedDocs} مستند مكرر)`);
    showNotice('ok', removedDocs > 0 ? `تم توحيد المستودعات وحذف ${removedDocs} مستند مكرر بنجاح` : 'المستودعات موحدة ولا توجد تكرارات حالياً');
  };

  const handlePurgeOrphanStockMoves = async () => {
    if (!canDelete && !canEdit) return;
    if (orphanStockMoves.length === 0) return;
    if (!window.confirm(`تأكيد حذف ${orphanStockMoves.length} حركة مخزنية يتيمة (لأصناف أو مستودعات محذوفة) من السحابة نهائياً؟`)) {
      return;
    }
    const actor = userProfile?.username || 'admin';
    await Promise.all(
      orphanStockMoves.map((m) =>
        ErpDataService.deleteDocumentWithTombstone('stock_moves', (m as any)._docId || `SM-${m.id}`, m.id, actor)
      )
    );
    await ErpDataService.logAudit(actor, 'PURGE_ORPHAN_STOCK_MOVES', `تنظيف وحذف ${orphanStockMoves.length} حركة مخزنية يتيمة`);
    showNotice('ok', `تم تنظيف وحذف ${orphanStockMoves.length} حركة مخزنية يتيمة بنجاح`);
  };

  const handleDeleteStockMove = async (m: StockMove) => {
    if (!canDelete) return;
    if (!window.confirm(`حذف حركة المخزون رقم ${m.referenceNo || m.id} (${m.quantity} كرتونة)؟`)) return;
    const actor = userProfile?.username || 'admin';
    await ErpDataService.deleteDocumentWithTombstone('stock_moves', (m as any)._docId || `SM-${m.id}`, m.id, actor);
    await ErpDataService.logAudit(actor, 'DELETE_STOCK_MOVE', `حذف حركة مخزنية #${m.id} (${m.quantity})`);
    showNotice('ok', 'تم حذف الحركة المخزنية بنجاح');
  };

  const filteredProducts = computedProducts.filter(
    (p) =>
      (selectedWeightFilter === 'ALL' || p.packageWeight === selectedWeightFilter) &&
      (!searchTerm ||
        p.name?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        p.paintColor?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        p.sku?.toLowerCase().includes(searchTerm.toLowerCase()))
  );

  const selectedWhObj = warehouses.find((w) => {
    const aliases: number[] = Array.isArray((w as any)._aliasIds) ? (w as any)._aliasIds : [Number(w.id)];
    return Number(w.id) === Number(selectedWarehouseFilter) || aliases.includes(Number(selectedWarehouseFilter));
  });
  const selectedWhTitle = selectedWhObj ? selectedWhObj.name : 'كل المخازن والفروع';

  const totalCartons = computedProducts.reduce((s, p) => s + Math.max(0, Number(p.currentStock || 0)), 0);
  const totalInMoves = Array.from(productMoveBreakdown.values()).reduce((s, v) => s + v.inQty, 0);
  const totalOutMoves = Array.from(productMoveBreakdown.values()).reduce((s, v) => s + v.outQty, 0);
  const lowStockItems = computedProducts.filter((p) => Number(p.currentStock || 0) <= Number(p.minLevel || 10));

  const handleExportInventoryPdf = () => {
    ReportExporter.exportToPrint(
      `تقرير أرصدة المخازن — ${selectedWhTitle}`,
      `إجمالي الكراتين المتاحة: ${totalCartons.toLocaleString()} كرتونة | الوارد: ${totalInMoves.toLocaleString()} | المنصرف: ${totalOutMoves.toLocaleString()}`,
      ['الكود', 'اسم الصنف', 'اللون', 'الوزن', 'إجمالي الوارد (+)', 'إجمالي المنصرف (-)', 'الرصيد الفعلي المتاح', 'حد الطلب', 'سعر الكرتونة (SDG)'],
      filteredProducts.map((p) => {
        const mv = productMoveBreakdown.get(Number(p.id)) || { inQty: 0, outQty: 0 };
        return [
          p.sku || `PRD-${p.id}`,
          p.name,
          p.paintColor || '-',
          p.packageWeight || '-',
          mv.inQty.toLocaleString(),
          mv.outQty.toLocaleString(),
          `${Number(p.currentStock || 0).toLocaleString()} ${p.unit || 'كرتونة'}`,
          p.minLevel || 10,
          Number(p.salePrice || p.salePriceSdg || 0).toLocaleString()
        ];
      })
    );
  };

  const handleExportInventoryCsv = () => {
    ReportExporter.exportToCsv(
      `جرد_المخازن_${selectedWhTitle.replace(/\s+/g, '_')}`,
      ['الكود', 'اسم الصنف', 'اللون', 'الوزن', 'إجمالي الوارد', 'إجمالي المنصرف', 'الرصيد الفعلي المتاح', 'الوحدة', 'حد الطلب', 'سعر الكرتونة SDG'],
      filteredProducts.map((p) => {
        const mv = productMoveBreakdown.get(Number(p.id)) || { inQty: 0, outQty: 0 };
        return [
          p.sku || `PRD-${p.id}`,
          p.name,
          p.paintColor || '-',
          p.packageWeight || '-',
          mv.inQty,
          mv.outQty,
          Number(p.currentStock || 0),
          p.unit || 'كرتونة',
          p.minLevel || 10,
          Number(p.salePrice || p.salePriceSdg || 0)
        ];
      })
    );
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <GlassCard className="p-5 flex flex-col lg:flex-row justify-between items-start lg:items-center gap-4">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 rounded-2xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center text-cyan-400">
            <Package className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-lg font-black text-theme-primary">
              {language === 'ar'
                ? 'إدارة المخازن، الأرصدة، وحركة الأصناف'
                : 'Warehouses, Stock Balances & Movements'}
            </h2>
            <p className="text-xs text-theme-secondary">
              إجمالي المخزون المتاح ({selectedWhTitle}):{' '}
              <span className="font-mono font-bold text-cyan-400">{totalCartons.toLocaleString()}</span> كرتونة | تنبيهات النواقص:{' '}
              <span className="font-mono font-bold text-amber-400">{lowStockItems.length}</span>
            </p>
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          {[
            { id: 'BALANCES' as const, label: 'أرصدة الأصناف والجرد', icon: <Package className="w-4 h-4" /> },
            { id: 'WAREHOUSES_DIR' as const, label: 'دليل المستودعات', icon: <WarehouseIcon className="w-4 h-4" /> },
            { id: 'STOCK_MOVES' as const, label: 'حركات الوارد والمنصرف', icon: <ArrowUpDown className="w-4 h-4" /> },
            { id: 'ADD_PRODUCT' as const, label: 'إضافة صنف جديد', icon: <Plus className="w-4 h-4" /> }
          ].map((t) => (
            <button
              key={t.id}
              onClick={() => setSubTab(t.id)}
              className={`px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 transition-all ${
                subTab === t.id
                  ? 'bg-red-600 text-white shadow-lg shadow-red-600/25'
                  : 'bg-theme-surface text-theme-secondary hover:text-theme-primary border border-theme'
              }`}
            >
              {t.icon}
              <span>{t.label}</span>
            </button>
          ))}
        </div>
      </GlassCard>

      {notice && (
        <div
          className={`p-3.5 rounded-xl border text-xs font-bold ${
            notice.type === 'ok'
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300'
              : 'bg-red-500/10 border-red-500/30 text-red-300'
          }`}
        >
          {notice.text}
        </div>
      )}

      {/* SUB-TAB 1: BALANCES (Matches Android InventoryScreen.kt 1:1) */}
      {subTab === 'BALANCES' && (
        <div className="space-y-5">
          {/* Warehouse Selector Bar (matching Android InventoryScreen.kt lines 210-238) */}
          <GlassCard className="p-4 space-y-3">
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2">
              <div className="flex items-center gap-2 text-xs font-bold text-theme-primary">
                <Filter className="w-4 h-4 text-cyan-400" />
                <span>تصفية الأرصدة حسب المخزن (مطابق لتطبيق الأندرويد):</span>
              </div>
              <div className="flex flex-wrap gap-2">
                {selectedWhObj && canDelete && (
                  <>
                    <GlassButton
                      size="sm"
                      variant="secondary"
                      icon={<RotateCcw className="w-3.5 h-3.5 text-amber-400" />}
                      onClick={() => handleResetWarehouseStock(selectedWhObj)}
                    >
                      تصفير المخزن
                    </GlassButton>
                    <GlassButton
                      size="sm"
                      variant="secondary"
                      icon={<Trash2 className="w-3.5 h-3.5 text-red-400" />}
                      onClick={() => handleDeleteWarehouse(selectedWhObj)}
                    >
                      حذف المخزن
                    </GlassButton>
                  </>
                )}
                <GlassButton
                  size="sm"
                  variant="secondary"
                  icon={<Printer className="w-3.5 h-3.5" />}
                  onClick={handleExportInventoryPdf}
                >
                  طباعة الجرد PDF
                </GlassButton>
                <GlassButton
                  size="sm"
                  variant="secondary"
                  icon={<FileSpreadsheet className="w-3.5 h-3.5" />}
                  onClick={handleExportInventoryCsv}
                >
                  تصدير Excel / CSV
                </GlassButton>
              </div>
            </div>

            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setSelectedWarehouseFilter(0)}
                className={`px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-2 transition-all border ${
                  selectedWarehouseFilter === 0
                    ? 'bg-cyan-500/20 border-cyan-500 text-cyan-300 shadow-sm'
                    : 'bg-theme-base border-theme text-theme-secondary hover:text-theme-primary'
                }`}
              >
                <WarehouseIcon className="w-3.5 h-3.5" />
                <span>كل المخازن ({warehouseSummaries.length})</span>
              </button>

              {warehouseSummaries.map((ws) => {
                const isSelected = Number(selectedWarehouseFilter) === Number(ws.warehouse.id);
                return (
                  <button
                    key={ws.warehouse.id}
                    type="button"
                    onClick={() => setSelectedWarehouseFilter(Number(ws.warehouse.id))}
                    className={`px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-2 transition-all border ${
                      isSelected
                        ? 'bg-cyan-500/20 border-cyan-500 text-cyan-300 shadow-sm'
                        : 'bg-theme-base border-theme text-theme-secondary hover:text-theme-primary'
                    }`}
                  >
                    <span>{ws.warehouse.name}</span>
                    <span className="px-1.5 py-0.5 rounded-md bg-black/30 font-mono text-[11px] text-cyan-400">
                      {ws.netCartons.toLocaleString()} كرتونة
                    </span>
                  </button>
                );
              })}
            </div>
          </GlassCard>

          {orphanStockMoves.length > 0 && (
            <GlassCard className="p-3.5 border border-amber-500/40 bg-amber-500/10 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 text-xs">
              <div className="flex items-center gap-2 text-amber-300">
                <AlertTriangle className="w-4 h-4 shrink-0" />
                <span>
                  تم رصد <strong>{orphanStockMoves.length}</strong> حركة مخزنية يتيمة مرتبطة بأصناف أو مستودعات محذوفة (تم استبعادها تلقائياً لضمان دقة الأرصدة الفعلية).
                </span>
              </div>
              {(canDelete || canEdit) && (
                <GlassButton
                  type="button"
                  size="sm"
                  variant="secondary"
                  icon={<Trash2 className="w-3.5 h-3.5 text-red-400" />}
                  onClick={handlePurgeOrphanStockMoves}
                >
                  تنظيف الحركات اليتيمة من السحابة
                </GlassButton>
              )}
            </GlassCard>
          )}

          {/* Summary KPI Row */}
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <GlassCard className="p-4 border-r-4 border-r-cyan-500">
              <p className="text-xs text-theme-muted">الرصيد الصافي المتاح ({selectedWhTitle})</p>
              <p className="text-xl font-black text-cyan-400 font-mono mt-1">
                {totalCartons.toLocaleString()} كرتونة
              </p>
            </GlassCard>
            <GlassCard className="p-4 border-r-4 border-r-emerald-500">
              <p className="text-xs text-theme-muted">إجمالي حركات الوارد (+)</p>
              <p className="text-xl font-black text-emerald-400 font-mono mt-1">
                +{totalInMoves.toLocaleString()} كرتونة
              </p>
            </GlassCard>
            <GlassCard className="p-4 border-r-4 border-r-amber-500">
              <p className="text-xs text-theme-muted">إجمالي حركات المنصرف والمبيعات (-)</p>
              <p className="text-xl font-black text-amber-400 font-mono mt-1">
                -{totalOutMoves.toLocaleString()} كرتونة
              </p>
            </GlassCard>
            <GlassCard className="p-4 border-r-4 border-r-red-500">
              <p className="text-xs text-theme-muted">أصناف دون حد الطلب</p>
              <p className="text-xl font-black text-red-400 font-mono mt-1">
                {lowStockItems.length} صنف
              </p>
            </GlassCard>
          </div>

          {/* Package Weight Filter & Android 3-Column Table (اللون | الوزن | العدد) */}
          <GlassCard className="p-5 space-y-4">
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
              <div>
                <h3 className="text-sm font-black text-theme-primary">
                  جدول الجرد السريع حسب اللون والوزن (اللون | الوزن | العدد الفعلي)
                </h3>
                <p className="text-xs text-theme-secondary mt-0.5">
                  مطابق لجدول المخزون في تطبيق الأندرويد — اضغط على أي صف لتعديل الرصيد الفعلي أو تسجيل وارد/منصرف.
                </p>
              </div>
              <div className="flex flex-wrap gap-1.5">
                <button
                  type="button"
                  onClick={() => setSelectedWeightFilter('ALL')}
                  className={`px-3 py-1.5 rounded-lg text-xs font-bold border transition-all ${
                    selectedWeightFilter === 'ALL'
                      ? 'bg-red-600 text-white border-red-600'
                      : 'bg-theme-base border-theme text-theme-secondary'
                  }`}
                >
                  كل الأوزان
                </button>
                {availableWeights.map((w) => (
                  <button
                    key={w}
                    type="button"
                    onClick={() => setSelectedWeightFilter(w)}
                    className={`px-3 py-1.5 rounded-lg text-xs font-bold border transition-all ${
                      selectedWeightFilter === w
                        ? 'bg-red-600 text-white border-red-600'
                        : 'bg-theme-base border-theme text-theme-secondary'
                    }`}
                  >
                    {w}
                  </button>
                ))}
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-xs text-right">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2.5 px-3">اللون</th>
                    <th className="py-2.5 px-3">الوزن / العبوة</th>
                    <th className="py-2.5 px-3">إجمالي الوارد (+)</th>
                    <th className="py-2.5 px-3">إجمالي المنصرف (-)</th>
                    <th className="py-2.5 px-3">العدد الفعلي المتاح</th>
                    <th className="py-2.5 px-3">تحديث سريع</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/5">
                  {colorWeightRows.map((row, idx) => (
                    <tr key={`${row.colorName}-${idx}`} className="hover:bg-white/[0.02]">
                      <td className="py-2.5 px-3 font-bold text-theme-primary">{row.colorName}</td>
                      <td className="py-2.5 px-3 text-theme-secondary">{row.weightLabel}</td>
                      <td className="py-2.5 px-3 font-mono text-emerald-400">+{row.inQty.toLocaleString()}</td>
                      <td className="py-2.5 px-3 font-mono text-amber-400">-{row.outQty.toLocaleString()}</td>
                      <td className="py-2.5 px-3 font-mono font-black text-cyan-400 text-sm">
                        {row.netCount.toLocaleString()} كرتونة
                      </td>
                      <td className="py-2.5 px-3">
                        {row.primaryProduct && (canCreate || canEdit) && (
                          <div className="flex items-center gap-1.5">
                            <button
                              type="button"
                              onClick={() => openQuickAdjustModal(row.primaryProduct!, 'SET')}
                              className="px-2.5 py-1 rounded-lg bg-cyan-500/15 hover:bg-cyan-500/25 text-cyan-300 border border-cyan-500/30 text-[11px] font-bold flex items-center gap-1"
                            >
                              <Edit3 className="w-3 h-3" />
                              <span>تعديل الرصيد</span>
                            </button>
                            <button
                              type="button"
                              onClick={() => openQuickAdjustModal(row.primaryProduct!, 'IN')}
                              className="px-2 py-1 rounded-lg bg-emerald-500/15 hover:bg-emerald-500/25 text-emerald-300 border border-emerald-500/30 text-[11px] font-bold"
                            >
                              + وارد
                            </button>
                            <button
                              type="button"
                              onClick={() => openQuickAdjustModal(row.primaryProduct!, 'OUT')}
                              className="px-2 py-1 rounded-lg bg-amber-500/15 hover:bg-amber-500/25 text-amber-300 border border-amber-500/30 text-[11px] font-bold"
                            >
                              - صرف
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                  {colorWeightRows.length === 0 && (
                    <tr>
                      <td colSpan={6} className="py-6 text-center text-theme-muted">
                        لا توجد أصناف مسجلة لهذا الوزن حالياً.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </GlassCard>

          {/* Detailed Products Inventory Table */}
          <GlassCard className="p-5 space-y-4">
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
              <div className="relative flex-1 max-w-md w-full">
                <Search className="w-4 h-4 text-theme-muted absolute right-3 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  placeholder="ابحث باسم الصنف، اللون، أو الكود..."
                  className="w-full pr-9 pl-3 py-2 rounded-xl bg-theme-surface border border-theme text-xs text-theme-primary"
                />
              </div>
              <span className="text-xs text-theme-secondary">
                إجمالي الأصناف المعروضة: <strong>{filteredProducts.length}</strong>
              </span>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-xs text-right">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-3 px-3">الكود</th>
                    <th className="py-3 px-3">الصنف</th>
                    <th className="py-3 px-3">اللون / الوزن</th>
                    <th className="py-3 px-3">الوارد (+)</th>
                    <th className="py-3 px-3">المنصرف (-)</th>
                    <th className="py-3 px-3">الرصيد المتاح</th>
                    <th className="py-3 px-3">حد الطلب</th>
                    <th className="py-3 px-3">سعر الكرتونة (SDG)</th>
                    <th className="py-3 px-3">سعر الكرتونة (EGP)</th>
                    <th className="py-3 px-3">الحالة</th>
                    <th className="py-3 px-3">إجراء</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/5">
                  {filteredProducts.map((p) => {
                    const stock = Number(p.currentStock || 0);
                    const min = Number(p.minLevel || 10);
                    const isLow = stock <= min;
                    const mv = productMoveBreakdown.get(Number(p.id)) || { inQty: 0, outQty: 0 };
                    return (
                      <tr key={p.id} className="hover:bg-white/[0.02]">
                        <td className="py-3 px-3 font-mono text-theme-muted">{p.sku || `PRD-${p.id}`}</td>
                        <td className="py-3 px-3 font-bold text-theme-primary">{p.name}</td>
                        <td className="py-3 px-3 text-theme-secondary">
                          {p.paintColor || '-'} • {p.packageWeight || '-'}
                        </td>
                        <td className="py-3 px-3 font-mono text-emerald-400">+{mv.inQty.toLocaleString()}</td>
                        <td className="py-3 px-3 font-mono text-amber-400">-{mv.outQty.toLocaleString()}</td>
                        <td className="py-3 px-3 font-mono font-black text-cyan-400">
                          {stock.toLocaleString()} {p.unit || 'كرتونة'}
                        </td>
                        <td className="py-3 px-3 font-mono text-theme-muted">{min}</td>
                        <td className="py-3 px-3 font-mono text-emerald-400">
                          {Number(p.salePrice || p.salePriceSdg || 0).toLocaleString()}
                        </td>
                        <td className="py-3 px-3 font-mono text-amber-400">
                          {Number(p.salePriceEgp || 0).toLocaleString()}
                        </td>
                        <td className="py-3 px-3">
                          {isLow ? (
                            <GlassBadge variant="gold">
                              <AlertTriangle className="w-3 h-3 ml-1 inline" />
                              تحت حد الطلب
                            </GlassBadge>
                          ) : (
                            <GlassBadge variant="green">متوفر</GlassBadge>
                          )}
                        </td>
                        <td className="py-3 px-3">
                          <div className="flex items-center gap-1.5">
                            {(canCreate || canEdit) && (
                              <button
                                type="button"
                                onClick={() => openQuickAdjustModal(p, 'SET')}
                                className="p-1.5 rounded-lg bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-400"
                                title="تعديل الرصيد الفعلي"
                              >
                                <Edit3 className="w-4 h-4" />
                              </button>
                            )}
                            {canDelete && (
                              <button
                                type="button"
                                onClick={() => handleDeleteProduct(p)}
                                className="p-1.5 rounded-lg bg-red-500/10 hover:bg-red-500/20 text-red-400"
                                title="حذف الصنف"
                              >
                                <Trash2 className="w-4 h-4" />
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                  {filteredProducts.length === 0 && (
                    <tr>
                      <td colSpan={11} className="py-8 text-center text-theme-muted">
                        لا توجد أصناف مطابقة للبحث
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {/* SUB-TAB 2: WAREHOUSES DIRECTORY */}
      {subTab === 'WAREHOUSES_DIR' && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-2 space-y-4">
            {duplicateWarehouseDocsCount > 0 && canDelete && (
              <GlassCard className="p-3.5 border border-cyan-500/30 bg-cyan-500/10 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 text-xs">
                <div className="flex items-center gap-2 text-cyan-300">
                  <Sparkles className="w-4 h-4 shrink-0" />
                  <span>
                    يوجد <strong>{duplicateWarehouseDocsCount}</strong> سجل مستودع مكرر في قاعدة البيانات تم دمجه تلقائياً في العرض.
                  </span>
                </div>
                <GlassButton size="sm" variant="primary" onClick={handleDeduplicateWarehouses}>
                  تنظيف وحذف التكرارات نهائياً
                </GlassButton>
              </GlassCard>
            )}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {warehouseSummaries.map((ws) => {
                const w = ws.warehouse;
                return (
                  <GlassCard key={w.id} className="p-5 flex flex-col justify-between gap-3">
                    <div className="space-y-2">
                      <div className="flex justify-between items-start">
                        <div>
                          <span className="text-[11px] font-mono text-cyan-400">{w.code}</span>
                          <h3 className="text-base font-black text-theme-primary">{w.name}</h3>
                        </div>
                        <GlassBadge variant="cyan">{w.branch}</GlassBadge>
                      </div>
                      <p className="text-xs text-theme-secondary">الموقع: {w.location || w.branch}</p>
                      <p className="text-xs text-theme-muted">المسؤول: {w.manager || 'أمين المستودع'}</p>

                      <div className="grid grid-cols-3 gap-2 pt-2 border-t border-theme text-center">
                        <div className="p-2 rounded-lg bg-theme-base">
                          <div className="text-[10px] text-theme-muted">الرصيد المتاح</div>
                          <div className="text-xs font-black font-mono text-cyan-400 mt-0.5">
                            {ws.netCartons.toLocaleString()}
                          </div>
                        </div>
                        <div className="p-2 rounded-lg bg-theme-base">
                          <div className="text-[10px] text-theme-muted">الوارد (+)</div>
                          <div className="text-xs font-bold font-mono text-emerald-400 mt-0.5">
                            +{ws.inQty.toLocaleString()}
                          </div>
                        </div>
                        <div className="p-2 rounded-lg bg-theme-base">
                          <div className="text-[10px] text-theme-muted">المنصرف (-)</div>
                          <div className="text-xs font-bold font-mono text-amber-400 mt-0.5">
                            -{ws.outQty.toLocaleString()}
                          </div>
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-2">
                      <GlassButton
                        size="sm"
                        variant="secondary"
                        onClick={() => {
                          setSelectedWarehouseFilter(Number(w.id));
                          setSubTab('BALANCES');
                        }}
                        className="flex-1 justify-center"
                      >
                        عرض الجرد ({ws.distinctProductsCount} صنف)
                      </GlassButton>
                      {canDelete && (
                        <>
                          <button
                            type="button"
                            onClick={() => handleResetWarehouseStock(w)}
                            className="p-2 rounded-xl bg-amber-500/10 hover:bg-amber-500/20 text-amber-400 border border-amber-500/20"
                            title="تصفير أرصدة وحركات هذا المستودع"
                          >
                            <RotateCcw className="w-4 h-4" />
                          </button>
                          <button
                            type="button"
                            onClick={() => handleDeleteWarehouse(w)}
                            className="p-2 rounded-xl bg-red-500/10 hover:bg-red-500/20 text-red-400 border border-red-500/20"
                            title="حذف المستودع نهائياً ومزامنة الحذف مع الموبايل"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </>
                      )}
                    </div>
                  </GlassCard>
                );
              })}
            </div>
          </div>

          <GlassCard className="p-5">
            <h3 className="text-sm font-black text-theme-primary mb-4">إضافة مستودع / مخزن فرعي جديد</h3>
            <form onSubmit={handleAddWarehouse} className="space-y-3 text-xs">
              <div>
                <label className="block text-theme-secondary mb-1">اسم المستودع</label>
                <input
                  type="text"
                  required
                  value={whName}
                  onChange={(e) => setWhName(e.target.value)}
                  placeholder="مثال: مخزن عطبرة الرئيسي"
                  className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
                />
              </div>
              <div>
                <label className="block text-theme-secondary mb-1">الفرع</label>
                <select
                  value={whBranch}
                  onChange={(e) => setWhBranch(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
                >
                  <option value="الخرطوم">الخرطوم</option>
                  <option value="القاهرة">القاهرة</option>
                  <option value="وادي حلفا">وادي حلفا</option>
                  <option value="عطبرة">عطبرة</option>
                  <option value="بورتسودان">بورتسودان</option>
                </select>
              </div>
              <div>
                <label className="block text-theme-secondary mb-1">العنوان / الموقع التفصيلي</label>
                <input
                  type="text"
                  value={whLocation}
                  onChange={(e) => setWhLocation(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
                />
              </div>
              <GlassButton type="submit" variant="primary" className="w-full justify-center">
                حفظ المستودع
              </GlassButton>
            </form>
          </GlassCard>
        </div>
      )}

      {/* SUB-TAB 3: STOCK MOVES */}
      {subTab === 'STOCK_MOVES' && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <GlassCard className="p-5">
            <h3 className="text-sm font-black text-theme-primary mb-4">تسجيل إذن وارد / منصرف / تعديل جرد</h3>
            <form onSubmit={handleRecordStockMove} className="space-y-3 text-xs">
              <div>
                <label className="block text-theme-secondary mb-1">الصنف</label>
                <select
                  value={moveProductId}
                  onChange={(e) => setMoveProductId(Number(e.target.value))}
                  className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
                >
                  {computedProducts.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name} — ({p.packageWeight}) [المتاح: {p.currentStock}]
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-theme-secondary mb-1">المستودع</label>
                <select
                  value={moveWarehouseId}
                  onChange={(e) => setMoveWarehouseId(Number(e.target.value))}
                  className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
                >
                  {warehouses.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name} ({w.branch})
                    </option>
                  ))}
                </select>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-theme-secondary mb-1">نوع الحركة</label>
                  <select
                    value={moveKind}
                    onChange={(e) => setMoveKind(e.target.value as 'IN' | 'OUT' | 'SET')}
                    className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
                  >
                    <option value="IN">وارد للمخزن (+)</option>
                    <option value="OUT">منصرف من المخزن (-)</option>
                    <option value="SET">تثبيت الرصيد الفعلي (=)</option>
                  </select>
                </div>
                <div>
                  <label className="block text-theme-secondary mb-1">
                    {moveKind === 'SET' ? 'الرصيد الفعلي الجديد' : 'الكمية'}
                  </label>
                  <input
                    type="number"
                    min="0"
                    required
                    value={moveQty}
                    onChange={(e) => setMoveQty(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
                  />
                </div>
              </div>
              <div>
                <label className="block text-theme-secondary mb-1">ملاحظات / رقم الإذن</label>
                <input
                  type="text"
                  value={moveNote}
                  onChange={(e) => setMoveNote(e.target.value)}
                  placeholder="مثال: استلام من خط الإنتاج أو تسوية جرد"
                  className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
                />
              </div>
              <GlassButton type="submit" variant="primary" className="w-full justify-center">
                اعتماد حركة المخزون
              </GlassButton>
            </form>
          </GlassCard>

          <GlassCard className="p-5 lg:col-span-2 space-y-4">
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
              <h3 className="text-sm font-black text-theme-primary">
                سجل حركات المخزون الفوري ({stockMoves.length})
              </h3>
              <div className="flex flex-wrap items-center gap-2">
                {orphanStockMoves.length > 0 && (canDelete || canEdit) && (
                  <GlassButton
                    type="button"
                    size="sm"
                    variant="secondary"
                    icon={<Trash2 className="w-3.5 h-3.5 text-red-400" />}
                    onClick={handlePurgeOrphanStockMoves}
                  >
                    حذف الحركات اليتيمة ({orphanStockMoves.length})
                  </GlassButton>
                )}
                <select
                  value={selectedWarehouseFilter}
                  onChange={(e) => setSelectedWarehouseFilter(Number(e.target.value))}
                  className="px-2.5 py-1.5 rounded-lg bg-theme-surface border border-theme text-xs"
                >
                  <option value={0}>كل المستودعات</option>
                  {warehouses.map((w) => (
                    <option key={`sm_wh_${w.id}`} value={w.id}>
                      {w.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-xs text-right">
                <thead>
                  <tr className="border-b border-theme text-theme-muted">
                    <th className="py-2.5 px-3">التاريخ</th>
                    <th className="py-2.5 px-3">الصنف</th>
                    <th className="py-2.5 px-3">المستودع</th>
                    <th className="py-2.5 px-3">النوع</th>
                    <th className="py-2.5 px-3">الكمية</th>
                    <th className="py-2.5 px-3">البيان</th>
                    {canDelete && <th className="py-2.5 px-3">إجراء</th>}
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/5">
                  {(() => {
                    const whFilterSet = resolveWarehouseFilterIds(activeWhFilterId, warehouses);
                    return [...stockMoves]
                      .filter((m) => whFilterSet === null || whFilterSet.has(Number(m.warehouseId)))
                      .sort((a, b) => compareRecordsChronologically(a, b, 'desc'))
                      .map((m) => {
                        const isIn = isStockInMoveKind(m.kind);
                        const resolvedProd = productByIdMap.get(Number(m.productId));
                        const resolvedWh = warehouseByIdMap.get(Number(m.warehouseId));
                        const isOrphan =
                          (products.length > 0 && !resolvedProd) ||
                          (warehouses.length > 0 && !resolvedWh);
                        return (
                          <tr key={m.id} className="hover:bg-white/[0.02]">
                            <td className="py-2.5 px-3 font-mono text-theme-muted">
                              {new Date(m.createdAt || m.timestamp || Date.now()).toLocaleDateString('ar-EG')}
                            </td>
                            <td className="py-2.5 px-3 font-bold text-theme-primary">
                              {resolvedProd?.name || m.productName || `صنف محذوف (#${m.productId})`}
                              {isOrphan && (
                                <span className="mr-1.5 px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 text-[10px] font-normal">
                                  يتيم
                                </span>
                              )}
                            </td>
                            <td className="py-2.5 px-3 text-theme-secondary">
                              {resolvedWh?.name || m.warehouseName || `مستودع #${m.warehouseId}`}
                            </td>
                            <td className="py-2.5 px-3">
                              <GlassBadge variant={isIn ? 'green' : 'gold'}>
                                {isIn ? 'وارد (+)' : 'منصرف (-)'}
                              </GlassBadge>
                            </td>
                            <td className="py-2.5 px-3 font-mono font-bold">
                              {isIn ? '+' : '-'}
                              {Math.abs(Number(m.quantity || 0)).toLocaleString()}
                            </td>
                            <td className="py-2.5 px-3 text-theme-secondary">{m.note}</td>
                            {canDelete && (
                              <td className="py-2.5 px-3">
                                <button
                                  type="button"
                                  onClick={() => handleDeleteStockMove(m)}
                                  className="p-1.5 rounded-lg bg-red-500/10 hover:bg-red-500/20 text-red-400"
                                  title="حذف الحركة"
                                >
                                  <Trash2 className="w-3.5 h-3.5" />
                                </button>
                              </td>
                            )}
                          </tr>
                        );
                      });
                  })()}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </div>
      )}

      {/* SUB-TAB 4: ADD PRODUCT */}
      {subTab === 'ADD_PRODUCT' && (
        <GlassCard className="p-6 max-w-2xl">
          <h3 className="text-base font-black text-theme-primary mb-4">تعريف صنف جديد في كتالوج المنتجات</h3>
          <form onSubmit={handleAddProduct} className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
            <div>
              <label className="block text-theme-secondary mb-1">اسم الصنف</label>
              <input
                type="text"
                required
                value={prodForm.name}
                onChange={(e) => setProdForm({ ...prodForm, name: e.target.value })}
                placeholder="مثال: معجون حراري PMP أبيض"
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
              />
            </div>
            <div>
              <label className="block text-theme-secondary mb-1">كود الصنف (SKU)</label>
              <input
                type="text"
                value={prodForm.sku}
                onChange={(e) => setProdForm({ ...prodForm, sku: e.target.value })}
                placeholder="اختياري — يولد تلقائياً"
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block text-theme-secondary mb-1">اللون</label>
              <input
                type="text"
                value={prodForm.paintColor}
                onChange={(e) => setProdForm({ ...prodForm, paintColor: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
              />
            </div>
            <div>
              <label className="block text-theme-secondary mb-1">وزن العبوة</label>
              <select
                value={prodForm.packageWeight}
                onChange={(e) => setProdForm({ ...prodForm, packageWeight: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
              >
                <option value="250 جم">250 جم</option>
                <option value="900 جم">900 جم</option>
                <option value="4000 جم">4000 جم (جالون)</option>
                <option value="18 كجم">18 كجم (بستلة)</option>
              </select>
            </div>
            <div>
              <label className="block text-theme-secondary mb-1">سعر الكرتونة (SDG)</label>
              <input
                type="number"
                value={prodForm.salePrice}
                onChange={(e) => setProdForm({ ...prodForm, salePrice: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block text-theme-secondary mb-1">سعر الكرتونة (EGP)</label>
              <input
                type="number"
                value={prodForm.salePriceEgp}
                onChange={(e) => setProdForm({ ...prodForm, salePriceEgp: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block text-theme-secondary mb-1">الرصيد الافتتاحي (كرتونة)</label>
              <input
                type="number"
                value={prodForm.initialStock}
                onChange={(e) => setProdForm({ ...prodForm, initialStock: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div>
              <label className="block text-theme-secondary mb-1">حد إعادة الطلب</label>
              <input
                type="number"
                value={prodForm.minLevel}
                onChange={(e) => setProdForm({ ...prodForm, minLevel: e.target.value })}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
            <div className="sm:col-span-2">
              <GlassButton type="submit" variant="primary" className="w-full justify-center">
                حفظ الصنف في الكتالوج السحابي
              </GlassButton>
            </div>
          </form>
        </GlassCard>
      )}

      {/* Quick Stock Adjustment Modal */}
      <Modal
        isOpen={isQuickAdjustOpen}
        onClose={() => setIsQuickAdjustOpen(false)}
        title="تحديث رصيد الصنف في المخزن"
      >
        <form onSubmit={handleRecordStockMove} className="space-y-3 text-xs">
          <div>
            <label className="block text-theme-secondary mb-1">الصنف المختار</label>
            <select
              value={moveProductId}
              onChange={(e) => setMoveProductId(Number(e.target.value))}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              {computedProducts.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({p.packageWeight}) — الرصيد الحالي: {p.currentStock}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-theme-secondary mb-1">المستودع</label>
            <select
              value={moveWarehouseId}
              onChange={(e) => setMoveWarehouseId(Number(e.target.value))}
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            >
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name} ({w.branch})
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="block text-theme-secondary mb-1">نوع العملية</label>
              <select
                value={moveKind}
                onChange={(e) => setMoveKind(e.target.value as 'IN' | 'OUT' | 'SET')}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
              >
                <option value="SET">تثبيت الرصيد الفعلي الجديد (=)</option>
                <option value="IN">إضافة كمية واردة (+)</option>
                <option value="OUT">خصم كمية منصرفة (-)</option>
              </select>
            </div>
            <div>
              <label className="block text-theme-secondary mb-1">
                {moveKind === 'SET' ? 'الرصيد الفعلي (كرتونة)' : 'الكمية (كرتونة)'}
              </label>
              <input
                type="number"
                min="0"
                required
                value={moveQty}
                onChange={(e) => setMoveQty(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary font-mono"
              />
            </div>
          </div>
          <div>
            <label className="block text-theme-secondary mb-1">البيان / السبب</label>
            <input
              type="text"
              value={moveNote}
              onChange={(e) => setMoveNote(e.target.value)}
              placeholder="مثال: مطابقة الجرد الفعلي مع المستودع"
              className="w-full px-3 py-2 rounded-xl bg-theme-surface border border-theme text-theme-primary"
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <GlassButton type="button" variant="secondary" onClick={() => setIsQuickAdjustOpen(false)}>
              إلغاء
            </GlassButton>
            <GlassButton type="submit" variant="primary">
              حفظ وتحديث الرصيد فوراً
            </GlassButton>
          </div>
        </form>
      </Modal>
    </div>
  );
};
