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

test("16. Hardened Movements, Transfers & Distributed Tombstone Non-Resurrection Rules", async () => {
  await seedClusterRbac();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  const empDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();
  const cMgr = managerDb.collection("erp_clusters").doc(CLUSTER);
  const cAdm = adminDb.collection("erp_clusters").doc(CLUSTER);
  const cEmp = empDb.collection("erp_clusters").doc(CLUSTER);

  // Invalid currency or negative transfer rate rejected
  await assertFails(
    cMgr.collection("movements").doc("MOV-BAD-CUR").set({
      cashbox: "خزنة السودان",
      currency: "EUR",
      direction: "وارد",
      amount: 500,
    })
  );
  await assertFails(
    cMgr.collection("transfers").doc("TRF-BAD-RATE").set({
      sourceAmount: 1000,
      destinationAmount: 500,
      rate: 0,
    })
  );

  // Manager CAN create & merge-update deleted_records tombstones with multi-aliases, CANNOT delete tombstones
  const tombRef = cMgr.collection("deleted_records").doc("movements_MOV-500");
  await assertSucceeds(
    tombRef.set({
      collection: "movements",
      docId: "MOV-500",
      entityId: "500",
      numericId: 500,
      aliases: ["500", "MOV-500", "REC-500"],
      deletedBy: "mgr",
    })
  );
  await assertSucceeds(
    tombRef.set(
      {
        collection: "movements",
        docId: "MOV-500",
        entityId: "500",
        aliases: ["500", "MOV-500", "REC-500", "UUID-500"],
      },
      { merge: true }
    )
  );
  await assertFails(tombRef.delete());
  await assertFails(
    cEmp.collection("deleted_records").doc("movements_MOV-EMP").set({
      collection: "movements",
      docId: "MOV-EMP",
      entityId: "1",
    })
  );

  // Admin CAN delete tombstone only if explicitly restoring
  await assertSucceeds(cAdm.collection("deleted_records").doc("movements_MOV-500").delete());
});

test("17. Admin Own Account Sync, Manager Boundaries & Idempotent deleted_users Merge", async () => {
  await seedClusterRbac();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("user_accounts").doc("USR-ADMIN").set({
      userCode: "USR-ADMIN",
      username: "admin",
      fullName: "المدير العام",
      role: "مدير النظام (Super Admin)",
      branch: "ALL",
      linkedUid: ADMIN_UID,
      active: true,
    });
    await cRef.collection("user_accounts").doc("USR-EMP-1").set({
      userCode: "USR-EMP-1",
      username: "emp1",
      fullName: "موظف",
      role: "موظف مبيعات",
      branch: "فرع السودان (SD-001)",
      linkedUid: EMPLOYEE_SD_UID,
      active: true,
    });
  });

  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  const cAdm = adminDb.collection("erp_clusters").doc(CLUSTER);
  const cMgr = managerDb.collection("erp_clusters").doc(CLUSTER);

  // Admin can update own USR-ADMIN doc when active remains true
  await assertSucceeds(
    cAdm.collection("user_accounts").doc("USR-ADMIN").set(
      {
        userCode: "USR-ADMIN",
        username: "admin",
        active: true,
        serverSyncToken: "SYNC-ADMIN-NEW",
        updatedAt: Date.now(),
      },
      { merge: true }
    )
  );
  // Admin cannot deactivate self
  await assertFails(
    cAdm.collection("user_accounts").doc("USR-ADMIN").set({ active: false }, { merge: true })
  );

  // Manager can update employee and create/merge deleted_users for non-admin
  await assertSucceeds(
    cMgr.collection("user_accounts").doc("USR-EMP-1").set(
      {
        fullName: "موظف مبيعات محدث",
        branch: "فرع السودان (SD-001)",
        role: "موظف مبيعات",
        active: true,
      },
      { merge: true }
    )
  );
  await assertFails(
    cMgr.collection("user_accounts").doc("USR-ADMIN").set(
      { fullName: "تلاعب", active: false },
      { merge: true }
    )
  );

  await assertSucceeds(
    cMgr.collection("deleted_users").doc("USR-DEL-99").set({
      userCode: "USR-DEL-99",
      username: "old_emp",
      deletedBy: "mgr",
      deletedAt: Date.now(),
    })
  );
  await assertSucceeds(
    cMgr.collection("deleted_users").doc("USR-DEL-99").set(
      { userCode: "USR-DEL-99", reason: "تأكيد" },
      { merge: true }
    )
  );
  await assertFails(
    cMgr.collection("deleted_users").doc("USR-ADMIN").set({
      userCode: "USR-ADMIN",
      username: "admin",
    })
  );
});

