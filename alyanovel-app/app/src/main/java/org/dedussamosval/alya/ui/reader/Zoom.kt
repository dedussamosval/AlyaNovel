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

    fun transform(zoom: Float, panX: Float, panY: Float, w: Int, h: Int, lockY: Boolean) {
        scale = (scale * zoom).coerceIn(1f, maxScale)
        val mx = w * (scale - 1f) / 2f
        val my = h * (scale - 1f) / 2f
        offsetX = (offsetX + panX).coerceIn(-mx, mx)
        offsetY = if (lockY) 0f else (offsetY + panY).coerceIn(-my, my)
    }

    fun toggle() { if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 2.5f }
}

/**
 * Щипок и перетаскивание. Работает на проходе Initial, чтобы щипок не листал список под пальцами.
 * listMode = true (лента манги): вертикаль остаётся списку, мы двигаем только по горизонтали.
 */
fun Modifier.zoomGestures(state: ZoomState, listMode: Boolean): Modifier =
    pointerInput(state, listMode) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val pan = e.calculatePan()
                val multi = e.changes.count { it.pressed } > 1
                when {
                    multi -> {
                        state.transform(e.calculateZoom(), pan.x, pan.y, size.width, size.height, lockY = listMode)
                        e.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    state.scale > 1f && !listMode -> {
                        state.transform(1f, pan.x, pan.y, size.width, size.height, lockY = false)
                        e.changes.forEach { if (it.positionChanged()) it.consume() }   // не гасим тапы — иначе не будет двойного тапа
                    }
                    state.scale > 1f && abs(pan.x) > abs(pan.y) ->
                        state.transform(1f, pan.x, 0f, size.width, size.height, lockY = true)
                }
            } while (e.changes.any { it.pressed })
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
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
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