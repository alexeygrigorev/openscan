package io.github.alexeygrigorev.openscan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alexeygrigorev.openscan.OpenScanApp
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The foss flavor's own batch capture (docs/FEATURES.md "In-app capture" +
 * "Auto-capture"): the camera stays open, every page is snapped automatically
 * once the detector holds its corners steady, and the user keeps flipping
 * pages until Stop. Stop opens the review grid, where each page can be
 * corrected — drag the detected corners, re-detect, rotate, delete — and the
 * whole batch is warped and saved as one document.
 */
@Composable
fun BatchScanRoute(
    documentId: Long?,
    onDone: (documentId: Long, added: Int, failed: Int) -> Unit,
    onCancel: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val container = (appContext as OpenScanApp).container
    val batchViewModel = viewModel { BatchScanViewModel(documentId, container.repository, container.settings, appContext) }
    // The gallery fallback reuses the shared import flow over the same repo.
    val galleryViewModel = viewModel { CaptureViewModel(container.repository) }

    val permission = Manifest.permission.CAMERA
    var granted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var galleryFallback by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { grantedNow ->
        granted = grantedNow
        showRationale = !grantedNow
    }

    val phase by batchViewModel.phase.collectAsState()
    val pageCount by batchViewModel.pageCount.collectAsState()
    val busy by batchViewModel.busy.collectAsState()
    val error by batchViewModel.error.collectAsState()

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(permission)
    }

    // Saving swallows back (half-way imports must not die); otherwise back
    // leaves review for more capture, and capture asks before discarding.
    BackHandler(enabled = !busy) {
        when {
            galleryFallback -> galleryFallback = false
            phase == BatchScanViewModel.Phase.REVIEW -> batchViewModel.resumeCapture()
            pageCount > 0 -> confirmDiscard = true
            else -> onCancel()
        }
    }

    when {
        galleryFallback -> GalleryImportRoute(galleryViewModel, onDone, onCancel)
        !granted -> PermissionGate(
            showRationale = showRationale,
            onGrant = { permissionLauncher.launch(permission) },
            onGallery = { galleryFallback = true },
            onCancel = onCancel,
        )
        phase == BatchScanViewModel.Phase.CAPTURE -> BatchCapturePhase(
            viewModel = batchViewModel,
            error = error,
            onDismissError = batchViewModel::dismissError,
            onClose = {
                if (pageCount > 0) confirmDiscard = true
                else {
                    batchViewModel.discard()
                    onCancel()
                }
            },
        )
        else -> ReviewPhase(viewModel = batchViewModel, onDone = onDone)
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard captured pages?") },
            text = { Text("$pageCount captured page(s) will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    batchViewModel.discard()
                    onCancel()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep capturing") }
            },
        )
    }
}

