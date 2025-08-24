package com.example.smart_health_watch_android

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smart_health_watch_android.ui.theme.SmarthealthwatchandroidTheme
import java.util.*

class MainActivity : ComponentActivity() {
    companion object {
        // ESP32 use 16-bit UUID, Android convert to 128-bit format
        const val SERVICE_UUID = "0000180D-0000-1000-8000-00805F9B34FB"  // Heart Rate Service
        const val CHAR_UUID_HR = "00002A37-0000-1000-8000-00805F9B34FB"  // Heart Rate Measurement
        const val CHAR_UUID_TEMP = "00002A6E-0000-1000-8000-00805F9B34FB" // Temperature
        const val CHAR_UUID_SPO2 = "00002A5F-0000-1000-8000-00805F9B34FB" // SpO2

        // Client Characteristic Configuration Descriptor UUID
        const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // Store health data state
    private var currentHeartRate = 0
    private var currentTemperature = 0.0f
    private var currentSpO2 = 0

    // Permission launcher
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Permissions granted, can start BLE operations
            initBluetooth()
        } else {
            // Handle permission denial
            android.util.Log.w("Permissions", "Some Bluetooth permissions were denied")
        }
    }

    // Bluetooth enable launcher - THÊM MỚI
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            android.util.Log.d("Bluetooth", "Bluetooth enabled by user")
            initBluetooth()
        } else {
            android.util.Log.w("Bluetooth", "User declined to enable Bluetooth")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter

            // KIỂM TRA VÀ YÊU CẦU BẬT BLUETOOTH
            if (bluetoothAdapter == null) {
                android.util.Log.e("Bluetooth", "Device doesn't support Bluetooth")
                return
            }

            if (bluetoothAdapter?.isEnabled != true) {
                android.util.Log.w("Bluetooth", "Bluetooth is not enabled, requesting to enable")
                requestEnableBluetooth()
                return
            }

            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            android.util.Log.d("Bluetooth", "Bluetooth initialized successfully")
        } catch (exception: Exception) {
            android.util.Log.e("Bluetooth", "Failed to initialize Bluetooth: ${exception.message}")
        }
    }

    // HÀM MỚI: Yêu cầu bật Bluetooth
    private fun requestEnableBluetooth() {
        if (hasBluetoothConnectPermission()) {
            try {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
            } catch (securityException: SecurityException) {
                android.util.Log.e("Bluetooth", "Permission error requesting Bluetooth enable: ${securityException.message}")
            }
        }
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
        } else {
            // All permissions already granted
            initBluetooth()
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
            true // No permission needed on Android < 12
        }
    }

    @Composable
    fun SmartWatchApp() {
        var connectionStatus by remember { mutableStateOf("Disconnected") }
        var isScanning by remember { mutableStateOf(false) }
        var scannedDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
        var isConnected by remember { mutableStateOf(false) }

        // Separate state for each health metric
        var heartRate by remember { mutableStateOf(0) }
        var temperature by remember { mutableStateOf(0.0f) }
        var spO2 by remember { mutableStateOf(0) }

        // THÊM STATE ĐỂ THEO DÕI TRẠNG THÁI BLUETOOTH
        var bluetoothEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }
        var bluetoothSupported by remember { mutableStateOf(bluetoothAdapter != null) }

        // CẬP NHẬT TRẠNG THÁI BLUETOOTH MỖI KHI RENDER
        LaunchedEffect(Unit) {
            bluetoothEnabled = bluetoothAdapter?.isEnabled == true
            bluetoothSupported = bluetoothAdapter != null
        }

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

            // HIỂN THỊ TRẠNG THÁI BLUETOOTH
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (bluetoothEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Bluetooth Status:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = when {
                            !bluetoothSupported -> "Not Supported"
                            !bluetoothEnabled -> "Disabled - Please enable Bluetooth"
                            else -> "Enabled"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // NÚT BẬT BLUETOOTH (nếu cần)
            if (bluetoothSupported && !bluetoothEnabled) {
                Button(
                    onClick = {
                        requestEnableBluetooth()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Enable Bluetooth")
                }
            }

            // Scan Button - CẢI TIẾN ĐIỀU KIỆN ENABLE
            Button(
                onClick = {
                    if (!isScanning) {
                        startScan { devices, scanning, status ->
                            scannedDevices = devices
                            isScanning = scanning
                            connectionStatus = status
                        }
                    }
                },
                enabled = !isScanning && bluetoothEnabled && bluetoothSupported,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        !bluetoothSupported -> "Bluetooth Not Supported"
                        !bluetoothEnabled -> "Enable Bluetooth First"
                        isScanning -> "Scanning..."
                        else -> "Scan for ESP32"
                    }
                )
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
                                    connectToDevice(device) { connected, status, hr, temp, spo2 ->
                                        isConnected = connected
                                        connectionStatus = status
                                        heartRate = hr
                                        temperature = temp
                                        spO2 = spo2
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = getDeviceName(device),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Address: ${device.address}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                // Show device type if available
                                Text(
                                    text = "Type: ${getDeviceTypeString(device)}",
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
                        text = buildString {
                            appendLine("Heart Rate: ${if (heartRate > 0) "$heartRate BPM" else "--"}")
                            appendLine("SpO2: ${if (spO2 > 0) "$spO2%" else "--"}")
                            append("Temperature: ${if (temperature > 0) String.format(Locale.getDefault(), "%.1f°C", temperature) else "--"}")
                        },
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
                        heartRate = 0
                        temperature = 0.0f
                        spO2 = 0
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            }

            // NÚT REFRESH BLUETOOTH STATUS
            Button(
                onClick = {
                    bluetoothEnabled = bluetoothAdapter?.isEnabled == true
                    bluetoothSupported = bluetoothAdapter != null
                    initBluetooth()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Refresh Bluetooth Status")
            }
        }
    }

    // Helper function to get device type
    private fun getDeviceTypeString(device: BluetoothDevice): String {
        return try {
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual Mode"
                else -> "Unknown"
            }
        } catch (exception: Exception) {
            "Unknown"
        }
    }

    // Safe function to get device name with permission check
    private fun getDeviceName(device: BluetoothDevice): String {
        return if (hasBluetoothConnectPermission()) {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    device.name ?: "Unknown Device"
                } else {
                    "Unknown Device"
                }
            } catch (securityException: SecurityException) {
                "Unknown Device"
            }
        } else {
            "Unknown Device"
        }
    }

    private fun startScan(
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
                    val deviceName = getDeviceName(device)
                    val rssi = result.rssi

                    // RELAXED FILTERING - Show more devices for debugging
                    val shouldInclude = when {
                        // ESP32 devices by name
                        deviceName.contains("ESP32", true) -> true
                        deviceName.contains("ESP", true) -> true

                        // Devices with health service
                        result.scanRecord?.serviceUuids?.any {
                            it.toString().equals(SERVICE_UUID, true)
                        } == true -> true

                        // Any device with a name (not "Unknown Device") and good signal
                        deviceName != "Unknown Device" && rssi > -70 -> true

                        // Fallback: any device with decent signal strength
                        rssi > -60 -> true

                        else -> false
                    }

                    if (shouldInclude && !devices.contains(device)) {
                        devices.add(device)
                        android.util.Log.d("BLE_SCAN", "Found device: $deviceName, Address: ${device.address}, RSSI: $rssi")
                        callback(devices.toList(), true, "Found ${devices.size} device(s)")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val errorMessage = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                    else -> "Unknown error: $errorCode"
                }
                callback(devices.toList(), false, "Scan failed: $errorMessage")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        // SCAN FILTERS - Try both with and without filters
        val filters = listOf<ScanFilter>()  // Empty filter to catch all devices

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("BLE_SCAN", "Starting BLE scan...")
                bluetoothLeScanner?.startScan(filters, settings, scanCallback)
                callback(devices.toList(), true, "Scanning...")

                // Stop scan after 15 seconds (increased time)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                            bluetoothLeScanner?.stopScan(scanCallback)
                            android.util.Log.d("BLE_SCAN", "Scan stopped. Found ${devices.size} devices")
                        }
                    } catch (securityException: SecurityException) {
                        android.util.Log.e("Scan", "Permission error stopping scan: ${securityException.message}")
                    }
                    callback(
                        devices.toList(),
                        false,
                        if (devices.isEmpty()) "No devices found - Make sure ESP32 is powered on and nearby"
                        else "Scan complete - Found ${devices.size} device(s)"
                    )
                }, 15000) // Increased from 10 to 15 seconds
            } else {
                callback(emptyList(), false, "Permission denied")
            }
        } catch (securityException: SecurityException) {
            callback(emptyList(), false, "Permission denied")
        } catch (exception: Exception) {
            callback(emptyList(), false, "Scan error: ${exception.message}")
        }
    }

    private fun connectToDevice(
        device: BluetoothDevice,
        callback: (Boolean, String, Int, Float, Int) -> Unit
    ) {
        if (!hasBluetoothConnectPermission()) {
            callback(false, "Missing BLUETOOTH_CONNECT permission", 0, 0.0f, 0)
            return
        }

        callback(false, "Connecting...", 0, 0.0f, 0)

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (hasBluetoothConnectPermission()) {
                            try {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                    runOnUiThread {
                                        callback(true, "Connected - Discovering services...", 0, 0.0f, 0)
                                    }
                                    gatt?.discoverServices()
                                }
                            } catch (securityException: SecurityException) {
                                runOnUiThread {
                                    callback(false, "Permission error during service discovery", 0, 0.0f, 0)
                                }
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        runOnUiThread {
                            callback(false, "Disconnected", 0, 0.0f, 0)
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && hasBluetoothConnectPermission()) {
                    try {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            val service = gatt?.getService(UUID.fromString(SERVICE_UUID))
                            if (service != null) {
                                runOnUiThread {
                                    callback(true, "Connected - Setting up notifications...", 0, 0.0f, 0)
                                }

                                // Enable notifications for each characteristic
                                val hrCharacteristic = service.getCharacteristic(UUID.fromString(CHAR_UUID_HR))
                                val tempCharacteristic = service.getCharacteristic(UUID.fromString(CHAR_UUID_TEMP))
                                val spo2Characteristic = service.getCharacteristic(UUID.fromString(CHAR_UUID_SPO2))

                                hrCharacteristic?.let { enableNotification(gatt, it) }
                                tempCharacteristic?.let { enableNotification(gatt, it) }
                                spo2Characteristic?.let { enableNotification(gatt, it) }

                                runOnUiThread {
                                    callback(true, "Connected - Ready", currentHeartRate, currentTemperature, currentSpO2)
                                }
                            } else {
                                runOnUiThread {
                                    callback(false, "Health service not found", 0, 0.0f, 0)
                                }
                            }
                        }
                    } catch (securityException: SecurityException) {
                        runOnUiThread {
                            callback(false, "Permission error during characteristic setup", 0, 0.0f, 0)
                        }
                    } catch (exception: Exception) {
                        runOnUiThread {
                            callback(false, "Error setting up characteristics: ${exception.message}", 0, 0.0f, 0)
                        }
                    }
                } else {
                    runOnUiThread {
                        callback(false, "Service discovery failed", 0, 0.0f, 0)
                    }
                }
            }

            private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                try {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID))
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    }
                } catch (exception: Exception) {
                    android.util.Log.e("BLE", "Error enabling notification: ${exception.message}")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                characteristic?.let { char ->
                    val uuid = char.uuid.toString().uppercase(Locale.getDefault())
                    val data = char.value

                    when {
                        uuid.contains("2A37") -> { // Heart Rate
                            currentHeartRate = parseHeartRate(data)
                            runOnUiThread {
                                callback(true, "Connected - Ready", currentHeartRate, currentTemperature, currentSpO2)
                            }
                        }
                        uuid.contains("2A6E") -> { // Temperature
                            currentTemperature = parseTemperature(data)
                            runOnUiThread {
                                callback(true, "Connected - Ready", currentHeartRate, currentTemperature, currentSpO2)
                            }
                        }
                        uuid.contains("2A5F") -> { // SpO2
                            currentSpO2 = parseSpO2(data)
                            runOnUiThread {
                                callback(true, "Connected - Ready", currentHeartRate, currentTemperature, currentSpO2)
                            }
                        }
                    }
                }
            }

            private fun parseHeartRate(data: ByteArray): Int {
                return try {
                    if (data.isNotEmpty()) {
                        // Heart Rate format: first byte is flags, next bytes are HR value
                        if (data[0].toInt() and 0x01 == 0) {
                            data.getOrNull(1)?.toUByte()?.toInt() ?: 0 // 8-bit HR
                        } else {
                            // 16-bit HR
                            val byte1 = data.getOrNull(1)?.toUByte()?.toInt() ?: 0
                            val byte2 = data.getOrNull(2)?.toUByte()?.toInt() ?: 0
                            (byte2 shl 8) + byte1
                        }
                    } else 0
                } catch (exception: Exception) {
                    android.util.Log.e("Parse", "Error parsing heart rate: ${exception.message}")
                    0
                }
            }

            private fun parseTemperature(data: ByteArray): Float {
                return try {
                    if (data.size >= 4) {
                        // IEEE-11073 32-bit FLOAT format
                        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                    } else if (data.size >= 2) {
                        // Simple 16-bit temperature (divide by 100 for decimal places)
                        val temp = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).short
                        temp / 100.0f
                    } else 0.0f
                } catch (exception: Exception) {
                    android.util.Log.e("Parse", "Error parsing temperature: ${exception.message}")
                    0.0f
                }
            }

            private fun parseSpO2(data: ByteArray): Int {
                return try {
                    if (data.isNotEmpty()) {
                        data[0].toUByte().toInt()
                    } else 0
                } catch (exception: Exception) {
                    android.util.Log.e("Parse", "Error parsing SpO2: ${exception.message}")
                    0
                }
            }
        }

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt = device.connectGatt(this, false, gattCallback)
            } else {
                callback(false, "Permission denied for connection", 0, 0.0f, 0)
            }
        } catch (securityException: SecurityException) {
            callback(false, "Permission denied for connection", 0, 0.0f, 0)
        } catch (exception: Exception) {
            callback(false, "Connection error: ${exception.message}", 0, 0.0f, 0)
        }
    }

    private fun disconnect() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
        } catch (securityException: SecurityException) {
            android.util.Log.e("Disconnect", "Permission error: ${securityException.message}")
        } catch (exception: Exception) {
            android.util.Log.e("Disconnect", "Error: ${exception.message}")
        }
        bluetoothGatt = null
        // Reset health data
        currentHeartRate = 0
        currentTemperature = 0.0f
        currentSpO2 = 0
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