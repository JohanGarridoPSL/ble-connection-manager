/*
 * Copyright 2024 Punch Through Design LLC.
 *
 * Modifications Copyright 2024 Pretty Smart Labs.
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
 * Modifications made on February 5, 2025 by Pretty Smart Labs:
 * - Documentation and comments added and improved.
 */

package com.prettysmartlabs.ble_connection_manager.connection_manager

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import android.util.Log
import java.util.Locale
import java.util.UUID

private const val TAG = "BleExtensions"

/**
 * UUID of the Client Characteristic Configuration Descriptor (0x2902).
 */
const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"

/**
 * Extension function for [BluetoothGatt] that logs a table of its services along with their
 * characteristics and descriptors.
 *
 * If no services are found, it logs a message suggesting that [BluetoothGatt.discoverServices]
 * should be called first.
 */
fun BluetoothGatt.printGattTable() {
    if (services.isEmpty()) {
        Log.d(TAG, "No service and characteristic available, call discoverServices() first?")
        return
    }
    services.forEach { service ->
        val characteristicsTable = service.characteristics.joinToString(
            separator = "\n|--",
            prefix = "|--"
        ) { characteristic ->
            var description = "${characteristic.uuid}: ${characteristic.printProperties()}"
            if (characteristic.descriptors.isNotEmpty()) {
                description += "\n" + characteristic.descriptors.joinToString(
                    separator = "\n|------",
                    prefix = "|------"
                ) { descriptor ->
                    "${descriptor.uuid}: ${descriptor.printProperties()}"
                }
            }
            description
        }
        Log.d(TAG, "Service ${service.uuid}\nCharacteristics:\n$characteristicsTable")
    }
}

/**
 * Searches for a [BluetoothGattCharacteristic] with the specified [characteristicUuid].
 *
 * Optionally, if a [serviceUuid] is provided, the search will be narrowed to that service.
 *
 * @param characteristicUuid The UUID of the characteristic to search for.
 * @param serviceUuid Optional UUID of the service to search within.
 * @return The matching [BluetoothGattCharacteristic], or null if not found.
 */
fun BluetoothGatt.findCharacteristic(
    characteristicUuid: UUID,
    serviceUuid: UUID? = null
): BluetoothGattCharacteristic? {
    return if (serviceUuid != null) {
        // Use service UUID to disambiguate in case multiple services contain a characteristic with the same UUID.
        services.firstOrNull { it.uuid == serviceUuid }
            ?.characteristics?.firstOrNull { it.uuid == characteristicUuid }
    } else {
        // Iterate through services and return the first matching characteristic.
        services.forEach { service ->
            service.characteristics.firstOrNull { it.uuid == characteristicUuid }?.let { matchingCharacteristic ->
                return matchingCharacteristic
            }
        }
        null
    }
}

/**
 * Searches for a [BluetoothGattDescriptor] with the specified [descriptorUuid].
 *
 * Optionally, if [characteristicUuid] and [serviceUuid] are provided, the search will be narrowed to
 * the descriptor that belongs to the specified characteristic and service.
 *
 * @param descriptorUuid The UUID of the descriptor to search for.
 * @param characteristicUuid Optional UUID of the characteristic to search within.
 * @param serviceUuid Optional UUID of the service to search within.
 * @return The matching [BluetoothGattDescriptor], or null if not found.
 */
fun BluetoothGatt.findDescriptor(
    descriptorUuid: UUID,
    characteristicUuid: UUID? = null,
    serviceUuid: UUID? = null
): BluetoothGattDescriptor? {
    return if (characteristicUuid != null && serviceUuid != null) {
        // Use both characteristic and service UUIDs to narrow down the search.
        services.firstOrNull { it.uuid == serviceUuid }
            ?.characteristics?.firstOrNull { it.uuid == characteristicUuid }
            ?.descriptors?.firstOrNull { it.uuid == descriptorUuid }
    } else {
        // Iterate through all services and characteristics until a matching descriptor is found.
        services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                characteristic.descriptors.firstOrNull { descriptor ->
                    descriptor.uuid == descriptorUuid
                }?.let { matchingDescriptor ->
                    return matchingDescriptor
                }
            }
        }
        null
    }
}

/**
 * Extension function for [BluetoothGattCharacteristic] that returns a human-readable string
 * representing its properties (e.g., READABLE, WRITABLE).
 *
 * @return A comma-separated string of properties.
 */
fun BluetoothGattCharacteristic.printProperties(): String = mutableListOf<String>().apply {
    if (isReadable()) add("READABLE")
    if (isWritable()) add("WRITABLE")
    if (isWritableWithoutResponse()) add("WRITABLE WITHOUT RESPONSE")
    if (isIndicatable()) add("INDICATABLE")
    if (isNotifiable()) add("NOTIFIABLE")
    if (isEmpty()) add("EMPTY")
}.joinToString()

/**
 * Checks if the characteristic is readable.
 *
 * @return True if the characteristic has the PROPERTY_READ flag.
 */
fun BluetoothGattCharacteristic.isReadable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

/**
 * Checks if the characteristic is writable.
 *
 * @return True if the characteristic has the PROPERTY_WRITE flag.
 */
