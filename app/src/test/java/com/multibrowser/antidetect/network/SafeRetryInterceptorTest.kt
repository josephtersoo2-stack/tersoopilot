package com.multibrowser.antidetect.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class SafeRetryInterceptorTest {

    private class FakeChain(
        private val request: Request,
        private val responseHandler: (Request, Int) -> Response
    ) : Interceptor.Chain {
        var callCount = 0

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            callCount++
            return responseHandler(request, callCount)
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 1000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 1000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private fun createResponse(request: Request, code: Int): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Server Error")
            .body("test".toResponseBody("text/plain".toMediaType()))
            .build()
    }

    @Test
    fun testGetRetriesOn503ServerFailureAndSucceeds() {
        val request = Request.Builder()
            .url("https://example.com/api/settings/global/")
            .get()
            .build()

        val chain = FakeChain(request) { req, count ->
            if (count == 1) {
                createResponse(req, 503)
            } else {
                createResponse(req, 200)
            }
        }

        val interceptor = SafeRetryInterceptor(maxRetries = 2)
        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals("Safe GET request should retry and succeed on 2nd attempt", 2, chain.callCount)
    }

    @Test
    fun testPostAutomationNeverRetries() {
        val request = Request.Builder()
            .url("https://example.com/api/automation/run/")
            .post(RequestBody.create("application/json".toMediaType(), "{}"))
            .build()

        val chain = FakeChain(request) { _, _ ->
            throw IOException("Network dropped")
        }

        val interceptor = SafeRetryInterceptor(maxRetries = 2)
        try {
            interceptor.intercept(chain)
            fail("Expected IOException to be thrown immediately")
        } catch (_: IOException) {
            assertEquals("POST automation action must never be retried", 1, chain.callCount)
        }
    }

    @Test
    fun testPostGeneralActionNeverRetried() {
        val request = Request.Builder()
            .url("https://example.com/api/sync/push/")
            .post(RequestBody.create("application/json".toMediaType(), "{}"))
            .build()

        val chain = FakeChain(request) { req, _ ->
            createResponse(req, 500)
        }

        val interceptor = SafeRetryInterceptor(maxRetries = 2)
        val response = interceptor.intercept(chain)

        assertEquals(500, response.code)
        assertEquals("POST request should not be retried even on 500 status", 1, chain.callCount)
    }

    @Test
    fun testClientError404NotRetried() {
        val request = Request.Builder()
            .url("https://example.com/api/devices/models/?provider=unknown")
            .get()
            .build()

        val chain = FakeChain(request) { req, _ ->
            createResponse(req, 404)
        }

        val interceptor = SafeRetryInterceptor(maxRetries = 2)
        val response = interceptor.intercept(chain)

        assertEquals(404, response.code)
        assertEquals("4xx client errors should not be retried", 1, chain.callCount)
    }
}
