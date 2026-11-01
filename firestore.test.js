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
    clusterCode: "PMP-ERP-V16-CLUSTER",
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
      .doc("PMP-ERP-V16-CLUSTER")
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
      .doc("PMP-ERP-V16-CLUSTER")
      .collection("user_accounts")
      .get()
  );
});

