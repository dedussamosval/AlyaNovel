package org.dedussamosval.alya.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
class ZoomState(private val maxScale: Float = 5f) {
    var scale by mutableFloatStateOf(1f); private set
    var offsetX by mutableFloatStateOf(0f); private set
    var offsetY by mutableFloatStateOf(0f); private set

    /** Лента манги: прокрутить список на delta px, чтобы точка под пальцем осталась на месте. */
    var scrollBy: ((Float) -> Unit)? = null

    /** Зум в точке (fx, fy) — координаты контейнера без трансформации. */
    fun zoomAt(factor: Float, fx: Float, fy: Float, w: Int, h: Int, listMode: Boolean) {
        val s0 = scale
        val s1 = (s0 * factor).coerceIn(1f, maxScale)
        if (s1 == s0) return
        val cx = w / 2f
        val px = cx + (fx - cx - offsetX) / s0          // точка контента под пальцем
        scale = s1
        val mx = w * (s1 - 1f) / 2f
        offsetX = (fx - cx - (px - cx) * s1).coerceIn(-mx, mx)
        if (listMode) {
            // окно списка = H/scale от верха: сдвигаем его, чтобы fy указывал на тот же контент
            scrollBy?.invoke(fy / s0 - fy / s1)
            offsetY = 0f
        } else {
            val cy = h / 2f
            val py = cy + (fy - cy - offsetY) / s0
            val my = h * (s1 - 1f) / 2f
            offsetY = (fy - cy - (py - cy) * s1).coerceIn(-my, my)
        }
    }

    fun pan(dx: Float, dy: Float, w: Int, h: Int, listMode: Boolean, scrollList: Boolean = false) {
        val mx = w * (scale - 1f) / 2f
        offsetX = (offsetX + dx).coerceIn(-mx, mx)
        if (listMode) {
            if (scrollList) scrollBy?.invoke(-dy / scale)
        } else {
            val my = h * (scale - 1f) / 2f
            offsetY = (offsetY + dy).coerceIn(-my, my)
        }
    }

    // Двойной тап: приблизить в точке тапа / вернуть 1×.
    fun toggleAt(fx: Float, fy: Float, w: Int, h: Int, listMode: Boolean) {
        val target = if (scale > 1f) 1f else 2.5f
        zoomAt(target / scale, fx, fy, w, h, listMode)
    }
}

/**
 * Щипок и перетаскивание. Работает на проходе Initial, чтобы щипок не листал список под пальцами.
 * listMode = true (лента манги): вертикаль остаётся списку, мы двигаем только по горизонтали.
 */
fun Modifier.zoomGestures(state: ZoomState, listMode: Boolean): Modifier =
    pointerInput(state, listMode) {
        var lastTapTime = 0L
        var lastTapPos = Offset.Zero
        val maxTapDistance = 100.dp.toPx()

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var moved = Offset.Zero
            var tapCandidate = true
            var lastTime = down.uptimeMillis

            do {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                lastTime = e.changes.maxOf { it.uptimeMillis }
                val pan = e.calculatePan()
                val multi = e.changes.count { it.pressed } > 1

                if (multi) {
                    tapCandidate = false
                    // Масштабируем вокруг центра пальцев на ПРОШЛОМ событии, затем сдвигаем на смещение центра:
                    // так точка контента, которую вы держите пальцами, остаётся под ними.
                    val prevCentroid = e.calculateCentroid(useCurrent = false)
                    val z = e.calculateZoom()
                    if (prevCentroid.isSpecified && z != 1f) {
                        state.zoomAt(z, prevCentroid.x, prevCentroid.y, size.width, size.height, listMode)
                    }
                    state.pan(pan.x, pan.y, size.width, size.height, listMode, scrollList = true)
                    e.changes.forEach { if (it.positionChanged()) it.consume() }
                } else {
                    moved += pan
                    if (moved.getDistance() > viewConfiguration.touchSlop) tapCandidate = false
                    if (!tapCandidate) {
                        when {
                            state.scale > 1f && !listMode -> {
                                state.pan(pan.x, pan.y, size.width, size.height, listMode = false)
                                e.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            state.scale > 1f && abs(pan.x) > abs(pan.y) ->
                                state.pan(pan.x, 0f, size.width, size.height, listMode = true)
                        }
                    }
                }
            } while (e.changes.any { it.pressed })

            // Жест закончился: был ли это тап, и второй ли подряд
            if (tapCandidate && lastTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                val isDouble = lastTime - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis &&
                        (down.position - lastTapPos).getDistance() < maxTapDistance
                if (isDouble) {
                    state.toggleAt(down.position.x, down.position.y, size.width, size.height, listMode)
                    lastTapTime = 0L
                } else {
                    lastTapTime = lastTime
                    lastTapPos = down.position
                }
            }
        }
    }

/**
 * Для ленты манги: окно списка = H/scale, после увеличения в scale раз оно заполняет экран.
 * Так весь список достижим без обрезанных краёв, а скролл идёт 1:1 с пальцем.
 */
fun Modifier.zoomLayer(z: ZoomState): Modifier = this
    .layout { m, c ->
        val h = (c.maxHeight / z.scale).roundToInt()
        val p = m.measure(c.copy(minHeight = h, maxHeight = h))
        layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
    }
    .graphicsLayer {
        scaleX = z.scale; scaleY = z.scale
        transformOrigin = TransformOrigin(0.5f, 0f)
        translationX = z.offsetX
    }

/** Картинка в большем разрешении, чем размер вью, иначе при зуме будет мыло. Ограничиваем только ширину. */
@Composable
fun hiRes(file: File, maxWidth: Int = 2048): ImageRequest {
    val ctx = LocalContext.current
    return remember(file, maxWidth) {
        ImageRequest.Builder(ctx).data(file).size(Size(Dimension.Pixels(maxWidth), Dimension.Undefined)).build()
    }
}

/** Ранобэ: тап по иллюстрации → на весь экран, щипок / двойной тап — зум. */
@Composable
fun FullImageDialog(file: File, caption: String, onDismiss: () -> Unit) {
    val zoom = remember { ZoomState() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = hiRes(file, 4096),
                contentDescription = caption.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .zoomGestures(zoom, listMode = false)
                    .graphicsLayer {
                        scaleX = zoom.scale; scaleY = zoom.scale
                        translationX = zoom.offsetX; translationY = zoom.offsetY
                    },
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White)
            }
        }
    }
}