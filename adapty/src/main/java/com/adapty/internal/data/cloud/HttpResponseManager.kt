package com.adapty.internal.data.cloud

import androidx.annotation.RestrictTo
import com.adapty.errors.AdaptyError
import com.adapty.errors.AdaptyErrorCode
import com.adapty.internal.data.cache.CacheRepository
import com.adapty.internal.data.cache.ResponseCacheKeys
import com.adapty.internal.utils.NetworkLogger
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.util.zip.GZIPInputStream

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
internal interface HttpResponseManager {

    fun <T> handleResponse(
        connection: HttpURLConnection,
        responseCacheKeys: ResponseCacheKeys?,
        classOfT: Class<T>
    ): Response<T>
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
internal class DefaultHttpResponseManager(
    private val bodyConverter: ResponseBodyConverter,
    private val cacheRepository: CacheRepository,
    private val networkLogger: NetworkLogger,
) : HttpResponseManager {

    override fun <T> handleResponse(
        connection: HttpURLConnection,
        responseCacheKeys: ResponseCacheKeys?,
        classOfT: Class<T>
    ): Response<T> {
        val isInGzip =
            connection.getHeaderField("Content-Encoding")?.contains("gzip", true) ?: false

        if (connection.isSuccessful()) {
            val response = connection.evaluateSuccessfulResponse(isInGzip, responseCacheKeys)
            networkLogger.logResponse { "Request is successful. ${connection.url} Response: $response" }
            return bodyConverter.convertSuccess(response, classOfT)

        } else {
            val response = toStringUtf8(connection.errorStream, isInGzip)
            val errorMessage =
                "Request is unsuccessful. ${connection.url} Code: ${connection.responseCode}, Response: $response"
            networkLogger.logError { errorMessage }
            return Response.Error(
                AdaptyError(
                    message = errorMessage,
                    adaptyErrorCode = AdaptyErrorCode.fromNetwork(connection.responseCode)
                )
            )
        }
    }

    private fun toStringUtf8(inputStream: InputStream, isInGzip: Boolean): String {
        val reader = BufferedReader(
            InputStreamReader(
                if (isInGzip) GZIPInputStream(inputStream) else inputStream,
                Charsets.UTF_8
            )
        )
        val total = StringBuilder()
        var line: String? = reader.readLine()
        while (line != null) {
            total.append(line).append('\n')
            line = reader.readLine()
        }
        return total.toString()
    }

    private fun HttpURLConnection.isSuccessful() = responseCode in 200..299

    private fun HttpURLConnection.evaluateSuccessfulResponse(
        isInGzip: Boolean,
        responseCacheKeys: ResponseCacheKeys?,
    ): String {
        val previousResponseHash = getRequestProperty("ADAPTY-SDK-PREVIOUS-RESPONSE-HASH")
        val currentResponseHash = getHeaderField("X-Response-Hash")

        return if (!previousResponseHash.isNullOrEmpty() && previousResponseHash == currentResponseHash) {
            responseCacheKeys?.responseKey?.let(cacheRepository::getString)
                ?: toStringUtf8(inputStream, isInGzip)
        } else {
            toStringUtf8(inputStream, isInGzip).also { response ->
                if (responseCacheKeys != null && currentResponseHash != null) {
                    cacheRepository.saveRequestOrResponseLatestData(
                        mapOf(
                            responseCacheKeys.responseKey to response,
                            responseCacheKeys.responseHashKey to currentResponseHash
                        )
                    )
                }
            }
        }
    }
}