package com.example.util

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * Enterprise cryptographic utility for PMP PAINTS ERP:
 * 1. Modern password hashing via PBKDF2WithHmacSHA256 (65,536 iterations, 128-bit SecureRandom salt, 256-bit key).
 * 2. Constant-time verification with transparent migration from legacy SHA-256 and plaintext PINs.
 * 3. Cryptographic HMAC-SHA256 credential & device binding fingerprints.
 * 4. RFC 6238 Time-Based One-Time Password (TOTP) generation and verification for optional 2FA.
 * 5. SecureRandom OTP and single-use backup recovery code generation.
 */
object PasswordSecurityUtil {

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_PREFIX = "pbkdf2\$sha256"
    const val DEFAULT_ITERATIONS = 65536
    private const val SALT_BYTES = 16
    private const val KEY_LENGTH_BITS = 256

    private const val LEGACY_SALT_PREFIX = "PMP_SECURE_V2:"
    private const val LEGACY_SALT_SUFFIX = ":PMP_HASH_SALT"
    private const val HMAC_BINDING_SECRET = "PMP_ERP_DEVICE_BIOMETRIC_HMAC_KEY_V4"

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val RECOVERY_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    private val secureRandom = SecureRandom()

    /**
     * Hashes a raw password or PIN using PBKDF2WithHmacSHA256 and a random 16-byte salt.
     * Format: pbkdf2$sha256$<iterations>$<hexSalt>$<hexHash>
     */
    fun hashPassword(rawPassword: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val clean = rawPassword.trim()
        require(clean.isNotEmpty()) { "Password cannot be empty" }
        if (isModernPbkdf2Hash(clean)) return clean

        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val derived = derivePbkdf2(clean.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return "$PBKDF2_PREFIX\$$iterations\$${bytesToHex(salt)}\$${bytesToHex(derived)}"
    }

    /**
     * Computes the legacy deterministic SHA-256 hash used by earlier PMP versions.
     * Preserved strictly for verifying and migrating pre-existing accounts.
     */
    fun legacySha256Hash(secret: String): String {
        val salted = "$LEGACY_SALT_PREFIX${secret.trim()}$LEGACY_SALT_SUFFIX"
        val digest = MessageDigest.getInstance("SHA-256").digest(salted.toByteArray(Charsets.UTF_8))
        return bytesToHex(digest)
    }

    /**
     * Returns true if the stored secret is already hashed with modern PBKDF2-HMAC-SHA256.
     */
    fun isModernPbkdf2Hash(storedSecret: String): Boolean {
        return storedSecret.trim().startsWith("$PBKDF2_PREFIX\$")
    }

    fun isPbkdf2Hash(storedSecret: String): Boolean = isModernPbkdf2Hash(storedSecret)

    /**
     * Returns true if the stored secret is either a modern PBKDF2 hash or a legacy 64-char hex SHA-256 hash.
     */
    fun isHashedSecret(storedSecret: String): Boolean {
        val clean = storedSecret.trim()
        if (isModernPbkdf2Hash(clean)) return true
        return clean.length == 64 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * Returns true if the stored password is in plaintext or legacy SHA-256 format and should be upgraded to PBKDF2.
     */
    fun needsUpgradeToPbkdf2(storedSecret: String): Boolean {
        return !isModernPbkdf2Hash(storedSecret.trim())
    }

    /**
     * Verifies a candidate password or PIN against a stored secret (PBKDF2, legacy SHA-256, or legacy plaintext)
     * using constant-time byte comparisons to prevent timing side-channel attacks.
     */
    fun verifyPassword(candidateRaw: String, storedSecret: String): Boolean {
        val cleanCandidate = candidateRaw.trim()
        val cleanStored = storedSecret.trim()
        if (cleanCandidate.isEmpty() || cleanStored.isEmpty()) return false

        // 1. Modern PBKDF2-HMAC-SHA256 format: pbkdf2$sha256$<iterations>$<hexSalt>$<hexHash>
        if (isModernPbkdf2Hash(cleanStored)) {
            val parts = cleanStored.split("$")
            if (parts.size != 5) return false
            val iterations = parts[2].toIntOrNull() ?: return false
            val salt = hexToBytes(parts[3]) ?: return false
            val expectedHash = hexToBytes(parts[4]) ?: return false
            if (salt.isEmpty() || expectedHash.isEmpty()) return false

            val actualHash = derivePbkdf2(cleanCandidate.toCharArray(), salt, iterations, expectedHash.size * 8)
            return MessageDigest.isEqual(actualHash, expectedHash)
        }

        // 2. If candidate itself was passed as an identical hash (e.g., internal session sync check)
        if (isHashedSecret(cleanCandidate) && constantTimeEquals(cleanCandidate.lowercase(Locale.ROOT), cleanStored.lowercase(Locale.ROOT))) {
            return true
        }

        // 3. Legacy 64-char SHA-256 hex format
        val candidateLegacyHash = legacySha256Hash(cleanCandidate)
        if (constantTimeEquals(candidateLegacyHash.lowercase(Locale.ROOT), cleanStored.lowercase(Locale.ROOT))) {
            return true
        }

        // 4. Legacy plaintext PIN fallback (for initial seeded accounts prior to first login migration)
        return constantTimeEquals(cleanCandidate, cleanStored)
    }

    /**
     * Evaluates password strength and policy compliance.
     */
    data class PasswordStrengthResult(
        val isValid: Boolean,
        val score: Int, // 0..4
        val labelAr: String,
        val errorMessageAr: String? = null
    )

    fun evaluatePasswordStrength(password: String, enforceStrongPolicy: Boolean = false): PasswordStrengthResult {
        val clean = password.trim()
        if (clean.length < 4) {
            return PasswordStrengthResult(
                isValid = false,
                score = 0,
                labelAr = "ضعيفة جداً",
                errorMessageAr = "كلمة المرور أو رمز الدخول يجب أن يتكون من 4 خانات على الأقل"
            )
        }
        var score = 1
        if (clean.length >= 8) score++
        if (clean.any { it.isLetter() } && clean.any { it.isDigit() }) score++
        if (clean.any { !it.isLetterOrDigit() } || (clean.any { it.isUpperCase() } && clean.any { it.isLowerCase() })) score++

        val weakDefaults = setOf("1234", "0000", "1111", "2222", "3333", "123456", "password", "admin")
        if (clean.lowercase(Locale.ROOT) in weakDefaults) {
            score = 1
            if (enforceStrongPolicy) {
                return PasswordStrengthResult(
                    isValid = false,
                    score = 1,
                    labelAr = "ضعيفة (شائعة)",
                    errorMessageAr = "يرجى اختيار كلمة مرور قوية وغير افتراضية لحماية الحساب"
                )
            }
        }

        val label = when (score) {
            1 -> "مقبولة (أساسية)"
            2 -> "متوسطة"
            3 -> "قوية"
            else -> "قوية جداً"
        }
        return PasswordStrengthResult(isValid = true, score = score, labelAr = label)
    }

    /**
     * Computes a deterministic HMAC-SHA256 fingerprint binding a user's identity, current password hash,
     * serverSyncToken, and hardware deviceId. Used to detect password/session rotation and prevent
     * cross-account biometric spoofing.
     */
    fun computeAccountDeviceFingerprint(
        userCode: String,
        username: String,
        storedPasswordSecret: String,
        serverSyncToken: String,
        deviceId: String
    ): String {
        val normalizedSecret = if (isHashedSecret(storedPasswordSecret)) {
            storedPasswordSecret.trim().lowercase(Locale.ROOT)
        } else {
            legacySha256Hash(storedPasswordSecret.trim()).lowercase(Locale.ROOT)
        }
        // For PBKDF2 hashes with random salts, bind to the stable hash tail so legitimate re-verifications match
        val secretAnchor = normalizedSecret.takeLast(32)
        val payload = listOf(
            userCode.trim().uppercase(Locale.ROOT),
            username.trim().lowercase(Locale.ROOT),
            secretAnchor,
            serverSyncToken.trim(),
            deviceId.trim().uppercase(Locale.ROOT)
        ).joinToString("|")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_BINDING_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return bytesToHex(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Generates a cryptographically random 6-digit numeric OTP code via SecureRandom.
     */
    fun generateSecureSixDigitOtp(): String {
        val code = 100000 + secureRandom.nextInt(900000)
        return String.format(Locale.US, "%06d", code)
    }

    /**
     * Generates a 160-bit Base32 secret for RFC 6238 TOTP (2FA).
     */
    fun generateTotpSecretBase32(byteLength: Int = 20): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(BASE32_ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(BASE32_ALPHABET[index])
        }
        return sb.toString()
    }

    /**
     * Generates a 6-digit RFC 6238 TOTP code for the given Base32 secret and timestamp.
     */
    fun generateTotpCode(
        secretBase32: String,
        timeMillis: Long = System.currentTimeMillis(),
        timeStepSeconds: Long = 30L
    ): String {
        val keyBytes = decodeBase32(secretBase32)
        if (keyBytes.isEmpty()) return "000000"
        val timeCounter = (timeMillis / 1000L) / timeStepSeconds
        val counterBytes = ByteBuffer.allocate(8).putLong(timeCounter).array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)
        val offset = (hash[hash.size - 1] and 0x0F).toInt()
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val otp = binary % 1_000_000
        return String.format(Locale.US, "%06d", otp)
    }

    /**
     * Verifies a 6-digit TOTP code allowing +/- 1 time step (30s) for clock drift.
     */
    fun verifyTotpCode(
        secretBase32: String,
        enteredCode: String,
        timeMillis: Long = System.currentTimeMillis(),
        windowSteps: Int = 1
    ): Boolean {
        val cleanCode = enteredCode.trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) return false
        if (secretBase32.isBlank()) return false

        for (stepOffset in -windowSteps..windowSteps) {
            val candidateTime = timeMillis + (stepOffset * 30_000L)
            val expected = generateTotpCode(secretBase32, candidateTime)
            if (constantTimeEquals(cleanCode, expected)) {
                return true
            }
        }
        return false
    }

    /**
     * Generates N single-use 8-character backup recovery codes formatted as XXXX-XXXX.
     */
    fun generateBackupRecoveryCodes(count: Int = 8): List<String> {
        return List(count) {
            val part1 = (1..4).map { RECOVERY_ALPHABET[secureRandom.nextInt(RECOVERY_ALPHABET.length)] }.joinToString("")
            val part2 = (1..4).map { RECOVERY_ALPHABET[secureRandom.nextInt(RECOVERY_ALPHABET.length)] }.joinToString("")
            "$part1-$part2"
        }
    }

    fun normalizeRecoveryCode(code: String): String {
        return code.trim().uppercase(Locale.ROOT).replace("-", "").replace(" ", "")
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    private fun derivePbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyBits)
        return try {
            val skf = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            skf.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decodeBase32(base32: String): ByteArray {
        val clean = base32.trim().uppercase(Locale.ROOT).replace(" ", "").replace("=", "")
        if (clean.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val val5 = BASE32_ALPHABET.indexOf(ch)
            if (val5 < 0) continue
            buffer = (buffer shl 5) or val5
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
