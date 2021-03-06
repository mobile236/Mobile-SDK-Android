package com.adapty.internal.data.cloud

import android.app.Activity
import android.content.Context
import androidx.annotation.RestrictTo
import com.adapty.errors.AdaptyError
import com.adapty.errors.AdaptyErrorCode
import com.adapty.internal.data.models.ProductDto
import com.adapty.internal.data.models.PurchaseHistoryRecordModel
import com.adapty.internal.data.models.responses.PaywallsResponse
import com.adapty.internal.utils.*
import com.adapty.models.SubscriptionUpdateParamModel
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode.*
import com.android.billingclient.api.BillingClient.SkuType.INAPP
import com.android.billingclient.api.BillingClient.SkuType.SUBS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
internal class StoreManager(
    context: Context,
    private val currencyHelper: CurrencyHelper,
    private val productMapper: ProductMapper,
    private val prorationModeMapper: ProrationModeMapper,
) : PurchasesUpdatedListener {

    private val billingClient = BillingClient
        .newBuilder(context)
        .enablePendingPurchases()
        .setListener(this)
        .build()

    private val storeHelper = StoreHelper(billingClient)

    private var makePurchaseCallback: MakePurchaseCallback? = null

    @JvmSynthetic
    fun fillBillingInfo(
        data: ArrayList<Any>,
        maxAttemptCount: Long,
    ): Flow<ArrayList<Any>> =
        querySkuDetails(
            data.map(::prepareSkuList).flatten().distinct(),
            maxAttemptCount
        ).map { fullSkuList -> fillInfo(fullSkuList, data) }

    @JvmSynthetic
    fun getPurchaseHistoryDataToRestore(maxAttemptCount: Long): Flow<ArrayList<PurchaseHistoryRecordModel>> =
        getPurchaseHistoryDataToRestoreForType(SUBS, maxAttemptCount)
            .flatMapConcat { subsHistoryList ->
                getPurchaseHistoryDataToRestoreForType(INAPP, maxAttemptCount)
                    .map { inAppHistoryList -> concatResults(subsHistoryList, inAppHistoryList) }
            }

    private fun getPurchaseHistoryDataToRestoreForType(
        @BillingClient.SkuType type: String,
        maxAttemptCount: Long,
    ): Flow<List<PurchaseHistoryRecordModel>> {
        return onConnected {
            storeHelper.queryPurchaseHistoryForType(type)
                .map { purchaseHistoryRecordList ->
                    purchaseHistoryRecordList.map { purchase ->
                        PurchaseHistoryRecordModel(
                            purchase,
                            type,
                            type.takeIf { it == SUBS }?.let {
                                billingClient.queryPurchases(SUBS).purchasesList
                                    ?.firstOrNull { it.purchaseToken == purchase.purchaseToken }
                                    ?.orderId
                            }
                        )
                    }
                }
        }.retryOnConnectionError(maxAttemptCount)
    }

    @JvmSynthetic
    fun querySkuDetails(
        skuList: List<String>,
        maxAttemptCount: Long,
    ): Flow<List<SkuDetails>> =
        querySkuDetailsForType(
            SkuDetailsParams.newBuilder().setSkusList(skuList).setType(SUBS).build(),
            maxAttemptCount
        )
            .flatMapConcat { subsList ->
                querySkuDetailsForType(
                    SkuDetailsParams.newBuilder().setSkusList(skuList).setType(INAPP).build(),
                    maxAttemptCount
                ).map { inAppList -> concatResults(subsList, inAppList) }
            }

    private fun querySkuDetailsForType(
        params: SkuDetailsParams,
        maxAttemptCount: Long,
    ): Flow<List<SkuDetails>> {
        return onConnected {
            storeHelper.querySkuDetailsForType(params)
        }.retryOnConnectionError(maxAttemptCount)
    }

    private fun <T> concatResults(list1: List<T>, list2: List<T>) =
        ArrayList(list1).apply { addAll(list2) }

    private fun onError(
        purchase: Purchase?,
        billingResult: BillingResult,
        callback: MakePurchaseCallback?
    ) {
        val message = "Play Market request failed: ${billingResult.debugMessage}"
        Logger.logError { message }
        callback?.invoke(
            purchase, AdaptyError(
                message = message,
                adaptyErrorCode = AdaptyErrorCode.fromBilling(billingResult.responseCode)
            )
        )
    }

    private fun onError(
        purchase: Purchase?,
        error: Throwable,
        callback: MakePurchaseCallback?
    ) {
        val message = error.message ?: "Play Market request failed"
        Logger.logError { message }
        callback?.invoke(
            purchase,
            (error as? AdaptyError) ?: AdaptyError(
                originalError = error,
                message = message,
                adaptyErrorCode = AdaptyErrorCode.UNKNOWN
            )
        )
    }

    private fun fillInfo(
        skuDetailsList: List<SkuDetails>,
        dataList: ArrayList<Any>
    ): ArrayList<Any> {
        skuDetailsList.forEach { skuDetails ->
            dataList.forEach { data ->
                when (data) {
                    is PaywallsResponse.Data -> {
                        data.attributes?.products?.forEach { product ->
                            if (skuDetails.sku == product.vendorProductId) {
                                product.setDetails(skuDetails, currencyHelper, productMapper)
                            }
                        }
                    }
                    is ArrayList<*> -> {
                        data.filterIsInstance(ProductDto::class.java).forEach { product ->
                            if (skuDetails.sku == product.vendorProductId) {
                                product.setDetails(skuDetails, currencyHelper, productMapper)
                            }
                        }
                    }
                }
            }
        }
        return dataList
    }

    private val productsToPurchaseSkuType = hashMapOf<String, String>()

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        when (billingResult.responseCode) {
            OK -> {
                if (purchases == null) {
                    makePurchaseCallback?.invoke(null, null)
                    return
                }

                for (purchase in purchases) {
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        postProcess(purchase)
                    } else {
                        makePurchaseCallback?.invoke(purchase, AdaptyError(
                            message = "Purchase: PENDING_PURCHASE",
                            adaptyErrorCode = AdaptyErrorCode.PENDING_PURCHASE
                        ))
                    }
                }
            }
            USER_CANCELED -> {
                makePurchaseCallback?.invoke(
                    null, AdaptyError(
                        message = "Purchase: USER_CANCELED",
                        adaptyErrorCode = AdaptyErrorCode.USER_CANCELED
                    )
                )
            }
            else -> {
                onError(null, billingResult, makePurchaseCallback)
            }
        }
    }

    private fun postProcess(purchase: Purchase) {
        if (purchase.isAcknowledged) {
            makePurchaseCallback?.invoke(purchase, null)
            return
        }

        execute {
            if (productsToPurchaseSkuType[purchase.skus.firstOrNull().orEmpty()] == INAPP) {
                consumePurchase(purchase, maxAttemptCount = DEFAULT_RETRY_COUNT)
            } else {
                acknowledgePurchase(purchase, maxAttemptCount = DEFAULT_RETRY_COUNT)
            }
                .catch { error -> onError(purchase, error, makePurchaseCallback) }
                .onEach { billingResult ->
                    if (billingResult.responseCode == OK) {
                        makePurchaseCallback?.invoke(purchase, null)
                    } else {
                        onError(purchase, billingResult, makePurchaseCallback)
                    }
                }
                .flowOnMain()
                .collect()
        }
    }

    @JvmSynthetic
    fun makePurchase(
        activity: Activity,
        productId: String,
        purchaseType: String,
        subscriptionUpdateParams: SubscriptionUpdateParamModel?,
        callback: MakePurchaseCallback
    ) {
        execute {
            onConnected {
                storeHelper.querySkuDetailsForType(
                    params = SkuDetailsParams.newBuilder().setSkusList(listOf(productId))
                        .setType(purchaseType).build(),
                ).flatMapConcat { skuDetailsForNewProduct ->
                    if (subscriptionUpdateParams != null) {
                        onConnected {
                            storeHelper.queryPurchaseHistoryForType(SUBS)
                                .map {
                                    buildSubscriptionUpdateParams(
                                        billingClient.queryPurchases(SUBS).purchasesList,
                                        subscriptionUpdateParams,
                                    ).let { updateParams -> skuDetailsForNewProduct to updateParams }
                                }
                        }
                    } else {
                        flowOf(skuDetailsForNewProduct to null)
                    }
                }
            }
                .flowOnIO()
                .catch { error -> onError(null, error, callback) }
                .onEach { (skuDetailsList, subscriptionUpdateParams) ->
                    skuDetailsList.firstOrNull { it.sku == productId }?.let { skuDetails ->
                        productsToPurchaseSkuType[productId] = purchaseType
                        makePurchaseCallback = MakePurchaseCallbackWrapper(productId, callback)

                        billingClient.launchBillingFlow(
                            activity,
                            BillingFlowParams.newBuilder()
                                .setSkuDetails(skuDetails)
                                .apply { subscriptionUpdateParams?.let(::setSubscriptionUpdateParams) }
                                .build()
                        )
                    } ?: callback.invoke(
                        null,
                        AdaptyError(
                            message = "This product_id was not found with this purchase type",
                            adaptyErrorCode = AdaptyErrorCode.PRODUCT_NOT_FOUND
                        )
                    )
                }
                .flowOnMain()
                .collect()
        }
    }

    private fun buildSubscriptionUpdateParams(
        purchasesList: List<Purchase>?,
        subscriptionUpdateParams: SubscriptionUpdateParamModel,
    ): BillingFlowParams.SubscriptionUpdateParams =
        purchasesList
            ?.firstOrNull { it.skus.firstOrNull() == subscriptionUpdateParams.oldSubVendorProductId }
            ?.let { subToBeReplaced ->
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldSkuPurchaseToken(subToBeReplaced.purchaseToken)
                    .setReplaceSkusProrationMode(
                        prorationModeMapper.map(subscriptionUpdateParams.prorationMode)
                    )
                    .build()
            }
            ?: "Can't launch flow to change subscription. Either subscription to change is inactive, or it was purchased from different Google account or from iOS".let { errorMessage ->
                Logger.logError { errorMessage }
                throw AdaptyError(
                    message = errorMessage,
                    adaptyErrorCode = AdaptyErrorCode.CURRENT_SUBSCRIPTION_TO_UPDATE_NOT_FOUND_IN_HISTORY
                )
            }

    @JvmSynthetic
    fun acknowledgePurchase(purchase: Purchase, maxAttemptCount: Long = INFINITE_RETRY) =
        onConnected {
            storeHelper.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            )
        }
            .retryOnConnectionError(maxAttemptCount)
            .flowOnIO()

    @JvmSynthetic
    fun queryInapps() =
        billingClient.queryPurchases(INAPP).purchasesList?.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }

    @JvmSynthetic
    fun queryUnacknowledgedSubs() =
        billingClient.queryPurchases(SUBS).purchasesList
            ?.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }

    @JvmSynthetic
    fun findActivePurchaseForProduct(
        productId: String,
        @BillingClient.SkuType productType: String
    ) =
        billingClient.queryPurchases(productType).purchasesList
            ?.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.skus.firstOrNull() == productId }

    @JvmSynthetic
    fun consumePurchase(purchase: Purchase, maxAttemptCount: Long = INFINITE_RETRY) =
        onConnected {
            storeHelper.consumePurchase(
                ConsumeParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            )
        }
            .retryOnConnectionError(maxAttemptCount)
            .flowOnIO()

    private fun prepareSkuList(data: Any) = when (data) {
        is PaywallsResponse.Data -> {
            data.attributes?.products?.mapNotNull { it.vendorProductId }.orEmpty()
        }
        is ArrayList<*> -> {
            data.filterIsInstance(ProductDto::class.java).mapNotNull { it.vendorProductId }
        }
        else -> {
            listOf()
        }
    }

    private fun <T> onConnected(call: () -> Flow<T>): Flow<T> =
        restoreConnection()
            .flatMapLatest { call() }

    private fun restoreConnection(): Flow<Unit> =
        flow {
            emit(billingClient.startConnectionSync())
        }
            .take(1)

    private val startConnectionSemaphore = Semaphore(1)

    private suspend fun BillingClient.startConnectionSync() {
        startConnectionSemaphore.acquire()
        return suspendCancellableCoroutine { continuation ->
            var resumed = false
            startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (!resumed) {
                        if (billingResult.responseCode == OK) {
                            continuation.resume(Unit) {}
                        } else {
                            continuation.resumeWithException(
                                AdaptyError(
                                    message = "Play Market request failed: ${billingResult.debugMessage}",
                                    adaptyErrorCode = AdaptyErrorCode.fromBilling(billingResult.responseCode)
                                )
                            )
                        }
                        resumed = true
                        startConnectionSemaphore.release()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    if (!resumed) {
                        continuation.resumeWithException(
                            AdaptyError(
                                message = "Play Market request failed: SERVICE_DISCONNECTED",
                                adaptyErrorCode = AdaptyErrorCode.fromBilling(SERVICE_DISCONNECTED)
                            )
                        )
                        resumed = true
                        startConnectionSemaphore.release()
                    }
                }
            })
        }
    }

    private fun <T> Flow<T>.retryOnConnectionError(maxAttemptCount: Long = INFINITE_RETRY): Flow<T> =
        this.retryWhen { error, attempt ->
            if (error.isRetryable() && (maxAttemptCount !in 0..attempt)) {
                delay(2000)
                return@retryWhen true
            } else {
                return@retryWhen false
            }
        }

    private fun Throwable.isRetryable() =
        this !is AdaptyError || this.originalError is IOException || this.adaptyErrorCode in arrayOf(
            AdaptyErrorCode.BILLING_SERVICE_DISCONNECTED,
            AdaptyErrorCode.BILLING_SERVICE_UNAVAILABLE,
            AdaptyErrorCode.BILLING_SERVICE_TIMEOUT
        )
}

