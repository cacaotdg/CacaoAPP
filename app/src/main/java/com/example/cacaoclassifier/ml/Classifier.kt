package com.example.cacaoclassifier.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream

// Data class para almacenar el resultado de una detección
data class DetectionResult(val boundingBox: RectF, val label: String, val confidence: Float)

// El constructor ahora acepta el nombre del modelo a cargar
class Classifier(context: Context, modelName: String) {

    private val module: Module
    private val classNames = listOf("bueno", "malo", "parcial") // Asegúrate que el orden es correcto

    companion object {
        const val MODEL_INPUT_WIDTH = 640
        const val MODEL_INPUT_HEIGHT = 640
        val NORM_MEAN_RGB = floatArrayOf(0.0f, 0.0f, 0.0f)
        val NORM_STD_RGB = floatArrayOf(1.0f, 1.0f, 1.0f)
        const val CONFIDENCE_THRESHOLD = 0.3f
        const val IOU_THRESHOLD = 0.45f
    }

    init {
        // Carga el modelo especificado desde la carpeta assets
        val modelPath = assetFilePath(context, modelName)
        module = Module.load(modelPath)
    }

    fun predict(bitmap: Bitmap): List<DetectionResult> {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT, true)
        val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
            resizedBitmap,
            NORM_MEAN_RGB,
            NORM_STD_RGB
        )

        val output: IValue = module.forward(IValue.from(inputTensor))
        val outputTensor = output.toTensor()
        val results = outputTensor.dataAsFloatArray

        return processYoloOutput(results, bitmap.width, bitmap.height)
    }

    private fun processYoloOutput(results: FloatArray, imageWidth: Int, imageHeight: Int): List<DetectionResult> {
        val numClasses = classNames.size
        val numPredictions = results.size / (4 + numClasses)

        if (results.size % (4 + numClasses) != 0) {
            Log.e("Classifier", "Output tensor size is not as expected. Size: ${results.size}")
            return emptyList()
        }

        val detections = mutableListOf<DetectionResult>()

        for (i in 0 until numPredictions) {
            var maxScore = 0f
            var classIndex = -1
            for (j in 0 until numClasses) {
                val score = results[(4 + j) * numPredictions + i]
                if (score > maxScore) {
                    maxScore = score
                    classIndex = j
                }
            }

            if (maxScore < CONFIDENCE_THRESHOLD) {
                continue
            }

            if (classIndex != -1) {
                val xCenter = results[i] * imageWidth / MODEL_INPUT_WIDTH
                val yCenter = results[numPredictions + i] * imageHeight / MODEL_INPUT_HEIGHT
                val width = results[2 * numPredictions + i] * imageWidth / MODEL_INPUT_WIDTH
                val height = results[3 * numPredictions + i] * imageHeight / MODEL_INPUT_HEIGHT

                val left = xCenter - width / 2
                val top = yCenter - height / 2

                val rect = RectF(left, top, left + width, top + height)
                val label = classNames[classIndex]

                detections.add(DetectionResult(rect, label, maxScore))
            }
        }

        return nonMaxSuppression(detections)
    }

    private fun nonMaxSuppression(detections: List<DetectionResult>): List<DetectionResult> {
        val finalDetections = mutableListOf<DetectionResult>()
        detections.groupBy { it.label }
            .forEach { (_, group) ->
                val sortedGroup = group.sortedByDescending { it.confidence }
                val selected = mutableListOf<DetectionResult>()
                val active = sortedGroup.toMutableList()

                while (active.isNotEmpty()) {
                    val primary = active.first()
                    selected.add(primary)
                    active.removeAt(0)

                    active.removeAll { det ->
                        calculateIoU(primary.boundingBox, det.boundingBox) > IOU_THRESHOLD
                    }
                }
                finalDetections.addAll(selected)
            }
        return finalDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val xA = maxOf(box1.left, box2.left)
        val yA = maxOf(box1.top, box2.top)
        val xB = minOf(box1.right, box2.right)
        val yB = minOf(box1.bottom, box2.bottom)

        val intersectionArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)

        val unionArea = box1Area + box2Area - intersectionArea
        return if (unionArea <= 0) 0f else intersectionArea / unionArea
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            try {
                context.assets.open(assetName).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        val buffer = ByteArray(4 * 1024)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                throw RuntimeException("Error copying asset file: $assetName", e)
            }
        }
        return file.absolutePath
    }
}