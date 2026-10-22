package com.filemind.smartmanager.ads

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.startapp.sdk.ads.banner.Banner
import com.filemind.smartmanager.BuildConfig
import com.startapp.sdk.adsbase.StartAppSDK

/**
 * Single integration point for Start.io. App ID: local.properties (startio.appId) > env STARTIO_APP_ID > project default.
 * Put `startio.appId=` (empty) in local.properties to disable ads entirely.
 * Debug builds always use Start.io TEST ads. Ads are never shown during move/replace/delete/organize operations (see [canShowAds]).
 */
object AdsManager {
    val isConfigured: Boolean get() = BuildConfig.STARTIO_APP_ID.isNotBlank()

    @Volatile var fileOperationInProgress: Boolean = false

    fun canShowAds(): Boolean = isConfigured && !fileOperationInProgress

    fun init(context: Context) {
        if (!isConfigured) return
        // Per official Start.io docs (initParams). Return ads are disabled. Start.io's own consent dialog stays at its default (enabled).
        StartAppSDK.setTestAdsEnabled(BuildConfig.DEBUG)
        StartAppSDK.initParams(context.applicationContext, BuildConfig.STARTIO_APP_ID)
            .setReturnAdsEnabled(false)
            .init()
        // TODO: interstitial on list-exit (needs user's approval of placement); never while fileOperationInProgress.
    }
}

/** One small Start.io banner fixed at the very bottom of the whole app, OUTSIDE the screen content (never overlaps buttons). Hidden while a file operation runs. */
@Composable
fun BottomAdBar(hidden: Boolean) {
    if (!AdsManager.isConfigured || hidden) return
    Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
        AndroidView(factory = { Banner(it) }, modifier = Modifier.fillMaxWidth())
    }
}
