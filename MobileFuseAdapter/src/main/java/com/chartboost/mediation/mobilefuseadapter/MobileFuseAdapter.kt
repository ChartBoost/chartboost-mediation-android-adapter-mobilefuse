/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.mobilefuseadapter

import android.app.Activity
import android.content.Context
import android.util.Size
import com.chartboost.chartboostmediationsdk.ad.ChartboostMediationBannerAdView.ChartboostMediationBannerSize.Companion.asSize
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.domain.ChartboostMediationError.LoadError.NoFill
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_EXPIRE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentValue
import com.mobilefuse.sdk.AdError
import com.mobilefuse.sdk.AdError.AD_ALREADY_LOADED
import com.mobilefuse.sdk.AdError.AD_ALREADY_RENDERED
import com.mobilefuse.sdk.AdError.AD_LOAD_ERROR
import com.mobilefuse.sdk.AdError.AD_RUNTIME_ERROR
import com.mobilefuse.sdk.MobileFuse
import com.mobilefuse.sdk.MobileFuseBannerAd
import com.mobilefuse.sdk.MobileFuseBannerAd.AdSize
import com.mobilefuse.sdk.MobileFuseBannerAd.AdSize.BANNER_300x250
import com.mobilefuse.sdk.MobileFuseBannerAd.AdSize.BANNER_320x50
import com.mobilefuse.sdk.MobileFuseBannerAd.AdSize.BANNER_728x90
import com.mobilefuse.sdk.MobileFuseBannerAd.Listener
import com.mobilefuse.sdk.MobileFuseInterstitialAd
import com.mobilefuse.sdk.MobileFuseRewardedAd
import com.mobilefuse.sdk.SdkInitListener
import com.mobilefuse.sdk.internal.MobileFuseBiddingTokenProvider
import com.mobilefuse.sdk.internal.MobileFuseBiddingTokenRequest
import com.mobilefuse.sdk.internal.TokenGeneratorListener
import com.mobilefuse.sdk.privacy.MobileFusePrivacyPreferences
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class MobileFuseAdapter : PartnerAdapter {
    companion object {
        /**
         * The MobileFuse bidding token key.
         */
        private const val TOKEN_KEY = "signal"

        /**
         * Convert a given MobileFuse error into a [ChartboostMediationError].
         *
         * @param error The MobileFuse error code to convert.
         *
         * @return The corresponding [ChartboostMediationError].
         */
        internal fun getChartboostMediationError(error: AdError) =
            when (error) {
                AD_ALREADY_LOADED -> ChartboostMediationError.LoadError.LoadInProgress
                AD_ALREADY_RENDERED -> ChartboostMediationError.ShowError.ShowInProgress
                AD_RUNTIME_ERROR -> ChartboostMediationError.ShowError.Unknown
                AD_LOAD_ERROR -> ChartboostMediationError.LoadError.Unknown
                else -> ChartboostMediationError.OtherError.PartnerError
            }

        /**
         * Lambda to be called for a successful MobileFuse interstitial ad show.
         */
        internal var onInterstitialAdShowSuccess: () -> Unit = {}

        /**
         * Lambda to be called for a successful MobileFuse rewarded ad show.
         */
        internal var onRewardedAdShowSuccess: () -> Unit = {}
    }

    /**
     * The MobileFuse adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = MobileFuseAdapterConfiguration

    /**
     * The MobileFuse privacy preferences builder.
     */
    private var privacyBuilder = MobileFusePrivacyPreferences.Builder()

    /**
     * Initialize the MobileFuse SDK so that it is ready to request ads.
     *
     * @param context The current [Activity].
     * @param partnerConfiguration Configuration object containing relevant data to initialize MobileFuse.
     *
     * @return Result.success() if the initialization was successful, Result.failure(Exception) otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, Any>>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            MobileFuse.init(
                object : SdkInitListener {
                    override fun onInitSuccess() {
                        PartnerLogController.log(SETUP_SUCCEEDED)
                        resumeOnce(Result.success(emptyMap()))
                    }

                    override fun onInitError() {
                        PartnerLogController.log(SETUP_FAILED)
                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown),
                            ),
                        )
                    }
                },
            )
        }
    }

    /**
     * Notify MobileFuse of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        MobileFuse.setPrivacyPreferences(
            privacyBuilder.setSubjectToCoppa(isUserUnderage)
                .build(),
        )
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, String>>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val listener =
                object : TokenGeneratorListener {
                    override fun onTokenGenerated(token: String) {
                        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
                        resumeOnce(Result.success(mapOf(TOKEN_KEY to token)))
                    }

                    override fun onTokenGenerationFailed(error: String) {
                        PartnerLogController.log(BIDDER_INFO_FETCH_FAILED, error)
                        resumeOnce(Result.success(emptyMap()))
                    }
                }

            MobileFuseBiddingTokenProvider.getToken(
                MobileFuseBiddingTokenRequest(
                    privacyPreferences = privacyBuilder.build(),
                    isTestMode = MobileFuseAdapterConfiguration.testMode,
                ),
                context,
                listener,
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
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            PartnerAdFormats.INTERSTITIAL ->
                loadInterstitialAd(
                    context,
                    request,
                    partnerAdListener,
                )

            PartnerAdFormats.REWARDED, PartnerAdFormats.REWARDED_INTERSTITIAL ->
                loadRewardedAd(
                    context,
                    request,
                    partnerAdListener,
                )

            PartnerAdFormats.BANNER ->
                loadBannerAd(
                    context,
                    request,
                    partnerAdListener,
                )

            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded MobileFuse ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the MobileFuse ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return suspendCancellableCoroutine { continuation ->
            val weakContinuationRef = WeakReference(continuation)

            fun resumeOnce(result: Result<PartnerAd>) {
                weakContinuationRef.get()?.let {
                    if (it.isActive) {
                        it.resume(result)
                    }
                } ?: run {
                    PartnerLogController.log(SHOW_FAILED, "Unable to resume continuation once. Continuation is null.")
                }
            }

            val result =
                when (partnerAd.request.format) {
                    PartnerAdFormats.BANNER -> {
                        // Banner ads do not have a separate "show" mechanism.
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        Result.success(partnerAd)
                    }

                    PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED, PartnerAdFormats.REWARDED_INTERSTITIAL -> {
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
                        PartnerLogController.log(SHOW_FAILED)
                        Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
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
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>,
    ) {
        consents[ConsentKeys.GPP]?.let {
            privacyBuilder.setGppConsentString(it)
        }

        consents[ConsentKeys.TCF]?.let {
            privacyBuilder.setIabConsentString(it)
        }

        consents[ConsentKeys.USP]?.let {
            PartnerLogController.log(CUSTOM, "${PartnerLogController.PRIVACY_TAG} USP set to $it")
            privacyBuilder.setUsPrivacyConsentString(it)
        }

        MobileFuse.setPrivacyPreferences(privacyBuilder.build())
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
        listener: PartnerAdListener,
    ): Result<PartnerAd> =
        suspendCancellableCoroutine { continuation ->
            val bannerAd =
                MobileFuseBannerAd(
                    context,
                    request.partnerPlacement,
                    getMobileFuseBannerAdSize(request.bannerSize?.asSize()),
                )

            val partnerAd =
                PartnerAd(
                    ad = bannerAd,
                    details = emptyMap(),
                    request = request,
                )

            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            bannerAd.setListener(
                object : Listener {
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
                        PartnerLogController.log(LOAD_FAILED, NoFill.cause.toString())
                        resumeOnce(Result.failure(ChartboostMediationAdException(NoFill)))
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
                                ChartboostMediationAdException(getChartboostMediationError(adError)),
                            ),
                        )
                    }
                },
            )

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
        listener: PartnerAdListener,
    ): Result<PartnerAd> =
        suspendCancellableCoroutine { continuation ->
            val interstitialAd = MobileFuseInterstitialAd(context, request.partnerPlacement)

            interstitialAd.setListener(
                InterstitialAdListener(
                    WeakReference(continuation),
                    request = request,
                    listener = listener,
                    interstitialAd = interstitialAd,
                ),
            )

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
        listener: PartnerAdListener,
    ): Result<PartnerAd> =
        suspendCancellableCoroutine { continuation ->
            val rewardedAd = MobileFuseRewardedAd(context, request.partnerPlacement)

            rewardedAd.setListener(
                RewardedAdListener(
                    continuationRef = WeakReference(continuation),
                    request = request,
                    listener = listener,
                    rewardedAd = rewardedAd,
                ),
            )

            rewardedAd.loadAdFromBiddingToken(request.adm)
        }

    /**
     * Attempt to show a MobileFuse fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the MobileFuse ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private fun showFullscreenAd(partnerAd: PartnerAd): Result<PartnerAd> {
        fun showAdIfLoaded(
            isLoaded: () -> Boolean,
            showAd: () -> Unit,
        ): Result<PartnerAd> {
            return if (isLoaded()) {
                showAd()
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
            }
        }

        return when (val ad = partnerAd.ad) {
            null -> {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
            }

            is MobileFuseInterstitialAd -> showAdIfLoaded(ad::isLoaded, ad::showAd)
            is MobileFuseRewardedAd -> showAdIfLoaded(ad::isLoaded, ad::showAd)

            else -> {
                PartnerLogController.log(
                    SHOW_FAILED,
                    "Ad is not an instance of MobileFuseInterstitialAd or MobileFuseRewardedAd.",
                )
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.WrongResourceType))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        }
    }

    /**
     * Callback for interstitial ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param request A [PartnerAdLoadRequest] object containing the request.
     * @param listener A [PartnerAdListener] to be notified of ad events.
     * @param interstitialAd A [MobileFuseInterstitialAd] object containing the MobileFuse ad.
     */
    private class InterstitialAdListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val request: PartnerAdLoadRequest,
        private val listener: PartnerAdListener,
        private val interstitialAd: MobileFuseInterstitialAd,
    ) : MobileFuseInterstitialAd.Listener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
            }
        }

        override fun onAdLoaded() {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = interstitialAd,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdNotFilled() {
            PartnerLogController.log(LOAD_FAILED, NoFill.cause.toString())
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(NoFill),
                ),
            )
        }

        override fun onAdClosed() {
            PartnerLogController.log(DID_DISMISS)
            listener.onPartnerAdDismissed(
                PartnerAd(
                    ad = interstitialAd,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
        }

        override fun onAdRendered() {
            onInterstitialAdShowSuccess()
            onInterstitialAdShowSuccess = {}
        }

        override fun onAdClicked() {
            PartnerLogController.log(DID_CLICK)
            listener.onPartnerAdClicked(
                PartnerAd(
                    ad = interstitialAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdExpired() {
            PartnerLogController.log(DID_EXPIRE)
            listener.onPartnerAdExpired(
                PartnerAd(
                    ad = interstitialAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdError(error: AdError) {
            PartnerLogController.log(LOAD_FAILED, error.errorMessage)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(getChartboostMediationError(error)),
                ),
            )
        }
    }

    /**
     * Callback for rewarded ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param request A [PartnerAdLoadRequest] object containing the request.
     * @param listener A [PartnerAdListener] to be notified of ad events.
     * @param rewardedAd A [MobileFuseRewardedAd] object containing the MobileFuse ad.
     */
    private class RewardedAdListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val request: PartnerAdLoadRequest,
        private val listener: PartnerAdListener,
        private val rewardedAd: MobileFuseRewardedAd,
    ) : MobileFuseRewardedAd.Listener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
            }
        }

        override fun onUserEarnedReward() {
            PartnerLogController.log(DID_REWARD)
            listener.onPartnerAdRewarded(
                PartnerAd(
                    ad = rewardedAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdLoaded() {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = rewardedAd,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdNotFilled() {
            PartnerLogController.log(LOAD_FAILED, NoFill.cause.toString())
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(NoFill),
                ),
            )
        }

        override fun onAdClosed() {
            PartnerLogController.log(DID_DISMISS)
            listener.onPartnerAdDismissed(
                PartnerAd(
                    ad = rewardedAd,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
        }

        override fun onAdRendered() {
            onRewardedAdShowSuccess()
            onRewardedAdShowSuccess = {}
        }

        override fun onAdClicked() {
            PartnerLogController.log(DID_CLICK)
            listener.onPartnerAdClicked(
                PartnerAd(
                    ad = rewardedAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdExpired() {
            PartnerLogController.log(DID_EXPIRE)
            listener.onPartnerAdExpired(
                PartnerAd(
                    ad = rewardedAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onAdError(error: AdError) {
            PartnerLogController.log(LOAD_FAILED, error.errorMessage)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(getChartboostMediationError(error)),
                ),
            )
        }
    }
}
