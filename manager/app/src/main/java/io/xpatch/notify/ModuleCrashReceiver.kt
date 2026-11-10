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

/**
 * 内核的模块熔断广播接收端。
 *
 * 信任模型（重要，改动前先读）：
 *  - 这条广播来自被注入的目标应用进程，所以 receiver 必须 exported=true；
 *    第一道门是清单里的 signature 级权限 io.xpatch.permission.MODULE_CRASH（任何非同一证书的应用直接被系统拦下）。
 *  - 第二道门是下面的语义收窄：只有"该模块此刻确实在该应用作用域内"的组合才处理。
 *    这样即使有人绕过权限，也既不能删作用域、也不能往首页/通知里塞伪造事件。
 *  - 第三道门是 60 s 限流：一次伪造最多顶掉一条真实事件，不会刷爆 prefs / 通知 / 线程。
 *
 * 耗时预算：onReceive 主路径只做"校验 + 记账 + 内存作用域变更"（毫秒级）。
 * 真正的写盘（ScopeFileWriter.sync：Shizuku 重试 + 逐个解析模块 APK）放到后台线程并限时 5 s，
 * 超时按失败处理（发"未能写入作用域"而不是"已移除"），避免撞上 goAsync 的 10 s 广播超时。
 */
class ModuleCrashReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val appContext = context.applicationContext
        // 崩溃通知不经过任何 UI，渠道必须在这里就建好，否则升级后首条通知会被系统丢弃。
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

        // 语义收窄：不在作用域里的 (module, app) 组合不是"熔断"的目标，直接忽略并留痕。
        val inScope = runCatching { ScopeStore(appContext).modulesOf(appPackage).contains(module) }
            .getOrDefault(false)
        if (!inScope) {
            Log.w(TAG, "忽略不在作用域内的组合：$module -> $appPackage")
            return
        }

        if (!allowOnce(module, appPackage)) return

        val appLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(appPackage)
        val moduleLabel = runCatching { pm.getApplicationLabel(moduleInfo).toString() }.getOrDefault(module)

        // 先记账再改作用域：写盘失败时首页横幅仍然看得到这次事件（旧实现把顺序反过来，
        // 一旦后续抛异常用户什么都看不到）。
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

        // 慢路径搬到后台线程，并给一个硬上限：超时按"没能写入作用域"处理。
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

    /**
     * 发送方校验（fail-closed）。
     * - setPackage 必须是本应用：内核的 intent 一定带包名，指别人的 intent 不可能是内核发的。
     * - API 34+ 能拿到发送者 uid 时必须同证书且 uid 拥有 app 包；拿不到 uid 就拒绝（旧实现放行）。
     * - API < 34 只能依赖清单里的 signature 权限（系统在投递前已校验），这里不做额外判断。
     */
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

    /**
     * 60 s 内同一 (module, app) 只处理一次。表很小（最多 [RATE_LIMIT_MAX] 项），
     * 满了就整表丢弃重建——限流本身只是防刷，不需要精确历史。
     */
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

    /**
     * 在后台线程里执行 [ScopeFileWriter.sync]，最多等 [budgetMs] 毫秒。
     * 返回 null 表示超时（调用方按失败处理）；sync 自己的异常也返回 null。
     */
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

        /** 写盘预算：必须在 goAsync 的 10 s 广播窗口内留足余量。 */
        private const val SYNC_BUDGET_MS = 5_000L
        private const val RATE_LIMIT_WINDOW_MS = 60_000L
        private const val RATE_LIMIT_MAX = 64

        private val RATE_LIMIT = LinkedHashMap<String, Long>()
    }
}
