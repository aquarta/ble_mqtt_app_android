package it.unisalento.bleiot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier



import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.*

class MainActivity : ComponentActivity() {

    private val TAG = "BleGattNotificationApp"
    private val SCAN_PERIOD: Long = 10000 // Scan for 10 seconds

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var gattClient: BluetoothGatt? = null


    private val TARGET_DEVICE_NAME = "BlueNRGLP"
    // Example UUIDs - replace with your device's actual UUIDs
    //private val SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb") // Heart Rate Service
    private val SERVICE_UUID = UUID.fromString("00000000-0001-11e1-9ab4-0002a5d5c51b") // MSSensorDemo Service

    private val CHARACTERISTIC_UUID = UUID.fromString("00140000-0001-11e1-ac36-0002a5d5c51b") //  MSSensorDemo Characteristic

    // State for UI
    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    // Request for Bluetooth permissions
    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            // All permissions granted
            startScan()
        } else {
            updateStatus("Permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Check if Bluetooth is supported
        if (bluetoothAdapter == null) {
            updateStatus("Bluetooth not supported")
            finish()
            return
        }

        setContent {
            BleNotificationApp(
                uiState = uiState,
                onScanButtonClick = {
                    if (!scanning) {
                        checkPermissionsAndStartScan()
                    } else {
                        stopScan()
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()

        // Check if Bluetooth is enabled
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } else {
                checkPermissionsAndStartScan()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        gattClient?.close()
    }

    private fun checkPermissionsAndStartScan() {
        val permissionsToRequest = mutableListOf<String>()

        // For Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // For older Android versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Request permissions if needed
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
        } else {
            startScan()
        }
    }

    private fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            updateStatus("Bluetooth is disabled")
            return
        }

        // Check for permissions
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                updateStatus("Bluetooth scan permission denied")
                return
            }
        }

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            updateStatus("Cannot access Bluetooth scanner")
            return
        }

        // Stop scanning after a pre-defined period
        handler.postDelayed({
            if (scanning) {
                scanning = false
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothLeScanner?.stopScan(scanCallback)
                    updateScanButtonText("Start Scan")
                    updateStatus("Scan stopped")
                }
            }
        }, SCAN_PERIOD)

        scanning = true
        bluetoothLeScanner?.startScan(scanCallback)
        updateScanButtonText("Stop Scan")
        updateStatus("Scanning...")
    }

    private fun stopScan() {
        if (scanning && bluetoothLeScanner != null) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                scanning = false
                bluetoothLeScanner?.stopScan(scanCallback)
                updateScanButtonText("Start Scan")
                updateStatus("Scan stopped")
            }
        }
    }

    // Device scan callback
    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            // Check for permissions
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return
                }
            }

            val device = result.device
            val deviceName = device.name ?: "Unknown Device"

            Log.i(TAG, "Found device: $deviceName")

            // Connect to the first device found (for demonstration)
            // In a real app, you might want to show a list of devices

            if (deviceName == TARGET_DEVICE_NAME) {
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            updateStatus("Scan failed: $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        updateStatus("Connecting to ${device.name ?: "Unknown Device"}...")

        // Connect to GATT server
        gattClient = device.connectGatt(this, false, gattCallback)
    }

    // GATT callback
    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server.")
                    updateStatus("Connected to ${gatt.device.name ?: "Unknown Device"}")

                    // Check for permissions
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }

                    // Discover services
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT server.")
                    updateStatus("Disconnected")
                    updateData("")

                    gatt.close()
                }
            } else {
                Log.w(TAG, "Error $status encountered for ${gatt.device.address}! Disconnecting...")
                updateStatus("Connection error: $status")

                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")

                // Find our service and characteristic
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        // Check for permissions
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return
                        }

                        // Enable notifications
                        gatt.setCharacteristicNotification(characteristic, true)


//                        // For some characteristics, we need to enable the Client Characteristic Configuration Descriptor (CCCD)
                        val desc_uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        val descriptor = characteristic.getDescriptor(desc_uuid)
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            gatt.writeDescriptor(descriptor)
                            updateStatus("Notifications enabled")
                        }
                    } else {
                        Log.w(TAG, "Characteristic not found")
                        updateStatus("Characteristic not found")
                    }
                } else {
                    Log.w(TAG, "Service not found")
                    updateStatus("Service not found")
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
                updateStatus("Service discovery failed: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.w(TAG, "onServicesDiscovered received: ${characteristic.uuid.toString()} - $value")
            // This is called when notifications are received
            if (characteristic.uuid == CHARACTERISTIC_UUID) {

                // Parse the data based on your specific device's format
                val data = parseHeartRateData(value) // Example parser
                updateData("Heart Rate: $data bpm")
            }
        }

        // For Android versions below 13, this method is used
        @Deprecated("Deprecated in API level 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                super.onCharacteristicChanged(gatt, characteristic)
            } else {
                // For older Android versions
                val value = characteristic.value
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    val data = parseHeartRateData(value)
                    updateData("Heart Rate: $data bpm")
                }
            }
        }
    }

    // Example parser for Heart Rate data (adjust according to your device)
    private fun parseHeartRateData(data: ByteArray): Int {
        // Check if the Heart Rate value format is uint8 or uint16
        return if (data[0].toInt() and 0x01 == 0) {
            // Heart Rate is in the second byte
            data[1].toInt() and 0xFF
        } else {
            // Heart Rate is in the second and third bytes
            (data[1].toInt() and 0xFF) + ((data[2].toInt() and 0xFF) shl 8)
        }
    }

    // Update UI state helpers
    private fun updateStatus(status: String) {
        _uiState.update { currentState ->
            currentState.copy(statusText = status)
        }
    }

    private fun updateData(data: String) {
        _uiState.update { currentState ->
            currentState.copy(dataText = data)
        }
    }

    private fun updateScanButtonText(text: String) {
        _uiState.update { currentState ->
            currentState.copy(scanButtonText = text)
        }
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
    }
}

// Data class to hold UI state
data class BleUiState(
    val statusText: String = "Not scanning",
    val dataText: String = "No data received",
    val scanButtonText: String = "Start Scan"
)

// Composable function for the UI
@Composable
fun BleNotificationApp(
    uiState: StateFlow<BleUiState>,
    onScanButtonClick: () -> Unit
) {
    val state by uiState.collectAsState()

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "BLE GATT Notification Example",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 32.dp)
                )

                Button(
                    onClick = onScanButtonClick,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    Text(text = state.scanButtonText)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status:",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(text = state.statusText)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Data:",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(text = state.dataText)
                }
            }
        }
    }
}