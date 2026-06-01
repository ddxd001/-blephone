package com.blephone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

private const val TARGET_DEVICE_NAME = "DDXD_BLUE"
private val PRESSURE_SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
private val PRESSURE_NOTIFY_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private val FRAME_HEADER = intArrayOf(0xAA, 0x55, 0x10)
private const val FRAME_LENGTH = 20
private const val SENSOR_COUNT = 16

object BleRuntimePermissions {
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasRequiredPermissions(context: Context): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}

data class PressureFrame(
    val sensors: List<Int>,
    val raw: ByteArray
)

data class BleScanDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isTarget: Boolean
)

class PressureFrameBuffer {
    private val buffer = mutableListOf<Int>()

    fun feed(chunk: ByteArray): List<PressureFrame> {
        chunk.forEach { buffer += it.toInt() and 0xFF }

        val frames = mutableListOf<PressureFrame>()
        while (true) {
            val start = findHeader()
            if (start < 0) {
                if (buffer.size > 2) {
                    val tail = buffer.takeLast(2)
                    buffer.clear()
                    buffer.addAll(tail)
                }
                break
            }

            if (start > 0) {
                repeat(start) { buffer.removeAt(0) }
            }
            if (buffer.size < FRAME_LENGTH) break

            val candidate = buffer.take(FRAME_LENGTH)
            repeat(FRAME_LENGTH) { buffer.removeAt(0) }
            parseFrame(candidate)?.let(frames::add)
        }

        return frames
    }

    private fun findHeader(): Int {
        for (i in 0..buffer.size - FRAME_HEADER.size) {
            if (
                buffer[i] == FRAME_HEADER[0] &&
                buffer[i + 1] == FRAME_HEADER[1] &&
                buffer[i + 2] == FRAME_HEADER[2]
            ) {
                return i
            }
        }
        return -1
    }

    private fun parseFrame(bytes: List<Int>): PressureFrame? {
        if (bytes.size != FRAME_LENGTH) return null
        if (bytes[0] != FRAME_HEADER[0] || bytes[1] != FRAME_HEADER[1] || bytes[2] != FRAME_HEADER[2]) {
            return null
        }

        val expected = bytes.take(FRAME_LENGTH - 1).sum() and 0xFF
        if (bytes.last() != expected) return null

        return PressureFrame(
            sensors = bytes.subList(3, 3 + SENSOR_COUNT),
            raw = ByteArray(FRAME_LENGTH) { index -> bytes[index].toByte() }
        )
    }
}

class PressureBleClient(
    private val context: Context,
    private val onState: (ConnectionState) -> Unit,
    private val onFrame: (List<Int>) -> Unit,
    private val onDevice: (BleScanDevice) -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameBuffer = PressureFrameBuffer()
    private val scanDevices = mutableMapOf<String, BluetoothDevice>()
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    private val bluetoothAdapter by lazy {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName
            val name = deviceName ?: "未知设备"
            val isTarget = deviceName?.contains(TARGET_DEVICE_NAME, ignoreCase = true) == true
            scanDevices[result.device.address] = result.device
            postDevice(
                BleScanDevice(
                    name = name,
                    address = result.device.address,
                    rssi = result.rssi,
                    isTarget = isTarget
                )
            )

            if (isTarget) {
                stopScan()
                connectDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            postMessage("扫描失败: $errorCode")
            postState(ConnectionState.DISCONNECTED)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                postMessage("已连接，发现服务中")
                postState(ConnectionState.CONNECTING)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                postMessage(if (status == BluetoothGatt.GATT_SUCCESS) "蓝牙已断开" else "蓝牙断开: $status")
                closeGatt()
                postState(ConnectionState.DISCONNECTED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postMessage("发现服务失败: $status")
                disconnect()
                return
            }

            val characteristic = gatt
                .getService(PRESSURE_SERVICE_UUID)
                ?.getCharacteristic(PRESSURE_NOTIFY_UUID)

            if (characteristic == null) {
                postMessage("未找到 FFF0/FFF1 特征")
                disconnect()
                return
            }

            enableNotify(gatt, characteristic)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleNotify(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CLIENT_CONFIG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    postMessage("已订阅 FFF1 Notify")
                    postState(ConnectionState.CONNECTED)
                } else {
                    postMessage("订阅 Notify 失败: $status")
                    disconnect()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        if (!BleRuntimePermissions.hasRequiredPermissions(context)) {
            postMessage("缺少蓝牙权限")
            postState(ConnectionState.DISCONNECTED)
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            postMessage("蓝牙未开启")
            postState(ConnectionState.DISCONNECTED)
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            postMessage("BLE 扫描器不可用")
            postState(ConnectionState.DISCONNECTED)
            return
        }

        disconnect()
        scanDevices.clear()
        scanning = true
        postMessage("扫描 $TARGET_DEVICE_NAME")
        postState(ConnectionState.SCANNING)
        scanner.startScan(scanCallback)
        mainHandler.postDelayed({
            if (scanning) {
                stopScan()
                postMessage("未找到 $TARGET_DEVICE_NAME")
                postState(ConnectionState.DISCONNECTED)
            }
        }, 10_000L)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        closeGatt()
    }

    fun connectTo(address: String) {
        val device = scanDevices[address]
        if (device == null) {
            postMessage("设备已不在扫描缓存: $address")
            return
        }
        stopScan()
        connectDevice(device)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        postMessage("连接 ${device.name ?: device.address}")
        postState(ConnectionState.CONNECTING)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val enabled = gatt.setCharacteristicNotification(characteristic, true)
        if (!enabled) {
            postMessage("开启本地 Notify 失败")
            disconnect()
            return
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor == null) {
            postMessage("FFF1 无 CCCD，已开启本地 Notify")
            postState(ConnectionState.CONNECTED)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.close()
        gatt = null
    }

    private fun handleNotify(value: ByteArray) {
        frameBuffer.feed(value).forEach { frame ->
            mainHandler.post { onFrame(frame.sensors) }
        }
    }

    private fun postState(state: ConnectionState) {
        mainHandler.post { onState(state) }
    }

    private fun postMessage(message: String) {
        mainHandler.post { onMessage(message) }
    }

    private fun postDevice(device: BleScanDevice) {
        mainHandler.post { onDevice(device) }
    }
}
