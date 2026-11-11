package com.example.cardvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuditTest {

    @Test
    fun testEntropyLevels() {
        val weak = "abc"
        val (weakLabel, _) = SecurityAudit.getStrengthLabel(weak)
        assertTrue(weakLabel.equals("Weak", ignoreCase = true))

        val moderate = "Passw0rd12"
        val (modLabel, _) = SecurityAudit.getStrengthLabel(moderate)
        assertTrue(modLabel.equals("Moderate", ignoreCase = true))

        val strong = "aB3!kL9#mP2\$qR7%"
        val (strongLabel, _) = SecurityAudit.getStrengthLabel(strong)
        assertTrue(strongLabel.contains("Strong", ignoreCase = true))
    }

    @Test
    fun testCardBrandDetection() {
        assertEquals(CardVisuals.CardBrand.VISA, CardVisuals.detectBrand("4111222233334444"))
        assertEquals(CardVisuals.CardBrand.MASTERCARD, CardVisuals.detectBrand("5500000000000004"))
        assertEquals(CardVisuals.CardBrand.AMEX, CardVisuals.detectBrand("378282246310005"))
        assertEquals(CardVisuals.CardBrand.DISCOVER, CardVisuals.detectBrand("6011000000000000"))
        assertEquals(CardVisuals.CardBrand.RUPAY, CardVisuals.detectBrand("6061000000000000"))
    }

    @Test
    fun testCardExpiryDetection() {
        assertTrue(SecurityAudit.isCardExpiringSoon("01/20"))
        assertFalse(SecurityAudit.isCardExpiringSoon("12/35"))
    }

    @Test
    fun testAuditScoring() {
        val emptyResult = SecurityAudit.audit(emptyList())
        assertEquals(100, emptyResult.score)

        val entries = listOf(
            VaultStore.Entry(
                id = "1",
                kind = "Password",
                title = "Email",
                cardholderName = "",
                username = "test@example.com",
                secret = "123456",
                cardNumber = "",
                expiry = "",
                cvv = "",
                notes = "",
                customFields = emptyMap()
            ),
            VaultStore.Entry(
                id = "2",
                kind = "Password",
                title = "Bank",
                cardholderName = "",
                username = "test@example.com",
                secret = "123456",
                cardNumber = "",
                expiry = "",
                cvv = "",
                notes = "",
                customFields = emptyMap()
            )
        )

        val audit = SecurityAudit.audit(entries)
        assertTrue(audit.score < 90)
        assertTrue(audit.weakPasswordsCount > 0)
        assertTrue(audit.reusedPasswordsCount > 0)
    }
}