test("18. Treasury Permission Grant for Cashiers & Direct Idempotency Completion", async () => {
  await seedClusterRbac();
  const TREASURY_EMP_UID = "treasury_cashier_uid_555";
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("cluster_members").doc(TREASURY_EMP_UID).set({
      uid: TREASURY_EMP_UID,
      userCode: "USR-TREAS-1",
      roleKey: "EMPLOYEE",
      branch: "فرع السودان (SD-001)",
      permissions: "الخزن,العمليات المالية,المبيعات",
      email: "cashier@pmp.sd",
      active: true,
    });
  });

  const cashierDb = testEnv.authenticatedContext(TREASURY_EMP_UID).firestore();
  const plainEmpDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();
  const cCashier = cashierDb.collection("erp_clusters").doc(CLUSTER);
  const cPlainEmp = plainEmpDb.collection("erp_clusters").doc(CLUSTER);

  // Cashier with treasury permission CAN record movement & transfer in same branch
  await assertSucceeds(
    cCashier.collection("movements").doc("MOV-CASHIER-1").set({
      cashbox: "خزنة السودان",
      currency: "SDG",
      direction: "وارد",
      amount: 15000,
      branch: "فرع السودان (SD-001)",
    })
  );
  await assertSucceeds(
    cCashier.collection("transfers").doc("TRF-CASHIER-1").set({
      sourceAmount: 5000,
      destinationAmount: 5000,
      rate: 1,
      branch: "فرع السودان (SD-001)",
    })
  );

  // Cross-branch movement is denied even for treasury employee
  await assertFails(
    cCashier.collection("movements").doc("MOV-CAIRO-1").set({
      cashbox: "خزنة مصر",
      currency: "EGP",
      direction: "وارد",
      amount: 1000,
      branch: "فرع مصر (EG-001)",
    })
  );

  // Plain employee WITHOUT treasury permission cannot create movement or cashbox
  await assertFails(
    cPlainEmp.collection("movements").doc("MOV-PLAIN-1").set({
      cashbox: "خزنة السودان",
      currency: "SDG",
      direction: "وارد",
      amount: 500,
      branch: "فرع السودان (SD-001)",
    })
  );
  await assertFails(
    cCashier.collection("cashboxes").doc("BOX-NEW").set({
      name: "خزنة غير مصرحة",
      branch: "فرع السودان (SD-001)",
    })
  );

  // Direct COMPLETED idempotency write with operationType succeeds, then blocks mutation
  const directIdemRef = cCashier.collection("idempotency_requests").doc("IDEM-DIRECT-1");
  await assertSucceeds(
    directIdemRef.set({
      idempotencyKey: "IDEM-DIRECT-1",
      operationType: "RECEIPT_VOUCHER",
      requestStatus: "COMPLETED",
      resultReference: "MOV-CASHIER-1",
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })
  );
  await assertFails(directIdemRef.set({ requestStatus: "PROCESSING", updatedAt: Date.now() }, { merge: true }));
});

