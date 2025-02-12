Credits and Attribution
This library incorporates code originally developed by Punch Through Design LLC, which is licensed under the Apache License, Version 2.0.

Modifications to the original code have been made by Pretty Smart Labs. These modifications include (but are not limited to):

ConnectionEventListener.kt:
- onReadRemoteRssi callback added
- Documentation and comments added and improved.
- alreadyConnected callback added.
- onConnectToDeviceEnqueued callback added.
- onConnectingToGatt callback added.
- onGattConnected callback added.
- onGattDiscoveringServices callback added.
- onConnectFailure callback added.
- onfailedToDisconnect callback added.
- onDisconnectDueToFailure callback added.
- onGattDisconnected callback added.
- onBondStateChanged callback added.

ConnectionManager.kt:
- Read remote RSSI operation has been added.
- Find characteristic by uuid function has been added.
- Documentation and comments added and improved.
- Connect function modified, if a device is already connected, an error is logged and the
    alreadyConnected callback is invoked. onConnectToDeviceEnqueued callback is invoked instead.
- onConnectingToGatt callback is invoked in function connect now.
- GATT callback modified, onGattConnected callback is invoked when the device is connected, also
onGattDiscoveringServices callback is invoked when a discovery of services in a gatt is called.
- GATT callback modified when the gatt fails, the error is analyzed using analyzeConnectionError
    and send to teardownConnection function.
- onDisconnectDueToFailure callback is invoked when a device is disconnected due to a failure in the connection.
- onFailedToDisconnect callback is invoked when a disconnection to a device fails.
- onGattDisconnected callback is invoked when a gatt disconnects.
- analyzeConnectionError function and the sealed class DisconnectionError have been added to handle connection errors
- teardownConnection function has been added to handle connection errors.
- onBondStateChanged callback is invoked when the bond state of a device changes.

BleExtensions.kt:
- Documentation and comments added and improved.

BleOperationType.kt:
- ReadRemoteRssi operation has been added.
- Documentation and comments added and improved.

All modified files include detailed headers specifying the original copyright and the modifications made.

For full license details, please see the LICENSE file included in this repository.

