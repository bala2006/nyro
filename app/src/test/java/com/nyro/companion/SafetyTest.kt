package com.nyro.companion

import com.nyro.companion.tools.Risk
import com.nyro.companion.tools.RiskAssessment
import com.nyro.companion.tools.SensitiveUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The risk rules that every tool call, plan step and local command is assessed with. Speed work must not change them. */
class SafetyTest {
    @Test fun paymentsAndDeletionAreHighRisk() {
        for (label in listOf("Pay now", "Place order", "Delete account", "Confirm payment", "Send money", "Uninstall", "Factory reset", "Subscribe")) {
            assertEquals(label, Risk.HIGH, SensitiveUi.classify(label, "com.shop")!!.risk)
        }
    }

    @Test fun anythingInsideAFinancialAppIsHighRisk() {
        assertEquals(Risk.HIGH, SensitiveUi.classify("Next", "com.phonepe.app")!!.risk)
        assertEquals(Risk.HIGH, SensitiveUi.classify("Home", "com.sbi.lotusintouch")!!.risk)
        assertTrue(SensitiveUi.isFinancial("net.one97.paytm"))
    }

    @Test fun sendingAndSharingAreMediumRisk() {
        for (label in listOf("Send", "Post", "Share", "Reply", "Submit", "Follow", "Install")) {
            assertEquals(label, Risk.MEDIUM, SensitiveUi.classify(label, "com.chat")!!.risk)
        }
    }

    @Test fun ordinaryTapsAreNotClassified() {
        assertNull(SensitiveUi.classify("Start", "com.duolingo"))
        assertNull(SensitiveUi.classify("Practice", "com.duolingo"))
        assertNull(SensitiveUi.classify("SECTION 2, UNIT 1", "com.duolingo"))
    }

    @Test fun theUsersOwnRequestCoversMediumButNeverHighRisk() {
        val send = RiskAssessment(Risk.MEDIUM, "Tap \"Send\" (sends, shares or changes something)")
        assertTrue(SensitiveUi.requestCovers("send hello to Pranav on whatsapp", send, "tap_element"))
        assertFalse("a request that never mentioned sending does not cover it", SensitiveUi.requestCovers("open whatsapp", send, "tap_element"))
        val pay = RiskAssessment(Risk.HIGH, "Tap \"Pay now\" (possible payment, purchase, deletion or security change)")
        assertFalse("high risk always asks, whatever the request said", SensitiveUi.requestCovers("pay now please, I confirm", pay, "tap_element"))
    }

    @Test fun modelConfidenceIsNotConsent() {
        // Nothing in the plan format lets a step declare itself safe: there is no risk or confirm field to set.
        val fields = com.nyro.companion.exec.PlanStep::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("risk", true) || it.contains("confirm", true) || it.contains("approve", true) || it.contains("skip", true) })
    }
}
