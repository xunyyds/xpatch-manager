package io.xpatch.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.xpatch.data.RepoStore
import io.xpatch.model.AppInfo
import io.xpatch.model.ModuleInfo
import java.io.File

internal sealed interface PatchTarget {
    data class Installed(val app: AppInfo) : PatchTarget

    data class FromFile(
        val file: File,
        val label: String,
        val packageName: String? = null,
        val versionName: String? = null,
        val versionCode: Long? = null,
        val icon: android.graphics.drawable.Drawable? = null,
    ) : PatchTarget
}

internal data class InstallBlock(val packageName: String, val fromRepo: Boolean)

internal sealed interface Page {
    data object Home : Page
    data object Picker : Page
    data object Signatures : Page
    data class Config(val target: PatchTarget) : Page
    data class ModuleScope(val module: ModuleInfo) : Page
    data class AppScope(val app: AppInfo) : Page
    data class ModuleDetail(
        val module: ModuleInfo,
        val app: AppInfo,
    ) : Page
    data object Repo : Page
    data object RepoDownloads : Page
    data class RepoDetail(val module: RepoStore.RepoModule) : Page
    data object PatchHistory : Page
    data object About : Page
    data object Disclaimer : Page
    data class LogModule(
        val groupKey: String,
        val module: String,
        val framework: Boolean,
        val targets: List<String>,
    ) : Page
}

internal enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Apps),
    Modules("模块", Icons.Rounded.Extension),
    Logs("日志", Icons.Rounded.BugReport),
    Settings("设置", Icons.Rounded.Settings),
}

internal class Navigator {
    var page by mutableStateOf<Page>(Page.Home)
        private set

    var tab by mutableStateOf(Tab.Home)
        private set

    private val stack = mutableStateListOf<Page>()

    val stackPages: List<Page> get() = stack.toList()

    val stackSize: Int get() = stack.size

    val canBack: Boolean
        get() = stack.isNotEmpty() || page !is Page.Home || tab != Tab.Home

    fun navigate(target: Page) {
        if (target == page) return
        val existing = stack.indexOf(target)
        if (existing >= 0) {
            while (stack.size > existing + 1) stack.removeAt(stack.lastIndex)
            page = stack.removeAt(stack.lastIndex)
            return
        }
        if (target is Page.Home) stack.clear() else stack.add(page)
        page = target
    }

    /** 同级页面互跳：替换当前页而不是压栈，避免仓库↔下载管理无限嵌套。 */
    fun replace(target: Page) {
        if (target == page) return
        if (target is Page.Home) {
            stack.clear()
            page = target
            return
        }
        if (stack.isEmpty()) {
            stack.add(page)
            page = target
            return
        }
        page = target
    }

    fun select(target: Tab) {
        if (page !is Page.Home) {
            stack.clear()
            page = Page.Home
        }
        tab = target
    }

    fun openLogs() {
        stack.clear()
        page = Page.Home
        tab = Tab.Logs
    }

    fun back(): Boolean {
        if (stack.isNotEmpty()) {
            page = stack.removeAt(stack.lastIndex)
            return true
        }
        if (page !is Page.Home) {
            page = Page.Home
            return true
        }
        if (tab != Tab.Home) {
            tab = Tab.Home
            return true
        }
        return false
    }
}

@Composable
internal fun rememberNavigator(): Navigator = remember { Navigator() }

internal fun navKeyOf(page: Page, tab: Tab): String = when (page) {
    is Page.Home -> "home:" + tab.name
    is Page.Picker -> "picker"
    is Page.Signatures -> "signatures"
    is Page.PatchHistory -> "history"
    is Page.Repo -> "repo"
    is Page.RepoDownloads -> "repoDownloads"
    is Page.RepoDetail -> "repoDetail:" + page.module.packageName
    is Page.About -> "about"
    is Page.Disclaimer -> "disclaimer"
    is Page.Config -> "config:" + page.target.stableKey()
    is Page.ModuleScope -> "moduleScope:" + page.module.packageName
    is Page.AppScope -> "appScope:" + page.app.packageName
    is Page.ModuleDetail -> "moduleDetail:" + page.module.packageName + ":" + page.app.packageName
    is Page.LogModule -> "logModule:" + page.groupKey
}

private fun PatchTarget.stableKey(): String = when (this) {
    is PatchTarget.Installed -> "installed:" + app.packageName
    is PatchTarget.FromFile -> "file:" + file.absolutePath
}
