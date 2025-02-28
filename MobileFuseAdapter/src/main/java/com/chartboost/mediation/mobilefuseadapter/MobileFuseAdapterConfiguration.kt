/*
 * Copyright 2024-2025 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.mobilefuseadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.mobilefuse.sdk.MobileFuse
import com.mobilefuse.sdk.MobileFuseSettings

object MobileFuseAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "mobilefuse"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "MobileFuse"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion: String = MobileFuse.getSdkVersion()

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_MOBILEFUSE_ADAPTER_VERSION

    /**
     * Test mode flag that can optionally be set to true to enable test ads. It can be set at any
     * time and it will take effect for the next ad request. Remember to set this to false in
     * production.
     */
    var testMode = MobileFuseSettings.isTestMode()
        get() = MobileFuseSettings.isTestMode()
        set(value) {
            field = value
            MobileFuseSettings.setTestMode(value)
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "MobileFuse test mode is ${
                    if (value) {
                        "enabled. Remember to disable it before publishing."
                    } else {
                        "disabled."
                    }
                }",
            )
        }
}
