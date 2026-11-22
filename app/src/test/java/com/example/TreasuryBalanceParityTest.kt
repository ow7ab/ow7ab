package com.example

import com.example.data.local.MovementEntity
import com.example.util.TreasuryBalanceNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreasuryBalanceParityTest {

    @Test
    fun arabicCashboxKeysNormalizeIdenticallyToWeb() {
        val k1 = TreasuryBalanceNormalizer.normalizeArabicKey("خزنة   السودان")
        val k2 = TreasuryBalanceNormalizer.normalizeArabicKey("خزنه السودان")
        assertEquals(k1, k2)

        val k3 = TreasuryBalanceNormalizer.normalizeArabicKey("إدارة   المبيعات")
        val k4 = TreasuryBalanceNormalizer.normalizeArabicKey("اداره المبيعات")
        assertEquals(k3, k4)
    }

    @Test
    fun directionAliasesMatchWebInflowOutflowRules() {
        assertTrue(TreasuryBalanceNormalizer.isMovementInflow("وارد"))
        assertTrue(TreasuryBalanceNormalizer.isMovementInflow("IN"))
        assertTrue(TreasuryBalanceNormalizer.isMovementInflow("INCOME"))
        assertTrue(TreasuryBalanceNormalizer.isMovementInflow("قبض"))
        assertTrue(TreasuryBalanceNormalizer.isMovementInflow("ايداع"))
        assertTrue(TreasuryBalanceNormalizer.isMovementInflow("", "IN"))
        assertFalse(TreasuryBalanceNormalizer.isMovementInflow("منصرف"))
        assertFalse(TreasuryBalanceNormalizer.isMovementInflow("OUT"))

        assertEquals(500.0, TreasuryBalanceNormalizer.signedAmount("IN", -500.0), 0.001)
        assertEquals(-500.0, TreasuryBalanceNormalizer.signedAmount("منصرف", -500.0), 0.001)
    }

    @Test
    fun transferLegsSharingReferenceAreNotCollapsed() {
        val outLeg = MovementEntity(
            id = 1,
            txDate = "2026-11-20",
            cashbox = "خزنة السودان",
            currency = "SDG",
            direction = "منصرف",
            amount = 10000.0,
            description = "تحويل صادر",
            reference = "TRF-100",
            cloudDocId = "MOV-TRF-100-OUT"
        )
        val inLeg = MovementEntity(
            id = 2,
            txDate = "2026-11-20",
            cashbox = "خزنة مصر",
            currency = "EGP",
            direction = "وارد",
            amount = 200.0,
            description = "تحويل وارد",
            reference = "TRF-100",
            cloudDocId = "MOV-TRF-100-IN"
        )

        val keyOut = TreasuryBalanceNormalizer.movementDeduplicationKey(outLeg)
        val keyIn = TreasuryBalanceNormalizer.movementDeduplicationKey(inLeg)
        assertNotEquals(keyOut, keyIn)

        val deduped = TreasuryBalanceNormalizer.deduplicateMovements(listOf(outLeg, inLeg))
        assertEquals(2, deduped.size)
    }

    @Test
    fun localDemoSeedMovementsAreDetectedAccurately() {
        val demoSeed = MovementEntity(
            id = 1,
            txDate = "2026-10-01",
            cashbox = "خزنة السودان",
            currency = "SDG",
            direction = "وارد",
            amount = 45000000.0,
            description = "رصيد افتتاحي",
            reference = "OP-SDG-01",
            cloudDocId = "",
            syncUuid = ""
        )
        val syncedRealMov = demoSeed.copy(cloudDocId = "MOV-REAL-01")
        assertTrue(TreasuryBalanceNormalizer.isLocalDemoSeedMovement(demoSeed))
        assertFalse(TreasuryBalanceNormalizer.isLocalDemoSeedMovement(syncedRealMov))
    }
}
