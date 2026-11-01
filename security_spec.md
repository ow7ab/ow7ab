# Firestore Security Specification (`security_spec.md`)

## 1. Data Invariants
1. **Authentication Prerequisite**: Unauthenticated callers (`request.auth == null`) cannot read or write any document in `/linked_accounts/{accountCode}`.
2. **Path & Document ID Integrity**: Every `accountCode` document ID must match `^[a-zA-Z0-9_\-]+$`, be at most 128 characters, and match `incoming().userCode`.
3. **Ownership & Multi-User Isolation**:
   - Only the creating manager (`ownerId == request.auth.uid`) or the explicitly linked employee (`linkedUid == request.auth.uid`) can `get` or `list` a `LinkedAccount` document.
   - Unfiltered `list` queries or cross-user reads by unauthorized third-party UIDs are rejected with `PERMISSION_DENIED`.
4. **Tiered Identity & Anti-Escalation**:
   - **Tier 1 (Manager / `ownerId`)**: Can update `fullName`, `username`, `role`, `branch`, `permissions`, `linkedUid`, `serverSyncToken`, `active`, and `updatedAt`.
   - **Tier 2 (Employee / `linkedUid`)**: Can only update `serverSyncToken` and `updatedAt` (cannot escalate their own `role`, `permissions`, or `active` status).
5. **Immutable Audit Fields**: `userCode`, `ownerId`, `clusterCode`, and `createdAt` cannot be modified after creation.
6. **Temporal Integrity**: `createdAt` and `updatedAt` must be Firestore `timestamp` values (`<= request.time`).

## 2. The "Dirty Dozen" Payloads
1. **Unauthenticated Read**: `auth = null` attempting `get /linked_accounts/USR-SD-1001` -> Reject.
2. **Unauthenticated Create**: `auth = null` attempting `create /linked_accounts/USR-SD-1001` -> Reject.
3. **Identity Spoofing on Create**: `auth.uid = "alice"` attempting `create` with `ownerId: "bob"` -> Reject.
4. **Path/Payload ID Mismatch**: `create /linked_accounts/USR-SD-1001` with `userCode: "USR-SD-9999"` -> Reject.
5. **Cross-User Unauthorized Read**: `auth.uid = "charlie"` attempting `get` on doc where `ownerId == "alice"` and `linkedUid == "bob"` -> Reject.
6. **Unfiltered List Query Scraping**: `auth.uid = "alice"` calling `db.collection("linked_accounts").get()` without `.where("ownerId", "==", "alice")` or `.where("linkedUid", "==", "alice")` -> Reject.
7. **Shadow Update (Ghost Field Injection)**: `auth.uid = "alice"` updating doc with extra field `isAdmin: true` -> Reject via `hasOnly()`.
8. **Privilege Escalation by Linked Employee**: `auth.uid = "bob"` (`linkedUid`) attempting to update `permissions` or `role` -> Reject via Tier 2 `affectedKeys().hasOnly(['serverSyncToken', 'updatedAt'])`.
9. **Immutable Field Mutation**: `auth.uid = "alice"` attempting to change `ownerId` or `createdAt` on update -> Reject.
10. **Value Poisoning (Oversized String)**: `auth.uid = "alice"` attempting to write a 10,000-character string into `permissions` -> Reject via `size() <= 300`.
11. **Invalid Timestamp Type**: `auth.uid = "alice"` passing epoch integer `1700000000000` for `createdAt` instead of `FieldValue.serverTimestamp()` -> Reject via `is timestamp`.
12. **Unauthorized Delete by Employee**: `auth.uid = "bob"` (`linkedUid`, not `ownerId`) attempting `delete` -> Reject.
