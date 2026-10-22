package com.example.sms_reader_pmj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker

object SmsSync {
    const val BACKEND_BASE     = "https://eventease-mobile-backend.onrender.com/api/pmj-sms"
    const val SYNC_ENDPOINT    = "$BACKEND_BASE/sync"
    const val RECEIVE_ENDPOINT = "$BACKEND_BASE/receive"
    const val TAG      = "SmsSync"
    const val WORK_TAG = "sms_sync_worker"

    fun normalizeNumber(number: String) =
        number.replace(Regex("[\\s\\-()]"), "").trim()

    fun getNumberId(sender: String, targetNumbers: Map<String, String>): String? {
        val normalizedSender = normalizeNumber(sender)
        for ((id, number) in targetNumbers) {
            val normalizedTarget = normalizeNumber(number)
            if (normalizedSender == normalizedTarget ||
                normalizedSender.endsWith(normalizedTarget.takeLast(10)) ||
                normalizedTarget.endsWith(normalizedSender.takeLast(10))
            ) return id
        }
        return null
    }

    fun schedulePeriodicSync(context: Context) {
        val request = buildSyncRequest()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_TAG,
            ExistingWorkPolicy.KEEP,
            request
        )
        Log.d(TAG, "🟢 WorkManager sync loop started")
    }

    fun buildSyncRequest(delayMs: Long = 0): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(SmsSyncWorker::class.java)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

    fun httpPost(urlString: String, json: JSONObject, deviceId: String): String? {
        return try {
            val url  = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("ngrok-skip-browser-warning", "true")
            conn.doOutput       = true
            conn.connectTimeout = 8000
            conn.readTimeout    = 8000
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = if (code in 200..299)
                conn.inputStream.bufferedReader().readText()
            else {
                Log.e(TAG, "❌ HTTP $code from backend")
                null
            }
            conn.disconnect()
            body
        } catch (e: Exception) {
            Log.e(TAG, "❌ Network error: ${e.message}")
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  SmsReceiver
// ─────────────────────────────────────────────────────────────────
class SmsReceiver : BroadcastReceiver() {

    companion object {
        var eventSinkCallback: ((Map<String, String>) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        Log.d(SmsSync.TAG, "📡 SMS broadcast received")

        val db = DatabaseHelper(context)
        val targetNumbers = db.getAllTargetNumbers()
            .associate { it["number_id"]!! to it["phone"]!! }

        if (targetNumbers.isEmpty()) {
            Log.w(SmsSync.TAG, "⚠️ No target numbers configured — ignoring SMS")
            return
        }

        Log.d(SmsSync.TAG, "🎯 Watching ${targetNumbers.size} target number(s)")

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (sms in messages) {
            val sender = sms.originatingAddress ?: continue
            val body   = sms.messageBody        ?: continue

            Log.d(SmsSync.TAG, "📩 Incoming SMS from: $sender")

            val numberId = SmsSync.getNumberId(sender, targetNumbers)
            if (numberId == null) {
                Log.d(SmsSync.TAG, "🚫 Sender $sender not in target list — skipped")
                continue
            }

            Log.d(SmsSync.TAG, "✅ Matched target number → numberId=$numberId")

            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
            ).format(Date())

            val rowId = db.insertMessage(
                sender    = sender,
                message   = body,
                timestamp = timestamp,
                numberId  = numberId,
                synced    = 0
            )

            if (rowId > 0) {
                Log.d(SmsSync.TAG, "💾 Saved to SQLite (rowId=$rowId) | synced=0 — pending backend push")
                eventSinkCallback?.invoke(
                    mapOf(
                        "sender"    to sender,
                        "message"   to body,
                        "timestamp" to timestamp,
                        "number_id" to numberId
                    )
                )
                Log.d(SmsSync.TAG, "📲 UI notified instantly via eventSinkCallback")
            } else {
                Log.d(SmsSync.TAG, "⚠️ Duplicate SMS — already in SQLite, skipping")
            }
        }

        Log.d(SmsSync.TAG, "⚡ Kicking immediate sync after SMS received")
        SmsSync.schedulePeriodicSync(context)
    }
}

// ─────────────────────────────────────────────────────────────────
//  SmsSyncWorker — self-chaining every 2 seconds
// ─────────────────────────────────────────────────────────────────
class SmsSyncWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Log.d(SmsSync.TAG, "\n🔁 ======= SYNC CYCLE START =======")

        val db = DatabaseHelper(ctx)
        val deviceId = android.provider.Settings.Secure.getString(
            ctx.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        Log.d(SmsSync.TAG, "📱 Device ID: $deviceId")

        return try {
            performSync(db, deviceId)
            scheduleNext()
            Log.d(SmsSync.TAG, "🔁 ======= SYNC CYCLE DONE ========\n")
            Result.success()
        } catch (e: Exception) {
            Log.e(SmsSync.TAG, "❌ Sync cycle crashed: ${e.message}")
            scheduleNext()
            Result.retry()
        }
    }

    private fun performSync(db: DatabaseHelper, deviceId: String) {

        // 1️⃣ Check local SQLite state
        val localHashes = db.getAllHashes()
        val unsynced    = db.getUnsyncedMessages()
        val allLocal    = db.getAllMessages()

        Log.d(SmsSync.TAG, "🗄️  LOCAL SQLite STATUS:")
        Log.d(SmsSync.TAG, "   📦 Total messages in SQLite : ${allLocal.size}")
        Log.d(SmsSync.TAG, "   ⏳ Unsynced (pending push)  : ${unsynced.size}")
        Log.d(SmsSync.TAG, "   🔐 Total hashes known       : ${localHashes.size}")

        if (allLocal.isEmpty()) {
            Log.w(SmsSync.TAG, "   ⚠️  LOCAL DB IS EMPTY — will pull everything from backend")
        }

        if (unsynced.isEmpty()) {
            Log.d(SmsSync.TAG, "   ✅ All local messages already synced to backend")
        } else {
            Log.d(SmsSync.TAG, "   📤 Messages to push:")
            unsynced.forEach {
                Log.d(SmsSync.TAG, "      ➡️  [${it["sender"]}] \"${it["message"]?.take(30)}...\"")
            }
        }

        // 2️⃣ Build payload
        val payload = JSONObject().apply {
            put("deviceId",    deviceId)
            put("localHashes", JSONArray(localHashes))
            put("messages",    JSONArray().apply {
                unsynced.forEach { row ->
                    put(JSONObject().apply {
                        put("sender",    row["sender"])
                        put("message",   row["message"])
                        put("timestamp", row["timestamp"])
                        put("numberId",  row["number_id"])
                        put("msgHash",   row["msg_hash"])
                    })
                }
            })
        }

        // 3️⃣ Hit backend
        Log.d(SmsSync.TAG, "🌐 Posting to backend /sync ...")
        val responseBody = SmsSync.httpPost(SmsSync.SYNC_ENDPOINT, payload, deviceId)
        if (responseBody == null) {
            Log.w(SmsSync.TAG, "⚠️  No response from backend — will retry next cycle")
            return
        }
        Log.d(SmsSync.TAG, "📬 Backend responded OK")

        // 4️⃣ Parse response
        val json    = JSONObject(responseBody)
        val success = json.optBoolean("success", false)
        if (!success) {
            Log.w(SmsSync.TAG, "❌ Backend returned failure: $responseBody")
            return
        }

        // 5️⃣ Mark confirmed hashes as synced
        val confirmedHashes = json.optJSONArray("confirmedHashes")
        if (confirmedHashes != null && confirmedHashes.length() > 0) {
            val hashes = (0 until confirmedHashes.length()).map { confirmedHashes.getString(it) }
            db.markSynced(hashes)
            Log.d(SmsSync.TAG, "✅ Marked ${hashes.size} message(s) as synced in SQLite")
        } else {
            Log.d(SmsSync.TAG, "   ℹ️  No new confirmations from backend this cycle")
        }

        // 6️⃣ Pull missing messages from backend
        val missingMessages = json.optJSONArray("missingOnDevice")
        if (missingMessages != null && missingMessages.length() > 0) {
            Log.d(SmsSync.TAG, "📥 Backend has ${missingMessages.length()} message(s) missing on device — inserting:")
            for (i in 0 until missingMessages.length()) {
                val msg = missingMessages.getJSONObject(i)
                val inserted = db.insertMessage(
                    sender    = msg.optString("sender"),
                    message   = msg.optString("message"),
                    timestamp = msg.optString("timestamp"),
                    numberId  = msg.optString("numberId", "number_1"),
                    synced    = 1
                )
                if (inserted > 0) {
                    Log.d(SmsSync.TAG, "   ⬇️  Inserted: [${msg.optString("sender")}] \"${msg.optString("message").take(30)}...\"")
                } else {
                    Log.d(SmsSync.TAG, "   ⏭️  Already exists locally: [${msg.optString("sender")}]")
                }
            }
        } else {
            Log.d(SmsSync.TAG, "✅ Device has everything — nothing to pull from backend")
        }

        // 7️⃣ Final status
        val stats = json.optJSONObject("stats")
        if (stats != null) {
            Log.d(SmsSync.TAG, "📊 SYNC STATS — pushed: ${stats.optInt("pushed")} | pulled: ${stats.optInt("pulled")} | knownByDevice: ${stats.optInt("knownByDevice")}")
        }

        Log.d(SmsSync.TAG, "🟢 Local & backend are IN SYNC")

        // 8️⃣ Check if any synced messages were deleted from backend — re-queue them
        val backendHashes = json.optJSONArray("confirmedHashes")
        if (backendHashes != null) {
            val backendHashSet = (0 until backendHashes.length())
                .map { backendHashes.getString(it) }.toSet()

            val allSyncedLocal = db.getAllMessages().filter { it["synced"] == "1" }
            val deletedFromBackend = allSyncedLocal.filter {
                it["msg_hash"] != null && it["msg_hash"] !in backendHashSet
            }

            if (deletedFromBackend.isNotEmpty()) {
                Log.w(SmsSync.TAG, "🔴 ${deletedFromBackend.size} message(s) deleted from backend — resetting to unsynced:")
                deletedFromBackend.forEach {
                    Log.w(SmsSync.TAG, "   🔄 Re-queuing: [${it["sender"]}] \"${it["message"]?.take(30)}...\"")
                }
                db.resetSyncedStatus(deletedFromBackend.mapNotNull { it["msg_hash"] })
            } else {
                Log.d(SmsSync.TAG, "✅ All local synced messages confirmed present in backend")
            }
        }

    } // ← end of performSync()

    private fun scheduleNext() {
        val next = SmsSync.buildSyncRequest(delayMs = 2_000)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SmsSync.WORK_TAG,
            ExistingWorkPolicy.REPLACE,
            next
        )
        Log.d(SmsSync.TAG, "⏰ Next sync scheduled in 2 seconds")
    }
}