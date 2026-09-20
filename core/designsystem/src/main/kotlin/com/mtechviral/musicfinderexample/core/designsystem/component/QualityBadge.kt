package com.mtechviral.musicfinderexample.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytQualityHrBg
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytQualityHrFg
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytQualitySqBg
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytQualitySqFg
import com.mtechviral.musicfinderexample.core.model.AudioQuality

/**
 * 音质徽章（第二十三轮新增，对应截图副标题行开头的 `SQ` / `HR` 小标签）。
 *
 * 取自设计截图的实测值：
 * - SQ：浅青底 `#E0F2F4` + 深青字 `#37779B`
 * - HR：金黄底 `#F8D466` + 近黑字 `#161B1E`
 * - 尺寸约 50x34 px（3x）= 17x11 dp，圆角约 3dp，字号约 9sp
 */
@Composable
fun QualityBadge(
    quality: AudioQuality,
    modifier: Modifier = Modifier,
) {
    val background: Color
    val foreground: Color
    when (quality) {
        AudioQuality.SQ -> {
            background = MaterialTheme.ytQualitySqBg
            foreground = MaterialTheme.ytQualitySqFg
        }
        AudioQuality.HR -> {
            background = MaterialTheme.ytQualityHrBg
            foreground = MaterialTheme.ytQualityHrFg
        }
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = quality.label,
            color = foreground,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 10.sp,
            maxLines = 1,
        )
    }
}
