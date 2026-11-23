package com.example

import com.example.data.local.MovementEntity
import com.example.util.AuthErrorHandler
import com.example.util.PasswordSecurityUtil
import com.example.util.TreasuryBalanceNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCredentialsFixTest {

    @Test
    fun arabicIndicDigitsAndBiDiMarksVerifyAgainstStoredPassword() {
        val pbkdf2Hash = PasswordSecurityUtil.hashPassword("1234")
        val legacyHash = PasswordSecurityUtil.legacySha256Hash("1234")

        // Arabic-Indic numerals with surrounding BiDi marks & whitespace
        assertTrue(PasswordSecurityUtil.verifyPassword(" \u200F١٢٣٤\u200E ", pbkdf2Hash))
        assertTrue(PasswordSecurityUtil.verifyPassword("١٢٣٤", legacyHash))
        assertTrue(PasswordSecurityUtil.verifyPassword("١٢٣٤", "1234"))

        // Persian digits
        assertTrue(PasswordSecurityUtil.verifyPassword("۱۲۳۴", pbkdf2Hash))

        // Wrong PIN still strictly fails
        assertFalse(PasswordSecurityUtil.verifyPassword("١٢٣٥", pbkdf2Hash))
    }

    @Test
    fun identitiesMatchHandlesCaseWhitespaceArabicDigitsAndPhones() {
        assertTrue(PasswordSecurityUtil.identitiesMatch("  ADMIN  ", "admin"))
        assertTrue(PasswordSecurityUtil.identitiesMatch("usr-pmp-٠٠١", "USR-PMP-001"))
        assertTrue(PasswordSecurityUtil.identitiesMatch("\u200Fadmin@pmp-paints.com\uFEFF", "admin@pmp-paints.com"))
        assertTrue(PasswordSecurityUtil.identitiesMatch("+249 912-345-678", "0912345678"))
        assertTrue(PasswordSecurityUtil.identitiesMatch("٠٠٢٤٩٩١٢٣٤٥٦٧٨", "0912345678"))
        assertFalse(PasswordSecurityUtil.identitiesMatch("sudan_acc", "admin"))
    }

    @Test
    fun authErrorHandlerTranslatesInvalidApiKeyErrorToArabic() {
        val msg = AuthErrorHandler.getReadableErrorMessage(
            Exception("API key not valid. Please pass a valid API key.")
        )
        assertTrue("Expected Arabic API key message, got: $msg", msg.contains("Firebase API"))
        assertFalse("Should not leak raw English API key error", msg.contains("Please pass a valid API key"))
    }

    @Test
    fun localDemoSeedMovementsAreExcludedFromCloudPush() {
        val demoSeed = MovementEntity(
            id = 1L,
            txDate = "2026-10-20",
            cashbox = "خزنة السودان",
            currency = "SDG",
            direction = "وارد",
            amount = 45000000.0,
            description = "رصيد افتتاحي الخزنة",
            reference = "OP-SDG-01",
            cloudDocId = "",
            syncUuid = ""
        )
        val realUserMovement = MovementEntity(
            id = 2L,
            txDate = "2026-11-20",
            cashbox = "خزنة السودان",
            currency = "SDG",
            direction = "وارد",
            amount = 500000.0,
            description = "تحصيل مبيعات",
            reference = "REC-1001",
            cloudDocId = "MOV-1700000000-abcd1234",
            syncUuid = "uuid-real-1"
        )
        assertTrue(TreasuryBalanceNormalizer.isLocalDemoSeedMovement(demoSeed))
        assertFalse(TreasuryBalanceNormalizer.isLocalDemoSeedMovement(realUserMovement))
        assertEquals("SDG", TreasuryBalanceNormalizer.resolveCanonicalCurrency("سوداني", "خزنة السودان"))
    }
}
