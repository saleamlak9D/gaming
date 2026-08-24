package com.example

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

const val COLS = 20
const val ROWS = 20
const val FPS = 9L

fun playRetroSound(freq: Double, durationMs: Int, waveType: Int = 0) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val sampleRate = 44100
            val numSamples = (durationMs * sampleRate) / 1000
            val generatedSnd = ByteArray(2 * numSamples)
            
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val sample = when (waveType) {
                    0 -> sin(2.0 * Math.PI * freq * t) // Sine
                    1 -> if (sin(2.0 * Math.PI * freq * t) > 0) 0.5 else -0.5 // Square
                    else -> Random.nextDouble(-0.5, 0.5) // Noise
                }
                val env = if (i < 200) i / 200.0 else if (i > numSamples - 200) (numSamples - i) / 200.0 else 1.0
                val pcmValue = (sample * env * 32767).toInt()
                
                generatedSnd[2 * i] = (pcmValue and 0x00FF).toByte()
                generatedSnd[2 * i + 1] = ((pcmValue and 0xFF00) ushr 8).toByte()
            }
            
            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                generatedSnd.size,
                AudioTrack.MODE_STATIC
            )
            audioTrack.write(generatedSnd, 0, generatedSnd.size)
            audioTrack.play()
            Thread.sleep(durationMs.toLong() + 50)
            audioTrack.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

data class Point(val x: Int, val y: Int)
data class Particle(val x: Float, val y: Float, val vx: Float, val vy: Float, val life: Float)

enum class Phase { IDLE, RUNNING, DEAD, PAUSED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SnakeGame()
        }
    }
}

