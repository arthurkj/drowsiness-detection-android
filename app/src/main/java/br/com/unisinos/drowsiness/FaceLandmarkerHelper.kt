package br.com.unisinos.drowsiness // Ajuste para o pacote do seu projeto

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener
) {

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: FaceLandmarkerResult, inputImage: MPImage)
    }

    private var faceLandmarker: FaceLandmarker? = null

    init {
        setupFaceLandmarker()
    }

    private fun setupFaceLandmarker() {
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .setDelegate(Delegate.GPU) // Utiliza aceleração gráfica da GPU no smartphone

            val optionsBuilder = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setNumFaces(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, inputImage ->
                    listener.onResults(result, inputImage)
                }
                .setErrorListener { error ->
                    listener.onError(error.message ?: "Erro desconhecido no MediaPipe")
                }

            faceLandmarker = FaceLandmarker.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            listener.onError("Falha ao inicializar o MediaPipe: ${e.message}")
        }
    }

    // Processa a imagem bitmap vinda da câmera do celular
    fun detectLiveStream(bitmap: Bitmap) {
        val frameTime = SystemClock.uptimeMillis()
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker?.detectAsync(mpImage, frameTime)
    }

    fun clear() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}