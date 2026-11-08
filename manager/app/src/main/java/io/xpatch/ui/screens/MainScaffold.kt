package io.xpatch.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.xpatch.data.ConfigBackup
import io.xpatch.data.DownloadCenter
import io.xpatch.data.EnvInfoCollector
import io.xpatch.data.EventLog
import io.xpatch.data.ManagerRepository
import io.xpatch.data.ModuleApiStore
import io.xpatch.data.PatchExportRef
import io.xpatch.data.PatchHistoryStore
import io.xpatch.data.PatchRecord
import io.xpatch.data.RepoStore
import io.xpatch.data.RestartCenter
import io.xpatch.data.RuntimeLogStore
import io.xpatch.data.ScopeFileWriter
import io.xpatch.data.SettingsStore
import io.xpatch.data.SignatureStore
import io.xpatch.data.UpdateChecker
import io.xpatch.model.AppInfo
import io.xpatch.model.ModuleInfo
import io.xpatch.patch.InstallHelper
import io.xpatch.patch.PatchFingerprint
import io.xpatch.patch.PatchOptions
import io.xpatch.patch.PatchService
import io.xpatch.patch.ShizukuBridge
import io.xpatch.patch.ShizukuInstaller
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.LiquidBar
import io.xpatch.ui.components.MiuixBottomBar
import io.xpatch.ui.components.PatchProgress
import io.xpatch.ui.components.PatchProgressDialog
import io.xpatch.ui.components.StandardBottomBar
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.util.AdaptiveWidth
import io.xpatch.ui.util.LocalPageKey
import io.xpatch.ui.util.LocalScrollStateStore
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    onThemeModeChange: (Int) -> Unit = {},
    onUiStyleChange: (io.xpatch.data.UiStyle) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember { ManagerRepository(context) }
    val settingsStore = remember { SettingsStore(context) }
    val eventLog = remember { EventLog(context) }
    val logStore = remember { RuntimeLogStore() }
    val repoStore = remember { RepoStore(context) }
    val configBackup = remember { ConfigBackup(context) }
    val patchService = remember { PatchService(context) }
    val signatureStore = remember { SignatureStore(context) }
    val moduleApiStore = remember { ModuleApiStore(context) }
    val historyStore = remember { PatchHistoryStore(context) }
    var signatures by remember { mutableStateOf(emptyList<SignatureStore.Profile>()) }
    var defaultSignatureId by remember { mutableStateOf(SignatureStore.BUILTIN_XPATCH) }
    var historyTick by remember { mutableIntStateOf(0) }
    var currentRecordId by remember { mutableStateOf<String?>(null) }
    val historyState = produceState(
        initialValue = HistorySnapshot(),
        historyTick,
    ) {
        value = withContext(Dispatchers.IO) {
            val records = runCatching { historyStore.list() }.getOrDefault(emptyList())
            HistorySnapshot(records = records, totalSize = runCatching { historyStore.totalSize() }.getOrDefault(0L))
        }
    }
    val scope = rememberCoroutineScope()

    val nav = rememberNavigator()
    val scrollStore = remember { io.xpatch.ui.util.ScrollStateStore() }
    val miuix = LocalMiuix.current
    val clipboard = LocalClipboardManager.current
    val logsState = remember { LogsState() }
    var logDetailState by remember { mutableStateOf(LogDetailState()) }
    val logDetailList = androidx.compose.foundation.lazy.rememberLazyListState()
    var logsRefreshing by remember { mutableStateOf(false) }
    var logsLoadingMore by remember { mutableStateOf(false) }
    var logsRuntime by remember {
        mutableStateOf(RuntimeLogsState(hasAccess = false))
    }
    var homeRefreshing by remember { mutableStateOf(false) }
    var shizukuAvailable by remember { mutableStateOf(ShizukuBridge.isAvailable()) }
    var shizukuReady by remember {
        mutableStateOf(shizukuAvailable && ShizukuBridge.hasPermission())
    }
    var modulesRefreshing by remember { mutableStateOf(false) }
    var pendingExportFileName by remember { mutableStateOf<String?>(null) }
    var pendingLogText by remember { mutableStateOf<String?>(null) }
    var pendingLogStream by remember {
        mutableStateOf<(suspend ((String) -> Unit) -> Unit)?>(null)
    }
    val labelCache = remember { java.util.concurrent.ConcurrentHashMap<String, String>() }
    val labelOf: (String) -> String = { packageName ->
        labelCache.getOrPut(packageName) {
            runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }
    }
    var query by remember { mutableStateOf("") }
    var pickerQuery by remember { mutableStateOf("") }
    var scopeQuery by remember { mutableStateOf("") }
    var scopeSort by remember { mutableIntStateOf(0) }
    var scopeReversed by remember { mutableStateOf(false) }
    val scopeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var includeSystem by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var patchRunning by remember { mutableStateOf(false) }
    var patchProgress by remember { mutableIntStateOf(0) }
    var patchFailure by remember { mutableStateOf<String?>(null) }
    val patchLog = remember { mutableStateListOf<String>() }
    var result by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var pendingUninstall by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var uninstallingTarget by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var installBlocked by remember { mutableStateOf<InstallBlock?>(null) }

    var patchedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var patchableApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var modules by remember { mutableStateOf<List<ModuleInfo>>(emptyList()) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var onboardingDone by remember { mutableStateOf(settings.onboardingDone) }
    if (!onboardingDone) {
        OnboardingScreen(
            onAcceptDisclaimer = {
                settingsStore.setDisclaimerAcceptedAt(System.currentTimeMillis())
            },
            onComplete = {
                settingsStore.setOnboardingDone(true)
                onboardingDone = true
            },
            onExit = { (context as? android.app.Activity)?.finish() },
        )
        return
    }
    var startupUpdate by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var startupPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(onboardingDone) {
        if (!onboardingDone || startupPrompted) return@LaunchedEffect
        val result = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(20_000) { UpdateChecker.check(context, force = false) }
        }.getOrNull()
        if (result is UpdateChecker.CheckResult.Available) {
            startupPrompted = true
            startupUpdate = result.release
        }
    }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var repoModules by remember { mutableStateOf<List<RepoStore.RepoModule>>(emptyList()) }
    var repoBusy by remember { mutableStateOf(false) }
    var repoDownloadPkg by remember { mutableStateOf<String?>(null) }
    var repoDownloadProgress by remember { mutableIntStateOf(0) }
    var repoDownloadDoneTick by remember { mutableIntStateOf(0) }
    var repoDownloadTaskId by remember { mutableStateOf<Long?>(null) }
    var repoError by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableIntStateOf(0) }
    val scopeSyncLocks = remember { HashMap<String, Mutex>() }
    val syncScopeFile: (String) -> Unit = { appPackage ->
        val lock = synchronized(scopeSyncLocks) {
            scopeSyncLocks.getOrPut(appPackage) { Mutex() }
        }
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val result = ScopeFileWriter(context).sync(appPackage)
                if (!result.ok) {
                    eventLog.add("作用域同步失败 " + (result.failureText ?: appPackage))
                    val entries = eventLog.entries()
                    withContext(Dispatchers.Main) { logs = entries }
                } else if (result.dataViaShizuku) {
                    eventLog.add("已通过 Shizuku 清理 " + appPackage + " 的遗留 data 作用域")
                    val entries = eventLog.entries()
                    withContext(Dispatchers.Main) { logs = entries }
                }
            }
        }
    }

    val startPatch: suspend (PatchTarget, PatchOptions) -> Unit = startPatch@{ target, options ->
        settingsStore.setHookCore(options.hookCore)
        settingsStore.setSignatureBypassLevel(options.bypassLevel)
        settingsStore.setInjectSignature(options.injectSignature)
        settingsStore.setFileProvider(options.fileProvider)
        settingsStore.setDynamicMode(options.dynamicMode)
        settingsStore.setForceOverwrite(options.forceOverwrite)
        settings = settings.copy(
            hookCore = options.hookCore,
            signatureBypassLevel = options.bypassLevel,
            injectSignature = options.injectSignature,
            fileProvider = options.fileProvider,
            dynamicMode = options.dynamicMode,
            forceOverwrite = options.forceOverwrite,
        )
        val sourceKey = patchSourceKey(target)
        val fingerprint = PatchFingerprint.of(sourceKey, options)
        val summary = PatchFingerprint.summary(options)
        val recordVersionName = options.versionName ?: patchVersionName(target)
        val recordVersionCode = options.versionCode?.toLong() ?: patchVersionCode(target)
        busyText = null
        patchLog.clear()
        patchProgress = 0
        patchFailure = null
        patchRunning = true
        runCatching {
            when (target) {
                is PatchTarget.Installed -> patchService.build(
                    target.app,
                    options,
                    onLog = { line -> patchLog.add(line) },
                    onProgress = { patchProgress = it },
                )

                is PatchTarget.FromFile -> patchService.buildFromFile(
                    target.file,
                    target.label,
                    options,
                    onLog = { line -> patchLog.add(line) },
                    onProgress = { patchProgress = it },
                )
            }
        }.onSuccess { outcome ->
            eventLog.add("打包完成 " + outcome.packageName + " · " + outcome.bootDex)
            patchProgress = 100
            result = outcome
            val record = PatchRecord(
                id = historyStore.newId(),
                time = System.currentTimeMillis(),
                packageName = outcome.packageName,
                label = outcome.label,
                versionName = recordVersionName,
                versionCode = recordVersionCode,
                sourceKey = sourceKey,
                fingerprint = fingerprint,
                optionSummary = summary,
                apkPath = outcome.apk.absolutePath,
                apkSize = outcome.apk.length(),
                apkModified = outcome.apk.lastModified(),
                bootDex = outcome.bootDex,
                originalFactory = outcome.originalFactory,
                config = outcome.config,
                forceOverwrite = outcome.forceOverwrite,
            )
            withContext(Dispatchers.IO) { historyStore.record(record) }
            currentRecordId = record.id
            historyTick++
            token++
        }.onFailure {
            eventLog.add("打包失败 " + it.message)
            patchLog.add("失败：" + it.message)
            patchFailure = it.message ?: "未知错误"
        }
        patchRunning = false
        logs = eventLog.entries()
    }

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyText = "正在读取 APK…"
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "input").apply { mkdirs() }
                val target = File(dir, "picked.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getPackageArchiveInfo(
                        file.absolutePath,
                        0,
                    )?.apply { applicationInfo?.sourceDir = file.absolutePath }
                }.getOrNull()
            }
            busyText = null
            if (info == null) {
                failure = "无法读取该 APK 的信息，文件可能已损坏"
            } else {
                val app = io.xpatch.model.AppInfo(
                    packageName = info.packageName,
                    label = info.applicationInfo?.loadLabel(context.packageManager).toString(),
                    versionName = info.versionName.orEmpty(),
                    isSystem = false,
                    enabledModules = 0,
                    icon = info.applicationInfo?.loadIcon(context.packageManager),
                    apkPath = file.absolutePath,
                    versionCode = @Suppress("DEPRECATION") info.versionCode.toLong(),
                )
                nav.navigate(
                    Page.Config(
                        PatchTarget.FromFile(
                            file = file,
                            label = app.label,
                            packageName = app.packageName,
                            versionName = app.versionName,
                            versionCode = app.versionCode,
                            icon = app.icon,
                        ),
                    ),
                )
            }
        }
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(configBackup.export().toByteArray())
                    }
                }
            }.onSuccess {
                eventLog.add("配置已导出")
                logs = eventLog.entries()
            }.onFailure {
                failure = it.message
            }
        }
    }

    val importConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyText = "正在导入配置…"
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }
            busyText = null
            if (text != null) {
                val outcome = configBackup.import(text)
                eventLog.add(outcome.message)
                logs = eventLog.entries()
                val reloaded = settingsStore.load()
                settings = reloaded
                onThemeModeChange(reloaded.themeMode)
                onUiStyleChange(reloaded.uiStyle)
                token++
            } else {
                eventLog.add("读取文件失败")
                logs = eventLog.entries()
            }
        }
    }

    val exportDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val granted = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.isSuccess
            }
            if (!granted) {
                eventLog.add("导出失败 无法写入所选目录")
                logs = eventLog.entries()
                return@launch
            }
            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "已选择目录"
            settingsStore.setExportTree(uri.toString(), label)
            settings = settingsStore.load()
            eventLog.add("导出目录已设为 " + label)
            logs = eventLog.entries()
            token++
        }
    }

    val logExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val text = pendingLogText
        val stream = pendingLogStream
        pendingLogText = null
        pendingLogStream = null
        val name = pendingExportFileName
        pendingExportFileName = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (text == null && stream == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val baseName = (name ?: "xpatch-logs").removeSuffix(".zip").removeSuffix(".txt")
                    val extras = buildMap {
                        put(
                            "Shizuku",
                            when {
                                ShizukuBridge.isAvailable() && ShizukuBridge.hasPermission() -> "已连接并授权"
                                ShizukuBridge.isAvailable() -> "可连接未授权"
                                else -> "不可用"
                            },
                        )
                        put("运行日志快照条数", (logsRuntime.snapshot?.totalLoaded ?: 0).toString())
                        put(
                            "运行日志文件数",
                            (logsRuntime.snapshot?.fileCount ?: 0).toString() +
                                "（已加载 " + (logsRuntime.snapshot?.loadedFiles ?: 0) + "）",
                        )
                    }
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                            fun putEntry(entryName: String, content: String) {
                                zip.putNextEntry(ZipEntry(entryName))
                                zip.write(content.toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                            }
                            if (stream != null) {
                                zip.putNextEntry(ZipEntry("logs/" + baseName + ".log"))
                                val buffered = java.io.BufferedWriter(
                                    java.io.OutputStreamWriter(zip, Charsets.UTF_8),
                                    64 * 1024,
                                )
                                var first = true
                                stream { line ->
                                    if (!first) buffered.write("\n")
                                    buffered.write(line)
                                    first = false
                                }
                                buffered.flush()
                                zip.closeEntry()
                            } else {
                                putEntry("logs/" + baseName + ".log", text.orEmpty())
                            }
                            putEntry("environment-info.txt", EnvInfoCollector.collect(context, extras))
                            io.xpatch.data.CrashRecorder.lastCrashFile(context)?.let { crash ->
                                runCatching {
                                    zip.putNextEntry(ZipEntry("last-crash.txt"))
                                    crash.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
                }
            }.onSuccess {
                eventLog.add("已导出日志包 " + (name ?: ""))
            }.onFailure {
                failure = "写入导出文件失败：" + (it.message ?: "未知错误")
                eventLog.add("日志导出失败 " + (it.message ?: ""))
            }
            logs = eventLog.entries()
        }
    }

    val loadRuntimeLogs: suspend () -> Unit = {
        if (!logsRefreshing) {
            logsRefreshing = true
            logsRuntime = logsRuntime.copy(loading = true, error = null)
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val targets = repository.patchedPackageNames()
                    val merged = if (ShizukuBridge.hasPermission()) {
                        runCatching {
                            logStore.loadFirstPageMerged(
                                reader = { command -> ShizukuBridge.exec(command) },
                                targets = targets,
                                fileReader = { path, maxBytes -> ShizukuBridge.readText(path, maxBytes) },
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    var acc = merged ?: runCatching { logStore.loadFirstPage(targets) }.getOrNull()
                    var guard = 0
                    while (acc != null && acc.hasMore && guard < MAX_LOG_PAGES) {
                        val next = runCatching { logStore.loadMore(acc) }.getOrNull()
                        if (next == null) break
                        acc = next
                        guard++
                    }
                    acc
                }
                logsRuntime = if (snapshot != null) {
                    RuntimeLogsState(
                        snapshot = snapshot,
                        loading = false,
                        error = null,
                        hasAccess = true,
                    )
                } else {
                    logsRuntime.copy(loading = false, error = "读取运行日志失败")
                }
            } catch (error: Throwable) {
                logsRuntime = logsRuntime.copy(
                    loading = false,
                    error = "读取运行日志失败：" + (error.message ?: "未知错误"),
                )
            } finally {
                logsRefreshing = false
            }
        }
    }

    val loadMoreRuntimeLogs: suspend () -> Unit = {
        val current = logsRuntime.snapshot
        if (!logsLoadingMore && current != null && current.hasMore) {
            logsLoadingMore = true
            logsRuntime = logsRuntime.copy(loadingMore = true)
            try {
                val next = withContext(Dispatchers.IO) {
                    runCatching { logStore.loadMore(current) }.getOrNull()
                }
                if (next != null) {
                    logsRuntime = logsRuntime.copy(
                        snapshot = next,
                        loadingMore = false,
                        error = null,
                    )
                } else {
                    logsRuntime = logsRuntime.copy(
                        loadingMore = false,
                        error = "加载更早的日志失败",
                    )
                }
            } finally {
                logsLoadingMore = false
            }        }
    }

    LaunchedEffect(Unit) {
        val (loadedProfiles, loadedDefaultId) = withContext(Dispatchers.IO) {
            signatureStore.allProfiles() to signatureStore.defaultProfileId()
        }
        signatures = loadedProfiles
        defaultSignatureId = loadedDefaultId
        logsRuntime = logsRuntime.copy(hasAccess = logStore.hasAccess())
    }

    LaunchedEffect(token) {
        val loadedPatched = repository.loadPatchedApps()
        val loadedModules = repository.loadModules()
        withContext(Dispatchers.Main) {
            patchedApps = loadedPatched
            modules = loadedModules
        }
    }

    LaunchedEffect(token, includeSystem) {
        val loadedPatchable = repository.loadPatchableApps(includeSystem)
        withContext(Dispatchers.Main) {
            patchableApps = loadedPatchable
        }
    }

    LaunchedEffect(token) {
        logs = withContext(Dispatchers.IO) { eventLog.entries() }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shizukuAvailable = ShizukuBridge.isAvailable()
                shizukuReady = shizukuAvailable && ShizukuBridge.hasPermission()
                val access = logStore.hasAccess()
                val hadAccess = logsRuntime.hasAccess
                logsRuntime = logsRuntime.copy(hasAccess = access)
                if (access && (logsRuntime.snapshot == null || !hadAccess) && !logsRefreshing) {
                    scope.launch { loadRuntimeLogs() }
                }
                scope.launch {
                    repository.invalidate()
                    val refreshed = repository.loadPatchedApps()
                    withContext(Dispatchers.Main) {
                        patchedApps = refreshed
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(nav.tab) {
        if (nav.tab == Tab.Logs) {
            logsState.tab = LogsTab.Runtime
            if (logsRuntime.snapshot == null && !logsRefreshing) {
                loadRuntimeLogs()
            }
        }
    }

    BackHandler(enabled = nav.canBack) { nav.back() }

    val title: String = when (val current = nav.page) {
        is Page.Home -> when (nav.tab) {
            Tab.Home -> "首页"
            Tab.Modules -> "模块"
            Tab.Logs -> "日志"
            Tab.Settings -> "设置"
        }
        is Page.Picker -> "选择应用"
        is Page.Signatures -> "签名管理"
        is Page.Config -> "Patch 配置"
        is Page.ModuleScope -> current.module.label
        is Page.AppScope -> current.app.label
        is Page.ModuleDetail -> current.module.label
        is Page.Repo -> "模块仓库"
        is Page.RepoDownloads -> "下载管理"
        is Page.RepoDetail -> current.module.name
        is Page.PatchHistory -> "打包历史"
        is Page.About -> "关于"
        is Page.Disclaimer -> "免责声明"
        is Page.LogModule -> if (current.framework) LogLayer.Framework.label
        else labelOf(current.module)
    }

    val backdrop = rememberLayerBackdrop()
    val miuixBehavior = if (miuix) MiuixScrollBehavior() else null
    val m3Behavior = if (miuix) null else TopAppBarDefaults.enterAlwaysScrollBehavior()
    val scrollConnection = miuixBehavior?.nestedScrollConnection
        ?: m3Behavior?.nestedScrollConnection
        ?: object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {}

    val showToast: (String) -> Unit = { message ->
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    val fallbackModuleInstall: (String, java.io.File, String) -> Unit = { name, file, pkg ->
        if (!InstallHelper.canInstall(context)) {
            eventLog.add("缺少「安装未知应用」权限，已停止安装 " + name)
            showToast("需要安装权限，请授权后重试")
            installBlocked = InstallBlock(pkg, true)
            logs = eventLog.entries()
        } else {
            val launched = runCatching { InstallHelper.openWith(context, file) }.getOrDefault(false)
            if (launched) {
                eventLog.add("已调起系统安装器安装 " + name + "，请在弹窗中确认")
                showToast("已调起系统安装器，请在弹窗中确认")
            } else {
                eventLog.add("没有可用的安装器 " + name)
                showToast("没有可用的安装器")
            }
            logs = eventLog.entries()
        }
    }

    val installModuleFile: (String, java.io.File, String) -> Unit = { name, file, pkg ->
        scope.launch {
            val viaShizuku = ShizukuBridge.isAvailable()
            busyText = if (viaShizuku) "正在通过 Shizuku 安装 " + name + "…" else null
            try {
                when (val step = ShizukuInstaller.install(context, file, pkg)) {
                    is ShizukuInstaller.Result.Success -> {
                        eventLog.add(step.message)
                        showToast(step.message)
                    }
                    is ShizukuInstaller.Result.Failure -> {
                        eventLog.add(step.message)
                        showToast(step.message)
                        fallbackModuleInstall(name, file, pkg)
                    }
                    ShizukuInstaller.Result.Unavailable -> {
                        eventLog.add("Shizuku 不可用，改用系统安装器安装 " + name)
                        fallbackModuleInstall(name, file, pkg)
                    }
                }
            } finally {
                busyText = null
            }
            logs = eventLog.entries()
            token++
        }
    }

    val fallbackInstall: (PatchService.Outcome) -> Unit = fallback@{ outcome ->
        if (!InstallHelper.canInstall(context)) {
            eventLog.add("缺少「安装未知应用」权限，已停止安装 " + outcome.packageName)
            showToast("需要安装权限，请授权后重试")
            installBlocked = InstallBlock(outcome.packageName, false)
            logs = eventLog.entries()
            return@fallback
        }
        val launched = runCatching { InstallHelper.openWith(context, outcome.apk) }.getOrDefault(false)
        if (launched) {
            eventLog.add("已调起系统安装器 " + outcome.packageName + "，请在弹窗中确认安装")
            showToast("已调起系统安装器，请在弹窗中确认")
        } else {
            eventLog.add("没有可用的安装器 " + outcome.packageName)
            showToast("没有可用的安装器")
        }
        logs = eventLog.entries()
    }

    val installOutcome: (PatchService.Outcome) -> Unit = { outcome ->
        scope.launch {
            val viaShizuku = ShizukuBridge.isAvailable()
            busyText = if (viaShizuku) "正在通过 Shizuku 安装…" else null
            try {
                val step = if (outcome.forceOverwrite) {
                    busyText = "正在强行覆盖安装…"
                    ShizukuInstaller.installForceOverwrite(context, outcome.apk, outcome.packageName)
                } else {
                    ShizukuInstaller.install(context, outcome.apk, outcome.packageName)
                }
                when (step) {
                    is ShizukuInstaller.Result.Success -> {
                        eventLog.add(step.message)
                        showToast(step.message)
                    }
                    is ShizukuInstaller.Result.Failure -> {
                        eventLog.add(step.message)
                        if (outcome.forceOverwrite) {
                            failure = step.message + "\n\n请确认 Shizuku 已授权，或改为普通打包后再安装。"
                        } else {
                            showToast(step.message)
                            fallbackInstall(outcome)
                        }
                    }
                    ShizukuInstaller.Result.Unavailable -> {
                        if (outcome.forceOverwrite) {
                            eventLog.add("强行覆盖安装缺少 Shizuku，已停止 " + outcome.packageName)
                            failure = "强行覆盖安装需要 Shizuku，当前不可用。\n\n请先安装并授权 Shizuku，然后重新安装；该产物始终按强行覆盖安装处理。"
                        } else {
                            eventLog.add("Shizuku 不可用，改用系统安装器 " + outcome.packageName)
                            fallbackInstall(outcome)
                        }
                    }
                }
            } finally {
                busyText = null
            }
            logs = eventLog.entries()
            token++
        }
    }

    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        val target = uninstallingTarget
        uninstallingTarget = null
        if (target == null) return@rememberLauncherForActivityResult
        val stillInstalled = runCatching {
            context.packageManager.getPackageInfo(target.packageName, 0)
        }.isSuccess
        if (stillInstalled) {
            eventLog.add("已取消卸载 " + target.packageName)
            logs = eventLog.entries()
            pendingInstall = target
            return@rememberLauncherForActivityResult
        }
        eventLog.add("已卸载 " + target.packageName + "，继续安装")
        logs = eventLog.entries()
        scope.launch {
            repository.invalidate()
            val refreshed = repository.loadPatchedApps()
            withContext(Dispatchers.Main) {
                patchedApps = refreshed
                token++
            }
        }
        installOutcome(target)
    }

    val repoSnapshot = produceState(
        initialValue = RepoInstallSnapshot.Empty,
        token,
        repoModules,
        patchedApps,
    ) {
        value = withContext(Dispatchers.IO) {
            buildRepoSnapshot(context, repoStore, repoModules, patchedApps)
        }
    }

    val onRepoDownloadProgress: (Int) -> Unit = { percent ->
        if (percent != repoDownloadProgress) repoDownloadProgress = percent
    }

    val downloadTasks by DownloadCenter.tasks.collectAsState()
    LaunchedEffect(downloadTasks, repoDownloadTaskId) {
        val id = repoDownloadTaskId ?: return@LaunchedEffect
        val task = downloadTasks[id] ?: return@LaunchedEffect
        if (task.percent != repoDownloadProgress) {
            repoDownloadProgress = task.percent
        }
    }
    LaunchedEffect(Unit) {
        DownloadCenter.events.collect { event ->
            when (event) {
                is DownloadCenter.Event.ModuleReady -> {
                    repoDownloadPkg = null
                    repoDownloadTaskId = null
                    repoDownloadDoneTick++
                    eventLog.add("已下载 " + event.module.name)
                    showToast("已下载 " + event.module.name)
                    installModuleFile(event.module.name, event.file, event.module.packageName)
                    token++
                }

                is DownloadCenter.Event.Failed -> {
                    repoDownloadPkg = null
                    repoDownloadTaskId = null
                    eventLog.add(event.message)
                }
            }
        }
    }

    val liquidBottomBar = settings.bottomBarStyle == SettingsStore.BOTTOM_BAR_LIQUID

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (miuix) {
                MiuixTopAppBar(
                    title = title,
                    scrollBehavior = miuixBehavior,
                    navigationIcon = {
                        if (nav.page !is Page.Home) {
                            MiuixIconButton(onClick = { nav.back() }) {
                                MiuixIcon(
                                    imageVector = Icons.Rounded.ArrowBack,
                                    contentDescription = "返回",
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    actions = {
                        if (nav.page is Page.Home && nav.tab == Tab.Home) {
                            MiuixIconButton(onClick = { nav.navigate(Page.PatchHistory) }) {
                                MiuixIcon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = "打包历史",
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    scrollBehavior = m3Behavior,
                    navigationIcon = {
                        if (nav.page is Page.Home) {
                            Unit
                        } else {
                            IconButton(onClick = { nav.back() }) {
                                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        if (nav.page is Page.Home && nav.tab == Tab.Home) {
                            IconButton(onClick = { nav.navigate(Page.PatchHistory) }) {
                                Icon(Icons.Rounded.History, contentDescription = "打包历史")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        floatingActionButton = {
            if (nav.page is Page.Home && nav.tab == Tab.Home) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("添加应用") },
                )
            }
        },
        bottomBar = {
            if (nav.page is Page.Home) {
                Box(Modifier.background(androidx.compose.ui.graphics.Color.Transparent)) {
                    when {
                        liquidBottomBar -> LiquidBar(nav.tab, backdrop) { nav.select(it) }

                        else -> StandardBottomBar(nav.tab) { nav.select(it) }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollConnection)
                .padding(
                    androidx.compose.foundation.layout.PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = 0.dp,
                    ),
                )
                .background(
                    if (miuix) MiuixTheme.colorScheme.background
                    else MaterialTheme.colorScheme.background,
                )
                .then(if (liquidBottomBar) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            AnimatedContent(
                targetState = nav.page,
                transitionSpec = {
                    (slideInHorizontally(animationSpec = tween(160)) { it / 6 } + fadeIn(tween(160)))
                        .togetherWith(
                            slideOutHorizontally(animationSpec = tween(120)) { -it / 6 } + fadeOut(tween(120)),
                        )
                },
                label = "pageNav",
            ) { current ->
            AdaptiveWidth {
            val pageKey = navKeyOf(current, nav.tab)
            val liveKeys = remember(nav.page, nav.tab, nav.stackSize) {
                buildSet {
                    add(navKeyOf(nav.page, nav.tab))
                    nav.stackPages.forEach { add(navKeyOf(it, nav.tab)) }
                }
            }
            remember(liveKeys) { scrollStore.retain(liveKeys); liveKeys }
            CompositionLocalProvider(
                LocalPageKey provides pageKey,
                LocalScrollStateStore provides scrollStore,
            ) {
            key(pageKey) {
            when (current) {
                is Page.Home -> when (nav.tab) {
                    Tab.Home -> HomeScreen(
                        apps = patchedApps,
                        refreshing = homeRefreshing,
                        onRefresh = {
                            scope.launch {
                                homeRefreshing = true
                                repository.invalidate()
                                patchedApps = repository.loadPatchedApps()
                                homeRefreshing = false
                                token++
                            }
                        },
                        onOpen = { nav.navigate(Page.AppScope(it)) },
                    )

                    Tab.Modules -> ModulesScreen(
                        modules = modules,
                        query = query,
                        onQueryChange = { query = it },
                        onOpen = { nav.navigate(Page.ModuleScope(it)) },
                        refreshing = modulesRefreshing,
                        onRefresh = {
                            scope.launch {
                                modulesRefreshing = true
                                repository.invalidate()
                                modules = repository.loadModules()
                                modulesRefreshing = false
                                token++
                            }
                        },
                        onOpenRepo = {
                            eventLog.add("正在获取仓库列表…")
                            nav.navigate(Page.Repo)
                            if (repoModules.isEmpty() && !repoBusy) {
                                scope.launch {
                                    repoBusy = true
                                    repoError = null
                                    runCatching { repoStore.fetch() }
                                        .onSuccess { repoModules = it; eventLog.add("仓库：获取到 " + it.size + " 个模块") }
                                        .onFailure { repoError = it.message ?: "仓库获取失败"; eventLog.add("仓库获取失败 " + it.message) }
                                    repoBusy = false
                                }
                            }
                        },
                    )

                    Tab.Logs -> LogsScreen(
                        runtime = logsRuntime,
                        operations = logs,
                        state = logsState,
                        labelOf = labelOf,
                        onRefreshRuntime = { scope.launch { loadRuntimeLogs() } },
                        onRequestStorage = { openAllFilesSettings(context) },
                        onLoadMoreRuntime = { scope.launch { loadMoreRuntimeLogs() } },
                        onClearRuntime = {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { logStore.clear() }
                            }
                            val cleared = result.getOrElse { error ->
                                RuntimeLogStore.ClearResult(
                                    deletedFiles = 0,
                                    affectedApps = 0,
                                    failed = listOf(
                                        RuntimeLogStore.Failure(
                                            path = "logs",
                                            reason = error.message ?: "未知错误",
                                        ),
                                    ),
                                )
                            }
                            loadRuntimeLogs()
                            eventLog.add(
                                if (cleared.ok) "已清空运行日志 " + cleared.deletedFiles + " 个文件"
                                else "清空运行日志部分失败 " + cleared.failed.size + " 处",
                            )
                            logs = eventLog.entries()
                            cleared
                        },
                        onClearOperations = {
                            val ok = eventLog.clear()
                            if (ok) eventLog.add("已清空操作日志")
                            logs = eventLog.entries()
                            ok
                        },
                        onExport = { fileName, text, stream ->
                            pendingExportFileName = fileName
                            pendingLogText = text
                            pendingLogStream = stream
                            logExportLauncher.launch(fileName)
                        },
                        onOpenModule = { group ->
                            logDetailState = LogDetailState()
                            nav.navigate(
                                Page.LogModule(
                                    groupKey = group.key,
                                    module = group.module,
                                    framework = group.framework,
                                    targets = group.targets,
                                ),
                            )
                        },
                        listLogFiles = {
                            val targets = repository.patchedPackageNames()
                            withContext(Dispatchers.IO) {
                                if (ShizukuBridge.hasPermission()) {
                                    val shell = runCatching {
                                        logStore.listShellFiles(
                                            reader = { command -> ShizukuBridge.exec(command) },
                                            targets = targets,
                                        )
                                    }.getOrDefault(emptyList())
                                    if (shell.isNotEmpty()) shell
                                    else runCatching { logStore.listLocalFiles(targets) }.getOrNull()?.first ?: emptyList()
                                } else {
                                    runCatching { logStore.listLocalFiles(targets) }.getOrNull()?.first ?: emptyList()
                                }
                            }
                        },
                        deleteLogFiles = { paths ->
                            withContext(Dispatchers.IO) {
                                if (ShizukuBridge.hasPermission()) {
                                    logStore.deleteViaShell(
                                        reader = { command -> ShizukuBridge.exec(command) },
                                        paths = paths,
                                    )
                                } else {
                                    logStore.clearFiles(paths)
                                }
                            }
                        },
                    )

                    Tab.Settings -> SettingsScreen(
                        settings = settings,
                        defaultSignatureName = signatures.firstOrNull { it.id == defaultSignatureId }?.name ?: "XPatch",
                        exportLabel = settings.exportTreeLabel,
                        shizukuReady = shizukuReady,
                        shizukuAvailable = shizukuAvailable,
                        onRequestShizuku = {
                            val wasReady = shizukuReady
                            shizukuAvailable = ShizukuBridge.isAvailable()
                            shizukuReady = shizukuAvailable && ShizukuBridge.hasPermission()
                            if (shizukuReady) {
                                if (!wasReady) eventLog.add("Shizuku 已授权")
                                token++
                            } else if (!shizukuAvailable) {
                                failure = "未检测到 Shizuku。请先安装并启动 Shizuku，或使用「打开方式」自行选择安装器。"
                            } else {
                                ShizukuBridge.requestPermission { granted ->
                                    shizukuReady = granted
                                    eventLog.add(if (granted) "Shizuku 已授权" else "Shizuku 授权被拒绝")
                                    token++
                                }
                            }
                        },
                        onRecheckShizuku = {
                            val wasReady = shizukuReady
                            val wasAvailable = shizukuAvailable
                            shizukuAvailable = ShizukuBridge.isAvailable()
                            shizukuReady = shizukuAvailable && ShizukuBridge.hasPermission()
                            if (shizukuReady != wasReady || shizukuAvailable != wasAvailable) {
                                eventLog.add(
                                    when {
                                        shizukuReady -> "Shizuku 已授权"
                                        shizukuAvailable -> "Shizuku 可用但未授权"
                                        else -> "未检测到 Shizuku"
                                    },
                                )
                            }
                            token++
                        },
                        onOpenSignatures = { nav.navigate(Page.Signatures) },
                        onThemeChange = {
                            settingsStore.setThemeMode(it)
                            settings = settings.copy(themeMode = it)
                            onThemeModeChange(it)
                        },
                        onUiStyleChange = {
                            settingsStore.setUiStyle(it)
                            settings = settings.copy(uiStyle = it)
                            onUiStyleChange(it)
                        },                        onShowSystemChange = {
                            settingsStore.setShowSystemApps(it)
                            settings = settings.copy(showSystemApps = it)
                            token++
                        },
                        onBottomBarChange = {
                            settingsStore.setBottomBarStyle(it)
                            settings = settings.copy(bottomBarStyle = it)
                        },

                        onPickExportDir = { exportDirLauncher.launch(null) },
                        onClearExportDir = {
                            settingsStore.clearExportTree()
                            settings = settings.copy(exportTreeUri = "", exportTreeLabel = "")
                        },
                        onExportConfig = { exportConfigLauncher.launch("xpatch-backup.json") },
                        onImportConfig = { importConfigLauncher.launch(arrayOf("application/json", "*/*")) },
                        onHistoryLimitChange = { limit ->
                            settingsStore.setPatchHistoryLimit(limit)
                            settings = settings.copy(patchHistoryLimit = limit)
                            historyTick++
                        },
                        onDeleteArtifactChange = { delete ->
                            settingsStore.setDeleteArtifactOnRemove(delete)
                            settings = settings.copy(deleteArtifactOnRemove = delete)
                        },
                        onSkipDeletePromptChange = { skip ->
                            settingsStore.setSkipArtifactDeletePrompt(skip)
                            settings = settings.copy(skipArtifactDeletePrompt = skip)
                        },
                        onOpenDisclaimer = { nav.navigate(Page.Disclaimer) },
                        onOpenAbout = { nav.navigate(Page.About) },
                    )
                }

                is Page.Repo -> RepoPageScreen(
                    modules = repoModules,
                    busy = repoBusy,
                    snapshot = repoSnapshot.value,
                    readmeText = { pkg -> repoStore.readmeText(pkg) },
                    downloadingPkg = repoDownloadPkg,
                    downloadProgress = { repoDownloadProgress },
                    downloadDoneTick = repoDownloadDoneTick,
                    onOpenDownloads = { nav.replace(Page.RepoDownloads) },
                    onRefresh = {
                        scope.launch {
                            repoBusy = true
                            repoError = null
                            runCatching { repoStore.fetch() }
                                .onSuccess { repoModules = it; eventLog.add("仓库：获取到 " + it.size + " 个模块") }
                                .onFailure { repoError = it.message ?: "仓库获取失败"; eventLog.add("仓库获取失败") }
                            repoBusy = false
                        }
                    },
                    onOpenDetail = { module ->
                        runCatching { nav.navigate(Page.RepoDetail(module)) }
                            .onFailure { eventLog.add("打开详情失败 " + (it.message ?: "")) }
                    },
                    onAction = { module ->
                        scope.launch {
                            repoBusy = true
                            repoError = null
                            runCatching {
                                val cached = repoStore.cachedApk(module)
                                if (cached != null) {
                                    installModuleFile(module.name, cached, module.packageName)
                                } else {
                                    repoDownloadPkg = module.packageName
                                    repoDownloadProgress = 0
                                    repoDownloadTaskId = DownloadCenter.startModule(context, repoStore, module)
                                }
                            }.onFailure {
                                repoError = it.message ?: "下载失败"
                                eventLog.add("下载失败 " + (it.message ?: ""))
                            }
                            busyText = null
                            repoBusy = false
                            token++
                        }
                    },
                )

                is Page.RepoDownloads -> DownloadsScreen(
                    modules = repoModules,
                    entries = repoSnapshot.value.cache.entries,
                    snapshot = repoSnapshot.value,
                    busy = repoBusy,
                    onInstall = { entry ->
                        val module = repoModules.firstOrNull { it.packageName == entry.packageName }
                        installModuleFile(
                            module?.name ?: entry.fileName,
                            java.io.File(entry.path),
                            entry.packageName,
                        )
                    },
                    onDelete = { entry ->
                        val ok = runCatching { repoStore.removeDownload(entry.packageName) }.getOrDefault(false)
                        eventLog.add(if (ok) "已删除 " + entry.packageName + " 的下载缓存" else "删除失败 " + entry.packageName)
                        showToast(if (ok) "已删除" else "删除失败")
                        logs = eventLog.entries()
                        token++
                    },
                    onClearAll = {
                        runCatching { repoStore.clearCache() }
                        eventLog.add("已清空模块下载缓存")
                        showToast("已清空模块下载缓存")
                        logs = eventLog.entries()
                        token++
                    },
                    onOpenRepo = { nav.replace(Page.Repo) },
                    onOpenDetail = { module ->
                        runCatching { nav.navigate(Page.RepoDetail(module)) }
                            .onFailure { eventLog.add("打开详情失败 " + (it.message ?: "")) }
                    },
                )

                is Page.RepoDetail -> {
                    var detailReadme by remember(current.module.packageName) { mutableStateOf<String?>(null) }
                    LaunchedEffect(current.module.packageName) {
                        detailReadme = withContext(Dispatchers.IO) {
                            repoStore.readmeText(current.module.packageName)
                        }
                    }
                    val detailSnapshot = repoSnapshot.value
                    RepoDetailScreen(
                        module = current.module,
                        installed = detailSnapshot.versionNameOf(current.module.packageName),
                        installedVersionCode = detailSnapshot.versionCodeOf(current.module.packageName),
                        cached = detailSnapshot.isCached(current.module),
                        busy = repoBusy,
                        readme = detailReadme,
                        onAction = {
                            scope.launch {
                                repoBusy = true
                                repoError = null
                                runCatching {
                                    val cached = repoStore.cachedApk(current.module)
                                    if (cached != null) {
                                        installModuleFile(current.module.name, cached, current.module.packageName)
                                    } else {
                                        repoDownloadPkg = current.module.packageName
                                        repoDownloadProgress = 0
                                        repoDownloadTaskId = DownloadCenter.startModule(context, repoStore, current.module)
                                    }
                                }.onFailure {
                                    repoError = it.message ?: "下载失败"
                                    eventLog.add("下载失败 " + (it.message ?: ""))
                                }
                                busyText = null
                                repoBusy = false
                                token++
                            }
                        },
                        onOpenStore = { nav.navigate(Page.Repo) },
                    )
                }

                is Page.LogModule -> {
                    val targets = remember(current) { current.targets.toHashSet() }
                    val groupEntries = remember(logsRuntime, current) {
                        logsRuntime.entries.filter { entry ->
                            val targetHit = entry.target in targets
                            if (current.framework) targetHit && entry.framework
                            else targetHit && entry.module == current.module
                        }
                    }
                    LogModuleScreen(
                        module = current.module,
                        framework = current.framework,
                        targets = current.targets,
                        entries = groupEntries,
                        labelOf = labelOf,
                        state = logDetailState,
                        listState = logDetailList,
                        onCopy = { entry ->
                            clipboard.setText(AnnotatedString(entry.fullText))
                        },
                    )
                }

                is Page.Picker -> PickerScreen(
                    apps = patchableApps,
                    query = pickerQuery,
                    onQueryChange = { pickerQuery = it },
                    onPick = { nav.navigate(Page.Config(PatchTarget.Installed(it))) },
                    includeSystem = includeSystem,
                    onToggleSystem = { includeSystem = it },
                )

                is Page.Config -> ConfigScreen(
                    target = current.target,
                    defaults = settings,
                    signatures = signatures,
                    defaultSignatureId = defaultSignatureId,
                    busy = busyText != null,
                    forceOverwriteReady = shizukuReady,
                    forceOverwriteBlockReason = remember(shizukuReady, shizukuAvailable) {
                        ShizukuInstaller.forceOverwriteBlockReason()
                    },
                    onForceOverwriteChange = { on ->
                        settingsStore.setForceOverwrite(on)
                        settings = settings.copy(forceOverwrite = on)
                        eventLog.add(if (on) "已开启强行覆盖安装" else "已关闭强行覆盖安装")
                        logs = eventLog.entries()
                    },
                    findReusable = { options ->
                        val pkg = patchPackageName(current.target)
                        if (pkg.isBlank()) {
                            null
                        } else {
                            val fingerprint = PatchFingerprint.of(patchSourceKey(current.target), options)
                            historyState.value.records.firstOrNull {
                                it.packageName == pkg && it.fingerprint == fingerprint && it.hasArtifact()
                            }
                        }
                    },
                    onReuse = { record ->
                        patchLog.clear()
                        patchLog.add("复用已打包产物：" + record.apkPath)
                        patchProgress = 100
                        patchFailure = null
                        patchRunning = false
                        result = patchService.outcomeOf(record)
                        currentRecordId = record.id
                        eventLog.add("复用打包产物 " + record.label + " " + record.versionName)
                        logs = eventLog.entries()
                    },
                    onStart = { options ->
                        scope.launch { startPatch(current.target, options) }
                    },
                    onPickSignature = { profile ->
                        signatureStore.setDefaultProfile(profile.id)
                        defaultSignatureId = profile.id
                        token++
                    },
                )

                is Page.PatchHistory -> PatchHistoryScreen(
                    records = historyState.value.records,
                    totalSize = historyState.value.totalSize,
                    limit = settings.patchHistoryLimit,
                    refreshing = false,
                    deletePromptRequired = !settings.skipArtifactDeletePrompt,
                    defaultDeleteArtifact = settings.deleteArtifactOnRemove,
                    onRefresh = { historyTick++ },
                    onInstall = { record ->
                        installOutcome(patchService.outcomeOf(record))
                    },
                    onShare = { record ->
                        val shared = runCatching {
                            InstallHelper.shareApk(context, patchService.outcomeOf(record).apk)
                        }.getOrDefault(false)
                        showToast(if (shared) "已调起系统分享" else "没有可分享安装包的应用")
                    },
                    onExport = { record ->
                        scope.launch {
                            val outcome = patchService.outcomeOf(record)
                            val saved = withContext(Dispatchers.IO) {
                                runCatching { patchService.export(outcome).absolutePath }
                            }
                            saved.onSuccess { path ->
                                withContext(Dispatchers.IO) {
                                    historyStore.addExport(
                                        record.id,
                                        PatchExportRef(path = path, name = File(path).name, time = System.currentTimeMillis()),
                                    )
                                }
                                historyTick++
                                showToast("已导出 " + File(path).name)
                            }.onFailure {
                                showToast("导出失败：" + (it.message ?: "未知错误"))
                            }
                        }
                    },
                    onDeleteExport = { record, ref ->
                        scope.launch {
                            withContext(Dispatchers.IO) { historyStore.removeExport(record.id, ref.path, true) }
                            historyTick++
                            showToast("已删除导出文件")
                        }
                    },
                    onDelete = { record, deleteArtifact, rememberChoice ->
                        scope.launch {
                            withContext(Dispatchers.IO) { historyStore.remove(record.id, deleteArtifact) }
                            if (rememberChoice) {
                                settingsStore.setDeleteArtifactOnRemove(deleteArtifact)
                                settingsStore.setSkipArtifactDeletePrompt(true)
                                settings = settings.copy(
                                    deleteArtifactOnRemove = deleteArtifact,
                                    skipArtifactDeletePrompt = true,
                                )
                            }
                            if (currentRecordId == record.id) currentRecordId = null
                            historyTick++
                            showToast(if (deleteArtifact) "已删除记录与产物" else "已删除记录")
                        }
                    },
                    onClearMissing = {
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) { historyStore.clearMissing() }
                            historyTick++
                            showToast(if (removed > 0) "已清理 " + removed + " 条无效记录" else "没有需要清理的记录")
                        }
                    },
                    onOpenSettings = { nav.select(Tab.Settings) },
                )

                is Page.Signatures -> SignaturesScreen(
                    profiles = signatures,
                    defaultId = defaultSignatureId,
                    onSelect = { profile ->
                        signatureStore.setDefaultProfile(profile.id)
                        defaultSignatureId = profile.id
                    },
                    onDelete = { profile ->
                        signatureStore.deleteCustom(profile.id)
                        signatures = signatureStore.allProfiles()
                        defaultSignatureId = signatureStore.defaultProfileId()
                    },
                    onOpenPicker = { },
                )

                is Page.About -> AboutScreen(onOpenDisclaimer = { nav.navigate(Page.Disclaimer) })
                is Page.Disclaimer -> DisclaimerScreen()

                is Page.ModuleScope -> ModuleScopeScreen(
                    module = current.module,
                    apps = patchedApps,
                    scopeOf = { repository.appsOfModule(current.module.packageName) },
                    onToggle = { app, inScope ->
                        repository.setInScope(current.module.packageName, app.packageName, inScope)
                        eventLog.add(
                            if (inScope) {
                                "作用域 + ${current.module.packageName} → ${app.packageName}"
                            } else {
                                "作用域 - ${current.module.packageName} → ${app.packageName}"
                            },
                        )
                        token++
                        syncScopeFile(app.packageName)
                        RestartCenter.request(
                            context,
                            app.packageName,
                            app.label,
                            "模块作用域已更新，重启 " + app.label + " 后生效",
                        )
                    },
                )

                is Page.AppScope -> AppScopeScreen(
                    app = current.app,
                    modules = modules,
                    apiOf = remember(token, current.app.packageName) {
                        { module: ModuleInfo -> moduleApiStore.apiOf(current.app.packageName, module.packageName) }
                    },
                    onApiChange = { module, api ->
                        moduleApiStore.setApi(current.app.packageName, module.packageName, api)
                        eventLog.add("API ${module.packageName} → ${ModuleApiStore.label(api)}，重启目标应用后生效")
                        token++
                        syncScopeFile(current.app.packageName)
                        RestartCenter.request(
                            context,
                            current.app.packageName,
                            current.app.label,
                            "模块 " + module.label + " 的 API 级别已改为 " + ModuleApiStore.label(api) + "，重启后生效",
                        )
                    },
                    onLaunch = {
                        runCatching { ShizukuBridge.launch(context, current.app.packageName) }
                            .onSuccess { eventLog.add("已启动 ${current.app.packageName}") }
                            .onFailure { eventLog.add("启动失败 ${it.message}") }
                        token++
                    },
                    onForceStop = {
                        runCatching { ShizukuBridge.forceStop(current.app.packageName) }
                            .onSuccess { eventLog.add("已强制停止 ${current.app.packageName}") }
                            .onFailure { eventLog.add("停止失败：请先授权 Shizuku") }
                        token++
                    },
                    onRestart = {
                        eventLog.add("正在重启 ${current.app.packageName}")
                        runCatching { ShizukuBridge.restart(context, current.app.packageName) }
                            .onSuccess { eventLog.add("已重启 ${current.app.packageName}") }
                            .onFailure { eventLog.add("重启失败：请先授权 Shizuku") }
                        token++
                    },
                    onDexopt = {
                        scope.launch {
                            busyText = "正在执行 Dexopt…"
                            val ok = runCatching { ShizukuBridge.dexopt(current.app.packageName) }.getOrDefault(false)
                            busyText = null
                            eventLog.add(
                                if (ok) "Dexopt 优化完成 ${current.app.packageName}"
                                else "Dexopt 失败：请先授权 Shizuku",
                            )
                            token++
                        }
                    },
                    onAppInfo = { ShizukuBridge.openAppDetails(context, current.app.packageName) },
                    onViewLogs = { nav.openLogs() },
                    onUpdateCore = { nav.navigate(Page.Config(PatchTarget.Installed(current.app))) },
                    meta = remember(current.app.packageName, token) {
                        runCatching {
                            val pi = context.packageManager.getPackageInfo(current.app.packageName, 0)
                            val bytes = File(pi.applicationInfo?.sourceDir ?: "").length()
                            val size = when {
                                bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
                                else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
                            }
                            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(pi.lastUpdateTime))
                            AppScopeMeta(time, size)
                        }.getOrNull()
                    },
                    inScope = { module -> repository.isInScopeFor(module.packageName, current.app.packageName) },
                    onOpenModule = { module ->
                        nav.navigate(Page.ModuleDetail(module, current.app))
                    },
                    listState = scopeListState,
                    query = scopeQuery,
                    onQueryChange = { scopeQuery = it },
                    sort = scopeSort,
                    onSortChange = { scopeSort = it },
                    reversed = scopeReversed,
                    onReversedChange = { scopeReversed = it },
                    onToggle = { module, inScope ->
                        repository.setInScope(module.packageName, current.app.packageName, inScope)
                        eventLog.add(
                            if (inScope) "作用域 + ${module.packageName} → ${current.app.packageName}"
                            else "作用域 - ${module.packageName} → ${current.app.packageName}",
                        )
                        token++
                        syncScopeFile(current.app.packageName)
                        RestartCenter.request(
                            context,
                            current.app.packageName,
                            current.app.label,
                            "模块作用域已更新，重启 " + current.app.label + " 后生效",
                        )
                    },
                )

                is Page.ModuleDetail -> ModuleDetailScreen(
                    module = current.module,
                    appLabel = current.app.label,
                    checked = repository.isInScopeFor(current.module.packageName, current.app.packageName),
                    scopeCount = repository.countApps(current.module.packageName),
                    onToggle = { inScope ->
                        repository.setInScope(current.module.packageName, current.app.packageName, inScope)
                        eventLog.add(
                            if (inScope) "作用域 + ${current.module.packageName} → ${current.app.packageName}"
                            else "作用域 - ${current.module.packageName} → ${current.app.packageName}",
                        )
                        token++
                        syncScopeFile(current.app.packageName)
                        RestartCenter.request(
                            context,
                            current.app.packageName,
                            current.app.label,
                            "模块作用域已更新，重启 " + current.app.label + " 后生效",
                        )
                    },
                )
            }
            }
            }
            }
            }
        }
    }

    startupUpdate?.let { release ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { startupUpdate = null },
            title = "发现新版本 v" + release.version,
            actions = listOf(
                DialogAction("稍后") { startupUpdate = null },
                DialogAction("去更新") {
                    startupUpdate = null
                    nav.navigate(Page.About)
                },
            ),
        ) {
            Text(
                "当前版本 v" + io.xpatch.BuildConfig.VERSION_NAME + "，可在「关于」页下载安装。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (showAddDialog) {        XPatchDialog(
            miuix = miuix,
            onDismiss = { showAddDialog = false },
            title = "添加应用",
            actions = listOf(DialogAction("取消") { showAddDialog = false }),
        ) {
            Text(
                "选择已安装的应用",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showAddDialog = false
                        nav.navigate(Page.Picker)
                    }
                    .padding(vertical = 14.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(
                "选择 APK 文件",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showAddDialog = false
                        apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
                    }
                    .padding(vertical = 14.dp),
            )
        }
    }

    busyText?.let { text ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { },
            title = "处理中",
            actions = emptyList(),
            dismissible = false,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        }
    }

    if (patchRunning || result != null || patchFailure != null) {
        PatchProgressDialog(
            state = PatchProgress(
                running = patchRunning,
                progress = patchProgress,
                logs = patchLog.toList(),
                title = "正在打包",
            ),
            failed = patchFailure,
            resultPath = result?.apk?.absolutePath,
            onExport = {
                val outcome = result ?: return@PatchProgressDialog
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { patchService.export(outcome).absolutePath }
                    }
                    saved.onSuccess { path ->
                        val recordId = currentRecordId
                        if (recordId != null) {
                            withContext(Dispatchers.IO) {
                                historyStore.addExport(
                                    recordId,
                                    PatchExportRef(path = path, name = File(path).name, time = System.currentTimeMillis()),
                                )
                            }
                            historyTick++
                        }
                        patchLog.add("已导出到 " + path)
                        eventLog.add("已导出到 " + path)
                        showToast("已导出 " + path.substringAfterLast('/'))
                    }.onFailure {
                        val msg = "导出失败：" + (it.message ?: "未知错误")
                        patchLog.add(msg)
                        eventLog.add(msg)
                        showToast(msg)
                    }
                    logs = eventLog.entries()
                    token++
                }
            },
            onShare = {
                val outcome = result ?: return@PatchProgressDialog
                val shared = runCatching { InstallHelper.shareApk(context, outcome.apk) }
                    .getOrDefault(false)
                if (shared) {
                    eventLog.add("已调起系统分享 " + outcome.packageName)
                    showToast("已调起系统分享")
                } else {
                    eventLog.add("没有可分享安装包的应用 " + outcome.packageName)
                    showToast("没有可分享安装包的应用")
                }
                logs = eventLog.entries()
                result = null
                patchLog.clear()
            },
            onInstall = {
                val outcome = result ?: return@PatchProgressDialog
                val matched = InstallHelper.signatureMatches(
                    context,
                    outcome.packageName,
                    signatureStore.certificateBytes(signatureStore.defaultProfile()),
                )
                result = null
                patchLog.clear()
                token++
                if (matched == false) {
                    pendingInstall = outcome
                } else {
                    installOutcome(outcome)
                }
            },
            onDismiss = {
                result = null
                patchFailure = null
                patchLog.clear()
            },
        )
    }

    pendingInstall?.let { outcome ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { pendingInstall = null },
            title = "签名不一致",
            actions = listOf(
                DialogAction("返回") { pendingInstall = null },
                DialogAction("卸载原应用", destructive = true) {
                    val target = pendingInstall
                    pendingInstall = null
                    pendingUninstall = target
                },
                DialogAction("继续安装") {
                    val target = pendingInstall
                    pendingInstall = null
                    if (target != null) installOutcome(target)
                },
            ),
        ) {
            Text(
                if (outcome.forceOverwrite) {
                    "该产物使用 XPatch 签名，与已安装应用签名不同。\n「继续安装」会先保留数据卸载原应用，再安装新包（需要 Shizuku）。\n「卸载原应用」调用系统卸载，数据会被删除。\n\n点「返回」会保留打包结果，不必重新打包。"
                } else {
                    "内置证书与已安装应用的签名不同，直接覆盖安装会失败。\n「卸载原应用」调用系统卸载，卸载完成后自动继续安装（数据会被删除）。\n也可以改用其他签名重新打包。\n\n点「返回」会保留打包结果，不必重新打包。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    pendingUninstall?.let { outcome ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { pendingUninstall = null },
            title = "卸载原应用？",
            actions = listOf(
                DialogAction("取消") { pendingUninstall = null },
                DialogAction("继续卸载", destructive = true) {
                    uninstallingTarget = outcome
                    pendingUninstall = null
                    val launched = runCatching {
                        uninstallLauncher.launch(
                            InstallHelper.uninstallIntent(outcome.packageName),
                        )
                    }.isSuccess
                    if (!launched) {
                        uninstallingTarget = null
                        failure = "没有可用的卸载界面，请在系统设置中手动卸载 " + outcome.packageName
                    }
                },
            ),
        ) {
            Text(
                "将调用系统卸载界面。" +
                    "卸载会连同该应用的全部数据一起删除，且无法恢复。\n\n" +
                    "如果希望保留数据，请取消并改用「强行覆盖安装」（需要 Shizuku）。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    installBlocked?.let { pending ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { installBlocked = null },
            title = "需要安装权限",
            actions = listOf(
                DialogAction("返回") { installBlocked = null },
                DialogAction("去授权") {
                    InstallHelper.openInstallPermission(context)
                    installBlocked = null
                },
            ),
        ) {
            Text(
                if (pending.fromRepo) {
                    "系统尚未允许 XPatch 安装应用，点「去授权」开启后即可安装 " +
                        pending.packageName + "。\n授权返回后会自动继续。"
                } else {
                    "系统尚未允许 XPatch 安装应用，点「去授权」开启后即可安装 " +
                        pending.packageName + "。\n授权返回后会自动继续安装，打包结果会保留。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    val restartRequest by RestartCenter.dialog.collectAsState()
    restartRequest?.let { request ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { RestartCenter.dismissDialog() },
            title = "需要重启 " + request.label,
            actions = listOf(
                DialogAction("稍后处理") { RestartCenter.dismissDialog() },
                DialogAction("立即重启") { RestartCenter.restartNow(context, request.packageName) },
            ),
        ) {
            Text(request.reason, style = MaterialTheme.typography.bodySmall)
        }
    }

    failure?.let { message ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { failure = null },
            title = "操作失败",
            actions = listOf(DialogAction("知道了") { failure = null }),
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Suppress("DEPRECATION")
private fun buildRepoSnapshot(
    context: android.content.Context,
    repoStore: RepoStore,
    repoModules: List<RepoStore.RepoModule>,
    patchedApps: List<AppInfo>,
): RepoInstallSnapshot {
    val cache = runCatching { repoStore.cacheSnapshot() }
        .getOrDefault(RepoStore.CacheSnapshot.Empty)

    val wanted = HashSet<String>(repoModules.size + patchedApps.size + cache.versions.size)
    repoModules.forEach { wanted.add(it.packageName) }
    patchedApps.forEach { wanted.add(it.packageName) }
    cache.versions.keys.forEach { wanted.add(it) }
    if (wanted.isEmpty()) return RepoInstallSnapshot.Empty

    val pm = context.packageManager
    val bulk = runCatching { pm.getInstalledPackages(0).associateBy { it.packageName } }.getOrNull()
    val installed = HashMap<String, RepoInstalledApp>(wanted.size * 2)
    for (pkg in wanted) {
        val info = if (bulk != null) bulk[pkg] else runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        installed[pkg] = RepoInstalledApp(
            versionName = info?.versionName,
            versionCode = info?.versionCode?.toLong(),
            lastUpdateTime = info?.lastUpdateTime ?: 0L,
        )
    }
    for (app in patchedApps) {
        val current = installed[app.packageName] ?: RepoInstalledApp(null, null, 0L)
        installed[app.packageName] = current.copy(versionName = app.versionName)
    }

    val badge = cache.entries.count { entry ->
        val name = runCatching { pm.getPackageInfo(entry.packageName, 0).versionName }.getOrNull()
        name != entry.version
    }

    return RepoInstallSnapshot(installed = installed, cache = cache, badge = badge)
}

private fun openAllFilesSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName),
            ),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                ),
            )
        }
    }
}

private data class HistorySnapshot(
    val records: List<PatchRecord> = emptyList(),
    val totalSize: Long = 0L,
)

private fun patchPackageName(target: PatchTarget): String = when (target) {
    is PatchTarget.Installed -> target.app.packageName
    is PatchTarget.FromFile -> target.packageName.orEmpty()
}

private fun patchVersionName(target: PatchTarget): String = when (target) {
    is PatchTarget.Installed -> target.app.versionName
    is PatchTarget.FromFile -> target.versionName.orEmpty()
}

private fun patchVersionCode(target: PatchTarget): Long = when (target) {
    is PatchTarget.Installed -> target.app.versionCode
    is PatchTarget.FromFile -> target.versionCode ?: 0L
}

private fun patchSourceKey(target: PatchTarget): String = when (target) {
    is PatchTarget.Installed -> listOf(
        "installed",
        target.app.packageName,
        target.app.versionCode.toString(),
        target.app.versionName,
        target.app.apkPath.orEmpty(),
    ).joinToString("|")

    is PatchTarget.FromFile -> listOf(
        "file",
        target.file.absolutePath,
        target.file.length().toString(),
        target.file.lastModified().toString(),
    ).joinToString("|")
}

private const val MAX_LOG_PAGES = 400
