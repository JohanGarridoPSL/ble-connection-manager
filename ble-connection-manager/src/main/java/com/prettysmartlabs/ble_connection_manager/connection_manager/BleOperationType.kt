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
 * - ReadRemoteRssi operation has been added.
 * - Documentation and comments added and improved.
 */
package com.prettysmartlabs.ble_connection_manager.connection_manager

import android.bluetooth.BluetoothDevice
import android.content.Context
import java.util.*

/**
 * Sealed class representing a Bluetooth Low Energy (BLE) operation.
 *
 * This abstract class serves as the common supertype for various BLE operations
 * such as connecting, disconnecting, reading/writing characteristics or descriptors,
 * and other operations like reading RSSI and changing the MTU.
 */
sealed class BleOperationType {
    /**
     * The target Bluetooth device associated with this operation.
     */
    abstract val device: BluetoothDevice
}

/**
 * Operation to read the remote device's RSSI (Received Signal Strength Indicator).
 *
 * @property device The Bluetooth device from which the RSSI will be read.
 */
data class ReadRemoteRssi(
    override val device: BluetoothDevice
) : BleOperationType()

/**
 * Operation to connect to a Bluetooth device and perform service discovery.
 *
 * @property device The Bluetooth device to connect to.
 * @property context The context in which the connection is initiated.
 */
data class Connect(
    override val device: BluetoothDevice,
    val context: Context
) : BleOperationType()

/**
 * Operation to disconnect from a Bluetooth device and release connection resources.
 *
 * @property device The Bluetooth device to disconnect from.
 */
data class Disconnect(
    override val device: BluetoothDevice
) : BleOperationType()

/**
 * Operation to write a payload to a Bluetooth characteristic.
 *
 * @property device The Bluetooth device containing the characteristic.
 * @property characteristicUuid The UUID of the characteristic to write.
 * @property writeType The type of write operation (e.g., with or without response).
 * @property payload The byte array to be written as the characteristic's value.
 */
data class CharacteristicWrite(
    override val device: BluetoothDevice,
    val characteristicUuid: UUID,
    val writeType: Int,
    val payload: ByteArray
) : BleOperationType() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CharacteristicWrite

        if (device != other.device) return false
        if (characteristicUuid != other.characteristicUuid) return false
        if (writeType != other.writeType) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = device.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + writeType
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Operation to read the value of a Bluetooth characteristic.
 *
 * @property device The Bluetooth device containing the characteristic.
 * @property characteristicUuid The UUID of the characteristic to be read.
 */
data class CharacteristicRead(
    override val device: BluetoothDevice,
    val characteristicUuid: UUID
) : BleOperationType()

/**
 * Operation to write a payload to a Bluetooth descriptor.
 *
 * @property device The Bluetooth device containing the descriptor.
 * @property descriptorUuid The UUID of the descriptor to write.
 * @property payload The byte array to be written as the descriptor's value.
 */
data class DescriptorWrite(
    override val device: BluetoothDevice,
    val descriptorUuid: UUID,
    val payload: ByteArray
) : BleOperationType() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DescriptorWrite

        if (device != other.device) return false
        if (descriptorUuid != other.descriptorUuid) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = device.hashCode()
        result = 31 * result + descriptorUuid.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Operation to read the value of a Bluetooth descriptor.
 *
 * @property device The Bluetooth device containing the descriptor.
 * @property descriptorUuid The UUID of the descriptor to be read.
 */
data class DescriptorRead(
    override val device: BluetoothDevice,
    val descriptorUuid: UUID
) : BleOperationType()

/**
 * Operation to enable notifications or indications on a Bluetooth characteristic.
 *
 * @property device The Bluetooth device containing the characteristic.
 * @property characteristicUuid The UUID of the characteristic for which notifications
 *                              or indications should be enabled.
 */
data class EnableNotifications(
    override val device: BluetoothDevice,
    val characteristicUuid: UUID
) : BleOperationType()

/**
 * Operation to disable notifications or indications on a Bluetooth characteristic.
 *
 * @property device The Bluetooth device containing the characteristic.
 * @property characteristicUuid The UUID of the characteristic for which notifications
 *                              or indications should be disabled.
 */
data class DisableNotifications(
    override val device: BluetoothDevice,
    val characteristicUuid: UUID
) : BleOperationType()

/**
 * Operation to request a change to the MTU (Maximum Transmission Unit) for the connection.
 *
 * @property device The Bluetooth device for which the MTU change is requested.
 * @property mtu The desired MTU value.
 */
data class MtuRequest(
    override val device: BluetoothDevice,
    val mtu: Int
) : BleOperationType()
