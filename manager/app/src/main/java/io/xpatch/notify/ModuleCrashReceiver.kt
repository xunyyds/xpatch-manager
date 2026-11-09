package io.xpatch.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import io.xpatch.data.ModuleGuardStore
import io.xpatch.data.ScopeFileWriter
import io.xpatch.data.ScopeStore
import kotlin.concurrent.thread

class ModuleCrashReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val module = intent.getStringExtra(EXTRA_MODULE)?.takeIf { it.isNotBlank() } ?: return
        val app = intent.getStringExtra(EXTRA_APP)?.takeIf { it.isNotBlank() } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val uid = sentFromUid
            if (uid != Process.INVALID_UID) {
                val owned = context.packageManager.getPackagesForUid(uid)
                if (owned == null || app !in owned) return
            }
        }

        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(app, 0) }.getOrNull() ?: return
        val moduleInfo = runCatching { pm.getApplicationInfo(module, 0) }.getOrNull() ?: return

        runCatching { ScopeStore(context).setInScope(module, app, false) }

        val appLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(app)
        val moduleLabel = runCatching { pm.getApplicationLabel(moduleInfo).toString() }.getOrDefault(module)
        ModuleGuardStore.record(
            context,
            ModuleGuardStore.Event(
                appPackage = app,
                appLabel = appLabel,
                modulePackage = module,
                moduleLabel = moduleLabel,
                time = System.currentTimeMillis(),
            ),
        )
        AppNotifications.post(
            context,
            AppNotifications.moduleCrashId(app),
            AppNotifications.moduleCrash(context, moduleLabel, appLabel),
        )

        val pending = goAsync()
        thread(name = "xp-module-scope-sync") {
            try {
                ScopeFileWriter(context).sync(app)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "io.xpatch.action.MODULE_CRASH"
        const val EXTRA_MODULE = "module"
        const val EXTRA_APP = "app"
    }
}
