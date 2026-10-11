package dev.ene.companion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.ene.companion.input.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** API 26부터 같은 방식으로 배경 픽셀만 처리한다. 캐릭터·대화에는 blur 레이어를 씌우지 않는다. */
internal suspend fun backgroundBitmap(bytes: ByteArray, radius: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    currentCoroutineContext().ensureActive()
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth !in 1..2048 || options.outHeight !in 1..2048 || bytes.size > 4194304) return@withContext null
    options.inJustDecodeBounds = false
    if (radius > 0) while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize.coerceAtLeast(1) > 256)
        options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null
    try {
        if (radius == 0) { currentCoroutineContext().ensureActive(); return@withContext bitmap.asImageBitmap() }
        val scale = 256f / maxOf(bitmap.width, bitmap.height)
        val fixed = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).roundToInt()),
            maxOf(1, (bitmap.height * scale).roundToInt()), true)
        if (fixed !== bitmap) bitmap.recycle()
        bitmap = fixed
        val width = bitmap.width
        val height = bitmap.height
        var source = IntArray(width * height)
        var target = IntArray(source.size)
        bitmap.getPixels(source, 0, width, 0, 0, width, height)
        repeat(2) {
            for (horizontal in listOf(true, false)) {
                for (y in 0 until height) {
                    currentCoroutineContext().ensureActive()
                    for (x in 0 until width) {
                        var a = 0; var r = 0; var g = 0; var b = 0
                        for (delta in -radius..radius) {
                            val color = source[(if (horizontal) y else (y + delta).coerceIn(0, height - 1)) * width +
                                if (horizontal) (x + delta).coerceIn(0, width - 1) else x]
                            a += color ushr 24; r += color ushr 16 and 255; g += color ushr 8 and 255; b += color and 255
                        }
                        val count = radius * 2 + 1
                        target[y * width + x] = ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                    }
                }
                val swap = source; source = target; target = swap
            }
        }
        Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    } finally { if (radius > 0) bitmap.recycle() }
}

@Composable
internal fun PhoneBackground(state: BackgroundState) {
    val settings = state.settings
    val rendered by produceState<ImageBitmap?>(null, state.image, settings.blur) {
        value = state.image?.let { backgroundBitmap(it, settings.blur) }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF152331), Color(0xFF304858), Color(0xFF18212C))))) {
        if (settings.mode == "solid") Box(Modifier.fillMaxSize().background(Color(settings.color)))
        if (settings.mode == "photo") rendered?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.dim)))
    }
}

@Composable
internal fun BackgroundDialog(controller: BackgroundController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    val value = state.settings
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { controller.select(it.toString()) } }
    fun close() { controller.finish(); onDismiss() }
    AlertDialog(onDismissRequest = ::close, title = { Text("배경 및 효과") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("이 휴대폰에만 저장합니다. 사진을 PC에 보내지 않습니다.")
            TextButton(onClick = controller::reset, enabled = state.loaded && state.status != "preparing") { Text("기본 배경") }
            listOf("청회색" to 0xFF18212CL, "자주색" to 0xFF25202CL, "초록색" to 0xFF16332AL, "검정" to 0xFF111111L).chunked(2).forEach { colors ->
                Row {
                    colors.forEach { (name, color) ->
                        TextButton(onClick = { controller.change(value.copy(mode = "solid", imageId = null, color = color)); controller.finish() },
                            enabled = state.loaded && state.status != "preparing") {
                            Box(Modifier.size(16.dp).background(Color(color)))
                            Text(" $name")
                        }
                    }
                }
            }
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = state.loaded && state.status != "preparing") { Text("배경 사진 선택") }
            Text("어둡게 · ${(value.dim * 100).roundToInt()}%")
            Slider(value.dim, { controller.change(value.copy(dim = it)) }, valueRange = 0f..0.75f,
                onValueChangeFinished = controller::finish, enabled = state.loaded && state.status != "preparing")
            Text("배경 흐림 · ${value.blur}")
            Slider(value.blur.toFloat(), { controller.change(value.copy(blur = it.roundToInt())) }, valueRange = 0f..16f,
                onValueChangeFinished = controller::finish, enabled = state.loaded && state.status != "preparing" && value.mode == "photo")
            when (state.status) {
                "preparing" -> Text("사진을 준비하고 저장하는 중…")
                "error" -> Text("읽기 또는 저장에 실패했습니다. 기존 저장값은 유지됩니다. 다시 선택하거나 설정을 저장해 주세요.")
                "dirty" -> Text("설정 저장 중…")
                else -> Text("배경 효과는 캐릭터와 대화창에는 적용되지 않습니다.")
            }
            if (state.status == "error") TextButton(onClick = controller::finish) { Text("설정 저장 재시도") }
        }
    }, confirmButton = { TextButton(onClick = ::close) { Text("닫기") } })
}
