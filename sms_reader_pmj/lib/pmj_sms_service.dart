// import 'dart:convert';
// import 'dart:io';
// import 'package:flutter/services.dart';
// import 'package:http/http.dart' as http;

// class PmjSmsService {
//   static const String baseUrl =
//       'https://c351-103-52-38-143.ngrok-free.app';
//   static const String endpoint = '/api/pmj-sms/receive';

//   static const methodChannel =
//       MethodChannel('com.example.sms_reader/sms_methods');

//   // ✅ Send single SMS to backend
//   static Future<bool> sendSmsToBackend({
//     required String sender,
//     required String message,
//     required String timestamp,
//     String? deviceId,
//   }) async {
//     try {
//       final response = await http.post(
//         Uri.parse('$baseUrl$endpoint'),
//         headers: {
//           'Content-Type': 'application/json',
//           'ngrok-skip-browser-warning': 'true',
//         },
//         body: jsonEncode({
//           'sender': sender,
//           'message': message,
//           'timestamp': timestamp,
//           'deviceId': deviceId ?? await _getDeviceId(),
//         }),
//       ).timeout(const Duration(seconds: 10));

//       if (response.statusCode == 200) {
//         print('✅ SMS sent to backend: $message');
//         return true;
//       } else {
//         print('❌ Backend error: ${response.statusCode}');
//         return false;
//       }
//     } catch (e) {
//       print('❌ Send failed: $e');
//       return false;
//     }
//   }

//   // ✅ Sync all messages from SQLite to backend
//   static Future<void> syncAllToBackend() async {
//     try {
//       final List result =
//           await methodChannel.invokeMethod('getAllMessages');
//       final messages =
//           result.map((e) => Map<String, String>.from(e as Map)).toList();

//       print('🔄 Syncing ${messages.length} messages to backend...');

//       for (final msg in messages) {
//         await sendSmsToBackend(
//           sender: msg['sender'] ?? '',
//           message: msg['message'] ?? '',
//           timestamp: msg['timestamp'] ?? '',
//         );
//       }
//       print('✅ Sync complete!');
//     } catch (e) {
//       print('❌ Sync error: $e');
//     }
//   }

//   static Future<String> _getDeviceId() async {
//     try {
//       return Platform.localHostname;
//     } catch (e) {
//       return 'unknown_device';
//     }
//   }
// }