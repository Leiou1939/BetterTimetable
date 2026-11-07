package com.liangyuchen.timetable.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.liangyuchen.timetable.data.NearbyPayload
import java.util.UUID

/**
 * 发送端：把自己变成一台「广播课表」的 BLE 外设。
 *
 * 流程：广播服务 UUID → 对方连上来 → 先读 META（长度 + CRC）→
 * 再按偏移量反复读 DATA，直到读满。
 */
class NearbySender(private val context: Context) {

    interface Listener {
        fun onStatus(text: String)
        fun onServed()
    }

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var payload: ByteArray = ByteArray(0)
    private var meta: ByteArray = ByteArray(8)

    @SuppressLint("MissingPermission")
    fun start(content: NearbyPayload, listener: Listener) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: run {
            listener.onStatus("这台设备没有蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            listener.onStatus("请先打开蓝牙")
            return
        }

        payload = content.encode()
        meta = NearbyPayload.meta(payload)

        val svcUuid = UUID.fromString(NearbyPayload.SERVICE_UUID)
        val metaUuid = UUID.fromString(NearbyPayload.META_UUID)
        val dataUuid = UUID.fromString(NearbyPayload.DATA_UUID)

        val metaChar = BluetoothGattCharacteristic(
            metaUuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val dataChar = BluetoothGattCharacteristic(
            dataUuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(svcUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(metaChar)
        service.addCharacteristic(dataChar)

        server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    listener.onStatus("${device.name ?: "对方"} 已连接，正在传课表…")
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val source = when (characteristic.uuid) {
                    metaUuid -> meta
                    dataUuid -> payload
                    else -> null
                }
                if (source == null) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }
                if (offset >= source.size) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                    return
                }
                val end = minOf(offset + NearbyPayload.CHUNK, source.size)
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    source.copyOfRange(offset, end),
                )
                if (characteristic.uuid == dataUuid && end >= source.size) {
                    listener.onServed()
                }
            }
        })?.apply {
            addService(service)
        }

        advertiser = adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(svcUuid))
            .setIncludeDeviceName(true)
            .build()
        advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                listener.onStatus("正在广播：等对方来接收（保持这个窗口打开）")
            }

            override fun onStartFailure(errorCode: Int) {
                listener.onStatus("广播失败（错误码 $errorCode），换台设备或重试")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { advertiser?.stopAdvertising(null) }
        runCatching { server?.close() }
        advertiser = null
        server = null
    }
}
