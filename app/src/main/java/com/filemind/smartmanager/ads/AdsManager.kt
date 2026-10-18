package com.filemind.smartmanager.ads

import com.filemind.smartmanager.BuildConfig

/**
 * Single integration point for Start.io. App ID comes from local.properties (startio.appId).
 * With an empty ID, ads are disabled — no placeholder IDs are ever used.
 * Ads must never be shown during move/replace/delete/organize operations (see [canShowAds]).
 */
object AdsManager {
    val isConfigured: Boolean get() = BuildConfig.STARTIO_APP_ID.isNotBlank()

    @Volatile var fileOperationInProgress: Boolean = false

    fun canShowAds(): Boolean = isConfigured && !fileOperationInProgress

    fun init(/* context: Context */) {
        if (!isConfigured) return
        // TODO: StartAppSDK.init(context, BuildConfig.STARTIO_APP_ID, false) — verify against current official Start.io docs;
        // TODO: show consent/privacy disclosure before init where required (GDPR/CCPA).
    }
}