test("19. Chemical Lab RBAC & IP Protection: plain employee denied secret formulas, Lab Officer edits NEEDS_REVIEW only, Approver approves, explicitly denied Manager blocked", async () => {
  await seedClusterRbac();
  const LAB_OFFICER_UID = "lab_officer_uid_701";
  const LAB_VIEWONLY_UID = "lab_viewonly_uid_702";
  const DENIED_MGR_UID = "denied_mgr_uid_703";

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("cluster_members").doc(LAB_OFFICER_UID).set({
      uid: LAB_OFFICER_UID,
      userCode: "USR-LAB-1",
      roleKey: "EMPLOYEE",
      branch: "فرع السودان (SD-001)",
      permissions: "VIEW_SECRET_FORMULAS,المختبر الكيميائي",
      active: true,
    });
    await cRef.collection("cluster_members").doc(LAB_VIEWONLY_UID).set({
      uid: LAB_VIEWONLY_UID,
      userCode: "USR-LAB-2",
      roleKey: "EMPLOYEE",
      branch: "فرع السودان (SD-001)",
      permissions: "المختبر الكيميائي,VIEW_ONLY",
      active: true,
    });
    await cRef.collection("cluster_members").doc(DENIED_MGR_UID).set({
      uid: DENIED_MGR_UID,
      userCode: "USR-MGR-DENY",
      roleKey: "MANAGER",
      branch: "ALL",
      permissions: "DENY:VIEW_SECRET_FORMULAS,DENY:APPROVE_FORMULAS",
      active: true,
    });
  });

  const empDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();
  const labDb = testEnv.authenticatedContext(LAB_OFFICER_UID).firestore();
  const viewOnlyDb = testEnv.authenticatedContext(LAB_VIEWONLY_UID).firestore();
  const deniedMgrDb = testEnv.authenticatedContext(DENIED_MGR_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  const cEmp = empDb.collection("erp_clusters").doc(CLUSTER);
  const cLab = labDb.collection("erp_clusters").doc(CLUSTER);
  const cView = viewOnlyDb.collection("erp_clusters").doc(CLUSTER);
  const cDeniedMgr = deniedMgrDb.collection("erp_clusters").doc(CLUSTER);
  const cAdmin = adminDb.collection("erp_clusters").doc(CLUSTER);

  // Plain employee CANNOT create or read secret formulations or versions
  await assertFails(
    cEmp.collection("lab_formulations").doc("LAB-1").set({
      productName: "Secret Gloss",
      approvalStatus: "NEEDS_REVIEW",
      ingredientsJson: "[{\"name\":\"TiO2\",\"pct\":25}]",
    })
  );

  // Lab Officer CAN create formula with NEEDS_REVIEW
  await assertSucceeds(
    cLab.collection("lab_formulations").doc("LAB-1").set({
      productName: "Acrylic Coat v1",
      branch: "فرع السودان (SD-001)",
      approvalStatus: "NEEDS_REVIEW",
      approvedForProduction: false,
      ingredientsJson: "[{\"name\":\"Resin\",\"pct\":45}]",
    })
  );

  // Lab Officer CANNOT self-approve formula to APPROVED / approvedForProduction = true
  await assertFails(
    cLab.collection("lab_formulations").doc("LAB-SELF-APP").set({
      productName: "Bypass Attempt",
      branch: "فرع السودان (SD-001)",
      approvalStatus: "APPROVED",
      approvedForProduction: true,
      ingredientsJson: "[]",
    })
  );
  await assertFails(
    cLab.collection("lab_formulations").doc("LAB-1").update({
      approvalStatus: "APPROVED",
      approvedForProduction: true,
    })
  );

  // View-only Lab user CAN read secret formula, CANNOT modify it
  await assertSucceeds(cView.collection("lab_formulations").doc("LAB-1").get());
  await assertFails(
    cView.collection("lab_formulations").doc("LAB-1").update({
      productName: "Tampered",
    })
  );

  // Plain employee CANNOT read secret formula
  await assertFails(cEmp.collection("lab_formulations").doc("LAB-1").get());

  // Admin CAN approve formula
  await assertSucceeds(
    cAdmin.collection("lab_formulations").doc("LAB-1").update({
      approvalStatus: "APPROVED",
      approvedForProduction: true,
    })
  );

  // Manager with explicit DENY:VIEW_SECRET_FORMULAS / DENY:APPROVE_FORMULAS is blocked even with MANAGER roleKey
  await assertFails(cDeniedMgr.collection("lab_formulations").doc("LAB-1").get());
  await assertFails(
    cDeniedMgr.collection("lab_formulations").doc("LAB-1").update({
      approvalStatus: "APPROVED",
      approvedForProduction: true,
    })
  );
});

