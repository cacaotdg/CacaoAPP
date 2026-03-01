package com.example.cacaoclassifier

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.cacaoclassifier.ml.Classifier
import com.example.cacaoclassifier.ml.DetectionResult
import com.example.cacaoclassifier.ui.theme.CacaoClassifierTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        setContent {
            CacaoClassifierTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CacaoClassifierApp()
                }
            }
        }
    }
}

// --- Funciones de Ayuda ---
fun createImageUri(context: Context): Uri {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val imageFileName = "CacaoClassifier_$timeStamp"
    val storageDir = context.externalCacheDir
    val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile)
}

@Throws(IOException::class)
fun getCorrectlyOrientedBitmap(context: Context, imageUri: Uri): Bitmap? {
    context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
        val exifInterface = ExifInterface(inputStream)
        val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        context.contentResolver.openInputStream(imageUri)?.use { bitmapInputStream ->
            val bitmap = BitmapFactory.decodeStream(bitmapInputStream)
            return when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                else -> bitmap
            }
        }
    }
    return null
}

fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun saveBitmapWithDetections(context: Context, bitmap: Bitmap, results: List<DetectionResult>) {
    val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(resultBitmap)
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    results.forEach { result ->
        when (result.label) {
            "bueno" -> paint.color = Color.Green.toArgb()
            "malo" -> paint.color = Color.Red.toArgb()
            "parcial" -> paint.color = Color.Blue.toArgb()
            else -> paint.color = Color.Yellow.toArgb()
        }
        canvas.drawRect(result.boundingBox, paint)
    }

    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        put(MediaStore.MediaColumns.DISPLAY_NAME, "CacaoResult_$timeStamp.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "CacaoClassifier")
        }
    }

    var stream: OutputStream? = null
    var uri: Uri? = null
    try {
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) throw IOException("Failed to create new MediaStore record.")
        stream = resolver.openOutputStream(uri)
        if (stream == null) throw IOException("Failed to get output stream.")
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        Toast.makeText(context, "Imagen guardada en la galería", Toast.LENGTH_SHORT).show()
    } catch (e: IOException) {
        if (uri != null) resolver.delete(uri, null, null)
        Toast.makeText(context, "Error al guardar la imagen", Toast.LENGTH_SHORT).show()
        Log.e("SaveImage", "Failed to save bitmap", e)
    } finally {
        stream?.close()
    }
}


// --- Composables Principales ---
@Composable
fun CacaoClassifierApp() {
    var selectedModelName by remember { mutableStateOf<String?>(null) }

    if (selectedModelName == null) {
        ModelSelectionScreen(onModelSelected = { modelName -> selectedModelName = modelName })
    } else {
        ClassifierScreen(
            modelName = selectedModelName!!,
            modelTitle = if (selectedModelName == "best.torchscript") "Granos Abiertos" else "Granos Cerrados",
            onBack = { selectedModelName = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionScreen(onModelSelected: (String) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Clasificador de Cacao", fontWeight = FontWeight.Bold) }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Selecciona el tipo de grano a analizar", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(32.dp))

            ModelCard(text = "Granos Abiertos", onClick = { onModelSelected("best.torchscript") })
            Spacer(modifier = Modifier.height(24.dp))
            ModelCard(text = "Granos Cerrados", onClick = { onModelSelected("best2.torchscript") })
        }
    }
}

@Composable
fun ModelCard(text: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassifierScreen(modelName: String, modelTitle: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var results by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tempImageUri by remember { mutableStateOf<Uri?>(null) }
    var isLoadingModel by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var classifier by remember { mutableStateOf<Classifier?>(null) }

    LaunchedEffect(modelName) {
        isLoadingModel = true
        classifier = null
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                val loadedClassifier = Classifier(context, modelName)
                withContext(Dispatchers.Main) {
                    classifier = loadedClassifier
                }
            } catch (e: Throwable) { // CAPTURAMOS CUALQUIER ERROR (Incluso nativos)
                Log.e("ClassifierScreen", "Critical Error loading model", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Error Crítico: ${e.javaClass.simpleName}\n${e.message}\n${e.stackTrace.take(3).joinToString("\n")}"
                }
            }
        }
        isLoadingModel = false
    }

    fun processImage(imageUri: Uri) {
        if (classifier == null) {
            Toast.makeText(context, "El modelo aún no está listo", Toast.LENGTH_SHORT).show()
            return
        }
        
        scope.launch {
            isProcessing = true
            results = emptyList()
            errorMessage = null
            bitmap = withContext(Dispatchers.IO) {
                try {
                    getCorrectlyOrientedBitmap(context, imageUri)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { errorMessage = "Error al cargar imagen: ${e.message}" }
                    null
                }
            }
            bitmap?.let { bitmapToPredict ->
                try {
                    val predictionResults = withContext(Dispatchers.Default) { classifier?.predict(bitmapToPredict) }
                    if (predictionResults != null) {
                        results = predictionResults
                    }
                } catch (e: Throwable) {
                    errorMessage = "Error en predicción: ${e.message}"
                }
            }
            isProcessing = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { processImage(it) } }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = tempImageUri
            uri?.let { processImage(it) }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            val newUri = createImageUri(context)
            tempImageUri = newUri
            cameraLauncher.launch(newUri)
        } else {
            errorMessage = "Permiso de cámara denegado."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(modelTitle, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoadingModel) {
                CircularProgressIndicator()
                Text("Cargando modelo...")
            } else {
                errorMessage?.let { 
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp), fontSize = 12.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { galleryLauncher.launch("image/*") }, Modifier.weight(1f), enabled = !isProcessing && classifier != null) {
                        Icon(Icons.Default.AddPhotoAlternate, "Galería")
                        Spacer(Modifier.size(8.dp))
                        Text("Galería")
                    }
                    Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, Modifier.weight(1f), enabled = !isProcessing && classifier != null) {
                        Icon(Icons.Default.CameraAlt, "Cámara")
                        Spacer(Modifier.size(8.dp))
                        Text("Cámara")
                    }
                }

                if (results.isNotEmpty() && !isProcessing) {
                    Button(onClick = { bitmap?.let { saveBitmapWithDetections(context, it, results) } }, Modifier.fillMaxWidth()) {
                        Text("Guardar Imagen")
                    }
                }

                if (isProcessing) {
                    CircularProgressIndicator()
                    Text("Procesando...")
                }

                DetectionResultView(bitmap = bitmap, results = results)
            }
        }
    }
}

@Composable
fun DetectionResultView(bitmap: Bitmap?, results: List<DetectionResult>) {
    if (bitmap == null) {
        Image(
            painter = painterResource(id = R.drawable.cacaito),
            contentDescription = "Placeholder",
            modifier = Modifier.size(200.dp)
        )
        return
    }

    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()).clip(RoundedCornerShape(12.dp))) {
        Image(bitmap.asImageBitmap(), "Imagen", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) { 
            val scaleX = size.width / bitmap.width
            val scaleY = size.height / bitmap.height
            results.forEach { result ->
                val box = result.boundingBox
                val color = when (result.label) {
                    "bueno" -> Color.Green
                    "malo" -> Color.Red
                    else -> Color.Blue
                }
                drawRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(box.left * scaleX, box.top * scaleY),
                    size = androidx.compose.ui.geometry.Size((box.right - box.left) * scaleX, (box.bottom - box.top) * scaleY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
