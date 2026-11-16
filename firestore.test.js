const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const CLUSTER = "PMP-ERP-MAIN-CLUSTER";
const ADMIN_UID = "admin_root_001";
const MANAGER_UID = "manager_alice_123";
const ACCOUNTANT_UID = "accountant_omar_234";
const DEPUTY_UID = "deputy_sara_345";
const EMPLOYEE_SD_UID = "employee_bob_456";
const INTRUDER_UID = "intruder_charlie_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validLinkedAccountPayload(overrides = {}) {
  const ts = new Date(Date.now() - 1000);
  return {
    userCode: "USR-SD-1001",
    ownerId: MANAGER_UID,
    linkedUid: EMPLOYEE_SD_UID,
    clusterCode: CLUSTER,
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

async function seedClusterRbac() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const cRef = db.collection("erp_clusters").doc(CLUSTER);
    await cRef.set({ clusterCode: CLUSTER, initializedAt: Date.now() });
    await cRef.collection("system_metadata").doc("cluster_owner").set({ ownerUid: ADMIN_UID });

    const members = [
      { uid: ADMIN_UID, userCode: "USR-ADMIN", roleKey: "ADMIN", branch: "ALL", email: "admin@pmp.sd", active: true },
      { uid: MANAGER_UID, userCode: "USR-MGR-1", roleKey: "MANAGER", branch: "ALL", email: "mgr@pmp.sd", active: true },
      { uid: ACCOUNTANT_UID, userCode: "USR-ACC-1", roleKey: "ACCOUNTANT", branch: "ALL", email: "acc@pmp.sd", active: true },
      { uid: DEPUTY_UID, userCode: "USR-DEP-1", roleKey: "DEPUTY_ACCOUNTANT", branch: "فرع السودان (SD-001)", email: "dep@pmp.sd", active: true },
      { uid: EMPLOYEE_SD_UID, userCode: "USR-SD-1001", roleKey: "EMPLOYEE", branch: "فرع السودان (SD-001)", email: "emp@pmp.sd", active: true },
    ];
    for (const m of members) {
      await cRef.collection("cluster_members").doc(m.uid).set(m);
    }
  });
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

  const employeeDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();
  await assertSucceeds(employeeDb.collection("linked_accounts").doc("USR-SD-1001").get());
  await assertSucceeds(
    employeeDb.collection("linked_accounts").where("linkedUid", "==", EMPLOYEE_SD_UID).get()
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

  const employeeDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();
  await assertFails(
    employeeDb.collection("linked_accounts").doc("USR-SD-1001").update({
      permissions: "الخزن,المبيعات,المخازن,المصنع,الأسعار,التقارير,المستخدمين,الإعدادات",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(
    employeeDb.collection("linked_accounts").doc("USR-SD-1001").update({
      serverSyncToken: "SYNC-PMP-SD-1001-ACK",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("8. Unfiltered list query fails on linked_accounts (rules are not filters)", async () => {
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  await assertFails(managerDb.collection("linked_accounts").get());
});

test("9. Zero-Trust: Unauthenticated and non-member callers are denied on ERP clusters & operational roots", async () => {
  await seedClusterRbac();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID).firestore();

  await assertFails(unauthDb.collection("erp_clusters").doc(CLUSTER).collection("user_accounts").get());
  await assertFails(unauthDb.collection("erp_clusters").doc(CLUSTER).collection("invoices").get());
  await assertFails(intruderDb.collection("erp_clusters").doc(CLUSTER).collection("user_accounts").get());
  await assertFails(intruderDb.collection("erp_clusters").doc(CLUSTER).collection("invoices").doc("INV-1").set({ customerName: "Test", total: 100 }));
  await assertFails(unauthDb.collection("system_config").doc("app_version").set({ versionCode: 99 }));
});

test("10. Initial Admin Bootstrap locks down after first owner; intruder cannot self-promote to ADMIN", async () => {
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, { email: "admin@pmp.sd", email_verified: true }).firestore();
  const cAdmin = adminDb.collection("erp_clusters").doc(CLUSTER);

  await assertSucceeds(
    cAdmin.collection("cluster_members").doc(ADMIN_UID).set({
      uid: ADMIN_UID,
      userCode: "USR-ADMIN",
      roleKey: "ADMIN",
      branch: "ALL",
      active: true,
    })
  );
  await assertSucceeds(cAdmin.collection("system_metadata").doc("cluster_owner").set({ ownerUid: ADMIN_UID }));

  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID, { email: "hacker@evil.com", email_verified: true }).firestore();
  await assertFails(
    intruderDb.collection("erp_clusters").doc(CLUSTER).collection("cluster_members").doc(INTRUDER_UID).set({
      uid: INTRUDER_UID,
      userCode: "USR-HACK",
      roleKey: "ADMIN",
      branch: "ALL",
      active: true,
    })
  );
});

test("11. Verified email_directory claim enforces exact Admin-assigned role & blocks role tampering", async () => {
  await seedClusterRbac();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const emailKey = "new_emp_pmp_sd";

  await assertSucceeds(
    adminDb.collection("erp_clusters").doc(CLUSTER).collection("email_directory").doc(emailKey).set({
      email: "new.emp@pmp.sd",
      userCode: "USR-EMP-99",
      roleKey: "EMPLOYEE",
      branch: "فرع السودان (SD-001)",
      permissions: "المبيعات,العملاء",
      active: true,
    })
  );

  const verifiedNewUserDb = testEnv.authenticatedContext("new_uid_99", {
    email: "new.emp@pmp.sd",
    email_verified: true,
  }).firestore();

  // Attempt to self-claim as ADMIN instead of EMPLOYEE must fail
  await assertFails(
    verifiedNewUserDb.collection("erp_clusters").doc(CLUSTER).collection("cluster_members").doc("new_uid_99").set({
      uid: "new_uid_99",
      emailKey,
      userCode: "USR-EMP-99",
      roleKey: "ADMIN",
      branch: "ALL",
      permissions: "ALL",
      active: true,
    })
  );

  // Claiming exact Admin-provisioned role succeeds
  await assertSucceeds(
    verifiedNewUserDb.collection("erp_clusters").doc(CLUSTER).collection("cluster_members").doc("new_uid_99").set({
      uid: "new_uid_99",
      emailKey,
      userCode: "USR-EMP-99",
      roleKey: "EMPLOYEE",
      branch: "فرع السودان (SD-001)",
      permissions: "المبيعات,العملاء",
      active: true,
    })
  );
});

test("12. RBAC Hierarchy: Manager cannot escalate to/modify Admin or Manager; users cannot modify own role/permissions", async () => {
  await seedClusterRbac();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  const empDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();

  // Admin creates user_accounts doc for employee
  await assertSucceeds(
    adminDb.collection("erp_clusters").doc(CLUSTER).collection("user_accounts").doc("USR-SD-1001").set({
      userCode: "USR-SD-1001",
      linkedUid: EMPLOYEE_SD_UID,
      username: "sudan_emp",
      role: "موظف مبيعات",
      branch: "فرع السودان (SD-001)",
      permissions: "المبيعات",
      active: true,
    })
  );

  // Manager CAN create/update regular employee
  await assertSucceeds(
    managerDb.collection("erp_clusters").doc(CLUSTER).collection("user_accounts").doc("USR-SD-1002").set({
      userCode: "USR-SD-1002",
      username: "sudan_emp2",
      role: "موظف مخازن",
      branch: "فرع السودان (SD-001)",
      permissions: "المخازن",
      active: true,
    })
  );

  // Manager CANNOT create an Admin or Manager account or cluster_member
  await assertFails(
    managerDb.collection("erp_clusters").doc(CLUSTER).collection("cluster_members").doc("rogue_admin").set({
      uid: "rogue_admin",
      userCode: "USR-ROGUE",
      roleKey: "ADMIN",
      branch: "ALL",
      active: true,
    })
  );
  await assertFails(
    managerDb.collection("erp_clusters").doc(CLUSTER).collection("user_accounts").doc("USR-FAKE-ADMIN").set({
      userCode: "USR-FAKE-ADMIN",
      username: "fake_admin",
      role: "مدير النظام",
      branch: "ALL",
      active: true,
    })
  );
  // Manager CANNOT modify system_config or Admin user
  await assertFails(managerDb.collection("system_config").doc("app_version").set({ versionCode: 22 }));
  await assertFails(
    managerDb.collection("erp_clusters").doc(CLUSTER).collection("cluster_members").doc(ADMIN_UID).update({ active: false })
  );

  // Employee CANNOT modify their own role or permissions, CAN update allowed profile fields
  await assertFails(
    empDb.collection("erp_clusters").doc(CLUSTER).collection("user_accounts").doc("USR-SD-1001").update({
      role: "مدير النظام",
      permissions: "الإعدادات الإدارية",
    })
  );
  await assertFails(
    empDb.collection("erp_clusters").doc(CLUSTER).collection("cluster_members").doc(EMPLOYEE_SD_UID).update({
      roleKey: "ADMIN",
    })
  );
  await assertSucceeds(
    empDb.collection("erp_clusters").doc(CLUSTER).collection("user_accounts").doc("USR-SD-1001").update({
      fullName: "أحمد محمد المحدث",
    })
  );
});

test("13. Branch Isolation & Cross-Branch Write Prevention", async () => {
  await seedClusterRbac();
  const empSdDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();
  const cEmp = empSdDb.collection("erp_clusters").doc(CLUSTER);

  // Employee in SD-001 CAN create invoice in SD-001
  await assertSucceeds(
    cEmp.collection("invoices").doc("INV-SD-100").set({
      customerName: "عميل الخرطوم",
      branch: "فرع السودان (SD-001)",
      total: 1500,
      subtotal: 1500,
    })
  );

  // Employee in SD-001 CANNOT create invoice for EG-001 branch
  await assertFails(
    cEmp.collection("invoices").doc("INV-EG-100").set({
      customerName: "عميل القاهرة",
      branch: "فرع القاهرة (EG-001)",
      total: 2500,
      subtotal: 2500,
    })
  );
});

test("14. Audit logs, inventory_movements & accounting journal rules (Admin/Accountant vs Deputy/Employee)", async () => {
  await seedClusterRbac();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const accountantDb = testEnv.authenticatedContext(ACCOUNTANT_UID).firestore();
  const deputyDb = testEnv.authenticatedContext(DEPUTY_UID).firestore();
  const empDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();

  const cAcc = accountantDb.collection("erp_clusters").doc(CLUSTER);
  const cDep = deputyDb.collection("erp_clusters").doc(CLUSTER);
  const cEmp = empDb.collection("erp_clusters").doc(CLUSTER);

  // Audit logs append-only
  const auditRef = cEmp.collection("audit_logs").doc("AUD-1001");
  await assertSucceeds(auditRef.set({ action: "CREATE_INVOICE", actor: "bob", timestamp: Date.now() }));
  await assertFails(auditRef.update({ action: "TAMPERED" }));
  await assertFails(auditRef.delete());

  // Negative invoice rejected even for Admin
  await assertFails(
    adminDb.collection("erp_clusters").doc(CLUSTER).collection("invoices").doc("INV-BAD").set({
      customerName: "عميل",
      total: -500,
      subtotal: -500,
    })
  );

  // Unbalanced journal rejected
  await assertFails(
    cAcc.collection("journal_entries").doc("JRN-UNBALANCED").set({
      entryNo: "JRN-UNBALANCED",
      totalDebit: 1000,
      totalCredit: 900,
      status: "POSTED",
    })
  );

  // Balanced journal created by Accountant
  const jRef = cAcc.collection("journal_entries").doc("JRN-1001");
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
  // Deputy Accountant CANNOT reverse posted journal entry
  await assertFails(
    cDep.collection("journal_entries").doc("JRN-1001").update({
      status: "REVERSED",
      reversedByEntryNo: "REV-DEP",
      reversalReason: "محاولة عكس غير مصرحة",
      recordVersion: 2,
      updatedAt: Date.now(),
    })
  );
  // Accountant CAN reverse via strict 7-key POSTED -> REVERSED transition
  await assertSucceeds(
    jRef.update({
      status: "REVERSED",
      reversedByEntryNo: "REV-1001",
      reversalReason: "تصحيح خطأ إدخال",
      recordVersion: 2,
      updatedAt: Date.now(),
    })
  );
  // Second reversal blocked
  await assertFails(jRef.update({ status: "REVERSED", reversalReason: "ثاني عكس" }));

  // Journal lines immutable
  const lRef = cAcc.collection("journal_lines").doc("JL-1001-1");
  await assertSucceeds(lRef.set({ accountCode: "1110", debit: 1000, credit: 0 }));
  await assertFails(lRef.update({ debit: 500 }));
  await assertFails(lRef.delete());

  // Inventory movement append-only & reversal-only
  const movRef = cEmp.collection("inventory_movements").doc("IM-UUID-1");
  await assertSucceeds(
    movRef.set({
      syncUuid: "IM-UUID-1",
      movementType: "PURCHASE_RECEIPT",
      quantity: 50,
      status: "POSTED",
    })
  );
  await assertFails(movRef.delete());
  await assertFails(movRef.update({ status: "POSTED", quantity: 999 }));
  await assertSucceeds(
    adminDb.collection("erp_clusters").doc(CLUSTER).collection("inventory_movements").doc("IM-UUID-1").update({ status: "REVERSED" })
  );
});

test("15. Idempotency Protection: idempotency_requests enforce schema, block reverting COMPLETED, deny deletion", async () => {
  await seedClusterRbac();
  const empDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();
  const cRef = empDb.collection("erp_clusters").doc(CLUSTER);
  const idemRef = cRef.collection("idempotency_requests").doc("IDEM-INV-2026-0001");

  await assertFails(
    cRef.collection("idempotency_requests").doc("IDEM-BAD-1").set({
      idempotencyKey: "IDEM-BAD-1",
      operationType: "CREATE_INVOICE",
      requestStatus: "INVALID_STATE",
    })
  );

  await assertSucceeds(
    idemRef.set({
      idempotencyKey: "IDEM-INV-2026-0001",
      userId: "bob",
      operationType: "CREATE_INVOICE",
      documentReference: "INV-2026-0001",
      requestStatus: "PROCESSING",
      createdAt: Date.now(),
      updatedAt: Date.now(),
      platform: "ANDROID",
    })
  );

  await assertSucceeds(
    idemRef.update({
      requestStatus: "COMPLETED",
      resultReference: "INV-2026-0001",
      completionTime: Date.now(),
      updatedAt: Date.now(),
    })
  );

  await assertFails(idemRef.update({ requestStatus: "PROCESSING", updatedAt: Date.now() }));
  await assertFails(idemRef.update({ requestStatus: "FAILED", updatedAt: Date.now() }));
  await assertFails(idemRef.delete());
});