test("20. Chemical Lab Non-Secret Summary Enforcement & Version Immutability", async () => {
  await seedClusterRbac();
  const LAB_OFFICER_UID = "lab_officer_uid_704";
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("cluster_members").doc(LAB_OFFICER_UID).set({
      uid: LAB_OFFICER_UID,
      userCode: "USR-LAB-4",
      roleKey: "EMPLOYEE",
      branch: "ALL",
      permissions: "المختبر الكيميائي",
      active: true,
    });
  });

  const cLab = testEnv.authenticatedContext(LAB_OFFICER_UID).firestore().collection("erp_clusters").doc(CLUSTER);
  const cEmp = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore().collection("erp_clusters").doc(CLUSTER);
  const cMgr = testEnv.authenticatedContext(MANAGER_UID).firestore().collection("erp_clusters").doc(CLUSTER);

  // Non-secret summary write MUST reject secret fields (ingredientsJson / confidentialCiphertext)
  await assertFails(
    cLab.collection("lab_formula_summaries").doc("LAB-1").set({
      formulaId: "LAB-1",
      productName: "Acrylic Satin",
      ingredientsJson: "[{\"name\":\"Secret\"}]",
    })
  );

  // Valid non-secret summary succeeds & is readable by active Production/Sales employees
  await assertSucceeds(
    cLab.collection("lab_formula_summaries").doc("LAB-1").set({
      formulaId: "LAB-1",
      code: "PMP-101",
      productName: "Acrylic Satin",
      approvalStatus: "APPROVED",
      approvedForProduction: true,
    })
  );
  await assertSucceeds(cEmp.collection("lab_formula_summaries").doc("LAB-1").get());

  // Plain employee CANNOT overwrite summary
  await assertFails(
    cEmp.collection("lab_formula_summaries").doc("LAB-1").update({ productName: "Hacked" })
  );

  // Manager approves version snapshot; Lab Officer CANNOT subsequently mutate APPROVED version snapshot
  await assertSucceeds(
    cMgr.collection("lab_formula_versions").doc("LAB-1-v1").set({
      formulaId: "LAB-1",
      version: 1,
      approvalStatus: "APPROVED",
      lockedTotalCost: 240.5,
      ingredientsJson: "[{\"name\":\"Resin\",\"pct\":40}]",
    })
  );
  await assertFails(
    cLab.collection("lab_formula_versions").doc("LAB-1-v1").update({
      lockedTotalCost: 999,
    })
  );
  await assertFails(cLab.collection("lab_formula_versions").doc("LAB-1-v1").delete());
});

test("21. Chemical Lab QC Gate, Price History & Staged Excel Imports Isolation", async () => {
  await seedClusterRbac();
  const LAB_OFFICER_UID = "lab_officer_uid_705";
  const QC_OFFICER_UID = "qc_officer_uid_706";
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("cluster_members").doc(LAB_OFFICER_UID).set({
      uid: LAB_OFFICER_UID,
      userCode: "USR-LAB-5",
      roleKey: "EMPLOYEE",
      branch: "ALL",
      permissions: "المختبر الكيميائي",
      active: true,
    });
    await cRef.collection("cluster_members").doc(QC_OFFICER_UID).set({
      uid: QC_OFFICER_UID,
      userCode: "USR-QC-6",
      roleKey: "EMPLOYEE",
      branch: "ALL",
      permissions: "الجودة,QC_OFFICER",
      active: true,
    });
  });

  const cLab = testEnv.authenticatedContext(LAB_OFFICER_UID).firestore().collection("erp_clusters").doc(CLUSTER);
  const cQc = testEnv.authenticatedContext(QC_OFFICER_UID).firestore().collection("erp_clusters").doc(CLUSTER);
  const cAcc = testEnv.authenticatedContext(ACCOUNTANT_UID).firestore().collection("erp_clusters").doc(CLUSTER);
  const cEmp = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore().collection("erp_clusters").doc(CLUSTER);

  // Lab Officer CAN create PENDING experiment (without secret ingredientsJson)
  await assertSucceeds(
    cLab.collection("lab_experiments").doc("EXP-1").set({
      formulaId: "LAB-1",
      formulaVersion: 1,
      status: "PENDING",
      viscosityMeasured: 92,
      pvcMeasured: 41.2,
    })
  );

  // Lab Officer CANNOT pass/approve their own QC experiment
  await assertFails(
    cLab.collection("lab_experiments").doc("EXP-1").update({
      status: "PASSED",
      decision: "APPROVED",
    })
  );

  // QC Officer CAN approve the QC experiment
  await assertSucceeds(
    cQc.collection("lab_experiments").doc("EXP-1").update({
      status: "PASSED",
      decision: "APPROVED",
    })
  );

  // Secret fields rejected inside lab_experiments
  await assertFails(
    cLab.collection("lab_experiments").doc("EXP-LEAK").set({
      formulaId: "LAB-1",
      status: "PENDING",
      ingredientsJson: "[]",
    })
  );

  // Price history accessible to Accountant & Lab Officer, denied to plain Sales Employee
  await assertSucceeds(
    cAcc.collection("raw_material_price_history").doc("PH-1").set({
      materialId: "RM-TiO2",
      previousPrice: 120,
      newPrice: 135,
      sourceType: "GRN",
    })
  );
  await assertFails(cEmp.collection("raw_material_price_history").doc("PH-1").get());

  // Staged Excel import batches accessible ONLY to Lab Secret readers/editors
  await assertSucceeds(
    cLab.collection("lab_excel_imports").doc("IMP-1").set({
      fileName: "formulations.xlsx",
      workbookSha256: "abc123",
      candidateCount: 3,
      approvalStatus: "NEEDS_REVIEW",
    })
  );
  await assertFails(cEmp.collection("lab_excel_imports").doc("IMP-1").get());
  await assertFails(
    cEmp.collection("lab_excel_imports").doc("IMP-EMP").set({
      fileName: "hack.xlsx",
      candidateCount: 1,
    })
  );
});

