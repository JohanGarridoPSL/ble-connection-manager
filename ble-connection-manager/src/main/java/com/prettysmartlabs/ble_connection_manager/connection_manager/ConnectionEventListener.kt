/*
 * Copyright 2024 Punch Through Design LLC
 *
 * Modifications Copyright 2024 Pretty Smart Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications made on February 5 2025 by Pretty Smart Labs:
 * - onReadRemoteRssi callback added
 * - Documentation and comments added and improved.
 * - alreadyConnected callback added.
 * - onConnectToDeviceEnqueued callback added.
 * - onConnectingToGatt callback added.
 * - onGattConnected callback added.
 * - onGattDiscoveringServices callback added.
 * - onConnectFailure callback added.
 * - onfailedToDisconnect callback added.
 */
package com.prettysmartlabs.ble_connection_manager.connection_manager

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

/**
 * A listener class that contains callback functions to handle various Bluetooth connection events.
 *
 * To use this listener, simply set the desired callbacks, which will be invoked by the
 * [ConnectionManager] when corresponding events occur.
 *
 * The following callbacks can be set:
 * - [onConnectionSetupComplete] - Called when the Bluetooth connection setup is complete.
 * - [onDisconnect] - Called when the device disconnects.
 * - [onConnectToDeviceEnqueued] - Called when a connection to a device is enqueued.
 * - [onConnectingToGatt] - Called when a connection to a device is being established.
 * - [onGattConnected] - Called when a connection to a device gatt is completed.
 * - [onGattDiscoveringServices] - Called when a discovery of services in a gatt is called.
 * - [onDescriptorRead] - Called when a descriptor is successfully read.
 * - [onDescriptorWrite] - Called when a descriptor is successfully written.
 * - [onCharacteristicChanged] - Called when a characteristic value changes (e.g., via notification).
 * - [onCharacteristicRead] - Called when a characteristic is successfully read.
 * - [onCharacteristicWrite] - Called when a characteristic is successfully written.
 * - [onNotificationsEnabled] - Called when notifications are enabled for a characteristic.
 * - [onNotificationsDisabled] - Called when notifications are disabled for a characteristic.
 * - [onMtuChanged] - Called when the MTU (Maximum Transmission Unit) changes.
 * - [onReadRemoteRssi] - Called when the remote device's RSSI is read.
 * - [alreadyConnected] - Called when the device is already connected.
 * - [onDisconnectDueToFailure] - Called when a connection to a device fails.
 * - [onFailedToDisconnect] - Called when a disconnection to a device fails.
 * - [onGattDisconnected] - Called when a gatt disconnects.
 */
class ConnectionEventListener {

    /**
     * Callback invoked when the BluetoothGatt connection setup is complete.
     *
     * @param gatt The [BluetoothGatt] instance representing the established connection.
     */
    var onConnectionSetupComplete: ((gatt: BluetoothGatt) -> Unit)? = null

    /**
     * Callback invoked when the Bluetooth device disconnects.
     *
     * @param device The [BluetoothDevice] that has been disconnected.
     */
    var onDisconnect: ((device: BluetoothDevice) -> Unit)? = null

    /**
     * Callback invoked when a connection to a device is enqueued.
     *
     * @param device The [BluetoothDevice] that is being connected.
     */
    var onConnectToDeviceEnqueued: ((device: BluetoothDevice) -> Unit)? = null

    /**
     * Callback invoked when a connection to a device gatt is called.
     *
     * @param device The [BluetoothDevice] that is being connected.
     */
    var onConnectingToGatt: ((device: BluetoothDevice) -> Unit)? = null

    /**
     * Callback invoked when a connection to a device gatt is completed.
     *
     * @param device The [BluetoothDevice] that is being connected.
     */
    var onGattConnected : ((device: BluetoothDevice) -> Unit)? = null

    /**
     * Callback invoked when a discovery of services in a gatt is called.
     *
     * @param device The [BluetoothDevice] that is being connected.
     */
    var onGattDiscoveringServices : ((device: BluetoothDevice) -> Unit)? = null


