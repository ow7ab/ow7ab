package com.example.util

import com.example.data.local.MovementEntity
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Unified single-source-of-truth normalization & deduplication logic for Treasury balances,
 * strictly mirroring `web/src/services/ErpCalculations.ts` so Android and Web always compute
 * identical cashbox and currency totals from the same Firestore `movements` collection.
 */
object TreasuryBalanceNormalizer {

    private val DEMO_SEED_REFERENCES = setOf("OP-SDG-01", "OP-EGP-01", "OP-USD-01")

    fun normalizeArabicText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .replace(Regex("\\s+"), " ")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .lowercase()
    }

    fun normalizeArabicKey(raw: String?): String {
        return normalizeArabicText(raw).replace(Regex("[^a-z0-9\\u0600-\\u06ff]"), "")
    }

    fun resolveCanonicalCurrency(rawCurrency: String?, cashboxHint: String? = null): String {
        val clean = (rawCurrency ?: "").trim().uppercase()
        if (clean == "USD" || clean.contains("دولار") || clean.contains("$")) return "USD"
        if (clean == "EGP" || clean.contains("مصري")) return "EGP"
        if (clean == "SDG" || clean.contains("سودان")) return "SDG"

        val hint = (cashboxHint ?: "").trim().uppercase()
        if (hint.contains("USD") || hint.contains("دولار")) return "USD"
        if (hint.contains("EGP") || hint.contains("مصري") || hint.contains("قاهر") || hint.contains("مصر")) {
            return "EGP"
        }
        return "SDG"
    }

    fun isCancelledOrReversedStatus(status: String?): Boolean {
        val st = (status ?: "").trim().lowercase()
        return st == "cancelled" || st == "reversed" || st == "void" || st == "ملغاة" || st == "ملغي"
    }

    fun isMovementInflow(direction: String?, typeFallback: String? = null): Boolean {
        val raw = direction?.takeIf { it.isNotBlank() } ?: typeFallback.orEmpty()
        val dir = raw.trim().uppercase()
        return dir == "وارد" ||
            dir == "IN" ||
            dir == "INCOME" ||
            dir == "DEPOSIT" ||
            dir == "قبض" ||
            dir == "توريد" ||
            dir == "إيداع" ||
            dir == "ايداع"
    }

    fun canonicalDirection(direction: String?, typeFallback: String? = null): String {
        return if (isMovementInflow(direction, typeFallback)) "وارد" else "منصرف"
    }

    fun signedAmount(direction: String?, amount: Double, typeFallback: String? = null): Double {
        if (!amount.isFinite()) return 0.0
        val mag = abs(amount)
        return if (isMovementInflow(direction, typeFallback)) mag else -mag
    }

    fun signedActiveAmount(movement: MovementEntity): Double {
        if (isCancelledOrReversedStatus(movement.status)) return 0.0
        return signedAmount(movement.direction, movement.amount)
    }

    /**
     * Exact mirror of Web `deduplicateCollectionRecords('movements', ...)` in `ErpCalculations.ts`.
     * - Movements with distinct `syncUuid` never collide.
     * - Legacy cross-platform movements without `syncUuid` merge ONLY when `(reference, cashbox, IN|OUT, currency, amount)`
     *   all match — so transfer legs (`TRF-100` OUT vs IN) and multiple invoice payments with different amounts
     *   are NEVER collapsed.
     */
    fun movementDeduplicationKey(
        syncUuid: String?,
        reference: String?,
        cashbox: String?,
        direction: String?,
        currency: String?,
        amount: Double,
        cloudDocId: String? = null,
        id: Long = 0L,
        description: String? = null,
        createdAt: Long = 0L
    ): String {
        val uuid = (syncUuid ?: "").trim()
        if (uuid.isNotBlank()) return "uuid:$uuid"

        val ref = normalizeArabicKey(reference)
        val dirKey = if (isMovementInflow(direction)) "IN" else "OUT"
        val boxKey = normalizeArabicKey(cashbox)
        val curr = resolveCanonicalCurrency(currency, cashbox)
        val amtCents = if (amount.isFinite()) (abs(amount) * 100.0).roundToLong() else 0L

        if (ref.isNotBlank() && !ref.startsWith("mov")) {
            return "ref:${ref}__${boxKey}__${dirKey}__${curr}__$amtCents"
        }
        val doc = (cloudDocId ?: "").trim()
        if (doc.isNotBlank()) return "doc:$doc"
        if (id > 0L) return "id:$id"
        return "${boxKey}__${dirKey}__${amtCents}__${normalizeArabicKey(description)}__$createdAt"
    }

    fun movementDeduplicationKey(m: MovementEntity): String = movementDeduplicationKey(
        syncUuid = m.syncUuid,
        reference = m.reference,
        cashbox = m.cashbox,
        direction = m.direction,
        currency = m.currency,
        amount = m.amount,
        cloudDocId = m.cloudDocId,
        id = m.id,
        description = m.description,
        createdAt = m.createdAt
    )

    fun deduplicateMovements(movements: List<MovementEntity>): List<MovementEntity> {
        if (movements.size <= 1) return movements
        val map = LinkedHashMap<String, MovementEntity>(movements.size)
        for (m in movements) {
            val key = movementDeduplicationKey(m)
            val existing = map[key]
            if (existing == null) {
                map[key] = m
            } else {
                val exTs = existing.updatedAt.takeIf { it > 0L } ?: existing.createdAt
                val candTs = m.updatedAt.takeIf { it > 0L } ?: m.createdAt
                if (candTs >= exTs) {
                    map[key] = m
                }
            }
        }
        return map.values.toList()
    }

    fun isLocalDemoSeedMovement(m: MovementEntity): Boolean {
        val cleanRef = m.reference.trim().uppercase()
        return cleanRef in DEMO_SEED_REFERENCES &&
            m.cloudDocId.isBlank() &&
            m.syncUuid.isBlank()
    }
}
