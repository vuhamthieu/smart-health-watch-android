package com.example.smart_health_watch_android
import androidx.compose.ui.tooling.preview.Preview

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smart_health_watch_android.ui.theme.SmarthealthwatchandroidTheme
import java.util.*

class MainActivity : ComponentActivity() {
    companion object {
        // ESP32 use 16-bit UUID, Android  convert to 128-bit format
        const val SERVICE_UUID = "0000180D-0000-1000-8000-00805F9B34FB"  // Heart Rate Service
        const val CHAR_UUID_HR = "00002A37-0000-1000-8000-00805F9B34FB"  // Heart Rate Measurement
        const val CHAR_UUID_TEMP = "00002A6E-0000-1000-8000-00805F9B34FB" // Temperature
        const val CHAR_UUID_SPO2 = "00002A5F-0000-1000-8000-00805F9B34FB" // SpO2
        const val CHAR_UUID_GPS = "00002A67-0000-1000-8000-00805F9B34FB"  // GPS
        const val CHAR_UUID_CMD = "00002A56-0000-1000-8000-00805F9B34FB"  // Commands
        const val CHAR_UUID_NOTIFY = "00002A18-0000-1000-8000-00805F9B34FB" // Notifications
    }


    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // Permission launcher
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Permissions granted, can start BLE operations
        } else {
            // Handle permission denial
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initBluetooth()
        requestBluetoothPermissions()

        setContent {
            SmarthealthwatchandroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SmartWatchApp()
                }
            }
        }
    }

    private fun initBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions.launch(missingPermissions.toTypedArray())
        }
    }

    // Permission checking functions
    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Không cần permission trên Android < 12
        }
    }

    @Composable
    fun SmartWatchApp() {
        var connectionStatus by remember { mutableStateOf("Disconnected") }
        var isScanning by remember { mutableStateOf(false) }
        var scannedDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
        var healthData by remember { mutableStateOf("Heart Rate: --\nSpO2: --\nTemperature: --") }
        var isConnected by remember { mutableStateOf(false) }

        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                text = "Smart Health Watch",
                style = MaterialTheme.typography.headlineSmall
            )

            // Scan Button
            Button(
                onClick = {
                    if (!isScanning) {
                        startScan(context) { devices, scanning, status ->
                            scannedDevices = devices
                            isScanning = scanning
                            connectionStatus = status
                        }
                    }
                },
                enabled = !isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isScanning) "Scanning..." else "Scan for ESP32")
            }

            // Connection Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Status: $connectionStatus",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // Device List
            if (scannedDevices.isNotEmpty()) {
                Text("Found Devices:", style = MaterialTheme.typography.titleMedium)

                LazyColumn(
                    modifier = Modifier.height(200.dp)
                ) {
                    items(scannedDevices) { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    connectToDevice(device) { connected, status, data ->
                                        isConnected = connected
                                        connectionStatus = status
                                        if (data.isNotEmpty()) {
                                            healthData = data
                                        }
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = getDeviceName(device),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Health Data Display
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Health Data:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = healthData,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Disconnect Button
            if (isConnected) {
                Button(
                    onClick = {
                        disconnect()
                        isConnected = false
                        connectionStatus = "Disconnected"
                        healthData = "Heart Rate: --\nSpO2: --\nTemperature: --"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            }
        }
    }

    // Safe function to get device name with permission check
    private fun getDeviceName(device: BluetoothDevice): String {
        return if (hasBluetoothConnectPermission()) {
            try {
                device.name ?: "Unknown Device"
            } catch (e: SecurityException) {
                "Unknown Device"
            }
        } else {
            "Unknown Device"
        }
    }

    private fun startScan(
        context: Context,
        callback: (List<BluetoothDevice>, Boolean, String) -> Unit
    ) {
        if (!hasBluetoothScanPermission()) {
            callback(emptyList(), false, "Missing BLUETOOTH_SCAN permission")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            callback(emptyList(), false, "Bluetooth not enabled")
            return
        }

        val devices = mutableListOf<BluetoothDevice>()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    if (getDeviceName(device) != "Unknown Device" && !devices.contains(device)) {
                        devices.add(device)
                        callback(devices.toList(), true, "Scanning...")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                callback(devices.toList(), false, "Scan failed: $errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(SERVICE_UUID))
                .build()
        )

        try {
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            callback(devices.toList(), true, "Scanning...")

            // Stop scan after 10 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (hasBluetoothScanPermission()) {
                        bluetoothLeScanner?.stopScan(scanCallback)
                    }
                } catch (e: SecurityException) {
                    // Handle silently
                }
                callback(
                    devices.toList(),
                    false,
                    if (devices.isEmpty()) "No devices found" else "Scan complete"
                )
            }, 10000)

        } catch (e: SecurityException) {
            callback(emptyList(), false, "Permission denied")
        }
    }

    private fun connectToDevice(
        device: BluetoothDevice,
        callback: (Boolean, String, String) -> Unit
    ) {
        if (!hasBluetoothConnectPermission()) {
            callback(false, "Missing BLUETOOTH_CONNECT permission", "")
            return
        }

        callback(false, "Connecting...", "")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        callback(true, "Connected", "")
                        if (hasBluetoothConnectPermission()) {
                            try {
                                gatt?.discoverServices()
                            } catch (e: SecurityException) {
                                callback(false, "Permission error during service discovery", "")
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        callback(false, "Disconnected", "")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && hasBluetoothConnectPermission()) {
                    try {
                        val service = gatt?.getService(UUID.fromString(SERVICE_UUID))
                        service?.let {
                            // Enable notifications cho Heart Rate
                            val hrCharacteristic = it.getCharacteristic(UUID.fromString(CHAR_UUID_HR))
                            enableNotification(gatt, hrCharacteristic)

                            // Enable notifications cho Temperature
                            val tempCharacteristic = it.getCharacteristic(UUID.fromString(CHAR_UUID_TEMP))
                            enableNotification(gatt, tempCharacteristic)

                            // Enable notifications cho SpO2
                            val spo2Characteristic = it.getCharacteristic(UUID.fromString(CHAR_UUID_SPO2))
                            enableNotification(gatt, spo2Characteristic)
                        }
                    } catch (e: SecurityException) {
                        callback(false, "Permission error during characteristic setup", "")
                    }
                }
            }

            private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
                characteristic?.let {
                    gatt.setCharacteristicNotification(it, true)
                    val descriptor = it.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    descriptor?.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    gatt.writeDescriptor(descriptor)
                }
            }


            override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                characteristic?.let { char ->
                    val uuid = char.uuid.toString().uppercase()
                    val data = char.value

                    when {
                        uuid.contains("2A37") -> { // Heart Rate
                            val heartRate = parseHeartRate(data)
                            runOnUiThread {
                                callback(true, "Connected", "Heart Rate: $heartRate BPM")
                            }
                        }
                        uuid.contains("2A6E") -> { // Temperature
                            val temp = parseTemperature(data)
                            runOnUiThread {
                                callback(true, "Connected", "Temperature: ${temp}°C")
                            }
                        }
                        uuid.contains("2A5F") -> { // SpO2
                            val spo2 = parseSpO2(data)
                            runOnUiThread {
                                callback(true, "Connected", "SpO2: $spo2%")
                            }
                        }
                    }
                }
            }

            private fun parseHeartRate(data: ByteArray): Int {
                // Heart Rate format: first byte is flags, next bytes are HR value
                return if (data.size >= 2) {
                    if (data[0].toInt() and 0x01 == 0) {
                        data[1].toUByte().toInt() // 8-bit HR
                    } else {
                        ((data[2].toUByte().toInt() shl 8) + data[1].toUByte().toInt()) // 16-bit HR
                    }
                } else 0
            }

            private fun parseTemperature(data: ByteArray): Float {
                // Temperature format depends on your ESP32 implementation
                // Example: IEEE-11073 32-bit FLOAT
                return if (data.size >= 4) {
                    java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                } else 0.0f
            }

            private fun parseSpO2(data: ByteArray): Int {
                // SpO2 format depends on your implementation
                return if (data.size >= 1) {
                    data[0].toUByte().toInt()
                } else 0
            }

        }

        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            callback(false, "Permission denied for connection", "")
        }
    }

    private fun disconnect() {
        if (hasBluetoothConnectPermission()) {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                // Handle silently
            }
        }
        bluetoothGatt = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    @Preview(showBackground = true)
    @Composable
    fun SmartWatchAppPreview() {
        SmarthealthwatchandroidTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                SmartWatchApp()
            }
        }
    }

}



