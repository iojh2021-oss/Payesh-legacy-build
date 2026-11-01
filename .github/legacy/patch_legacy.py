#!/usr/bin/env python3
"""
Build-time patcher for the Android 7+ (API 24) variant of payesh.

Runs ONLY on the GitHub runner, on its temporary checkout. Nothing is committed back, so the real
project, its settings and its normal "Build APK" workflow stay exactly as they are.

What it does
  1. minSdk 29 -> 24
  2. adaptive icons (API 26+) moved to mipmap-anydpi-v26 and a plain fallback icon added
  3. adds app/payesh/PCompat.kt: API-guarded replacements for the calls that do not exist on Android 7-9
  4. rewrites the call sites to use PCompat
If any expected pattern is missing/left over, the script fails loudly instead of building a broken APK.
"""
import pathlib, re, sys

ROOT = pathlib.Path(".")
SRC = ROOT / "app/src/main/java/app/payesh"
RES = ROOT / "app/src/main/res"
errors = []

def read(p): return p.read_text(encoding="utf-8")
def write(p, s): p.write_text(s, encoding="utf-8")

# ---------------------------------------------------------------- 1. gradle
g = ROOT / "app/build.gradle.kts"
s = read(g)
s2, n = re.subn(r"minSdk\s*=\s*29", "minSdk = 24", s)
if n != 1: errors.append("minSdk = 29 not found in app/build.gradle.kts")
write(g, s2)

# ---------------------------------------------------------------- 2. icons
old = RES / "mipmap-anydpi"
new = RES / "mipmap-anydpi-v26"
if old.exists() and not new.exists():
    old.rename(new)
elif not new.exists():
    errors.append("res/mipmap-anydpi not found")
legacy = RES / "mipmap"
legacy.mkdir(exist_ok=True)
for name in ("ic_launcher", "ic_launcher_round"):
    write(legacy / f"{name}.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n'
          '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <item android:drawable="@color/ic_bg" />\n'
          '    <item android:drawable="@drawable/ic_fg" />\n'
          '</layer-list>\n')

# ---------------------------------------------------------------- 3. PCompat.kt
write(SRC / "PCompat.kt", r'''package app.payesh

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android 7-9 compatibility layer. Generated at build time by .github/legacy/patch_legacy.py */
object PCompat {

    @Suppress("DEPRECATION")
    fun nb(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel)
        else Notification.Builder(ctx).setPriority(Notification.PRIORITY_LOW)

    fun channel(nm: NotificationManager, id: String, name: CharSequence, importance: Int) {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    fun isLink(f: File): Boolean = try { f.canonicalFile != f.absoluteFile } catch (_: Exception) { false }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Suppress("DEPRECATION")
    fun opCheck(ao: AppOpsManager?, op: String, uid: Int, pkg: String): Int? =
        if (Build.VERSION.SDK_INT >= 29) ao?.unsafeCheckOpNoThrow(op, uid, pkg)
        else ao?.checkOpNoThrow(op, uid, pkg)

    @Suppress("DEPRECATION")
    fun signers(pi: PackageInfo): Array<Signature>? {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return null
            return if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        }
        return pi.signatures
    }

    fun query(cr: ContentResolver, uri: Uri, cols: Array<String>, b: Bundle): Cursor? {
        if (Build.VERSION.SDK_INT >= 26) return cr.query(uri, cols, b, null)
        val sel = b.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
        val args = b.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
        var sort = b.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
        val lim = b.getInt(ContentResolver.QUERY_ARG_LIMIT, -1)
        if (lim > 0) sort = (sort ?: "_id") + " LIMIT " + lim
        return cr.query(uri, cols, sel, args, sort)
    }

    /** Which app (uid) owns this connection. API 29+ asks the system; Android 7-9 reads the proc net files. */
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
                File("/proc/net/" + n).bufferedReader().use { r ->
                    r.readLine()
                    var line = r.readLine()
                    while (line != null) {
                        val f = line.trim().split(Regex("\\s+"))
                        if (f.size > 7 && f[1].endsWith(":" + lp)) {
                            val ok = if (tcp) f[2].equals(ra + ":" + rp, true) else true
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
}
''')

# ---------------------------------------------------------------- 4. call sites
RULES = [
    # (regex, replacement, leftover-check)
    (r"Notification\.Builder\(", "PCompat.nb(", r"Notification\.Builder\("),
    (r"(\w+)\.createNotificationChannel\(NotificationChannel\(([^()]*)\)\)", r"PCompat.channel(\1, \2)",
        r"createNotificationChannel\(NotificationChannel"),
    (r"\b(ctx|context)\.startForegroundService\(", r"androidx.core.content.ContextCompat.startForegroundService(\1, ",
        r"\.startForegroundService\((?!\w+, )"),
    (r"Files\.isSymbolicLink\((\w+)\.toPath\(\)\)", r"PCompat.isLink(\1)", r"isSymbolicLink"),
    (r"java\.time\.LocalDate\.now\(\)\.toString\(\)", "PCompat.today()", r"java\.time\.LocalDate"),
    (r"(\w+)\??\.unsafeCheckOpNoThrow\(", r"PCompat.opCheck(\1, ", r"\.unsafeCheckOpNoThrow\("),
    (r"val si = pi\.signingInfo\s*val signers = if \(si == null\) null else if \(si\.hasMultipleSigners\(\)\) "
     r"si\.apkContentsSigners else si\.signingCertificateHistory", "val signers = PCompat.signers(pi)",
        r"pi\.signingInfo"),
    (r"PackageManager\.GET_SIGNING_CERTIFICATES",
        "(if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)",
        None),
    (r"contentResolver\.query\(MediaStore\.Files\.getContentUri\(\"external\"\), cols, q, null\)",
        'PCompat.query(contentResolver, MediaStore.Files.getContentUri("external"), cols, q)',
        r"cols, q, null"),
    (r"cm\.getConnectionOwnerUid\(proto, InetSocketAddress\(local, sp\), InetSocketAddress\(remote, dp\)\)",
        "PCompat.owner(cm, proto, local, sp, remote, dp)", r"\.getConnectionOwnerUid\("),
]

hits = {r[0]: 0 for r in RULES}
for f in sorted(SRC.glob("*.kt")):
    if f.name == "PCompat.kt": continue
    t = read(f); o = t
    for pat, rep, _ in RULES:
        t, n = re.subn(pat, rep, t)
        hits[pat] += n
    if t != o: write(f, t)

for pat, _, left in RULES:
    if hits[pat] == 0:
        errors.append("pattern never matched (source changed?): " + pat[:70])
    if left:
        for f in SRC.glob("*.kt"):
            if f.name == "PCompat.kt": continue
            if re.search(left, read(f)):
                errors.append(f"leftover API-26+ call in {f.name}: {left}")

if errors:
    print("PATCH FAILED:"); [print("  -", e) for e in errors]; sys.exit(1)
print("legacy patch OK:", {k[:28]: v for k, v in hits.items()})
