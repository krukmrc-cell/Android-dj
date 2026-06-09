package com.krukkex.radio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Passthrough audio-processor die de PCM-stream van ExoPlayer aftapt, een FFT uitvoert en
 * 32 frequentiebanden (0-255) doorgeeft via [onBands]. Vereist GEEN microfoon-permissie:
 * we lezen onze eigen audiostream, niet de mic. De audio zelf gaat onveranderd door.
 * Ook biedt het 10-band grafische EQ via biquad filters.
 */
class FftAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val FFT_SIZE = 1024
        private const val BANDS = 32
        private const val MIN_FRAME_INTERVAL_MS = 40L // ~25 fps
        // Drempel waarboven de soft-clip-knie ingrijpt (laat normale audio ongemoeid).
        private const val SOFT_CLIP_THRESHOLD = 0.9f

        // Wordt door MainActivity gezet zodat de banden naar de WebView gepusht worden.
        @Volatile
        var onBands: ((IntArray) -> Unit)? = null

        // 10-band EQ (60Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz)
        // Waarden in dB (-12..+12), IndexOutOfBounds safe.
        val eqBands = FloatArray(10)  // initialized to 0

        // Aantal kanalen waarvoor we aparte filter-banken bijhouden (stereo).
        private const val MAX_EQ_CHANNELS = 2
        // 10-band biquad filters (cascade) — PER KANAAL. Een biquad heeft interne state
        // (y1/y2); links en rechts moeten daarom hun eigen filter-instanties hebben,
        // anders vervuilen ze elkaars state en wordt maar één kanaal correct gefilterd.
        private val filters = Array(MAX_EQ_CHANNELS) { Array(10) { BiquadFilter() } }
        // Frequenties in Hz (exponentieel: 60, 125, 250, 500, 1k, 2k, 4k, 8k, 16k)
        private val centerFreqs = floatArrayOf(60f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f, 20000f)
        // Per-band Q (butterworth-achtig, licht brede curve)
        private val qFactors = floatArrayOf(0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f)

        var sampleRate = 44100

        fun updateFilterBand(bandIndex: Int) {
            if (bandIndex in 0..9 && sampleRate > 0) {
                val gainDb = eqBands[bandIndex]
                val freqHz = centerFreqs[bandIndex]
                val q = qFactors[bandIndex]
                // Zelfde coëfficiënten voor elk kanaal (alleen de filter-state verschilt).
                for (ch in 0 until MAX_EQ_CHANNELS) {
                    filters[ch][bandIndex].setPeakingEQ(freqHz, q, gainDb, sampleRate)
                }
            }
        }
    }

    private var channelCount = 2
    private var analyze = false

    private val mono = FloatArray(FFT_SIZE)
    private var monoWritten = 0
    private val window = FloatArray(FFT_SIZE) {
        (0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFT_SIZE - 1))).toFloat() // Hann
    }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private var lastEmit = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        // Analyseer alleen 16-bit PCM; bij andere formaten gewoon passthrough zonder FFT.
        analyze = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && channelCount > 0
        monoWritten = 0
        // Update all filters met de nieuwe sample rate
        updateEqFilters()
        return inputAudioFormat // formaat ongewijzigd → passthrough
    }

    private fun updateEqFilters() {
        for (i in 0 until 10) {
            updateFilterBand(i)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val startPos = inputBuffer.position()

        // Alleen analyseren als er een luisteraar is (app op voorgrond). Met scherm uit /
        // achtergrond zet MainActivity onBands op null → puur passthrough, nul extra werk.
        if (analyze && onBands != null) {
            accumulate(inputBuffer, startPos, size)
            inputBuffer.position(startPos)
        }

        // Passthrough: kopieer de input naar output
        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()

        // Pas EQ toe op de output buffer (in-place DSP)
        if (analyze && shouldApplyEq()) {
            applyEqualizer(output)
        }
    }

    private fun shouldApplyEq(): Boolean {
        // Controleer of minstens één band niet-nul is
        return eqBands.any { it != 0f }
    }

    private fun applyEqualizer(buffer: ByteBuffer) {
        val frameSize = 2 * channelCount // 16-bit
        val startPos = buffer.position()
        val endPos = buffer.limit()
        var pos = startPos

        while (pos + frameSize <= endPos) {
            // Filter ELK kanaal afzonderlijk door zijn eigen filter-bank en schrijf het
            // resultaat terug op de juiste positie (anders blijft rechts ongefilterd).
            for (ch in 0 until channelCount) {
                val sampleOffset = pos + ch * 2
                val lo = buffer.get(sampleOffset).toInt() and 0xFF
                val hi = buffer.get(sampleOffset + 1).toInt() // sign-extended
                var sample = ((hi shl 8) or lo).toFloat() / 32768f

                val chFilters = filters[ch.coerceAtMost(MAX_EQ_CHANNELS - 1)]
                for (i in 0 until 10) {
                    sample = chFilters[i].process(sample)
                }

                // Soft-clip: bij het optellen van meerdere geboostte banden kan het
                // signaal boven full-scale uitkomen. Een zachte tanh-knie boven 0.9
                // voorkomt harde digitale clipping (= hoorbare vervorming) terwijl
                // normale niveaus exact lineair (transparant) blijven.
                sample = softClip(sample)

                // Write back (clamp als laatste vangnet voor het randgeval ±1.0)
                val out = (sample * 32768f).toInt().coerceIn(-32768, 32767)
                buffer.put(sampleOffset, (out and 0xFF).toByte())
                buffer.put(sampleOffset + 1, ((out shr 8) and 0xFF).toByte())
            }

            pos += frameSize
        }
        buffer.position(startPos)
    }

    override fun onFlush() {
        monoWritten = 0
        resetFilterState()
    }

    override fun onReset() {
        monoWritten = 0
        lastEmit = 0
        resetFilterState()
    }

    private fun resetFilterState() {
        for (ch in 0 until MAX_EQ_CHANNELS) {
            for (i in 0 until 10) filters[ch][i].reset()
        }
    }

    /** Zachte saturatie boven ±[SOFT_CLIP_THRESHOLD]; daaronder exact lineair. */
    private fun softClip(x: Float): Float {
        val t = SOFT_CLIP_THRESHOLD
        return when {
            x > t -> t + (1f - t) * tanh((x - t) / (1f - t))
            x < -t -> -t + (1f - t) * tanh((x + t) / (1f - t))
            else -> x
        }
    }

    private fun accumulate(buffer: ByteBuffer, start: Int, size: Int) {
        val frameSize = 2 * channelCount // 16-bit
        var pos = start
        val end = start + size
        while (pos + frameSize <= end) {
            var sum = 0
            for (ch in 0 until channelCount) {
                val lo = buffer.get(pos).toInt() and 0xFF
                val hi = buffer.get(pos + 1).toInt() // sign-extended
                sum += (hi shl 8) or lo
                pos += 2
            }
            mono[monoWritten++] = sum.toFloat() / channelCount / 32768f
            if (monoWritten >= FFT_SIZE) {
                maybeEmit()
                monoWritten = 0
            }
        }
    }

    private fun maybeEmit() {
        val now = System.currentTimeMillis()
        if (now - lastEmit < MIN_FRAME_INTERVAL_MS) return
        val callback = onBands ?: return
        lastEmit = now
        callback(computeBands())
    }

    private fun computeBands(): IntArray {
        for (i in 0 until FFT_SIZE) {
            re[i] = mono[i] * window[i]
            im[i] = 0f
        }
        fft(re, im)

        val half = FFT_SIZE / 2
        val minBin = 1.0
        val maxBin = (half - 1).toDouble()
        val ratio = maxBin / minBin
        val bands = IntArray(BANDS)

        for (b in 0 until BANDS) {
            val lo = (minBin * ratio.pow(b.toDouble() / BANDS)).toInt().coerceIn(1, half - 1)
            val hi = (minBin * ratio.pow((b + 1).toDouble() / BANDS)).toInt().coerceIn(lo, half - 1)
            var sum = 0f
            var count = 0
            for (k in lo..hi) {
                sum += hypot(re[k], im[k])
                count++
            }
            val avg = if (count > 0) sum / count else 0f
            // Normaliseer + log-compressie, map ~[-80,-20] dB naar [0,255] (visueel afstembaar).
            val norm = avg / (FFT_SIZE / 2f)
            val db = 20.0 * log10(norm + 1e-7)
            bands[b] = (((db + 80.0) / 60.0) * 255.0).coerceIn(0.0, 255.0).toInt()
        }
        return bands
    }

    /** In-place iteratieve radix-2 Cooley-Tukey FFT (FFT_SIZE is een macht van 2). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                val halfLen = len / 2
                for (k in 0 until halfLen) {
                    val tRe = re[i + k + halfLen] * curRe - im[i + k + halfLen] * curIm
                    val tIm = re[i + k + halfLen] * curIm + im[i + k + halfLen] * curRe
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    re[i + k] = aRe + tRe
                    im[i + k] = aIm + tIm
                    re[i + k + halfLen] = aRe - tRe
                    im[i + k + halfLen] = aIm - tIm
                    val nextCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextCurRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** 2e-orde IIR biquad filter voor peaking EQ */
class BiquadFilter {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var y1 = 0f  // z⁻¹
    private var y2 = 0f  // z⁻²

    fun setPeakingEQ(freqHz: Float, q: Float, gainDb: Float, sampleRate: Int) {
        val a = 10f.pow(gainDb / 40f) // Peaking EQ amplitude
        // Houd de center-frequentie weg van Nyquist (0.45·fs): RBJ peaking-filters
        // worden vlak onder Nyquist instabiel/onnauwkeurig ("cramping"). Bij 44,1 kHz
        // landt de 20 kHz-band zo op ~19,8 kHz — onhoorbaar, maar numeriek stabiel.
        val f = freqHz.coerceAtMost(sampleRate * 0.45f)
        val w0 = 2f * Math.PI.toFloat() * f / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2f * q)

        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cosW0) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha / a) / a0
    }

    fun process(input: Float): Float {
        // Direct Form II transposed
        val output = input * b0 + y1
        y1 = input * b1 - a1 * output + y2
        y2 = input * b2 - a2 * output
        return output
    }

    fun reset() {
        y1 = 0f
        y2 = 0f
    }
}
