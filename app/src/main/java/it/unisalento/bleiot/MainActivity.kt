package it.unisalento.bleiot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import it.unisalento.bleiot.ui.theme.BleIotTheme

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.platform.LocalContext


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BleIotTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}



@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BleIotTheme {
        Greeting("Android")
    }
    val context = LocalContext.current

    val bluetoothManager: BluetoothManager by lazy {

        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            //Log.d(TAG, "Found device: ${device.address}, Name: ${device.name ?: "N/A"}")
            Log.d(TAG, "Found device: ${device.address}")

            //  STOP SCANNING ONCE DEVICE IS FOUND (Optional but recommended)
            //stopBleScan()

            // Initiate connection here (see next step)
            //connectToDevice(device)
        }

        // ... other methods ...
    }

//     var bluetoothGatt: BluetoothGatt? = null
//
//     fun connectToDevice(device: BluetoothDevice) {
//        bluetoothGatt = device.connectGatt(this, false, gattCallback)
//        Log.d(TAG, "Connecting to ${device.address}")
//    }
}