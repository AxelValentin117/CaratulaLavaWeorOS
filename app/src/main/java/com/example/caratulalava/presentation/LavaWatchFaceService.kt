package com.example.caratulalava.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ID de la opción de configuración
private const val COLOR_THEME_ID = "color_theme_setting"

// Definición de paletas de color
enum class LavaTheme(
    val id: String,
    val title: String,
    val bgColor: Int,
    val bubbleColors: IntArray
) {
    MAGMA(
        "magma", "Magma Clásico",
        Color.parseColor("#15001A"),
        intArrayOf(
            Color.parseColor("#FF3D00"),
            Color.parseColor("#FF9100"),
            Color.parseColor("#FF1744"),
            Color.parseColor("#FFD600")
        )
    ),
    NEON_GREEN(
        "toxic_green", "Verde Radiactivo",
        Color.parseColor("#00150B"),
        intArrayOf(
            Color.parseColor("#00E676"),
            Color.parseColor("#76FF03"),
            Color.parseColor("#00B0FF"),
            Color.parseColor("#1DE9B6")
        )
    ),
    CYBER_BLUE(
        "cyber_blue", "Océano Neón",
        Color.parseColor("#030A1C"),
        intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#2979FF"),
            Color.parseColor("#651FFF"),
            Color.parseColor("#F50057")
        )
    )
}

class LavaWatchFaceService : WatchFaceService() {

    // Registra el menú de personalización nativo en el sistema Wear OS
    override fun createUserStyleSchema(): UserStyleSchema {
        val themeOptions = LavaTheme.values().map { theme ->
            UserStyleSetting.ListUserStyleSetting.ListOption(
                UserStyleSetting.Option.Id(theme.id),
                theme.title,
                icon = null
            )
        }

        val themeSetting = UserStyleSetting.ListUserStyleSetting(
            UserStyleSetting.Id(COLOR_THEME_ID),
            "Tema de Lava",
            "Selecciona el color del fondo y la lava",
            icon = null,
            options = themeOptions,
            listOf(WatchFaceLayer.BASE)
        )

        return UserStyleSchema(listOf(themeSetting))
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = LavaCanvasRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository
        )

        return WatchFace(
            watchFaceType = WatchFaceType.DIGITAL,
            renderer = renderer
        )
    }
}

data class LavaBubble(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val radius: Float,
    var colorIndex: Int,
    val buoyancyFactor: Float
)

