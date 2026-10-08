package app.waypoint.mock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.waypoint.R
import app.waypoint.core.session.MockPhase
import app.waypoint.core.session.SessionState
import app.waypoint.core.session.TargetPlace
import app.waypoint.data.geo.RouteCodec
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.data.repo.RoutesRepo
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Foreground service (type location) that owns the mock session:
 * acquire → tick-inject → release, plus route playback.
 */
class MockService : Service(), KoinComponent {

    private val session: SessionState by inject()
    private val settings: SettingsStore by inject()
    private val routesRepo: RoutesRepo by inject()

    private val engine by lazy { MockEngine(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var sequenceJob: Job? = null
    private var tickJob: Job? = null
    private var follower: RouteFollower? = null
    private var routeName: String = ""
    private var mockOwnedByRoute = false
    private var screenOff = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var jitterTick = 0L
    private var lastNotifiedName: String? = null
    private var lastTickElapsed = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff = true
                Intent.ACTION_SCREEN_ON -> screenOff = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(targetFrom(intent))
            ACTION_MOVE -> handleMove(targetFrom(intent))
            ACTION_STOP -> handleStop()
            ACTION_ROUTE_PLAY -> handleRoutePlay(intent)
            ACTION_ROUTE_STOP -> handleRouteStop(intent.getBooleanExtra(EXTRA_RESTORE, true))
            ACTION_ROUTE_TUNE -> handleRouteTune(intent)
        }
        return START_NOT_STICKY
    }

    private fun targetFrom(intent: Intent): TargetPlace = TargetPlace(
        lat = intent.getDoubleExtra(EXTRA_LAT, session.target.value.lat),
        lon = intent.getDoubleExtra(EXTRA_LON, session.target.value.lon),
        name = intent.getStringExtra(EXTRA_NAME) ?: session.target.value.name,
        sub = intent.getStringExtra(EXTRA_SUB) ?: ""
    )

    // ---- start / move / stop ----

    private fun handleStart(target: TargetPlace) {
        session.target.value = target
        settings.setLastTarget(target.lat, target.lon, target.name, target.sub)
        if (session.phase.value == MockPhase.Mocking || session.phase.value == MockPhase.Acquiring) {
            updateNotification()
            return
        }
        if (!goForeground()) return
        sequenceJob?.cancel()
        sequenceJob = scope.launch { acquireSequence() }
    }

    private fun handleMove(target: TargetPlace) {
        if (session.phase.value == MockPhase.Idle) {
            handleStart(target)
            return
        }
        session.target.value = target
        settings.setLastTarget(target.lat, target.lon, target.name, target.sub)
        updateNotification()
    }

    private fun handleStop() {
        sequenceJob?.cancel()
        sequenceJob = scope.launch { releaseSequence() }
    }

    private suspend fun acquireSequence() {
        session.lastError.value = null
        session.phase.value = MockPhase.Acquiring
        session.acquireStep.value = 0
        session.acquireSats.value = 0
        val t0 = SystemClock.elapsedRealtime()

        // sats counter: 0→12 over ~2.3 s (design cadence, real registration behind it)
        val satsJob = scope.launch {
            for (n in 1..12) {
                delay(190)
                session.acquireSats.value = n
            }
        }

        val registered = engine.register()
        if (!registered) {
            satsJob.cancel()
            failNotSelected()
            return
        }

        awaitSince(t0, 900)
        session.acquireStep.value = 1
        injectOnce() // first real fix

        awaitSince(t0, 1800)
        session.acquireStep.value = 2

        awaitSince(t0, MIN_ACQUIRE_MS)
        satsJob.cancel()
        session.acquireSats.value = 12
        session.phase.value = MockPhase.Mocking
        if (session.since.value == null) session.since.value = System.currentTimeMillis()
        startTicks()
        updateNotification(force = true)
    }