/** Camera viewfinder with the auto-capture loop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchCapturePhase(
    viewModel: BatchScanViewModel,
    error: String?,
    onDismissError: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var cameraBroken by remember { mutableStateOf<String?>(null) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    val detection by viewModel.detection.collectAsState()
    val pageCount by viewModel.pageCount.collectAsState()
    val snapTick by viewModel.snapTick.collectAsState()
    var flash by remember { mutableStateOf(false) }

    LaunchedEffect(snapTick) {
        if (snapTick > 0) {
            flash = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(180)
            flash = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
            camera?.cameraControl?.enableTorch(false)
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider
                .getInstance(context)
                .await(ContextCompat.getMainExecutor(context))
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            // Enough resolution for the detector's 1000px
                            // working scale; the still is captured separately.
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analyzerExecutor, FrameAnalyzer(viewModel)) }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            viewModel.captureShutter = {
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val still = image.toUprightBitmap()
                            image.close()
                            viewModel.onStillCaptured(still)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            viewModel.onCaptureFailed(exception)
                        }
                    },
                )
            }

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
                imageCapture,
            )
        } catch (e: Throwable) {
            cameraBroken = e.message ?: "The camera is unavailable on this device."
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Document-corners guide, mapped from analysis-frame coordinates into
        // the FILL_CENTER preview view.
        val det = detection
        if (det?.quad != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val (scale, off) = fillCenterTransform(
                    det.frameWidth, det.frameHeight, size.width, size.height,
                )
                val pts = FloatArray(8) { i ->
                    if (i % 2 == 0) det.quad[i] * scale + off.x else det.quad[i] * scale + off.y
                }
                drawPath(
                    path = quadPath(pts),
                    color = if (det.stable) Color(0xFF4CD964) else Color(0xFFFFD60A),
                    style = Stroke(width = 4.dp.toPx()),
                )
            }
        }

        // Snap feedback.
        if (flash) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.55f)))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top controls.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Stop capturing", tint = Color.White)
                }
                Text(
                    "$pageCount page(s)",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }) {
                    Icon(
                        if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "Toggle torch",
                        tint = Color.White,
                    )
                }
            }

            // Status chip.
            val status = when {
                det == null || det.quad == null -> "Looking for a document…"
                det.stable -> "Capturing…"
                else -> "Hold steady…"
            }
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    status,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {}

            error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(8.dp),
                ) {
                    TextButton(onClick = onDismissError) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            // Bottom controls: manual shutter + Stop.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { viewModel.requestCapture() }) {
                    Text("Snap")
                }
                Button(
                    onClick = { viewModel.stop() },
                    enabled = pageCount > 0,
                    modifier = Modifier.size(width = 120.dp, height = 56.dp),
                ) {
                    Text("Stop")
                }
            }
        }
    }

    if (cameraBroken != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Camera unavailable") },
            text = { Text(cameraBroken ?: "") },
            confirmButton = {
                TextButton(onClick = { cameraBroken = null }) { Text("Close") }
            },
        )
    }
}

/** Review grid: correct each page, then save the batch as one document. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewPhase(
    viewModel: BatchScanViewModel,
    onDone: (documentId: Long, added: Int, failed: Int) -> Unit,
) {
    val pages by viewModel.pages.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val progress by viewModel.saveProgress.collectAsState()
    val error by viewModel.error.collectAsState()
    var editing by remember { mutableStateOf<Long?>(null) }

    BackHandler(enabled = busy) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review pages (${pages.size})") },
                navigationIcon = {
                    IconButton(onClick = { if (!busy) viewModel.resumeCapture() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to camera")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.save { documentId, added, failed ->
                                BatchImport.summary.value = added to failed
                                onDone(documentId, added, failed)
                            }
                        },
                        enabled = !busy && pages.isNotEmpty(),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pages, key = { it.id }) { page ->
                    PageThumbnail(
                        viewModel = viewModel,
                        page = page,
                        onClick = { editing = page.id },
                    )
                }
            }

            if (busy) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.align(Alignment.Center).padding(48.dp),
                    ) {
                        val (done, total) = progress ?: (0 to pages.size)
                        Text(
                            if (progress == null) "Preparing…" else "Saving page ${done + 1} of $total…",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else (done + 1f) / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            error?.let {
                TextButton(
                    onClick = { viewModel.dismissError() },
                    modifier = Modifier.align(Alignment.Center),
                ) { Text(it) }
            }
        }
    }

    editing?.let { id ->
        pages.firstOrNull { it.id == id }?.let { page ->
            PageEditor(viewModel = viewModel, page = page, onDismiss = { editing = null })
        }
    }
}

/** One review-grid tile: warped preview, delete control, rotation badge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageThumbnail(
    viewModel: BatchScanViewModel,
    page: CapturedPage,
    onClick: () -> Unit,
) {
    var thumb by remember(page.id, page.version) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page.id, page.version) {
        thumb = viewModel.thumbnail(page)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        onClick = onClick,
        modifier = Modifier.aspectRatio(0.75f),
    ) {
        Box {
            val image = thumb
            if (image == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                Image(
                    bitmap = image,
                    contentDescription = "Page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (page.rotation != 0) {
                Text(
                    "↻ ${page.rotation * 90}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            if (page.quad == null) {
                Text(
                    "uncropped",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            IconButton(
                onClick = { viewModel.deletePage(page.id) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete page",
                    tint = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape),
                )
            }
        }
    }
}

/** Full-screen editor: drag the quad corners, re-detect, rotate, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageEditor(
    viewModel: BatchScanViewModel,
    page: CapturedPage,
    onDismiss: () -> Unit,
) {
    var frame by remember(page.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page.id) { frame = viewModel.editorFrame(page) }

    // Local working quad in frame coordinates; committed on Done. Re-synced
    // from the page when its version bumps (Auto re-detect).
    var quad by remember(page.id) {
        mutableStateOf(page.quad ?: defaultQuad(page.frameWidth, page.frameHeight))
    }
    var dragCorner by remember(page.id) { mutableStateOf(-1) }
    LaunchedEffect(page.id, page.version) { page.quad?.let { quad = it } }

    BackHandler { onDismiss() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Correct page") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close editor")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.autoDetect(page.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-detect edges")
                    }
                    IconButton(onClick = { viewModel.rotatePage(page.id) }) {
                        Icon(Icons.Filled.RotateRight, contentDescription = "Rotate 90°")
                    }
                    IconButton(onClick = {
                        viewModel.deletePage(page.id)
                        onDismiss()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.setQuad(page.id, quad)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Done") }
                OutlinedButton(
                    onClick = {
                        viewModel.clearQuad(page.id)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Use full frame") }
            }
        },
    ) { padding ->
        val bmp = frame
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp == null) {
                CircularProgressIndicator()
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val (scale, off) = fitTransform(
                        bmp.width, bmp.height,
                        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                    )
                    val density = LocalDensity.current
                    val imageWidth = with(density) { (bmp.width * scale).toDp() }
                    val imageHeight = with(density) { (bmp.height * scale).toDp() }
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .offset { IntOffset(off.x.roundToInt(), off.y.roundToInt()) }
                            .size(imageWidth, imageHeight),
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(page.id) {
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        dragCorner = nearestCorner(quad, pos, scale, off)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val corner = dragCorner
                                        if (corner >= 0) {
                                            val q = quad.copyOf()
                                            q[corner * 2] = (q[corner * 2] + amount.x / scale)
                                                .coerceIn(0f, page.frameWidth.toFloat())
                                            q[corner * 2 + 1] = (q[corner * 2 + 1] + amount.y / scale)
                                                .coerceIn(0f, page.frameHeight.toFloat())
                                            quad = q
                                        }
                                    },
                                    onDragEnd = { dragCorner = -1 },
                                )
                            },
                    ) {
                        val q = quad
                        val pts = FloatArray(8) { i ->
                            if (i % 2 == 0) q[i] * scale + off.x else q[i] * scale + off.y
                        }
                        drawPath(
                            path = quadPath(pts),
                            color = Color(0xFF4CD964),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                        for (i in 0 until 4) {
                            val c = Offset(pts[i * 2], pts[i * 2 + 1])
                            drawCircle(color = Color.White, radius = 10.dp.toPx(), center = c)
                            drawCircle(color = Color(0xFF4CD964), radius = 7.dp.toPx(), center = c)
                        }
                    }
                }
            }
        }
    }
}

/** Camera-permission gate with the gallery fallback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionGate(
    showRationale: Boolean,
    onGrant: () -> Unit,
    onGallery: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Batch capture") }) },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Text(
                if (showRationale) {
                    "OpenScan needs the camera permission to capture pages. " +
                        "Nothing leaves the device unless you turn on sharing in Settings."
                } else {
                    "Batch capture uses the camera."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onGrant) { Text("Allow camera") }
            OutlinedButton(onClick = onGallery) { Text("Import images from the gallery") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Analysis-stream pump: throttled frame conversion feeding the view model. */
