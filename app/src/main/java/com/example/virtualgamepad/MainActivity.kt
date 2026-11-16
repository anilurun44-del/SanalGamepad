package com.example.virtualgamepad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var hidHelper: GamepadHidHelper
    private var statusText = "Izinler bekleniyor..."

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            initGamepad()
        } else {
            Toast.makeText(this, "Bluetooth izinleri gerekli!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hidHelper = GamepadHidHelper(this) { status ->
            runOnUiThread {
                statusText = status
            }
        }

        setContentView(GamepadCanvasView(this))
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            initGamepad()
        }
    }

    @SuppressLint("MissingPermission")
    private fun initGamepad() {
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivity(discoverableIntent)
        hidHelper.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        hidHelper.stop()
    }

    inner class GamepadCanvasView(context: Context) : View(context) {
        private val paintBg = Paint().apply { color = Color.parseColor("#121212") }
        private val paintBase = Paint().apply { color = Color.parseColor("#2C2C2C"); style = Paint.Style.FILL; isAntiAlias = true }
        private val paintStick = Paint().apply { color = Color.parseColor("#00E5FF"); style = Paint.Style.FILL; isAntiAlias = true }
        private val paintBtn = Paint().apply { color = Color.parseColor("#37474F"); style = Paint.Style.FILL; isAntiAlias = true }
        private val paintBtnPressed = Paint().apply { color = Color.parseColor("#FF5252"); style = Paint.Style.FILL; isAntiAlias = true }
        private val paintText = Paint().apply { color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER; isAntiAlias = true }

        private var stickCenterX = 0f
        private var stickCenterY = 0f
        private var stickRadius = 220f
        private var knobX = 0f
        private var knobY = 0f
        private var stickPointerId = -1

        private data class GameButton(val label: String, val bit: Int, var cx: Float = 0f, var cy: Float = 0f, var pressed: Boolean = false)
        private val buttons = listOf(
            GameButton("A", 1),
            GameButton("B", 2),
            GameButton("X", 4),
            GameButton("Y", 8)
        )
        private val btnRadius = 95f
        private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            stickCenterX = w * 0.22f
            stickCenterY = h * 0.58f
            knobX = stickCenterX
            knobY = stickCenterY

            val rightCenterX = w * 0.78f
            val rightCenterY = h * 0.58f
            val offset = 165f

            buttons[0].apply { cx = rightCenterX; cy = rightCenterY + offset }
            buttons[1].apply { cx = rightCenterX + offset; cy = rightCenterY }
            buttons[2].apply { cx = rightCenterX - offset; cy = rightCenterY }
            buttons[3].apply { cx = rightCenterX; cy = rightCenterY - offset }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

            paintText.textSize = 38f
            canvas.drawText(statusText, width / 2f, 70f, paintText)

            canvas.drawCircle(stickCenterX, stickCenterY, stickRadius, paintBase)
            canvas.drawCircle(knobX, knobY, 85f, paintStick)

            paintText.textSize = 55f
            for (btn in buttons) {
                canvas.drawCircle(btn.cx, btn.cy, btnRadius, if (btn.pressed) paintBtnPressed else paintBtn)
                canvas.drawText(btn.label, btn.cx, btn.cy + 20f, paintText)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val action = event.actionMasked
            val index = event.actionIndex
            val pointerId = event.getPointerId(index)

            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (x < width / 2f && stickPointerId == -1) {
                        stickPointerId = pointerId
                        updateStick(x, y)
                    } else {
                        checkButtons(event, triggerVibrate = true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        if (event.getPointerId(i) == stickPointerId) {
                            updateStick(event.getX(i), event.getY(i))
                        }
                    }
                    checkButtons(event, triggerVibrate = false)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pointerId == stickPointerId) {
                        stickPointerId = -1
                        knobX = stickCenterX
                        knobY = stickCenterY
                    }
                    updateButtonsOnRelease(event, pointerId)
                }
            }

            sendCurrentInput()
            invalidate()
            return true
        }

        private fun updateStick(touchX: Float, touchY: Float) {
            val dx = touchX - stickCenterX
            val dy = touchY - stickCenterY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= stickRadius) {
                knobX = touchX
                knobY = touchY
            } else {
                val angle = atan2(dy, dx)
                knobX = stickCenterX + cos(angle) * stickRadius
                knobY = stickCenterY + sin(angle) * stickRadius
            }
        }

        private fun checkButtons(event: MotionEvent, triggerVibrate: Boolean) {
            for (btn in buttons) {
                var isDown = false
                for (i in 0 until event.pointerCount) {
                    val dx = event.getX(i) - btn.cx
                    val dy = event.getY(i) - btn.cy
                    if (sqrt(dx * dx + dy * dy) <= btnRadius * 1.2f) {
                        isDown = true
                        break
                    }
                }
                if (isDown && !btn.pressed && triggerVibrate) {
                    vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                btn.pressed = isDown
            }
        }

        private fun updateButtonsOnRelease(event: MotionEvent, liftedPointerId: Int) {
            for (btn in buttons) {
                var stillDown = false
                for (i in 0 until event.pointerCount) {
                    if (event.getPointerId(i) == liftedPointerId) continue
                    val dx = event.getX(i) - btn.cx
                    val dy = event.getY(i) - btn.cy
                    if (sqrt(dx * dx + dy * dy) <= btnRadius * 1.2f) {
                        stillDown = true
                        break
                    }
                }
                btn.pressed = stillDown
            }
        }

        private fun sendCurrentInput() {
            var bitmask = 0
            for (btn in buttons) {
                if (btn.pressed) bitmask = bitmask or btn.bit
            }
            val normX = (((knobX - stickCenterX) / stickRadius) * 127).toInt().coerceIn(-127, 127).toByte()
            val normY = (((knobY - stickCenterY) / stickRadius) * 127).toInt().coerceIn(-127, 127).toByte()
            hidHelper.sendGamepadState(bitmask, normX, normY)
        }
    }
}
