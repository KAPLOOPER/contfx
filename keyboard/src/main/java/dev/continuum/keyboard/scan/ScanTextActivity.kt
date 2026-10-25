package dev.continuum.keyboard.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.continuum.keyboard.ui.Icons
import dev.continuum.keyboard.ui.LineIcon
import dev.continuum.keyboard.ui.Motion
import dev.continuum.keyboard.ui.Pill
import dev.continuum.keyboard.ui.Txt

private val Ink = Color(0xFF000000)
private val OnInk = Color(0xFFF4F4F1)
private val OnInkSub = Color(0xFFA3A39E)
private val Tonal = Color(0xFF242423)
private val Raised = Color(0xFF1C1C1B)

/** Text read by Scan text, waiting to be typed when the field you came from is back. */
object ScanResult {
    @Volatile
    private var pending: String? = null

    fun offer(text: String) {
        pending = text
    }

    fun take(): String? = pending.also { pending = null }
}

/** One recognised line, and which block of text it came from. */
private data class ScannedLine(val block: Int, val text: String)

private sealed interface ScanState {
    data object Aiming : ScanState
    data object Reading : ScanState
    data class Read(val lines: List<ScannedLine>) : ScanState
    data class Problem(val message: String) : ScanState
}

/**
 * Scan text: aim the camera, take a picture, pick the lines you want, and they're typed
 * where you were. The text is read on the phone; nothing leaves it.
 */
class ScanTextActivity : ComponentActivity() {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScanScreen() }
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    private fun insert(text: String) {
        if (text.isNotBlank()) ScanResult.offer(text)
        finish()
    }

    @Composable
    private fun ScanScreen() {
        val context = LocalContext.current
        var allowed by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
        LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }

        var state by remember { mutableStateOf<ScanState>(ScanState.Aiming) }
        var selected by remember { mutableStateOf(setOf<Int>()) }
        val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

        Column(
            Modifier
                .fillMaxSize()
                .background(Ink)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Scan text", 26.sp, OnInk, Modifier.weight(1f), bold = true)
                Pill("Close", Tonal, OnInk, { finish() })
            }
            Spacer(Modifier.height(4.dp))
            Txt(
                when (state) {
                    is ScanState.Read -> "Tap the lines you want, then insert them."
                    ScanState.Reading -> "Reading…"
                    else -> "Point at some text and take a picture. It's read on your phone."
                },
                15.sp, OnInkSub,
            )
            Spacer(Modifier.height(14.dp))

            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Raised)) {
                when (val s = state) {
                    is ScanState.Read -> LazyColumn(
                        Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(s.lines) { i, line ->
                            val on = i in selected
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (on) OnInk else Tonal)
                                    .pointerInput(i, on) { detectTapGestures { selected = if (on) selected - i else selected + i } }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Txt(line.text, 16.sp, if (on) Ink else OnInk)
                            }
                        }
                    }
                    is ScanState.Problem -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Txt(s.message, 16.sp, OnInk)
                    }
                    else -> if (allowed) {
                        CameraPreview(capture)
                    } else {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Txt("Scan text needs the camera.", 16.sp, OnInk)
                                Spacer(Modifier.height(12.dp))
                                Pill("Allow camera", OnInk, Ink, { ask.launch(Manifest.permission.CAMERA) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth().height(76.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                when (val s = state) {
                    is ScanState.Read -> {
                        Pill("Retake", Tonal, OnInk, {
                            selected = emptySet()
                            state = ScanState.Aiming
                        }, icon = Icons.RETAKE)
                        Pill(if (selected.size == s.lines.size) "Select none" else "Select all", Tonal, OnInk, {
                            selected = if (selected.size == s.lines.size) emptySet() else s.lines.indices.toSet()
                        })
                        Pill("Insert", if (selected.isEmpty()) Tonal else OnInk, if (selected.isEmpty()) OnInkSub else Ink, {
                            if (selected.isNotEmpty()) insert(join(s.lines, selected))
                        }, icon = Icons.CHECK)
                    }
                    is ScanState.Problem -> Pill("Try again", OnInk, Ink, { state = ScanState.Aiming })
                    else -> Shutter(enabled = allowed && state == ScanState.Aiming) {
                        state = ScanState.Reading
                        take(capture) { result ->
                            state = result
                            if (result is ScanState.Read) selected = result.lines.indices.toSet()
                        }
                    }
                }
            }
        }
    }

    private fun join(lines: List<ScannedLine>, picked: Set<Int>): String {
        val chosen = lines.withIndex().filter { it.index in picked }.map { it.value }
        val out = StringBuilder()
        var lastBlock = -1
        for (line in chosen) {
            if (out.isNotEmpty()) out.append(if (line.block == lastBlock) " " else "\n")
            out.append(line.text)
            lastBlock = line.block
        }
        return out.toString()
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun take(capture: ImageCapture, done: (ScanState) -> Unit) {
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val media = image.image
                    if (media == null) {
                        image.close()
                        done(ScanState.Problem("Couldn't read that picture. Try again."))
                        return
                    }
                    val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
                    recognizer.process(input)
                        .addOnSuccessListener { text ->
                            val lines = text.textBlocks.flatMapIndexed { b, block ->
                                block.lines.map { ScannedLine(b, it.text) }
                            }.filter { it.text.isNotBlank() }
                            done(if (lines.isEmpty()) ScanState.Problem("No text found there. Try getting a little closer.") else ScanState.Read(lines))
                        }
                        .addOnFailureListener { done(ScanState.Problem("Couldn't read the text. Try again.")) }
                        .addOnCompleteListener { image.close() }
                }

                override fun onError(exception: ImageCaptureException) {
                    done(ScanState.Problem("The camera didn't take the picture. Try again."))
                }
            },
        )
    }

    @Composable
    private fun CameraPreview(capture: ImageCapture) {
        val owner = LocalLifecycleOwner.current
        AndroidView(
            factory = { ctx ->
                val view = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The shutter: a solid drop of white that squashes under your thumb. */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) 0.86f else 1f, if (pressed) Motion.snap() else Motion.flow(), label = "shutter")
    Box(
        Modifier
            .graphicsLayer {
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.4f
            }
            .size(72.dp)
            .clip(CircleShape)
            .background(OnInk)
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (enabled) {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        }
                    },
                    onTap = { if (enabled) onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(Icons.SHUTTER, Ink, 30.dp)
    }
}
