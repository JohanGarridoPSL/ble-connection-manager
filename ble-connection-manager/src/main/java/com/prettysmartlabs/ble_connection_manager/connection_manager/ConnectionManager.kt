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
 * - Documentation and comments added and improved.
 * - connect function modified, if a device is already connected, an error is logged and the
 * alreadyConnected callback is invoked. onConnectToDeviceEnqueued callback is invoked instead.
 * - onConnectingToGatt callback is invoked in function connect now.
 * - GATT callback modified, onGattConnected callback is invoked when the device is connected, also
 * onGattDiscoveringServices callback is invoked when a discovery of services in a gatt is called.
 * - GATT callback modified when the gatt fails, the error is analyzed using analyzeConnectionError 
 * and send to teardownConnection function.
 * - onDisconnectDueToFailure callback is invoked when a device is disconnected due to a failure in the connection.
 * - onFailedToDisconnect callback is invoked when a disconnection to a device fails.
 * - onGattDisconnected callback is invoked when a gatt disconnects.
 * - analyzeConnectionError function and the sealed class DisconnectionError have been added to handle connection errors
 * - teardownConnection function has been added to handle connection errors.
 * - onBondStateChanged callback is invoked when the bond state of a device changes.
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
private const val TAG = "ConnectionManager" // Log tag for debugging purposes
private const val GATT_MIN_MTU_SIZE = 23  // Minimum allowed MTU size for BLE connections
/** Maximum BLE MTU size as defined in gatt_api.h. */
private const val GATT_MAX_MTU_SIZE = 517

@SuppressLint("MissingPermission") // Assume permissions are handled by UI
object ConnectionManager {

    /**
     * A set of weak references to registered [ConnectionEventListener] instances.
     * Weak references are used to avoid memory leaks.
     */
    private var listeners: MutableSet<WeakReference<ConnectionEventListener>> = mutableSetOf()

    /**
     * Analyzes the connection error based on the provided GATT [status] code and [device].
     *
     * This function inspects could return the following errors:
     * - [DisconnectionError.DeviceNotConnected] - Device is not connected.
     * - [DisconnectionError.GeneralError] - General error with a message.
     * - [DisconnectionError.DfuUploadFailure] - Error during DFU upload.
     * - [DisconnectionError.ExpectedDisconnect] - Expected disconnection.
     *
     * @param status The GATT status code returned from an error callback.
     * @param device The Bluetooth device associated with the error.
     * @return A [DisconnectionError] instance representing the error condition.
     */
    private fun analyzeDisconnectionError(status: Int, device: BluetoothDevice): DisconnectionError {
        // Log the incoming parameters for debugging.
        Log.d(TAG, "Analyzing connection error: status=$status, device=${device.address}, pendingOperation=$pendingOperation")

        val currentOperation = pendingOperation

        return when (status) {

            GATT_DISCONNECT_NO_ERROR -> {
                Log.d(TAG, "analyzeConnectionError: ")
                DisconnectionError.ExpectedDisconnect(status, device)
            } 
            GATT_CONNECTION_LOST -> {
                when {
                    // If a Connect operation is pending and the device matches the one in the connection map,
                    // we assume the connection was lost during a DFU upload.
                    currentOperation is Connect &&
                            deviceGattMap[device]?.device?.address == device.address -> {
                        Log.d(TAG, "Connection lost during DFU operation.")
                        DisconnectionError.DfuUploadFailure(status, device)
                    }
                    // If there is no pending operation, consider this an expected disconnect.
                    currentOperation == null -> {
                        Log.d(TAG, "No pending operation: expected disconnect.")
                        DisconnectionError.ExpectedDisconnect(status, device)
                    }
                    // Otherwise, return a general error for this case.
                    else -> {
                        Log.d(TAG, "Unhandled case for connection lost, falling back to general error.")
                        DisconnectionError.GeneralError(status, device, "Unhandled case for connection lost")
                    }
                }
            }
            
            GATT_ERROR_GENERIC -> {
                Log.d(TAG, "Generic BLE connection error (133).")
                DisconnectionError.GeneralError(status, device, "Generic BLE connection error (133)")
            }
            GATT_INTERNAL_ERROR -> {
                Log.d(TAG, "Internal BLE error (129).")
                DisconnectionError.GeneralError(status, device, "Internal BLE error (129)")
            }
            DEVICE_NOT_CONNECTED -> {
                Log.e(TAG, "Device not connected.")
                DisconnectionError.DeviceNotConnected(status, device)
            }
            // For any other status codes, default to a general error.
            else -> {
                Log.d(TAG, "Unhandled status code: $status.")
                DisconnectionError.GeneralError(status, device, "Unhandled status code ($status)")
            }
        }
    }

