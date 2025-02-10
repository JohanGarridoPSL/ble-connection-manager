/*
 * Copyright 2024 Punch Through Design LLC
 *
 * Modifications Copyright 2024 Pretty Smart Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications made on february 5 2025 by Pretty Smart Labs:
 * - Read remote RSSI operation has been added.
 * - Find characteristic by uuid function has been added.
 */
package com.prettysmartlabs.ble_connection_manager.connection_manager

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "ConnectionManager"
private const val GATT_MIN_MTU_SIZE = 23
/** Maximum BLE MTU size as defined in gatt_api.h. */
private const val GATT_MAX_MTU_SIZE = 517

@SuppressLint("MissingPermission") // Assume permissions are handled by UI
object ConnectionManager {

    private var listeners: MutableSet<WeakReference<ConnectionEventListener>> = mutableSetOf()
    private val listenersAsSet
        get() = listeners.toSet()

    /**
     * A map of BluetoothDevice to BluetoothGatt, keeping track of active connections.
     */
    private val deviceGattMap = ConcurrentHashMap<BluetoothDevice, BluetoothGatt>()

    private val operationQueue = ConcurrentLinkedQueue<BleOperationType>()
    private var pendingOperation: BleOperationType? = null

    fun servicesOnDevice(device: BluetoothDevice): List<BluetoothGattService>? =
        deviceGattMap[device]?.services

    /**
     * Find a characteristic on a device by its UUID.
     * @param uuid The UUID of the service to find.
     * @return The characteristic if found, null otherwise.
     */
    fun findCharacteristicOnServiceByUuid(service: BluetoothGattService, uuid: UUID): BluetoothGattCharacteristic? =
        service.characteristics.find { it.uuid == uuid }

