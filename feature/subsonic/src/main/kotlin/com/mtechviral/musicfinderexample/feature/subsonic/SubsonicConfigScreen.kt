package com.mtechviral.musicfinderexample.feature.subsonic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch
import java.util.UUID

/** Kept under the old route name so existing navigation links continue to work. */
@Composable
fun SubsonicConfigScreen() {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val openSidebar = LocalOpenSidebar.current
    var protocol by remember { mutableStateOf(RemoteProtocol.SUBSONIC) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var intranet by remember { mutableStateOf("") }
    var publicUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
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
            serverIdentity = if (sameSource) current?.serverIdentity else null,
        )
    }

    Scaffold(
        topBar = { PrimaryAppBar(title = "远程配置", onMenuClick = openSidebar) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (loaded) Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(RemoteProtocol.NAVIDROME, RemoteProtocol.SUBSONIC).let { choices ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { option ->
                        FilterChip(selected = protocol == option, onClick = { protocol = option }, label = { Text(option.label) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RemoteProtocol.WEBDAV, RemoteProtocol.EMBY).forEach { option ->
                    FilterChip(selected = protocol == option, onClick = { protocol = option }, label = { Text(option.label) })
                }
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
            val current = RemoteSessionManager.source.value
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
                        busy = false
                    }
                }) { Text("移除当前配置") }
            }
            if (protocol == RemoteProtocol.SUBSONIC || protocol == RemoteProtocol.NAVIDROME) {
                Spacer(Modifier.height(8.dp))
                EasyTierSection(intranetUrl = intranet, onMessage = { message ->
                    scope.launch { snackbar.showSnackbar(message) }
                })
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