    /**
     * Returns a snapshot of the current listeners as a set.
     */
    private val listenersAsSet: Set<WeakReference<ConnectionEventListener>>
        get() = listeners.toSet()

    /**
     * A map that keeps track of active Bluetooth connections.
     * Each [BluetoothDevice] is mapped to its corresponding [BluetoothGatt] connection.
     */
    private val deviceGattMap = ConcurrentHashMap<BluetoothDevice, BluetoothGatt>()

    /**
     * A thread-safe queue of pending BLE operations.
     */
    private val operationQueue = ConcurrentLinkedQueue<BleOperationType>()

    /**
     * The currently pending BLE operation, if any.
     */
    private var pendingOperation: BleOperationType? = null

    /**
     * Registers a broadcast receiver to listen for Bluetooth bond state changes.
     *
     * @param context The application context used to register the receiver.
     */
    fun listenToBondStateChanges(context: Context) {
        context.applicationContext.registerReceiver(
            broadcastReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
    }

    /**
     * Registers a [ConnectionEventListener] to receive connection event callbacks.
     *
     * Duplicate registrations are ignored.
     *
     * @param listener The listener to register.
     */
    fun registerListener(listener: ConnectionEventListener) {
        if (listeners.map { it.get() }.contains(listener)) return
        listeners.add(WeakReference(listener))
        // Clean up any null references
        listeners = listeners.filter { it.get() != null }.toMutableSet()
        Log.d(TAG, "Added listener $listener, ${listeners.size} listeners total")
    }

    /**
     * Unregisters a previously registered [ConnectionEventListener].
     *
     * @param listener The listener to remove.
     */
    fun unregisterListener(listener: ConnectionEventListener) {
        // Removing elements while iterating can cause ConcurrentModificationException.
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

    /**
     * Initiates a connection to the specified [device].
     * If the device is already connected, an error is logged and the alreadyConnected callback is invoked.
     *
     * @param device The Bluetooth device to connect to.
     * @param context The context used to initialize the connection.
     */
    fun connect(device: BluetoothDevice, context: Context) {
        if (device.isConnected()) {
            Log.e(TAG, "Already connected to ${device.address}!")
            listeners.forEach { it.get()?.alreadyConnected?.invoke(device) }
        } else {
            listeners.forEach { it.get()?.onConnectToDeviceEnqueued?.invoke(device) }
            enqueueOperation(Connect(device, context.applicationContext))
        }
    }

    /**
     * Retrieves the list of Bluetooth GATT services available on the given [device].
     *
     * @param device The Bluetooth device for which services are requested.
     * @return A list of [BluetoothGattService] objects if connected; null otherwise.
     */
    fun getServicesOnDevice(device: BluetoothDevice): List<BluetoothGattService>? =
        deviceGattMap[device]?.services

    /**
     * Finds a characteristic within the provided [service] that matches the given [uuid].
     *
     * @param service The [BluetoothGattService] to search within.
     * @param uuid The UUID of the characteristic to locate.
     * @return The matching [BluetoothGattCharacteristic] if found, or null otherwise.
     */
    fun findCharacteristicOnServiceByUuid(
        service: BluetoothGattService,
        uuid: UUID
    ): BluetoothGattCharacteristic? = service.characteristics.find { it.uuid == uuid }

    /**
     * Tears down the connection to the given [device] and notifies listeners of a failure
     * if an error [reason] is provided. Default value is [GATT_DISCONNECT_NO_ERROR].
     *
     * If the device is connected, a [Disconnect] operation is enqueued. If an error code is
     * provided, the error is analyzed using [analyzeDisconnectionError]. Listeners
     * are notified of a connection failure **unless** the error code is an expected disconnect.
     * These are the possible errors:
     * - [DisconnectionError.DeviceNotConnected] - Device is not connected.
     * - [DisconnectionError.DfuUploadFailure] - Error during DFU upload.
     * - [DisconnectionError.ExpectedDisconnect] - Expected disconnection.
     * - [DisconnectionError.GeneralError] - General error with a message.
     *
     * @param reason The GATT error status code (default is 100, meaning “no error”).
     * @param device The Bluetooth device to disconnect.
     */
    fun teardownConnection(reason: Int = GATT_DISCONNECT_NO_ERROR, device: BluetoothDevice) {
        Log.w(TAG, "teardownConnection called, reason=$reason")
        if (device.isConnected()) {
            val error = analyzeDisconnectionError(reason, device)
            if (reason != GATT_DISCONNECT_NO_ERROR) {
                // Analyze the error and notify listeners if it is not an expected disconnect.
                if (error !is DisconnectionError.ExpectedDisconnect) {
                    listeners.forEach { it.get()?.onDisconnectDueToFailure?.invoke(device, error) }
                }
            }
            enqueueOperation(Disconnect(device))
        } else {
            val error = analyzeDisconnectionError(DEVICE_NOT_CONNECTED, device)
            if (reason != GATT_DISCONNECT_NO_ERROR && reason != DEVICE_NOT_CONNECTED) {
                Log.e(TAG, "Not connected to ${device.address}, cannot teardown connection!")
                if (error !is DisconnectionError.ExpectedDisconnect) {
                    listeners.forEach { it.get()?.onFailedToDisconnect?.invoke(device, error) }
                }
            } else {
                Log.e(TAG, "Not connected to ${device.address}, cannot teardown connection!")
                listeners.forEach { it.get()?.onDisconnectDueToFailure?.invoke(device, error) }
            }
        }
    }


    /**
     * Initiates a read operation for the given [characteristic] on the specified [device].
     * Validates that the device is connected and the characteristic is readable.
     *
     * @param device The Bluetooth device containing the characteristic.
     * @param characteristic The characteristic to be read.
     */
    fun readCharacteristic(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        if (device.isConnected() && characteristic.isReadable()) {
            enqueueOperation(CharacteristicRead(device, characteristic.uuid))
        } else if (!characteristic.isReadable()) {
            Log.e(TAG, "Attempting to read ${characteristic.uuid} that isn't readable!")
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot perform characteristic read")
        }
    }

    /**
     * Initiates a write operation for the given [characteristic] on the specified [device]
     * using the provided [payload]. The write type is determined by the characteristic's capabilities.
     *
     * @param device The Bluetooth device containing the characteristic.
     * @param characteristic The characteristic to be written.
     * @param payload The data to be written as a byte array.
     */
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

    /**
     * Initiates a read operation for the given [descriptor] on the specified [device].
     * Validates that the device is connected and the descriptor is readable.
     *
     * @param device The Bluetooth device containing the descriptor.
     * @param descriptor The descriptor to be read.
     */
    fun readDescriptor(device: BluetoothDevice, descriptor: BluetoothGattDescriptor) {
        if (device.isConnected() && descriptor.isReadable()) {
            enqueueOperation(DescriptorRead(device, descriptor.uuid))
        } else if (!descriptor.isReadable()) {
            Log.e(TAG, "Attempting to read ${descriptor.uuid} that isn't readable!")
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot perform descriptor read")
        }
    }

    /**
     * Initiates a write operation for the given [descriptor] on the specified [device]
     * using the provided [payload]. Validates that the device is connected and the descriptor
     * is either writable or is a Client Characteristic Configuration Descriptor (CCCD).
     *
     * @param device The Bluetooth device containing the descriptor.
     * @param descriptor The descriptor to be written.
     * @param payload The data to be written as a byte array.
     */
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

    /**
     * Enables notifications or indications for the given [characteristic] on [device].
     *
     * This function validates that the device is connected and that the characteristic supports
     * either notifications or indications. If both conditions are met, it enqueues an
     * [EnableNotifications] operation.
     *
     * @param device The Bluetooth device on which to enable notifications.
     * @param characteristic The characteristic for which notifications or indications should be enabled.
     */
    fun enableNotifications(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        if (device.isConnected() &&
            (characteristic.isIndicatable() || characteristic.isNotifiable())
        ) {
            enqueueOperation(EnableNotifications(device, characteristic.uuid))
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot enable notifications")
        } else if (!characteristic.isIndicatable() && !characteristic.isNotifiable()) {
            Log.e(
                TAG,
                "Characteristic ${characteristic.uuid} doesn't support notifications/indications"
            )
        }
    }

    /**
     * Disables notifications or indications for the given [characteristic] on [device].
     *
     * This function validates that the device is connected and that the characteristic supports
     * notifications or indications. If both conditions are met, it enqueues a [DisableNotifications]
     * operation.
     *
     * @param device The Bluetooth device on which to disable notifications.
     * @param characteristic The characteristic for which notifications or indications should be disabled.
     */
    fun disableNotifications(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        if (device.isConnected() &&
            (characteristic.isIndicatable() || characteristic.isNotifiable())
        ) {
            enqueueOperation(DisableNotifications(device, characteristic.uuid))
        } else if (!device.isConnected()) {
            Log.e(TAG, "Not connected to ${device.address}, cannot disable notifications")
        } else if (!characteristic.isIndicatable() && !characteristic.isNotifiable()) {
            Log.e(
                TAG,
                "Characteristic ${characteristic.uuid} doesn't support notifications/indications"
            )
        }
    }

    /**
     * Requests an MTU update for the given [device] with the desired [mtu].
     *
     * The requested MTU value is coerced between the minimum ([GATT_MIN_MTU_SIZE])
     * and maximum ([GATT_MAX_MTU_SIZE]) allowed values. If the device is not connected,
     * an error is logged.
     *
     * @param device The Bluetooth device for which to request the MTU update.
     * @param mtu The desired MTU size.
     */
    fun requestMtu(device: BluetoothDevice, mtu: Int) {
        if (device.isConnected()) {
            // Enforce MTU boundaries before enqueuing the operation
            enqueueOperation(MtuRequest(device, mtu.coerceIn(GATT_MIN_MTU_SIZE, GATT_MAX_MTU_SIZE)))
        } else {
            Log.e(TAG, "Not connected to ${device.address}, cannot request MTU update!")
        }
    }

    /**
     * Initiates a remote RSSI read operation on the given [device].
     *
     * If the device is connected, a [ReadRemoteRssi] operation is enqueued,
     * which will eventually trigger [BluetoothGatt.readRemoteRssi()]. If not connected,
     * an error is logged.
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

    /**
     * Enqueues the given BLE operation for execution.
     *
     * The operation is added to the thread-safe [operationQueue]. If no operation
     * is currently pending, the next operation is started immediately.
     *
     * @param operation The BLE operation to enqueue.
     */
    @Synchronized
    private fun enqueueOperation(operation: BleOperationType) {
        operationQueue.add(operation)
        if (pendingOperation == null) {
            doNextOperation()
        }
    }

    /**
     * Signals that the current operation has completed.
     *
     * This function logs the completion, clears the pending operation, and triggers
     * execution of the next operation in the queue if one exists.
     */
    @Synchronized
    private fun signalEndOfOperation() {
        Log.d(TAG, "End of $pendingOperation")
        pendingOperation = null
        if (operationQueue.isNotEmpty()) {
            doNextOperation()
        }
    }

    /**
     * Executes the next pending BLE operation from the [operationQueue].
     *
     * If an operation is already in progress, this method logs an error and aborts.
     * Otherwise, it polls the next operation and dispatches it appropriately.
     */
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

        // Handle Connect operation separately because it requires a fresh connection.
        if (operation is Connect) {
            with(operation) {
                Log.w(TAG, "Connecting to GATT of ${device.address}")
                listeners.forEach { it.get()?.onConnectingToGatt?.invoke(device) }
                device.connectGatt(context, false, callback)
            }
            return
        }

        // For all other operations, ensure the device is connected and obtain its active Gatt.
        val gatt = deviceGattMap[operation.device] ?: run {
            Log.e(TAG, "Not connected to ${operation.device.address}! Aborting $operation.")
            signalEndOfOperation()
            return
        }

        // Dispatch the operation based on its type.
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
                            Log.e(
                                TAG,
                                "setCharacteristicNotification failed for ${characteristic.uuid}"
                            )
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
                            Log.e(
                                TAG,
                                "setCharacteristicNotification failed for ${characteristic.uuid}"
                            )
                            signalEndOfOperation()
                            return
                        }
                        cccDescriptor.executeWrite(
                            gatt,
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        )
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

            is ReadRemoteRssi -> {
                Log.d(TAG, "Reading remote RSSI for device ${operation.device.address}")
                gatt.readRemoteRssi()
                // The operation will be finalized in onReadRemoteRssi() callback.
            }

            else -> error("Unsupported operation: $operation")
        }
    }

