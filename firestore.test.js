const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const MANAGER_UID = "manager_alice_123";
const EMPLOYEE_UID = "employee_bob_456";
const INTRUDER_UID = "intruder_charlie_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validLinkedAccountPayload(overrides = {}) {
  const ts = new Date(Date.now() - 1000);
  return {
    userCode: "USR-SD-1001",
    ownerId: MANAGER_UID,
    linkedUid: EMPLOYEE_UID,
    clusterCode: "PMP-ERP-MAIN-CLUSTER",
    fullName: "أحمد محمد أحمد - محاسب فرع السودان",
    username: "sudan_acc",
    role: "محاسب خزنة ومبيعات",
    branch: "فرع السودان (SD-001)",
    permissions: "الخزن,المبيعات,المخازن",
    serverSyncToken: "SYNC-PMP-SD-1001",
    active: true,
    createdAt: ts,
    updatedAt: ts,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("1. Unauthenticated user: cannot read or create linked_accounts", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("linked_accounts").doc("USR-SD-1001").get());
  await assertFails(
    unauthDb.collection("linked_accounts").doc("USR-SD-1001").set(validLinkedAccountPayload())
  );
});

test("2. Manager (ownerId): can create valid linked account for employee", async () => {
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  await assertSucceeds(
    managerDb.collection("linked_accounts").doc("USR-SD-1001").set(validLinkedAccountPayload())
  );
});

test("3. Identity spoofing on create: fails if ownerId does not match request.auth.uid", async () => {
  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID).firestore();
  await assertFails(
    intruderDb
      .collection("linked_accounts")
      .doc("USR-SD-1001")
      .set(validLinkedAccountPayload({ ownerId: MANAGER_UID }))
  );
});

test("4. Cross-user isolation: third-party user cannot read another employee's linked account", async () => {
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  await assertSucceeds(
    managerDb.collection("linked_accounts").doc("USR-SD-1001").set(validLinkedAccountPayload())
  );

  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID).firestore();
  await assertFails(intruderDb.collection("linked_accounts").doc("USR-SD-1001").get());
});

test("5. Linked employee (linkedUid): can read their assigned account and query by linkedUid", async () => {
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  await assertSucceeds(
    managerDb.collection("linked_accounts").doc("USR-SD-1001").set(validLinkedAccountPayload())
  );

  const employeeDb = testEnv.authenticatedContext(EMPLOYEE_UID).firestore();
  await assertSucceeds(employeeDb.collection("linked_accounts").doc("USR-SD-1001").get());
  await assertSucceeds(
    employeeDb.collection("linked_accounts").where("linkedUid", "==", EMPLOYEE_UID).get()
  );
});

test("6. Manager: can query all accounts they own and update employee permissions", async () => {
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  await assertSucceeds(
    managerDb.collection("linked_accounts").doc("USR-SD-1001").set(validLinkedAccountPayload())
  );

  await assertSucceeds(
    managerDb.collection("linked_accounts").where("ownerId", "==", MANAGER_UID).get()
  );

  await assertSucceeds(
    managerDb.collection("linked_accounts").doc("USR-SD-1001").update({
      permissions: "الخزن,المبيعات",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("7. Privilege escalation blocked: linked employee cannot modify their own permissions or role", async () => {
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  await assertSucceeds(
    managerDb.collection("linked_accounts").doc("USR-SD-1001").set(validLinkedAccountPayload())
  );

  const employeeDb = testEnv.authenticatedContext(EMPLOYEE_UID).firestore();
  await assertFails(
    employeeDb.collection("linked_accounts").doc("USR-SD-1001").update({
      permissions: "الخزن,المبيعات,المخازن,المصنع,الأسعار,التقارير,المستخدمين,الإعدادات",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Employee CAN update their serverSyncToken / heartbeat
  await assertSucceeds(
    employeeDb.collection("linked_accounts").doc("USR-SD-1001").update({
      serverSyncToken: "SYNC-PMP-SD-1001-ACK",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("8. Unfiltered list query fails (rules are not filters)", async () => {
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  await assertFails(managerDb.collection("linked_accounts").get());
});

test("9. ERP Cluster: multi-user nodes can sync user_accounts and invoices in erp_clusters", async () => {
  const nodeADb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  const nodeBDb = testEnv.unauthenticatedContext().firestore();

  await assertSucceeds(
    nodeADb
      .collection("erp_clusters")
      .doc("PMP-ERP-MAIN-CLUSTER")
      .collection("user_accounts")
      .doc("USR-HQ-001")
      .set({
        userCode: "USR-HQ-001",
        username: "admin",
        role: "مدير النظام",
        permissions: "الخزن,التحويلات,المبيعات,تصدير PDF,المخازن,المصنع,الأسعار,التقارير,المستخدمين,الإعدادات",
        active: true,
      })
  );

  await assertSucceeds(
    nodeBDb
      .collection("erp_clusters")
      .doc("PMP-ERP-MAIN-CLUSTER")
      .collection("user_accounts")
      .get()
  );
});

test("10. Security Hardening: cluster audit_logs are append-only and negative invoice/stock values are rejected", async () => {
  const clientDb = testEnv.unauthenticatedContext().firestore();
  const auditRef = clientDb
    .collection("erp_clusters")
    .doc("PMP-ERP-MAIN-CLUSTER")
    .collection("audit_logs")
    .doc("AUD-1001");

  await assertSucceeds(auditRef.set({ action: "CREATE_INVOICE", actor: "admin", timestamp: Date.now() }));
  await assertFails(auditRef.update({ action: "TAMPERED" }));
  await assertFails(auditRef.delete());

  await assertFails(
    clientDb
      .collection("erp_clusters")
      .doc("PMP-ERP-MAIN-CLUSTER")
      .collection("invoices")
      .doc("INV-BAD")
      .set({ customerName: "عميل", total: -500, subtotal: -500 })
  );

  await assertFails(
    clientDb
      .collection("erp_clusters")
      .doc("PMP-ERP-MAIN-CLUSTER")
      .collection("stock_moves")
      .doc("SM-BAD")
      .set({ productId: 1, warehouseId: 1, kind: "IN", quantity: -25 })
  );
});

test("11. Phase 2 Rules: inventory_movements are append-only (no delete, reversal-only update) and balances reject negatives", async () => {
  const clientDb = testEnv.unauthenticatedContext().firestore();
  const movRef = clientDb
    .collection("erp_clusters")
    .doc("PMP-ERP-MAIN-CLUSTER")
    .collection("inventory_movements")
    .doc("IM-UUID-1");

  await assertSucceeds(
    movRef.set({
      syncUuid: "IM-UUID-1",
      movementType: "PURCHASE_RECEIPT",
      quantity: 50,
      status: "POSTED",
    })
  );

  // Deleting an inventory movement must fail
  await assertFails(movRef.delete());

  // Arbitrary update mutating quantity must fail
  await assertFails(movRef.update({ status: "POSTED", quantity: 999 }));

  // Reversal state transition POSTED -> REVERSED succeeds
  await assertSucceeds(movRef.update({ status: "REVERSED" }));

  // Negative balance must fail
  await assertFails(
    clientDb
      .collection("erp_clusters")
      .doc("PMP-ERP-MAIN-CLUSTER")
      .collection("warehouse_stock_balances")
      .doc("BAL-BAD")
      .set({ onHandQty: -10, availableQty: -10 })
  );

  // Invalid packaging rule (cansPerCarton <= 0) must fail
  await assertFails(
    clientDb
      .collection("erp_clusters")
      .doc("PMP-ERP-MAIN-CLUSTER")
      .collection("packaging_rules")
      .doc("PKG-BAD")
      .set({ weightGrams: 900, cansPerCarton: 0 })
  );
});

test("12. Phase 3 Rules: purchase_receipts, production_orders & qc_inspections validate positive/non-negative bounds", async () => {
  const clientDb = testEnv.unauthenticatedContext().firestore();
  const cRef = clientDb.collection("erp_clusters").doc("PMP-ERP-MAIN-CLUSTER");

  await assertSucceeds(
    cRef.collection("purchase_receipts").doc("GRN-1001").set({
      grnNo: "GRN-1001",
      materialName: "تيتانيوم أبيض",
      receivedQty: 100,
      acceptedQty: 90,
      rejectedQty: 10,
      qcStatus: "PARTIAL",
    })
  );

  await assertFails(
    cRef.collection("purchase_receipts").doc("GRN-BAD").set({
      receivedQty: 50,
      acceptedQty: -5,
    })
  );

  await assertSucceeds(
    cRef.collection("production_orders").doc("PRD-1001").set({
      orderNo: "PRD-1001",
      productName: "بوهية أبيض 900 جم",
      packageWeightGrams: 900,
      targetCartons: 20,
      status: "APPROVED",
    })
  );

  await assertFails(
    cRef.collection("production_orders").doc("PRD-BAD").set({
      packageWeightGrams: 900,
      targetCartons: 0,
    })
  );

  await assertSucceeds(
    cRef.collection("qc_inspections").doc("QC-1001").set({
      qcNo: "QC-1001",
      inspectedQty: 240,
      acceptedQty: 240,
      rejectedQty: 0,
      status: "APPROVED",
    })
  );
});

test("13. Phase 4 Rules: journal_entries enforce DR==CR balance & append-only reversals, journal_lines are immutable", async () => {
  const clientDb = testEnv.unauthenticatedContext().firestore();
  const cRef = clientDb.collection("erp_clusters").doc("PMP-ERP-MAIN-CLUSTER");

  await assertSucceeds(
    cRef.collection("accounting_accounts").doc("ACC-1110").set({
      accountCode: "1110",
      nameAr: "صندوق النقدية - السودان (SDG)",
      accountType: "ASSET",
    })
  );

  await assertFails(
    cRef.collection("journal_entries").doc("JRN-UNBALANCED").set({
      entryNo: "JRN-UNBALANCED",
      totalDebit: 1000,
      totalCredit: 900,
      status: "POSTED",
    })
  );

  const jRef = cRef.collection("journal_entries").doc("JRN-1001");
  await assertSucceeds(
    jRef.set({
      entryNo: "JRN-1001",
      totalDebit: 1000,
      totalCredit: 1000,
      status: "POSTED",
      recordVersion: 1,
    })
  );

  await assertFails(jRef.delete());
  await assertFails(jRef.update({ totalDebit: 2000, totalCredit: 2000, status: "REVERSED" }));

  await assertSucceeds(
    jRef.update({
      status: "REVERSED",
      reversedByEntryNo: "REV-1001",
      reversalReason: "تصحيح خطأ إدخال",
      recordVersion: 2,
      updatedAt: Date.now(),
    })
  );

  await assertFails(
    jRef.update({
      status: "REVERSED",
      reversalReason: "ثاني عكس",
    })
  );

  const lRef = cRef.collection("journal_lines").doc("JL-1001-1");
  await assertFails(
    cRef.collection("journal_lines").doc("JL-BAD").set({
      debit: -50,
      credit: 0,
    })
  );
  await assertSucceeds(
    lRef.set({
      accountCode: "1110",
      debit: 1000,
      credit: 0,
    })
  );
  await assertFails(lRef.update({ debit: 500 }));
  await assertFails(lRef.delete());
});

test("14. Idempotency Protection: idempotency_requests enforce schema, block reverting COMPLETED status, and deny deletion", async () => {
  const clientDb = testEnv.unauthenticatedContext().firestore();
  const cRef = clientDb.collection("erp_clusters").doc("PMP-ERP-MAIN-CLUSTER");
  const idemRef = cRef.collection("idempotency_requests").doc("IDEM-INV-2026-0001");

  // Missing required fields or invalid status must fail
  await assertFails(
    cRef.collection("idempotency_requests").doc("IDEM-BAD-1").set({
      idempotencyKey: "IDEM-BAD-1",
      operationType: "CREATE_INVOICE",
      requestStatus: "INVALID_STATE",
    })
  );

  // Create in PROCESSING state succeeds
  await assertSucceeds(
    idemRef.set({
      idempotencyKey: "IDEM-INV-2026-0001",
      userId: "admin",
      operationType: "CREATE_INVOICE",
      documentReference: "INV-2026-0001",
      requestStatus: "PROCESSING",
      createdAt: Date.now(),
      updatedAt: Date.now(),
      platform: "WEB",
    })
  );

  // Transition PROCESSING -> COMPLETED succeeds
  await assertSucceeds(
    idemRef.update({
      requestStatus: "COMPLETED",
      resultReference: "INV-2026-0001",
      completionTime: Date.now(),
      updatedAt: Date.now(),
    })
  );

  // Reverting COMPLETED -> PROCESSING or FAILED must fail
  await assertFails(
    idemRef.update({
      requestStatus: "PROCESSING",
      updatedAt: Date.now(),
    })
  );
  await assertFails(
    idemRef.update({
      requestStatus: "FAILED",
      updatedAt: Date.now(),
    })
  );

  // Deleting idempotency request record must fail
  await assertFails(idemRef.delete());
});




