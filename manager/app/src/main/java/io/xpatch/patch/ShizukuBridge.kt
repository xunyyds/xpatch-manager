package io.xpatch.patch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import io.xpatch.BuildConfig
import io.xpatch.IXPatchService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ShizukuBridge {

    private const val REQUEST_CODE = 1001
    private const val BIND_TIMEOUT_SECONDS = 6L

    private val serviceRef = AtomicReference<IXPatchService?>(null)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceRef.set(
                if (binder != null && binder.pingBinder()) {
                    IXPatchService.Stub.asInterface(binder)
                } else {
                    null
                },
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceRef.set(null)
        }
    }

    private fun args(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, XPatchUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    fun isAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.getVersion() >= 11
    }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isAvailable()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure {
                runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                onResult(false)
            }
    }

    private fun service(): IXPatchService? {
        serviceRef.get()?.let { if (it.asBinder().pingBinder()) return it }
        if (!isAvailable() || !hasPermission()) return null
        val latch = CountDownLatch(1)
        runCatching {
            Shizuku.bindUserService(args(), object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    serviceRef.set(
                        if (binder != null && binder.pingBinder()) {
                            IXPatchService.Stub.asInterface(binder)
                        } else {
                            null
                        },
                    )
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    latch.countDown()
                }
            })
        }.onFailure { return null }
        runCatching { latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        return serviceRef.get()
    }

    fun exec(command: String): String? =
        runCatching { service()?.exec(command) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun readText(path: String, maxBytes: Int): String? =
        runCatching { service()?.readText(path, maxBytes) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun forceStop(packageName: String): Boolean = runCatching {
        service()?.forceStop(packageName)
        true
    }.getOrDefault(false)

    fun restart(context: Context, packageName: String): Boolean {
        val stopped = forceStop(packageName)
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            runCatching { context.startActivity(intent) }
        }
        return stopped
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openAppDetails(context: Context, packageName: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun dexopt(packageName: String): Boolean = runCatching {
        service()?.dexopt(packageName)
        true
    }.getOrDefault(false)

    fun unbind() {
        if (!bound) return
        runCatching { Shizuku.unbindUserService(args(), connection, true) }
        bound = false
        serviceRef.set(null)
    }
}
