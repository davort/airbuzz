package com.davort.airbuzz.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.davort.airbuzz.service.AlertListener
import java.util.regex.Pattern

/** One line of the setup checklist. [fix] is null when nothing can be done from the app. */
data class Check(val title: String, val detail: String, val done: Boolean?, val fixLabel: String? = null, val fix: Fix? = null)

enum class Fix { NEARBY, NOTIFICATIONS, LISTENER, PAIR, COMPANION, AUTOSTART, TEST }

object Setup {
    fun hasNearby(c: Context) = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasPostNotifications(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True unless the phone's Bluetooth is switched off. Needs no permission. */
    fun bluetoothOn(c: Context) = c.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != false

    fun hasListener(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    @SuppressLint("MissingPermission")
    fun bondedAirAddress(c: Context): String? {
        if (!hasNearby(c)) return null
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return try {
            adapter.bondedDevices?.firstOrNull { it.name?.contains("Fitbit Air", ignoreCase = true) == true }?.address
        } catch (_: SecurityException) {
            null
        }
    }

    fun isAssociated(c: Context): Boolean {
        val cdm = c.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.isNotEmpty() else @Suppress("DEPRECATION") cdm.associations.isNotEmpty()
    }

    /** The phone maker as people know it, such as "OPPO" or "Samsung", for the background activity step. */
    val maker: String get() = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    /**
     * Whether Android exempts AirBuzz from battery optimisation. On ColorOS this is "Allow background activity" under
     * Battery usage (checked on Davor's phone, issue #20); on other phones it is the app's unrestricted battery setting.
     */
    fun ignoresBatteryOptimizations(c: Context) =
        c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true

    /** OPPO, OnePlus and realme run ColorOS, whose App info page has Battery usage with "Allow background activity". */
    val isColorOs: Boolean get() = Build.MANUFACTURER.lowercase() in setOf("oppo", "oneplus", "realme")

    val isXiaomi: Boolean get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    fun checks(c: Context, testedOk: Boolean): List<Check> = buildList {
        val nearby = hasNearby(c)
        add(Check("Nearby devices", "Lets AirBuzz talk to your Fitbit Air over Bluetooth.", nearby, "Allow", Fix.NEARBY))
        add(Check("Fitbit Air paired", "Pair your Fitbit Air in the Google Health app first. AirBuzz uses that pairing.",
            if (nearby) bondedAirAddress(c) != null else null, "Open Bluetooth", Fix.PAIR))
        add(Check("Notification access", "Lets AirBuzz see calls and messages. Nothing is stored except who they were from.",
            hasListener(c), "Allow", Fix.LISTENER))
        add(Check("Notifications", "Shows Silence while a call rings your Fitbit Air.", hasPostNotifications(c), "Allow", Fix.NOTIFICATIONS))
        add(Check("Run in the background", "Links AirBuzz to your Fitbit Air in Android, so Android lets it keep running in the background.",
            isAssociated(c), "Link", Fix.COMPANION))
        if (isXiaomi) add(Check("Xiaomi Autostart", "On Xiaomi phones, also turn on Autostart for AirBuzz. AirBuzz can't check this.",
            null, "Open", Fix.AUTOSTART))
        add(Check("Test buzz", "Checks the whole path to your Fitbit Air.", testedOk, "Test", Fix.TEST))
    }

    fun listenerSettings(c: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, AlertListener::class.java).flattenToString())
        else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun autostartSettings(c: Context): Intent {
        val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        return if (c.packageManager.resolveActivity(miui, 0) != null) miui
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
    }

    /**
     * Asks Android to associate the Air with AirBuzz as a companion device. Android shows its own dialog, launched
     * through [launch]; [failed] gets Android's reason when it can't find the Air.
     */
    fun associate(activity: Activity, launch: (IntentSender) -> Unit, failed: (String) -> Unit) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return failed("This phone has no companion device support.")
        val address = bondedAirAddress(activity)
        val request = AssociationRequest.Builder().setSingleDevice(true).apply {
            if (address != null) addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            addDeviceFilter(BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("Fitbit Air.*")).build())
        }.build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Replaced by onAssociationPending on API 33")
            override fun onDeviceFound(sender: IntentSender) = launch(sender)
            override fun onAssociationPending(sender: IntentSender) = launch(sender)
            override fun onAssociationCreated(info: AssociationInfo) {}
            override fun onFailure(error: CharSequence?) = failed(error?.toString() ?: "Android couldn't find your Fitbit Air.")
        }
        if (Build.VERSION.SDK_INT >= 33) cdm.associate(request, activity.mainExecutor, callback)
        else @Suppress("DEPRECATION") cdm.associate(request, callback, null)
    }
}
