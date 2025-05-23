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

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MainActivity : ComponentActivity() {

    private val TAG = "BleGattNotificationApp"
    private val SCAN_PERIOD: Long = 10000 // Scan for 10 seconds

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var gattClient: BluetoothGatt? = null


    var target_device_name = "" // Initialize as empty
    // Example UUIDs - replace with your device's actual UUIDs
    private val SERVICE_UUID = UUID.fromString("00000000-0001-11e1-9ab4-0002a5d5c51b") // MSSensorDemo Service

    private val BLUENRG_MS_ENV_CHARACTERISTIC_UUID = UUID.fromString("00140000-0001-11e1-ac36-0002a5d5c51b") //  MSSensorDemo Characteristic
    private val BLUENRG_MS_ACC_CHARACTERISTIC_UUID = UUID.fromString("00c00000-0001-11e1-ac36-0002a5d5c51b")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val BLUENRG_MS_SERVICE_UUID = UUID.fromString("00000000-0001-11e1-9ab4-0002a5d5c51b") // MSSensorDemo Service
    private val HEAR_RATE_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB") // Heart Rate Service
    private val SERVICE_UUIDS = listOf(
        HEAR_RATE_SERVICE_UUID,
        BLUENRG_MS_SERVICE_UUID,  // MSSensorDemo Service
    )

    private val HEART_RATE_CHARACTERISTIC_UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
    private val BATTERY_CHARACTERISTIC_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")

    private val CHAR_UUIDS = listOf(
        HEART_RATE_CHARACTERISTIC_UUID,
        //BLUENRG_MS_ENV_CHARACTERISTIC_UUID,
        BLUENRG_MS_ACC_CHARACTERISTIC_UUID,
        BATTERY_CHARACTERISTIC_UUID,
    )


    // MQTT Client properties
    private var mqttClient: MqttClient? = null
    private val MQTT_SERVER_URI = "tcp://localhost:1883"
    private val MQTT_CLIENT_ID = "AndroidBleClient"
    private val MQTT_TOPIC = "ble/temperature"
    private val MQTT_USERNAME = "your_username" // Optional
    private val MQTT_PASSWORD = "your_password" // Optional

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
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            //permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
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
                ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
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

            Log.i(TAG, "Found device: $deviceName ${device.address} ")

            // Connect to the first device found (for demonstration)
            // In a real app, you might want to show a list of devices

            if (deviceName == target_device_name) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return
            }
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            return
                        }
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



                for (service_uuid in SERVICE_UUIDS) {
                    val service = gatt.getService(service_uuid)
                    if (service != null) {
                        for (char_uuid in CHAR_UUIDS) {
                            val characteristic =
                                service.getCharacteristic(char_uuid)
                            if (characteristic != null) {
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

                                // Enable notifications
                                gatt.setCharacteristicNotification(characteristic, true)

                                // For some characteristics, we need to enable the Client Characteristic Configuration Descriptor (CCCD)
                                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                                if (descriptor != null) {
                                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                    gatt.writeDescriptor(descriptor)
                                    updateStatus("Notifications enabled")
                                }
                            } else {
                                Log.w(TAG, "Characteristic not found")
                                updateStatus("Characteristic not found")
                            }
                        }
                    } else {
                        Log.w(TAG, "Service not found ${service_uuid.toString()}")
                        updateStatus("Service not found")
                    }
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
            Log.w(TAG, "onServicesDiscovered received: ${characteristic.uuid.toString()} - $value - ${gatt.device.name} ${gatt.device.address}")
            // This is called when notifications are received
            if (characteristic.uuid == BLUENRG_MS_ENV_CHARACTERISTIC_UUID) {

                // Parse the data based on your specific device's format
                val data = parseTemperatureDeta(value) // Example parser
                updateData("Temperature : $data C")
            }
            else if (characteristic.uuid == HEART_RATE_CHARACTERISTIC_UUID) {
                // Parse the data based on your specific device's format

                updateData("Heart Rate : ${value.toString()} bpm")
            }
            else if (characteristic.uuid == BLUENRG_MS_ACC_CHARACTERISTIC_UUID) {
                parseMSAccDeta(value)
                updateData("Acc measure : ${value.toString()}")
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
                if (characteristic.uuid == BLUENRG_MS_ENV_CHARACTERISTIC_UUID) {
                    val data = parseTemperatureDeta(value)
                    updateData("Temperature: $data bpm")
                }
            }
        }
    }

    // Example parser for Temperature data (adjust according to your device)
    private fun parseTemperatureDeta(data: ByteArray): Double {

        val tempValue = data.sliceArray(6 until data.size).foldIndexed(0) { index, acc, byte ->
            acc or ((byte.toInt() and 0xFF) shl (8 * index))
        }

        val temperature = (tempValue/10).toDouble()
        // Publish temperature to MQTT
        publishToMqtt(MQTT_TOPIC, temperature.toString())

        return temperature
    }


    private fun parseMSAccDeta(data: ByteArray): Int {

        val accValue = data.sliceArray(6 until data.size).foldIndexed(0) { index, acc, byte ->
            acc or ((byte.toInt() and 0xFF) shl (8 * index))
        }

        // Publish temperature to MQTT
        publishToMqtt(MQTT_TOPIC, accValue.toString())

        return accValue
    }

    private fun setupMqttClient() {
        try {
            // Create a new MqttClient instance
            mqttClient = MqttClient(
                MQTT_SERVER_URI,
                MQTT_CLIENT_ID,
                MemoryPersistence()
            )

            // Set up connection options
            val options = MqttConnectOptions()
            // Optional username and password
//            if (MQTT_USERNAME.isNotEmpty() && MQTT_PASSWORD.isNotEmpty()) {
//                options.userName = MQTT_USERNAME
//                options.password = MQTT_PASSWORD.toCharArray()
//            }
            options.isAutomaticReconnect = true
            options.isCleanSession = true

            // Connect to the broker
            mqttClient?.connect(options)
            updateStatus("Connected to MQTT broker")

        } catch (e: MqttException) {
            Log.e(TAG, "Error setting up MQTT client: ${e.message}")
            updateStatus("MQTT connection failed: ${e.message}")
        }
    }


    private fun publishToMqtt(topic: String, message: String) {
        try {
            if (mqttClient?.isConnected == true) {
                val mqttMessage = MqttMessage(message.toByteArray())
                mqttMessage.qos = 1
                mqttClient?.publish(topic, mqttMessage)
                Log.i(TAG, "Published to MQTT: $message")
            } else {
                Log.w(TAG, "MQTT client not connected, attempting to reconnect")
                setupMqttClient()
            }
        } catch (e: MqttException) {
            Log.e(TAG, "Error publishing to MQTT: ${e.message}")
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
    var deviceName by remember { mutableStateOf("") }
    val state by uiState.collectAsState()
    val context = LocalContext.current
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

                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("Device Name") },
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                SideEffect {

                    if (context is MainActivity) { context.target_device_name = deviceName
                    }
                }


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