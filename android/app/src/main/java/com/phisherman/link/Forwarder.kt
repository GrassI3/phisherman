package com.phisherman.link

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.phisherman.data.Prefs

data class BrowserApp(val packageName: String, val label: String)

object Forwarder {
    private const val CHROME = "com.android.chrome"

    /** Installed browsers other than Phisherman itself. */
    fun browsers(ctx: Context): List<BrowserApp> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = ctx.packageManager
        return pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { BrowserApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
    }

    fun targetBrowser(ctx: Context): String? {
        val installed = browsers(ctx).map { it.packageName }
        return Prefs.chosenBrowser(ctx)?.takeIf { it in installed }
            ?: CHROME.takeIf { it in installed }
            ?: installed.firstOrNull()
    }

    /** Opens the URL in the real browser. Never forwards to ourselves, which would loop. */
    fun openInRealBrowser(ctx: Context, url: String): Boolean {
        val target = targetBrowser(ctx) ?: return false
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