test("22. Cluster Owner Self-Recovery when cluster_owner exists without cluster_members doc", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("system_metadata").doc("cluster_owner").set({
      ownerUid: ADMIN_UID,
      userCode: "USR-ADMIN",
    });
  });

  const adminDb = testEnv.authenticatedContext(ADMIN_UID, { email: "admin@pmp.sd", email_verified: true }).firestore();
  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID, { email: "hacker@evil.com", email_verified: true }).firestore();

  // Intruder still cannot claim ADMIN when cluster_owner belongs to ADMIN_UID
  await assertFails(
    intruderDb.collection("erp_clusters").doc(CLUSTER).collection("cluster_members").doc(INTRUDER_UID).set({
      uid: INTRUDER_UID,
      userCode: "USR-HACK",
      roleKey: "ADMIN",
      branch: "ALL",
      active: true,
    })
  );

  // Legitimate owner CAN recover/create cluster_members/{ADMIN_UID}
  await assertSucceeds(
    adminDb.collection("erp_clusters").doc(CLUSTER).collection("cluster_members").doc(ADMIN_UID).set({
      uid: ADMIN_UID,
      userCode: "USR-ADMIN",
      roleKey: "ADMIN",
      branch: "ALL",
      active: true,
    })
  );
});

test("23. Non-Admin Active Members read system_metadata/config safely while writes & user_accounts list remain restricted", async () => {
  await seedClusterRbac();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("system_metadata").doc("cluster_epoch").set({ wipeTimestamp: 1700000000000, wipedBy: "admin" });
    await cRef.collection("config").doc("inventory_weights").set({ defaultWeight: "900", weightsJson: "[]" });
    await cRef.collection("user_accounts").doc("USR-SD-1001").set({
      userCode: "USR-SD-1001",
      username: "sudan_emp",
      linkedUid: EMPLOYEE_SD_UID,
      active: true,
    });
  });

  const empDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID, { email: "emp@pmp.sd", email_verified: true }).firestore();
  const depDb = testEnv.authenticatedContext(DEPUTY_UID, { email: "dep@pmp.sd", email_verified: true }).firestore();
  const cEmp = empDb.collection("erp_clusters").doc(CLUSTER);
  const cDep = depDb.collection("erp_clusters").doc(CLUSTER);

  await assertSucceeds(cEmp.collection("system_metadata").doc("cluster_epoch").get());
  await assertSucceeds(cDep.collection("config").doc("inventory_weights").get());
  await assertFails(cEmp.collection("system_metadata").doc("cluster_epoch").set({ wipeTimestamp: 1 }));
  await assertFails(cDep.collection("config").doc("inventory_weights").set({ defaultWeight: "500" }));
  await assertFails(cEmp.collection("user_accounts").get());
  await assertSucceeds(cEmp.collection("user_accounts").doc("USR-SD-1001").get());
});

test("24. Active Accountant/Employee self-get via remoteServerId, health probes, and financial movement commits", async () => {
  await seedClusterRbac();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("user_accounts").doc("USR-ACC-2001").set({
      userCode: "USR-ACC-2001",
      username: "acc_user",
      phoneOrEmail: "acc@pmp.sd",
      remoteServerId: ACCOUNTANT_UID,
      active: true,
    });
    await cRef.collection("warehouses").doc("WH-1").set({
      id: 1,
      name: "مخزن الخرطوم",
      branch: "فرع السودان (SD-001)",
    });
  });

  const accDb = testEnv.authenticatedContext(ACCOUNTANT_UID, { email: "acc@pmp.sd", email_verified: true }).firestore();
  const cAcc = accDb.collection("erp_clusters").doc(CLUSTER);

  // Accountant can self-get via remoteServerId & probe warehouses/movements without listing user_accounts
  await assertSucceeds(cAcc.collection("user_accounts").doc("USR-ACC-2001").get());
  await assertFails(cAcc.collection("user_accounts").get());
  await assertSucceeds(cAcc.collection("warehouses").get());
  await assertSucceeds(cAcc.collection("movements").get());

  // Accountant can claim idempotency request and commit valid financial movement
  await assertSucceeds(
    cAcc.collection("idempotency_requests").doc("IDEM-MOV-24").set({
      idempotencyKey: "IDEM-MOV-24",
      operationType: "RECEIPT_VOUCHER",
      requestStatus: "PROCESSING",
      resultReference: "",
      errorDetails: "",
    })
  );
  await assertSucceeds(
    cAcc.collection("movements").doc("MOV-24-UNIQUE").set({
      id: 1763544000000,
      txDate: "2026-11-19",
      cashbox: "خزنة السودان",
      currency: "SDG",
      direction: "وارد",
      amount: 12500,
      description: "سند قبض معتمد",
      reference: "REC-24",
      branch: "فرع السودان (SD-001)",
      createdBy: "acc_user",
    })
  );
});

