package io.xpatch.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

enum class AnimationQuality {
    FULL,
    REDUCED,
}

val LocalAnimationQuality = staticCompositionLocalOf { AnimationQuality.FULL }

@Composable
fun rememberAnimationQuality(): AnimationQuality {
    val context = LocalContext.current
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    var quality by remember(powerManager) {
        mutableStateOf(
            if (powerManager?.isPowerSaveMode == true) AnimationQuality.REDUCED else AnimationQuality.FULL,
        )
    }
    DisposableEffect(context, powerManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                quality = if (powerManager?.isPowerSaveMode == true) {
                    AnimationQuality.REDUCED
                } else {
                    AnimationQuality.FULL
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return quality
}