@Composable
fun SnakeGame() {
    var snake by remember { mutableStateOf(listOf(Point(10, 10), Point(9, 10), Point(8, 10))) }
    var dir by remember { mutableStateOf(Point(1, 0)) }
    var nextDir by remember { mutableStateOf(Point(1, 0)) }
    var food by remember { mutableStateOf(Point(0, 0)) }
    var score by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("SnakePrefs", Context.MODE_PRIVATE) }
    var best by remember { mutableIntStateOf(sharedPrefs.getInt("BEST_SCORE", 0)) }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var particles by remember { mutableStateOf(emptyList<Particle>()) }

    val level = score / 5
    val currentFps = 9L + minOf(level, 10).toLong()

    val targetTopColor = when(minOf(level, 5)) {
        0 -> Color(0xFF18181B) // Zinc 900
        1 -> Color(0xFF1C1917) // Stone 900
        2 -> Color(0xFF171717) // Neutral 900
        3 -> Color(0xFF111827) // Gray 900
        4 -> Color(0xFF1F2937) // Gray 800
        else -> Color(0xFF27272A) // Zinc 800
    }
    val targetBottomColor = when(minOf(level, 5)) {
        0 -> Color(0xFF09090B) // Zinc 950
        1 -> Color(0xFF0C0A09) // Stone 950
        2 -> Color(0xFF0A0A0A) // Neutral 950
        3 -> Color(0xFF030712) // Gray 950
        4 -> Color(0xFF111827) // Gray 900
        else -> Color(0xFF18181B) // Zinc 900
    }

    val animatedTop by animateColorAsState(targetTopColor, tween(1000))
    val animatedBottom by animateColorAsState(targetBottomColor, tween(1000))
    val bgBrush = Brush.verticalGradient(listOf(animatedTop, animatedBottom))

    val grid = Color(0x1AFFFFFF)
    val snakeHead = Color(0xFF00FFCC)
    val snakeBody = Color(0xFF00B299)
    val foodColor = Color(0xFFFF3366)
    val textCol = Color(0xFFFFFFFF)
    val dimText = Color(0xFFA1A1AA)
    val overlay = Color(0xE609090B)
    val borderColor = Color(0x1AFFFFFF)

    fun placeFood() {
        var newFood: Point
        do {
            newFood = Point((0 until COLS).random(), (0 until ROWS).random())
        } while (snake.any { it.x == newFood.x && it.y == newFood.y })
        food = newFood
    }

    fun init() {
        snake = listOf(Point(10, 10), Point(9, 10), Point(8, 10))
        dir = Point(1, 0)
        nextDir = Point(1, 0)
        score = 0
        particles = emptyList()
        placeFood()
    }

    LaunchedEffect(Unit) {
        init()
        while (true) {
            withFrameNanos {
                if (particles.isNotEmpty()) {
                    particles = particles.mapNotNull { p ->
                        val newLife = p.life - 0.03f
                        if (newLife > 0) p.copy(x = p.x + p.vx, y = p.y + p.vy, life = newLife) else null
                    }
                }
            }
        }
    }

    LaunchedEffect(phase) {
        if (phase == Phase.RUNNING) {
            while (true) {
                val loopFps = 9L + minOf(score / 5, 10).toLong()
                delay(1000L / loopFps)
                
                dir = nextDir
                val head = Point(
                    x = (snake[0].x + dir.x + COLS) % COLS,
                    y = (snake[0].y + dir.y + ROWS) % ROWS
                )
                
                if (snake.any { it.x == head.x && it.y == head.y }) {
                    playRetroSound(150.0, 300, 1) // Thud
                    phase = Phase.DEAD
                    if (score > best) {
                        best = score
                        sharedPrefs.edit().putInt("BEST_SCORE", best).apply()
                    }
                    continue 
                }
                
                val newSnake = snake.toMutableList()
                newSnake.add(0, head)
                
                if (head.x == food.x && head.y == food.y) {
                    score++
                    
                    if (score % 5 == 0) {
                        playRetroSound(880.0, 150, 0) // Level up (higher pitch sine)
                    } else {
                        playRetroSound(600.0, 100, 1) // Eat (square beep)
                    }
                    
                    val newParticles = List(15) {
                        val angle = Random.nextFloat() * 2 * Math.PI.toFloat()
                        val speed = Random.nextFloat() * 0.4f + 0.1f
                        Particle(
                            x = food.x.toFloat(),
                            y = food.y.toFloat(),
                            vx = cos(angle) * speed,
                            vy = sin(angle) * speed,
                            life = 1f
                        )
                    }
                    particles = particles + newParticles
                    
                    placeFood()
                } else {
                    newSnake.removeLast()
                }
                snake = newSnake
            }
        }
    }
    
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        when (phase) {
                            Phase.IDLE, Phase.DEAD -> {
                                if (phase == Phase.DEAD) init()
                                phase = Phase.RUNNING
                            }
                            Phase.RUNNING -> phase = Phase.PAUSED
                            Phase.PAUSED -> phase = Phase.RUNNING
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        if (phase != Phase.RUNNING) {
                            if (phase == Phase.DEAD) init()
                            phase = Phase.RUNNING
                        }
                        dragOffset = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                        if (dragOffset.getDistance() > 30f) {
                            val dx = dragOffset.x
                            val dy = dragOffset.y
                            if (abs(dx) > abs(dy)) {
                                if (dx > 0 && dir.x == 0) nextDir = Point(1, 0)
                                if (dx < 0 && dir.x == 0) nextDir = Point(-1, 0)
                            } else {
                                if (dy > 0 && dir.y == 0) nextDir = Point(0, 1)
                                if (dy < 0 && dir.y == 0) nextDir = Point(0, -1)
                            }
                            dragOffset = Offset.Zero
                        }
                    }
                )
            }
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CURRENT SCORE",
                    color = Color(0xFF94A3B8), // slate-400
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.em,
                    fontFamily = FontFamily.SansSerif
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$score",
                        color = Color(0xFF34D399), // emerald-400
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "pts",
                        color = Color(0xFF64748B), // slate-500
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "ALL-TIME BEST",
                    color = Color(0xFF94A3B8), // slate-400
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.em,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "$best",
                    color = Color(0xFFE2E8F0), // slate-200
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
        
        // Main Board Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(grid)
                    .border(1.dp, borderColor, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cell = size.width / COLS
                    
                    // Radial dot background
                    for (x in 0..COLS) {
                        for (y in 0..ROWS) {
                            drawCircle(
                                color = Color(0x08FFFFFF),
                                radius = 2f,
                                center = Offset(x * cell, y * cell)
                            )
                        }
                    }
                    
                    // Food Glow
                    drawCircle(
                        color = foodColor.copy(alpha = 0.3f),
                        radius = cell / 2f + 4f,
                        center = Offset(food.x * cell + cell / 2f, food.y * cell + cell / 2f)
                    )
                    // Food
                    drawCircle(
                        color = foodColor,
                        radius = cell / 2f - 2f,
                        center = Offset(food.x * cell + cell / 2f, food.y * cell + cell / 2f)
                    )
                    
                    // Particles
                    particles.forEach { p ->
                        drawCircle(
                            color = foodColor.copy(alpha = p.life),
                            radius = (cell / 3f) * p.life,
                            center = Offset(p.x * cell + cell / 2f, p.y * cell + cell / 2f)
                        )
                    }
                    
                    // Snake
                    val pad = 1f
                    snake.forEachIndexed { i, s ->
                        drawRoundRect(
                            color = if (i == 0) snakeHead else snakeBody,
                            topLeft = Offset(s.x * cell + pad, s.y * cell + pad),
                            size = Size(cell - pad * 2, cell - pad * 2),
                            cornerRadius = CornerRadius(4f, 4f),
                            alpha = if (i == 0) 1f else maxOf(0.4f, 1f - (i * 0.05f))
                        )
                    }
                }
                
                if (phase != Phase.RUNNING) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(overlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (phase == Phase.IDLE) {
                                Text(
                                    text = "🐍",
                                    fontSize = 72.sp,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                            }
                            val titleText = when (phase) {
                                Phase.IDLE -> "SNAKE"
                                Phase.DEAD -> "GAME OVER"
                                Phase.PAUSED -> "PAUSED"
                                else -> ""
                            }
                            Text(
                                text = titleText,
                                color = textCol,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val subText = when (phase) {
                                Phase.IDLE -> "swipe to start"
                                Phase.DEAD -> "score $score   tap or swipe to play again"
                                Phase.PAUSED -> "tap or swipe to resume"
                                else -> ""
                            }
                            Text(
                                text = subText,
                                color = dimText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (phase == Phase.IDLE) {
                                Spacer(modifier = Modifier.height(32.dp))
                                val infiniteTransition = rememberInfiniteTransition(label = "rainbow_anim")
                                val animatedOffset by infiniteTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 500f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(2000, easing = LinearEasing)
                                    ),
                                    label = "rainbow_offset"
                                )
                                val rainbowBrush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF00FFCC), // Cyan
                                        Color(0xFF3B82F6), // Blue
                                        Color(0xFF8B5CF6), // Purple
                                        Color(0xFFFF3366), // Pink
                                        Color(0xFFF59E0B), // Yellow
                                        Color(0xFF10B981), // Green
                                        Color(0xFF00FFCC)  // Cyan (loop)
                                    ),
                                    start = Offset(animatedOffset, 0f),
                                    end = Offset(animatedOffset + 500f, 500f),
                                    tileMode = TileMode.Repeated
                                )
                                Text(
                                    text = "created by saleamlak.dege",
                                    style = TextStyle(
                                        brush = rainbowBrush,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.SansSerif,
                                        letterSpacing = 0.05.em
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Footer (Phase/Speed info)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x0DFFFFFF))
                    .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 Row(verticalAlignment = Alignment.CenterVertically) {
                     Box(
                         modifier = Modifier
                             .size(40.dp)
                             .clip(CircleShape)
                             .background(Color(0x1A10B981)),
                         contentAlignment = Alignment.Center
                     ) {
                         Text("◈", color = Color(0xFF34D399), fontSize = 16.sp)
                     }
                     Spacer(modifier = Modifier.width(12.dp))
                     Column {
                         Text(
                             text = "Level ${level + 1} - Phase: ${phase.name.lowercase().replaceFirstChar { it.uppercase() }}",
                             color = Color(0xFFCBD5E1),
                             fontSize = 12.sp,
                             fontWeight = FontWeight.Bold,
                             fontFamily = FontFamily.SansSerif
                         )
                         Text(
                             text = "${currentFps} FPS • Speed: ${if (level == 0) "Normal" else "Fast"}",
                             color = Color(0xFF64748B),
                             fontSize = 10.sp,
                             fontFamily = FontFamily.SansSerif
                         )
                     }
                 }
            }
        }
        
        Text(
            text = "SWIPE OR TAP TO NAVIGATE",
            modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp, top = 8.dp),
            textAlign = TextAlign.Center,
            color = Color(0xFF475569),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.em,
            fontFamily = FontFamily.SansSerif
        )
    }
}
