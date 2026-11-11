package io.xpatch.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import io.xpatch.data.EventLog
import io.xpatch.data.ModuleGuardStore
import io.xpatch.data.ScopeFileWriter
import io.xpatch.data.ScopeStore
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ModuleCrashReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val appContext = context.applicationContext
        AppNotifications.ensureChannels(appContext)

        val module = intent.getStringExtra(EXTRA_MODULE)?.trim().orEmpty()
        val appPackage = intent.getStringExtra(EXTRA_APP)?.trim().orEmpty()
        if (module.isEmpty() || appPackage.isEmpty()) return
        if (!senderTrusted(appContext, intent)) {
            Log.w(TAG, "丢弃不可信的模块崩溃广播：$module -> $appPackage")
            return
        }

        val pm = appContext.packageManager
        val appInfo = installedApp(pm, appPackage) ?: return
        val moduleInfo = installedApp(pm, module) ?: return

        val inScope = runCatching { ScopeStore(appContext).modulesOf(appPackage).contains(module) }
            .getOrDefault(false)
        if (!inScope) {
            Log.w(TAG, "忽略不在作用域内的组合：$module -> $appPackage")
            return
        }

        if (!allowOnce(module, appPackage)) return

        val appLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(appPackage)
        val moduleLabel = runCatching { pm.getApplicationLabel(moduleInfo).toString() }.getOrDefault(module)

        ModuleGuardStore.record(
            appContext,
            ModuleGuardStore.Event(
                appPackage = appPackage,
                appLabel = appLabel,
                modulePackage = module,
                moduleLabel = moduleLabel,
                time = System.currentTimeMillis(),
            ),
        )
        val removed = runCatching { ScopeStore(appContext).setInScope(module, appPackage, false) }
            .isSuccess

        val pending = goAsync()
        thread(name = "xp-module-guard") {
            try {
                val result = if (removed) syncWithBudget(appContext, appPackage, SYNC_BUDGET_MS) else null
                val written = result?.ok == true
                if (!written) {
                    ModuleGuardStore.markSyncFailed(appContext, appPackage, module)
                    val reason = result?.failureText ?: "作用域文件同步超时（超过 ${SYNC_BUDGET_MS / 1000} s）"
                    runCatching { EventLog(appContext).add("模块保护：$reason") }
                    Log.w(TAG, "作用域同步未完成：$reason")
                }
                AppNotifications.post(
                    appContext,
                    AppNotifications.moduleCrashId(appPackage),
                    AppNotifications.moduleCrash(appContext, moduleLabel, appLabel, written),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "处理模块崩溃广播失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun senderTrusted(context: Context, intent: Intent): Boolean {
        val target = intent.`package`
        if (target != null && target != context.packageName) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val uid = sentFromUid
        if (uid == Process.INVALID_UID) return false
        val sameSigner = runCatching { context.packageManager.checkSignatures(uid, Process.myUid()) }
            .getOrDefault(PackageManager.SIGNATURE_UNKNOWN_PACKAGE)
        if (sameSigner != PackageManager.SIGNATURE_MATCH) return false
        val appPackage = intent.getStringExtra(EXTRA_APP)?.trim()
        val owned = runCatching { context.packageManager.getPackagesForUid(uid) }.getOrNull()
        return owned != null && appPackage != null && owned.contains(appPackage)
    }

    private fun installedApp(pm: PackageManager, packageName: String): ApplicationInfo? =
        runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()

    private fun allowOnce(module: String, appPackage: String): Boolean {
        val key = "$module->$appPackage"
        val now = System.currentTimeMillis()
        synchronized(RATE_LIMIT) {
            if (RATE_LIMIT.size >= RATE_LIMIT_MAX) RATE_LIMIT.clear()
            val last = RATE_LIMIT[key]
            if (last != null && now - last < RATE_LIMIT_WINDOW_MS) return false
            RATE_LIMIT[key] = now
            return true
        }
    }

    private fun syncWithBudget(context: Context, appPackage: String, budgetMs: Long): ScopeFileWriter.Result? {
        val holder = AtomicReference<ScopeFileWriter.Result?>(null)
        val worker = thread(name = "xp-module-scope-sync") {
            holder.set(runCatching { ScopeFileWriter(context).sync(appPackage) }.getOrNull())
        }
        worker.join(budgetMs)
        return if (worker.isAlive) null else holder.get()
    }

    companion object {
        const val ACTION = "io.xpatch.action.MODULE_CRASH"
        const val EXTRA_MODULE = "module"
        const val EXTRA_APP = "app"

        private const val TAG = "XPatch-Crash"

        private const val SYNC_BUDGET_MS = 5_000L
        private const val RATE_LIMIT_WINDOW_MS = 60_000L
        private const val RATE_LIMIT_MAX = 64

        private val RATE_LIMIT = LinkedHashMap<String, Long>()
    }
}