// -----------------------------------------
// GATT Callback for BLE operations
// -----------------------------------------

    /**
     * The main BluetoothGattCallback instance used to receive GATT events.
     *
     * This callback handles connection state changes, service discovery, MTU changes,
     * characteristic/descriptor reads and writes, notifications, and RSSI readings.
     */
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.w(TAG, "onConnectionStateChange: connected to $deviceAddress")
                    listenersAsSet.forEach { it.get()?.onGattConnected?.invoke(gatt.device) }
                    deviceGattMap[gatt.device] = gatt
                    Handler(Looper.getMainLooper()).post {
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    listeners.forEach { it.get()?.onGattDisconnected?.invoke(gatt.device) }
                    Log.e(TAG, "onConnectionStateChange: disconnected from $deviceAddress")
                    teardownConnection(device = gatt.device)
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
                teardownConnection(status, gatt.device)
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
                    teardownConnection(status, gatt.device)
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

        // ---------------------------
        // Characteristic Read
        // ---------------------------
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

                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> Log.e(
                        TAG,
                        "Read not permitted for $uuid!"
                    )

                    else -> Log.e(TAG, "Characteristic read failed for $uuid, error: $status")
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

                BluetoothGatt.GATT_READ_NOT_PERMITTED -> Log.e(TAG, "Read not permitted for $uuid!")
                else -> Log.e(TAG, "Characteristic read failed for $uuid, error: $status")
            }
            if (pendingOperation is CharacteristicRead) {
                signalEndOfOperation()
            }
        }
        //endregion

        // ---------------------------
        // Characteristic Write
        // ---------------------------
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val writtenValue = (pendingOperation as? CharacteristicWrite)?.payload
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(
                            TAG,
                            "Wrote to characteristic $uuid | value: ${writtenValue?.toHexString()}"
                        )
                        listenersAsSet.forEach {
                            it.get()?.onCharacteristicWrite?.invoke(gatt.device, this)
                        }
                    }

                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> Log.e(
                        TAG,
                        "Write not permitted for $uuid!"
                    )

                    else -> Log.e(TAG, "Characteristic write failed for $uuid, error: $status")
                }
            }
            if (pendingOperation is CharacteristicWrite) {
                signalEndOfOperation()
            }
        }
        //endregion

        // ---------------------------
        // Characteristic Changed
        // ---------------------------
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
            Log.i(
                TAG,
                "Characteristic ${characteristic.uuid} changed | value: ${value.toHexString()}"
            )
            listenersAsSet.forEach {
                it.get()?.onCharacteristicChanged?.invoke(gatt.device, characteristic, value)
            }
        }
        //endregion

        // ---------------------------
        // Descriptor Read
        // ---------------------------
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

                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> Log.e(
                        TAG,
                        "Read not permitted for $uuid!"
                    )

                    else -> Log.e(TAG, "Descriptor read failed for $uuid, error: $status")
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

                BluetoothGatt.GATT_READ_NOT_PERMITTED -> Log.e(TAG, "Read not permitted for $uuid!")
                else -> Log.e(TAG, "Descriptor read failed for $uuid, error: $status")
            }
            if (pendingOperation is DescriptorRead) {
                signalEndOfOperation()
            }
        }
        //endregion

        // ---------------------------
        // Descriptor Write
        // ---------------------------
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

                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> Log.e(
                        TAG,
                        "Write not permitted for $uuid!"
                    )

                    else -> Log.e(TAG, "Descriptor write failed for $uuid, error: $status")
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

            // End the operation if it was a ReadRemoteRssi operation.
            if (pendingOperation is ReadRemoteRssi) {
                signalEndOfOperation()
            }
        }

        /**
         * Handles the completion of a CCCD write operation by invoking the appropriate listener callback.
         *
         * @param gatt The active BluetoothGatt.
         * @param characteristic The characteristic associated with the CCCD.
         * @param operationType The current BLE operation (either EnableNotifications or DisableNotifications).
         */
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

