package com.liangyuchen.timetable.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.liangyuchen.timetable.data.NearbyPayload
import java.util.UUID

/**
 * 接收端：扫描附近正在广播课表的设备，连上去把数据读完。
 *
 * 读取是「按偏移量分段读」：先读 META 拿到总长度和 CRC，
 * 再反复读 DATA（每次 480 字节），拼起来校验 CRC 后交给上层。
 */
class NearbyReceiver(private val context: Context) {

    interface Listener {
        fun onStatus(text: String)
        fun onFound(device: DeviceItem)
        fun onPayload(payload: NearbyPayload)
    }

    data class DeviceItem(val name: String, val address: String)

    private val main = Handler(Looper.getMainLooper())
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var listener: Listener? = null
    private var buffer = ByteArray(0)
    private var totalSize = 0
    private var expectedCrc = 0L

    @SuppressLint("MissingPermission")
    fun scan(listener: Listener) {
        this.listener = listener
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: run {
            listener.onStatus("这台设备没有蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            listener.onStatus("请先打开蓝牙")
            return
        }
        scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val uuid = java.util.UUID.fromString(NearbyPayload.SERVICE_UUID)
        val filter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(uuid))
            .build()
        scanner?.startScan(
            listOf(filter),
            settings,
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        result.scanRecord?.deviceName ?: device.name ?: "附近的人"
                    } else {
                        device.name ?: "附近的人"
                    }
                    listener.onFound(DeviceItem(name, device.address))
                }

                override fun onScanFailed(errorCode: Int) {
                    listener.onStatus("扫描失败（错误码 $errorCode）")
                }
            },
        )
        listener.onStatus("正在找附近的同学…（让对方点「发送」）")
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stopScan()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter?.getRemoteDevice(address) ?: return
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        listener?.onStatus("正在连接…")
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    main.post { listener?.onStatus("已连接，读取课表…") }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    if (buffer.isEmpty()) {
                        main.post { listener?.onStatus("连接断开了，重试一下") }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service: BluetoothGattService = g.getService(
                UUID.fromString(NearbyPayload.SERVICE_UUID),
            ) ?: run {
                main.post { listener?.onStatus("这台设备没有课表服务") }
                return
            }
            val meta = service.getCharacteristic(UUID.fromString(NearbyPayload.META_UUID))
                ?: return
            readCharacteristic(g, meta)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { listener?.onStatus("读取失败（$status）") }
                return
            }
            when (characteristic.uuid) {
                UUID.fromString(NearbyPayload.META_UUID) -> {
                    val meta = NearbyPayload.readMeta(value) ?: run {
                        main.post { listener?.onStatus("数据头不对劲") }
                        return
                    }
                    totalSize = meta.first
                    expectedCrc = meta.second
                    buffer = ByteArray(0)
                    val dataChar = g.getService(
                        UUID.fromString(NearbyPayload.SERVICE_UUID),
                    )?.getCharacteristic(UUID.fromString(NearbyPayload.DATA_UUID)) ?: return
                    readCharacteristic(g, dataChar)
                }
                UUID.fromString(NearbyPayload.DATA_UUID) -> {
                    buffer += value
                    if (buffer.size >= totalSize) {
                        finish(g)
                    } else {
                        readCharacteristic(g, characteristic)
                    }
                }
            }
        }

        @Deprecated("兼容旧系统")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                onCharacteristicRead(g, characteristic, value, status)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristic(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.readCharacteristic(characteristic)
        } else {
            @Suppress("DEPRECATION")
            g.readCharacteristic(characteristic)
        }
    }

    private fun finish(g: BluetoothGatt) {
        val bytes = buffer
        val crcOk = NearbyPayload.crcOf(bytes) == expectedCrc
        val payload = if (crcOk) NearbyPayload.decode(bytes) else null
        runCatching { g.disconnect(); g.close() }
        gatt = null
        main.post {
            if (payload == null) {
                listener?.onStatus("数据不完整，让对方重新发送")
            } else {
                listener?.onPayload(payload)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { scanner?.stopScan(null as ScanCallback?) }
        scanner = null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        listener = null
    }
}
