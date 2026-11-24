package org.echchara.app

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat

/** Android asks this service about every incoming call. Calls are never blocked; unknown numbers start Call Shield. */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        respondToCall(details, CallResponse.Builder().build())
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        Store.init(this)
        if (!Store.callShield) return
        val number = details.handle?.schemeSpecificPart ?: ""
        if (Store.isContact(this, number)) return
        if (!Settings.canDrawOverlays(this)) {
            Store.notifyAlert(this, 7002, Txt("Unknown number calling", "ಅಪರಿಚಿತ ಸಂಖ್ಯೆಯಿಂದ ಕರೆ").get(Store.kannada) + ": $number",
                Txt("Turn on \"Display over other apps\" for Echchara to see live warnings during calls.", "ಕರೆಯ ಸಮಯದಲ್ಲಿ ಎಚ್ಚರಿಕೆಗಾಗಿ \"ಇತರ ಆ್ಯಪ್‌ಗಳ ಮೇಲೆ ತೋರಿಸು\" ಆನ್ ಮಾಡಿ.").get(Store.kannada))
            return
        }
        Guard.startForCall(applicationContext, number)
    }
}

/** Keeps Echchara alive while it checks a call, with a visible notification. */
class CallGuardService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this); Store.channels(this)
        val number = intent?.getStringExtra("number").orEmpty()
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Store.CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(Txt("Call Shield is checking a call", "ಕಾಲ್ ಶೀಲ್ಡ್ ಕರೆಯನ್ನು ಪರಿಶೀಲಿಸುತ್ತಿದೆ").get(Store.kannada))
            .setContentText(number)
            .setOngoing(true).setContentIntent(pi).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(42, n)
        } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
}