private class StoreHelper(private val billingClient: BillingClient) {

    @JvmSynthetic
    fun querySkuDetailsForType(params: SkuDetailsParams) =
        flow {
            val skuDetailsResult = billingClient.querySkuDetails(params)
            if (skuDetailsResult.billingResult.responseCode == OK) {
                emit(
                    skuDetailsResult.skuDetailsList ?: arrayListOf<SkuDetails>()
                )
            } else {
                throwException(skuDetailsResult.billingResult)
            }
        }

    @JvmSynthetic
    fun queryPurchaseHistoryForType(@BillingClient.SkuType type: String) =
        flow {
            val purchaseHistoryResult = billingClient.queryPurchaseHistory(type)
            if (purchaseHistoryResult.billingResult.responseCode == OK) {
                emit(
                    purchaseHistoryResult.purchaseHistoryRecordList
                        ?: arrayListOf<PurchaseHistoryRecord>()
                )
            } else {
                throwException(purchaseHistoryResult.billingResult)
            }
        }

    @JvmSynthetic
    fun acknowledgePurchase(params: AcknowledgePurchaseParams) =
        flow {
            val result = billingClient.acknowledgePurchase(params)
            if (result.responseCode == OK) {
                emit(result)
            } else {
                throwException(result)
            }
        }

    @JvmSynthetic
    fun consumePurchase(params: ConsumeParams) =
        flow {
            val result = billingClient.consumePurchase(params).billingResult
            if (result.responseCode == OK) {
                emit(result)
            } else {
                throwException(result)
            }
        }

    private fun throwException(billingResult: BillingResult) {
        throw AdaptyError(
            message = "Play Market request failed: ${billingResult.debugMessage}",
            adaptyErrorCode = AdaptyErrorCode.fromBilling(billingResult.responseCode)
        )
    }
}

private typealias MakePurchaseCallback = (purchase: Purchase?, error: AdaptyError?) -> Unit

private class MakePurchaseCallbackWrapper(
    private val productId: String,
    private val callback: MakePurchaseCallback
) : MakePurchaseCallback {

    private val wasInvoked = AtomicBoolean(false)

    override operator fun invoke(purchase: Purchase?, error: AdaptyError?) {
        if (purchase?.skus?.firstOrNull()?.equals(productId) != false) {
            if (wasInvoked.compareAndSet(false, true)) {
                callback.invoke(if (error == null) purchase else null, error)
            }
        }
    }
}