test("25. Anonymous Auth Isolation: anonymous sign_in_provider is denied user_accounts list/get, cluster_members ADMIN self-creation, and ERP read/create", async () => {
  await seedClusterRbac();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("user_accounts").doc("USR-SD-1001").set({
      userCode: "USR-SD-1001",
      username: "sudan_emp",
      linkedUid: EMPLOYEE_SD_UID,
      active: true,
    });
    await cRef.collection("invoices").doc("INV-25").set({
      invoiceNo: "INV-25",
      customerName: "عميل الخرطوم",
      branch: "فرع السودان (SD-001)",
      total: 1000,
      subtotal: 1000,
    });
    await cRef.collection("movements").doc("MOV-25").set({
      id: 2501,
      cashbox: "خزنة السودان",
      currency: "SDG",
      direction: "وارد",
      amount: 1000,
      branch: "فرع السودان (SD-001)",
    });
  });

  const ANON_UID = "anon_session_901";
  const anonDb = testEnv
    .authenticatedContext(ANON_UID, { firebase: { sign_in_provider: "anonymous" } })
    .firestore();
  const cAnon = anonDb.collection("erp_clusters").doc(CLUSTER);

  // Anonymous auth cannot list or get user_accounts
  await assertFails(cAnon.collection("user_accounts").get());
  await assertFails(cAnon.collection("user_accounts").doc("USR-SD-1001").get());

  // Anonymous auth cannot self-create ADMIN (or EMPLOYEE) in cluster_members even with active userCode
  await assertFails(
    cAnon.collection("cluster_members").doc(ANON_UID).set({
      uid: ANON_UID,
      userCode: "USR-SD-1001",
      roleKey: "ADMIN",
      branch: "ALL",
      active: true,
    })
  );
  await assertFails(
    cAnon.collection("cluster_members").doc(ANON_UID).set({
      uid: ANON_UID,
      userCode: "USR-SD-1001",
      roleKey: "EMPLOYEE",
      branch: "فرع السودان (SD-001)",
      active: true,
    })
  );

  // Anonymous auth has zero read/create access to operational ERP collections or linked_accounts
  await assertFails(cAnon.collection("invoices").get());
  await assertFails(cAnon.collection("invoices").doc("INV-25").get());
  await assertFails(
    cAnon.collection("invoices").doc("INV-ANON-NEW").set({
      customerName: "عميل مجهول",
      total: 500,
      subtotal: 500,
    })
  );
  await assertFails(cAnon.collection("movements").get());
  await assertFails(cAnon.collection("movements").doc("MOV-25").get());
  await assertFails(
    cAnon.collection("movements").doc("MOV-ANON-NEW").set({
      cashbox: "خزنة السودان",
      currency: "SDG",
      direction: "وارد",
      amount: 500,
    })
  );
  await assertFails(
    anonDb.collection("linked_accounts").doc("USR-ANON-1").set(
      validLinkedAccountPayload({ userCode: "USR-ANON-1", ownerId: ANON_UID })
    )
  );
});