    private suspend fun releaseSequence() {
        val t0 = SystemClock.elapsedRealtime()
        session.phase.value = MockPhase.Releasing
        stopTicks()
        follower = null
        session.routeActive.value = false
        session.routeProgress.value = 0f
        engine.unregister()
        awaitSince(t0, MIN_RELEASE_MS)
        session.phase.value = MockPhase.Idle
        session.since.value = null
        session.liveBearing.value = 0f
        session.liveSpeedMs.value = 0f
        mockOwnedByRoute = false
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failNotSelected() {
        session.lastError.value = ERROR_NOT_SELECTED
        session.phase.value = MockPhase.Idle
        session.since.value = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- route playback ----

    private fun handleRoutePlay(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ROUTE_ID, -1L)
        val pointsJson = intent.getStringExtra(EXTRA_POINTS)

        scope.launch {
            val entity = if (pointsJson == null && id > 0) routesRepo.byId(id) else null
            val points = when {
                pointsJson != null -> RouteCodec.fromJson(pointsJson)
                entity != null -> routesRepo.points(entity)
                else -> emptyList()
            }
            val speed = if (intent.hasExtra(EXTRA_SPEED)) intent.getIntExtra(EXTRA_SPEED, 40)
            else entity?.speedKmh ?: 40
            val loop = if (intent.hasExtra(EXTRA_LOOP)) intent.getBooleanExtra(EXTRA_LOOP, false)
            else entity?.loop ?: false
            val reverse = if (intent.hasExtra(EXTRA_REVERSE)) intent.getBooleanExtra(EXTRA_REVERSE, false)
            else entity?.reverse ?: false
            val name = intent.getStringExtra(EXTRA_NAME) ?: entity?.name ?: getString(R.string.route_title)
            if (points.size < 2) return@launch

            mockOwnedByRoute = session.phase.value == MockPhase.Idle
            routeName = name
            follower = RouteFollower(points, speed, loop, reverse)
            session.routeProgress.value = if (reverse) 1f else 0f
            lastTickElapsed = SystemClock.elapsedRealtime()

            if (session.phase.value != MockPhase.Mocking) {
                if (!goForeground()) return@launch
                if (!engine.register()) {
                    failNotSelected()
                    return@launch
                }
                session.phase.value = MockPhase.Mocking
                if (session.since.value == null) session.since.value = System.currentTimeMillis()
                startTicks()
            }
            session.routeActive.value = true
            updateNotification(force = true)
        }
    }

    private fun handleRouteStop(restore: Boolean) {
        follower = null
        session.routeActive.value = false
        session.routeProgress.value = 0f
        session.liveBearing.value = 0f
        session.liveSpeedMs.value = 0f
        if (restore && mockOwnedByRoute) {
            handleStop()
        } else {
            updateNotification(force = true)
        }
        mockOwnedByRoute = false
    }

    private fun handleRouteTune(intent: Intent) {
        val f = follower ?: return
        if (intent.hasExtra(EXTRA_SPEED)) f.speedKmh = intent.getIntExtra(EXTRA_SPEED, f.speedKmh)
        if (intent.hasExtra(EXTRA_LOOP)) f.loop = intent.getBooleanExtra(EXTRA_LOOP, f.loop)
        if (intent.hasExtra(EXTRA_REVERSE)) f.reverse = intent.getBooleanExtra(EXTRA_REVERSE, f.reverse)
    }

    // ---- tick loop ----

    private fun startTicks() {
        acquireWakeLock()
        lastTickElapsed = SystemClock.elapsedRealtime()
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                injectOnce()
                delay(intervalMs())
            }
        }
    }

    private fun stopTicks() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun intervalMs(): Long {
        val base = (settings.value.intervalS * 1000).toLong().coerceAtLeast(250L)
        val routePlaying = session.routeActive.value
        return if (screenOff && !routePlaying) maxOf(base, 2000L) else base
    }

    private fun injectOnce() {
        val s = settings.value
        jitterTick++
        val f = follower

        if (f != null && session.routeActive.value) {
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - lastTickElapsed).coerceAtLeast(0L)) / 1000.0
            lastTickElapsed = now
            val playing = f.advance(dt)
            val p = f.position()
            val brg = f.bearing()
            val spd = f.speedMs.toFloat()
            session.routeProgress.value = f.progress.toFloat()
            session.liveBearing.value = brg
            session.liveSpeedMs.value = spd
            session.target.value = TargetPlace(p.lat, p.lon, routeName, "")
            engine.inject(p.lat, p.lon, s.altitudeM.toDouble(), s.accuracyM.toFloat(), brg, spd)
            if (!playing) {
                // Route finished · position held at last point
                follower = null
                session.routeActive.value = false
                session.lastError.value = EVENT_ROUTE_FINISHED
                settings.setLastTarget(p.lat, p.lon, routeName, "")
                updateNotification(force = true)
            }
            return
        }

        val t = session.target.value
        var lat = t.lat
        var lon = t.lon
        var acc = s.accuracyM.toFloat()
        if (s.jitter) {
            lat += sin(jitterTick * 1.7) * 0.00009
            lon += cos(jitterTick * 1.3) * 0.00011
            acc += (jitterTick % 3).toInt()
        }
        session.liveBearing.value = 0f
        session.liveSpeedMs.value = 0f
        engine.inject(lat, lon, s.altitudeM.toDouble(), acc, 0f, 0f)
    }

    // ---- foreground / notification ----

    private fun goForeground(): Boolean = try {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        // Missing runtime location permission (34+) or background-start restriction.
        session.lastError.value = ERROR_FOREGROUND
        stopSelf()
        false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val contentPi = PendingIntent.getActivity(
            this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, MockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val place = session.target.value.name
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_on_fill)
            .setContentTitle(getString(R.string.notif_title, place))
            .setContentText(getString(R.string.notif_text))
            .setColor(ContextCompat.getColor(this, R.color.wp_live))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        if (session.phase.value == MockPhase.Idle) return
        val name = session.target.value.name
        if (!force && name == lastNotifiedName) return
        lastNotifiedName = name
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waypoint:mock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private suspend fun awaitSince(t0: Long, target: Long) {
        val left = target - (SystemClock.elapsedRealtime() - t0)
        if (left > 0) delay(left)
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        stopTicks()
        engine.unregister()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "mocking"
        const val NOTIF_ID = 41

        const val ACTION_START = "app.waypoint.mock.START"
        const val ACTION_MOVE = "app.waypoint.mock.MOVE"
        const val ACTION_STOP = "app.waypoint.mock.STOP"
        const val ACTION_ROUTE_PLAY = "app.waypoint.mock.ROUTE_PLAY"
        const val ACTION_ROUTE_STOP = "app.waypoint.mock.ROUTE_STOP"
        const val ACTION_ROUTE_TUNE = "app.waypoint.mock.ROUTE_TUNE"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_NAME = "name"
        const val EXTRA_SUB = "sub"
        const val EXTRA_ROUTE_ID = "routeId"
        const val EXTRA_POINTS = "points"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_REVERSE = "reverse"
        const val EXTRA_RESTORE = "restore"

        const val ERROR_NOT_SELECTED = "not_selected"
        const val ERROR_FOREGROUND = "foreground_denied"
        const val EVENT_ROUTE_FINISHED = "route_finished"

        const val MIN_ACQUIRE_MS = 2700L
        const val MIN_RELEASE_MS = 700L

        fun start(context: Context, lat: Double, lon: Double, name: String, sub: String) {
            val i = Intent(context, MockService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LON, lon)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_SUB, sub)
            ContextCompat.startForegroundService(context, i)
        }

        fun move(context: Context, lat: Double, lon: Double, name: String, sub: String) {
            val i = Intent(context, MockService::class.java).setAction(ACTION_MOVE)
                .putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LON, lon)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_SUB, sub)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MockService::class.java).setAction(ACTION_STOP))
        }

        fun routePlay(
            context: Context,
            pointsJson: String,
            name: String,
            speed: Int,
            loop: Boolean,
            reverse: Boolean
        ) {
            val i = Intent(context, MockService::class.java).setAction(ACTION_ROUTE_PLAY)
                .putExtra(EXTRA_POINTS, pointsJson)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_SPEED, speed)
                .putExtra(EXTRA_LOOP, loop)
                .putExtra(EXTRA_REVERSE, reverse)
            ContextCompat.startForegroundService(context, i)
        }

        fun routePlayById(context: Context, routeId: Long, speed: Int, loop: Boolean, reverse: Boolean) {
            val i = Intent(context, MockService::class.java).setAction(ACTION_ROUTE_PLAY)
                .putExtra(EXTRA_ROUTE_ID, routeId)
                .putExtra(EXTRA_SPEED, speed)
                .putExtra(EXTRA_LOOP, loop)
                .putExtra(EXTRA_REVERSE, reverse)
            ContextCompat.startForegroundService(context, i)
        }

        fun routeStop(context: Context, restore: Boolean) {
            context.startService(
                Intent(context, MockService::class.java)
                    .setAction(ACTION_ROUTE_STOP)
                    .putExtra(EXTRA_RESTORE, restore)
            )
        }

        fun routeTune(context: Context, speed: Int?, loop: Boolean?, reverse: Boolean?) {
            val i = Intent(context, MockService::class.java).setAction(ACTION_ROUTE_TUNE)
            speed?.let { i.putExtra(EXTRA_SPEED, it) }
            loop?.let { i.putExtra(EXTRA_LOOP, it) }
            reverse?.let { i.putExtra(EXTRA_REVERSE, it) }
            context.startService(i)
        }
    }
}
