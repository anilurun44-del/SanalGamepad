package com.example.virtualgamepad

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class GamepadHidHelper(private val context: Context, private val onStatusChanged: (String) -> Unit) {

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val hidDescriptor = byteArrayOf(
        0x05, 0x01,
        0x09, 0x05,
        0xA1.toByte(), 0x01,
        0x85.toByte(), 0x01,
        
        0x05, 0x09,
        0x19, 0x01,
        0x29, 0x10,
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95.toByte(), 0x10,
        0x81.toByte(), 0x02,

        0x05, 0x01,
        0x09, 0x30,
        0x09, 0x31,
        0x15, 0x81.toByte(),
        0x25, 0x7F,
        0x75, 0x08,
        0x95.toByte(), 0x02,
        0x81.toByte(), 0x02,

        0xC0.toByte()
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                onStatusChanged("Gamepad Hazir! Tabletten Bluetooth ile baglanin.")
            } else {
                onStatusChanged("HID Kaydi Basarisiz.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    onStatusChanged("Baglandi: ${device?.name ?: "Tablet"}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    hostDevice = null
                    onStatusChanged("Baglanti Kesildi. Bekleniyor...")
                }
            }
        }
    }

    fun start() {
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = proxy as BluetoothHidDevice
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "Redmi Sanal Gamepad",
                        "Android HID Controller",
                        "Custom",
                        BluetoothHidDevice.SUBCLASS1_GAMEPAD,
                        hidDescriptor
                    )
                    bluetoothHidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                bluetoothHidDevice = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun sendGamepadState(buttonsBitmask: Int, leftX: Byte, leftY: Byte) {
        val device = hostDevice ?: return
        val report = byteArrayOf(
            (buttonsBitmask and 0xFF).toByte(),
            ((buttonsBitmask shr 8) and 0xFF).toByte(),
            leftX,
            leftY
        )
        bluetoothHidDevice?.sendReport(device, 1, report)
    }

    fun stop() {
        bluetoothHidDevice?.unregisterApp()
    }
}
