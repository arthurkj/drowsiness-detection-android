package br.com.unisinos.drowsiness // Ajuste para o pacote do seu projeto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), FaceLandmarkerHelper.LandmarkerListener {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvEarValue: TextView
    private lateinit var tvEarStatus: TextView
    private lateinit var tvMarValue: TextView
    private lateinit var tvMarStatus: TextView
    private lateinit var tvGeneralStatus: TextView
    private lateinit var btnRecalibrate: Button
    private lateinit var mainLayout: ConstraintLayout

    private lateinit var cameraExecutor: ExecutorService
    private var helper: FaceLandmarkerHelper? = null
    private val analyzer = DrowsinessAnalyzer()

    // --- COMPONENTES DE ÁUDIO E VIBRAÇÃO ---
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var isAlertOn: Boolean = false

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvEarValue = findViewById(R.id.tvEarValue)
        tvEarStatus = findViewById(R.id.tvEarStatus)
        tvMarValue = findViewById(R.id.tvMarValue)
        tvMarStatus = findViewById(R.id.tvMarStatus)
        tvGeneralStatus = findViewById(R.id.tvGeneralStatus)
        mainLayout = findViewById(R.id.mainLayout)
        btnRecalibrate = findViewById(R.id.btnRecalibrate)

        btnRecalibrate.setOnClickListener {
            analyzer.recalibrate()
            stopAlerts()
            Toast.makeText(
                this,
                "Calibração reiniciada. Olhe fixamente para a frente!",
                Toast.LENGTH_SHORT
            ).show()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Inicialização do Gerador de Som e do Motor Tátil
        initializeAlertComponents()

        if (allPermissionsGranted()) {
            startCameraAndDetector()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    private fun initializeAlertComponents() {
        try {
            // Bipe contínuo em volume máximo
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

            // Tratamento de compatibilidade da API de Vibração
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Erro ao inicializar alertas sonoros/táteis", e)
        }
    }

    private fun startCameraAndDetector() {
        helper = FaceLandmarkerHelper(this, this)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        helper?.detectLiveStream(bitmap)
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("MainActivity", "Erro ao iniciar CameraX", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // --- CALLBACKS DO MEDIAPIPE ---

    override fun onResults(result: FaceLandmarkerResult, inputImage: MPImage) {
        runOnUiThread {
            val faceLandmarks = result.faceLandmarks()

            if (!faceLandmarks.isNullOrEmpty()) {
                val landmarks = faceLandmarks[0]
                val res = analyzer.processFrames(landmarks)

                // 1. Atualização da Caixa 1 (EAR / Olhos e Calibração Adaptativa)
                tvEarValue.text = String.format(
                    Locale.US,
                    "EAR: %.2f (Lim: %.2f)",
                    res.averageEar,
                    res.currentEarThreshold
                )

                if (res.isCalibrating) {
                    tvEarStatus.text = "Calibrando... Olhe para a frente"
                    tvEarStatus.setTextColor(Color.YELLOW)
                } else if (res.averageEar < res.currentEarThreshold) {
                    tvEarStatus.text = "Fechado (${res.eyesFramesCount}/${DrowsinessAnalyzer.EYES_FRAMES_THRESHOLD})"
                    tvEarStatus.setTextColor(Color.YELLOW)
                } else {
                    tvEarStatus.text = String.format(
                        Locale.US,
                        "Aberto (Base: %.2f)",
                        res.baseEarCalculated
                    )
                    tvEarStatus.setTextColor(Color.GREEN)
                }

                // 2. Atualização da Caixa 2 (MAR / Boca)
                tvMarValue.text = String.format(Locale.US, "MAR: %.2f", res.currentMar)
                if (res.currentMar >= DrowsinessAnalyzer.MAR_THRESHOLD) {
                    tvMarStatus.text = "Aberta (${res.mouthFramesCount}/${DrowsinessAnalyzer.MOUTH_FRAMES_THRESHOLD})"
                    tvMarStatus.setTextColor(Color.YELLOW)
                } else {
                    tvMarStatus.text = "Normal"
                    tvMarStatus.setTextColor(Color.GREEN)
                }

                // 3. Atualização do Banner de Status Geral e Alertas
                if (res.isCalibrating) {
                    tvGeneralStatus.text = "CALIBRANDO SISTEMA... AGUARDE (3s)"
                    tvGeneralStatus.setTextColor(Color.YELLOW)
                    mainLayout.setBackgroundColor(Color.BLACK)
                    stopAlerts()

                } else if (res.isEyesAlertOn) {
                    tvGeneralStatus.text = "!!! ALERTA: MICRO-COCHILO DETECTADO (EAR) !!!"
                    tvGeneralStatus.setTextColor(Color.RED)
                    mainLayout.setBackgroundColor(Color.RED)
                    triggerAlerts()

                } else if (res.isMouthAlertOn) {
                    tvGeneralStatus.text = "AVISO: BOCEJO LONGO DETECTADO"
                    tvGeneralStatus.setTextColor(Color.YELLOW)
                    mainLayout.setBackgroundColor(Color.DKGRAY)
                    stopAlerts()

                } else {
                    tvGeneralStatus.text = "SISTEMA ATENTO / OK"
                    tvGeneralStatus.setTextColor(Color.GREEN)
                    mainLayout.setBackgroundColor(Color.BLACK)
                    stopAlerts()
                }

            } else {
                // Rosto ausente / perdido
                analyzer.reset()
                stopAlerts()

                tvEarValue.text = "EAR: --"
                tvEarStatus.text = "Sem Rosto"
                tvEarStatus.setTextColor(Color.GRAY)

                tvMarValue.text = "MAR: --"
                tvMarStatus.text = "Sem Rosto"
                tvMarStatus.setTextColor(Color.GRAY)

                tvGeneralStatus.text = "Aviso: Rosto Nao Detectado"
                tvGeneralStatus.setTextColor(Color.GRAY)
                mainLayout.setBackgroundColor(Color.BLACK)
            }
        }
    }

    // --- LÓGICA DE CONTROLE DO SOM E VIBRAÇÃO ---

    private fun triggerAlerts() {
        if (!isAlertOn) {
            isAlertOn = true

            // Som de Alarme de Alta Frequência
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 5000)

            // Vibração Contínua
            vibrator?.let { v ->
                if (v.hasVibrator()) {
                    val timings = longArrayOf(0, 500, 200)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createWaveform(timings, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(timings, 0)
                    }
                }
            }
        } else {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 5000)
        }
    }

    private fun stopAlerts() {
        if (isAlertOn) {
            isAlertOn = false

            toneGenerator?.stopTone()
            vibrator?.cancel()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (allPermissionsGranted()) {
                startCameraAndDetector()
            } else {
                Toast.makeText(this, "Permissão de câmera negada.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlerts()
        toneGenerator?.release()
        toneGenerator = null
        cameraExecutor.shutdown()
        helper?.clear()
    }
}