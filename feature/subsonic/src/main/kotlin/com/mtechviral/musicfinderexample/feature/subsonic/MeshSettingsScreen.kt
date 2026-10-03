package com.mtechviral.musicfinderexample.feature.subsonic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch

/**
 * 「组网设置」一级页面（侧边栏「设置」按钮上方的入口）。
 *
 * EasyTier 组网配置从远程配置页**提取**为独立页面：
 * 组网设置独立展示，转发目标由当前远程源的「内网地址」推导。
 */
@Composable
fun MeshSettingsScreen() {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val source by RemoteSessionManager.source.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { PrimaryAppBar(title = "组网设置", onMenuClick = LocalOpenSidebar.current) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EasyTierSection(
                intranetUrl = source?.intranetUrl ?: "",
                onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            )
        }
    }
}