test("26. Non-Verified User & Privilege Boundaries: blocks cluster_members self-promotion via active userCode, cross-user user_accounts read/list, and Manager admin-token escalation", async () => {
  await seedClusterRbac();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("user_accounts").doc("USR-SD-1001").set({
      userCode: "USR-SD-1001",
      username: "sudan_emp",
      phoneOrEmail: "emp@pmp.sd",
      linkedUid: EMPLOYEE_SD_UID,
      branch: "فرع السودان (SD-001)",
      active: true,
    });
    await cRef.collection("user_accounts").doc("USR-ACC-1").set({
      userCode: "USR-ACC-1",
      username: "acc_omar",
      phoneOrEmail: "acc@pmp.sd",
      linkedUid: ACCOUNTANT_UID,
      branch: "ALL",
      active: true,
    });
  });

  const UNVERIFIED_UID = "unverified_user_888";
  const SPOOF_BOOT_UID = "unverified_boot_889";
  const unverifiedDb = testEnv
    .authenticatedContext(UNVERIFIED_UID, { email: "new.emp@pmp.sd", email_verified: false })
    .firestore();
  const unverifiedBootDb = testEnv
    .authenticatedContext(SPOOF_BOOT_UID, { email: "oaw7ab@gmail.com", email_verified: false })
    .firestore();
  const empDb = testEnv
    .authenticatedContext(EMPLOYEE_SD_UID, { email: "emp@pmp.sd", email_verified: true })
    .firestore();
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();

  const cUnv = unverifiedDb.collection("erp_clusters").doc(CLUSTER);
  const cSpoof = unverifiedBootDb.collection("erp_clusters").doc(CLUSTER);
  const cEmp = empDb.collection("erp_clusters").doc(CLUSTER);
  const cMgr = managerDb.collection("erp_clusters").doc(CLUSTER);

  // Regular non-verified user CANNOT self-promote in cluster_members using an existing active userCode
  await assertFails(
    cUnv.collection("cluster_members").doc(UNVERIFIED_UID).set({
      uid: UNVERIFIED_UID,
      userCode: "USR-SD-1001",
      roleKey: "ADMIN",
      branch: "ALL",
      active: true,
    })
  );
  await assertFails(
    cUnv.collection("cluster_members").doc(UNVERIFIED_UID).set({
      uid: UNVERIFIED_UID,
      userCode: "USR-SD-1001",
      roleKey: "EMPLOYEE",
      branch: "فرع السودان (SD-001)",
      active: true,
    })
  );

  // Unverified bootstrap email CANNOT claim ADMIN or overwrite cluster_owner
  await assertFails(
    cSpoof.collection("cluster_members").doc(SPOOF_BOOT_UID).set({
      uid: SPOOF_BOOT_UID,
      userCode: "USR-ADMIN",
      roleKey: "ADMIN",
      branch: "ALL",
      active: true,
    })
  );
  await assertFails(
    cSpoof.collection("system_metadata").doc("cluster_owner").set({
      ownerUid: SPOOF_BOOT_UID,
    })
  );

  // Non-verified user and active employee CANNOT list user_accounts or read another user's doc
  await assertFails(cUnv.collection("user_accounts").doc("USR-SD-1001").get());
  await assertFails(cUnv.collection("user_accounts").get());
  await assertFails(cEmp.collection("user_accounts").doc("USR-ACC-1").get());
  await assertFails(cEmp.collection("user_accounts").get());

  // Manager CANNOT grant USER_MANAGEMENT / ADMIN_SETTINGS tokens via user_accounts create or update
  await assertFails(
    cMgr.collection("user_accounts").doc("USR-MGR-ESC").set({
      userCode: "USR-MGR-ESC",
      username: "mgr_esc",
      role: "موظف مبيعات",
      branch: "فرع السودان (SD-001)",
      permissions: "إدارة المستخدمين,الإعدادات الإدارية",
      active: true,
    })
  );
  await assertFails(
    cMgr.collection("user_accounts").doc("USR-SD-1001").update({
      permissions: "USER_MANAGEMENT,ADMIN_SETTINGS",
    })
  );
});

