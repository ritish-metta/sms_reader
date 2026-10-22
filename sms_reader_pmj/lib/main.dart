import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SMS Reader',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const NumbersPage(),
    );
  }
}

const methodChannel = MethodChannel('com.example.sms_reader/sms_methods');
const eventChannel  = EventChannel('com.example.sms_reader/sms_events');

// ─────────────────────────────────────────────────────────────
//  Global SMS stream — single subscription, app-wide
// ─────────────────────────────────────────────────────────────
final smsStream = eventChannel.receiveBroadcastStream();

// ================================================
//  NUMBERS PAGE
// ================================================
class NumbersPage extends StatefulWidget {
  const NumbersPage({super.key});
  @override
  State<NumbersPage> createState() => _NumbersPageState();
}

class _NumbersPageState extends State<NumbersPage> {
  List<Map<String, String>> _numbers = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _requestPermissions().then((_) => _loadNumbers());
  }

  Future<void> _requestPermissions() async {
    await [Permission.sms, Permission.notification].request();
  }

  Future<void> _loadNumbers() async {
    try {
      final List result =
          await methodChannel.invokeMethod('getAllTargetNumbers');
      setState(() {
        _numbers =
            result.map((e) => Map<String, String>.from(e as Map)).toList();
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
    }
  }

  Future<void> _addNumber() async {
    final labelController = TextEditingController();
    final phoneController = TextEditingController();

    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Add Target Number'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: labelController,
              decoration: const InputDecoration(
                labelText: 'Label (e.g. John)',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: phoneController,
              keyboardType: TextInputType.phone,
              decoration: const InputDecoration(
                labelText: 'Phone (e.g. +916303852399)',
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () async {
              final label = labelController.text.trim();
              final phone = phoneController.text.trim();
              if (label.isEmpty || phone.isEmpty) return;
              final numberId =
                  'number_${DateTime.now().millisecondsSinceEpoch}';

              // ✅ addTargetNumber also triggers syncInboxMessages on Android side
              await methodChannel.invokeMethod('addTargetNumber', {
                'numberId': numberId,
                'label':    label,
                'phone':    phone,
              });
              if (ctx.mounted) Navigator.pop(ctx);
              await _loadNumbers();
            },
            child: const Text('Add'),
          ),
        ],
      ),
    );
  }

  Future<void> _deleteNumber(String numberId, String label) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Number'),
        content: Text('Delete "$label" and all its messages?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
    if (confirm == true) {
      await methodChannel
          .invokeMethod('deleteTargetNumber', {'numberId': numberId});
      await _loadNumbers();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('SMS Reader'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loadNumbers,
            tooltip: 'Refresh',
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _numbers.isEmpty
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Icon(Icons.phone_disabled,
                          size: 64, color: Colors.grey),
                      const SizedBox(height: 16),
                      const Text(
                        'No numbers added yet\nTap + to add a target number',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.grey, fontSize: 16),
                      ),
                      const SizedBox(height: 24),
                      ElevatedButton.icon(
                        onPressed: _addNumber,
                        icon: const Icon(Icons.add),
                        label: const Text('Add Number'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.deepPurple,
                          foregroundColor: Colors.white,
                        ),
                      ),
                    ],
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.all(12),
                  itemCount: _numbers.length,
                  itemBuilder: (context, index) {
                    final num = _numbers[index];
                    return Card(
                      margin: const EdgeInsets.only(bottom: 12),
                      elevation: 3,
                      child: ListTile(
                        onTap: () async {
                          await Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => MessagesPage(
                                numberId: num['number_id'] ?? '',
                                label:    num['label']     ?? '',
                                phone:    num['phone']     ?? '',
                              ),
                            ),
                          );
                          // Refresh count badge when coming back
                          _loadNumbers();
                        },
                        leading: CircleAvatar(
                          backgroundColor: Colors.deepPurple,
                          child: Text(
                            (num['label'] ?? '?')[0].toUpperCase(),
                            style: const TextStyle(color: Colors.white),
                          ),
                        ),
                        title: Text(
                          num['label'] ?? '',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        subtitle: Text(num['phone'] ?? ''),
                        trailing: IconButton(
                          icon: const Icon(Icons.delete, color: Colors.red),
                          onPressed: () => _deleteNumber(
                            num['number_id'] ?? '',
                            num['label']     ?? '',
                          ),
                        ),
                      ),
                    );
                  },
                ),
      floatingActionButton: FloatingActionButton(
        onPressed: _addNumber,
        backgroundColor: Colors.deepPurple,
        child: const Icon(Icons.add, color: Colors.white),
      ),
    );
  }
}

// ================================================
//  MESSAGES PAGE
// ================================================
class MessagesPage extends StatefulWidget {
  final String numberId;
  final String label;
  final String phone;

  const MessagesPage({
    super.key,
    required this.numberId,
    required this.label,
    required this.phone,
  });

