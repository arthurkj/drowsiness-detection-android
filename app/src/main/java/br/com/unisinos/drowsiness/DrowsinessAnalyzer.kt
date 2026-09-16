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

        // Limiares PERCLOS e amostragem de 30 FPS (0,5s)
        const val EAR_THRESHOLD = 0.21f
        const val FRAMES_THRESHOLD = 15
    }

    // Classe DTO com resultado da análise
    data class ProcessResult(
        val averageEar: Float,
        val framesCount: Int,
        val isAlertOn: Boolean
    )

    private var framesCount = 0
    var isAlertOn = false
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

    // Função chamada a cada quadro da câmera
    fun processFrames(landmarks: List<NormalizedLandmark>): ProcessResult {
        val leftEar = calculateEar(landmarks, LEFT_EYE_INDEX)
        val rightEar = calculateEar(landmarks, RIGHT_EYE_INDEX)
        val averageEar = (leftEar + rightEar) / 2.0f

        if (averageEar < EAR_THRESHOLD) {
            framesCount++
            if (framesCount >= FRAMES_THRESHOLD) {
                isAlertOn = true
            }
        } else {
            framesCount = 0
            isAlertOn = false
        }

        return ProcessResult(
            averageEar = averageEar,
            framesCount = framesCount,
            isAlertOn = isAlertOn
        )
    }

    fun reset() {
        framesCount = 0
        isAlertOn = false
    }
}