import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'native_sms_models.dart';

/// A deliberately separate transport: sending an SMS never calls RustPush,
/// Apple IDS, or the iPhone registration relay.
class NativeSmsService extends ChangeNotifier {
  static final NativeSmsService instance = NativeSmsService();
  NativeSmsService({MethodChannel? channel})
      : _channel =
            channel ?? const MethodChannel('com.openbubbles/native_sms') {
    _channel.setMethodCallHandler(_onNativeCall);
  }

  static const pageSize = 200;
  final MethodChannel _channel;
  final Map<int, NativeSmsMessage> _messages = {};
  final Set<String> _sending = {};
  bool _disposed = false;
  bool _refreshAgain = false;
  Future<void>? _refreshing;
  Future<void>? _loadingOlder;

  NativeSmsState state = const NativeSmsState.unavailable();
  List<NativeSmsSubscription> subscriptions = [];
  bool loading = false;
  bool hasOlder = true;
  String? error;

  List<NativeSmsMessage> get messages =>
      _messages.values.toList()..sort((a, b) => b.date.compareTo(a.date));
  List<NativeSmsConversation> get conversations =>
      groupSmsConversations(_messages.values);

  Future<void> _onNativeCall(MethodCall call) async {
    if (call.method == 'nativeSmsChanged') {
      await refresh();
    }
  }

  void _notify() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  /// Refreshing updates existing IDs in place so a pending message becomes
  /// sent/failed instead of appearing twice when a carrier callback arrives.
  Future<void> refresh() {
    if (_disposed) {
      return Future.value();
    }
    if (_refreshing != null) {
      _refreshAgain = true;
      return _refreshing!;
    }
    final completer = Completer<void>();
    _refreshing = completer.future;
    () async {
      do {
        _refreshAgain = false;
        loading = true;
        _notify();
        try {
          final result =
              await _channel.invokeMapMethod<dynamic, dynamic>('getState');
          state = NativeSmsState.fromMap(result ?? {});
          if (state.supported && state.permissionsGranted) {
            final sims =
                await _channel.invokeListMethod<dynamic>('getSubscriptions');
            subscriptions = (sims ?? [])
                .map((s) => NativeSmsSubscription.fromMap(s as Map))
                .toList();
            final page = await _readPage();
            final refreshedIds = page.map((m) => m.id).toSet();
            final olderIds = _messages.keys
                .where((id) => id >= 0 && !refreshedIds.contains(id))
                .toList();
            // Requery loaded history by ID. Provider deletes and read/status
            // changes must not leave stale rows in an already-open inbox.
            for (var offset = 0; offset < olderIds.length; offset += 500) {
              final ids =
                  olderIds.sublist(offset, min(offset + 500, olderIds.length));
              final rows = await _channel
                  .invokeListMethod<dynamic>('listMessages', {'ids': ids});
              for (final id in ids) {
                _messages.remove(id);
              }
              for (final row in rows ?? []) {
                final message = NativeSmsMessage.fromMap(row as Map);
                _messages[message.id] = message;
              }
            }
            // Native outbox entries use negative IDs until Android creates a
            // provider row. Replace that whole temporary set on every refresh.
            _messages.removeWhere((id, _) => id < 0);
            for (final item in page) {
              _messages[item.id] = item;
            }
            final providerCount = page.where((m) => m.id >= 0).length;
            if (_messages.keys.where((id) => id >= 0).length <= pageSize) {
              hasOlder = providerCount == pageSize;
            }
          } else {
            subscriptions = [];
            _messages.clear();
          }
          error = null;
        } on MissingPluginException {
          state = const NativeSmsState.unavailable();
          subscriptions = [];
          _messages.clear();
          error = 'This build does not include the Android SMS component.';
        } catch (e) {
          _recordError(e);
        } finally {
          loading = false;
          _notify();
        }
      } while (_refreshAgain && !_disposed);
      _refreshing = null;
      completer.complete();
    }();
    return completer.future;
  }

