package io.xpatch.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.xpatch.data.BackupArchive
import io.xpatch.data.DownloadCenter
import io.xpatch.data.EnvInfoCollector
import io.xpatch.data.EventLog
import io.xpatch.data.ExportStore
import io.xpatch.data.ManagerRepository
import io.xpatch.data.ModuleApiStore
import io.xpatch.data.ModuleUpdate
import io.xpatch.data.OnlinePackageStore
import io.xpatch.data.PatchExportRef
import io.xpatch.data.PatchHistoryStore
import io.xpatch.data.PatchRecord
import io.xpatch.data.RepoStore
import io.xpatch.data.RestartCenter
import io.xpatch.data.RuntimeLogStore
import io.xpatch.data.ScopeFileWriter
import io.xpatch.data.ScopeStore
import io.xpatch.data.SettingsStore
import io.xpatch.data.SignatureStore
import io.xpatch.data.SoftwareSource
import io.xpatch.data.UpdateChecker
import io.xpatch.data.UserDirectory
import io.xpatch.data.UserPackages
import io.xpatch.data.UserSelectionStore
import io.xpatch.data.XpUser
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
import io.xpatch.ui.components.PatchProgress
import io.xpatch.ui.components.PatchProgressDialog
import io.xpatch.ui.components.StandardBottomBar
import io.xpatch.ui.components.UserTargetDialog
import io.xpatch.ui.components.UserTargetRows
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.components.userTargetText
import io.xpatch.ui.format.formatDateTime
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.AnimationQuality
import io.xpatch.ui.theme.LocalAnimationQuality
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.Motion
import io.xpatch.ui.theme.PagePad
import io.xpatch.ui.theme.Space
import io.xpatch.ui.util.AdaptiveWidth
import io.xpatch.ui.util.LocalPageKey
import io.xpatch.ui.util.LocalScrollStateStore
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    onlineTaskRequest: Int = -1,
    onOnlineTaskHandled: () -> Unit = {},
    onThemeModeChange: (Int) -> Unit = {},
    onUiStyleChange: (io.xpatch.data.UiStyle) -> Unit = {},
    onThemeColorChange: (io.xpatch.data.ThemeColor) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    onOledDarkChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember { ManagerRepository(context) }
    val settingsStore = remember { SettingsStore(context) }
    val eventLog = remember { EventLog(context) }
    val repoStore = remember { RepoStore(context) }
    val backupArchive = remember { BackupArchive(context) }
    val exportStore = remember { ExportStore(context) }
    val patchService = remember { PatchService(context) }
    val signatureStore = remember { SignatureStore(context) }
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
    val saveableStateHolder = rememberSaveableStateHolder()
    val retainedKeys = remember { mutableSetOf<String>() }
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
    val appendLog: (String) -> Unit = { line ->
        patchLog.add(line)
    }
    var result by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var pendingInstallProfileName by remember { mutableStateOf("") }
    var pendingInstallUsers by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showPendingUserDialog by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var pendingUserInstall by remember { mutableStateOf<AppInfo?>(null) }
    val users by produceState(initialValue = UserDirectory.cached(), shizukuReady) {
        value = withContext(Dispatchers.IO) {
            runCatching { UserDirectory.refresh(context) }.getOrDefault(UserDirectory.cached())
        }
    }
    var usersInstalledIn by remember { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(users, shizukuReady) {
        usersInstalledIn = withContext(Dispatchers.IO) { probeManagerUsers(users) }
    }
    val selectionStore = remember { UserSelectionStore(context) }
    var homeUser by remember { mutableIntStateOf(selectionStore.of(UserSelectionStore.HOME)) }
    var moduleUser by remember { mutableIntStateOf(selectionStore.of(UserSelectionStore.MODULES)) }
    var logsUser by remember { mutableIntStateOf(selectionStore.of(UserSelectionStore.LOGS)) }
    LaunchedEffect(users) {
        val fallback = users.firstOrNull { it.id == UserDirectory.currentUserId() }?.id
            ?: users.firstOrNull()?.id
        if (fallback != null && users.none { it.id == homeUser }) homeUser = fallback
        if (fallback != null && users.none { it.id == moduleUser }) moduleUser = fallback
        if (fallback != null && users.none { it.id == logsUser }) logsUser = fallback
    }
    val activeLogStore = remember(logsUser) { RuntimeLogStore(userId = logsUser) }
    val logStateCache = remember { HashMap<Int, RuntimeLogsState>() }
    var pendingUninstall by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var uninstallingTarget by remember { mutableStateOf<PatchService.Outcome?>(null) }
    var installBlocked by remember { mutableStateOf<InstallBlock?>(null) }

    var patchedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var patchableApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var modules by remember { mutableStateOf<List<ModuleInfo>>(emptyList()) }
    var userRefreshTick by remember { mutableIntStateOf(0) }
    var catalogApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var catalogReady by remember { mutableStateOf(false) }
    val catalogRequests = remember { Channel<Unit>(Channel.CONFLATED) }
    var catalogNeedsScan by remember { mutableStateOf(false) }
    var catalogForceScan by remember { mutableStateOf(false) }
    val requestCatalogRefresh: (Boolean, Boolean) -> Unit = { scan, force ->
        catalogNeedsScan = catalogNeedsScan || scan
        catalogForceScan = catalogForceScan || force
        catalogRequests.trySend(Unit)
    }
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
    val onlineStore = remember { OnlinePackageStore(context) }
    var onlineApp by remember { mutableStateOf<OnlinePackageStore.OnlineApp?>(null) }
    var onlineVersions by remember { mutableStateOf<List<OnlinePackageStore.OnlineVersion>>(emptyList()) }
    var onlinePrompt by remember { mutableStateOf<OnlinePrompt?>(null) }
    var onlineProgress by remember { mutableStateOf<OnlineProgress?>(null) }
    var onlineCapsule by remember { mutableStateOf(OnlineCapsuleState.HIDDEN) }
    var onlineOnlyDownload by remember { mutableStateOf(false) }
    var onlineFailedUrl by remember { mutableStateOf<String?>(null) }
    var onlineFailedDetailUrl by remember { mutableStateOf("") }
    var onlineNotice by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var onlineTargetPending by remember { mutableStateOf<PatchTarget?>(null) }
    var onlineUseOnlyDownload by remember { mutableStateOf(false) }
    var onlineDownloadTick by remember { mutableIntStateOf(0) }
    var onlinePromptToken by remember { mutableIntStateOf(0) }
    var historyTab by remember { mutableIntStateOf(0) }
    val onlineVersionsById = remember { java.util.concurrent.ConcurrentHashMap<Long, Pair<OnlinePackageStore.OnlineApp, OnlinePackageStore.OnlineVersion>>() }
    val onlineEntries by produceState(initialValue = emptyList<OnlinePackageStore.OnlineEntry>(), onlineDownloadTick) {
        value = withContext(Dispatchers.IO) { onlineStore.downloads() }
    }
    var token by remember { mutableIntStateOf(0) }
    val homeSplit by produceState(
        initialValue = HomeSplit(emptyList(), emptyList()),
        patchedApps,
        homeUser,
        userRefreshTick,
    ) {
        value = withContext(Dispatchers.IO) {
            val visible = UserPackages.visibleIn(homeUser, patchedApps.map { it.packageName })
            HomeSplit(
                visible = patchedApps.filter { it.packageName in visible },
                hidden = patchedApps.filter { it.packageName !in visible },
            )
        }
    }
    val homeScopeCounts by produceState(
        initialValue = emptyMap<String, Int>(),
        patchedApps,
        homeUser,
        userRefreshTick,
    ) {
        value = withContext(Dispatchers.IO) {
            if (homeUser == UserDirectory.currentUserId()) {
                emptyMap()
            } else {
                val store = ScopeStore(context, homeUser)
                patchedApps.associate { it.packageName to store.countModules(it.packageName) }
            }
        }
    }
    val modulesForUser by produceState(
        initialValue = emptyList<ModuleInfo>(),
        modules,
        moduleUser,
        patchedApps,
        userRefreshTick,
    ) {
        value = withContext(Dispatchers.IO) {
            val store = if (moduleUser == UserDirectory.currentUserId()) null else ScopeStore(context, moduleUser)
            if (store == null) {
                modules
            } else {
                val patched = patchedApps.mapTo(HashSet()) { it.packageName }
                modules.map { module ->
                    module.copy(enabledApps = store.appsOf(module.packageName).count { it in patched })
                }
            }
        }
    }
    var exportTarget by remember { mutableStateOf<ExportStore.Target?>(null) }
    var exportChecked by remember { mutableStateOf(false) }
    LaunchedEffect(settings.exportTreeUri, token) {
        exportTarget = withContext(Dispatchers.IO) {
            if (settings.exportTreeUri.isBlank()) null else exportStore.savedTarget()
        }
        exportChecked = true
    }
    val scopeSyncLocks = remember { HashMap<String, Mutex>() }
    val syncScopeFile: (String, Int) -> Unit = sync@{ appPackage, userId ->
        val lock = synchronized(scopeSyncLocks) {
            scopeSyncLocks.getOrPut("$userId:$appPackage") { Mutex() }
        }
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val result = ScopeFileWriter(context).sync(appPackage, userId)
                if (!result.ok) {
                    eventLog.add("作用域同步失败 " + (result.failureText ?: appPackage))
                    val entries = eventLog.entries()
                    withContext(Dispatchers.Main) { logs = entries }
                } else if (userId == UserDirectory.currentUserId()) {
                    val version = repository.scopeSyncVersion(appPackage)
                    repository.acknowledgeScopeSync(appPackage, version)
                }
            }
        }
    }
    val setScopeFor: (String, String, Boolean, Int) -> Unit = { modulePackage, appPackage, inScope, userId ->
        val store = ScopeStore(context, userId)
        store.setInScope(modulePackage, appPackage, inScope)
        syncScopeFile(appPackage, userId)
        if (userId == UserDirectory.currentUserId()) {
            repository.invalidate()
        }
        userRefreshTick++
        val suffix = if (userId == UserDirectory.currentUserId()) "" else
            " → " + UserDirectory.labelOf(userId)
        eventLog.add(
            (if (inScope) "作用域 + " else "作用域 - ") + modulePackage + " → " + appPackage + suffix,
        )
        logs = eventLog.entries()
        token++
    }

    val startPatch: suspend (PatchTarget, PatchOptions) -> Unit = startPatch@{ target, options ->
        settingsStore.setHookCore(options.hookCore)
        settingsStore.setSignatureBypassLevel(options.bypassLevel)
        settingsStore.setInjectSignature(options.injectSignature)
        settingsStore.setFileProvider(options.fileProvider)
        settingsStore.setForceOverwrite(options.forceOverwrite)
        settingsStore.setSigMode(options.sigMode)
        settingsStore.setInjectObfuscate(options.obfuscate)
        settingsStore.setInjectKeepCrashPermission(options.obfuscateKeepCrashPermission)
        settings = settings.copy(
            hookCore = options.hookCore,
            signatureBypassLevel = options.bypassLevel,
            injectSignature = options.injectSignature,
            fileProvider = options.fileProvider,
            forceOverwrite = options.forceOverwrite,
            sigMode = options.sigMode,
            injectObfuscate = options.obfuscate,
            injectKeepCrashPermission = options.obfuscateKeepCrashPermission,
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
        val runOptions = options.copy(logLevel = settings.patchLogLevel)
        runCatching {
            when (target) {
                is PatchTarget.Installed -> patchService.build(
                    target.app,
                    runOptions,
                    onLog = appendLog,
                    onProgress = { patchProgress = it },
                )

                is PatchTarget.FromFile -> patchService.buildFromFile(
                    target.file,
                    target.label,
                    runOptions,
                    onLog = appendLog,
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
            withContext(Dispatchers.IO) {
                historyStore.record(record)
            }
            currentRecordId = record.id
            historyTick++
            token++
        }.onFailure {
            eventLog.add("打包失败 " + it.message)
            appendLog("失败：" + it.message)
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

    var pendingBackupScopeKeys by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingRestorePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestorePreview by remember { mutableStateOf<BackupArchive.Preview?>(null) }
    LaunchedEffect(pendingRestorePath) {
        val path = pendingRestorePath ?: return@LaunchedEffect
        if (pendingRestorePreview != null) return@LaunchedEffect
        val preview = withContext(Dispatchers.IO) {
            val file = File(path)
            if (file.isFile) backupArchive.inspect(file) else null
        }
        if (preview == null) {
            pendingRestorePath = null
            withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
            failure = "无法识别该备份文件"
        } else {
            pendingRestorePreview = preview
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportStore.MIME_ZIP),
    ) { uri ->
        val scopes = BackupArchive.Scope.ofKeys(pendingBackupScopeKeys)
        pendingBackupScopeKeys = arrayListOf()
        if (uri == null) return@rememberLauncherForActivityResult
        if (scopes.isEmpty()) {
            scope.launch(Dispatchers.IO) { exportStore.delete(uri.toString()) }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busyText = "正在备份数据…"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        backupArchive.write(output, scopes)
                    } ?: error("无法写入备份文件")
                }
            }
            busyText = null
            outcome.onSuccess { written ->
                eventLog.add(
                    "已备份 " + scopes.size + " 类数据 · " + readableSize(written.bytes) +
                        if (written.skipped.isEmpty()) "" else " · 跳过 " + written.skipped.size + " 个文件",
                )
                logs = eventLog.entries()
            }.onFailure {
                withContext(Dispatchers.IO) { exportStore.delete(uri.toString()) }
                eventLog.add("备份失败 " + (it.message ?: ""))
                logs = eventLog.entries()
                failure = "备份失败：" + (it.message ?: "未知错误")
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyText = "正在读取备份…"
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "restore").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val target = File(dir, "picked-backup")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选文件")
                    target
                }
            }
            busyText = null
            staged.onSuccess { file -> pendingRestorePath = file.absolutePath }
                .onFailure { failure = "读取备份失败：" + (it.message ?: "未知错误") }
        }
    }

    pendingRestorePreview?.let { preview ->
        val dismissRestore: () -> Unit = {
            pendingRestorePreview = null
            pendingRestorePath?.let { path -> scope.launch(Dispatchers.IO) { runCatching { File(path).delete() } } }
            pendingRestorePath = null
        }
        XPatchDialog(
            miuix = miuix,
            onDismiss = dismissRestore,
            title = "从备份恢复",
            actions = listOf(
                DialogAction("取消", onClick = dismissRestore),
                DialogAction("恢复", destructive = true) {
                    val path = pendingRestorePath
                    val scopeRestored = preview.scopes.contains(BackupArchive.Scope.SCOPE)
                    pendingRestorePreview = null
                    pendingRestorePath = null
                    if (path != null) {
                        scope.launch {
                            busyText = "正在恢复备份…"
                            val previousTargets = withContext(Dispatchers.IO) {
                                runCatching {
                                    ScopeStore(context).exportAll().values.flatten().toSet()
                                }.getOrDefault(emptySet())
                            }
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching { backupArchive.restore(File(path)) }
                            }
                            outcome.onSuccess { restored ->
                                eventLog.add(
                                    if (restored.ok) {
                                        "已恢复备份：" + restored.summary
                                    } else {
                                        "恢复未完成：" + restored.summary
                                    },
                                )
                                restored.warnings.forEach { warning ->
                                    eventLog.add("恢复告警 " + warning)
                                }
                                val reloaded = settingsStore.load()
                                settings = reloaded
                                onThemeModeChange(reloaded.themeMode)
                                onUiStyleChange(reloaded.uiStyle)
                                onThemeColorChange(reloaded.themeColor)
                                onDynamicColorChange(reloaded.dynamicColor)
                                onOledDarkChange(reloaded.oledDark)
                                signatures = signatureStore.allProfiles()
                                defaultSignatureId = signatureStore.defaultProfileId()
                                repository.invalidate()
                                repoStore.invalidate()
                                historyStore.invalidate()
                                historyTick++
                                token++
                                logs = eventLog.entries()
                                if (scopeRestored) {
                                    busyText = "正在同步作用域…"
                                    val synced = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val current = ScopeStore(context).exportAll()
                                                .values.flatten().toSet()
                                            val targets = previousTargets + current
                                            val failed = ScopeFileWriter(context).syncAll(targets) { message ->
                                                runCatching { eventLog.add(message) }
                                            }
                                            targets.size to failed
                                        }
                                    }
                                    synced.onSuccess { (total, failed) ->
                                        eventLog.add(
                                            if (failed > 0) {
                                                "作用域同步完成，失败 " + failed + " 个应用"
                                            } else {
                                                "已重新同步 " + total + " 个应用的作用域文件"
                                            },
                                        )
                                    }.onFailure {
                                        eventLog.add("作用域同步失败 " + (it.message ?: ""))
                                    }
                                    busyText = null
                                } else {
                                    busyText = null
                                }
                                if (!restored.ok) {
                                    failure = "恢复未完全成功：" + (
                                        restored.warnings.firstOrNull() ?: restored.summary
                                        )
                                }
                            }.onFailure {
                                busyText = null
                                eventLog.add("恢复失败 " + (it.message ?: ""))
                                logs = eventLog.entries()
                                failure = "恢复失败：" + (it.message ?: "未知错误")
                            }
                            logs = eventLog.entries()
                            runCatching { File(path).delete() }
                        }
                    }
                },
            ),
        ) {
            Text(
                "ZIP 备份 · XPatch " + preview.versionName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (preview.createdAt > 0) {
                Text(
                    "创建于 " + formatDateTime(preview.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            preview.scopes.forEach { item ->
                val count = preview.counts[item.key] ?: 0
                Text(
                    "· " + item.title + (if (count > 0) " " + count + " 项" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "恢复会覆盖当前对应数据且无法撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    val exportDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val target = withContext(Dispatchers.IO) { exportStore.saveTarget(uri.toString()) }
            if (target == null) {
                eventLog.add("导出目录不可写，请重新选择")
                logs = eventLog.entries()
                failure = "无法写入所选目录，请换一个目录"
                return@launch
            }
            settings = settingsStore.load()
            eventLog.add("导出目录已设为 " + target.label)
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
                failure = "日志导出失败：写入导出文件失败：" + (it.message ?: "未知错误")
                eventLog.add("日志导出失败 " + (it.message ?: ""))
            }
            logs = eventLog.entries()
        }
    }

    val loadRuntimeLogs: (Int) -> Unit = { requestUser ->
        val store = RuntimeLogStore(userId = requestUser)
        scope.launch {
            if (!logsRefreshing) {
                logsRefreshing = true
                logsRuntime = RuntimeLogsState(loading = true, hasAccess = store.hasAccess(), userId = requestUser)
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        val targets = repository.patchedPackageNames()
                        val merged = if (ShizukuBridge.hasPermission()) {
                            runCatching {
                                store.loadFirstPageMerged(
                                    reader = { command -> ShizukuBridge.exec(command) },
                                    targets = targets,
                                    fileReader = { path, maxBytes -> ShizukuBridge.readText(path, maxBytes) },
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                        val firstPage = merged ?: runCatching { store.loadFirstPage(targets) }.getOrNull()
                        firstPage?.let { capLogSnapshot(it) }
                    }
                    if (requestUser != logsUser) return@launch
                    val snapshot = loaded?.first
                    logsRuntime = if (snapshot != null) {
                        RuntimeLogsState(
                            snapshot = snapshot,
                            loading = false,
                            error = null,
                            hasAccess = true,
                            userId = requestUser,
                        )
                    } else {
                        logsRuntime.copy(loading = false, error = "日志读取失败", userId = requestUser)
                    }
                if (loaded?.second == true) {
                    eventLog.add("运行日志已达单次载入上限（$MAX_LOG_ENTRIES 条 / 约 32 MB），更早的日志未载入")
                    logs = eventLog.entries()
                }
            } catch (error: Throwable) {
                if (requestUser == logsUser) {
                    logsRuntime = logsRuntime.copy(
                        loading = false,
                        error = "日志读取失败：" + (error.message ?: "未知错误"),
                        userId = requestUser,
                    )
                }
            } finally {
                logsRefreshing = false
            }
        }
    }
    }

    LaunchedEffect(logsUser) {
        val cachedState = logStateCache[logsUser]
        if (cachedState != null) {
            logsRuntime = cachedState
        } else {
            logsRuntime = RuntimeLogsState(hasAccess = activeLogStore.hasAccess(), userId = logsUser)
            loadRuntimeLogs(logsUser)
        }
    }

    LaunchedEffect(logsUser, logsRuntime) {
        if (logsRuntime.userId == logsUser) logStateCache[logsUser] = logsRuntime
    }

    val loadMoreRuntimeLogs: suspend () -> Unit = {
        val current = logsRuntime.snapshot
        if (!logsLoadingMore && current != null && current.hasMore) {
            logsLoadingMore = true
            logsRuntime = logsRuntime.copy(loadingMore = true)
            try {
                val next = withContext(Dispatchers.IO) {
                    runCatching { activeLogStore.loadMore(current) }.getOrNull()
                }
                if (next != null) {
                    val (capped, truncated) = capLogSnapshot(next)
                    logsRuntime = logsRuntime.copy(
                        snapshot = capped,
                        loadingMore = false,
                        error = null,
                    )
                    if (truncated) {
                        eventLog.add(
                            "运行日志已达单次载入上限（$MAX_LOG_ENTRIES 条 / 约 32 MB），更早的日志未载入",
                        )
                        logs = eventLog.entries()
                    }
                } else {
                    logsRuntime = logsRuntime.copy(
                        loadingMore = false,
                        error = "加载更早的日志失败",
                    )
                }
            } finally {
                logsLoadingMore = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val (loadedProfiles, loadedDefaultId) = withContext(Dispatchers.IO) {
            signatureStore.allProfiles() to signatureStore.defaultProfileId()
        }
        signatures = loadedProfiles
        defaultSignatureId = loadedDefaultId
        logsRuntime = logsRuntime.copy(hasAccess = activeLogStore.hasAccess())
    }

    LaunchedEffect(repository) {
        for (request in catalogRequests) {
            val scan = catalogNeedsScan
            val force = catalogForceScan
            catalogNeedsScan = false
            catalogForceScan = false
            try {
                if (scan) {
                    withContext(Dispatchers.IO) { UserPackages.refreshAll(users.map { it.id }) }
                }
                val loaded = if (scan) repository.refresh(force) else repository.loadOverview()
                if (!catalogNeedsScan && !catalogForceScan) {
                    val initialSync = !catalogReady && loaded.packageScanComplete
                    patchedApps = loaded.patchedApps
                    modules = loaded.modules
                    catalogApps = loaded.apps
                    catalogReady = loaded.packageScanComplete
                    val syncTargets = loaded.affectedScopeApps.toMutableSet()
                    if (initialSync) {
                        val initialTargets = loaded.patchedApps.map { it.packageName }
                        withContext(Dispatchers.IO) { repository.requestScopeSync(initialTargets) }
                        syncTargets.addAll(initialTargets)
                    }
                    val owner = UserDirectory.currentUserId()
                    syncTargets.forEach { target -> syncScopeFile(target, owner) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                eventLog.add("应用与模块刷新失败：" + (error.message ?: "未知错误"))
                logs = eventLog.entries()
            } finally {
                if (!catalogNeedsScan && !catalogForceScan) {
                    homeRefreshing = false
                    modulesRefreshing = false
                }
            }
        }
    }

    LaunchedEffect(token) {
        requestCatalogRefresh(false, false)
    }

    LaunchedEffect(catalogApps, includeSystem) {
        patchableApps = catalogApps.filter { includeSystem || !it.isSystem }
    }

    LaunchedEffect(token) {
        logs = withContext(Dispatchers.IO) { eventLog.entries() }
    }

    LaunchedEffect(catalogReady, modules, patchedApps, nav.page) {
        if (!catalogReady) return@LaunchedEffect
        when (val current = nav.page) {
            is Page.ModuleScope -> if (modules.none { it.packageName == current.module.packageName }) {
                nav.select(Tab.Modules)
            }
            is Page.ModuleDetail -> if (modules.none { it.packageName == current.module.packageName }) {
                nav.select(Tab.Modules)
            }
            is Page.AppScope -> if (patchedApps.none { it.packageName == current.app.packageName }) {
                nav.select(Tab.Home)
            }
            else -> Unit
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shizukuAvailable = ShizukuBridge.isAvailable()
                shizukuReady = shizukuAvailable && ShizukuBridge.hasPermission()
                val access = activeLogStore.hasAccess()
                val hadAccess = logsRuntime.hasAccess
                logsRuntime = logsRuntime.copy(hasAccess = access)
                if (access && (logsRuntime.snapshot == null || !hadAccess) && !logsRefreshing) {
                    loadRuntimeLogs(logsUser)
                }
                requestCatalogRefresh(true, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context, repository) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                val replacing = intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                    repository.onPackageRemoved(packageName, replacing)
                } else {
                    repository.onPackageChanged(packageName)
                }
                labelCache.remove(packageName)
                if (!replacing) requestCatalogRefresh(true, true)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(nav.tab) {
        if (nav.tab == Tab.Logs) {
            logsState.tab = LogsTab.Runtime
            if (logsRuntime.snapshot == null && !logsRefreshing) {
                loadRuntimeLogs(logsUser)
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
        is Page.AppearanceSettings -> "外观"
        is Page.Users -> "用户"
        is Page.Releases -> "更新日志"
        is Page.Config -> "Patch 配置"
        is Page.ModuleScope -> current.module.label
        is Page.AppScope -> current.app.label
        is Page.ModuleDetail -> current.module.label
        is Page.Repo -> "模块仓库"
        is Page.RepoDownloads -> "下载管理"
        is Page.OnlineDownloads -> "下载管理"
        is Page.RepoDetail -> current.module.name
        is Page.PatchHistory -> if (historyTab == 1) "原包" else "打包历史"
        is Page.About -> "关于"
        is Page.Disclaimer -> "免责声明"
        is Page.Acknowledgements -> AcknowledgementsData.TITLE
        is Page.LogModule -> if (current.framework) LogLayer.Framework.label
        else labelOf(current.module)
    }

    val backdrop = rememberLayerBackdrop()
    val miuixBehavior = if (miuix) MiuixScrollBehavior() else null
    val m3Behavior = if (miuix) null else TopAppBarDefaults.pinnedScrollBehavior()
    val scrollConnection = miuixBehavior?.nestedScrollConnection
        ?: m3Behavior?.nestedScrollConnection
        ?: object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {}

    val showToast: (String) -> Unit = { message ->
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    val applyOnlineTarget: (PatchTarget, java.io.File) -> Unit = apply@{ target, file ->
        onlineCapsule = OnlineCapsuleState.DONE
        val label = when (target) {
            is PatchTarget.Installed -> target.app.label
            is PatchTarget.FromFile -> target.label
        }
        val fromFile = PatchTarget.FromFile(
            file = file,
            label = label,
            packageName = patchPackageName(target).takeIf { it.isNotBlank() },
            versionName = patchVersionName(target),
            versionCode = patchVersionCode(target),
            icon = when (target) {
                is PatchTarget.Installed -> target.app.icon
                is PatchTarget.FromFile -> target.icon
            },
        )
        nav.navigate(Page.Config(fromFile))
        showToast("已用在线原包替换，确认后开始打包")
    }

    val fallbackModuleInstall: (String, java.io.File, String) -> Unit = { name, file, pkg ->
        if (!InstallHelper.canInstall(context)) {
            eventLog.add("缺少「安装未知应用」权限，已停止安装 " + name)
            showToast("需要安装权限，请授权后重试")
            installBlocked = InstallBlock(pkg)
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
        fallbackModuleInstall(name, file, pkg)
    }

    val fallbackInstall: (PatchService.Outcome) -> Unit = fallback@{ outcome ->
        if (!InstallHelper.canInstall(context)) {
            eventLog.add("缺少「安装未知应用」权限，已停止安装 " + outcome.packageName)
            showToast("需要安装权限，请授权后重试")
            installBlocked = InstallBlock(outcome.packageName)
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

    val syncInstalledScope: (String, Collection<Int>) -> Unit = { appPackage, userIds ->
        val currentUser = UserDirectory.currentUserId()
        val source = ScopeStore(context, currentUser)
        val sourceModules = source.modulesOf(appPackage)
        userIds.distinct().forEach { userId ->
            if (userId != currentUser && sourceModules.isNotEmpty()) {
                runCatching { ScopeStore(context, userId).copyAppScope(source, appPackage) }
            }
            val result = runCatching { ScopeFileWriter(context).sync(appPackage, userId) }.getOrNull()
            if (result != null && !result.ok) {
                eventLog.add("作用域同步失败 " + (result.failureText ?: appPackage))
            }
        }
        UserPackages.invalidate()
    }

    val installOutcome: (PatchService.Outcome) -> Unit = { outcome ->
        scope.launch {
            val currentUser = io.xpatch.data.UserDirectory.currentUserId()
            val targets = outcome.installUsers.ifEmpty { setOf(currentUser) }.toSortedSet()
            val others = targets.filter { it != currentUser }
            val viaShizuku = ShizukuBridge.isAvailable()
            busyText = if (viaShizuku) "正在通过 Shizuku 安装…" else null
            try {
                val step = if (outcome.forceOverwrite) {
                    busyText = "正在强行覆盖安装…"
                    val forced = ShizukuInstaller.installForceOverwrite(context, outcome.apk, outcome.packageName)
                    if (forced is ShizukuInstaller.Result.Success && others.isNotEmpty()) {
                        val missing = others.toSet() - ShizukuInstaller.ensureExisting(outcome.packageName, others)
                        if (missing.isNotEmpty()) {
                            eventLog.add(
                                "以下用户未能启用 " + outcome.packageName + "：" +
                                    missing.joinToString("、") { UserDirectory.labelOf(it) },
                            )
                        }
                    }
                    forced
                } else {
                    ShizukuInstaller.install(context, outcome.apk, outcome.packageName, targets)
                }
                when (step) {
                    is ShizukuInstaller.Result.Success -> {
                        eventLog.add(step.message)
                        showToast(step.message)
                        withContext(Dispatchers.IO) { syncInstalledScope(outcome.packageName, targets) }
                        userRefreshTick++
                        requestCatalogRefresh(true, true)
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
                        if (outcome.forceOverwrite || others.isNotEmpty()) {
                            eventLog.add("跨用户安装缺少 Shizuku，已停止 " + outcome.packageName)
                            failure = "安装到其他用户需要 Shizuku，当前不可用。\n\n请先安装并授权 Shizuku 后重试，或只选择当前用户。"
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

    val beginInstall: (PatchService.Outcome, Set<Int>) -> Unit = beginInstall@{ outcome, picked ->
        val users2 = picked.ifEmpty { setOf(UserDirectory.currentUserId()) }
        val profile = signatureStore.defaultProfile()
        val matched = InstallHelper.signatureMatches(
            context,
            outcome.packageName,
            signatureStore.certificateBytes(profile),
        )
        val target = outcome.copy(installUsers = users2)
        result = null
        patchLog.clear()
        token++
        if (matched == false) {
            pendingInstall = target
            pendingInstallProfileName = profile.name
            pendingInstallUsers = users2
        } else {
            installOutcome(target)
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
        repository.onPackageChanged(target.packageName)
        requestCatalogRefresh(true, true)
        token++
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

    LaunchedEffect(onlineTaskRequest) {
        if (onlineTaskRequest > 0) {
            val id = onlineTaskRequest.toLong()
            val task = DownloadCenter.taskOf(id)
            if (task != null) {
                DownloadCenter.requestOpenDialog(id)
            }
            onOnlineTaskHandled()
        }
    }
    LaunchedEffect(Unit) {
        DownloadCenter.openDialog.collect { id ->
            val task = DownloadCenter.taskOf(id)
            if (task != null && task.kind == DownloadCenter.Kind.ONLINE) {
                val saved = onlineVersionsById[id] ?: DownloadCenter.onlineTaskOf(id)
                if (saved != null) {
                    onlineProgress = OnlineProgress(saved.first, saved.second, id)
                }
            }
        }
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
                    eventLog.add("已下载 " + event.module.name + "，可在下载管理中安装")
                    showToast("已下载 " + event.module.name + "，请到下载管理安装")
                    token++
                }

                is DownloadCenter.Event.Failed -> {
                    repoDownloadPkg = null
                    repoDownloadTaskId = null
                    eventLog.add(event.message)
                }

                is DownloadCenter.Event.OnlineReady -> {
                    onlineCapsule = OnlineCapsuleState.DONE
                    onlineDownloadTick++
                    eventLog.add("已下载原包 " + event.version.packageName + " v" + event.version.version)
                    if (!onlineUseOnlyDownload) {
                        onlineTargetPending?.let { target ->
                            onlineTargetPending = null
                            applyOnlineTarget(target, event.file)
                        }
                    } else {
                        onlineUseOnlyDownload = false
                        showToast("原包已保存到下载管理")
                    }
                    logs = eventLog.entries()
                }

                is DownloadCenter.Event.OnlineFailed -> {
                    onlineCapsule = OnlineCapsuleState.HIDDEN
                    eventLog.add("原包下载失败 " + event.version.packageName + " v" + event.version.version)
                    logs = eventLog.entries()
                }
            }
        }
    }

    fun currentVersionOf(target: PatchTarget?): String = when (target) {
        is PatchTarget.Installed -> target.app.versionName.orEmpty()
        is PatchTarget.FromFile -> target.versionName.orEmpty()
        else -> ""
    }

    fun buildOnlinePrompt(
        app: OnlinePackageStore.OnlineApp,
        versions: List<OnlinePackageStore.OnlineVersion>,
        target: PatchTarget? = null,
        loading: Boolean = false,
        error: String? = null,
    ): OnlinePrompt {
        val latest = versions.firstOrNull()?.version.orEmpty()
        return OnlinePrompt(app, versions, loading, error, currentVersionOf(target), latest)
    }

    fun showOnlineNotice(title: String, message: String, rawError: String) {
        onlineNotice = Triple(title, message, rawError)
    }

    fun reloadOnlinePrompt(
        seed: OnlinePackageStore.OnlineApp,
        target: PatchTarget?,
        source: SoftwareSource,
    ) {
        val display = seed.copy(source = source)
        onlinePromptToken++
        val token = onlinePromptToken
        onlineApp = display
        onlinePrompt = buildOnlinePrompt(display, emptyList(), target = target, loading = true)
        scope.launch {
            val resolved = runCatching { onlineStore.resolve(seed.packageName, source) }.getOrNull()
            if (token != onlinePromptToken || onlinePrompt == null) return@launch
            if (resolved == null) {
                onlineCapsule = OnlineCapsuleState.HIDDEN
                onlinePrompt = buildOnlinePrompt(
                    display,
                    emptyList(),
                    target = target,
                    error = "这个源没有收录该应用",
                )
                return@launch
            }
            onlineApp = resolved
            val listResult = runCatching { onlineStore.history(resolved) }
            val list = listResult.getOrNull().orEmpty()
            if (token != onlinePromptToken || onlinePrompt == null) return@launch
            onlineVersions = list
            if (list.isEmpty()) {
                onlineCapsule = OnlineCapsuleState.HIDDEN
                onlinePrompt = buildOnlinePrompt(
                    resolved,
                    emptyList(),
                    target = target,
                    error = listResult.exceptionOrNull()?.message ?: "这个源暂时没有可用版本",
                )
            } else {
                onlineCapsule = OnlineCapsuleState.FOUND
                onlinePrompt = buildOnlinePrompt(resolved, list, target = target)
            }
        }
    }

    fun resolveOnline(target: PatchTarget, showPrompt: Boolean, reportFailure: Boolean = false) {
        val pkg = patchPackageName(target)
        if (pkg.isBlank() || !pkg.contains('.')) return
        onlineCapsule = OnlineCapsuleState.CHECKING
        scope.launch {
            val resolveResult = runCatching { onlineStore.resolve(pkg, settings.softwareSource) }
            val app = resolveResult.getOrNull()
            if (app == null) {
                onlineCapsule = OnlineCapsuleState.HIDDEN
                if (reportFailure) {
                    val error = resolveResult.exceptionOrNull()
                    if (error != null) {
                        showOnlineNotice("查询在线版本失败", "无法获取该应用的在线版本，请检查网络后重试。", error.toString())
                    } else {
                        showOnlineNotice("未找到在线版本", "没有找到「" + pkg + "」的在线版本。", "在线源未收录该应用")
                    }
                }
                return@launch
            }
            val historyResult = runCatching { onlineStore.history(app) }
            val versions = historyResult.getOrNull().orEmpty()
            if (versions.isEmpty()) {
                onlineCapsule = OnlineCapsuleState.HIDDEN
                if (reportFailure) {
                    val error = historyResult.exceptionOrNull()
                    if (error != null) {
                        showOnlineNotice("获取历史版本失败", "已找到「" + app.name + "」，但读取版本列表失败。", error.toString())
                    } else {
                        showOnlineNotice("没有可用版本", "「" + app.name + "」暂时没有可下载的版本。", "版本列表为空")
                    }
                }
                return@launch
            }
            onlineApp = app
            onlineVersions = versions
            onlineCapsule = OnlineCapsuleState.FOUND
            if (showPrompt) {
                onlinePrompt = buildOnlinePrompt(app, versions, target = target)
            }
        }
    }

    val openOriginal: (OnlinePackageStore.OnlineEntry) -> Unit = open@{ entry ->
        val target = PatchTarget.FromFile(
            file = java.io.File(entry.path),
            label = entry.name,
            packageName = entry.packageName,
            versionName = entry.version,
            versionCode = entry.size,
        )
        onlineApp = OnlinePackageStore.OnlineApp(entry.packageName, "", entry.name)
        onlineTargetPending = target
        onlineCapsule = OnlineCapsuleState.DONE
        onlinePromptToken++
        onlinePrompt = buildOnlinePrompt(onlineApp!!, emptyList(), target = target, loading = true)
        scope.launch {
            val app = runCatching { onlineStore.resolve(entry.packageName, settings.softwareSource) }.getOrNull()
            if (app != null) {
                onlineApp = app
                val list = runCatching { onlineStore.history(app) }
                onlinePrompt = list.fold(
                    onSuccess = { buildOnlinePrompt(app, it, target = target) },
                    onFailure = { buildOnlinePrompt(app, emptyList(), target = target, error = it.message ?: "获取失败") },
                )
            } else {
                onlinePrompt = null
                showToast("已打开本地原包")
                nav.navigate(Page.Config(target))
            }
        }
    }

    val useOnlineVersion: (OnlinePackageStore.OnlineVersion, Boolean) -> Unit = use@{ version, onlyDownload ->
        val app = onlineApp ?: return@use
        onlinePromptToken++
        onlineUseOnlyDownload = onlyDownload
        onlinePrompt = null
        val cached = onlineStore.cachedApk(app.packageName, version.versionCode)
        if (cached != null) {
            onlineDownloadTick++
            if (onlyDownload) {
                showToast("该版本已在下载管理中")
            } else {
                val target = onlineTargetPending
                onlineTargetPending = null
                if (target != null) applyOnlineTarget(target, cached)
            }
            return@use
        }
        onlineCapsule = OnlineCapsuleState.DOWNLOADING
        onlineProgress = OnlineProgress(app, version, -1L)
        scope.launch {
            val resolved = runCatching { onlineStore.detail(app, version) }.getOrDefault(version)
            val wasHidden = onlineProgress == null
            val id = DownloadCenter.startOnline(context, onlineStore, app, resolved)
            onlineVersionsById[id] = Pair(app, resolved)
            if (!wasHidden) {
                onlineProgress = OnlineProgress(app, resolved, id)
            }
        }
    }

    val liquidBottomBar = settings.bottomBarStyle == SettingsStore.BOTTOM_BAR_LIQUID
    val moduleDownloadTask = downloadTasks.values.firstOrNull {
        it.kind == DownloadCenter.Kind.MODULE && it.failed == null && !it.done
    }
    val moduleDownloadPercent = moduleDownloadTask?.percent

    val configTarget = nav.page as? Page.Config
    val configPackage = configTarget?.let { patchPackageName(it.target) }.orEmpty()
    LaunchedEffect(configPackage, settings.softwareSource) {
        if (configPackage.isNotBlank() && configPackage.contains('.')) {
            if (onlineApp?.packageName != configPackage) {
                onlineApp = null
                onlinePrompt = null
                onlineCapsule = OnlineCapsuleState.HIDDEN
                onlineTargetPending = configTarget?.target
                resolveOnline(configTarget!!.target, showPrompt = false)
            }
        } else {
            onlineCapsule = OnlineCapsuleState.HIDDEN
        }
    }

    val renderSettings: @Composable (Boolean) -> Unit = { appearancePage ->
        SettingsScreen(
            settings = settings,
            users = users,
            onInstallUsersChange = { picked ->
                settingsStore.setInstallUsers(picked)
                settings = settings.copy(installUsers = picked)
            },
            defaultSignatureName = signatures.firstOrNull { it.id == defaultSignatureId }?.name ?: "XPatch",
            exportLabel = exportTarget?.label ?: settings.exportTreeLabel,
            exportLost = exportChecked && settings.exportTreeUri.isNotBlank() && exportTarget == null,
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
            onOpenUsers = { nav.navigate(Page.Users) },
            onLogLevelChange = {
                settingsStore.setPatchLogLevel(it)
                settings = settings.copy(patchLogLevel = it)
            },
            onSoftwareSourceChange = { source ->
                settingsStore.setSoftwareSource(source)
                settings = settings.copy(softwareSource = source)
                onlinePromptToken++
                onlineApp = null
                onlineVersions = emptyList()
                onlinePrompt = null
                onlineCapsule = OnlineCapsuleState.HIDDEN
            },
            onThemeChange = {
                settingsStore.setThemeMode(it)
                settings = settings.copy(themeMode = it)
                onThemeModeChange(it)
            },
            onUiStyleChange = {
                settingsStore.setUiStyle(it)
                settings = settings.copy(uiStyle = it)
                onUiStyleChange(it)
            },
            onThemeColorChange = {
                settingsStore.setThemeColor(it)
                settings = settings.copy(themeColor = it)
                onThemeColorChange(it)
            },
            onDynamicColorChange = {
                settingsStore.setDynamicColor(it)
                settings = settings.copy(dynamicColor = it)
                onDynamicColorChange(it)
            },
            onOledDarkChange = {
                settingsStore.setOledDark(it)
                settings = settings.copy(oledDark = it)
                onOledDarkChange(it)
            },
            onShowSystemChange = {
                settingsStore.setShowSystemApps(it)
                settings = settings.copy(showSystemApps = it)
                token++
            },
            onBottomBarChange = {
                settingsStore.setBottomBarStyle(it)
                settings = settings.copy(bottomBarStyle = it)
            },
            onLiquidGlassLevelChange = {
                settingsStore.setLiquidGlassLevel(it)
                settings = settings.copy(liquidGlassLevel = it)
            },

            onPickExportDir = { exportDirLauncher.launch(null) },
            onClearExportDir = {
                val previous = settings.exportTreeUri
                settingsStore.clearExportTree()
                settings = settings.copy(exportTreeUri = "", exportTreeLabel = "")
                token++
                scope.launch(Dispatchers.IO) { exportStore.release(previous) }
            },
            onBackup = { scopes ->
                pendingBackupScopeKeys = ArrayList(scopes.map { it.key })
                backupLauncher.launch(backupArchive.defaultFileName())
            },
            onRestoreBackup = {
                restoreLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
            },
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
            appearancePage = appearancePage,
            onOpenAppearance = { nav.navigate(Page.AppearanceSettings) },
        )
    }

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
                        if (nav.page is Page.Repo) {
                            DownloadAction(
                                miuix = true,
                                progress = moduleDownloadPercent,
                                badge = repoSnapshot.value.badge,
                                onClick = { nav.navigate(Page.RepoDownloads) },
                            )
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
                        if (nav.page is Page.Repo) {
                            DownloadAction(
                                miuix = false,
                                progress = moduleDownloadPercent,
                                badge = repoSnapshot.value.badge,
                                onClick = { nav.navigate(Page.RepoDownloads) },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            }
        },
        floatingActionButton = {
            if (nav.page is Page.Home && nav.tab == Tab.Home) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 2.dp,
                        pressedElevation = 4.dp,
                        focusedElevation = 2.dp,
                        hoveredElevation = 2.dp,
                    ),
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("添加应用") },
                )
            }
        },
        bottomBar = {
            if (nav.page is Page.Home) {
                Box(Modifier.background(androidx.compose.ui.graphics.Color.Transparent)) {
                    when {
                        liquidBottomBar -> LiquidBar(
                            current = nav.tab,
                            backdrop = backdrop,
                            glassLevel = settings.liquidGlassLevel,
                        ) { nav.select(it) }

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
            val reducedMotion = LocalAnimationQuality.current == AnimationQuality.REDUCED
            AnimatedContent(
                targetState = nav.page,
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        (slideInHorizontally(animationSpec = Motion.spatialEnter) { it / 8 } +
                            fadeIn(Motion.fadeIn))
                            .togetherWith(
                                slideOutHorizontally(animationSpec = Motion.spatialExit) { -it / 8 } +
                                    fadeOut(Motion.fadeOut),
                            )
                    }
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
            remember(liveKeys) {
                scrollStore.retain(liveKeys)
                val stale = retainedKeys - liveKeys
                stale.forEach { saveableStateHolder.removeState(it) }
                retainedKeys.clear()
                retainedKeys.addAll(liveKeys)
                liveKeys
            }
            CompositionLocalProvider(
                LocalPageKey provides pageKey,
                LocalScrollStateStore provides scrollStore,
            ) {
            saveableStateHolder.SaveableStateProvider(pageKey) {
            key(pageKey) {
            when (current) {
                is Page.Home -> when (nav.tab) {
                    Tab.Home -> HomeScreen(
                        apps = homeSplit.visible,
                        refreshing = homeRefreshing,
                        onRefresh = {
                            if (!homeRefreshing) {
                                homeRefreshing = true
                                requestCatalogRefresh(true, true)
                            }
                        },
                        onAdd = { showAddDialog = true },
                        onOpen = { nav.navigate(Page.AppScope(it)) },
                        onOpenGuardLogs = { app, module ->
                            logsState.tab = LogsTab.Runtime
                            logsState.apps = setOf(app)
                            logsState.query = module
                            nav.openLogs()
                        },
                        users = users,
                        viewUser = homeUser,
                        onViewUserChange = { userId ->
                            selectionStore.set(UserSelectionStore.HOME, userId)
                            homeUser = userId
                        },
                        hidden = homeSplit.hidden,
                        moduleCountOf = { packageName ->
                            if (homeUser == UserDirectory.currentUserId()) {
                                patchedApps.firstOrNull { it.packageName == packageName }?.enabledModules ?: 0
                            } else {
                                homeScopeCounts[packageName] ?: 0
                            }
                        },
                        onInstallHidden = { app -> pendingUserInstall = app },
                    )

                    Tab.Modules -> ModulesScreen(
                        modules = modulesForUser,
                        query = query,
                        onQueryChange = { query = it },
                        onOpen = { nav.navigate(Page.ModuleScope(it)) },
                        refreshing = modulesRefreshing,
                        users = users,
                        viewUser = moduleUser,
                        onViewUserChange = { userId ->
                            selectionStore.set(UserSelectionStore.MODULES, userId)
                            moduleUser = userId
                        },
                        onRefresh = {
                            if (!modulesRefreshing) {
                                modulesRefreshing = true
                                requestCatalogRefresh(true, true)
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
                        users = users,
                        viewUser = logsUser,
                        onViewUserChange = { userId ->
                            selectionStore.set(UserSelectionStore.LOGS, userId)
                            logsUser = userId
                        },
                        onRefreshRuntime = { loadRuntimeLogs(logsUser) },
                        onRequestStorage = { openAllFilesSettings(context) },
                        onLoadMoreRuntime = { scope.launch { loadMoreRuntimeLogs() } },
                        onClearRuntime = {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { activeLogStore.clear() }
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
                            loadRuntimeLogs(logsUser)
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
                                        activeLogStore.listShellFiles(
                                            reader = { command -> ShizukuBridge.exec(command) },
                                            targets = targets,
                                        )
                                    }.getOrDefault(emptyList())
                                    if (shell.isNotEmpty()) shell
                                    else runCatching { activeLogStore.listLocalFiles(targets) }.getOrNull()?.first ?: emptyList()
                                } else {
                                    runCatching { activeLogStore.listLocalFiles(targets) }.getOrNull()?.first ?: emptyList()
                                }
                            }
                        },
                        deleteLogFiles = { paths ->
                            withContext(Dispatchers.IO) {
                                if (ShizukuBridge.hasPermission()) {
                                    activeLogStore.deleteViaShell(
                                        reader = { command -> ShizukuBridge.exec(command) },
                                        paths = paths,
                                    )
                                } else {
                                    activeLogStore.clearFiles(paths)
                                }
                            }
                        },
                    )

                    Tab.Settings -> renderSettings(false)
                }

                is Page.Repo -> RepoPageScreen(
                    modules = repoModules,
                    busy = repoBusy,
                    snapshot = repoSnapshot.value,
                    readmeText = { pkg -> repoStore.readmeText(pkg) },
                    downloadingPkg = repoDownloadPkg,
                    downloadProgress = { repoDownloadProgress },
                    downloadDoneTick = repoDownloadDoneTick,
                    onOpenDownloads = { nav.navigate(Page.RepoDownloads) },
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
                                    nav.navigate(Page.RepoDownloads)
                                } else {
                                    showToast("开始下载 " + module.name)
                                    eventLog.add("开始下载 " + module.name)
                                    repoDownloadPkg = module.packageName
                                    repoDownloadProgress = 0
                                    repoDownloadTaskId = DownloadCenter.startModule(context, repoStore, module)
                                }
                            }.onFailure {
                                repoError = it.message ?: "下载失败"
                                eventLog.add("下载失败 " + (it.message ?: ""))
                                showToast("下载失败：" + (it.message ?: "未知错误"))
                            }
                            busyText = null
                            repoBusy = false
                            logs = eventLog.entries()
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
                        val ok = runCatching { repoStore.removeDownload(entry.key) }.getOrDefault(false)
                        eventLog.add(if (ok) "已删除 " + entry.packageName + " v" + entry.version + " 的下载缓存" else "删除失败 " + entry.packageName)
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

                is Page.OnlineDownloads -> OriginalsPanel(
                    entries = onlineEntries,
                    totalSize = onlineStore.cacheSize(),
                    onOpen = { entry -> openOriginal(entry) },
                    onDelete = { entry ->
                        val ok = runCatching { onlineStore.removeDownload(entry.key) }.getOrDefault(false)
                        eventLog.add(if (ok) "已删除原包 " + entry.name + " v" + entry.version else "删除失败")
                        onlineDownloadTick++
                        logs = eventLog.entries()
                    },
                )

                is Page.RepoDetail -> {
                    var detailReadme by remember(current.module.packageName) { mutableStateOf<String?>(null) }
                    var detailVersions by remember(current.module.packageName) {
                        mutableStateOf<List<RepoStore.RemoteVersion>>(emptyList())
                    }
                    var versionsLoading by remember(current.module.packageName) { mutableStateOf(false) }
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
                        versions = detailVersions,
                        versionsLoading = versionsLoading,
                        onLoadVersions = {
                            if (!versionsLoading) {
                                scope.launch {
                                    versionsLoading = true
                                    runCatching { repoStore.versions(current.module.packageName) }
                                        .onSuccess { detailVersions = it }
                                        .onFailure {
                                            eventLog.add("获取历史版本失败 " + (it.message ?: ""))
                                            showToast("获取历史版本失败")
                                        }
                                    versionsLoading = false
                                }
                            }
                        },
                        onOpenVersion = { version ->
                            showToast("开始下载 " + current.module.name + " v" + version.version)
                            eventLog.add("开始下载 " + current.module.name + " v" + version.version)
                            scope.launch {
                                repoBusy = true
                                repoError = null
                                runCatching {
                                    repoDownloadPkg = current.module.packageName
                                    repoDownloadProgress = 0
                                    repoDownloadTaskId = DownloadCenter.startModule(
                                        context,
                                        repoStore,
                                        current.module,
                                        version,
                                    )
                                }.onFailure {
                                    repoError = it.message ?: "下载失败"
                                    eventLog.add("下载失败 " + (it.message ?: ""))
                                    showToast("下载失败：" + (it.message ?: "未知错误"))
                                }
                                busyText = null
                                repoBusy = false
                                logs = eventLog.entries()
                                token++
                            }
                        },
                        onAction = {
                            scope.launch {
                                repoBusy = true
                                repoError = null
                                runCatching {
                                    val cached = repoStore.cachedApk(current.module)
                                    if (cached != null) {
                                        nav.navigate(Page.RepoDownloads)
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
                    users = users,
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
                    onSignatureModeChange = { mode, level ->
                        settingsStore.setSigMode(mode)
                        settingsStore.setSignatureBypassLevel(level)
                        settings = settings.copy(
                            sigMode = mode,
                            signatureBypassLevel = level,
                        )
                    },
                    onObfuscateChange = { obfuscate, keepCrash ->
                        settingsStore.setInjectObfuscate(obfuscate)
                        settingsStore.setInjectKeepCrashPermission(keepCrash)
                        settings = settings.copy(
                            injectObfuscate = obfuscate,
                            injectKeepCrashPermission = keepCrash,
                        )
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
                        appendLog("复用已打包产物：" + record.apkPath)
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
                    onlineCapsule = onlineCapsule,
                    onlineCapsulePercent = onlineProgress?.let { downloadTasks[it.taskId]?.percent },
                    onOnlineCapsuleClick = {
                        when (onlineCapsule) {
                            OnlineCapsuleState.FOUND -> {
                                onlinePromptToken++
                                onlineTargetPending = current.target
                                val app = onlineApp
                                if (app != null) {
                                    if (onlineVersions.isEmpty()) {
                                        onlinePrompt = buildOnlinePrompt(app, emptyList(), target = current.target)
                                        scope.launch {
                                            val list = runCatching { onlineStore.history(app) }.getOrDefault(emptyList())
                                            onlineVersions = list
                                            onlinePrompt = buildOnlinePrompt(app, list, target = current.target)
                                        }
                                    } else {
                                        onlinePrompt = buildOnlinePrompt(app, onlineVersions, target = current.target)
                                    }
                                }
                            }

                            OnlineCapsuleState.DOWNLOADING -> {
                                onlineProgress?.let { DownloadCenter.requestOpenDialog(it.taskId) }
                            }

                            OnlineCapsuleState.DONE -> {
                                onlineTargetPending?.let { target ->
                                    val entry = onlineStore.findAny(patchPackageName(target))
                                    if (entry != null) applyOnlineTarget(target, java.io.File(entry.path))
                                } ?: run { nav.navigate(Page.OnlineDownloads) }
                            }

                            else -> Unit
                        }
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
                                runCatching { patchService.export(outcome) }
                            }
                            saved.onSuccess { exported ->
                                withContext(Dispatchers.IO) {
                                    historyStore.addExport(
                                        record.id,
                                        PatchExportRef(
                                            path = exported.path,
                                            name = exported.name,
                                            time = System.currentTimeMillis(),
                                            uri = exported.uri,
                                            size = exported.size,
                                        ),
                                    )
                                }
                                historyTick++
                                showToast("已导出 " + exported.name)
                            }.onFailure {
                                showToast("导出失败：" + (it.message ?: "未知错误"))
                            }
                        }
                    },
                    onDeleteExport = { record, ref ->
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) {
                                historyStore.removeExport(record.id, ref.path, ref.uri, true)
                            }
                            historyTick++
                            showToast(
                                when (removed) {
                                    null -> "导出记录已不存在"
                                    true -> "已删除导出文件"
                                    false -> "记录已删除，文件未删除"
                                },
                            )
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
                    tabIndex = historyTab,
                    onTabChange = { historyTab = it },
                    originalsBadge = onlineEntries.size,
                    originalsContent = {
                        OriginalsPanel(
                            entries = onlineEntries,
                            totalSize = onlineStore.cacheSize(),
                            onOpen = { entry -> openOriginal(entry) },
                            onDelete = { entry ->
                                val ok = runCatching { onlineStore.removeDownload(entry.key) }.getOrDefault(false)
                                eventLog.add(if (ok) "已删除原包 " + entry.name + " v" + entry.version else "删除失败")
                                onlineDownloadTick++
                                logs = eventLog.entries()
                            },
                        )
                    },
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

                is Page.AppearanceSettings -> renderSettings(true)
                is Page.Users -> UsersScreen(
                    users = users,
                    installedIn = usersInstalledIn,
                    onInstallManager = { userId ->
                        scope.launch {
                            busyText = "正在安装管理器…"
                            val installed = withContext(Dispatchers.IO) {
                                ShizukuInstaller.installExisting(context.packageName, userId)
                            }
                            busyText = null
                            if (installed) {
                                UserPackages.invalidate()
                                eventLog.add("已把管理器安装到 " + UserDirectory.labelOf(userId))
                            } else {
                                eventLog.add(
                                    "安装管理器到 " + UserDirectory.labelOf(userId) + " 失败，请确认 Shizuku 已授权",
                                )
                            }
                            usersInstalledIn = withContext(Dispatchers.IO) { probeManagerUsers(users) }
                            logs = eventLog.entries()
                            token++
                        }
                    },
                )
                is Page.Releases -> ReleasesScreen()
                is Page.About -> AboutScreen(
                    onOpenDisclaimer = { nav.navigate(Page.Disclaimer) },
                    onOpenAcknowledgements = { nav.navigate(Page.Acknowledgements) },
                    onOpenReleases = { nav.navigate(Page.Releases) },
                )
                is Page.Disclaimer -> DisclaimerScreen()
                is Page.Acknowledgements -> AcknowledgementsScreen()

                is Page.ModuleScope -> {
                    val scopeUser = moduleUser
                    ModuleScopeScreen(
                        module = modules.firstOrNull { it.packageName == current.module.packageName } ?: current.module,
                        apps = patchedApps,
                        users = users,
                        viewUser = scopeUser,
                        onViewUserChange = { userId ->
                            selectionStore.set(UserSelectionStore.MODULES, userId)
                            moduleUser = userId
                        },
                        scopeOf = {
                            ScopeStore(context, scopeUser).appsOfModuleFor(current.module.packageName)
                        },
                        onToggle = { app, inScope ->
                            setScopeFor(current.module.packageName, app.packageName, inScope, scopeUser)
                            RestartCenter.request(
                                context,
                                app.packageName,
                                app.label,
                                "模块作用域已更新，重启 " + app.label + " 后生效",
                                scopeUser,
                            )
                        },
                    )
                }

                is Page.AppScope -> {
                    val scopeUser = homeUser
                    AppScopeScreen(
                    app = patchedApps.firstOrNull { it.packageName == current.app.packageName }
                        ?: current.app.copy(enabledModules = repository.countModules(current.app.packageName)),
                    modules = modules,
                    users = users,
                    viewUser = scopeUser,
                    apiOf = remember(token, current.app.packageName, scopeUser) {
                        val store = ModuleApiStore(context, scopeUser)
                        val lookup: (ModuleInfo) -> Int = { module ->
                            store.apiOf(current.app.packageName, module.packageName)
                        }
                        lookup
                    },
                    onApiChange = { module, api ->
                        ModuleApiStore(context, scopeUser)
                            .setApi(current.app.packageName, module.packageName, api)
                        eventLog.add("API ${module.packageName} → ${ModuleApiStore.label(api)}，重启目标应用后生效")
                        token++
                        syncScopeFile(current.app.packageName, scopeUser)
                        RestartCenter.request(
                            context,
                            current.app.packageName,
                            current.app.label,
                            "模块 " + module.label + " 的 API 级别已改为 " + ModuleApiStore.label(api) + "，重启后生效",
                            scopeUser,
                        )
                    },
                    onLaunch = {
                        scope.launch {
                            val hasEntry = scopeUser == UserDirectory.currentUserId() && runCatching {
                                context.packageManager.getLaunchIntentForPackage(current.app.packageName) != null
                            }.getOrDefault(false)
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { ShizukuBridge.launch(context, current.app.packageName, scopeUser) }
                                    .getOrDefault(false)
                            }
                            eventLog.add(
                                when {
                                    ok -> "已启动 ${current.app.packageName} · " + UserDirectory.labelOf(scopeUser)
                                    scopeUser != UserDirectory.currentUserId() -> "启动失败：跨用户启动需要 Shizuku"
                                    !hasEntry -> "启动失败：${current.app.packageName} 没有可启动的入口"
                                    else -> "启动失败：无法调起 ${current.app.packageName}"
                                },
                            )
                            token++
                        }
                    },
                    onForceStop = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { ShizukuBridge.forceStop(current.app.packageName, scopeUser) }
                                    .getOrDefault(false)
                            }
                            eventLog.add(
                                if (ok) {
                                    "已强制停止 ${current.app.packageName} · " + UserDirectory.labelOf(scopeUser)
                                } else {
                                    "停止失败：请先安装并授权 Shizuku"
                                },
                            )
                            token++
                        }
                    },
                    onRestart = {
                        scope.launch {
                            eventLog.add(
                                "正在重启 ${current.app.packageName} · " + UserDirectory.labelOf(scopeUser),
                            )
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { ShizukuBridge.restart(context, current.app.packageName, scopeUser) }
                                    .getOrDefault(false)
                            }
                            eventLog.add(
                                if (ok) "已重启 ${current.app.packageName}" else "重启失败：请先安装并授权 Shizuku",
                            )
                            token++
                        }
                    },
                    onDexopt = {
                        if (!ShizukuBridge.isAvailable() || !ShizukuBridge.hasPermission()) {
                            failure = "优化启动速度需要 Shizuku。请先安装并授权 Shizuku 后重试。"
                        } else {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    ShizukuBridge.dexopt(current.app.packageName)
                                }
                                eventLog.add(
                                    if (ok) "已开始在后台优化 ${current.app.packageName}，完成后系统会自动生效"
                                    else "Dexopt 调度失败，请检查 Shizuku 授权",
                                )
                                logs = eventLog.entries()
                                showToast(
                                    if (ok) "已开始在后台优化，可正常使用应用"
                                    else "Dexopt 调度失败，请检查 Shizuku 授权",
                                )
                                token++
                            }
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
                    inScope = { module ->
                        ScopeStore(context, scopeUser).modulesOf(current.app.packageName)
                            .contains(module.packageName)
                    },
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
                        setScopeFor(module.packageName, current.app.packageName, inScope, scopeUser)
                        RestartCenter.request(
                            context,
                            current.app.packageName,
                            current.app.label,
                            "模块作用域已更新，重启 " + current.app.label + " 后生效",
                            scopeUser,
                        )
                    },
                )
                }

                is Page.ModuleDetail -> ModuleDetailScreen(
                    module = current.module,
                    appLabel = current.app.label,
                    checked = ScopeStore(context, homeUser).modulesOf(current.app.packageName)
                        .contains(current.module.packageName),
                    scopeCount = ScopeStore(context, homeUser).countApps(current.module.packageName),
                    onToggle = { inScope ->
                        setScopeFor(current.module.packageName, current.app.packageName, inScope, homeUser)
                        RestartCenter.request(
                            context,
                            current.app.packageName,
                            current.app.label,
                            "模块作用域已更新，重启 " + current.app.label + " 后生效",
                            homeUser,
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
    }

    onlineNotice?.let { notice ->
        OnlineNoticeDialog(
            title = notice.first,
            message = notice.second,
            rawError = notice.third,
            onDismiss = { onlineNotice = null },
            onRetry = {
                onlineNotice = null
                val target = configTarget?.target ?: onlineTargetPending
                if (target != null) resolveOnline(target, showPrompt = true, reportFailure = true)
            },
        )
    }
    onlinePrompt?.let { prompt ->
        OnlinePromptDialog(
            prompt = prompt,
            cached = onlineEntries,
            source = settings.softwareSource,
            onSourceChange = { source ->
                settingsStore.setSoftwareSource(source)
                settings = settings.copy(softwareSource = source)
                reloadOnlinePrompt(prompt.app, onlineTargetPending ?: configTarget?.target, source)
            },
            onRetry = {
                reloadOnlinePrompt(prompt.app, onlineTargetPending ?: configTarget?.target, settings.softwareSource)
            },
            onDismiss = { onlinePromptToken++; onlinePrompt = null },
            onUse = { version, onlyDownload -> useOnlineVersion(version, onlyDownload) },
        )
    }
    onlineProgress?.let { progress ->
        OnlineProgressDialog(
            progress = progress,
            task = downloadTasks[progress.taskId],
            onHide = { onlineProgress = null },
            onCancel = {
                DownloadCenter.cancel(progress.taskId)
                onlineProgress = null
            },
            onRetry = {
                val saved = onlineVersionsById[progress.taskId]
                if (saved != null) {
                    val id = DownloadCenter.startOnline(context, onlineStore, saved.first, saved.second)
                    onlineVersionsById[id] = saved
                    onlineProgress = OnlineProgress(saved.first, saved.second, id)
                } else {
                    onlineProgress = null
                }
            },
            onFailedLink = { url ->
                onlineFailedUrl = url
                onlineFailedDetailUrl = progress.version.detailUrl
            },
            onDone = {
                onlineProgress = null
                val version = onlineVersionsById[progress.taskId]?.second
                if (version != null && !onlineUseOnlyDownload) {
                    onlineTargetPending?.let { target ->
                        onlineStore.cachedApk(version.packageName, version.versionCode)?.let { file ->
                            onlineTargetPending = null
                            applyOnlineTarget(target, file)
                        }
                    }
                }
            },
        )
    }
    onlineFailedUrl?.let { url ->
        OnlineFailedLinkDialog(
            url = url,
            detailUrl = onlineFailedDetailUrl,
            onDismiss = { onlineFailedUrl = null },
        )
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
                        runCatching { patchService.export(outcome) }
                    }
                    saved.onSuccess { exported ->
                        val recordId = currentRecordId
                        if (recordId != null) {
                            withContext(Dispatchers.IO) {
                                historyStore.addExport(
                                    recordId,
                                    PatchExportRef(
                                        path = exported.path,
                                        name = exported.name,
                                        time = System.currentTimeMillis(),
                                        uri = exported.uri,
                                        size = exported.size,
                                    ),
                                )
                            }
                            historyTick++
                        }
                        appendLog("已导出到 " + exported.path)
                        eventLog.add("已导出到 " + exported.path)
                        showToast("已导出 " + exported.name)
                    }.onFailure {
                        val msg = "导出失败：" + (it.message ?: "未知错误")
                        appendLog(msg)
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
                if (users.size > 1) {
                    pendingInstallUsers = outcome.installUsers.ifEmpty {
                        setOf(UserDirectory.currentUserId())
                    }
                    result = null
                    patchLog.clear()
                    token++
                    confirmInstall = outcome
                } else {
                    beginInstall(outcome, outcome.installUsers)
                }
            },
            onDismiss = {
                result = null
                patchFailure = null
                patchLog.clear()
            },
        )
    }

    pendingUserInstall?.let { app ->
        val target = homeUser
        XPatchDialog(
            miuix = miuix,
            onDismiss = { pendingUserInstall = null },
            title = "安装到 " + UserDirectory.labelOf(target),
            actions = listOf(
                DialogAction("取消") { pendingUserInstall = null },
                DialogAction("安装") {
                    pendingUserInstall = null
                    scope.launch {
                        busyText = "正在安装…"
                        val ok = withContext(Dispatchers.IO) {
                            ShizukuInstaller.installExisting(app.packageName, target)
                        }
                        busyText = null
                        UserPackages.invalidate(target)
                        userRefreshTick++
                        requestCatalogRefresh(true, true)
                        eventLog.add(
                            if (ok) {
                                "已安装 " + app.label + " 到 " + UserDirectory.labelOf(target)
                            } else {
                                "安装失败 " + app.label + "：" + UserDirectory.labelOf(target)
                            },
                        )
                        logs = eventLog.entries()
                        token++
                    }
                },
            ),
        ) {
            Column {
                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "系统不会弹出确认框，确认后立即安装。应用数据不受影响，已打包的补丁保持生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmInstall?.let { outcome ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { confirmInstall = null },
            title = "安装到",
            actions = listOf(
                DialogAction("取消") { confirmInstall = null },
                DialogAction("开始安装") {
                    val target = confirmInstall
                    val picked = pendingInstallUsers
                    confirmInstall = null
                    if (target != null) beginInstall(target, picked)
                },
            ),
        ) {
            Column {
                Text(outcome.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    outcome.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                UserTargetRows(users, pendingInstallUsers) { id ->
                    pendingInstallUsers = if (id in pendingInstallUsers && pendingInstallUsers.size > 1) {
                        pendingInstallUsers - id
                    } else {
                        pendingInstallUsers + id
                    }
                }
            }
        }
    }

    pendingInstall?.let { outcome ->
        if (showPendingUserDialog) {
            UserTargetDialog(
                users = users,
                selected = pendingInstallUsers,
                onConfirm = { picked ->
                    showPendingUserDialog = false
                    pendingInstallUsers = picked
                },
                onDismiss = { showPendingUserDialog = false },
            )
        }
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
                    if (target != null) {
                        installOutcome(target.copy(installUsers = pendingInstallUsers))
                    }
                },
            ),
        ) {
            Column {
                Text(
                    "签名：" + pendingInstallProfileName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                InstallFact(label = "数据", value = if (outcome.forceOverwrite) "保留" else "删除且不可恢复")
                InstallFact(label = "方式", value = if (outcome.forceOverwrite) {
                    "保留数据卸载原应用后安装新包"
                } else {
                    "卸载原应用后自动继续安装"
                })
                if (outcome.forceOverwrite) {
                    InstallFact(label = "条件", value = "需要 Shizuku")
                }
                if (users.size > 1) {
                    InstallFact(label = "安装到", value = userTargetText(pendingInstallUsers))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showPendingUserDialog = true },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "更改安装用户",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
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
            Column {
                InstallFact(label = "数据", value = "删除且不可恢复")
                InstallFact(label = "方式", value = "调用系统卸载")
            }
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
                "系统尚未允许 XPatch 安装应用，点「去授权」开启后即可安装 " +
                    pending.packageName + "。",
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
                DialogAction("立即重启") {
                    RestartCenter.restartNow(context, request.packageName, request.userId)
                },
            ),
        ) {
            Text(request.reason, style = MaterialTheme.typography.bodySmall)
        }
    }

    failure?.let { message ->
        XPatchDialog(
            miuix = miuix,
            onDismiss = { failure = null },
            title = failureTitleOf(message),
            actions = listOf(DialogAction("知道了") { failure = null }),
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InstallFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label + "：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (label == "数据") MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(6.dp))
}

private fun failureTitleOf(message: String): String = when {
    "签名" in message && ("安装" in message || "卸载" in message) -> "安装失败"
    "优化" in message || "Dexopt" in message -> "优化失败"
    "安装" in message -> "安装失败"
    "日志" in message -> "日志读取失败"
    "导入" in message || "配置" in message -> "配置导入失败"
    else -> "操作失败"
}

private fun probeManagerUsers(users: List<io.xpatch.data.XpUser>): Set<Int> {
    val current = UserDirectory.currentUserId()
    val result = LinkedHashSet<Int>()
    users.forEach { user ->
        if (user.id == current) {
            result.add(user.id)
            return@forEach
        }
        val listed = UserPackages.packagesOf(user.id) ?: return@forEach
        if (ManagerPackage in listed) result.add(user.id)
    }
    return result
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
        val info = runCatching { pm.getPackageInfo(entry.packageName, 0) }.getOrNull()
        ModuleUpdate.isUpdate(entry.version, 0L, info?.versionName, info?.versionCode?.toLong())
    }

    return RepoInstallSnapshot(installed = installed, cache = cache, badge = badge)
}

@Composable
private fun DownloadAction(
    miuix: Boolean,
    progress: Int?,
    badge: Int,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        if (miuix) {
            MiuixIconButton(onClick = onClick) {
                MiuixIcon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "下载管理",
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        } else {
            IconButton(onClick = onClick) {
                Icon(Icons.Rounded.Download, contentDescription = "下载管理")
            }
        }
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.size(38.dp),
                strokeWidth = 2.dp,
            )
        } else if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
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

private fun capLogSnapshot(
    snapshot: RuntimeLogStore.Snapshot,
): Pair<RuntimeLogStore.Snapshot, Boolean> {
    var chars = 0L
    var keep = 0
    for (entry in snapshot.entries) {
        val cost = (entry.message.length + entry.detail.length + LOG_ENTRY_OVERHEAD_CHARS).toLong()
        if (keep >= MAX_LOG_ENTRIES || chars + cost > MAX_LOG_CHARS) break
        chars += cost
        keep++
    }
    if (keep >= snapshot.entries.size) return snapshot to false
    return snapshot.copy(
        entries = snapshot.entries.subList(0, keep).toList(),
        done = true,
    ) to true
}

private const val MAX_LOG_ENTRIES = 20_000

private const val MAX_LOG_CHARS = 16L * 1024 * 1024

private const val LOG_ENTRY_OVERHEAD_CHARS = 160

private const val ManagerPackage = "io.xpatch"

private data class HomeSplit(
    val visible: List<AppInfo>,
    val hidden: List<AppInfo>,
)
