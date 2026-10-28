package com.phisherman.data

import android.content.Context

object Prefs {
    private const val FILE = "phisherman"
    private const val KEY_BROWSER = "browser_package"
    private const val KEY_HINDI = "hindi"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun chosenBrowser(ctx: Context): String? = sp(ctx).getString(KEY_BROWSER, null)
    fun setChosenBrowser(ctx: Context, pkg: String) = sp(ctx).edit().putString(KEY_BROWSER, pkg).apply()

    fun hindi(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_HINDI, false)
    fun setHindi(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(KEY_HINDI, on).apply()
}
