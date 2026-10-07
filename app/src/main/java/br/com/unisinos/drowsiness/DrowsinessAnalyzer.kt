package br.com.unisinos.drowsiness

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.roundToInt
import kotlin.math.sqrt

class DrowsinessAnalyzer {

    companion object {
        // region Mapeamento de índices
        // Mapeamento dos 6 índices do EAR
        // [0] -> Canto externo
        // [1] -> Superior 1
        // [2] -> Superior 2
        // [3] -> Canto interno
        // [4] -> Inferior 1
        // [5] -> Inferior 2
        private val LEFT_EYE_INDEX = intArrayOf(33, 160, 158, 133, 153, 144)
        private val RIGHT_EYE_INDEX = intArrayOf(362, 385, 387, 263, 373, 380)

        // Mapeamento dos 4 índices do EAR
        // [0] -> Lábio superior interno
        // [1] -> Lábio inferior interno
        // [2] -> Canto esquerdo
        // [3] -> Canto direito
        private val MOUTH_INDEX = intArrayOf(13, 14, 78, 308)
        //endregion

        // region Limiares
        const val DEFAULT_EAR_THRESHOLD = 0.21f
        const val ADAPTIVE_EAR_FACTOR = 0.72f // 72% do EAR de olho aberto da pessoa
        const val MAR_THRESHOLD = 0.50f // Ponto de corte para abertura crítica da boca
        // endregion

        // region Parâmetros de tempo
        const val EYES_DURATION_SECONDS = 0.83f
        const val MOUTH_DURATION_SECONDS = 3f
        const val CALIBRATION_DURATION_MS = 3000L
        // endregion

    }

    // Classe DTO com resultado da análise
    data class ProcessResult(
        val averageEar: Float,
        val currentMar: Float,
        val eyesFramesCount: Int,
        val eyesFramesThreshold: Int,
        val mouthFramesCount: Int,
        val mouthFramesThreshold: Int,
        val isEyesAlertOn: Boolean,
        val isMouthAlertOn: Boolean,
        val isCalibrating: Boolean,
        val currentEarThreshold: Float,
        val baseEarCalculated: Float,
        val measuredFps: Float
    )

    //region Variáveis de calibração
    private var isCalibrated = false
    private var calibrationStartTime = System.currentTimeMillis()
    private var calibrationFrameCount = 0
    private val calibrationEarBuffer = mutableListOf<Float>()
    // endregion

    // region Valores calibrados dinamicamente
    private var baseEarCalculated = 0.29f
    private var adaptiveEarThreshold = DEFAULT_EAR_THRESHOLD
    private var measuredFps = 30.0f
    private var eyesFramesThreshold = (measuredFps * EYES_DURATION_SECONDS).roundToInt()
    private var mouthFramesThreshold = (measuredFps * MOUTH_DURATION_SECONDS).roundToInt()
    //endregion

    //region Count de quadros
    private var eyesFramesCount = 0
    private var mouthFramesCount = 0
    //region

    // Cálculo de distância euclidiana
    private fun calculateDistance(p1: NormalizedLandmark, p2: NormalizedLandmark): Float {
        val dx = p1.x() - p2.x()
        val dy = p1.y() - p2.y()
        return sqrt(dx * dx + dy * dy)
    }

    // Cálculo do EAR de um olho
    // Recebe os landmarks mapeados e array com os índices de cada landmarks necessários para o cálculo de um olho específico
    private fun calculateEar(landmarks: List<NormalizedLandmark>, eyeIndex: IntArray): Float {
        if (landmarks.size < 400) return 0.0f

        val p0 = landmarks[eyeIndex[0]]
        val p1 = landmarks[eyeIndex[1]]
        val p2 = landmarks[eyeIndex[2]]
        val p3 = landmarks[eyeIndex[3]]
        val p4 = landmarks[eyeIndex[4]]
        val p5 = landmarks[eyeIndex[5]]

        val v1 = calculateDistance(p1, p5)
        val v2 = calculateDistance(p2, p4)
        val h = calculateDistance(p0, p3)

        if (h == 0.0f) return 0.0f

        return (v1 + v2) / (2.0f * h)
    }

