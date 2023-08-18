/*
 * Copyright 2022-2023 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.mobilefuseadapter

import android.content.Context
import android.util.Size
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.domain.ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.mobilefuse.sdk.*
import com.mobilefuse.sdk.AdError.*
import com.mobilefuse.sdk.MobileFuseBannerAd.AdSize
import com.mobilefuse.sdk.MobileFuseBannerAd.AdSize.*
import com.mobilefuse.sdk.MobileFuseBannerAd.Listener
import com.mobilefuse.sdk.internal.MobileFuseBiddingTokenProvider
import com.mobilefuse.sdk.internal.MobileFuseBiddingTokenRequest
import com.mobilefuse.sdk.internal.TokenGeneratorListener
import com.mobilefuse.sdk.privacy.MobileFusePrivacyPreferences
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

class MobileFuseAdapter : PartnerAdapter {
    companion object {
        /**
         * Test mode flag that can optionally be set to true to enable test ads. It can be set at any
         * time and it will take effect for the next ad request. Remember to set this to false in
         * production.
         */
        var testMode = false
            set(value) {
                field = value
                MobileFuseSettings.setTestMode(value)
                PartnerLogController.log(
                    CUSTOM,
                    "MobileFuse test mode is ${
                        if (value) "enabled. Remember to disable it before publishing."
                        else "disabled."
                    }"
                )
            }

        /**
         * The MobileFuse bidding token key.
         */
        private const val TOKEN_KEY = "signal"
    }

    /**
     * Lambda to be called for a successful MobileFuse interstitial ad show.
     */
    private var onInterstitialAdShowSuccess: () -> Unit = {}

    /**
     * Lambda to be called for a successful MobileFuse rewarded ad show.
     */
    private var onRewardedAdShowSuccess: () -> Unit = {}

    /**
     * The MobileFuse privacy preferences builder.
     */
    private var privacyBuilder = MobileFusePrivacyPreferences.Builder()

    /**
     * Get the MobileFuse SDK version.
     */
    override val partnerSdkVersion: String
        get() = MobileFuse.getSdkVersion()

    /**
     * Get the MobileFuse adapter version.
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
    override
    val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_MOBILEFUSE_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "mobilefuse"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "MobileFuse"

    /**
     * Initialize the MobileFuse SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize MobileFuse.
     *
     * @return Result.success() if the initialization was successful, Result.failure(Exception) otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Unit>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            MobileFuse.init(object : SdkInitListener {
                override fun onInitSuccess() {
                    resumeOnce(Result.success(PartnerLogController.log(SETUP_SUCCEEDED)))
                }

                override fun onInitError() {
PartnerLogController.log(SETUP_FAILED)
resumeOnce(
    Result.failure(
        ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN)
    )
)
                }

            })
        }
    }

    /**
     * Notify the MobileFuse SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        // Consent setting is a NO-OP as Chartboost Mediation does not support an IAB-compatible privacy consent string
        // https://docs.mobilefuse.com/docs/android-sdk-data-privacy
    }

    /**
     * Save the current CCPA privacy String to be used later.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )

        // Consent setting is a NO-OP as MobileFuse does not provide an API for CCPA per
        // https://docs.mobilefuse.com/docs/android-sdk-data-privacy
    }

    /**
     * Notify MobileFuse of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
        )

        MobileFuse.setPrivacyPreferences(
            privacyBuilder.setSubjectToCoppa(isSubjectToCoppa)
                .build()
        )
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Map<String, String>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val listener = object : TokenGeneratorListener {
                override fun onTokenGenerated(token: String) {
                    PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
                    resumeOnce(mapOf(TOKEN_KEY to token))
                }

                override fun onTokenGenerationFailed(error: String) {
                    PartnerLogController.log(BIDDER_INFO_FETCH_FAILED, error)
                    resumeOnce(emptyMap())
                }
            }

            MobileFuseBiddingTokenProvider.getToken(
                MobileFuseBiddingTokenRequest(
                    privacyPreferences = privacyBuilder.build(),
                    isTestMode = testMode
                ),
                context,
                listener
            )
        }
    }

    /**
     * Attempt to load a MobileFuse ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            AdFormat.INTERSTITIAL -> loadInterstitialAd(
                context,
                request,
                partnerAdListener
            )

            AdFormat.REWARDED -> loadRewardedAd(
                context,
                request,
                partnerAdListener
            )

            AdFormat.BANNER -> loadBannerAd(
                context,
                request,
                partnerAdListener
            )

            else -> {
                if (request.format.key == "rewarded_interstitial") {
                    // MobileFuse does not have a specific rewarded interstitial class.
                    loadRewardedAd(
                        context,
                        request,
                        partnerAdListener
                    )
                } else {
                    PartnerLogController.log(LOAD_FAILED)
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
                }
            }
        }
    }

    /**
     * Attempt to show the currently loaded MobileFuse ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the MobileFuse ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val result = when (partnerAd.request.format) {
                AdFormat.BANNER -> {
                    // Banner ads do not have a separate "show" mechanism.
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    Result.success(partnerAd)
                }

                AdFormat.INTERSTITIAL, AdFormat.REWARDED -> {
                    onInterstitialAdShowSuccess = {
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        resumeOnce(Result.success(partnerAd))
                    }

                    onRewardedAdShowSuccess = {
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        resumeOnce(Result.success(partnerAd))
                    }

                    showFullscreenAd(partnerAd)
                }

                else -> {
                    if (partnerAd.request.format.key == "rewarded_interstitial") {
                        showFullscreenAd(partnerAd)
                    } else {
                        PartnerLogController.log(SHOW_FAILED)
                        Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
                    }
                }
            }

            resumeOnce(result)
        }
    }

    /**
     * Discard unnecessary MobileFuse ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the MobileFuse ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        // Only invalidate banners as there are no explicit methods to invalidate the other formats.
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    /**
     * Attempt to load a MobileFuse banner ad.
     *
     * @param context The current [Context].
     * @param request A [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> = suspendCancellableCoroutine { continuation ->
        val bannerAd = MobileFuseBannerAd(
            context,
            request.partnerPlacement,
            getMobileFuseBannerAdSize(request.size)
        )

        val partnerAd = PartnerAd(
            ad = bannerAd,
            details = emptyMap(),
            request = request,
        )

        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        bannerAd.setListener(object : Listener {
            override fun onAdExpanded() {
                PartnerLogController.log(CUSTOM, "onAdExpanded")
            }

            override fun onAdCollapsed() {
                PartnerLogController.log(CUSTOM, "onAdCollapsed")
            }

            override fun onAdLoaded() {
                PartnerLogController.log(LOAD_SUCCEEDED)
                resumeOnce(Result.success(partnerAd))
            }

            override fun onAdNotFilled() {
                PartnerLogController.log(LOAD_FAILED, CM_LOAD_FAILURE_NO_FILL.cause)
                resumeOnce(Result.failure(ChartboostMediationAdException(CM_LOAD_FAILURE_NO_FILL)))
            }

            override fun onAdRendered() {
                PartnerLogController.log(CUSTOM, "onAdRendered")
            }

            override fun onAdClicked() {
                PartnerLogController.log(DID_CLICK)
                listener.onPartnerAdClicked(partnerAd)
            }

            override fun onAdExpired() {
                PartnerLogController.log(DID_EXPIRE)
                listener.onPartnerAdExpired(partnerAd)
            }

            override fun onAdError(adError: AdError) {
                PartnerLogController.log(LOAD_FAILED, adError.errorMessage)
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(getChartboostMediationError(adError))
                    )
                )
            }
        })

        bannerAd.loadAdFromBiddingToken(request.adm)
    }

    /**
     * Find the most appropriate MobileFuse ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The MobileFuse ad size that best matches the given [Size].
     */
    private fun getMobileFuseBannerAdSize(size: Size?): AdSize {
        val height = size?.height ?: return BANNER_320x50

        return when {
            height in 50 until 90 -> BANNER_320x50
            height in 90 until 250 -> BANNER_728x90
            height >= 250 -> BANNER_300x250
            else -> BANNER_320x50
        }
    }

    /**
     * Attempt to load a MobileFuse interstitial.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> = suspendCancellableCoroutine { continuation ->
        val interstitialAd = MobileFuseInterstitialAd(context, request.partnerPlacement)

        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        interstitialAd.setListener(object : MobileFuseInterstitialAd.Listener {
            override fun onAdLoaded() {
                PartnerLogController.log(LOAD_SUCCEEDED)
                resumeOnce(
                    Result.success(
                        PartnerAd(
                            ad = interstitialAd,
                            details = emptyMap(),
                            request = request
                        )
                    )
                )
            }

            override fun onAdNotFilled() {
                PartnerLogController.log(LOAD_FAILED, CM_LOAD_FAILURE_NO_FILL.cause)
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(CM_LOAD_FAILURE_NO_FILL)
                    )
                )
            }

            override fun onAdClosed() {
                PartnerLogController.log(DID_DISMISS)
                listener.onPartnerAdDismissed(
                    PartnerAd(
                        ad = interstitialAd,
                        details = Collections.emptyMap(),
                        request = request
                    ), null
                )
            }

            override fun onAdRendered() {
                onInterstitialAdShowSuccess()
            }

            override fun onAdClicked() {
                PartnerLogController.log(DID_CLICK)
                listener.onPartnerAdClicked(
                    PartnerAd(
                        ad = interstitialAd,
                        details = Collections.emptyMap(),
                        request = request
                    )
                )
            }

            override fun onAdExpired() {
                PartnerLogController.log(DID_EXPIRE)
                listener.onPartnerAdExpired(
                    PartnerAd(
                        ad = interstitialAd,
                        details = Collections.emptyMap(),
                        request = request,
                    )
                )
            }

            override fun onAdError(error: AdError) {
                PartnerLogController.log(LOAD_FAILED, error.errorMessage)
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(getChartboostMediationError(error))
                    )
                )
            }
        })

        interstitialAd.loadAdFromBiddingToken(request.adm)
    }

    /**
     * Attempt to load an MobileFuse rewarded ad.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> = suspendCancellableCoroutine { continuation ->
        val rewardedAd = MobileFuseRewardedAd(context, request.partnerPlacement)

        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        rewardedAd.setListener(object : MobileFuseRewardedAd.Listener {
            override fun onUserEarnedReward() {
                PartnerLogController.log(DID_REWARD)
                listener.onPartnerAdRewarded(
                    PartnerAd(
                        ad = rewardedAd,
                        details = Collections.emptyMap(),
                        request = request
                    )
                )
            }

            override fun onAdLoaded() {
                PartnerLogController.log(LOAD_SUCCEEDED)
                resumeOnce(
                    Result.success(
                        PartnerAd(
                            ad = rewardedAd,
                            details = Collections.emptyMap(),
                            request = request
                        )
                    )
                )
            }

            override fun onAdNotFilled() {
                PartnerLogController.log(LOAD_FAILED, CM_LOAD_FAILURE_NO_FILL.cause)
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(CM_LOAD_FAILURE_NO_FILL)
                    )
                )
            }

            override fun onAdClosed() {
                PartnerLogController.log(DID_DISMISS)
                listener.onPartnerAdDismissed(
                    PartnerAd(
                        ad = rewardedAd,
                        details = Collections.emptyMap(),
                        request = request
                    ), null
                )
            }

            override fun onAdRendered() {
                onRewardedAdShowSuccess()
            }

            override fun onAdClicked() {
                PartnerLogController.log(DID_CLICK)
                listener.onPartnerAdClicked(
                    PartnerAd(
                        ad = rewardedAd,
                        details = Collections.emptyMap(),
                        request = request
                    )
                )
            }

            override fun onAdExpired() {
                PartnerLogController.log(DID_EXPIRE)
                listener.onPartnerAdExpired(
                    PartnerAd(
                        ad = rewardedAd,
                        details = Collections.emptyMap(),
                        request = request,
                    )
                )
            }

            override fun onAdError(error: AdError) {
                PartnerLogController.log(LOAD_FAILED, error.errorMessage)
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(getChartboostMediationError(error))
                    )
                )
            }
        })

        rewardedAd.loadAdFromBiddingToken(request.adm)
    }

    /**
     * Attempt to show a MobileFuse fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the MobileFuse ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private fun showFullscreenAd(
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        fun showAdIfLoaded(isLoaded: () -> Boolean, showAd: () -> Unit): Result<PartnerAd> {
            return if (isLoaded()) {
                showAd()
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
            }
        }

        return when (val ad = partnerAd.ad) {
            null -> {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
            }

            is MobileFuseInterstitialAd -> showAdIfLoaded(ad::isLoaded, ad::showAd)
            is MobileFuseRewardedAd -> showAdIfLoaded(ad::isLoaded, ad::showAd)

            else -> {
                PartnerLogController.log(
                    SHOW_FAILED,
                    "Ad is not an instance of MobileFuseInterstitialAd or MobileFuseRewardedAd."
                )
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_WRONG_RESOURCE_TYPE))
            }
        }
    }

    /**
     * Destroy the current MobileFuse banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the MobileFuse ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return when (val bannerAd = partnerAd.ad) {
            null -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED, "Ad is already null.")
                Result.success(partnerAd)
            }

            is MobileFuseBannerAd -> {
                bannerAd.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }

            else -> {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not an MobileFuseBannerAd.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        }
    }

    /**
     * Convert a given MobileFuse error into a [ChartboostMediationError].
     *
     * @param error The MobileFuse error code to convert.
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: AdError) = when (error) {
        AD_ALREADY_LOADED -> ChartboostMediationError.CM_LOAD_FAILURE_LOAD_IN_PROGRESS
        AD_ALREADY_RENDERED -> ChartboostMediationError.CM_SHOW_FAILURE_SHOW_IN_PROGRESS
        AD_RUNTIME_ERROR -> ChartboostMediationError.CM_SHOW_FAILURE_UNKNOWN
        AD_LOAD_ERROR -> ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN
        else -> ChartboostMediationError.CM_PARTNER_ERROR
    }
}
