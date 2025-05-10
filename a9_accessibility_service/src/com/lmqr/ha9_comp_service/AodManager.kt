package com.lmqr.ha9_comp_service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.preference.PreferenceManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.lmqr.ha9_comp_service.button_mapper.ClearScreenButtonAction
import com.lmqr.ha9_comp_service.command_runners.CommandRunner
import com.lmqr.ha9_comp_service.command_runners.UnixSocketCommandRunner
import java.util.Calendar

class AodManager : NotificationListenerService() {
    private lateinit var commandRunner: CommandRunner
    private lateinit var refreshModeManager: RefreshModeManager
    private lateinit var powerManager: PowerManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var clearScreenAction: ClearScreenButtonAction
    private var lastRefreshTime = 0L

    private val TAG = "AodManager"
    private val REFRESH_THROTTLE_MS = 15000
    private val ACTION_MINUTE_TICK = "com.lmqr.ha9_comp_service.MINUTE_TICK"

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_MINUTE_TICK && isAodActive()) {
                refreshAodIfNeeded("Time changed")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        commandRunner = UnixSocketCommandRunner()
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        refreshModeManager = RefreshModeManager(sharedPreferences, commandRunner)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        clearScreenAction = ClearScreenButtonAction(commandRunner, refreshModeManager)
        
        registerReceiver(timeTickReceiver, IntentFilter(ACTION_MINUTE_TICK))
        
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Check if this is AOD mode activating
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAodActive()) {
                        // Let AOD render initially, then refresh once and put to sleep
                        refreshAodContent()
                        putDisplayToSleep()
                    }
                }, 1000)
            }
        }, screenFilter)
        scheduleNextMinuteTick()
    }

    private fun scheduleNextMinuteTick() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 1)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        val intent = Intent(ACTION_MINUTE_TICK)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isAodActive()) {
            refreshAodIfNeeded("Notification received")
        }
    }

    private fun refreshAodIfNeeded(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTime > REFRESH_THROTTLE_MS) {
            lastRefreshTime = now
            Log.d(TAG, "Refreshing AOD: $reason")
            
            wakeDisplayBriefly()
            
            scheduleNextMinuteTick()
        }
    }

    private fun wakeDisplayBriefly() {
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EinkAodManager:RefreshWakeLock"
        )
        wakeLock.acquire(2000)
        
        Handler(Looper.getMainLooper()).postDelayed({
            refreshAodContent()
            wakeLock.release()
            
            Handler(Looper.getMainLooper()).postDelayed({
                putDisplayToSleep()
            }, 500)
        }, 1000)
    }
    
    private fun refreshAodContent() {
        clearScreenAction.execute(this)
    }
    private fun putDisplayToSleep() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "EinkAodManager:SleepLock")
        wakeLock.acquire(100)
        wakeLock.release()
    }
    
    private fun isAodActive(): Boolean {
        return Settings.Secure.getInt(contentResolver, "doze_enabled", 0) == 1 && 
               !powerManager.isInteractive
    }
    
    override fun onDestroy() {
        unregisterReceiver(timeTickReceiver)
        super.onDestroy()
    }
}