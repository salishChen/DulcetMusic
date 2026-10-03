package com.mtechviral.musicfinderexample.feature.subsonic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch
import java.util.UUID

/** 远程配置页的两步流程：先选音乐源类型，再填写具体配置。 */
private enum class ConfigStep { SELECT, CONFIG }

/**
 * 远程配置页（导航路由沿用 [com.mtechviral.musicfinderexample.core.common.AppRoutes.SUBSONIC]）。
 *
 * 流程（需求）：
 * - **首次进入**（尚未配置任何音乐源）：先展示音乐源类型选择页，
 *   选定后再进入该类型的具体配置表单；
 * - **再次进入**（已有配置）：直接进入当前音乐源的配置页，
 *   经「更换类型」可返回类型选择。
 *
 * EasyTier 组网配置已提取为独立的「组网设置」一级页面（[MeshSettingsScreen]）。
 */
@Composable
fun SubsonicConfigScreen() {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val openSidebar = LocalOpenSidebar.current
    var step by remember { mutableStateOf(ConfigStep.SELECT) }
    var protocol by remember { mutableStateOf(RemoteProtocol.SUBSONIC) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var intranet by remember { mutableStateOf("") }
    var publicUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var libraryId by remember { mutableStateOf("") }
    var savedSource by remember { mutableStateOf<RemoteSource?>(null) }
    val drafts = remember { mutableMapOf<RemoteProtocol, RemoteSource>() }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var sameServerWithNewAddress by remember { mutableStateOf(false) }

    fun fillForm(option: RemoteProtocol, stored: RemoteSource?) {
        val draft = drafts[option] ?: stored
        protocol = option
        savedSource = stored
        sourceId = stored?.id
        intranet = draft?.intranetUrl.orEmpty()
        publicUrl = draft?.publicUrl.orEmpty()
        username = draft?.username.orEmpty()
        password = draft?.password.orEmpty()
        rootPath = draft?.rootPath.orEmpty()
        libraryId = draft?.libraryId.orEmpty()
        sameServerWithNewAddress = false
        result = ""
    }

    fun rememberDraft() {
        drafts[protocol] = RemoteSource(
            id = sourceId.orEmpty(), protocol = protocol, displayName = protocol.label,
            intranetUrl = intranet, publicUrl = publicUrl, username = username,
            password = password, rootPath = rootPath, libraryId = libraryId,
        )
    }

    LaunchedEffect(Unit) {
        RemoteSessionManager.load()
        RemoteSessionManager.source.value?.let { current ->
            fillForm(current.protocol, current)
            // 已有配置：直接进入该音乐源的配置页（跳过类型选择）
            step = ConfigStep.CONFIG
        }
        loaded = true
    }

    fun candidate(): RemoteSource {
        require(intranet.isNotBlank() || publicUrl.isNotBlank()) { "请填写内网或公网地址" }
        require(username.isNotBlank()) { "请填写用户名" }
        require(password.isNotBlank()) { "请填写密码" }
        val current = savedSource
        val sameSource = current?.protocol == protocol && current.username == username.trim() &&
            (protocol != RemoteProtocol.WEBDAV || current.rootPath == rootPath.trim()) &&
            current.libraryId == (if (protocol == RemoteProtocol.WEBDAV) "" else libraryId.trim()) &&
            ((current.intranetUrl == intranet.trim() && current.publicUrl == publicUrl.trim()) ||
                sameServerWithNewAddress)
        return RemoteSource(
            id = if (sameSource) current!!.id else UUID.randomUUID().toString(),
            protocol = protocol,
            displayName = protocol.label,
            intranetUrl = intranet.trim(),
            publicUrl = publicUrl.trim(),
            username = username.trim(),
            password = password,
            rootPath = if (protocol == RemoteProtocol.WEBDAV) rootPath.trim() else "",
            libraryId = if (sameSource && protocol != RemoteProtocol.WEBDAV) libraryId.trim() else "",
            serverIdentity = if (sameSource) current?.serverIdentity else null,
        )
    }

    fun saveCandidate() {
        scope.launch {
            busy = true
            try {
                val next = candidate()
                val previousId = RemoteSessionManager.activeSourceId
                if (previousId != null) {
                    PlayerController.stopRemoteForSourceSwitch()
                    CacheService.cancelPending()
                }
                RemoteSessionManager.activate(next, verifyConnection = false)
                if (previousId != null && previousId != next.id) {
                    val old = PlaylistRepository.current.filter { it.sourceId == previousId }.map { it.path }
                    if (old.isNotEmpty()) {
                        PlayerController.pause()
                        PlaylistRepository.removeSongs(old)
                    }
                }
                sourceId = next.id
                savedSource = next
                drafts[next.protocol] = next
                result = "已保存并启用 ${next.protocol.label}；可用「测试连接」验证服务器"
            } catch (e: Exception) {
                result = "保存失败：${e.message ?: "未知错误"}"
            }
            busy = false
        }
    }

    Scaffold(
        topBar = { PrimaryAppBar(title = "远程配置", onMenuClick = openSidebar) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!loaded) return@Scaffold
        if (step == ConfigStep.SELECT) {
            SourceTypeSelect(
                modifier = Modifier.fillMaxSize().padding(padding),
                enabled = !busy,
                onSelect = { option ->
                    scope.launch {
                        busy = true
                        try {
                            fillForm(option, DatabaseHelper.remoteConfiguration(option))
                            step = ConfigStep.CONFIG
                        } catch (e: Exception) {
                            snackbar.showSnackbar("读取配置失败：${e.message ?: "未知错误"}")
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 当前音乐源类型 + 返回类型选择入口
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音乐源类型：${protocol.label}", fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { rememberDraft(); step = ConfigStep.SELECT }) { Text("更换类型") }
            }
            ConfigTextField(intranet, { intranet = it }, "内网地址", "http://192.168.1.2:4533", Icons.Filled.Home)
            ConfigTextField(publicUrl, { publicUrl = it }, "公网地址", "https://music.example.com", Icons.Filled.Public)
            Text("内网和公网地址填一个即可；都填写时优先连接内网。")
            if (protocol == RemoteProtocol.WEBDAV) {
                OutlinedTextField(
                    value = rootPath, onValueChange = { rootPath = it },
                    label = { Text("音乐目录（相对于 WebDAV 地址）") },
                    placeholder = { Text("Music") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            ConfigTextField(username, { username = it }, "用户名", "", Icons.Filled.Person)
            ConfigTextField(
                password, { password = it }, "密码", "", Icons.Filled.Lock,
                visualTransformation = PasswordVisualTransformation(),
            )
            val current = savedSource
            if (current != null && current.protocol == protocol &&
                current.username == username.trim() &&
                (current.intranetUrl != intranet.trim() || current.publicUrl != publicUrl.trim())) {
                Row {
                    Checkbox(checked = sameServerWithNewAddress,
                        onCheckedChange = { sameServerWithNewAddress = it })
                    Text("仅修改同一台服务器的地址（保留原有歌曲和缓存归属）")
                }
                if (!sameServerWithNewAddress) Text("地址变化将作为新服务器，旧曲库记录保留但不显示。")
            }
            if (result.isNotEmpty()) Text(result)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        result = try {
                            val next = candidate()
                            if (RemoteSessionManager.activeSourceId != null) {
                                PlayerController.stopRemoteForSourceSwitch()
                                CacheService.cancelPending()
                            }
                            RemoteSessionManager.test(next)
                        }
                        catch (e: Exception) { "连接失败：${e.message ?: "未知错误"}" }
                        busy = false
                    }
                }) { Text("测试连接") }
                Button(enabled = !busy, onClick = { saveCandidate() }) { Text("保存并使用") }
            }
            if (sourceId != null) {
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            if (RemoteSessionManager.source.value?.protocol == protocol) {
                                val previousId = RemoteSessionManager.activeSourceId
                                PlayerController.stopRemoteForSourceSwitch()
                                CacheService.cancelPending()
                                EasyTierEngine.stop()
                                RemoteSessionManager.deactivate()
                                val old = PlaylistRepository.current.filter { it.sourceId == previousId }.map { it.path }
                                if (old.isNotEmpty()) {
                                    PlayerController.pause()
                                    PlaylistRepository.removeSongs(old)
                                }
                            } else {
                                RemoteSessionManager.removeSavedConfiguration(protocol)
                            }
                            drafts.remove(protocol)
                            fillForm(protocol, null)
                            snackbar.showSnackbar("已移除 ${protocol.label} 配置，历史曲库和缓存仍保留")
                            step = ConfigStep.SELECT
                        } catch (e: Exception) {
                            result = "移除配置失败：${e.message ?: "未知错误"}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("移除 ${protocol.label} 配置") }
            }
        }
    }
}

/** 音乐源类型条目（图标 + 名称 + 简介） */
private data class SourceTypeItem(
    val protocol: RemoteProtocol,
    val icon: ImageVector,
    val description: String,
)

private val kSourceTypeItems = listOf(
    SourceTypeItem(RemoteProtocol.SUBSONIC, Icons.Filled.Dns, "自建音乐服务器（Subsonic API 兼容）"),
    SourceTypeItem(RemoteProtocol.NAVIDROME, Icons.Filled.LibraryMusic, "Navidrome 等 Navidrome 兼容服务"),
    SourceTypeItem(RemoteProtocol.WEBDAV, Icons.Filled.Folder, "WebDAV 网盘上的音乐目录"),
    SourceTypeItem(RemoteProtocol.EMBY, Icons.Filled.Movie, "Emby 媒体服务器"),
)

/**
 * 音乐源类型选择页（首次进入远程配置时的第一步）。
 * 选定类型后再进入该类型的具体配置表单。
 */
@Composable
private fun SourceTypeSelect(
    onSelect: (RemoteProtocol) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("选择远程音乐源", fontWeight = FontWeight.Bold)
        Text("先选择你的音乐来源类型，再进行具体配置。")
        kSourceTypeItems.forEach { item ->
            OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(item.protocol) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(item.protocol.label, fontWeight = FontWeight.Medium)
                        Text(item.description)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    leadingIcon: ImageVector,
    isError: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = { Text(hint) },
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        trailingIcon = trailingIcon, isError = isError, singleLine = true,
        visualTransformation = visualTransformation,
    )
}