class LavaCanvasRenderer(
    context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository
) : Renderer.CanvasRenderer2<Renderer.SharedAssets>(
    surfaceHolder = surfaceHolder,
    currentUserStyleRepository = currentUserStyleRepository,
    watchState = watchState,
    canvasType = CanvasType.HARDWARE,
    interactiveDrawModeUpdateDelayMillis = 16L,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gravityX = 0f
    private var gravityY = 9.8f

    private val bubblePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val timePaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = 76f
        isFakeBoldText = true
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val bubbles = mutableListOf<LavaBubble>()
    private var isInitialized = false

    // Tema seleccionado actualmente
    private var currentTheme: LavaTheme = LavaTheme.MAGMA

    init {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Observador que actualiza los colores cuando el usuario cambia el estilo en el reloj
        currentUserStyleRepository.userStyle.asStateFlow().let { styleFlow ->
            // Al arrancar, leer el tema configurado
            val selectedOptionId = currentUserStyleRepository.userStyle.value[
                UserStyleSetting.Id(COLOR_THEME_ID)
            ]?.id?.value

            currentTheme = LavaTheme.values().find { it.id == selectedOptionId } ?: LavaTheme.MAGMA
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            gravityX = -event.values[0]
            gravityY = event.values[1]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun initBubbles(width: Float, height: Float) {
        bubbles.clear()
        val centerX = width / 2f
        val centerY = height / 2f

        for (i in 0 until 8) {
            bubbles.add(
                LavaBubble(
                    x = centerX + Random.nextFloat() * 120f - 60f,
                    y = centerY + Random.nextFloat() * 120f - 60f,
                    radius = Random.nextFloat() * 18f + 26f, // Tamaños variados
                    colorIndex = i % 4,
                    buoyancyFactor = Random.nextFloat() * 2.5f + 3.0f // Flotaciones distintas
                )
            )
        }
        isInitialized = true
    }

    override suspend fun createSharedAssets(): Renderer.SharedAssets {
        return object : Renderer.SharedAssets {
            override fun onDestroy() {}
        }
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: Renderer.SharedAssets
    ) {
        // Actualizar el tema en tiempo real si el usuario lo cambió
        val selectedId = currentUserStyleRepository.userStyle.value[UserStyleSetting.Id(COLOR_THEME_ID)]?.id?.value
        currentTheme = LavaTheme.values().find { it.id == selectedId } ?: currentTheme

        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        if (!isInitialized && width > 0 && height > 0) {
            initBubbles(width, height)
        }

        val isAmbient = renderParameters.drawMode == DrawMode.AMBIENT

        if (isAmbient) {
            canvas.drawColor(Color.BLACK)
            timePaint.color = Color.DKGRAY
            val timeString = zonedDateTime.format(timeFormatter)
            val textY = centerY - ((timePaint.descent() + timePaint.ascent()) / 2)
            canvas.drawText(timeString, centerX, textY, timePaint)
        } else {
            // Fondo del tema actual
            canvas.drawColor(currentTheme.bgColor)

            val screenRadius = width / 2f

            // 1. Aceleración y fricción
            for (bubble in bubbles) {
                bubble.vx += gravityX * 0.12f
                bubble.vy += (gravityY - bubble.buoyancyFactor) * 0.12f

                bubble.vx *= 0.94f
                bubble.vy *= 0.94f

                bubble.x += bubble.vx
                bubble.y += bubble.vy

                // Rebote con los límites circulares del reloj
                val dx = bubble.x - centerX
                val dy = bubble.y - centerY
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val maxDist = screenRadius - bubble.radius

                if (dist > maxDist) {
                    val angle = atan2(dy.toDouble(), dx.toDouble())
                    bubble.x = (centerX + cos(angle) * maxDist).toFloat()
                    bubble.y = (centerY + sin(angle) * maxDist).toFloat()
                    bubble.vx *= -0.35f
                    bubble.vy *= -0.35f
                }
            }

            // 2. Repulsión entre burbujas (para que no se amontonen en un solo círculo)
            for (i in 0 until bubbles.size) {
                for (j in i + 1 until bubbles.size) {
                    val b1 = bubbles[i]
                    val b2 = bubbles[j]
                    val dx = b2.x - b1.x
                    val dy = b2.y - b1.y
                    val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    val minDist = b1.radius + b2.radius

                    if (dist < minDist && dist > 0f) {
                        val overlap = minDist - dist
                        val nx = dx / dist
                        val ny = dy / dist

                        b1.x -= nx * overlap * 0.5f
                        b1.y -= ny * overlap * 0.5f
                        b2.x += nx * overlap * 0.5f
                        b2.y += ny * overlap * 0.5f

                        // Empuje elástico mutuo
                        b1.vx -= nx * 0.3f
                        b1.vy -= ny * 0.3f
                        b2.vx += nx * 0.3f
                        b2.vy += ny * 0.3f
                    }
                }
            }

            // 3. Dibujado de burbujas con la paleta activa
            val colors = currentTheme.bubbleColors
            for (bubble in bubbles) {
                bubblePaint.color = colors[bubble.colorIndex % colors.size]
                canvas.drawCircle(bubble.x, bubble.y, bubble.radius, bubblePaint)
            }

            // 4. Hora central nítida con sombra tenue
            timePaint.color = Color.WHITE
            val timeString = zonedDateTime.format(timeFormatter)
            val textY = centerY - ((timePaint.descent() + timePaint.ascent()) / 2)
            canvas.drawText(timeString, centerX, textY, timePaint)
        }
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: Renderer.SharedAssets
    ) {}
}