    /**
     * Callback invoked when a descriptor is successfully read.
     *
     * @param device The [BluetoothDevice] associated with the descriptor.
     * @param descriptor The [BluetoothGattDescriptor] that was read.
     * @param value The byte array value returned from the descriptor.
     */
    var onDescriptorRead: ((device: BluetoothDevice, descriptor: BluetoothGattDescriptor, value: ByteArray) -> Unit)? = null

    /**
     * Callback invoked when a descriptor is successfully written.
     *
     * @param device The [BluetoothDevice] associated with the descriptor.
     * @param descriptor The [BluetoothGattDescriptor] that was written.
     */
    var onDescriptorWrite: ((device: BluetoothDevice, descriptor: BluetoothGattDescriptor) -> Unit)? = null

    /**
     * Callback invoked when a characteristic value changes, typically due to a notification.
     *
     * @param device The [BluetoothDevice] associated with the characteristic.
     * @param characteristic The [BluetoothGattCharacteristic] that changed.
     * @param value The new value of the characteristic as a byte array.
     */
    var onCharacteristicChanged: ((device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray) -> Unit)? = null

    /**
     * Callback invoked when a characteristic is successfully read.
     *
     * @param device The [BluetoothDevice] associated with the characteristic.
     * @param characteristic The [BluetoothGattCharacteristic] that was read.
     * @param value The byte array value returned from the characteristic.
     */
    var onCharacteristicRead: ((device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray) -> Unit)? = null

    /**
     * Callback invoked when a characteristic is successfully written.
     *
     * @param device The [BluetoothDevice] associated with the characteristic.
     * @param characteristic The [BluetoothGattCharacteristic] that was written.
     */
    var onCharacteristicWrite: ((device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) -> Unit)? = null

    /**
     * Callback invoked when notifications are enabled for a characteristic.
     *
     * @param device The [BluetoothDevice] associated with the characteristic.
     * @param characteristic The [BluetoothGattCharacteristic] for which notifications were enabled.
     */
    var onNotificationsEnabled: ((device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) -> Unit)? = null

    /**
     * Callback invoked when notifications are disabled for a characteristic.
     *
     * @param device The [BluetoothDevice] associated with the characteristic.
     * @param characteristic The [BluetoothGattCharacteristic] for which notifications were disabled.
     */
    var onNotificationsDisabled: ((device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) -> Unit)? = null

    /**
     * Callback invoked when the MTU (Maximum Transmission Unit) changes.
     *
     * @param device The [BluetoothDevice] associated with the connection.
     * @param newMtu The new MTU value.
     */
    var onMtuChanged: ((device: BluetoothDevice, newMtu: Int) -> Unit)? = null

    /**
     * Callback invoked when the remote device's RSSI is read.
     *
     * @param device The [BluetoothDevice] whose RSSI was read.
     * @param rssi The received signal strength indicator (RSSI) value.
     */
    var onReadRemoteRssi: ((device: BluetoothDevice, rssi: Int) -> Unit)? = null

    /**
     * Callback invoked when the device is already connected.
     *
     * @param device The [BluetoothDevice] that is already connected.
     *
     */
    var alreadyConnected: ((BluetoothDevice) -> Unit)? = null

    /**
     * Callback invoked when a device is disconnected due to a failure in the connection.
     *
     * @param device The [BluetoothDevice] that failed to connect.
     * @param error The [ConnectionError] that describes the failure.
     */
    var onDisconnectDueToFailure: ((device: BluetoothDevice, error: DisconnectionError) -> Unit)? = null

    /**
     * Callback invoked when a disconnection to a device fails.
     *
     * @param device The [BluetoothDevice] that failed to disconnect.
     * @param error The [ConnectionError] that describes the failure.
     */
    var onFailedToDisconnect: ((device: BluetoothDevice, error: DisconnectionError) -> Unit)? = null

    /**
     * Callback invoked when a gatt disconnects.
     *
     * @param device The [BluetoothDevice] that is being disconnected.
     */
    var onGattDisconnected: ((BluetoothDevice) -> Unit)? = null

}