    fun listenToBondStateChanges(context: Context) {
        context.applicationContext.registerReceiver(
            broadcastReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
    }

    fun registerListener(listener: ConnectionEventListener) {
        if (listeners.map { it.get() }.contains(listener)) return
        listeners.add(WeakReference(listener))
        listeners = listeners.filter { it.get() != null }.toMutableSet()
        Log.d(TAG, "Added listener $listener, ${listeners.size} listeners total")
    }

    fun unregisterListener(listener: ConnectionEventListener) {
        // Removing elements while in a loop leads to a ConcurrentModificationException if not careful
        var toRemove: WeakReference<ConnectionEventListener>? = null
        listenersAsSet.forEach {
            if (it.get() == listener) {
                toRemove = it
            }
        }
        toRemove?.let {
            listeners.remove(it)
            Log.d(TAG, "Removed listener ${it.get()}, ${listeners.size} listeners total")
        }
    }

    // -----------------------------------------
    // Main public APIs for connecting, reading, writing, etc.
    // -----------------------------------------

    fun connect(device: BluetoothDevice, context: Context) {
        if (device.isConnected()) {
            Log.e(TAG, "Already connected to ${device.address}!")
        } else {
            enqueueOperation(Connect(device, context.applicationContext))
        }
    }

    fun teardownConnection(device: BluetoothDevice) {
        if (device.isConnected()) {
            enqueueOperation(Disconnect(device))
        } else {
            Log.e(TAG, "Not connected to ${device.address}, cannot teardown connection!")
        }
    }

    fun readCharacteristic(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        if (device.isConnected() && characteristic.isReadable()) {
            enqueueOperation(CharacteristicRead(device, characteristic.uuid))
        } else if (!characteristic.isReadable()) {
            Log.e(TAG, "Attempting to read ${characteristic.uuid} that isn't readable!")
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot perform characteristic read")
        }
    }

    fun writeCharacteristic(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        val writeType = when {
            characteristic.isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.isWritableWithoutResponse() ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> {
                Log.e(TAG, "Characteristic ${characteristic.uuid} cannot be written to")
                return
            }
        }
        if (device.isConnected()) {
            enqueueOperation(CharacteristicWrite(device, characteristic.uuid, writeType, payload))
        } else {
            Log.e(TAG, "Not connected to ${device.address}, cannot perform characteristic write")
        }
    }

    fun readDescriptor(device: BluetoothDevice, descriptor: BluetoothGattDescriptor) {
        if (device.isConnected() && descriptor.isReadable()) {
            enqueueOperation(DescriptorRead(device, descriptor.uuid))
        } else if (!descriptor.isReadable()) {
            Log.e(TAG, "Attempting to read ${descriptor.uuid} that isn't readable!")
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot perform descriptor read")
        }
    }

    fun writeDescriptor(
        device: BluetoothDevice,
        descriptor: BluetoothGattDescriptor,
        payload: ByteArray
    ) {
        if (device.isConnected() && (descriptor.isWritable() || descriptor.isCccd())) {
            enqueueOperation(DescriptorWrite(device, descriptor.uuid, payload))
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot perform descriptor write")
        } else if (!descriptor.isWritable() && !descriptor.isCccd()) {
            Log.e(TAG, "Descriptor ${descriptor.uuid} cannot be written to")
        }
    }

    fun enableNotifications(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        if (device.isConnected() &&
            (characteristic.isIndicatable() || characteristic.isNotifiable())
        ) {
            enqueueOperation(EnableNotifications(device, characteristic.uuid))
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot enable notifications")
        } else if (!characteristic.isIndicatable() && !characteristic.isNotifiable()) {
            Log.e(TAG, "Characteristic ${characteristic.uuid} doesn't support notifications/indications")
        }
    }

    fun disableNotifications(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        if (device.isConnected() &&
            (characteristic.isIndicatable() || characteristic.isNotifiable())
        ) {
            enqueueOperation(DisableNotifications(device, characteristic.uuid))
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot disable notifications")
        } else if (!characteristic.isIndicatable() && !characteristic.isNotifiable()) {
            Log.e(TAG, "Characteristic ${characteristic.uuid} doesn't support notifications/indications")
        }
    }

    fun requestMtu(device: BluetoothDevice, mtu: Int) {
        if (device.isConnected()) {
            enqueueOperation(MtuRequest(device, mtu.coerceIn(GATT_MIN_MTU_SIZE, GATT_MAX_MTU_SIZE)))
        } else {
            Log.e(TAG, "Not connected to ${device.address}, cannot request MTU update!")
        }
    }

    /**
     * **New**: Provide a public function to read the remote RSSI. It will
     * enqueue a new `ReadRemoteRssi` operation that eventually calls `gatt.readRemoteRssi()`.
     */
    fun readRemoteRssi(device: BluetoothDevice) {
        if (device.isConnected()) {
            enqueueOperation(ReadRemoteRssi(device))
        } else {
            Log.e(TAG, "Not connected to ${device.address}, cannot read remote RSSI")
        }
    }

    // -----------------------------------------
    // Internal queue mechanism
    // -----------------------------------------

    @Synchronized
    private fun enqueueOperation(operation: BleOperationType) {
        operationQueue.add(operation)
        if (pendingOperation == null) {
            doNextOperation()
        }
    }

    @Synchronized
    private fun signalEndOfOperation() {
        Log.d(TAG, "End of $pendingOperation")
        pendingOperation = null
        if (operationQueue.isNotEmpty()) {
            doNextOperation()
        }
    }

    @Synchronized
    private fun doNextOperation() {
        if (pendingOperation != null) {
            Log.e(TAG, "doNextOperation() called while another operation is pending! Aborting.")
            return
        }

        val operation = operationQueue.poll() ?: run {
            Log.v(TAG, "Operation queue empty, returning")
            return
        }
        pendingOperation = operation

        // Handle Connect separately
        if (operation is Connect) {
            with(operation) {
                Log.w(TAG, "Connecting to ${device.address}")
                device.connectGatt(context, false, callback)
            }
            return
        }

        // All other operations need an active Gatt
        val gatt = deviceGattMap[operation.device]
            ?: run {
                Log.e(TAG, "Not connected to ${operation.device.address}! Aborting $operation.")
                signalEndOfOperation()
                return
            }

        when (operation) {
            is Disconnect -> with(operation) {
                Log.w(TAG, "Disconnecting from ${device.address}")
                gatt.close()
                deviceGattMap.remove(device)
                listenersAsSet.forEach { it.get()?.onDisconnect?.invoke(device) }
                signalEndOfOperation()
            }
            is CharacteristicWrite -> with(operation) {
                gatt.findCharacteristic(characteristicUuid)?.executeWrite(gatt, payload, writeType)
                    ?: run {
                        Log.e(TAG, "Cannot find $characteristicUuid to write to")
                        signalEndOfOperation()
                    }
            }
            is CharacteristicRead -> with(operation) {
                gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
                    gatt.readCharacteristic(characteristic)
                } ?: run {
                    Log.e(TAG, "Cannot find $characteristicUuid to read from")
                    signalEndOfOperation()
                }
            }
            is DescriptorWrite -> with(operation) {
                gatt.findDescriptor(descriptorUuid)?.executeWrite(gatt, payload)
                    ?: run {
                        Log.e(TAG, "Cannot find $descriptorUuid to write to")
                        signalEndOfOperation()
                    }
            }
            is DescriptorRead -> with(operation) {
                gatt.findDescriptor(descriptorUuid)?.let { descriptor ->
                    gatt.readDescriptor(descriptor)
                } ?: run {
                    Log.e(TAG, "Cannot find $descriptorUuid to read from")
                    signalEndOfOperation()
                }
            }
            is EnableNotifications -> with(operation) {
                gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
                    val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
                    val payload = when {
                        characteristic.isIndicatable() ->
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        characteristic.isNotifiable() ->
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else ->
                            error("${characteristic.uuid} doesn't support notifications/indications")
                    }

                    characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
                        if (!gatt.setCharacteristicNotification(characteristic, true)) {
                            Log.e(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
                            signalEndOfOperation()
                            return
                        }
                        cccDescriptor.executeWrite(gatt, payload)
                    } ?: run {
                        Log.e(TAG, "${characteristic.uuid} has no CCC descriptor!")
                        signalEndOfOperation()
                    }
                } ?: run {
                    Log.e(TAG, "Cannot find $characteristicUuid! Failed to enable notifications.")
                    signalEndOfOperation()
                }
            }
            is DisableNotifications -> with(operation) {
                gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
                    val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
                    characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
                        if (!gatt.setCharacteristicNotification(characteristic, false)) {
                            Log.e(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
                            signalEndOfOperation()
                            return
                        }
                        cccDescriptor.executeWrite(gatt, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                    } ?: run {
                        Log.e(TAG, "${characteristic.uuid} has no CCC descriptor!")
                        signalEndOfOperation()
                    }
                } ?: run {
                    Log.e(TAG, "Cannot find $characteristicUuid! Failed to disable notifications.")
                    signalEndOfOperation()
                }
            }
            is MtuRequest -> with(operation) {
                gatt.requestMtu(mtu)
            }
            /**
             * **Handle the new read-remote-RSSI operation**:
             */
            is ReadRemoteRssi -> {
                Log.d(TAG, "Reading remote RSSI for device ${operation.device.address}")
                gatt.readRemoteRssi()
                // We'll finalize the operation in onReadRemoteRssi()
            }
            else -> error("Unsupported operation: $operation")
        }
    }

    // -----------------------------------------
    // The main GATT callback for all operations
    // -----------------------------------------
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.w(TAG, "onConnectionStateChange: connected to $deviceAddress")
                    deviceGattMap[gatt.device] = gatt
                    Handler(Looper.getMainLooper()).post {
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "onConnectionStateChange: disconnected from $deviceAddress")
                    teardownConnection(gatt.device)
                }
            } else {
                Log.e(
                    TAG,
                    "onConnectionStateChange: status $status encountered for $deviceAddress!"
                )
                // If we failed to connect, let's end the operation
                if (pendingOperation is Connect) {
                    signalEndOfOperation()
                }
                teardownConnection(gatt.device)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Discovered ${services.size} services for ${device.address}.")
                    printGattTable()
                    requestMtu(device, GATT_MAX_MTU_SIZE)
                    listenersAsSet.forEach { it.get()?.onConnectionSetupComplete?.invoke(this) }
                } else {
                    Log.e(TAG, "Service discovery failed due to status $status")
                    teardownConnection(gatt.device)
                }
            }

            if (pendingOperation is Connect) {
                signalEndOfOperation()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.w(TAG, "ATT MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")
            listenersAsSet.forEach { it.get()?.onMtuChanged?.invoke(gatt.device, mtu) }

            if (pendingOperation is MtuRequest) {
                signalEndOfOperation()
            }
        }

        //region Characteristic Read
        @Deprecated("Deprecated for Android 13+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(TAG, "Read characteristic $uuid | value: ${value.toHexString()}")
                        listenersAsSet.forEach {
                            it.get()?.onCharacteristicRead?.invoke(gatt.device, this, value)
                        }
                    }
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        Log.e(TAG, "Read not permitted for $uuid!")
                    }
                    else -> {
                        Log.e(TAG, "Characteristic read failed for $uuid, error: $status")
                    }
                }
            }
            if (pendingOperation is CharacteristicRead) {
                signalEndOfOperation()
            }
        }

        // For Android 13+:
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val uuid = characteristic.uuid
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Read characteristic $uuid | value: ${value.toHexString()}")
                    listenersAsSet.forEach {
                        it.get()?.onCharacteristicRead?.invoke(gatt.device, characteristic, value)
                    }
                }
                BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                    Log.e(TAG, "Read not permitted for $uuid!")
                }
                else -> {
                    Log.e(TAG, "Characteristic read failed for $uuid, error: $status")
                }
            }
            if (pendingOperation is CharacteristicRead) {
                signalEndOfOperation()
            }
        }
        //endregion

        //region Characteristic Write
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val writtenValue = (pendingOperation as? CharacteristicWrite)?.payload
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(TAG, "Wrote to characteristic $uuid | value: ${writtenValue?.toHexString()}")
                        listenersAsSet.forEach {
                            it.get()?.onCharacteristicWrite?.invoke(gatt.device, this)
                        }
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e(TAG, "Write not permitted for $uuid!")
                    }
                    else -> {
                        Log.e(TAG, "Characteristic write failed for $uuid, error: $status")
                    }
                }
            }
            if (pendingOperation is CharacteristicWrite) {
                signalEndOfOperation()
            }
        }
        //endregion

        //region Characteristic Changed
        @Deprecated("Deprecated for Android 13+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            with(characteristic) {
                Log.i(TAG, "Characteristic $uuid changed | value: ${value.toHexString()}")
                listenersAsSet.forEach {
                    it.get()?.onCharacteristicChanged?.invoke(gatt.device, this, value)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.i(TAG, "Characteristic ${characteristic.uuid} changed | value: ${value.toHexString()}")
            listenersAsSet.forEach {
                it.get()?.onCharacteristicChanged?.invoke(gatt.device, characteristic, value)
            }
        }
        //endregion

        //region Descriptor Read
        @Deprecated("Deprecated for Android 13+")
        @Suppress("DEPRECATION")
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            with(descriptor) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(TAG, "Read descriptor $uuid | value: ${value.toHexString()}")
                        listenersAsSet.forEach {
                            it.get()?.onDescriptorRead?.invoke(gatt.device, this, value)
                        }
                    }
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        Log.e(TAG, "Read not permitted for $uuid!")
                    }
                    else -> {
                        Log.e(TAG, "Descriptor read failed for $uuid, error: $status")
                    }
                }
            }
            if (pendingOperation is DescriptorRead) {
                signalEndOfOperation()
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            val uuid = descriptor.uuid
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Read descriptor $uuid | value: ${value.toHexString()}")
                    listenersAsSet.forEach {
                        it.get()?.onDescriptorRead?.invoke(gatt.device, descriptor, value)
                    }
                }
                BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                    Log.e(TAG, "Read not permitted for $uuid!")
                }
                else -> {
                    Log.e(TAG, "Descriptor read failed for $uuid, error: $status")
                }
            }
            if (pendingOperation is DescriptorRead) {
                signalEndOfOperation()
            }
        }
        //endregion

        //region Descriptor Write
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val operationType = pendingOperation
            with(descriptor) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(TAG, "Wrote to descriptor $uuid | operation type: $operationType")
                        if (isCccd() &&
                            (operationType is EnableNotifications || operationType is DisableNotifications)
                        ) {
                            onCccdWrite(gatt, characteristic, operationType)
                        } else {
                            listenersAsSet.forEach {
                                it.get()?.onDescriptorWrite?.invoke(gatt.device, this)
                            }
                        }
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e(TAG, "Write not permitted for $uuid!")
                    }
                    else -> {
                        Log.e(TAG, "Descriptor write failed for $uuid, error: $status")
                    }
                }
            }

            val isNotificationsOperation = descriptor.isCccd() &&
                    (operationType is EnableNotifications || operationType is DisableNotifications)
            val isManualWriteOperation = !descriptor.isCccd() && operationType is DescriptorWrite
            if (isNotificationsOperation || isManualWriteOperation) {
                signalEndOfOperation()
            }
        }
        //endregion

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onReadRemoteRssi: device=${gatt.device.address}, RSSI=$rssi")
                // If you want to notify listeners, you can add a callback:
                listenersAsSet.forEach { it.get()?.onReadRemoteRssi?.invoke(gatt.device, rssi) }
            } else {
                Log.e(TAG, "onReadRemoteRssi failed: status=$status")
            }

            // End the operation if it's a readRSSI
            if (pendingOperation is ReadRemoteRssi) {
                signalEndOfOperation()
            }
        }

        private fun onCccdWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            operationType: BleOperationType
        ) {
            val charUuid = characteristic.uuid
            when (operationType) {
                is EnableNotifications -> {
                    Log.w(TAG, "Notifications or indications ENABLED on $charUuid")
                    listenersAsSet.forEach {
                        it.get()?.onNotificationsEnabled?.invoke(gatt.device, characteristic)
                    }
                }
                is DisableNotifications -> {
                    Log.w(TAG, "Notifications or indications DISABLED on $charUuid")
                    listenersAsSet.forEach {
                        it.get()?.onNotificationsDisabled?.invoke(gatt.device, characteristic)
                    }
                }
                else -> {
                    Log.e(TAG, "Unexpected operation type of $operationType on CCCD of $charUuid")
                }
            }
        }
    }

    // Standard broadcast receiver for bond state changes, if needed
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            with(intent) {
                if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val device = parcelableExtraCompat<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val previousBondState = getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    val bondState = getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val bondTransition = "${previousBondState.toBondStateDescription()} to " +
                            bondState.toBondStateDescription()
                    Log.w(TAG, "${device?.address} bond state changed | $bondTransition")
                }
            }
        }

        private fun Int.toBondStateDescription() = when (this) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_NONE -> "NOT BONDED"
            else -> "ERROR: $this"
        }
    }

    private fun BluetoothDevice.isConnected() = deviceGattMap.containsKey(this)

    /**
     * A backwards compatible approach of obtaining a parcelable extra from an [Intent] object.
     *
     * NOTE: Despite the docs stating that [Intent.getParcelableExtra] is deprecated in Android 13,
     * Google has confirmed in https://issuetracker.google.com/issues/240585930#comment6 that the
     * replacement API is buggy for Android 13, and they suggested that developers continue to use the
     * deprecated API for Android 13. The issue will be fixed for Android 14 (U).
     */
    internal inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(key: String): T? = when {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
    }
}
