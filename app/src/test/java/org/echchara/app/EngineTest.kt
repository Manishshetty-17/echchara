package org.echchara.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-world style messages: genuine app/shopping SMS must not alert; scams must. */
class EngineTest {
    private fun alert(sender: String, text: String): Boolean {
        val a = Engine.analyze(text)
        println("%-12s score=%3d rules=%3d ml=%.2f alert=%-5s | %s".format(sender, a.score, a.ruleScore, a.ml, Engine.shouldAlertSms(sender, a), text.take(70)))
        return Engine.shouldAlertSms(sender, a)
    }

    private val genuine = listOf(
        "JD-FLPKRT-S" to "Flipkart: Your order for boAt Airdopes has been delivered. Rate your experience: fkrt.it/Rt5Y",
        "VM-FLPKRT" to "Big Saving Days LIVE! Up to 80% off on smartphones + extra 10% instant discount. Hurry, limited stock! fkrt.it/sale",
        "AX-FLPKRT" to "784512 is your OTP to log in to Flipkart. Do not share this OTP with anyone.",
        "JM-FLPKRT" to "Your Flipkart order is on hold as payment is pending. Complete payment within 30 minutes or it will be cancelled.",
        "VM-SPTIFY" to "Spotify: Your payment failed. Update your card details to keep Premium, or your account will be switched to Free.",
        "AD-SPTIFY" to "Get 3 months of Premium for Rs 59. Last chance! Offer ends tonight. spoti.fi/premium",
        "VK-AMAZON" to "Amazon: Congratulations! You have won a cashback of Rs 50 on your recent order. It will be credited to Amazon Pay.",
        "JK-HDFCBK" to "Rs 499.00 debited from a/c **1234 to SPOTIFY on 26-11-26. Not you? Call 18002586161.",
        "+919845011111" to "Hi, are we still meeting at 6 for dinner?"
    )
    private val scams = listOf(
        "+919845011111" to "Dear Customer, your SBI YONO account will be blocked today. Update your KYC immediately at sbi-kyc-update.top",
        "+918123456789" to "Dear consumer your electricity will be disconnected tonight. Contact electricity officer 9876501234 immediately",
        "+917000000000" to "Flipkart: You won an iPhone 15 in our lucky draw! Pay Rs 499 processing fee to claim your prize at flipkart-lucky.top",
        "VM-SBIINB" to "Your SBI account is suspended. Verify your account now at sbi-secure-login.xyz or it will be blocked within 24 hours",
        "+916000000000" to "send money iam your friend Kiran, or else otp will come to your phone share it"
    )

    @Test fun genuineMessagesDoNotAlert() {
        val wrong = genuine.filter { (s, t) -> alert(s, t) }
        assertTrue("False alarms: $wrong", wrong.isEmpty())
    }

    @Test fun scamsStillAlert() {
        val missed = scams.filter { (s, t) -> !alert(s, t) }
        assertTrue("Missed scams: $missed", missed.isEmpty())
    }

    @Test fun shortLinksOfBrandsAreKnown() {
        assertTrue(Engine.analyzeUrl("fkrt.it/abc")!!.known)
        assertTrue(Engine.analyzeUrl("https://spoti.fi/x")!!.known)
        assertFalse(Engine.analyzeUrl("flipkart-lucky.top")!!.known)
    }
}