// -----------------------------------------
// Broadcast Receiver for Bond State Changes
// -----------------------------------------

    /**
     * Standard broadcast receiver to listen for Bluetooth bond state changes.
     *
     * This receiver logs the transition between bond states when a bond state change event occurs.
     */
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            with(intent) {
                if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val device =
                        parcelableExtraCompat<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val previousBondState =
                        getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    val bondState = getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val bondTransition = "${previousBondState.toBondStateDescription()} to " +
                            bondState.toBondStateDescription()
                    Log.w(TAG, "${device?.address} bond state changed | $bondTransition")
                    listenersAsSet.forEach {
                        it.get()?.onBondStateChanged?.invoke(
                            device,
                            previousBondState,
                            bondState
                        )
                    }
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

    /**
     * Extension function to check if a [BluetoothDevice] is currently connected.
     *
     * @return True if the device has an associated active BluetoothGatt connection.
     */
    private fun BluetoothDevice.isConnected() = deviceGattMap.containsKey(this)

    /**
     * A backwards-compatible method for retrieving a parcelable extra from an [Intent].
     *
     * NOTE: Despite deprecation warnings for Android 13+, the replacement API is currently buggy.
     *
     * @param key The key for the parcelable extra.
     * @return The parcelable extra of type [T] if available, null otherwise.
     */
    internal inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(key: String): T? =
        when {
            Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(
                key,
                T::class.java
            )

            else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
        }

}