    private fun calculateMar(landmarks: List<NormalizedLandmark>): Float {
        if (landmarks.size < 400) return 0.0f

        val top = landmarks[MOUTH_INDEX[0]]
        val bottom = landmarks[MOUTH_INDEX[1]]
        val left = landmarks[MOUTH_INDEX[2]]
        val right = landmarks[MOUTH_INDEX[3]]

        val verticalOpening = calculateDistance(top, bottom)
        val horizontalWidth = calculateDistance(left, right)

        if (horizontalWidth == 0.0f) return 0.0f

        return verticalOpening / horizontalWidth
    }

    // Função chamada a cada quadro da câmera
    fun processFrames(landmarks: List<NormalizedLandmark>): ProcessResult {
        val leftEar = calculateEar(landmarks, LEFT_EYE_INDEX)
        val rightEar = calculateEar(landmarks, RIGHT_EYE_INDEX)
        val currentEar = (leftEar + rightEar) / 2.0f
        val currentMar = calculateMar(landmarks)

        //region calibração
        if (!isCalibrated) {
            calibrationFrameCount++
            if (currentEar > 0.0f) {
                calibrationEarBuffer.add(currentEar)
            }

            val elapsedMs = System.currentTimeMillis() - calibrationStartTime

            // Se completou os 3 segundos de calibração inicial
            if (elapsedMs >= CALIBRATION_DURATION_MS && calibrationFrameCount > 10) {
                // A) Calibração de Hardware (FPS Real)
                measuredFps = (calibrationFrameCount.toFloat() / (elapsedMs / 1000.0f))

                // Recalcula os limiares de frames com base no FPS REAL do aparelho
                eyesFramesThreshold = (measuredFps * EYES_DURATION_SECONDS).roundToInt().coerceAtLeast(5)
                mouthFramesThreshold = (measuredFps * MOUTH_DURATION_SECONDS).roundToInt().coerceAtLeast(15)

                // B) Calibração Anatômica (EAR Base)
                val averageBaseEar = if (calibrationEarBuffer.isNotEmpty()) {
                    calibrationEarBuffer.average().toFloat()
                } else 0.29f

                this.baseEarCalculated = averageBaseEar
                this.adaptiveEarThreshold = averageBaseEar * ADAPTIVE_EAR_FACTOR
                this.isCalibrated = true
            }

            return ProcessResult(
                averageEar = currentEar,
                currentMar = currentMar,
                eyesFramesCount = 0,
                eyesFramesThreshold = eyesFramesThreshold,
                mouthFramesCount = 0,
                mouthFramesThreshold = mouthFramesThreshold,
                isEyesAlertOn = false,
                isMouthAlertOn = false,
                isCalibrating = true,
                baseEarCalculated = baseEarCalculated,
                currentEarThreshold = DEFAULT_EAR_THRESHOLD,
                measuredFps = measuredFps
            )
        }
        //endregion

        //region Validação EAR
        var isEyesAlertOn = false
        if (currentEar < adaptiveEarThreshold) {
            eyesFramesCount++
            if (eyesFramesCount >= eyesFramesThreshold) {
                isEyesAlertOn = true
            }
        } else {
            eyesFramesCount = 0
        }
        //endregion

        //region Validação MAR
        var isMouthAlertOn = false
        if (currentMar >= MAR_THRESHOLD) {
            mouthFramesCount++
            if (mouthFramesCount >= mouthFramesThreshold) {
                isMouthAlertOn = true
            }
        } else {
            mouthFramesCount = 0
        }
        //endregion

        return ProcessResult(
            averageEar = currentEar,
            currentMar = currentMar,
            eyesFramesCount = eyesFramesCount,
            eyesFramesThreshold = eyesFramesThreshold,
            mouthFramesCount = mouthFramesCount,
            mouthFramesThreshold = mouthFramesThreshold,
            isEyesAlertOn = isEyesAlertOn,
            isMouthAlertOn = isMouthAlertOn,
            isCalibrating = false,
            baseEarCalculated = baseEarCalculated,
            currentEarThreshold = adaptiveEarThreshold,
            measuredFps = measuredFps
        )
    }

    fun reset() {
        eyesFramesCount = 0
        mouthFramesCount = 0
    }

    fun recalibrate() {
        isCalibrated = false
        calibrationStartTime = System.currentTimeMillis()
        calibrationFrameCount = 0
        calibrationEarBuffer.clear()
        eyesFramesCount = 0
        mouthFramesCount = 0
    }
}