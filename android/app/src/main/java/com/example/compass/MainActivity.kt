package com.example.compass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 最近一次传感器值
    @Volatile private var lastAccel = 1.0f
    @Volatile private var lastHeading = -1f
    @Volatile private var lastPressure = -1f          // hPa
    @Volatile private var pressureBaseline = -1f      // 基准气压（用于算海拔/爬升）
    @Volatile private var lastAltitude = -1.0         // 由气压算出的海拔（米）
    @Volatile private var lastLocAt = 0L              // 最后一次收到定位的时间

    // 定位请求
    private var locationListening = false
    private var retryCount = 0
    private val maxRetry = 3   // 最多重试 3 次，但每次进页面会重置
    private var stepsAtStart: Long = -1
    private var stepCounter = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (coarse || fine) {
            locationListening = false
            startLocation()
        } else {
            notifyJs("locationDenied", "{}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 屏幕常亮（运动记录时不熄屏）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式全屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage
            allowFileAccess = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }
        webView.setBackgroundColor(0xFF0B0C0E.toInt())
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.loadUrl("file:///android_asset/app.html")

        runCatching { setupSensors() }
        requestPermissions()
    }

    /* ---------------- 权限 ---------------- */
    private fun requestPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
        else startLocation()
    }

    /* ---------------- 定位 ---------------- */
    private fun hasFine(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCoarse(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (locationListening) return
        // 只要有任意定位权限就启动；没有权限就告诉网页
        if (!hasFine() && !hasCoarse()) {
            notifyJs("locationDenied", "{}")
            return
        }

        val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val hasNet = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        var started = false

        try {
            // GPS：只有拿到精确权限才请求，否则系统会抛 SecurityException
            if (hasGps && hasFine()) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0.5f, locationListener, Looper.getMainLooper()
                )
                started = true
            }
            // 网络定位：有大致位置权限就行，室内主要靠它
            if (hasNet) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 2f, locationListener, Looper.getMainLooper()
                )
                started = true
            }
            // 立刻取一次最后已知位置，避免刚打开时空白
            for (pv in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (pv == LocationManager.GPS_PROVIDER && !hasFine()) continue
                runCatching { locationManager.getLastKnownLocation(pv)?.let { pushLocation(it) } }
            }
        } catch (e: SecurityException) {
            started = false
        } catch (e: Exception) {
            started = false
        }

        locationListening = started
        // 告诉网页定位是否真的启动了
        val info = JSONObject()
        info.put("started", started)
        info.put("hasGps", hasGps)
        info.put("hasNet", hasNet)
        info.put("fine", hasFine())
        info.put("coarse", hasCoarse())
        notifyJs("locationState", info.toString())
    }

    private val locationListener = LocationListener { loc -> pushLocation(loc) }

    private fun pushLocation(loc: Location) {
        mainHandler.post {
            lastLocAt = System.currentTimeMillis()
            val o = JSONObject()
            o.put("lat", loc.latitude)
            o.put("lon", loc.longitude)
            // 海拔：安卓没数据时会给 0，这里转成 null，避免误显示 0 米
            val hasAlt = loc.hasAltitude() && loc.altitude != 0.0
            if (hasAlt) o.put("alt", loc.altitude) else o.put("alt", JSONObject.NULL)
            o.put("acc", if (loc.hasAccuracy()) loc.accuracy else -1.0)
            o.put("hasSpeed", loc.hasSpeed())
            o.put("speed", if (loc.hasSpeed()) loc.speed else -1.0)      // m/s
            o.put("hasBearing", loc.hasBearing())
            o.put("bearing", if (loc.hasBearing()) loc.bearing else -1.0)
            o.put("time", loc.time)
            o.put("provider", loc.provider ?: "")
            notifyJs("onLocation", o.toString())
        }
    }

    /* ---------------- 传感器 ---------------- */
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val a = event.values
            val g = Math.sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble()) / 9.80665
            lastAccel = g.toFloat()
            val o = JSONObject()
            o.put("x", a[0]); o.put("y", a[1]); o.put("z", a[2])
            o.put("g", g)
            notifyJs("onAccel", o.toString())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val o = JSONObject()
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val R = FloatArray(9)
                val orient = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(R, event.values)
                SensorManager.getOrientation(R, orient)
                var az = Math.toDegrees(orient[0].toDouble())
                if (az < 0) az += 360.0
                o.put("heading", az)
                o.put("absolute", true)
            } else {
                o.put("heading", ((event.values[0] + 360f) % 360f).toDouble())
                o.put("absolute", false)
            }
            notifyJs("onHeading", o.toString())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val p = event.values[0]          // hPa
            lastPressure = p
            // 首次采样作为基准，之后算相对高度
            if (pressureBaseline < 0) pressureBaseline = p
            // 气压高度公式（ISA 标准大气）
            // h = 44330 * (1 - (P/P0)^0.1903)
            val ratio = (p / pressureBaseline).toDouble()
            val h = 44330.0 * (1.0 - Math.pow(ratio, 0.1903))
            lastAltitude = h
            val o = JSONObject()
            o.put("pressure", p)
            o.put("baseline", pressureBaseline)
            o.put("alt", h)
            notifyJs("onPressure", o.toString())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val total = event.values[0].toLong()
            if (stepsAtStart < 0) stepsAtStart = total
            stepCounter = total - stepsAtStart
            val o = JSONObject(); o.put("steps", stepCounter)
            notifyJs("onSteps", o.toString())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun setupSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        (sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD))?.let {
            sensorManager.registerListener(rotationListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(pressureListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    /* ---------------- JS 桥 ---------------- */
    inner class Bridge {

        @JavascriptInterface
        fun isNative(): Boolean = true

    /** 重置并重新启动定位（网页可随时调用） */
    @JavascriptInterface
    fun retryLocation(): Boolean {
        mainHandler.post {
            locationListening = false
            retryCount = 0
            try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
            startLocation()
            // 3 秒后如果没有定位数据，再试一次
            mainHandler.postDelayed({
                if (System.currentTimeMillis() - lastLocAt > 3000 && retryCount < maxRetry) {
                    retryCount++
                    locationListening = false
                    startLocation()
                }
            }, 3200)
        }
        return true
    }

        @JavascriptInterface
        fun getVersion(): String = "1.0"

        /** 系统 UI 边距：状态栏、导航栏高度（px），让网页精确留白 */
        @JavascriptInterface
        fun getInsets(): String {
            val o = JSONObject()
            try {
                val dm = resources.displayMetrics
                val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
                val navId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
                val statusPx = if (statusId > 0) resources.getDimensionPixelSize(statusId) else 0
                val navPx = if (navId > 0) resources.getDimensionPixelSize(navId) else 0
                o.put("statusBar", statusPx / dm.density)
                o.put("navBar", navPx / dm.density)
                o.put("density", dm.density)
                o.put("densityDpi", dm.densityDpi)
                o.put("widthDp", dm.widthPixels / dm.density)
                o.put("heightDp", dm.heightPixels / dm.density)
                o.put("sdkInt", Build.VERSION.SDK_INT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val ins = window.decorView.rootWindowInsets
                    if (ins != null) {
                        val bars = ins.getInsets(android.view.WindowInsets.Type.systemBars())
                        o.put("insetTop", bars.top / dm.density)
                        o.put("insetBottom", bars.bottom / dm.density)
                    }
                }
            } catch (_: Exception) {}
            return o.toString()
        }

        /** 是否有气压计 */
        @JavascriptInterface
        fun hasBarometer(): Boolean =
            sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

        /** 当前气压海拔 */
        @JavascriptInterface
        fun getPressureAlt(): String {
            val o = JSONObject()
            o.put("pressure", lastPressure.toDouble())
            o.put("alt", lastAltitude)
            o.put("has", lastPressure > 0)
            return o.toString()
        }

        /** 重置气压基准（开始记录时调用） */
        @JavascriptInterface
        fun resetPressureBaseline() {
            pressureBaseline = lastPressure
            lastAltitude = 0.0
        }

        @JavascriptInterface
        fun getAccel(): String {
            val o = JSONObject(); o.put("g", lastAccel)
            return o.toString()
        }

        @JavascriptInterface
        fun getHeading(): String {
            val o = JSONObject(); o.put("heading", lastHeading)
            return o.toString()
        }

        @JavascriptInterface
        fun resetStepBaseline() {
            stepsAtStart = -1
            stepCounter = 0
        }

        @JavascriptInterface
        fun toast(msg: String) {
            mainHandler.post { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
        }

        /** 导出文件：把内容存到 App 缓存目录并调起分享 */
        @JavascriptInterface
        fun saveAndShare(fileName: String, content: String, mime: String): Boolean {
            return try {
                val dir = java.io.File(cacheDir, "export")
                if (!dir.exists()) dir.mkdirs()
                val f = java.io.File(dir, fileName)
                f.writeText(content, Charsets.UTF_8)
                mainHandler.post {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        f
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "导出到"))
                }
                true
            } catch (e: Exception) {
                mainHandler.post { Toast.makeText(this@MainActivity, "导出失败：${e.message}", Toast.LENGTH_LONG).show() }
                false
            }
        }

        /** 震动反馈 */
        @JavascriptInterface
        fun vibrate(ms: Int) {
            try {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(
                        ms.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") v.vibrate(ms.toLong())
                }
            } catch (_: Exception) {}
        }
    }

    private fun notifyJs(fn: String, json: String) {
        mainHandler.post {
            webView.evaluateJavascript("window.__native_$fn && window.__native_$fn($json);", null)
        }
    }

    /* ---------------- 生命周期 ---------------- */
    override fun onResume() {
        super.onResume()
        runCatching { setupSensors() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            locationListening = false
            startLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        try { sensorManager.unregisterListener(accelListener) } catch (_: Exception) {}
        try { sensorManager.unregisterListener(rotationListener) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        try { sensorManager.unregisterListener(stepListener) } catch (_: Exception) {}
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 交给网页处理返回，避免误退出
        webView.evaluateJavascript("window.__onBackPressed && window.__onBackPressed();") {
            if (it == "false" || it == null) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
