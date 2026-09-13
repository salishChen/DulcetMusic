/*
 * 对应 Dart 原文件：lib/pages/subsonic_config_page.dart
 *
 * 远程配置页：
 *   - 读取 SubsonicService.loadConfig() 回填「服务器名称 / 内网地址 / 公网地址 / 用户名 / 密码」；
 *   - 「测试连接」先 saveConfig(tempConfig) 再 ping()，并展示成功 / 失败提示；
 *   - 「保存配置」调用 saveConfig(config) 后提示「配置已保存」；
 *   - 「删除配置」取 SubsonicService.currentConfig?.id 后调用 DatabaseHelper.deleteSubsonicConfig(id)。
 *
 * 校验规则、提示文案、字段顺序与 Dart 原页面逐条一致。
 */

package com.mtechviral.musicfinderexample.feature.subsonic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.SubsonicConfig
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch

/** 测试连接成功的提示色（对应 Dart 的 Colors.green） */
private val TestSuccessGreen = Color(0xFF4CAF50)

/** 测试连接失败的提示色（对应 Dart 的 Colors.red） */
private val TestFailRed = Color(0xFFF44336)

/** 地址类字段的格式错误文案（与 Dart 校验器逐字一致） */
private const val URL_FORMAT_ERROR = "请输入有效的 URL（以 http:// 或 https:// 开头）"

/** 表单校验结果 */
private data class FieldErrors(
    val intranet: String? = null,
    val public: String? = null,
    val username: String? = null,
    val password: String? = null,
) {
    val hasError: Boolean
        get() = intranet != null || public != null || username != null || password != null
}

/** 与 Dart 三个 TextFormField 的 validator 完全一致的表单校验 */
private fun validateForm(
    intranet: String,
    public: String,
    username: String,
    password: String,
): FieldErrors = FieldErrors(
    intranet = when {
        intranet.trim().isEmpty() -> "请输入内网地址"
        !intranet.startsWith("http://") && !intranet.startsWith("https://") -> URL_FORMAT_ERROR
        else -> null
    },
    public = when {
        public.trim().isEmpty() -> "请输入公网地址"
        !public.startsWith("http://") && !public.startsWith("https://") -> URL_FORMAT_ERROR
        else -> null
    },
    username = if (username.trim().isEmpty()) "请输入用户名" else null,
    password = if (password.isEmpty()) "请输入密码" else null,
)

/**
 * 远程配置页。
 *
 * 一级页面，顶栏使用 [PrimaryAppBar]（目录按钮 → 侧边栏）。
 */
