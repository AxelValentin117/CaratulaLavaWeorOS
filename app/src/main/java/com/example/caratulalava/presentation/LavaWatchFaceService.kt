package com.example.caratulalava.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
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
import com.example.caratulalava.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val COLOR_THEME_ID = "color_theme_setting"

enum class LavaTheme(
    val id: String,
    val titleResId: Int,
    val bgColor: Int,
    val bubbleColors: IntArray,
) {
    MAGMA(
        "magma", R.string.theme_magma,
        Color.parseColor("#15001A"),
        intArrayOf(
            Color.parseColor("#FF3D00"),
            Color.parseColor("#FF9100"),
            Color.parseColor("#FF1744"),
            Color.parseColor("#FFD600"),
        ),
    ),
    NEON_GREEN(
        "toxic_green", R.string.theme_toxic_green,
        Color.parseColor("#00150B"),
        intArrayOf(
            Color.parseColor("#00E676"),
            Color.parseColor("#76FF03"),
            Color.parseColor("#00B0FF"),
            Color.parseColor("#1DE9B6"),
        ),
    ),
    CYBER_BLUE(
        "cyber_blue", R.string.theme_cyber_blue,
        Color.parseColor("#030A1C"),
        intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#2979FF"),
            Color.parseColor("#651FFF"),
            Color.parseColor("#F50057"),
        ),
    );
}

class LavaWatchFaceService : WatchFaceService() {

    override fun createUserStyleSchema(): UserStyleSchema {
        val themeOptions = LavaTheme.entries.map { theme ->
            UserStyleSetting.ListUserStyleSetting.ListOption(
                UserStyleSetting.Option.Id(theme.id),
                resources,
                theme.titleResId,
                theme.titleResId,
                null
            )
        }

        val themeSetting = UserStyleSetting.ListUserStyleSetting(
            UserStyleSetting.Id(COLOR_THEME_ID),
            resources,
            R.string.theme_setting_name,
            R.string.theme_setting_description,
            null,
            themeOptions,
            listOf(WatchFaceLayer.BASE),
            themeOptions.first()
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
            userStyleRepo = currentUserStyleRepository
        )

        return WatchFace(
            watchFaceType = WatchFaceType.DIGITAL,
            renderer = renderer
        )
    }
}

data class LavaBlob(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val radius: Float,
    val colorIndex: Int,
    var buoyancy: Float = 0f,
    var targetBuoyancy: Float = 0f
)

