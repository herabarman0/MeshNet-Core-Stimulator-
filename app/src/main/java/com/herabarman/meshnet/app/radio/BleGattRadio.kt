package com.herabarman.meshnet.app.radio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.herabarman.meshnet.core.TurboScheduler
import com.herabarman.meshnet.kit.RawRadio
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Android Bluetooth Low Energy (BLE) GATT Server + Client RawRadio.
 * Advertises this phone's MeshNet nodeId via Service Data, connects to nearby peers over GATT,
 * negotiates MTU up to 512 bytes, and chunks/coalesces MeshNet frames over BLE characteristics.
 */
class BleGattRadio(
    private val context: Context,
    private val selfNode: Int
) : RawRadio {

    companion object {
        private const val TAG = "BleGattRadio"
        val SERVICE_UUID: UUID = UUID.fromString("0000fe88-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000fe89-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("0000fe8a-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_CHUNK = 180
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = btManager?.adapter

    @Volatile
    private var handler: RawRadio.Handler? = null

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastStatus: String = "Idle"
        private set

    private var gattServer: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null

    private val peerToDevice = ConcurrentHashMap<Int, BluetoothDevice>()
    private val addrToPeer = ConcurrentHashMap<String, Int>()
    private val peerGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val peerWriteChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val peerMtu = ConcurrentHashMap<String, Int>()
    private val reassembly = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    var packetsSent: Long = 0L
        private set

    @Volatile
    var packetsReceived: Long = 0L
        private set

    override fun setHandler(h: RawRadio.Handler?) {
        this.handler = h
    }

    fun connectedPeerCount(): Int = peerToDevice.size

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            hasPerm(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPerm(Manifest.permission.BLUETOOTH_ADVERTISE) &&
                hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun start() {
        if (isActive) return
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            lastStatus = "Bluetooth বন্ধ বা অনুপলব্ধ"
            return
        }
        if (!hasBlePermissions()) {
            lastStatus = "BLE অনুমতি প্রয়োজন"
            return
        }
        try {
            startGattServer()
            startAdvertising(ad)
            startScanning(ad)
            isActive = true
            lastStatus = "BLE GATT সক্রিয়"
        } catch (e: SecurityException) {
            lastStatus = "BLE অনুমতি অস্বীকৃত"
        } catch (e: Exception) {
            lastStatus = "BLE ত্রুটি: ${e.message}"
            Log.w(TAG, "BLE start failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        isActive = false
        if (!hasBlePermissions()) return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) { }
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
        for (g in peerGatts.values) {
            try { g.disconnect(); g.close() } catch (_: Exception) { }
        }
        peerGatts.clear()
        peerWriteChars.clear()
        peerToDevice.clear()
        addrToPeer.clear()
        try { gattServer?.close() } catch (_: Exception) { }
        gattServer = null
        lastStatus = "বন্ধ"
    }

    @SuppressLint("MissingPermission")
    override fun drop(peerNode: Int) {
        val dev = peerToDevice.remove(peerNode) ?: return
        addrToPeer.remove(dev.address)
        peerGatts.remove(dev.address)?.let {
            try { it.disconnect(); it.close() } catch (_: Exception) { }
        }
        handler?.onLinkDown(peerNode)
    }

    @SuppressLint("MissingPermission")
    override fun sendRaw(peerNode: Int, data: ByteArray) {
        if (!isActive || !hasBlePermissions()) return
        val dev = peerToDevice[peerNode] ?: return
        val addr = dev.address
        val mtu = (peerMtu[addr] ?: DEFAULT_CHUNK) - 6
        val chunkSize = mtu.coerceIn(20, 480)
        // Frame format: seqByte(1) total(1) idx(1) payload
        val total = ((data.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        if (total > 250) return

        val writeCh = peerWriteChars[addr]
        val gatt = peerGatts[addr]
        for (idx in 0 until total) {
            val off = idx * chunkSize
            val len = minOf(chunkSize, data.size - off)
            val pkt = ByteBuffer.allocate(7 + len)
                .putInt(selfNode)
                .put(total.toByte())
                .put(idx.toByte())
                .put(0.toByte())
                .put(data, off, len)
                .array()
            if (writeCh != null && gatt != null) {
                writeCharacteristicCompat(gatt, writeCh, pkt)
            } else {
                val srv = gattServer
                val ch = txChar
                if (srv != null && ch != null) {
                    notifyCompat(srv, dev, ch, pkt)
                }
            }
        }
        packetsSent++
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            ch.value = value
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(ch)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun notifyCompat(srv: BluetoothGattServer, dev: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            srv.notifyCharacteristicChanged(dev, ch, false, value)
        } else {
            ch.value = value
            srv.notifyCharacteristicChanged(dev, ch, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        val mgr = btManager ?: return
        val srv = mgr.openGattServer(context, gattServerCallback) ?: return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        srv.addService(service)
        gattServer = srv
        txChar = tx
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(ad: BluetoothAdapter) {
        val adv = ad.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val nodeBytes = ByteBuffer.allocate(4).putInt(selfNode).array()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), nodeBytes)
            .build()
        adv.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(ad: BluetoothAdapter) {
        val scanner = ad.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun handleIncomingPacket(device: BluetoothDevice, value: ByteArray) {
        if (value.size < 7) return
        val b = ByteBuffer.wrap(value)
        val peerNode = b.int
        val total = b.get().toInt() and 0xFF
        val idx = b.get().toInt() and 0xFF
        b.get() // reserved
        if (peerNode <= 0 || peerNode == selfNode || total <= 0 || idx >= total) return
        val payload = ByteArray(b.remaining())
        b.get(payload)

        if (peerToDevice.putIfAbsent(peerNode, device) == null) {
            addrToPeer[device.address] = peerNode
            handler?.onLinkUp(peerNode)
        }

        val delivered: ByteArray? = if (total == 1) {
            payload
        } else {
            val key = "${device.address}:$peerNode"
            val prev = if (idx == 0) ByteArray(0) else (reassembly[key] ?: return)
            val combined = prev + payload
            if (idx == total - 1) {
                reassembly.remove(key)
                combined
            } else {
                reassembly[key] = combined
                null
            }
        }
        if (delivered != null) {
            packetsReceived++
            for (frame in TurboScheduler.unpackBurst(delivered)) {
                handler?.onRaw(peerNode, frame)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            lastStatus = "BLE Advertising + GATT Active"
        }

        override fun onStartFailure(errorCode: Int) {
            lastStatus = "BLE Adv error $errorCode"
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isActive) return
            val dev = result.device ?: return
            val addr = dev.address ?: return
            val svcData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
            val peerId = if (svcData != null && svcData.size >= 4) ByteBuffer.wrap(svcData).int else -1
            if (peerId <= 0 || peerId == selfNode) return
            if (peerGatts.containsKey(addr)) return
            addrToPeer[addr] = peerId
            peerToDevice[peerId] = dev
            if (selfNode > peerId) {
                val g = dev.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
                peerGatts[addr] = g
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val peer = addrToPeer.remove(device.address)
                if (peer != null) {
                    peerToDevice.remove(peer)
                    handler?.onLinkDown(peer)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid == RX_CHAR_UUID) {
                handleIncomingPacket(device, value)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            peerMtu[device.address] = mtu
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(512)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peerGatts.remove(addr)
                peerWriteChars.remove(addr)
                val peer = addrToPeer.remove(addr)
                if (peer != null) {
                    peerToDevice.remove(peer)
                    handler?.onLinkDown(peer)
                }
                try { gatt.close() } catch (_: Exception) { }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                peerMtu[gatt.device.address] = mtu
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(SERVICE_UUID) ?: return
            val rx = svc.getCharacteristic(RX_CHAR_UUID)
            if (rx != null) {
                peerWriteChars[gatt.device.address] = rx
                addrToPeer[gatt.device.address]?.let { handler?.onLinkUp(it) }
            }
            val tx = svc.getCharacteristic(TX_CHAR_UUID)
            if (tx != null) {
                gatt.setCharacteristicNotification(tx, true)
            }
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                characteristic.value?.let { handleIncomingPacket(gatt.device, it) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                handleIncomingPacket(gatt.device, value)
            }
        }
    }
}
