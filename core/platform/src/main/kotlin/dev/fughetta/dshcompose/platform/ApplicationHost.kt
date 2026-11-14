package dev.fughetta.dshcompose.platform

import android.content.Context
import android.content.Intent

interface ApplicationHost {
    fun appIntent(context: Context): Intent

    fun chatIntent(context: Context, sessionId: String, host: String?): Intent

    fun linkIntent(context: Context, url: String): Intent

    fun refreshPhoneNotifications(context: Context)
}

fun Context.applicationHost(): ApplicationHost = applicationContext as ApplicationHost
