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
import androidx.compose.material3.FilterChip
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
import com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.network.RemoteLibrary
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
    var name by remember { mutableStateOf("") }
    var intranet by remember { mutableStateOf("") }
    var publicUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var libraryId by remember { mutableStateOf("") }
    var libraries by remember { mutableStateOf<List<RemoteLibrary>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var sameServerWithNewAddress by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        RemoteSessionManager.load()
        RemoteSessionManager.source.value?.let { current ->
            sourceId = current.id
            protocol = current.protocol
            name = current.displayName
            intranet = current.intranetUrl
            publicUrl = current.publicUrl
            username = current.username
            password = current.password
            rootPath = current.rootPath
            libraryId = current.libraryId
            // 已有配置：直接进入该音乐源的配置页（跳过类型选择）
            step = ConfigStep.CONFIG
        }
        loaded = true
    }

    fun candidate(): RemoteSource {
        require(intranet.isNotBlank() || publicUrl.isNotBlank()) { "请填写内网或公网地址" }
        require(username.isNotBlank()) { "请填写用户名" }
        require(password.isNotBlank()) { "请填写密码" }
        val current = RemoteSessionManager.source.value
        val sameSource = current?.protocol == protocol && current.username == username.trim() &&
            (protocol != RemoteProtocol.WEBDAV || current.rootPath == rootPath.trim()) &&
            current.libraryId == (if (protocol == RemoteProtocol.WEBDAV) "" else libraryId.trim()) &&
            ((current.intranetUrl == intranet.trim() && current.publicUrl == publicUrl.trim()) ||
                sameServerWithNewAddress)
        return RemoteSource(
            id = if (sameSource) current!!.id else UUID.randomUUID().toString(),
            protocol = protocol,
            displayName = name.trim().ifEmpty { protocol.label },
            intranetUrl = intranet.trim(),
            publicUrl = publicUrl.trim(),
            username = username.trim(),
            password = password,
            rootPath = rootPath.trim(),
            libraryId = if (protocol == RemoteProtocol.WEBDAV) "" else libraryId.trim(),
            serverIdentity = if (sameSource) current?.serverIdentity else null,
        )
    }

    Scaffold(
        topBar = { PrimaryAppBar(title = "远程配置", onMenuClick = openSidebar) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!loaded) return@Scaffold
        if (step == ConfigStep.SELECT) {
            SourceTypeSelect(
                modifier = Modifier.fillMaxSize().padding(padding),
                onSelect = { option ->
                    if (protocol != option) {
                        protocol = option
                        libraryId = ""
                        libraries = emptyList()
                    }
                    step = ConfigStep.CONFIG
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
                TextButton(onClick = { step = ConfigStep.SELECT }) { Text("更换类型") }
            }
            ConfigTextField(name, { name = it }, "服务器名称", "例如：家里的音乐服务器", Icons.Filled.Dns)
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
            if (protocol != RemoteProtocol.WEBDAV) {
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        result = try {
                            val next = candidate()
                            if (RemoteSessionManager.activeSourceId != null) {
                                PlayerController.stopRemoteForSourceSwitch()
                                CacheService.cancelPending()
                            }
                            libraries = RemoteSessionManager.libraries(next)
                            if (libraries.none { it.id == libraryId }) libraryId = ""
                            if (libraries.isEmpty()) "没有可选音乐库，将扫描所有可访问音频"
                                else "已读取 ${libraries.size} 个音乐库"
                        } catch (e: Exception) { "读取音乐库失败：${e.message ?: "未知错误"}" }
                        busy = false
                    }
                }) { Text("读取音乐库") }
                if (libraryId.isNotBlank() || libraries.isNotEmpty()) {
                    if (libraryId.isNotBlank() && libraries.none { it.id == libraryId }) {
                        Text("当前音乐库 ID：$libraryId（可重新读取列表）")
                    }
                    FilterChip(selected = libraryId.isBlank(), onClick = { libraryId = "" },
                        label = { Text("全部可访问音乐") })
                    libraries.forEach { library ->
                        FilterChip(selected = libraryId == library.id,
                            onClick = { libraryId = library.id }, label = { Text(library.name) })
                    }
                }
            }
            val current = RemoteSessionManager.source.value
            if (current != null && current.protocol == protocol &&
                current.libraryId != libraryId && protocol != RemoteProtocol.WEBDAV) {
                Text("切换音乐库会建立新的曲库归属，旧库歌曲和缓存仍保留。")
            }
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
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val next = candidate()
                            val previousId = RemoteSessionManager.activeSourceId
                            if (previousId != null) {
                                PlayerController.stopRemoteForSourceSwitch()
                                CacheService.cancelPending()
                                if (previousId != next.id) EasyTierEngine.stop()
                            }
                            RemoteSessionManager.activate(next)
                            if (previousId != null && previousId != next.id) {
                                val old = PlaylistRepository.current.filter { it.sourceId == previousId }.map { it.path }
                                if (old.isNotEmpty()) {
                                    PlayerController.pause()
                                    PlaylistRepository.removeSongs(old)
                                }
                            }
                            sourceId = next.id
                            result = "已保存并启用 ${next.protocol.label}"
                        } catch (e: Exception) {
                            result = "保存失败：${e.message ?: "未知错误"}"
                        }
                        busy = false
                    }
                }) { Text("保存并使用") }
            }
            if (sourceId != null) {
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val previousId = RemoteSessionManager.activeSourceId
                        if (previousId != null) {
                            PlayerController.stopRemoteForSourceSwitch()
                            CacheService.cancelPending()
                            EasyTierEngine.stop()
                        }
                        RemoteSessionManager.deactivate()
                        if (previousId != null) {
                            val old = PlaylistRepository.current.filter { it.sourceId == previousId }.map { it.path }
                            if (old.isNotEmpty()) {
                                PlayerController.pause()
                                PlaylistRepository.removeSongs(old)
                            }
                        }
                        sourceId = null
                        result = "已移除当前配置，历史曲库和缓存仍保留"
                        // 移除后回到类型选择页（下次进入重新走首次流程）
                        step = ConfigStep.SELECT
                        busy = false
                    }
                }) { Text("移除当前配置") }
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("选择远程音乐源", fontWeight = FontWeight.Bold)
        Text("先选择你的音乐来源类型，再进行具体配置。")
        kSourceTypeItems.forEach { item ->
            OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { onSelect(item.protocol) }) {
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
