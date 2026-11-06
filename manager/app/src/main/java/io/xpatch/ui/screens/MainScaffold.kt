package io.xpatch.ui.screens

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.xpatch.data.ConfigBackup
import io.xpatch.data.EventLog
import io.xpatch.data.ManagerRepository
import io.xpatch.data.ModuleApiStore
import io.xpatch.data.RepoStore
import io.xpatch.data.RuntimeLogStore
import io.xpatch.data.ScopeFileWriter
import io.xpatch.data.SettingsStore
import io.xpatch.data.SignatureStore
import io.xpatch.model.AppInfo
import io.xpatch.model.ModuleInfo
import io.xpatch.patch.InstallHelper
import io.xpatch.patch.PatchOptions
import io.xpatch.patch.PatchService
import io.xpatch.patch.ShizukuBridge
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.LiquidBar
import io.xpatch.ui.components.MiuixBottomBar
import io.xpatch.ui.components.PatchProgress
import io.xpatch.ui.components.PatchProgressDialog
import io.xpatch.ui.components.StandardBottomBar
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    var signatures by remember { mutableStateOf(signatureStore.allProfiles()) }
    var defaultSignatureId by remember { mutableStateOf(signatureStore.defaultProfileId()) }
    val scope = rememberCoroutineScope()

    val nav = rememberNavigator()
    val miuix = LocalMiuix.current
    val logsState = remember { LogsState() }
    var logDetailState by remember { mutableStateOf(LogDetailState()) }
    val logDetailList = androidx.compose.foundation.lazy.rememberLazyListState()
    var logCopied by remember { mutableStateOf("") }
    var logCopyTick by remember { mutableIntStateOf(0) }
    var logsRefreshing by remember { mutableStateOf(false) }
    var logsRuntime by remember {
        mutableStateOf(RuntimeLogsState(hasAccess = logStore.hasAccess()))
    }
    var homeRefreshing by remember { mutableStateOf(false) }
    var shizukuAvailable by remember { mutableStateOf(ShizukuBridge.isAvailable()) }
    var shizukuReady by remember {
        mutableStateOf(shizukuAvailable && ShizukuBridge.hasPermission())
    }
    var modulesRefreshing by remember { mutableStateOf(false) }
    var pendingExportFileName by remember { mutableStateOf<String?>(null) }
    var pendingLogText by remember { mutableStateOf<String?>(null) }
    val labelCache = remember { HashMap<String, String>() }
    val labelOf: (String) -> String = { packageName ->
        labelCache.getOrPut(packageName) {
            runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }
    }
    var query by remember { mutableStateOf("") }
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
    var installBlocked by remember { mutableStateOf<InstallBlock?>(null) }

    var patchedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var patchableApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var modules by remember { mutableStateOf<List<ModuleInfo>>(emptyList()) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var logs by remember { mutableStateOf(eventLog.entries()) }
    var repoModules by remember { mutableStateOf<List<RepoStore.RepoModule>>(emptyList()) }
    var repoBusy by remember { mutableStateOf(false) }
    var repoError by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableIntStateOf(0) }
    val syncScopeFile: (String) -> Unit = { appPackage ->
        scope.launch(Dispatchers.IO) {
            val result = ScopeFileWriter(context).sync(appPackage)
            if (!result.ok) {
                eventLog.add("作用域写入失败 " + (result.failureText ?: appPackage))
                val entries = eventLog.entries()
                withContext(Dispatchers.Main) { logs = entries }
            }
        }
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
                nav.navigate(Page.Config(PatchTarget.Installed(app)))
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
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingLogText
        pendingLogText = null
        val name = pendingExportFileName
        pendingExportFileName = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (text == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
            }.onSuccess {
                eventLog.add("已导出日志 " + (name ?: ""))
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
                    val local = runCatching { logStore.load(targets) }.getOrNull()
                    if (local != null && local.entries.isNotEmpty()) {
                        local
                    } else if (ShizukuBridge.hasPermission()) {
                        runCatching {
                            logStore.loadViaShell(
                                reader = { command -> ShizukuBridge.exec(command) },
                                fileReader = { path, maxBytes -> ShizukuBridge.readText(path, maxBytes) },
                                targets = targets,
                            )
                        }.getOrNull() ?: local
                    } else {
                        local
                    }
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
        logs = eventLog.entries()
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
        is Page.Repo -> "模块仓库"
        is Page.RepoDetail -> current.module.name
        is Page.LogModule -> if (current.framework) LogLayer.Framework.label
        else labelOf(current.module)
    }

    val backdrop = rememberLayerBackdrop()
    val miuixBehavior = if (miuix) MiuixScrollBehavior() else null
    val m3Behavior = if (miuix) null else TopAppBarDefaults.enterAlwaysScrollBehavior()
    val scrollConnection = miuixBehavior?.nestedScrollConnection
        ?: m3Behavior?.nestedScrollConnection
        ?: object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {}

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
                        Unit
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
                        Unit
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
                        settings.bottomBarStyle == SettingsStore.BOTTOM_BAR_LIQUID ->
                            LiquidBar(nav.tab, backdrop) { nav.select(it) }

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
                )                .layerBackdrop(backdrop),
        ) {
            when (val current = nav.page) {
                is Page.Home -> when (nav.tab) {
                    Tab.Home -> HomeScreen(
                        apps = patchedApps,
                        refreshing = homeRefreshing,
                        onRefresh = {
                            scope.launch {
                                homeRefreshing = true
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
                        onExport = { fileName, text ->
                            pendingExportFileName = fileName
                            pendingLogText = text
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
                    )
                }

                is Page.Repo -> RepoPageScreen(
                    modules = repoModules,
                    busy = repoBusy,
                    installedVersion = { pkg ->
                        patchedApps.firstOrNull { it.packageName == pkg }?.versionName
                    },
                    installedTime = { pkg ->
                        runCatching {
                            context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
                        }.getOrDefault(0L)
                    },
                    isCached = { module -> runCatching { repoStore.cachedApk(module) != null }.getOrDefault(false) },
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
                    onClearCache = {
                        runCatching { repoStore.clearCache() }
                        eventLog.add("已清空模块下载缓存")
                        token++
                    },
                    onOpenDetail = { module ->
                        runCatching { nav.navigate(Page.RepoDetail(module)) }
                            .onFailure { eventLog.add("打开详情失败 " + (it.message ?: "")) }
                    },
                    sourceUrl = repoStore.sourceUrl(),
                    onSourceChange = { url ->
                        runCatching { repoStore.setSourceUrl(url) }
                        eventLog.add("仓库地址已更新")
                        repoModules = emptyList()
                        repoError = null
                        token++
                    },
                    onAction = { module ->
                        scope.launch {
                            repoBusy = true
                            repoError = null
                            runCatching {
                                val cached = repoStore.cachedApk(module)
                                if (cached != null) {
                                    busyText = "正在安装 " + module.name
                                    runCatching { patchService.installFromFile(cached, module.packageName) }
                                        .onSuccess { eventLog.add("已提交安装 " + module.name) }
                                        .onFailure { eventLog.add("安装失败 " + (it.message ?: "")) }
                                } else {
                                    busyText = "正在下载 " + module.name
                                    val file = repoStore.download(module)
                                    busyText = null
                                    nav.navigate(Page.Config(PatchTarget.FromFile(file, module.name)))
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

                is Page.RepoDetail -> RepoDetailScreen(
                    module = current.module,
                    installed = patchedApps.firstOrNull { it.packageName == current.module.packageName }?.versionName,
                    cached = runCatching { repoStore.cachedApk(current.module) != null }.getOrDefault(false),
                    busy = repoBusy,
                    onAction = {
                        scope.launch {
                            repoBusy = true
                            repoError = null
                            runCatching {
                                val cached = repoStore.cachedApk(current.module)
                                if (cached != null) {
                                    if (!InstallHelper.canInstall(context)) {
                                        installBlocked = InstallBlock(current.module.packageName, true)
                                    } else {
                                        busyText = "正在安装 " + current.module.name
                                        runCatching {
                                            patchService.installFromFile(cached, current.module.packageName)
                                        }
                                            .onSuccess { eventLog.add("已提交安装 " + current.module.name) }
                                            .onFailure { eventLog.add("安装失败 " + (it.message ?: "")) }
                                    }
                                } else {
                                    busyText = "正在下载 " + current.module.name
                                    val file = repoStore.download(current.module)
                                    busyText = null
                                    nav.navigate(Page.Config(PatchTarget.FromFile(file, current.module.name)))
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

                is Page.LogModule -> {
                    val groupEntries = logsRuntime.entries.filter { entry ->
                        if (current.framework) entry.framework else entry.module == current.module
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
                            logCopied = "已复制 " + entry.timeText
                            logCopyTick++
                        },
                    )
                }

                is Page.Picker -> PickerScreen(
                    apps = patchableApps,
                    query = query,
                    onQueryChange = { query = it },
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
                    onStart = { options ->
                        settingsStore.setHookCore(options.hookCore)
                        settingsStore.setSignatureBypassLevel(options.bypassLevel)
                        settingsStore.setInjectSignature(options.injectSignature)
                        settingsStore.setFileProvider(options.fileProvider)
                        settingsStore.setDynamicMode(options.dynamicMode)
                        settings = settings.copy(
                            hookCore = options.hookCore,
                            signatureBypassLevel = options.bypassLevel,
                            injectSignature = options.injectSignature,
                            fileProvider = options.fileProvider,
                            dynamicMode = options.dynamicMode,
                        )
                        scope.launch {
                            busyText = null
                            patchLog.clear()
                            patchProgress = 0
                            patchFailure = null
                            patchRunning = true
                            runCatching {
                                when (val target = current.target) {
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
                            }.onSuccess {
                                eventLog.add("打包完成 ${it.packageName} · ${it.bootDex}")
                                patchProgress = 100
                                result = it
                                token++
                            }.onFailure {
                                eventLog.add("打包失败 ${it.message}")
                                patchLog.add("失败：${it.message}")
                                patchFailure = it.message ?: "未知错误"
                            }
                            patchRunning = false
                            logs = eventLog.entries()
                        }
                    },
                    onPickSignature = { profile ->
                        signatureStore.setDefaultProfile(profile.id)
                        defaultSignatureId = profile.id
                        token++
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

                is Page.ModuleScope -> ModuleScopeScreen(
                    module = current.module,
                    apps = patchedApps,
                    scopeOf = { repository.appsOfModule(current.module.packageName) },
                    onToggle = { app, inScope ->
                        repository.setInScope(current.module.packageName, app.packageName, inScope)
                        eventLog.add(
                            if (inScope) "作用域 + ${current.module.packageName} → ${app.packageName}"
                            else "作用域 - ${current.module.packageName} → ${app.packageName}",
                        )
                        token++
                        syncScopeFile(app.packageName)
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
                    onUpdateCore = {
                        scope.launch {
                            busyText = "正在提取原始安装包…"
                            val src = patchService.extractOriginalApk(current.app)
                            if (src == null) {
                                busyText = null
                                failure = "没有找到内嵌的原始安装包（xp/source.apk）"
                            } else {
                                eventLog.add("已提取原始包 " + src.name)
                                busyText = null
                                patchLog.clear()
                                patchProgress = 0
                                patchFailure = null
                                patchRunning = true
                                runCatching {
                                    patchService.buildFromFile(
                                        src,
                                        current.app.label,
                                        PatchOptions(
                                            hookCore = settings.hookCore,
                                            bypassLevel = settings.signatureBypassLevel,
                                            injectSignature = settings.injectSignature,
                                            fileProvider = settings.fileProvider,
                                            dynamicMode = settings.dynamicMode,
                                        ),
                                        onLog = { line -> patchLog.add(line) },
                                        onProgress = { patchProgress = it },
                                    )
                                }.onSuccess { result = it }
                                    .onFailure {
                                        val msg = "该应用没有内嵌原始包，无法更新核心：" + (it.message ?: "")
                                        patchLog.add("失败：$msg")
                                        patchFailure = msg
                                    }
                                patchRunning = false
                                token++
                            }
                        }
                    },
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
                    onToggle = { module, inScope ->
                        repository.setInScope(module.packageName, current.app.packageName, inScope)
                        eventLog.add(
                            if (inScope) "作用域 + ${module.packageName} → ${current.app.packageName}"
                            else "作用域 - ${module.packageName} → ${current.app.packageName}",
                        )
                        token++
                        syncScopeFile(current.app.packageName)
                    },
                )
            }
        }
    }

    if (showAddDialog) {
        XPatchDialog(
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
                        patchLog.add("已导出到 " + path)
                        eventLog.add("已导出到 " + path)
                    }.onFailure {
                        val msg = "导出失败：" + (it.message ?: "未知错误")
                        patchLog.add(msg)
                        eventLog.add(msg)
                    }
                    logs = eventLog.entries()
                    token++
                }
            },
            onOpenWith = {
                val outcome = result ?: return@PatchProgressDialog
                runCatching { InstallHelper.openWith(context, outcome.apk) }
                    .onSuccess { opened ->
                        if (!opened) eventLog.add("没有可处理安装包的应用")
                    }
                    .onFailure { eventLog.add("打开失败 ${it.message}") }
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
                if (matched == false) {
                    pendingInstall = outcome
                } else if (!InstallHelper.canInstall(context)) {
                    installBlocked = InstallBlock(outcome.packageName, false)
                } else {
                    val started = runCatching { patchService.install(outcome) }.isSuccess
                    eventLog.add(
                        if (started) "已调起系统安装器 " + outcome.packageName
                        else "没有可用的安装器 " + outcome.packageName,
                    )
                    logs = eventLog.entries()
                }
                result = null
                patchLog.clear()
                token++
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
                DialogAction("继续安装") {
                    val installed = runCatching {
                        patchService.install(outcome)
                    }.isSuccess
                    if (installed) {
                        eventLog.add("调起系统安装器 " + outcome.packageName)
                    } else {
                        eventLog.add("没有可用的安装器，已改为打开方式 " + outcome.packageName)
                        runCatching { InstallHelper.openWith(context, outcome.apk) }
                    }
                    pendingInstall = null
                    token++
                },
            ),
        ) {
            Text(
                "内置证书与已安装应用的签名不同，直接覆盖安装会失败。\n可以先卸载原应用，或改用「打开方式」自行选择安装器。\n\n点「返回」会保留打包结果，不必重新打包。",
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
