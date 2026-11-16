import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import ts from 'typescript';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_DIR = path.resolve(__dirname, '..');
const SRC_DIR = path.join(WEB_DIR, 'src');
const tmpOutDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmp-non-resurrection-tests-'));

function transpileRelative(relPath, outRelPath, transformSource = (s) => s) {
  const fullPath = path.join(SRC_DIR, relPath);
  let source = fs.readFileSync(fullPath, 'utf8');
  source = transformSource(source);
  const transpiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler
    }
  });
  const outPath = path.join(tmpOutDir, outRelPath);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, transpiled.outputText, 'utf8');
  return outPath;
}

transpileRelative('types/erp.ts', 'types/erp.mjs');
const calcPath = transpileRelative('services/ErpCalculations.ts', 'services/ErpCalculations.mjs', (s) =>
  s.replace('../types/erp', '../types/erp.mjs')
);
const calcMod = await import(pathToFileURL(calcPath).href);

test.after(() => {
  fs.rmSync(tmpOutDir, { recursive: true, force: true });
});

test('buildTombstoneKeySet & isRecordTombstoned match MOV-id, Web idempotency docId, reference, syncUuid, and pre-epoch records', () => {
  const { buildTombstoneKeySet, isRecordTombstoned } = calcMod;

  const tombDocs = [
    {
      collection: 'movements',
      docId: 'MOV-IDEM-ABC123',
      entityId: '501',
      numericId: 501,
      reference: 'REC-778899',
      syncUuid: 'uuid-mov-501',
      aliases: ['501', 'MOV-501', 'MOV-IDEM-ABC123', 'REC-778899', 'uuid-mov-501']
    },
    {
      collection: 'cashboxes',
      docId: 'CB-HALFA',
      entityId: 'خزنة حلفا'
    }
  ];

  const keyMap = buildTombstoneKeySet(tombDocs);
  const clusterEpoch = 1740000000000;

  // Match by Android numeric id + prefix
  assert.equal(isRecordTombstoned('movements', { id: 501, cashbox: 'خزنة الخرطوم', createdAt: 1750000000000 }, keyMap, clusterEpoch), true);
  // Match by Web idempotency docId even with a different synthetic local id
  assert.equal(isRecordTombstoned('movements', { _docId: 'MOV-IDEM-ABC123', id: 9999, createdAt: 1750000000000 }, keyMap, clusterEpoch), true);
  // Match by shared receipt reference across platforms
  assert.equal(isRecordTombstoned('movements', { _docId: 'MOV-OTHER', id: 8888, reference: 'REC-778899', createdAt: 1750000000000 }, keyMap, clusterEpoch), true);
  // Match by shared syncUuid
  assert.equal(isRecordTombstoned('movements', { _docId: 'MOV-X', id: 7777, syncUuid: 'uuid-mov-501', createdAt: 1750000000000 }, keyMap, clusterEpoch), true);
  // Match pre-epoch historical transaction even if no explicit doc tombstone exists
  assert.equal(isRecordTombstoned('movements', { _docId: 'MOV-10', id: 10, createdAt: 1730000000000 }, keyMap, clusterEpoch), true);
  // Active post-epoch movement unaffected
  assert.equal(isRecordTombstoned('movements', { _docId: 'MOV-600', id: 600, reference: 'REC-ACTIVE-600', createdAt: 1750000000000 }, keyMap, clusterEpoch), false);
  // Arabic cashbox tombstone match
  assert.equal(isRecordTombstoned('cashboxes', { _docId: 'BOX-9', id: 9, name: 'خزنه حلفا' }, keyMap, clusterEpoch), true);
});

test('deduplicateCollectionRecords merges cross-platform Web+Android movement duplicates while keeping transfer legs separate', () => {
  const { deduplicateCollectionRecords } = calcMod;

  const list = [
    // Web doc and Android doc for same movement (same reference, cashbox, direction, currency, amount)
    {
      _docId: 'MOV-IDEM-1',
      id: 9001,
      cashbox: 'خزنة السودان',
      currency: 'SDG',
      direction: 'وارد',
      amount: 120000,
      reference: 'REC-555',
      updatedAt: 100
    },
    {
      _docId: 'MOV-14',
      id: 14,
      cashbox: 'خزنه السودان',
      currency: 'SDG',
      direction: 'وارد',
      amount: 120000,
      reference: 'REC-555',
      updatedAt: 200
    },
    // Inter-cashbox transfer legs share same reference REC-TRF-100 across two cashboxes/directions -> MUST NOT collapse!
    {
      _docId: 'MOV-20',
      id: 20,
      cashbox: 'خزنة السودان',
      currency: 'SDG',
      direction: 'منصرف',
      amount: 50000,
      reference: 'TRF-100'
    },
    {
      _docId: 'MOV-21',
      id: 21,
      cashbox: 'خزنة مصر',
      currency: 'EGP',
      direction: 'وارد',
      amount: 1000,
      reference: 'TRF-100'
    }
  ];

  const deduped = deduplicateCollectionRecords('movements', list);
  assert.equal(deduped.length, 3);
  assert.equal(deduped.filter((m) => m.reference === 'TRF-100').length, 2);
});

test('Web modules use tombstone-backed deletions for movements/cashboxes/warehouses & reactive epoch filtering', () => {
  const treasurySrc = fs.readFileSync(path.join(SRC_DIR, 'components/modules/TreasuryModuleView.tsx'), 'utf8');
  const warehouseSrc = fs.readFileSync(path.join(SRC_DIR, 'components/modules/WarehousesModuleView.tsx'), 'utf8');
  const erpDataSrc = fs.readFileSync(path.join(SRC_DIR, 'services/ErpDataService.ts'), 'utf8');

  assert.equal(treasurySrc.includes("ErpDataService.deleteDocument('movements'"), false);
  assert.equal(treasurySrc.includes("ErpDataService.deleteDocument('cashboxes'"), false);
  assert.equal(treasurySrc.includes("ErpDataService.deleteDocumentWithTombstone(\n          'movements'"), true);
  assert.equal(warehouseSrc.includes("ErpDataService.deleteDocument('warehouses'"), false);
  assert.equal(erpDataSrc.includes('cluster_epoch'), true);
  assert.equal(erpDataSrc.includes('activeCollectionRefreshers'), true);
  assert.equal(erpDataSrc.includes('refilterAndEmit'), true);
});
