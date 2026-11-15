import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

import 'rcs_models.dart';

/// The exception text is chosen here, never copied from account/native payloads.
class RcsException implements Exception {
  const RcsException(this.code, this.message);
  final String code;
  final String message;
  @override
  String toString() => message;
}

/// One app-process subscriber; the Android service owns the connected lifetime.
/// Provider IDs are the sole message deduplication key within each conversation.
class RcsService extends ChangeNotifier {
  RcsService(
      {MethodChannel? channel,
      EventChannel? eventChannel,
      Stream<dynamic>? events})
      : _channel = channel ?? const MethodChannel('com.openbubbles/rcs'),
        _eventChannel =
            eventChannel ?? const EventChannel('com.openbubbles/rcs_events'),
        _events = events;

  static final RcsService instance = RcsService();
  final MethodChannel _channel;
  final EventChannel _eventChannel;
  final Stream<dynamic>? _events;
  StreamSubscription<dynamic>? _subscription;
  Future<void>? _initializing;
  Future<void>? _conversationRead;
  final Map<String, Future<void>> _messageReads = {};
  final Map<String, RcsConversation> _conversations = {};
  final Map<String, Map<String, RcsMessage>> _messages = {};
  final Map<String, int> _liveMessageVersions = {};
  final Map<String, int> _liveConversationVersions = {};
  final Map<String, String> _sendSignatures = {};
  final Map<String, String> _sending = {};
  final Set<String> _pendingMessageReads = {};
  Timer? _refreshTimer;
  bool _pendingInboxRead = false;
  bool _conversationsAgain = false;
  final Set<String> _messagesAgain = {};
  bool _disposed = false;
  bool _commandBusy = false;
  int _generation = 0;
  int _eventVersion = 0;
  int _navigationEvents = 0;
  String? _visibleConversation;

  RcsSnapshot snapshot = const RcsSnapshot();
  String? error;
  final ValueNotifier<String?> notificationConversation = ValueNotifier(null);
  bool get commandBusy => _commandBusy;
  bool get sending => _sending.isNotEmpty;
  bool get loadingConversations => _conversationRead != null;
  bool loadingMessages(String id) => _messageReads.containsKey(id);
  bool isSending(String id) => _sending.containsValue(id);
  List<RcsConversation> get conversations => _conversations.values.toList()
    ..sort((a, b) {
      final date = b.timestampMs.compareTo(a.timestampMs);
      return date == 0 ? a.id.compareTo(b.id) : date;
    });
  RcsConversation? conversation(String id) => _conversations[id];
  List<RcsMessage> messagesFor(String id) =>
      (_messages[id]?.values.toList() ?? <RcsMessage>[])
        ..sort((a, b) {
          final date = a.timestampMs.compareTo(b.timestampMs);
          return date == 0 ? a.id.compareTo(b.id) : date;
        });

  Future<void> initialize() => _initializing ??= _initialize();
  Future<void> _initialize() async {
    if (_disposed) return;
    _subscription = (_events ?? _eventChannel.receiveBroadcastStream()).listen(
      _handleEvent,
      onError: (Object failure) {
        _recordError(failure);
      },
    );
    await refreshSnapshot();
    if (_disposed || !snapshot.supported) return;
    try {
      final pending =
          await _channel.invokeMethod<String>('takePendingConversation');
      if (!_disposed && _navigationEvents == 0 && _validId(pending)) {
        notificationConversation.value = pending;
      }
    } catch (failure) {
      _recordError(failure);
    }
    if (snapshot.ready) await refreshConversations();
  }

  Future<void> refreshSnapshot() async {
    final generation = _generation;
    try {
      final result = await _map('snapshot');
      if (!_current(generation)) return;
      snapshot = RcsSnapshot.fromMap(result);
      error = snapshot.errorCode.isEmpty
          ? null
          : _errorForCode(snapshot.errorCode).message;
      _notify();
    } catch (failure) {
      if (!_current(generation)) return;
      if (failure is MissingPluginException) {
        snapshot = const RcsSnapshot(supported: false);
      }
      _recordError(failure);
    }
  }

