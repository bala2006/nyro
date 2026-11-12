package com.nyro.companion.device

import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reads hardware and system state through official Android APIs only. Values that the
 * platform does not expose are reported as unavailable instead of being guessed.
 */
class DeviceInfo(private val context: Context) {

    fun device(): JsonObject = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("android_version", Build.VERSION.RELEASE)
        put("api_level", Build.VERSION.SDK_INT)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("build", Build.DISPLAY)
        put("cpu", cpu())
        put("memory", memory())
        put("storage", storage())
        val dm = context.resources.displayMetrics
        put("display", buildJsonObject {
            put("width_px", dm.widthPixels); put("height_px", dm.heightPixels); put("density_dpi", dm.densityDpi)
            context.display?.let { put("refresh_rate_hz", it.refreshRate.toInt()) }
        })
        put("uptime_hours", "%.1f".format(Locale.US, SystemClock.elapsedRealtime() / 3_600_000.0))
    }

    fun cpu(): JsonObject = buildJsonObject {
        put("cores", Runtime.getRuntime().availableProcessors())
        put("abis", Build.SUPPORTED_ABIS.joinToString())
        if (Build.VERSION.SDK_INT >= 31) {
            put("soc_manufacturer", Build.SOC_MANUFACTURER)
            put("soc_model", Build.SOC_MODEL)
        }
        put("hardware", Build.HARDWARE)
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
        }
        if (freqs.isNotEmpty()) put("max_freq_mhz", freqs.distinct().sortedDescending().joinToString { (it / 1000).toString() })
        else put("max_freq_mhz", "unavailable")
    }

    fun memory(): JsonObject {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return buildJsonObject {
            put("total_gb", gb(mi.totalMem)); put("available_gb", gb(mi.availMem))
            put("used_percent", ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt())
            put("low_memory", mi.lowMemory)
        }
    }

    fun storage(): JsonObject {
        val s = StatFs(Environment.getDataDirectory().path)
        val total = s.totalBytes; val free = s.availableBytes
        return buildJsonObject {
            put("total_gb", gb(total)); put("free_gb", gb(free)); put("used_percent", ((total - free) * 100 / total).toInt())
        }
    }

    fun battery(): JsonObject {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(BatteryManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return buildJsonObject {
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            put("percent", if (level >= 0) level * 100 / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            put("status", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> "unknown"
            })
            put("plugged", when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; else -> "unplugged"
            })
            i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { put("temperature_c", it / 10.0) }
            i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.let { put("voltage_mv", it) }
            put("health", when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"; BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"; else -> "unknown"
            })
            i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let { put("technology", it) }
            val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (now != Int.MIN_VALUE && now != 0) put("current_now_ma", now / 1000)
            val left = bm.computeChargeTimeRemaining()
            if (left > 0) put("charge_time_remaining_min", left / 60000)
            put("power_save_mode", pm.isPowerSaveMode)
            put("thermal_status", when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "normal"; PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"; PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                else -> "critical"
            })
        }
    }

    fun network(): JsonObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        val audio = context.getSystemService(AudioManager::class.java)
        return buildJsonObject {
            put("connected", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            put("validated_internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("transport", when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            })
            put("metered", cm.isActiveNetworkMetered)
            caps?.let { put("downlink_mbps", it.linkDownstreamBandwidthKbps / 1000); put("uplink_mbps", it.linkUpstreamBandwidthKbps / 1000) }
            put("wifi_enabled", wifi?.isWifiEnabled == true)
            put("bluetooth_enabled", runCatching { bt?.isEnabled == true }.getOrDefault(false))
            put("ringer_mode", when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" })
        }
    }

    fun sensors(): JsonObject {
        val sm = context.getSystemService(SensorManager::class.java)
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        return buildJsonObject {
            put("count", list.size)
            put("sensors", buildJsonArray {
                list.distinctBy { it.type to it.name }.forEach { s ->
                    add(buildJsonObject {
                        put("name", s.name); put("type", s.stringType.substringAfterLast('.')); put("vendor", s.vendor)
                        put("wake_up", s.isWakeUpSensor)
                    })
                }
            })
            put("readable_types", "accelerometer, gyroscope, magnetic_field, light, proximity, pressure, gravity, linear_acceleration, rotation_vector, ambient_temperature, relative_humidity, step_counter (needs activity recognition permission)")
        }
    }

    private val sensorTypes = mapOf(
        "accelerometer" to Sensor.TYPE_ACCELEROMETER, "gyroscope" to Sensor.TYPE_GYROSCOPE,
        "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD, "light" to Sensor.TYPE_LIGHT,
        "proximity" to Sensor.TYPE_PROXIMITY, "pressure" to Sensor.TYPE_PRESSURE,
        "gravity" to Sensor.TYPE_GRAVITY, "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
        "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR, "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
        "relative_humidity" to Sensor.TYPE_RELATIVE_HUMIDITY, "step_counter" to Sensor.TYPE_STEP_COUNTER,
    )

    private val sensorUnits = mapOf(
        "accelerometer" to "m/s²", "gyroscope" to "rad/s", "magnetic_field" to "µT", "light" to "lux", "proximity" to "cm",
        "pressure" to "hPa", "gravity" to "m/s²", "linear_acceleration" to "m/s²", "rotation_vector" to "unit quaternion",
        "ambient_temperature" to "°C", "relative_humidity" to "%", "step_counter" to "steps since boot",
    )

    /** Registers a listener, takes one sample and unregisters immediately. */
    suspend fun readSensor(name: String): JsonObject {
        val key = name.lowercase(Locale.US).replace(' ', '_')
        val type = sensorTypes[key] ?: return buildJsonObject { put("error", "Unknown sensor '$name'. Use one of: ${sensorTypes.keys.joinToString()}") }
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(type) ?: return buildJsonObject { put("error", "This device has no $key sensor.") }
        val values = withTimeoutOrNull(2500) {
            suspendCancellableCoroutine<FloatArray> { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values.copyOf())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                cont.invokeOnCancellation { sm.unregisterListener(l) }
                if (!sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)) {
                    if (cont.isActive) cont.resume(FloatArray(0))
                }
            }
        }
        return buildJsonObject {
            put("sensor", sensor.name); put("type", key); put("unit", sensorUnits[key] ?: "")
            if (values == null || values.isEmpty()) put("error", "No reading received (sensor idle, permission required, or value unchanged).")
            else put("values", buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive("%.3f".format(Locale.US, it).toDouble())) } })
            if (key == "proximity") put("max_range", sensor.maximumRange)
        }
    }

    fun hasFlashlight(): String? {
        val cm = context.getSystemService(CameraManager::class.java)
        return cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    /** A short line used in the system prompt so the model knows which phone it controls. */
    fun promptSummary(): String {
        val b = battery(); val m = memory(); val n = network()
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
            "battery ${b["percent"]}% ${b["status"]?.toString()?.trim('"')}; RAM free ${m["available_gb"]} GB of ${m["total_gb"]} GB; " +
            "network ${n["transport"]?.toString()?.trim('"')}"
    }

    private fun gb(b: Long) = "%.2f".format(Locale.US, b / 1_073_741_824.0).toDouble()
    private fun readLong(path: String) = runCatching { File(path).readText().trim().toLong() }.getOrNull()
}
