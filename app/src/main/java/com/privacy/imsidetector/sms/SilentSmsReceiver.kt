package com.privacy.imsidetector.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.privacy.imsidetector.notification.NotificationHelper

/**
 * "Silent" / "flash" / class-0 SMS are used operationally to probe a
 * handset's presence without alerting the user (empty body, not shown
 * in the normal inbox). A burst of these is a known IMSI-catcher /
 * location-confirmation technique. This receiver only logs metadata
 * (sender, timestamp) — it never reads or stores message content beyond
 * what's needed to confirm class/type, and does not intercept normal SMS.
 */
class SilentSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        for (sms: SmsMessage in messages) {
            if (isSilentSms(sms)) {
                Log.i(TAG, "Silent SMS from ${sms.originatingAddress} at ${System.currentTimeMillis()}")
                NotificationHelper.notifySilentSms(context, sms.originatingAddress)
                // Note: we deliberately do NOT abort the broadcast — this receiver
                // is observational only and must not interfere with the user's
                // normal SMS app / delivery.
            }
        }
    }

    private fun isSilentSms(sms: SmsMessage): Boolean {
        val isClassZero = sms.messageClass == SmsMessage.MessageClass.CLASS_0
        val emptyBody = sms.messageBody.isNullOrEmpty()
        return isClassZero && emptyBody
    }

    companion object {
        private const val TAG = "SilentSmsReceiver"
    }
}
