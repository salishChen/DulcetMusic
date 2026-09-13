package com.mtechviral.musicfinderexample.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple

/**
 * 圆形文字头像。
 *
 * 对应原 Flutter 工程 `lib/widgets/mp_circle_avatar.dart` 的语义：
 * 无封面时以紫青渐变圆形 + 首字占位（艺术家/歌手场景）。
 */
@Composable
fun MpCircleAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Brush.linearGradient(listOf(BrandPurple, BrandCyan)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.trim().take(1).ifEmpty { "?" },
            color = Color.White,
            fontWeight = FontWeight.W600,
            fontSize = (size.value * 0.4f).sp,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
