package com.example.sms_reader_pmj

import android.net.Uri
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        const val EVENT_CHANNEL = "com.example.sms_reader/sms_events"
        const val METHOD_CHANNEL = "com.example.sms_reader/sms_methods"
        var eventSink: EventChannel.EventSink? = null
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // ✅ Start background sync worker as soon as app opens
        SmsSync.schedulePeriodicSync(this)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
                    eventSink = sink
                    SmsReceiver.eventSinkCallback = { messageMap ->
                        runOnUiThread { eventSink?.success(messageMap) }
                    }
                    // ✅ Push ALL existing messages to Flutter on connect
                    val db = DatabaseHelper(this@MainActivity)
                    for (msg in db.getAllMessages()) sink?.success(msg)
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                    SmsReceiver.eventSinkCallback = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    "getAllMessages" -> {
                        val db = DatabaseHelper(this)
                        result.success(db.getAllMessages())
                    }

                    "getMessagesByNumberId" -> {
                        val numberId = call.argument<String>("numberId") ?: ""
                        val db = DatabaseHelper(this)
                        result.success(db.getMessagesByNumberId(numberId))
                    }

                    "deleteAllMessages" -> {
                        DatabaseHelper(this).deleteAllMessages()
                        result.success(null)
                    }

                    "syncInboxMessages" -> {
                        try {
                            result.success(syncInboxMessages())
                        } catch (e: Exception) {
                            result.error("SYNC_ERROR", e.message, null)
                        }
                    }

                    "addTargetNumber" -> {
                        val numberId = call.argument<String>("numberId") ?: ""
                        val label    = call.argument<String>("label")    ?: ""
                        val phone    = call.argument<String>("phone")    ?: ""
                        val db = DatabaseHelper(this)
                        val success = db.addTargetNumber(numberId, label, phone)
                        // ✅ Immediately sync inbox for the new number
                        syncInboxMessages()
                        SmsSync.schedulePeriodicSync(this)
                        result.success(success)
                    }

                    "getAllTargetNumbers" -> {
                        val db = DatabaseHelper(this)
                        result.success(db.getAllTargetNumbers())
                    }

                    "deleteTargetNumber" -> {
                        val numberId = call.argument<String>("numberId") ?: ""
                        DatabaseHelper(this).deleteTargetNumber(numberId)
                        result.success(null)
                    }

                    "debugInbox" -> {
                        val uri = android.net.Uri.parse("content://sms/inbox")
                        val cursor = contentResolver.query(
                            uri, arrayOf("address", "body", "date"),
                            null, null, "date DESC"
                        )
                        val results = mutableListOf<String>()
                        cursor?.use {
                            while (it.moveToNext()) {
                                val address = it.getString(0) ?: "null"
                                val body    = it.getString(1)?.take(20) ?: ""
                                results.add("[$address] $body")
                            }
                        }
                        result.success(results)
                    }

                    else -> result.notImplemented()
                }
            }
    }

    // ✅ Reads device SMS inbox, saves to SQLite (synced=0), worker pushes to backend
    private fun syncInboxMessages(): List<Map<String, String>> {
        val db = DatabaseHelper(this)
        val uri = Uri.parse("content://sms/inbox")
        val cursor = contentResolver.query(
            uri, arrayOf("address", "body", "date"),
            null, null, "date DESC"
        ) ?: return emptyList()

        val targetNumbers = db.getAllTargetNumbers()
            .associate { it["number_id"]!! to it["phone"]!! }

        val synced = mutableListOf<Map<String, String>>()
        cursor.use {
            while (it.moveToNext()) {
                val sender    = it.getString(it.getColumnIndexOrThrow("address")) ?: continue
                val body      = it.getString(it.getColumnIndexOrThrow("body"))    ?: continue
                val dateMs    = it.getLong(it.getColumnIndexOrThrow("date"))
                val numberId  = SmsSync.getNumberId(sender, targetNumbers)        ?: continue
                val timestamp = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date(dateMs))

                // Insert locally with synced=0 — worker will push to backend
                val rowId = db.insertMessage(sender, body, timestamp, numberId, synced = 0)
                if (rowId > 0) {
                    synced.add(mapOf(
                        "sender"    to sender,
                        "message"   to body,
                        "timestamp" to timestamp,
                        "number_id" to numberId
                    ))
                }
            }
        }

        // ✅ Kick an immediate sync after loading inbox
        SmsSync.schedulePeriodicSync(this)
        return synced
    }
}