  Future<List<NativeSmsMessage>> _readPage({int? beforeId}) async {
    final result = await _channel.invokeListMethod<dynamic>('listMessages', {
      'limit': pageSize,
      if (beforeId != null) 'beforeId': beforeId,
    });
    return (result ?? [])
        .map((m) => NativeSmsMessage.fromMap(m as Map))
        .toList();
  }

  Future<void> loadOlder() {
    if (_loadingOlder != null) {
      return _loadingOlder!;
    }
    if (!hasOlder || _disposed || !state.permissionsGranted) {
      return Future.value();
    }
    final completer = Completer<void>();
    _loadingOlder = completer.future;
    () async {
      try {
        final providerIds = _messages.keys.where((id) => id >= 0);
        final oldest = providerIds.isEmpty ? null : providerIds.reduce(min);
        final page = await _readPage(beforeId: oldest);
        for (final item in page) {
          _messages[item.id] = item;
        }
        hasOlder = page.where((m) => m.id >= 0).length == pageSize;
        error = null;
      } catch (e) {
        _recordError(e);
      } finally {
        _loadingOlder = null;
        _notify();
        completer.complete();
      }
    }();
    return completer.future;
  }

  Future<void> requestPermissions() async {
    await _channel.invokeMethod<bool>('requestPermissions');
    await refresh();
  }

  Future<void> requestDefault() async {
    if (!state.canRequestDefault) {
      return;
    }
    await _channel.invokeMethod<bool>('requestDefault');
    await refresh();
  }

  /// The caller retains this ID after an uncertain result; native code can
  /// deduplicate repeated requests instead of charging/sending twice.
  String newClientId() {
    final random = Random.secure();
    return List.generate(
            16, (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'))
        .join();
  }

  Future<NativeSmsMessage> send({
    required String address,
    required String body,
    required int subscriptionId,
    required String clientId,
  }) async {
    if (!state.permissionsGranted) {
      throw StateError('Allow SMS access before sending.');
    }
    if (address.trim().isEmpty || body.trim().isEmpty) {
      throw ArgumentError('Enter a phone number and a message.');
    }
    if (!subscriptions.any((s) => s.id == subscriptionId)) {
      throw StateError('Choose an active SIM before sending.');
    }
    if (!_sending.add(clientId)) {
      throw StateError('This SMS is already being submitted.');
    }
    try {
      final result =
          await _channel.invokeMapMethod<dynamic, dynamic>('sendSms', {
        'address': address.trim(),
        'body': body,
        'subscriptionId': subscriptionId,
        'clientId': clientId,
      });
      if (result == null || result['id'] == null) {
        throw StateError(
            'Submission status is unknown. Refresh the conversation before retrying.');
      }
      final message = NativeSmsMessage.fromMap(result);
      _messages[message.id] = message;
      _notify();
      return message;
    } finally {
      _sending.remove(clientId);
      unawaited(refresh());
    }
  }

  Future<void> markRead(Iterable<NativeSmsMessage> messages) async {
    final ids =
        messages.where((m) => m.incoming && !m.read).map((m) => m.id).toList();
    if (ids.isEmpty) {
      return;
    }
    await _channel.invokeMethod<void>('markRead', {'ids': ids});
    await refresh();
  }

  static String _errorText(Object error) => error is PlatformException
      ? error.message ?? error.code
      : error.toString();

  void _recordError(Object exception) {
    if (exception is PlatformException &&
        exception.code == 'SMS_PERMISSION_DENIED') {
      state = NativeSmsState(
        supported: state.supported,
        isDefault: state.isDefault,
        permissionsGranted: false,
        canRequestDefault: state.canRequestDefault,
      );
      subscriptions = [];
      _messages.clear();
    }
    error = _errorText(exception);
  }

  @override
  void dispose() {
    _disposed = true;
    _channel.setMethodCallHandler(null);
    super.dispose();
  }
}