  /// Concurrent reads share a future. A live event can schedule one follow-up,
  /// but reading history itself never invalidates the same history again.
  Future<void> refreshConversations() {
    if (_disposed || !snapshot.ready) return Future.value();
    if (_conversationRead != null) {
      _conversationsAgain = true;
      return _conversationRead!;
    }
    final done = Completer<void>();
    _conversationRead = done.future;
    final generation = _generation;
    final startVersion = _eventVersion;
    _notify();
    () async {
      try {
        final response = await _map('conversations');
        if (!_current(generation)) return;
        final records = <String, RcsConversation>{};
        for (final row in _maps(response['conversations'])) {
          final chat = RcsConversation.fromMap(row);
          if (_validId(chat.id)) records[chat.id] = chat;
        }
        for (final entry in _conversations.entries) {
          if ((_liveConversationVersions[entry.key] ?? 0) > startVersion) {
            records[entry.key] = entry.value;
          }
        }
        _conversations
          ..clear()
          ..addAll(records);
        error = null;
      } catch (failure) {
        if (_current(generation)) _recordError(failure);
      } finally {
        _conversationRead = null;
        _notify();
        done.complete();
        if (_conversationsAgain || generation != _generation) {
          _conversationsAgain = false;
          _scheduleRefresh(inbox: true);
        }
      }
    }();
    return done.future;
  }

  Future<void> loadMessages(String conversationId) {
    if (_disposed || !snapshot.ready || !_validId(conversationId)) {
      return Future.value();
    }
    if (_messageReads.containsKey(conversationId)) {
      _messagesAgain.add(conversationId);
      return _messageReads[conversationId]!;
    }
    final done = Completer<void>();
    _messageReads[conversationId] = done.future;
    final generation = _generation;
    final startVersion = _eventVersion;
    _notify();
    () async {
      try {
        final response =
            await _map('messages', {'conversationId': conversationId});
        if (!_current(generation)) return;
        final records = <String, RcsMessage>{};
        for (final row in _maps(response['messages'])) {
          final message = RcsMessage.fromMap(row);
          if (_validId(message.id) &&
              message.conversationId == conversationId) {
            records[message.id] = message;
          }
        }
        for (final message
            in _messages[conversationId]?.values ?? const <RcsMessage>[]) {
          if ((_liveMessageVersions['$conversationId\u0000${message.id}'] ??
                  0) >
              startVersion) records[message.id] = message;
        }
        _messages[conversationId] = records;
        _liveMessageVersions.removeWhere((key, _) =>
            key.startsWith('$conversationId\u0000') &&
            !records.containsKey(key.substring(conversationId.length + 1)));
        final rawChat = response['conversation'];
        if (rawChat is Map &&
            (_liveConversationVersions[conversationId] ?? 0) <= startVersion) {
          final chat = RcsConversation.fromMap(rawChat);
          if (chat.id == conversationId) _conversations[chat.id] = chat;
        }
        error = null;
      } catch (failure) {
        if (_current(generation)) _recordError(failure);
      } finally {
        _messageReads.remove(conversationId);
        _notify();
        done.complete();
        if (_messagesAgain.remove(conversationId) ||
            generation != _generation) {
          _scheduleRefresh(conversationId: conversationId);
        }
      }
    }();
    return done.future;
  }

  Future<void> pair(String cookiesJson) async {
    try {
      if (utf8.encode(cookiesJson).length > 64 * 1024 ||
          jsonDecode(cookiesJson) is! Map) throw const FormatException();
    } catch (_) {
      throw const RcsException('INVALID_SIGN_IN_FILE',
          'Choose the local sign-in JSON file created by the computer helper.');
    }
    await _command('pair', arguments: {'cookiesJson': cookiesJson});
  }

  Future<void> connect() => _command('connect');
  Future<void> disconnect() => _command('disconnect');
  Future<void> forget() async {
    _clearRecords();
    await _command('forget');
  }

  Future<void> setPreviews(bool enabled) => _command('setPreviews',
      arguments: {'enabled': enabled}, invalidateReads: false);

  Future<void> _command(String method,
      {Map<String, Object?>? arguments, bool invalidateReads = true}) async {
    if (_commandBusy) {
      throw const RcsException(
          'BUSY', 'Wait for the current connection action to finish.');
    }
    if (invalidateReads) {
      _generation++;
      _refreshTimer?.cancel();
      _refreshTimer = null;
      _pendingInboxRead = false;
      _pendingMessageReads.clear();
    }
    _commandBusy = true;
    error = null;
    _notify();
    try {
      snapshot = RcsSnapshot.fromMap(await _map(method, arguments));
      if (snapshot.ready) _scheduleRefresh(inbox: true);
    } catch (failure) {
      final safe = _recordError(failure);
      throw safe;
    } finally {
      _commandBusy = false;
      _notify();
    }
  }

  String newRequestId() => const Uuid().v4();

  Future<RcsSendResult> send(
      {required String conversationId,
      required String simId,
      required String text,
      required String requestId}) async {
    final chat = _conversations[conversationId];
    if (!snapshot.ready || !snapshot.paired) throw _errorForCode('NOT_READY');
    // The durable native journal rejects every new request while a send is
    // uncertain, but permits the exact prior ID/payload to return its status.
    // Keep that check authoritative so an explicit status check can recover.
    if (chat == null || !chat.rcs || chat.readOnly) {
      throw _errorForCode('RCS_REQUIRED');
    }
    if (!snapshot.availableSims.any((sim) => sim.id == simId)) {
      throw _errorForCode('SIM_RCS_UNAVAILABLE');
    }
    if (text.trim().isEmpty || utf8.encode(text).length > 16 * 1024) {
      throw _errorForCode('INVALID_SEND');
    }
    if (!RegExp(
            r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
        .hasMatch(requestId)) throw _errorForCode('INVALID_REQUEST_ID');
    if (_sending.containsKey(requestId)) {
      throw const RcsException(
          'BUSY', 'This message is already being submitted.');
    }
    _sending[requestId] = conversationId;
    error = null;
    _notify();
    try {
      final result = RcsSendResult.fromMap(await _map('send', {
        'conversationId': conversationId,
        'simId': simId,
        'text': text,
        'requestId': requestId,
      }));
      _scheduleRefresh(inbox: true, conversationId: conversationId);
      return result;
    } catch (failure) {
      throw _recordError(failure);
    } finally {
      _sending.remove(requestId);
      _notify();
      unawaited(refreshSnapshot());
    }
  }

  Future<void> markRead(
      {required String conversationId, required String messageId}) async {
    if (!_validId(conversationId) || !_validId(messageId)) {
      throw _errorForCode('INVALID_READ_TARGET');
    }
    final generation = _generation;
    try {
      await _channel.invokeMethod<Object?>('markRead',
          {'conversationId': conversationId, 'messageId': messageId});
      if (!_current(generation)) return;
      final chat = _conversations[conversationId];
      if (chat != null) {
        _conversations[conversationId] = chat.copyWith(unread: false);
        _liveConversationVersions[conversationId] = ++_eventVersion;
      }
      error = null;
      _notify();
    } catch (failure) {
      if (_current(generation)) throw _recordError(failure);
    }
  }

  Future<void> setVisibleConversation(String? id) async {
    _visibleConversation = _validId(id) ? id : null;
    try {
      await _channel.invokeMethod<Object?>(
          'setVisibleConversation', {'conversationId': _visibleConversation});
    } catch (failure) {
      _recordError(failure);
    }
  }

  String? consumeNotificationConversation() {
    final value = notificationConversation.value;
    notificationConversation.value = null;
    return value;
  }

  void _handleEvent(dynamic raw) {
    if (_disposed) return;
    final event = _decodeMap(raw);
    if (event == null) return;
    switch (event['type']) {
      case 'snapshot':
        final value = event['snapshot'];
        if (value is Map) {
          final wasReady = snapshot.ready;
          snapshot = RcsSnapshot.fromMap(value);
          if (!snapshot.paired && !snapshot.busy) _clearRecords();
          if (snapshot.ready && !wasReady) {
            _scheduleRefresh(inbox: true, conversationId: _visibleConversation);
          }
          if (snapshot.errorCode.isNotEmpty) {
            error = _errorForCode(snapshot.errorCode).message;
          }
          _notify();
        }
        break;
      case 'state':
        final state = event['state'];
        if (state is String) {
          final wasReady = snapshot.ready;
          snapshot = snapshot.withState(state);
          if (snapshot.ready && !wasReady) {
            _scheduleRefresh(inbox: true, conversationId: _visibleConversation);
          }
          _notify();
        }
        break;
      case 'openConversation':
        final id = event['conversationId'];
        if (id is String && _validId(id)) {
          _navigationEvents++;
          notificationConversation.value = id;
        }
        break;
      case 'conversation':
        final value = event['conversation'];
        if (value is Map) {
          final chat = RcsConversation.fromMap(value);
          if (_validId(chat.id)) {
            _conversations[chat.id] = chat;
            _liveConversationVersions[chat.id] = ++_eventVersion;
            _notify();
          }
        }
        break;
      case 'message':
        final value = event['message'];
        if (value is! Map) break;
        final message = RcsMessage.fromMap(value);
        if (!_validId(message.id) || !_validId(message.conversationId)) break;
        final messages =
            _messages.putIfAbsent(message.conversationId, () => {});
        final previous = messages[message.id];
        if (previous != null &&
            (message.old || previous.sameContent(message))) {
          break;
        }
        messages[message.id] = message;
        if (!message.old) {
          _liveMessageVersions['${message.conversationId}\u0000${message.id}'] =
              ++_eventVersion;
        }
        if (messages.length > 200) {
          final sorted = messages.values.toList()
            ..sort((a, b) => a.timestampMs.compareTo(b.timestampMs));
          for (final old in sorted.take(messages.length - 200)) {
            messages.remove(old.id);
            _liveMessageVersions
                .remove('${message.conversationId}\u0000${old.id}');
          }
        }
        final chat = _conversations[message.conversationId];
        if (chat != null && message.timestampMs >= chat.timestampMs) {
          _conversations[chat.id] = chat.copyWith(
              preview: message.text,
              timestampMs: message.timestampMs,
              unread: !message.outgoing && _visibleConversation != chat.id
                  ? true
                  : chat.unread);
          _liveConversationVersions[chat.id] = _eventVersion;
        }
        _notify();
        if (!message.old && chat == null) _scheduleRefresh(inbox: true);
        break;
      case 'send':
        final requestId = event['requestId'];
        if (requestId is! String || requestId.isEmpty) break;
        final signature =
            '${event['status']}|${event['protocol']}|${event['actualSimId']}';
        if (_sendSignatures[requestId] == signature) break;
        _sendSignatures[requestId] = signature;
        if (_sendSignatures.length > 256) {
          _sendSignatures.remove(_sendSignatures.keys.first);
        }
        final id = event['conversationId'];
        _scheduleRefresh(
            inbox: true,
            conversationId: id is String &&
                    (_visibleConversation == id || _messages.containsKey(id))
                ? id
                : null);
        break;
      case 'invalidate':
        final id = event['conversationId'];
        _scheduleRefresh(
            inbox: true,
            conversationId: id is String &&
                    (_visibleConversation == id || _messages.containsKey(id))
                ? id
                : null);
        break;
      case 'error':
        error = _errorForCode(
                event['code'] is String ? event['code'] as String : '')
            .message;
        _notify();
        break;
    }
  }

  void _scheduleRefresh({bool inbox = false, String? conversationId}) {
    if (_disposed) return;
    _pendingInboxRead |= inbox;
    if (_validId(conversationId)) _pendingMessageReads.add(conversationId!);
    if (_refreshTimer != null) return;
    _refreshTimer = Timer(const Duration(milliseconds: 400), () {
      _refreshTimer = null;
      if (_disposed || !snapshot.ready) return;
      final inbox = _pendingInboxRead;
      final ids = _pendingMessageReads.toList();
      _pendingInboxRead = false;
      _pendingMessageReads.clear();
      if (inbox) unawaited(refreshConversations());
      for (final id in ids) {
        unawaited(loadMessages(id));
      }
    });
  }

  Future<Map<dynamic, dynamic>> _map(String method,
      [Map<String, Object?>? args]) async {
    final result =
        _decodeMap(await _channel.invokeMethod<Object?>(method, args));
    if (result == null) {
      throw const RcsException('INVALID_RESPONSE',
          'The bridge returned an unreadable response. Refresh and try again.');
    }
    return result;
  }

  static Map? _decodeMap(dynamic value) {
    if (value is Map) return value;
    if (value is String) {
      try {
        final decoded = jsonDecode(value);
        return decoded is Map ? decoded : null;
      } catch (_) {
        return null;
      }
    }
    return null;
  }

  static Iterable<Map> _maps(dynamic value) =>
      value is List ? value.whereType<Map>() : const <Map>[];
  static bool _validId(String? value) =>
      value != null &&
      value.isNotEmpty &&
      value.length <= 512 &&
      !RegExp(r'[\x00-\x1f\x7f]').hasMatch(value);
  bool _current(int generation) => !_disposed && generation == _generation;
  void _notify() {
    if (!_disposed) notifyListeners();
  }

  RcsException _recordError(Object failure) {
    final safe = failure is RcsException
        ? failure
        : failure is MissingPluginException
            ? const RcsException('UNAVAILABLE',
                'RCS is available in the Android build with the Google Messages bridge.')
            : _errorForCode(
                failure is PlatformException ? failure.code : 'BRIDGE_ERROR');
    error = safe.message;
    _notify();
    return safe;
  }

  static RcsException _errorForCode(String code) {
    final safeCode =
        RegExp(r'^[A-Z0-9_]{1,64}$').hasMatch(code) ? code : 'BRIDGE_ERROR';
    final message = switch (safeCode) {
      'NOT_READY' => 'Wait for Google Messages to connect before using RCS.',
      'SEND_UNCERTAIN' =>
        'An earlier send is not confirmed. Check the conversation before sending again.',
      'SIM_RCS_UNAVAILABLE' ||
      'REPLY_SIM_UNAVAILABLE' =>
        'Choose an active SIM with RCS enabled.',
      'RCS_REQUIRED' =>
        'This conversation is not currently available for RCS sending.',
      'INVALID_SEND' => 'Enter a message of up to 16 KB and choose an RCS SIM.',
      'INVALID_REQUEST_ID' ||
      'REQUEST_ID_REUSED' =>
        'This send request is invalid. Check the conversation before trying again.',
      'INVALID_SIGN_IN_FILE' =>
        'Choose the local sign-in JSON file created by the computer helper.',
      'SESSION_STORAGE_FAILED' ||
      'STORAGE_FAILED' =>
        'The pairing or send record could not be saved securely. Check the app before continuing.',
      'READ_TARGET_UNAVAILABLE' ||
      'INVALID_READ_TARGET' ||
      'READ_UNCONFIRMED' =>
        'Could not mark this message as read. Check Google Messages.',
      _ =>
        'The RCS bridge could not complete this action. Check the connection and try again.',
    };
    return RcsException(safeCode, message);
  }

  void _clearRecords() {
    _conversations.clear();
    _messages.clear();
    _liveMessageVersions.clear();
    _liveConversationVersions.clear();
    _sendSignatures.clear();
    notificationConversation.value = null;
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _refreshTimer?.cancel();
    unawaited(_subscription?.cancel());
    notificationConversation.dispose();
    super.dispose();
  }
}
