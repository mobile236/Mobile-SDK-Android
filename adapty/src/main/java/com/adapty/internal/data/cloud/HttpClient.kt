package com.adapty.internal.data.cloud

import androidx.annotation.RestrictTo

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
internal interface HttpClient {

    fun <T> newCall(request: Request, classOfT: Class<T>): Response<T>
}