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

    private val spokenScams = listOf(
        "ಹಲೋ ನಾನು ಮುಂಬೈ ಸೈಬರ್ ಕ್ರೈಂ ಪೊಲೀಸ್ ಇಂದ ಮಾತಾಡ್ತಾ ಇದೀನಿ ನಿಮ್ಮ ಆಧಾರ್ ನಂಬರ್ ಮನಿ ಲಾಂಡರಿಂಗ್ ಕೇಸ್ ಅಲ್ಲಿ ಇದೆ ನಿಮ್ಮನ್ನ ಡಿಜಿಟಲ್ ಅರೆಸ್ಟ್ ಮಾಡ್ತಾ ಇದೀವಿ ಯಾರಿಗೂ ಹೇಳಬೇಡಿ ಕಾಲ್ ಕಟ್ ಮಾಡಬೇಡಿ",
        "ನಮಸ್ತೆ ಸರ್ ನಾನು ಎಸ್ ಬಿ ಐ ಬ್ಯಾಂಕ್ ಇಂದ ಕಾಲ್ ಮಾಡ್ತಿದೀನಿ ನಿಮ್ಮ ಕೆವೈಸಿ ಅಪ್ಡೇಟ್ ಆಗಿಲ್ಲ ಅಕೌಂಟ್ ಬ್ಲಾಕ್ ಆಗುತ್ತೆ ನಿಮ್ಮ ಮೊಬೈಲ್ ಗೆ ಒಂದು ಓಟಿಪಿ ಬಂದಿದೆ ಅದನ್ನ ಹೇಳಿ",
        "ನಿಮಗೆ ಲಾಟರಿ ಬಂದಿದೆ ಇಪ್ಪತ್ತೈದು ಲಕ್ಷ ಬಹುಮಾನ ಪಡೆಯೋಕೆ ಪ್ರೊಸೆಸಿಂಗ್ ಫೀ ಐದು ಸಾವಿರ ಕಳಿಸಿ",
        "ನಿಮ್ಮ ಕರೆಂಟ್ ಬಿಲ್ ಕಟ್ಟಿಲ್ಲ ಇವತ್ತು ರಾತ್ರಿ ಕರೆಂಟ್ ಕಟ್ ಮಾಡ್ತೀವಿ ಈ ಆ್ಯಪ್ ಡೌನ್ಲೋಡ್ ಮಾಡಿ",
        "ನಿಮ್ಮ ಹೆಸರಲ್ಲಿ ಒಂದು ಕೊರಿಯರ್ ಬಂದಿದೆ ಅದರಲ್ಲಿ ಡ್ರಗ್ಸ್ ಸಿಕ್ಕಿದೆ ಸಿಬಿಐ ಕೇಸ್ ಆಗುತ್ತೆ",
        "ಹಲೋ ನಾನು ನಿಮ್ಮ ಫ್ರೆಂಡ್ ರಾಜು ತುಂಬಾ ಅರ್ಜೆಂಟ್ ಹಣ ಬೇಕು ಈಗಲೇ ಗೂಗಲ್ ಪೇ ಮಾಡಿ"
    )
    private val spokenGenuine = listOf(
        "ಅಮ್ಮ ನಾನು ಸಂಜೆ ಆರು ಗಂಟೆಗೆ ಮನೆಗೆ ಬರ್ತೀನಿ ಊಟ ರೆಡಿ ಮಾಡು",
        "ಸರ್ ನಿಮ್ಮ ಪಾರ್ಸೆಲ್ ಗೇಟ್ ಹತ್ತಿರ ಇದೆ ಸೆಕ್ಯೂರಿಟಿ ಹತ್ರ ಕೊಡ್ಲಾ",
        "ನಿಮ್ಮ ಬ್ಯಾಂಕ್ ಖಾತೆಗೆ ಐದು ಸಾವಿರ ರೂಪಾಯಿ ಜಮೆ ಆಗಿದೆ"
    )

    @Test fun spokenKannadaScamsAreCaught() {
        val missed = spokenScams.filter { val a = Engine.analyze(it); println("KN scam  score=${a.score} rules=${a.ruleScore} | ${it.take(40)}"); a.score < 30 }
        assertTrue("Missed spoken Kannada scams: $missed", missed.isEmpty())
    }

    @Test fun spokenKannadaGenuineIsSafe() {
        val wrong = spokenGenuine.filter { val a = Engine.analyze(it); println("KN ok    score=${a.score} rules=${a.ruleScore} | ${it.take(40)}"); a.score >= 30 }
        assertTrue("False alarms on everyday Kannada: $wrong", wrong.isEmpty())
    }

    @Test fun shortLinksOfBrandsAreKnown() {
        assertTrue(Engine.analyzeUrl("fkrt.it/abc")!!.known)
        assertTrue(Engine.analyzeUrl("https://spoti.fi/x")!!.known)
        assertFalse(Engine.analyzeUrl("flipkart-lucky.top")!!.known)
    }
}