  @override
  State<MessagesPage> createState() => _MessagesPageState();
}

class _MessagesPageState extends State<MessagesPage> {
  List<Map<String, String>> _messages = [];
  bool  _isLoading  = true;
  bool  _isSyncing  = false;
  String _status    = '';

  @override
  void initState() {
    super.initState();
    // ✅ FIX 1: Load messages immediately when page opens — no tap needed
    _loadFromDb();
    // ✅ FIX 2: Listen for live new SMS — zero polling, instant update
    _listenForNewSms();
  }

  // ── Load from local SQLite instantly ────────────────────────
  Future<void> _loadFromDb() async {
    try {
      final List result = await methodChannel.invokeMethod(
        'getMessagesByNumberId',
        {'numberId': widget.numberId},
      );
      if (!mounted) return;
      setState(() {
        _messages = result
            .map((e) => Map<String, String>.from(e as Map))
            .toList();
        _isLoading = false;
        _status    = 'Total: ${_messages.length} messages';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _status    = 'Error: $e';
      });
    }
  }

  // ── Live listener: new SMS arrives → instant UI update ──────
  void _listenForNewSms() {
    smsStream.listen((event) {
      if (!mounted) return;
      final msg = Map<String, String>.from(event as Map);

      // Only care about messages for THIS number
      if (msg['number_id'] != widget.numberId) return;

      final isDuplicate = _messages.any(
        (m) =>
            m['message']   == msg['message'] &&
            m['timestamp'] == msg['timestamp'],
      );
      if (isDuplicate) return;

      setState(() {
        _messages.insert(0, msg);   // prepend — newest first
        _status = '🔴 New SMS! Total: ${_messages.length}';
      });
    });
  }

  // ── Manual sync (refresh button) ────────────────────────────
  Future<void> _syncInbox() async {
    if (_isSyncing) return;
    setState(() { _isSyncing = true; _status = 'Syncing inbox...'; });
    try {
      await methodChannel.invokeMethod('syncInboxMessages');
      await _loadFromDb();
    } catch (e) {
      if (!mounted) return;
      setState(() { _status = 'Sync error: $e'; });
    } finally {
      if (mounted) setState(() => _isSyncing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.label),
            Text(widget.phone, style: const TextStyle(fontSize: 12)),
          ],
        ),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          IconButton(
            icon: _isSyncing
                ? const SizedBox(
                    width: 18, height: 18,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Colors.white),
                  )
                : const Icon(Icons.sync),
            onPressed: _isSyncing ? null : _syncInbox,
            tooltip: 'Sync Inbox',
          ),
        ],
      ),
      body: Column(
        children: [
          // Status bar
          if (_status.isNotEmpty)
            Container(
              width: double.infinity,
              color: _status.contains('🔴')
                  ? Colors.green.shade50
                  : Colors.deepPurple.shade50,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Text(
                _status,
                style: TextStyle(
                  fontSize: 12,
                  color: _status.contains('🔴')
                      ? Colors.green
                      : Colors.deepPurple,
                ),
              ),
            ),

          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : _messages.isEmpty
                    ? Center(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const Icon(Icons.sms_outlined,
                                size: 64, color: Colors.grey),
                            const SizedBox(height: 16),
                            const Text(
                              'No messages yet\nPress sync to load from inbox',
                              textAlign: TextAlign.center,
                              style:
                                  TextStyle(color: Colors.grey, fontSize: 16),
                            ),
                            const SizedBox(height: 24),
                            ElevatedButton.icon(
                              onPressed: _isSyncing ? null : _syncInbox,
                              icon: const Icon(Icons.sync),
                              label: const Text('Sync Inbox Now'),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.deepPurple,
                                foregroundColor: Colors.white,
                              ),
                            ),
                          ],
                        ),
                      )
                    : ListView.builder(
                        padding: const EdgeInsets.all(12),
                        itemCount: _messages.length,
                        itemBuilder: (context, index) {
                          final msg = _messages[index];
                          return Card(
                            margin: const EdgeInsets.only(bottom: 10),
                            elevation: 2,
                            child: Padding(
                              padding: const EdgeInsets.all(14),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    children: [
                                      const Icon(Icons.sms,
                                          size: 16, color: Colors.deepPurple),
                                      const SizedBox(width: 6),
                                      Text(
                                        msg['sender'] ?? '',
                                        style: const TextStyle(
                                          fontWeight: FontWeight.bold,
                                          color: Colors.deepPurple,
                                          fontSize: 12,
                                        ),
                                      ),
                                      const Spacer(),
                                      Text(
                                        msg['timestamp'] ?? '',
                                        style: const TextStyle(
                                            fontSize: 11, color: Colors.grey),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 8),
                                  Text(msg['message'] ?? '',
                                      style: const TextStyle(fontSize: 15)),
                                ],
                              ),
                            ),
                          );
                        },
                      ),
          ),
        ],
      ),
    );
  }
}