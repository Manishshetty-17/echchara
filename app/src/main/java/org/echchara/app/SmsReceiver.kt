package org.echchara.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** SMS Shield: checks every incoming SMS on the device and warns about scams. Also warns about an OTP that arrives during an unknown call. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Store.init(ctx)
        val smsOn = Store.smsShield
        val otpWatch = Guard.inUnknownCall
        if (!smsOn && !otpWatch) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts[0].displayOriginatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        if (body.isBlank()) return
        val pending = goAsync()
        Thread {
            try {
                if (otpWatch && Tips.looksLikeOtp(body)) Guard.otpArrived()
                if (smsOn) {
                    val a = Engine.analyze(body)
                    if (a.score >= 30) {
                        val kn = Store.kannada
                        Store.addHistory(HistoryItem(System.currentTimeMillis(), "sms", sender, body, a.score, a.type?.en, a.type?.kn))
                        val title = (if (a.score >= 60) Txt("Scam SMS from $sender", "$sender ನಿಂದ ವಂಚನೆ SMS") else Txt("Suspicious SMS from $sender", "$sender ನಿಂದ ಅನುಮಾನಾಸ್ಪದ SMS")).get(kn)
                        val body2 = (a.type?.get(kn)?.let { "$it · " } ?: "") + a.summary.get(kn) + (Tips.codeWord(a)?.let { " " + it.get(kn) } ?: "")
                        val id = (System.currentTimeMillis() % 100000).toInt()
                        Store.notifyAlert(ctx, id, title, body2, Links.reportActions(ctx, 300_000 + id * 2, "$sender\n$body"))
                        if (a.score >= 60) RiskWindow.start(ctx, Txt("a scam SMS", "ವಂಚನೆ SMS"))
                        if (a.flags.any { it.id == "esim" }) Store.simRiskAt = System.currentTimeMillis()
                    }
                }
            } finally { pending.finish() }
        }.start()
    }
}
