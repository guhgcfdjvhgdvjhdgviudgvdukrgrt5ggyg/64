package com.ghosttype.security

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object ServerGate {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json".toMediaType()

    /** POST /verify — server batata hai app allowed hai ya nahi */
    fun verify(ctx: Context): Boolean {
        return try {
            val workerUrl = Obf.decode(ObfConstants.SERVER_GATE_URL)
            if (workerUrl.isEmpty()) return false
            val signature = Obf.currentSigningSha(ctx)
            val deviceId = DeviceId.get(ctx)
            val body = JSONObject().apply {
                put("signature", signature)
                put("device_id", deviceId)
            }.toString()

            val request = Request.Builder()
                .url("$workerUrl/verify")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return false

            val json = JSONObject(response.body!!.string())
            json.optBoolean("allowed", false)
        } catch (e: Exception) {
            false
        }
    }
}