@Composable
fun SubsonicConfigScreen() {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 顶层组合时读取一次（CompositionLocal.current 不能在非 @Composable 的 lambda 中读取）
    val openSidebar = LocalOpenSidebar.current

    // ---- 表单字段（对应 Dart 的 5 个 TextEditingController） ----
    var serverName by remember { mutableStateOf("") }
    var intranetUrl by remember { mutableStateOf("") }
    var publicUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // ---- 校验错误 ----
    var errorIntranet by remember { mutableStateOf<String?>(null) }
    var errorPublic by remember { mutableStateOf<String?>(null) }
    var errorUsername by remember { mutableStateOf<String?>(null) }
    var errorPassword by remember { mutableStateOf<String?>(null) }

    // ---- 页面状态 ----
    var loading by remember { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var obscurePassword by remember { mutableStateOf(true) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var configId by remember { mutableStateOf<Long?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingMessage by remember { mutableStateOf<String?>(null) }

    // 首次进入加载已保存的配置（对应 Dart 的 _loadConfig）
    LaunchedEffect(Unit) {
        val config = SubsonicService.loadConfig()
        if (config != null) {
            intranetUrl = config.intranetUrl
            publicUrl = config.publicUrl
            username = config.username
            password = config.password
            serverName = config.serverName ?: ""
        }
        configId = config?.id
        loading = false
    }

    // 提示条（对应 Dart 的 ScaffoldMessenger.showSnackBar，时长 1 秒）
    LaunchedEffect(pendingMessage) {
        val message = pendingMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        pendingMessage = null
    }

    Scaffold(
        topBar = {
            PrimaryAppBar(
                title = "远程配置",
                onMenuClick = openSidebar,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // 服务器名称（可选）
                ConfigTextField(
                    value = serverName,
                    onValueChange = { serverName = it },
                    label = "服务器名称（可选）",
                    hint = "例如：我的音乐服务器",
                    leadingIcon = Icons.Filled.Dns,
                    isError = false,
                )
                Spacer(Modifier.height(16.dp))

                // 内网地址
                ConfigTextField(
                    value = intranetUrl,
                    onValueChange = { intranetUrl = it },
                    label = "内网地址 *",
                    hint = "例如：http://192.168.1.100:4040",
                    leadingIcon = Icons.Filled.Home,
                    isError = errorIntranet != null,
                )
                FieldErrorText(errorIntranet)
                Spacer(Modifier.height(16.dp))

                // 公网地址
                ConfigTextField(
                    value = publicUrl,
                    onValueChange = { publicUrl = it },
                    label = "公网地址 *",
                    hint = "例如：https://music.example.com",
                    leadingIcon = Icons.Filled.Public,
                    isError = errorPublic != null,
                )
                FieldErrorText(errorPublic)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "优先使用内网连接，内网不可达时自动切换公网",
                    fontSize = 12.sp,
                    color = MaterialTheme.ytTextSecondary.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(16.dp))

                // 用户名
                ConfigTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "用户名 *",
                    hint = "",
                    leadingIcon = Icons.Filled.Person,
                    isError = errorUsername != null,
                )
                FieldErrorText(errorUsername)
                Spacer(Modifier.height(16.dp))

                // 密码（可见性切换）
                ConfigTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码 *",
                    hint = "",
                    leadingIcon = Icons.Filled.Lock,
                    isError = errorPassword != null,
                    visualTransformation = if (obscurePassword) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    trailingIcon = {
                        IconButton(onClick = { obscurePassword = !obscurePassword }) {
                            Icon(
                                imageVector = if (obscurePassword) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (obscurePassword) "显示密码" else "隐藏密码",
                            )
                        }
                    },
                )
                FieldErrorText(errorPassword)
                Spacer(Modifier.height(24.dp))

                // 测试结果提示
                val result = testResult
                if (result != null) {
                    val accent = if (testSuccess) TestSuccessGreen else TestFailRed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.1f))
                            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (testSuccess) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Filled.Error
                            },
                            contentDescription = null,
                            tint = accent,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = result, color = accent)
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // 按钮行：测试连接 / 保存配置
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val errors = validateForm(intranetUrl, publicUrl, username, password)
                            errorIntranet = errors.intranet
                            errorPublic = errors.public
                            errorUsername = errors.username
                            errorPassword = errors.password
                            if (!errors.hasError) {
                                testing = true
                                testResult = null
                                // 临时保存配置以测试（与 Dart 一致）
                                val tempConfig = SubsonicConfig(
                                    intranetUrl = intranetUrl.trim(),
                                    publicUrl = publicUrl.trim(),
                                    username = username.trim(),
                                    password = password,
                                    serverName = serverName.trim(),
                                )
                                scope.launch {
                                    SubsonicService.saveConfig(tempConfig)
                                    val success = SubsonicService.ping()
                                    // 保存后重新读库，保证后续「删除配置」能取到真实 id
                                    configId = SubsonicService.loadConfig()?.id
                                    testSuccess = success
                                    testResult = if (success) "连接成功！" else "连接失败，请检查配置"
                                    testing = false
                                }
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.WifiFind,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (testing) "测试中..." else "测试连接")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val errors = validateForm(intranetUrl, publicUrl, username, password)
                            errorIntranet = errors.intranet
                            errorPublic = errors.public
                            errorUsername = errors.username
                            errorPassword = errors.password
                            if (!errors.hasError) {
                                saving = true
                                val config = SubsonicConfig(
                                    intranetUrl = intranetUrl.trim(),
                                    publicUrl = publicUrl.trim(),
                                    username = username.trim(),
                                    password = password,
                                    serverName = serverName.trim(),
                                )
                                scope.launch {
                                    SubsonicService.saveConfig(config)
                                    // 保存后重新读库，保证后续「删除配置」能取到真实 id
                                    configId = SubsonicService.loadConfig()?.id
                                    saving = false
                                    pendingMessage = "配置已保存"
                                }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (saving) "保存中..." else "保存配置")
                    }
                }

                // 删除已保存的配置（取 currentConfig?.id 后删库，并刷新内存配置）
                if (configId != null) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TestFailRed),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("删除配置")
                    }
                }

                Spacer(Modifier.height(24.dp))

                // 使用说明
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
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.ytTextSecondary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "使用说明",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.ytTextSecondary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "1. 填写 Subsonic 服务器的内网和公网地址\n" +
                            "2. 输入用户名和密码\n" +
                            "3. 点击“测试连接”验证配置\n" +
                            "4. 保存后可在“扫描音乐”页面扫描远程音乐\n" +
                            "5. 播放远程音乐会自动缓存到本地",
                        fontSize = 12.sp,
                        color = MaterialTheme.ytTextSecondary.copy(alpha = 0.8f),
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }

    // 删除配置二次确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除配置") },
            text = { Text("确定删除当前服务器配置吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        val id = SubsonicService.currentConfig?.id
                        if (id != null) {
                            scope.launch {
                                DatabaseHelper.deleteSubsonicConfig(id)
                                // 刷新内存中的配置：删除后 loadConfig 返回 null
                                SubsonicService.loadConfig()
                                configId = null
                                intranetUrl = ""
                                publicUrl = ""
                                username = ""
                                password = ""
                                serverName = ""
                                testResult = null
                                pendingMessage = "配置已删除"
                            }
                        }
                    },
                ) {
                    Text("删除", color = TestFailRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 统一的输入框（对应 Dart 的 TextFormField + OutlineInputBorder(12)） */
@Composable
private fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    leadingIcon: ImageVector,
    isError: Boolean,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        // 无 hint 的字段传入空串，占位文案不可见（等价 Dart 不设置 hintText）
        placeholder = { Text(hint) },
        leadingIcon = { Icon(imageVector = leadingIcon, contentDescription = null) },
        trailingIcon = trailingIcon,
        isError = isError,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = visualTransformation,
    )
}

/** 字段下方的红色校验提示（对应 Flutter TextFormField 的 errorText） */
@Composable
private fun FieldErrorText(message: String?) {
    if (message != null) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
    }
}
