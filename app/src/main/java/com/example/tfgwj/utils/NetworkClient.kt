package com.example.tfgwj.utils

import android.util.Log
import com.example.tfgwj.BuildConfig
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * NetworkClient - V7.0.0 Network Layer Upgrade
 *
 * Provides a centralized OkHttpClient with:
 * - Connection pooling (5 connections, 5 minutes keep-alive)
 * - Flexible timeout configuration
 * - Logging interceptor for debugging
 * - ETag support for cache efficiency
 *
 * Replaces legacy HttpURLConnection with OkHttp for:
 * - HTTP/2 support
 * - Better connection management
 * - Simplified async operations
 */
object NetworkClient {
    private const val TAG = "NetworkClient"

    // Connection pool settings
    private const val MAX_IDLE_CONNECTIONS = 5
    private const val KEEP_ALIVE_DURATION_MINUTES = 5L

    // Timeout settings
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    // Singleton instance - lazily initialized
    @Volatile
    private var client: OkHttpClient? = null

    /**
     * Get singleton OkHttpClient instance
     * Thread-safe with double-checked locking
     */
    fun getClient(cacheDir: File? = null): OkHttpClient {
        return client ?: synchronized(this) {
            client ?: buildClient(cacheDir).also { client = it }
        }
    }

    /**
     * Build OkHttpClient with optimized settings
     */
    private fun buildClient(cacheDir: File?): OkHttpClient {
        val builder =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectionPool(
                    ConnectionPool(
                        MAX_IDLE_CONNECTIONS,
                        KEEP_ALIVE_DURATION_MINUTES,
                        TimeUnit.MINUTES,
                    ),
                )
                .retryOnConnectionFailure(true)

        // Add cache if directory provided
        cacheDir?.let { dir ->
            val cacheDirectory = File(dir, "http_cache")
            val cacheSize = 10L * 1024 * 1024 // 10 MB cache
            builder.cache(Cache(cacheDirectory, cacheSize))
        }

        // Add logging interceptor in debug builds
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(LoggingInterceptor())
        }

        return builder.build()
    }

    /**
     * Logging interceptor for debugging network requests
     */
    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startTime = System.nanoTime()

            Log.d(TAG, "┌────────────────────────────────────────────────────────")
            Log.d(TAG, "│ REQUEST: ${request.method} ${request.url}")
            Log.d(TAG, "│ Headers: ${request.headers}")

            val response = chain.proceed(request)
            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000

            Log.d(TAG, "│ RESPONSE: ${response.code} (${durationMs}ms)")
            Log.d(TAG, "│ Cache: ${response.cacheResponse != null}, Network: ${response.networkResponse != null}")
            Log.d(TAG, "└────────────────────────────────────────────────────────")

            return response
        }
    }

    /**
     * Create request builder with common headers
     * V7.0.0: Added ETag support for cache efficiency
     */
    fun createRequest(
        url: String,
        etag: String? = null,
    ): Request.Builder {
        return Request.Builder()
            .url(url)
            .apply {
                // Add common headers
                header("User-Agent", "tfgwj/${BuildConfig.VERSION_NAME}")
                header("Accept", "application/json, text/plain, */*")

                // Add ETag for conditional requests (cache efficiency)
                etag?.let { header("If-None-Match", it) }
            }
    }

    /**
     * Download request with Range header support
     * V7.0.0: Added for resume download functionality
     */
    fun createDownloadRequest(
        url: String,
        rangeStart: Long = 0,
    ): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("User-Agent", "tfgwj/${BuildConfig.VERSION_NAME}")
            .apply {
                if (rangeStart > 0) {
                    header("Range", "bytes=$rangeStart-")
                }
            }
    }

    /**
     * GitHub API request with Accept header
     */
    fun createGitHubApiRequest(url: String): Request.Builder {
        return createRequest(url)
            .header("Accept", "application/vnd.github.v3+json")
    }

    /**
     * Clear connection pool (for testing or memory management)
     */
    fun clearConnectionPool() {
        client?.connectionPool?.evictAll()
        Log.i(TAG, "Connection pool cleared")
    }
}