class LavaCanvasRenderer(
    context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    private val userStyleRepo: CurrentUserStyleRepository
) : Renderer.CanvasRenderer2<Renderer.SharedAssets>(
    surfaceHolder = surfaceHolder,
    currentUserStyleRepository = userStyleRepo,
    watchState = watchState,
    canvasType = CanvasType.HARDWARE,
    interactiveDrawModeUpdateDelayMillis = 16L,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var tiltX = 0f
    private var tiltY = 0f

    // Variables para la agitación
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var agitation = 0f

    private val gooeyPaint = Paint().apply {
        // m=80 y s=-48 aumentan masivamente el "aura" invisible de las gotas.
        // Esto hace que la fusión (metaballs) sea mucho más gruesa y deforme los círculos
        // convirtiéndolos en un solo óvalo o masa líquida.
        val m = 80f
        val s = -255f * 48f
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, m, s
        )))
    }

    private val blobPaints = mutableMapOf<Int, Paint>()

    private val timePaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = 80f
        isFakeBoldText = true
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val blobs = mutableListOf<LavaBlob>()
    private var isInitialized = false
    private var currentTheme: LavaTheme = LavaTheme.MAGMA

    private val coroutineScope = CoroutineScope(Dispatchers.Main.immediate)

    init {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        coroutineScope.launch {
            userStyleRepo.userStyle.collect { userStyle ->
                val selectedOption = userStyle.entries.find {
                    it.key.id.value.contentEquals(UserStyleSetting.Id(COLOR_THEME_ID).value)
                }?.value
                val selectedId = selectedOption?.id?.value?.let { String(it) }
                currentTheme = LavaTheme.entries.find { it.id == selectedId } ?: LavaTheme.MAGMA
                updatePaints()
            }
        }
    }

    private fun updatePaints() {
        blobPaints.clear()
        for (color in currentTheme.bubbleColors) {
            if (!blobPaints.containsKey(color)) {
                val transparentColor = color and 0x00FFFFFF
                blobPaints[color] = Paint().apply {
                    isAntiAlias = true
                    shader = RadialGradient(
                        0f, 0f, 100f,
                        color, transparentColor,
                        Shader.TileMode.CLAMP
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]

            // Calcular sacudida (agitar para dividir)
            if (lastAccelX != 0f || lastAccelY != 0f) {
                val dx = ax - lastAccelX
                val dy = ay - lastAccelY
                val dz = az - lastAccelZ
                val shake = sqrt(dx * dx + dy * dy + dz * dz)
                
                // Si la sacudida es notable, aumentamos la agitación
                if (shake > 3f) {
                    agitation = (agitation + shake * 0.1f).coerceAtMost(3f)
                }
            }

            lastAccelX = ax
            lastAccelY = ay
            lastAccelZ = az

            // Atracción leve del giroscopio (aumentada de 0.004f a 0.006f)
            // Esto permite que el movimiento de la muñeca se note un poquito más, pero sin jalar las gotas por completo.
            tiltX = -ax * 0.006f
            tiltY = ay * 0.006f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun initBlobs(width: Float, height: Float) {
        blobs.clear()
        val centerX = width / 2f
        val centerY = height / 2f

        // Aumentado a 9 GOTAS GRANDES
        for (i in 0 until 9) {
            blobs.add(
                LavaBlob(
                    x = centerX + (Random.nextFloat() - 0.5f) * width * 0.5f,
                    y = centerY + (Random.nextFloat() - 0.5f) * height * 0.5f,
                    radius = Random.nextFloat() * 25f + 45f, 
                    colorIndex = i % 4, // Ahora iteramos sobre los 4 colores disponibles en el tema
                    buoyancy = if (Random.nextBoolean()) 1f else -1f,
                    targetBuoyancy = if (Random.nextBoolean()) 1f else -1f
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
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        if (!isInitialized && width > 0 && height > 0) {
            initBlobs(width, height)
            updatePaints()
        }

        val isAmbient = renderParameters.drawMode == DrawMode.AMBIENT

        if (isAmbient) {
            canvas.drawColor(Color.BLACK)
            timePaint.color = Color.DKGRAY
            timePaint.clearShadowLayer()
            val timeString = zonedDateTime.format(timeFormatter)
            val textY = centerY - ((timePaint.descent() + timePaint.ascent()) / 2)
            canvas.drawText(timeString, centerX, textY, timePaint)
        } else {
            canvas.drawColor(currentTheme.bgColor)
            val screenRadius = width / 2f

            agitation *= 0.95f

            // 1. Motor Físico (Desplazamiento Vertical Continuo)
            for (blob in blobs) {
                if (blob.y > centerY + screenRadius * 0.35f) {
                    blob.targetBuoyancy = -1.5f 
                } else if (blob.y < centerY - screenRadius * 0.35f) {
                    blob.targetBuoyancy = 1.5f  
                }

                // Cambio de flotabilidad un poco más rápido (0.012f)
                blob.buoyancy += (blob.targetBuoyancy - blob.buoyancy) * 0.012f

                // Flotabilidad natural, fuerza direccional del giroscopio
                // Velocidad vertical aumentada (0.065f) para que se muevan más
                blob.vy += blob.buoyancy * 0.065f
                blob.vx += tiltX
                blob.vy += tiltY

                // Si se detecta agitación fuerte, las gotas rebotan aleatoriamente un poquito para separarse
                if (agitation > 0.1f) {
                    blob.vx += (Random.nextFloat() - 0.5f) * agitation * 0.3f
                    blob.vy += (Random.nextFloat() - 0.5f) * agitation * 0.3f
                }

                // Desplazamiento horizontal aleatorio aumentado (0.06f) para provocar más choques
                blob.vx += (Random.nextFloat() - 0.5f) * 0.06f

                blob.vx *= 0.90f
                blob.vy *= 0.90f

                blob.x += blob.vx
                blob.y += blob.vy

                val dx = blob.x - centerX
                val dy = blob.y - centerY
                val distToCenter = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val maxDist = screenRadius - blob.radius * 0.5f

                if (distToCenter > maxDist) {
                    val angle = atan2(dy.toDouble(), dx.toDouble())
                    val push = (distToCenter - maxDist) * 0.03f
                    blob.vx -= cos(angle).toFloat() * push
                    blob.vy -= sin(angle).toFloat() * push
                }
            }

            // 2. Tensión Superficial, Fusión y Repulsión
            for (i in 0 until blobs.size) {
                for (j in i + 1 until blobs.size) {
                    val b1 = blobs[i]
                    val b2 = blobs[j]
                    val dx = b2.x - b1.x
                    val dy = b2.y - b1.y
                    val dist = sqrt(dx * dx + dy * dy)
                    
                    val sameColor = b1.colorIndex % currentTheme.bubbleColors.size == b2.colorIndex % currentTheme.bubbleColors.size
                    
                    if (sameColor) {
                        // Tensión superficial para gotas del mismo color (Se atraen para formar masas grandes)
                        val mergeDist = (b1.radius + b2.radius) * 1.3f
                        val repelDist = (b1.radius + b2.radius) * 0.45f + (agitation * 15f)
                        
                        if (dist < mergeDist && dist > repelDist) {
                            // Atracción (se quieren juntar). Si se agita la muñeca, se rompe esta fuerza
                            if (agitation < 0.2f) {
                                val pull = (mergeDist - dist) * 0.0012f
                                val nx = dx / dist
                                val ny = dy / dist
                                b1.vx += nx * pull
                                b1.vy += ny * pull
                                b2.vx -= nx * pull
                                b2.vy -= ny * pull
                            }
                        } else if (dist <= repelDist && dist > 0f) {
                            // Repulsión en el núcleo (para que no colapsen en un solo punto, sino que formen un cacahuate u óvalo)
                            val overlap = repelDist - dist
                            val extraPush = if (agitation > 0.3f) agitation * 0.03f else 0f
                            val push = (overlap * 0.005f) + extraPush
                            
                            val nx = dx / dist
                            val ny = dy / dist
                            b1.vx -= nx * push
                            b1.vy -= ny * push
                            b2.vx += nx * push
                            b2.vy += ny * push
                        }
                    } else {
                        // Distinto color: Se repelen como agua y aceite
                        val repelDist = (b1.radius + b2.radius) * 0.9f + (agitation * 15f)
                        if (dist < repelDist && dist > 0f) {
                            val overlap = repelDist - dist
                            val extraPush = if (agitation > 0.3f) agitation * 0.02f else 0f
                            val push = (overlap * 0.006f) + extraPush
                            
                            val nx = dx / dist
                            val ny = dy / dist
                            b1.vx -= nx * push
                            b1.vy -= ny * push
                            b2.vx += nx * push
                            b2.vy += ny * push
                        }
                    }
                }
            }

            // 3. Renderizado Gooey
            val colors = currentTheme.bubbleColors.distinct()
            
            for (color in colors) {
                canvas.saveLayer(null, gooeyPaint)
                val paint = blobPaints[color] ?: continue
                
                for (blob in blobs) {
                    if (currentTheme.bubbleColors[blob.colorIndex % currentTheme.bubbleColors.size] != color) continue

                    // Con m=80 y s=-48, el límite de opacidad (60%) cae exactamente en la distancia 40.
                    val scale = blob.radius / 40f

                    canvas.save()
                    canvas.translate(blob.x, blob.y)
                    canvas.scale(scale, scale)
                    canvas.drawCircle(0f, 0f, 100f, paint)
                    canvas.restore()
                }
                
                canvas.restore()
            }

            // 4. Dibujar la hora
            timePaint.color = Color.WHITE
            timePaint.setShadowLayer(8f, 0f, 0f, Color.argb(180, 0, 0, 0))
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
