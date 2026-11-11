package com.example.cardvault

import java.util.Calendar

object SecurityAudit {

    data class AuditResult(
        val score: Int,
        val statusText: String,
        val totalItems: Int,
        val weakPasswordsCount: Int,
        val reusedPasswordsCount: Int,
        val expiringCardsCount: Int,
        val recommendations: List<String>
    )

    fun calculateEntropy(password: String): Double {
        if (password.isEmpty()) return 0.0
        var poolSize = 0
        if (password.any { it.isLowerCase() }) poolSize += 26
        if (password.any { it.isUpperCase() }) poolSize += 26
        if (password.any { it.isDigit() }) poolSize += 10
        if (password.any { !it.isLetterOrDigit() }) poolSize += 33
        if (poolSize == 0) return 0.0
        return password.length * (kotlin.math.log2(poolSize.toDouble()))
    }

    fun getStrengthLabel(password: String): Pair<String, Int> {
        val entropy = calculateEntropy(password)
        return when {
            password.length < 8 || entropy < 35 -> Pair("Weak", VaultTheme.danger)
            password.length < 12 || entropy < 60 -> Pair("Moderate", VaultTheme.warning)
            entropy < 85 -> Pair("Strong", VaultTheme.accent)
            else -> Pair("Very Strong", VaultTheme.positive)
        }
    }

    fun isCardExpiringSoon(expiry: String): Boolean {
        if (expiry.isBlank()) return false
        val parts = expiry.split("/", "-")
        if (parts.size != 2) return false
        val month = parts[0].trim().toIntOrNull() ?: return false
        val yearPart = parts[1].trim().toIntOrNull() ?: return false
        val year = if (yearPart < 100) 2000 + yearPart else yearPart

        val cal = Calendar.getInstance()
        val currentYear = cal.get(Calendar.YEAR)
        val currentMonth = cal.get(Calendar.MONTH) + 1

        if (year < currentYear) return true
        if (year == currentYear && month <= currentMonth + 2) return true
        return false
    }

    fun audit(entries: List<VaultStore.Entry>): AuditResult {
        if (entries.isEmpty()) {
            return AuditResult(
                score = 100,
                statusText = "Vault Empty",
                totalItems = 0,
                weakPasswordsCount = 0,
                reusedPasswordsCount = 0,
                expiringCardsCount = 0,
                recommendations = emptyList()
            )
        }

        val passwords = entries.filter { it.kind == "Password" && it.secret.isNotBlank() }.map { it.secret }
        val weakCount = passwords.count { it.length < 12 || calculateEntropy(it) < 60 }

        val freq = mutableMapOf<String, Int>()
        passwords.forEach { p -> freq[p] = (freq[p] ?: 0) + 1 }
        val reusedCount = freq.filter { it.value > 1 }.values.sum()

        val cards = entries.filter { it.kind == "Card" }
        val expiringCount = cards.count { isCardExpiringSoon(it.expiry) }

        var deduction = 0
        deduction += weakCount * 12
        deduction += reusedCount * 15
        deduction += expiringCount * 5
        val finalScore = (100 - deduction).coerceIn(20, 100)

        val status = when {
            finalScore >= 90 -> "Optimal"
            finalScore >= 75 -> "Good"
            finalScore >= 50 -> "Needs Review"
            else -> "Action Recommended"
        }

        val recs = mutableListOf<String>()
        if (reusedCount > 0) recs.add("$reusedCount repeated passwords")
        if (weakCount > 0) recs.add("$weakCount passwords with low entropy")
        if (expiringCount > 0) recs.add("$expiringCount cards near expiration")

        return AuditResult(
            score = finalScore,
            statusText = status,
            totalItems = entries.size,
            weakPasswordsCount = weakCount,
            reusedPasswordsCount = reusedCount,
            expiringCardsCount = expiringCount,
            recommendations = recs
        )
    }
}