test("27. Financial Ledger Immutability: movements, transfers, invoice_payments deny delete & field tampering while allowing controlled reversal; invoices protect totals & block non-Admin delete", async () => {
  await seedClusterRbac();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const cRef = ctx.firestore().collection("erp_clusters").doc(CLUSTER);
    await cRef.collection("movements").doc("MOV-IMM-1").set({
      id: 2701,
      cashbox: "خزنة السودان",
      currency: "SDG",
      direction: "وارد",
      amount: 5000,
      status: "active",
      branch: "فرع السودان (SD-001)",
    });
    await cRef.collection("transfers").doc("TRF-IMM-1").set({
      id: 2702,
      sourceCashbox: "خزنة السودان",
      destinationCashbox: "خزنة مصر",
      sourceCurrency: "SDG",
      destinationCurrency: "EGP",
      sourceAmount: 10000,
      destinationAmount: 500,
      rate: 0.05,
      status: "active",
      branch: "فرع السودان (SD-001)",
    });
    await cRef.collection("invoice_payments").doc("PAY-IMM-1").set({
      id: 2703,
      invoiceId: 2704,
      cashbox: "خزنة السودان",
      currency: "SDG",
      amount: 2500,
      status: "active",
      branch: "فرع السودان (SD-001)",
    });
    await cRef.collection("invoices").doc("INV-IMM-1").set({
      id: 2704,
      invoiceNo: "INV-IMM-1",
      customerName: "عميل الثبات المالي",
      total: 5000,
      subtotal: 5000,
      paidAmount: 2500,
      currency: "SDG",
      status: "سداد جزئي",
      branch: "فرع السودان (SD-001)",
    });
  });

  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();
  const accDb = testEnv.authenticatedContext(ACCOUNTANT_UID).firestore();
  const empDb = testEnv.authenticatedContext(EMPLOYEE_SD_UID).firestore();

  const cAdm = adminDb.collection("erp_clusters").doc(CLUSTER);
  const cMgr = managerDb.collection("erp_clusters").doc(CLUSTER);
  const cAcc = accDb.collection("erp_clusters").doc(CLUSTER);
  const cEmp = empDb.collection("erp_clusters").doc(CLUSTER);

  // Direct delete denied even for Admin & Manager on movements, transfers, invoice_payments
  await assertFails(cAdm.collection("movements").doc("MOV-IMM-1").delete());
  await assertFails(cMgr.collection("movements").doc("MOV-IMM-1").delete());
  await assertFails(cAdm.collection("transfers").doc("TRF-IMM-1").delete());
  await assertFails(cMgr.collection("transfers").doc("TRF-IMM-1").delete());
  await assertFails(cAdm.collection("invoice_payments").doc("PAY-IMM-1").delete());
  await assertFails(cMgr.collection("invoice_payments").doc("PAY-IMM-1").delete());

  // Mutating amount/currency/direction/rate denied even for Admin & Manager
  await assertFails(cAdm.collection("movements").doc("MOV-IMM-1").update({ amount: 9999 }));
  await assertFails(cMgr.collection("movements").doc("MOV-IMM-1").update({ currency: "USD" }));
  await assertFails(cAcc.collection("movements").doc("MOV-IMM-1").update({ direction: "منصرف" }));
  await assertFails(cAdm.collection("movements").doc("MOV-IMM-1").update({ status: "reversed", amount: 1 }));
  await assertFails(cMgr.collection("transfers").doc("TRF-IMM-1").update({ sourceAmount: 1, rate: 1 }));
  await assertFails(cAcc.collection("invoice_payments").doc("PAY-IMM-1").update({ amount: 1 }));

  // Plain employee without treasury permission cannot reverse movements or payments
  await assertFails(
    cEmp.collection("movements").doc("MOV-IMM-1").update({
      status: "reversed",
      reversalReason: "محاولة غير مصرحة",
    })
  );
  await assertFails(
    cEmp.collection("invoice_payments").doc("PAY-IMM-1").update({
      status: "reversed",
      reversalReason: "محاولة غير مصرحة",
    })
  );

  // Controlled one-time reversal succeeds on movements, transfers, invoice_payments; second reversal fails
  await assertSucceeds(
    cAcc.collection("movements").doc("MOV-IMM-1").update({
      status: "reversed",
      reversedByRef: "MOV-REV-2701",
      reversalReason: "تصحيح قيد معتمد",
      updatedAt: Date.now(),
    })
  );
  await assertFails(
    cAcc.collection("movements").doc("MOV-IMM-1").update({
      status: "reversed",
      reversalReason: "عكس مكرر",
    })
  );
  await assertSucceeds(
    cMgr.collection("transfers").doc("TRF-IMM-1").update({
      status: "reversed",
      reversalReason: "إلغاء تحويل معتمد",
      updatedAt: Date.now(),
    })
  );
  await assertSucceeds(
    cAcc.collection("invoice_payments").doc("PAY-IMM-1").update({
      status: "reversed",
      reversalReason: "إلغاء فاتورة INV-IMM-1",
      updatedAt: Date.now(),
    })
  );

  // Invoices block non-Admin deletion & total tampering while allowing payment summary & soft-cancellation
  await assertFails(cMgr.collection("invoices").doc("INV-IMM-1").delete());
  await assertFails(cMgr.collection("invoices").doc("INV-IMM-1").update({ total: 100 }));
  await assertSucceeds(
    cEmp.collection("invoices").doc("INV-IMM-1").update({
      paidAmount: 5000,
      status: "مدفوعة",
      updatedAt: Date.now(),
    })
  );
  await assertSucceeds(
    cMgr.collection("invoices").doc("INV-IMM-1").update({
      status: "ملغاة",
      cancelReason: "إلغاء معتمد",
      updatedAt: Date.now(),
    })
  );
  await assertFails(
    cMgr.collection("invoices").doc("INV-IMM-1").update({
      status: "مدفوعة",
    })
  );
});




