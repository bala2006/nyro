package com.nyro.companion.data

/**
 * Keeps passwords, OTPs, PINs and card numbers out of local history and memory.
 * Conservative by design: it is better to mask a harmless number than to persist a secret.
 */
object Redactor {
    private val secretKeyword = Regex(
        "(?i)\\b(otp|one[- ]time|passcode|password|passwd|pwd|pin|cvv|cvc|verification code|security code|auth(?:entication)? code|2fa|upi pin)\\b"
    )
    private val cardNumber = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val passwordValue = Regex(
        "(?i)\\b(passcode|password|passwd|pwd)\\b(\\s*(?:is|:|=|-)\\s*)(\\S{3,})"
    )
    private val numericValue = Regex(
        "(?i)\\b(otp|pin|cvv|cvc|code)\\b(\\s*(?:is|:|=|-)?\\s*)(\\S*\\d\\S*)"
    )
    private val shortCode = Regex("\\b\\d{4,8}\\b")

    fun containsSecret(text: String): Boolean =
        secretKeyword.containsMatchIn(text) || cardNumber.containsMatchIn(text)

    fun redact(text: String): String {
        var out = cardNumber.replace(text) { m -> if (m.value.count { it.isDigit() } >= 13) "[card number removed]" else m.value }
        out = passwordValue.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}[removed]" }
        out = numericValue.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}[removed]" }
        if (secretKeyword.containsMatchIn(out)) out = shortCode.replace(out, "[code removed]")
        return out
    }
}
