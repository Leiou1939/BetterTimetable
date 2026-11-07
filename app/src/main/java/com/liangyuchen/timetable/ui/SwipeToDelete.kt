package com.liangyuchen.timetable.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 左滑删除：滑过一行宽度的 1/3 就直接删掉，没到就弹回去。
 *
 * 不做"先滑开、再点删除"两步——那一步在小屏幕上很容易点不中。
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val screenWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // 底层：滑开时露出的红色删除区
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 4.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                "删除",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 18.dp),
            )
        }

        Layout(
            content = { content() },
            modifier = Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = -screenWidthPx * 0.33f
                        if (offset.value <= threshold) {
                            scope.launch {
                                offset.animateTo(-screenWidthPx, tween(140))
                                onDelete()
                            }
                        } else {
                            scope.launch { offset.animateTo(0f, tween(180)) }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f, tween(160)) }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        // 只允许往左拉
                        offset.snapTo((offset.value + dragAmount).coerceIn(-screenWidthPx, 0f))
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            val width = placeables.maxOfOrNull { it.width } ?: constraints.maxWidth
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                placeables.forEach { it.placeRelative(offset.value.roundToInt(), 0) }
            }
        }
    }
}
