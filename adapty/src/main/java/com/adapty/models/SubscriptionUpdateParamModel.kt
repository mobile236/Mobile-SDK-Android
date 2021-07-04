package com.adapty.models

class SubscriptionUpdateParamModel(
    val oldSubVendorProductId: String,
    val prorationMode: ProrationMode,
) {

    enum class ProrationMode {
        IMMEDIATE_WITH_TIME_PRORATION,
        IMMEDIATE_AND_CHARGE_PRORATED_PRICE,
        IMMEDIATE_WITHOUT_PRORATION,
        DEFERRED,
        IMMEDIATE_AND_CHARGE_FULL_PRICE,
    }

    override fun toString(): String {
        return "SubscriptionUpdateParamModel(oldSubVendorProductId='$oldSubVendorProductId', prorationMode=$prorationMode)"
    }
}