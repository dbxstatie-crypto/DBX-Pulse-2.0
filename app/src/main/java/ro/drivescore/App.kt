package ro.drivescore

import android.app.Application

/** Păstrează ultima eroare care a oprit aplicația, ca s-o vedem în Setări → Diagnostic. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val ts = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date())
                Store.setRaw(this, "last_crash", ts + " [" + t.name + "]\n" + sw.toString().take(1500))
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
}
