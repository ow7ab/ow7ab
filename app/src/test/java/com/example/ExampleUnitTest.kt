package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testPasswordHashingAndVerification() {
    val rawPin = "1234"
    val hashed = com.example.data.repository.PmpRepository.hashSecret(rawPin)
    assertNotEquals(rawPin, hashed)
    assertTrue(hashed.length >= 32)

    // Verification check as done in checkPendingAccountApprovalAndLogin
    val enteredPin = "1234"
    val enteredHashed = com.example.data.repository.PmpRepository.hashSecret(enteredPin)
    val isValid = enteredPin == rawPin || enteredHashed == hashed
    assertTrue(isValid)

    val wrongPin = "0000"
    val wrongHashed = com.example.data.repository.PmpRepository.hashSecret(wrongPin)
    val isWrongValid = wrongPin == hashed || wrongHashed == hashed
    assertFalse(isWrongValid)
  }

  @Test
  fun testDeletedRecordsRegistry() {
    val registry = com.example.data.local.DeletedRecordsRegistry

    // 1. Test Movement Tombstone Registration
    registry.registerDeleted("movements", 101L, "MOV-101", "المدير")
    assertTrue(registry.isDeleted("movements", 101L))
    assertTrue(registry.isDeleted("movements", "MOV-101"))
    assertFalse(registry.isDeleted("movements", 999L))

    // 2. Test Customer Tombstone Registration
    registry.registerDeleted("customers", 42L, "CUST-42", "المدير")
    assertTrue(registry.isDeleted("customers", 42L))
    assertTrue(registry.isDeleted("customers", "CUST-42"))
    assertFalse(registry.isDeleted("customers", 100L))

    // 3. Test Invoices Tombstone Registration
    registry.registerDeleted("invoices", 500L, "INV-500", "المدير")
    assertTrue(registry.isDeleted("invoices", 500L))
    assertTrue(registry.isDeleted("invoices", "INV-500"))

    // 4. Test Cluster Epoch Wipe Protection
    val wipeTime = System.currentTimeMillis()
    registry.setClusterEpoch(wipeTime, "مدير النظام")
    assertEquals(wipeTime, registry.getClusterEpoch())
    assertTrue(registry.isPreEpoch(wipeTime - 10_000L))
    assertFalse(registry.isPreEpoch(wipeTime + 10_000L))

    // 5. Test Remote Tombstones Merge
    val remoteTombs = listOf(
        mapOf("collection" to "transfers", "entityId" to "77", "docId" to "TRF-77", "deletedAt" to System.currentTimeMillis())
    )
    registry.mergeRemoteTombstones(remoteTombs)
    assertTrue(registry.isDeleted("transfers", 77L))
    assertTrue(registry.isDeleted("transfers", "TRF-77"))
  }
}