// Common GATT codes.
const val GATT_SUCCESS = 0                  // Indicates success.
const val GATT_DISCONNECT_NO_ERROR = 100    // Indicates success.
const val DEVICE_NOT_CONNECTED = -100       // Indicates the device is not connected.
const val GATT_CONNECTION_LOST = 8          // Indicates the connection was lost.
const val GATT_ERROR_GENERIC = 133          // A generic BLE connection error (often ambiguous).
const val GATT_INTERNAL_ERROR = 129         // An internal error from the BLE stack.

/**
 * Represents the different types of disconnection errors that can occur.
 *
 * - [DfuUploadFailure]: Indicates that the connection was lost during a DFU upload.
 * - [ExpectedDisconnect]: Indicates an expected disconnection.
 * - [GeneralError]: Represents all other connection errors.
 * - [DeviceNotConnected]: Indicates that the device cannot be disconnected because it is not even connected.
 *
 */
sealed class DisconnectionError {

    /**
     * Indicates that the connection was lost during a DFU upload.
     *
     * @property status The GATT error status code.
     * @property device The Bluetooth device involved.
     */
    data class DfuUploadFailure(val status: Int, val device: BluetoothDevice) : DisconnectionError()

    /**
     * Indicates an expected disconnection, for example after completing a DFU operation.
     *
     * @property status The GATT error status code.
     * @property device The Bluetooth device involved.
     */
    data class ExpectedDisconnect(val status: Int, val device: BluetoothDevice) : DisconnectionError()

    /**
     * Represents all other connection errors.
     *
     * @property status The GATT error status code.
     * @property device The Bluetooth device involved.
     * @property details Optional additional details about the error.
     */
    data class GeneralError(val status: Int, val device: BluetoothDevice, val details: String? = null) : DisconnectionError()

    /**
     * Indicates that the device cannot be disconnected because it is not even connected.
     *
     * @property device The Bluetooth device that is not connected.
     * @property status The GATT error status code.
     */
    data class DeviceNotConnected(val status: Int, val device: BluetoothDevice) :
        DisconnectionError()

}