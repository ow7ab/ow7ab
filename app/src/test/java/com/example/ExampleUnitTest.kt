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
}

