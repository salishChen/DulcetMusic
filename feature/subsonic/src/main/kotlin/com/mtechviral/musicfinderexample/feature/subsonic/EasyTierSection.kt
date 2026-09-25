/*
 * EasyTier 组网配置卡片（doc/EasyTier集成方案.md §4.2）。
 *
 * 独立于主表单的可选能力：启用后通过去中心化组网访问家庭 Subsonic
 * （免公网 IP / DDNS）。采用无 TUN 端口转发：虚拟网内服务器端口被转发到
 * 本地回环（127.0.0.1:端口），SubsonicService 探测顺序自动变为
 * 「EasyTier → 内网 → 公网」，主表单无需改动。
 */
package com.mtechviral.musicfinderexample.feature.subsonic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.easytier.EasyTierConfig
import com.mtechviral.musicfinderexample.core.easytier.EasyTierConfigStore
import com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import kotlinx.coroutines.launch

/**
 * EasyTier 组网卡片。
 *
 * @param onMessage 提示条消息（复用远程配置页的 Snackbar）
 */
@Composable
fun EasyTierSection(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(EasyTierConfigStore.load(context)) }
    var obscureSecret by remember { mutableStateOf(true) }
    var connecting by remember { mutableStateOf(false) }
    val engineState by EasyTierEngine.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.ytTextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "EasyTier 组网（可选）",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.ytTextSecondary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = config.enabled,
                onCheckedChange = {
                    config = config.copy(enabled = it)
                    if (!it) {
                        EasyTierEngine.disable()
                        onMessage("已关闭 EasyTier 组网")
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "通过去中心化组网访问家庭 Subsonic（免公网 IP / DDNS）：" +
                "服务器端口被转发到本地回环，连接建立后自动优先走虚拟网，" +
                "不可达时回退内网/公网地址。免 ROOT、无需 VPN 权限。",
            fontSize = 12.sp,
            color = MaterialTheme.ytTextSecondary.copy(alpha = 0.8f),
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))

        ConfigTextField(
            value = config.networkName,
            onValueChange = { config = config.copy(networkName = it) },
            label = "网络名（所有节点一致）",
            hint = "例如：my-music-net",
            leadingIcon = Icons.Filled.Dns,
            isError = false,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = config.networkSecret,
            onValueChange = { config = config.copy(networkSecret = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("网络密码（加密存储）") },
            placeholder = { Text("所有节点一致") },
            leadingIcon = { Icon(imageVector = Icons.Filled.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { obscureSecret = !obscureSecret }) {
                    Icon(
                        imageVector = if (obscureSecret) {
                            Icons.Filled.Visibility
                        } else {
                            Icons.Filled.VisibilityOff
                        },
                        contentDescription = null,
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (obscureSecret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        )
        Spacer(Modifier.height(12.dp))
        ConfigTextField(
            value = config.peers,
            onValueChange = { config = config.copy(peers = it) },
            label = "对端/中继地址（可选，逗号分隔）",
            hint = "例如：udp://公网中继:11010",
            leadingIcon = Icons.Filled.Public,
            isError = false,
        )
        Spacer(Modifier.height(12.dp))
        ConfigTextField(
            value = config.serverVirtualIp,
            onValueChange = { config = config.copy(serverVirtualIp = it) },
            label = "服务器虚拟 IP",
            hint = "例如：10.144.144.2",
            leadingIcon = Icons.Filled.Public,
            isError = false,
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Box(modifier = Modifier.weight(1f)) {
                ConfigTextField(
                    value = config.serverPort.toString(),
                    onValueChange = { v ->
                        v.filter { it.isDigit() }.take(5).toIntOrNull()
                            ?.let { config = config.copy(serverPort = it) }
                        if (v.isEmpty()) config = config.copy(serverPort = 0)
                    },
                    label = "服务器端口",
                    hint = "4533",
                    leadingIcon = Icons.Filled.Dns,
                    isError = false,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                ConfigTextField(
                    value = config.localPort.toString(),
                    onValueChange = { v ->
                        v.filter { it.isDigit() }.take(5).toIntOrNull()
                            ?.let { config = config.copy(localPort = it) }
                        if (v.isEmpty()) config = config.copy(localPort = 0)
                    },
                    label = "本地端口",
                    hint = "18080",
                    leadingIcon = Icons.Filled.Dns,
                    isError = false,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        // 连接状态
        val statusText = when (val s = engineState) {
            is EasyTierEngine.State.Disabled -> "未启用"
            is EasyTierEngine.State.Starting -> "启动中…"
            is EasyTierEngine.State.Running -> "运行中（本地转发 ${s.localBaseUrl}）"
            is EasyTierEngine.State.Stopped -> "已停止"
            is EasyTierEngine.State.Error -> "异常：${s.message}"
        }
        Text(
            text = "状态：$statusText",
            fontSize = 12.sp,
            color = MaterialTheme.ytTextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "启用后无需在上方填写「内网地址」，系统会自动优先尝试 " +
                config.localBaseUrl + "；主表单的内网/公网地址仍作为回退。",
            fontSize = 11.sp,
            color = MaterialTheme.ytTextSecondary.copy(alpha = 0.6f),
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = {
                    if (config.serverPort !in 1..65535 || config.localPort !in 1..65535) {
                        onMessage("端口需在 1~65535 之间")
                        return@Button
                    }
                    if (!config.isComplete) {
                        onMessage("网络名 / 网络密码 / 服务器虚拟 IP 必填")
                        return@Button
                    }
                    connecting = true
                    val snapshot = config.copy(enabled = true)
                    config = snapshot
                    scope.launch {
                        EasyTierConfigStore.save(context, snapshot)
                        val ok = EasyTierEngine.start(context, snapshot)
                        connecting = false
                        onMessage(
                            if (ok) {
                                "EasyTier 已启动，正在建立连接…"
                            } else {
                                "EasyTier 启动失败，请查看状态提示"
                            },
                        )
                    }
                },
                enabled = !connecting,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("保存并连接")
                }
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        EasyTierEngine.stop()
                        EasyTierConfigStore.save(context, config.copy(enabled = false))
                        config = config.copy(enabled = false)
                        onMessage("已断开 EasyTier 组网")
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("断开")
            }
        }
    }
}
