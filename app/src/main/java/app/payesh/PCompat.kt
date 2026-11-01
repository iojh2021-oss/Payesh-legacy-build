package app.payesh

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Compatibility layer so the same code runs from Android 5.0 (API 21) up to current Android.
 * Every function picks the modern API when it exists and an equivalent older one otherwise.
 */
@Suppress("DEPRECATION", "InlinedApi")
object PCompat {

    // ------------------------------------------------------------ system services (Context.getSystemService(Class) is API 23)
    fun <T : Any> sys(ctx: Context, c: Class<T>): T =
        ContextCompat.getSystemService(ctx, c) ?: throw IllegalStateException("service unavailable: " + c.simpleName)

    // ------------------------------------------------------------ notifications
    fun nb(ctx: Context, channel: String): Notification.Builder {
        if (Build.VERSION.SDK_INT >= 26) return Notification.Builder(ctx, channel)
        val b = Notification.Builder(ctx)
        when (channel) {
            "av_alerts" -> b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
            "av_info" -> b.setPriority(Notification.PRIORITY_DEFAULT)
            else -> b.setPriority(Notification.PRIORITY_LOW)
        }
        return b
    }

    fun channel(nm: NotificationManager, id: String, name: CharSequence, importance: Int) {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    fun action(ctx: Context, title: CharSequence, pi: PendingIntent): Notification.Action =
        if (Build.VERSION.SDK_INT >= 23)
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notif), title, pi).build()
        else Notification.Action.Builder(R.drawable.ic_notif, title, pi).build()

    fun notificationsEnabled(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun stopFg(s: Service) {
        try { ServiceCompat.stopForeground(s, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    /** Settings page of this app's notifications (the dedicated page exists only on Android 8+). */
    fun notifSettingsIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 26)
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

    fun openVpnSettings(ctx: Context) {
        for (a in listOf("android.settings.VPN_SETTINGS", "android.net.vpn.SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)) {
            try { ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------ device state
    fun deviceSecure(km: KeyguardManager): Boolean = if (Build.VERSION.SDK_INT >= 23) km.isDeviceSecure else km.isKeyguardSecure

    fun securityPatch(): String = if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "نامشخص"

    fun ignoringBattery(pm: PowerManager, pkg: String): Boolean =
        if (Build.VERSION.SDK_INT >= 23) pm.isIgnoringBatteryOptimizations(pkg) else true

    fun opCheck(ao: AppOpsManager?, op: String, uid: Int, pkg: String): Int? =
        if (ao == null) null
        else if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(op, uid, pkg)
        else ao.checkOpNoThrow(op, uid, pkg)

    fun signers(pi: PackageInfo): Array<Signature>? {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return null
            return if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        }
        return pi.signatures
    }

    val SIGNING_FLAG: Int
        get() = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        else android.content.pm.PackageManager.GET_SIGNATURES

    // ------------------------------------------------------------ files / time / processes
    /** true when [f] itself is a symbolic link (the parent folder may legitimately be one, e.g. /sdcard). */
    fun isLink(f: File): Boolean = try {
        val p = f.parentFile
        val c = if (p == null) f else File(p.canonicalFile, f.name)
        c.canonicalFile != c.absoluteFile
    } catch (_: Exception) { false }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun waitFor(p: Process, ms: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 26) return p.waitFor(ms, TimeUnit.MILLISECONDS)
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            try { p.exitValue(); return true } catch (_: IllegalThreadStateException) { Thread.sleep(50) }
        }
        return false
    }

    /** MediaStore query with selection / sort / limit given as a Bundle (Android 8+); older versions get the classic form. */
    fun query(cr: ContentResolver, uri: Uri, cols: Array<String>, b: Bundle): Cursor? {
        if (Build.VERSION.SDK_INT >= 26) return cr.query(uri, cols, b, null)
        val sel = b.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
        val args = b.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
        var sort = b.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
        val lim = b.getInt(ContentResolver.QUERY_ARG_LIMIT, -1)
        if (lim > 0) sort = (sort ?: "_id") + " LIMIT " + lim
        return cr.query(uri, cols, sel, args, sort)
    }

    // ------------------------------------------------------------ crypto: PBKDF2-HMAC-SHA256 (platform provider has it only on Android 8+)
    fun pbkdf2Sha256(pw: CharArray, salt: ByteArray, iter: Int, bits: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(String(pw).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hLen = 32
        val outLen = bits / 8
        val out = ByteArray(outLen)
        val blocks = (outLen + hLen - 1) / hLen
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (j in 1 until iter) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val off = (i - 1) * hLen
            System.arraycopy(t, 0, out, off, minOf(hLen, outLen - off))
        }
        return out
    }

    // ------------------------------------------------------------ who owns a connection
    /** API 29+ asks the system; Android 5-9 reads /proc/net/* (readable by apps before Android 10). */
    fun owner(cm: ConnectivityManager, proto: Int, local: InetAddress, sp: Int, remote: InetAddress, dp: Int): Int {
        if (Build.VERSION.SDK_INT >= 29)
            return cm.getConnectionOwnerUid(proto, InetSocketAddress(local, sp), InetSocketAddress(remote, dp))
        return procOwner(proto, sp, remote, dp)
    }

    private fun procOwner(proto: Int, sp: Int, remote: InetAddress, dp: Int): Int {
        val tcp = proto == 6
        val names = if (tcp) listOf("tcp6", "tcp") else listOf("udp6", "udp")
        val lp = String.format("%04X", sp)
        val rp = String.format("%04X", dp)
        for (n in names) {
            val ra = hexAddr(remote, n.endsWith("6"))
            try {
                File("/proc/net/$n").bufferedReader().use { r ->
                    r.readLine()
                    var line = r.readLine()
                    while (line != null) {
                        val f = line.trim().split(Regex("\\s+"))
                        if (f.size > 7 && f[1].endsWith(":$lp")) {
                            val ok = if (tcp) f[2].equals("$ra:$rp", true) else true
                            if (ok) {
                                val uid = f[7].toIntOrNull()
                                if (uid != null) return uid
                            }
                        }
                        line = r.readLine()
                    }
                }
            } catch (_: Exception) { }
        }
        return -1
    }

    private fun hexAddr(a: InetAddress, v6: Boolean): String {
        val b = a.address
        val sb = StringBuilder()
        if (b.size == 4) {
            if (v6) sb.append("0000000000000000FFFF0000")
            for (i in 3 downTo 0) sb.append(String.format("%02X", b[i]))
        } else {
            for (g in 0 until 4) for (i in 3 downTo 0) sb.append(String.format("%02X", b[g * 4 + i]))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ Persian (Jalali) date without android.icu (Android 7+)
    private val FA_DAYS = arrayOf("یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه")
    private val FA_MONTHS = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

    private fun faDigits(s: String): String {
        val sb = StringBuilder()
        for (ch in s) sb.append(if (ch in '0'..'9') ('۰' + (ch - '0')) else ch)
        return sb.toString()
    }

    fun jalali(d: Date): String {
        val cal = Calendar.getInstance()
        cal.time = d
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH) + 1
        val gd = cal.get(Calendar.DAY_OF_MONTH)
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        val jm: Int
        val jd: Int
        if (days < 186) { jm = 1 + days / 31; jd = 1 + days % 31 } else { jm = 7 + (days - 186) / 30; jd = 1 + (days - 186) % 30 }
        val wd = FA_DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return faDigits("$wd $jd ${FA_MONTHS[jm - 1]} $jy")
    }
}
