package com.freeturn.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Screen on/off as a flow (system broadcasts need a runtime receiver). */
fun Context.screenOnFlow(): Flow<Boolean> = callbackFlow {
    val pm = getSystemService(PowerManager::class.java)
    trySend(pm?.isInteractive ?: true)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            trySend(intent?.action == Intent.ACTION_SCREEN_ON)
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }
    ContextCompat.registerReceiver(
        this@screenOnFlow,
        receiver,
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
    awaitClose { unregisterReceiver(receiver) }
}.distinctUntilChanged()