fun BluetoothGattCharacteristic.isWritable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

/**
 * Checks if the characteristic supports writing without a response.
 *
 * @return True if the characteristic has the PROPERTY_WRITE_NO_RESPONSE flag.
 */
fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

/**
 * Checks if the characteristic supports indications.
 *
 * @return True if the characteristic has the PROPERTY_INDICATE flag.
 */
fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

/**
 * Checks if the characteristic supports notifications.
 *
 * @return True if the characteristic has the PROPERTY_NOTIFY flag.
 */
fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

/**
 * Determines whether this characteristic contains the specified property.
 *
 * @param property The property to check.
 * @return True if the property is set.
 */
fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean =
    properties and property != 0

/**
 * Executes a write operation on this characteristic.
 *
 * For devices running Android Tiramisu (API 33) or above, it uses the newer [BluetoothGatt.writeCharacteristic] method.
 * For lower API levels, it falls back to the legacy write method.
 *
 * @param gatt The [BluetoothGatt] instance to perform the write.
 * @param payload The data to write.
 * @param writeType The write type to use.
 */
@SuppressLint("MissingPermission")
fun BluetoothGattCharacteristic.executeWrite(
    gatt: BluetoothGatt,
    payload: ByteArray,
    writeType: Int
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(this, payload, writeType)
    } else {
        // Fallback for Android versions below 13
        legacyCharacteristicWrite(gatt, payload, writeType)
    }
}

/**
 * Legacy implementation of characteristic write for Android versions below Tiramisu (API 33).
 *
 * Sets the write type and value before writing the characteristic.
 *
 * @param gatt The [BluetoothGatt] instance.
 * @param payload The data to write.
 * @param writeType The write type to set.
 */
@TargetApi(Build.VERSION_CODES.S)
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun BluetoothGattCharacteristic.legacyCharacteristicWrite(
    gatt: BluetoothGatt,
    payload: ByteArray,
    writeType: Int
) {
    this.writeType = writeType
    value = payload
    gatt.writeCharacteristic(this)
}

/**
 * Extension function for [BluetoothGattDescriptor] that returns a human-readable string
 * representing its permissions (e.g., READABLE, WRITABLE).
 *
 * @return A comma-separated string of permissions.
 */
fun BluetoothGattDescriptor.printProperties(): String = mutableListOf<String>().apply {
    if (isReadable()) add("READABLE")
    if (isWritable()) add("WRITABLE")
    if (isEmpty()) add("EMPTY")
}.joinToString()

/**
 * Checks if the descriptor is readable.
 *
 * @return True if the descriptor has the PERMISSION_READ flag.
 */
fun BluetoothGattDescriptor.isReadable(): Boolean =
    containsPermission(BluetoothGattDescriptor.PERMISSION_READ)

/**
 * Checks if the descriptor is writable.
 *
 * @return True if the descriptor has the PERMISSION_WRITE flag.
 */
fun BluetoothGattDescriptor.isWritable(): Boolean =
    containsPermission(BluetoothGattDescriptor.PERMISSION_WRITE)

/**
 * Determines whether this descriptor contains the specified permission.
 *
 * @param permission The permission flag to check.
 * @return True if the permission is set.
 */
fun BluetoothGattDescriptor.containsPermission(permission: Int): Boolean =
    permissions and permission != 0

/**
 * Executes a write operation on this descriptor.
 *
 * For devices running Android Tiramisu (API 33) or above, it uses the newer [BluetoothGatt.writeDescriptor] method.
 * For lower API levels, it falls back to the legacy write method.
 *
 * @param gatt The [BluetoothGatt] instance to perform the write.
 * @param payload The data to write.
 */
@SuppressLint("MissingPermission")
fun BluetoothGattDescriptor.executeWrite(
    gatt: BluetoothGatt,
    payload: ByteArray
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(this, payload)
    } else {
        // Fallback for Android versions below 13
        legacyDescriptorWrite(gatt, payload)
    }
}

/**
 * Legacy implementation of descriptor write for Android versions below Tiramisu (API 33).
 *
 * Sets the value before writing the descriptor.
 *
 * @param gatt The [BluetoothGatt] instance.
 * @param payload The data to write.
 */
@TargetApi(Build.VERSION_CODES.S)
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun BluetoothGattDescriptor.legacyDescriptorWrite(
    gatt: BluetoothGatt,
    payload: ByteArray
) {
    value = payload
    gatt.writeDescriptor(this)
}

/**
 * Convenience extension function that determines whether this descriptor is the
 * Client Characteristic Configuration Descriptor (CCCD).
 *
 * @return True if the descriptor's UUID matches that of the CCCD.
 */
fun BluetoothGattDescriptor.isCccd() =
    uuid.toString().uppercase(Locale.US) == CCC_DESCRIPTOR_UUID.uppercase(Locale.US)

/**
 * Converts a [ByteArray] to a hex string representation.
 *
 * @return A string in the format "0xXX XX ..." where XX is the hexadecimal value of each byte.
 */
fun ByteArray.toHexString(): String =
    joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }
