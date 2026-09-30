package br.com.unisinos.drowsiness

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

class DrowsinessAnalyzer {

    companion object {
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

        // Limiares PERCLOS e amostragem de 30 FPS (0,5s)
        const val DEFAULT_EAR_THRESHOLD = 0.21f
        const val EYES_FRAMES_THRESHOLD = 20

        // Configurações do Limiar Adaptativo
        const val CALIBRATION_FRAMES_TARGET = 90 // 90 frames (~3 segundos a 30 FPS)
        const val ADAPTIVE_EAR_FACTOR = 0.72f     // 72% do EAR de olho aberto da pessoa

        // Limiares MAR e amostragem de 30 FPS (3s)
        const val MAR_THRESHOLD = 0.60f // Ponto de corte para abertura crítica da boca
        const val MOUTH_FRAMES_THRESHOLD = 90
    }

    // Classe DTO com resultado da análise
    data class ProcessResult(
        val averageEar: Float,
        val currentMar: Float,
        val eyesFramesCount: Int,
        val mouthFramesCount: Int,
        val isEyesAlertOn: Boolean,
        val isMouthAlertOn: Boolean,
        val isCalibrating: Boolean,
        val currentEarThreshold: Float,
        val baseEarCalculated: Float      // EAR médio capturado (olho 100% aberto)
    )

    private var eyesFramesCount = 0
    var isEyesAlertOn = false
        private set

    // Atributos para o controle da calibração adaptativa
    private val calibrationEarBuffer = mutableListOf<Float>()
    var isCalibrated: Boolean = false
        private set
    var adaptiveEarThreshold: Float = DEFAULT_EAR_THRESHOLD
        private set

    private var baseEarCalculated = DEFAULT_EAR_THRESHOLD / ADAPTIVE_EAR_FACTOR

    private var mouthFramesCount = 0
    var isMouthAlertOn = false
        private set

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
        val averageEar = (leftEar + rightEar) / 2.0f

        // Processamento dos Olhos
        if (!isCalibrated) {
            if (averageEar > 0.0f) {
                calibrationEarBuffer.add(averageEar)
            }

            if (calibrationEarBuffer.size >= CALIBRATION_FRAMES_TARGET) {
                val averageBaseEar = calibrationEarBuffer.average().toFloat()
                this.baseEarCalculated = averageBaseEar

                // Define o limiar individual como 72% da abertura normal do usuário
                adaptiveEarThreshold = averageBaseEar * ADAPTIVE_EAR_FACTOR
                isCalibrated = true
            }

            // Enquanto está calibrando, mantém os contadores zerados para não apitar sem querer
            eyesFramesCount = 0
            isEyesAlertOn = false
        } else {
            if (averageEar < adaptiveEarThreshold) {
                this@DrowsinessAnalyzer.eyesFramesCount++
                if (this@DrowsinessAnalyzer.eyesFramesCount >= EYES_FRAMES_THRESHOLD) {
                    isEyesAlertOn = true
                }
            } else {
                this@DrowsinessAnalyzer.eyesFramesCount = 0
                isEyesAlertOn = false
            }
        }

        // Processamento da boca
        val currentMar = calculateMar(landmarks)

        if (currentMar >= MAR_THRESHOLD) {
            mouthFramesCount++
            if (mouthFramesCount >= MOUTH_FRAMES_THRESHOLD) {
                isMouthAlertOn = true
            }
        } else {
            mouthFramesCount = 0
            isMouthAlertOn = false
        }

        return ProcessResult(
            averageEar = averageEar,
            currentMar = currentMar,
            eyesFramesCount = eyesFramesCount,
            mouthFramesCount = mouthFramesCount,
            isEyesAlertOn = isEyesAlertOn,
            isMouthAlertOn = isMouthAlertOn,
            isCalibrating = !isCalibrated,
            currentEarThreshold = if (isCalibrated) adaptiveEarThreshold else DEFAULT_EAR_THRESHOLD,
            baseEarCalculated = baseEarCalculated
        )
    }

    fun reset() {
        eyesFramesCount = 0
        isEyesAlertOn = false
        mouthFramesCount = 0
        isMouthAlertOn = false
    }

    // Força o reinício da calibração (útil ao reiniciar os testes ou mudar de usuário)
    fun recalibrate() {
        reset()
        calibrationEarBuffer.clear()
        adaptiveEarThreshold = DEFAULT_EAR_THRESHOLD
        isCalibrated = false
    }
}