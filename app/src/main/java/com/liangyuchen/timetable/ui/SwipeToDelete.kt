package com.liangyuchen.timetable.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 左滑露出「删除」。
 *
 * 用手指拖动位移，松手时：滑过一半就滑到删除位，否则弹回去。
 * 列表里不再常驻一个 ✕，误触少很多。
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val revealWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }

    Box(modifier = Modifier.fillMaxWidth()) {
        // 底层：删除按钮
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 4.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                .clickable {
                    scope.launch {
                        offset.animateTo(-revealWidthPx * 1.4f, tween(120))
                        onDelete()
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                "删除",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 16.dp),
            )
        }

        Layout(
            content = { content() },
            modifier = Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val target = if (offset.value < -revealWidthPx / 2f) -revealWidthPx else 0f
                            offset.animateTo(target, tween(180))
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f, tween(160)) }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        offset.snapTo((offset.value + dragAmount).coerceIn(-revealWidthPx, 0f))
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