private class FrameAnalyzer(private val viewModel: BatchScanViewModel) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!viewModel.wantsFrame(now)) {
            imageProxy.close()
            return
        }
        val bitmap = imageProxy.toUprightBitmap()
        imageProxy.close()
        viewModel.onAnalysisFrame(bitmap)
    }
}

/** Decodes the ImageProxy into an upright RGB bitmap (rotation applied). */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bitmap = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

private fun quadPath(pts: FloatArray): Path = Path().apply {
    moveTo(pts[0], pts[1])
    for (i in 1 until 4) lineTo(pts[i * 2], pts[i * 2 + 1])
    close()
}

/** Default crop when the detector found nothing: a 10% inset frame. */
private fun defaultQuad(w: Int, h: Int): FloatArray = floatArrayOf(
    w * 0.1f, h * 0.1f,
    w * 0.9f, h * 0.1f,
    w * 0.9f, h * 0.9f,
    w * 0.1f, h * 0.9f,
)

/** Index of the quad corner closest to [pos] (view coordinates), else -1. */
private fun nearestCorner(quad: FloatArray, pos: Offset, scale: Float, off: Offset): Int {
    var best = -1
    var bestDist = Float.MAX_VALUE
    for (i in 0 until 4) {
        val cx = quad[i * 2] * scale + off.x
        val cy = quad[i * 2 + 1] * scale + off.y
        val d = hypot(pos.x - cx, pos.y - cy)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    // Ignore drags that start nowhere near any corner.
    return if (bestDist < 200f) best else -1
}

/** FIT mapping from a full-frame space into a view: scale + centered offset. */
private fun fitTransform(frameW: Int, frameH: Int, viewW: Float, viewH: Float): Pair<Float, Offset> {
    val scale = min(viewW / frameW, viewH / frameH)
    return scale to Offset((viewW - frameW * scale) / 2f, (viewH - frameH * scale) / 2f)
}

/** FILL_CENTER mapping, for overlaying quads on the viewfinder. */
private fun fillCenterTransform(frameW: Int, frameH: Int, viewW: Float, viewH: Float): Pair<Float, Offset> {
    val scale = max(viewW / frameW, viewH / frameH)
    return scale to Offset((viewW - frameW * scale) / 2f, (viewH - frameH * scale) / 2f)
}

/** ListenableFuture await without pulling in guava's coroutine adapter. */
private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(
    executor: Executor,
): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (t: Throwable) {
            cont.resumeWithException(t)
        }
    }, executor)
}
