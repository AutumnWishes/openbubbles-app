import 'dart:async';

import 'package:bluebubbles/services/native_sms/native_sms_service.dart';
import 'package:bluebubbles/services/rcs/rcs_service.dart';
import 'package:bluebubbles/services/unified_inbox/unified_inbox_service.dart';
import 'package:bluebubbles/services/unified_inbox/unified_models.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _peer = '+15550101234';
const _local = '+15550104321';
const _request = '00000000-0000-4000-8000-000000000001';

Map<String, Object?> _sms(int id, {int subscription = 7, bool read = false}) =>
    {
      'id': id,
      'address': _peer,
      'body': 'Repeated words',
      'date': 100,
      'type': 1,
      'read': read,
      'status': -1,
      'subId': subscription,
      'threadId': 11,
    };
Map<String, Object?> _chat(String id,
        {String sim = 'work',
        bool rcs = true,
        List<String> locals = const [_local]}) =>
    {
      'id': id,
      'name': 'Synthetic recipient',
      'rcs': rcs,
      'readOnly': !rcs,
      'unread': true,
      'simId': sim,
      'addresses': [_peer],
      'localAddresses': locals,
      'timestampMs': 100,
    };
Map<String, Object?> _message(String id,
        {String conversation = 'chat',
        String protocol = 'RCS',
        String sim = 'work',
        int timestamp = 100,
        int status = 100,
        bool outgoing = false}) =>
    {
      'id': id,
      'conversationId': conversation,
      'text': 'Repeated words',
      'protocol': protocol,
      'simId': sim,
      'timestampMs': timestamp,
      'statusCode': status,
      'outgoing': outgoing,
    };

/// Both real provider services run against local channels. No Apple database,
/// device subscription, account, or live network is needed by these tests.
class _Rig {
  static const smsChannel = MethodChannel('test/unified_sms');
  static const rcsChannel = MethodChannel('test/unified_rcs');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final events = StreamController<dynamic>.broadcast(sync: true);
  final smsCalls = <MethodCall>[];
  final rcsCalls = <MethodCall>[];
  List<Map<String, Object?>> smsRows = [_sms(1)];
  List<Map<String, Object?>> chats = [_chat('chat')];
  final histories = <String, List<Map<String, Object?>>>{
    'chat': [_message('1')],
  };
  Map<String, Object?> state = {
    'supported': true,
    'paired': true,
    'ready': true,
    'state': 'ready',
    'sims': [
      {
        'id': 'personal',
        'label': 'Personal',
        'active': true,
        'rcsEnabled': true
      },
      {'id': 'work', 'label': 'Work', 'active': true, 'rcsEnabled': true},
    ],
  };
  bool smsPermission = true;
  Future<Object?> Function(MethodCall)? rcsSend;
  Future<Object?> Function(MethodCall)? rcsRead;
  late final NativeSmsService sms;
  late final RcsService rcs;
  late final UnifiedInboxService inbox;

  _Rig() {
    messenger.setMockMethodCallHandler(smsChannel, (call) async {
      smsCalls.add(call);
      switch (call.method) {
        case 'getState':
          return {
            'supported': true,
            'permissionsGranted': smsPermission,
            'isDefault': true,
          };
        case 'getSubscriptions':
          return [
            {'id': 7, 'displayName': 'Personal', 'number': _local},
            {'id': 8, 'displayName': 'Work', 'number': '+15550109999'},
          ];
        case 'listMessages':
          final args = call.arguments as Map;
          final ids = args['ids'] as List?;
          if (ids != null) {
            return smsRows.where((row) => ids.contains(row['id'])).toList();
          }
          return smsRows;
        case 'markRead':
          final ids = (call.arguments as Map)['ids'] as List;
          smsRows = [
            for (final row in smsRows)
              if (ids.contains(row['id'])) {...row, 'read': true} else row
          ];
          return null;
        case 'sendSms':
          final args = call.arguments as Map;
          final row = {
            ..._sms(-1, subscription: args['subscriptionId'] as int),
            'body': args['body'],
            'type': 4,
          };
          smsRows.add(row);
          return row;
        default:
          throw StateError('Unexpected SMS method ${call.method}');
      }
    });
    messenger.setMockMethodCallHandler(rcsChannel, (call) async {
      rcsCalls.add(call);
      switch (call.method) {
        case 'snapshot':
          return state;
        case 'takePendingConversation':
        case 'setVisibleConversation':
          return null;
        case 'conversations':
          return {'conversations': chats};
        case 'messages':
          final id = (call.arguments as Map)['conversationId'];
          return {
            'conversation': chats.firstWhere((chat) => chat['id'] == id),
            'messages': histories[id] ?? [],
          };
        case 'markRead':
          return rcsRead == null ? {'ok': true} : await rcsRead!(call);
        case 'send':
          if (rcsSend != null) return rcsSend!(call);
          final args = call.arguments as Map;
          return {
            'requestId': args['requestId'],
            'conversationId': args['conversationId'],
            'simId': args['simId'],
            'actualSimId': args['simId'],
            'status': 'accepted',
            'protocol': 'RCS',
          };
        case 'forget':
          state = {...state, 'paired': false, 'ready': false};
          return state;
        default:
          throw StateError('Unexpected RCS method ${call.method}');
      }
    });
    sms = NativeSmsService(channel: smsChannel);
    rcs = RcsService(channel: rcsChannel, events: events.stream);
    inbox = UnifiedInboxService(sms: sms, rcs: rcs);
  }

  List<InboxRoute> get routes =>
      inbox.threads.expand((thread) => thread.routes).toList();
  List<MethodCall> get reads =>
      rcsCalls.where((call) => call.method == 'markRead').toList();
  UnifiedThread thread(String providerId) => inbox.threads.firstWhere(
      (thread) => thread.routes.any((route) => route.providerId == providerId));
  Future<void> start() => inbox.initialize();
  Future<void> refreshBridge() async {
    await rcs.refreshSnapshot();
    await rcs.refreshConversations();
  }

  Future<void> close() async {
    await Future<void>.delayed(Duration.zero);
    inbox.dispose();
    rcs.dispose();
    sms.dispose();
    await events.close();
    messenger.setMockMethodCallHandler(smsChannel, null);
    messenger.setMockMethodCallHandler(rcsChannel, null);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Rig rig;
  setUp(() => rig = _Rig());
  tearDown(() => rig.close());

  test(
      'native SMS is authoritative while equal real messages keep provider IDs',
      () async {
    rig.histories['chat'] = [
      _message('1'),
      _message('2'),
      _message('copy-1', protocol: 'SMS'),
      _message('copy-2', protocol: 'sms'),
      _message('media', protocol: 'MMS'),
    ];
    await rig.start();
    final thread = rig.inbox.threads.single;
    await rig.inbox.loadThread(thread);
    final messages = rig.inbox.messages(thread);
    expect(messages.map((message) => message.id).toSet(),
        {'sms:1', 'rcs:chat:1', 'rcs:chat:2', 'rcs:chat:media'});
    expect(messages.map((message) => message.text).toSet(), {'Repeated words'});
    expect(
        messages.singleWhere((message) => message.id.endsWith('media')).label,
        'MMS');
    expect(rig.smsCalls.where((call) => call.method == 'sendSms'), isEmpty);
    expect(rig.rcsCalls.where((call) => call.method == 'send'), isEmpty);
  });

  test(
      'matched Google SMS history contributes older RCS without a duplicate row',
      () async {
    rig.chats = [
      _chat('chat', rcs: false),
      _chat('unverified', rcs: false, locals: [])
    ];
    rig.histories['chat'] = [
      _message('copied-sms', protocol: 'SMS'),
      _message('earlier-rcs'),
    ];
    await rig.start();
    expect(rig.inbox.threads, hasLength(1));
    final thread = rig.inbox.threads.single;
    final google =
        thread.routes.singleWhere((r) => r.transport == InboxTransport.rcs);
    expect(google.providerId, 'chat');
    expect(google.available, isFalse);
    expect(google.label, 'Google history');
    expect(
        rig.routes.any((route) => route.providerId == 'unverified'), isFalse);
    await rig.inbox.loadThread(thread);
    expect(rig.inbox.messages(thread).map((m) => m.id).toSet(),
        {'sms:1', 'rcs:chat:earlier-rcs'});
    await rig.inbox.markRead(thread);
    expect(rig.reads, isEmpty,
        reason: 'Google currently classifies this thread as SMS.');
    await expectLater(
        rig.inbox.send(google, 'text', _request), throwsStateError);
  });

  test(
      'without native SMS access Google history remains visible but cannot send',
      () async {
    rig.smsPermission = false;
    rig.chats = [_chat('chat', rcs: false, locals: [])];
    rig.histories['chat'] = [_message('sms-history', protocol: 'SMS')];
    await rig.start();
    final thread = rig.inbox.threads.single;
    await rig.inbox.loadThread(thread);
    expect(thread.routes.single.available, isFalse);
    expect(rig.inbox.messages(thread).single.id, 'rcs:chat:sms-history');
    await expectLater(rig.inbox.send(thread.routes.single, 'text', _request),
        throwsStateError);
    expect(rig.smsCalls.where((call) => call.method == 'sendSms'), isEmpty);
    expect(rig.rcsCalls.where((call) => call.method == 'send'), isEmpty);
  });

  test('chosen routes preserve provider, SIM, recipient and request IDs',
      () async {
    rig.smsRows = [_sms(1, subscription: 8)];
    rig.chats = [_chat('provider:chat/7')];
    await rig.start();
    final rcsRoute =
        rig.routes.singleWhere((r) => r.transport == InboxTransport.rcs);
    final smsRoute =
        rig.routes.singleWhere((r) => r.transport == InboxTransport.sms);
    await rig.inbox.send(rcsRoute, 'RCS body', _request);
    final bridgeSend =
        rig.rcsCalls.singleWhere((call) => call.method == 'send');
    expect(bridgeSend.arguments, {
      'conversationId': 'provider:chat/7',
      'simId': 'work',
      'text': 'RCS body',
      'requestId': _request,
    });
    expect(rig.smsCalls.where((call) => call.method == 'sendSms'), isEmpty);
    await rig.inbox.send(smsRoute, 'SMS body', 'stable-sms-request');
    final carrierSend =
        rig.smsCalls.singleWhere((call) => call.method == 'sendSms');
    expect(carrierSend.arguments, {
      'address': _peer,
      'body': 'SMS body',
      'subscriptionId': 8,
      'clientId': 'stable-sms-request',
    });
    expect(rig.rcsCalls.where((call) => call.method == 'send'), hasLength(1));
  });

  test('a disappearing chosen RCS SIM keeps its route and never falls back',
      () async {
    await rig.start();
    final frozen =
        rig.routes.singleWhere((r) => r.transport == InboxTransport.rcs);
    rig.state['sims'] = [
      {
        'id': 'personal',
        'label': 'Personal',
        'active': true,
        'rcsEnabled': true
      },
      {'id': 'work', 'label': 'Work', 'active': false, 'rcsEnabled': false},
    ];
    await rig.refreshBridge();
    final current = rig.routes.singleWhere((r) => r.key == frozen.key);
    expect(current.simId, 'work');
    expect(current.providerId, 'chat');
    expect(current.available, isFalse);
    expect(current.label, 'Work');
    await expectLater(
        rig.inbox.send(frozen, 'Do not reroute', _request), throwsStateError);
    expect(rig.rcsCalls.where((call) => call.method == 'send'), isEmpty);
    expect(rig.smsCalls.where((call) => call.method == 'sendSms'), isEmpty);
  });

  test('multiple SIM candidates load once and never relabel message history',
      () async {
    rig.chats = [_chat('chat', sim: '')];
    rig.histories['chat'] = [
      _message('personal', sim: 'personal'),
      _message('work', sim: 'work'),
      _message('unknown', sim: ''),
    ];
    await rig.start();
    final thread = rig.thread('chat');
    expect(thread.routes.where((r) => r.transport == InboxTransport.rcs),
        hasLength(2));
    expect(thread.preferredRoute, isNull);
    await rig.inbox.loadThread(thread);
    expect(
        rig.rcsCalls.where((call) => call.method == 'messages'), hasLength(1));
    final before = {
      for (final m in rig.inbox.messages(thread))
        m.id: '${m.routeKey}|${m.label}'
    };
    expect(before['rcs:chat:personal'], 'rcs:chat:personal|RCS · Personal');
    expect(before['rcs:chat:work'], 'rcs:chat:work|RCS · Work');
    expect(before['rcs:chat:unknown'], 'rcs:chat|RCS');
    rig.state['sims'] = (rig.state['sims'] as List).reversed.toList();
    await rig.refreshBridge();
    final after = {
      for (final m in rig.inbox.messages(rig.thread('chat')))
        m.id: '${m.routeKey}|${m.label}'
    };
    expect(after, before);
  });

  test(
      'mark-read coalesces candidate routes and reentrant updates without looping',
      () async {
    rig.smsRows = [];
    rig.chats = [_chat('chat', sim: '')];
    await rig.start();
    final thread = rig.thread('chat');
    await rig.inbox.loadThread(thread);
    final pending = Completer<Object?>();
    rig.rcsRead = (_) => pending.future;
    final repeated = <Future<void>>[];
    void listener() {
      repeated.add(rig.inbox.markRead(thread));
    }

    rig.inbox.addListener(listener);
    final first = rig.inbox.markRead(thread);
    final second = rig.inbox.markRead(thread);
    await Future<void>.delayed(Duration.zero);
    expect(rig.reads, hasLength(1));
    pending.complete({'ok': true});
    await Future.wait([first, second]);
    rig.inbox.removeListener(listener);
    await Future.wait(repeated);
    await rig.inbox.markRead(thread);
    expect(rig.reads, hasLength(1));
    rig.events
        .add({'type': 'message', 'message': _message('new', timestamp: 200)});
    rig.rcsRead = null;
    await rig.inbox.markRead(thread);
    expect(rig.reads, hasLength(2));
    expect((rig.reads.last.arguments as Map)['messageId'], 'new');
  });

  test('read receipts select only the newest verified incoming RCS message',
      () async {
    rig.smsRows = [];
    rig.histories['chat'] = [
      _message('old', timestamp: 100),
      _message('new', timestamp: 200, status: 108),
      _message('outgoing', timestamp: 300, outgoing: true),
      _message('sms', timestamp: 400, protocol: 'SMS'),
      _message('unconfirmed-case', timestamp: 500, protocol: 'rcs'),
      _message('unsupported-state', timestamp: 600, status: 999),
    ];
    await rig.start();
    final thread = rig.thread('chat');
    await rig.inbox.loadThread(thread);
    await rig.inbox.markRead(thread);
    expect(rig.reads.single.arguments,
        {'conversationId': 'chat', 'messageId': 'new'});
  });

  test(
      'failed mark-read can be retried explicitly but is never marked completed',
      () async {
    rig.smsRows = [];
    await rig.start();
    final thread = rig.thread('chat');
    await rig.inbox.loadThread(thread);
    rig.rcsRead =
        (_) async => throw PlatformException(code: 'READ_UNCONFIRMED');
    await expectLater(rig.inbox.markRead(thread), throwsA(isA<RcsException>()));
    expect(rig.reads, hasLength(1));
    rig.rcsRead = null;
    await rig.inbox.markRead(thread);
    await rig.inbox.markRead(thread);
    expect(rig.reads, hasLength(2));
  });

  test('late read completion cannot acknowledge a replacement pairing',
      () async {
    rig.smsRows = [];
    await rig.start();
    final oldThread = rig.thread('chat');
    await rig.inbox.loadThread(oldThread);
    final pending = Completer<Object?>();
    rig.rcsRead = (_) => pending.future;
    final oldRead = rig.inbox.markRead(oldThread);
    final oldFollower = rig.inbox.markRead(oldThread);
    await Future<void>.delayed(Duration.zero);
    await rig.rcs.forget();
    rig.state = {...rig.state, 'paired': true, 'ready': true};
    await rig.refreshBridge();
    final newThread = rig.thread('chat');
    await rig.inbox.loadThread(newThread);
    pending.complete({'ok': true});
    await Future.wait([oldRead, oldFollower]);
    expect(rig.reads, hasLength(1),
        reason: 'Old waiters cannot submit into the new session.');
    rig.rcsRead = null;
    await rig.inbox.markRead(newThread);
    expect(rig.reads, hasLength(2),
        reason: 'The replacement pairing needs its own read receipt.');
  });

  test('uncertain RCS send does not invoke carrier SMS or silently retry',
      () async {
    await rig.start();
    rig.rcsSend = (call) async => {
          'requestId': _request,
          'conversationId': 'chat',
          'simId': 'work',
          'status': 'unknown',
          'protocol': 'unconfirmed',
        };
    final route =
        rig.routes.singleWhere((r) => r.transport == InboxTransport.rcs);
    await expectLater(rig.inbox.send(route, 'One attempt', _request),
        throwsA(isA<RcsException>()));
    await Future<void>.delayed(const Duration(milliseconds: 400));
    expect(rig.rcsCalls.where((call) => call.method == 'send'), hasLength(1));
    expect(rig.smsCalls.where((call) => call.method == 'sendSms'), isEmpty);
  });

  test(
      'Apple rows are injected by provider ID without loading an Apple database',
      () async {
    await rig.start();
    rig.inbox.updateAppleRoutes([
      InboxRoute(
          key: 'apple:guid',
          providerId: 'original-apple-guid',
          transport: InboxTransport.iMessage,
          title: 'Apple display name',
          addresses: const [_peer],
          localAddress: _local,
          timestamp: DateTime.fromMillisecondsSinceEpoch(100),
          available: true),
    ]);
    String? readProvider;
    rig.inbox.readAppleMessages = (route, limit) {
      readProvider = route.providerId;
      return [
        InboxMessage(
            id: 'apple:message-guid',
            routeKey: route.key,
            text: 'Repeated words',
            timestamp: route.timestamp,
            outgoing: false,
            transport: InboxTransport.iMessage)
      ];
    };
    final thread = rig.inbox.threads.single;
    await rig.inbox.loadThread(thread);
    expect(thread.preferredRoute?.key, 'apple:guid');
    expect(rig.inbox.messages(thread).map((m) => m.id).toSet(),
        {'sms:1', 'rcs:chat:1', 'apple:message-guid'});
    expect(readProvider, 'original-apple-guid');
  });
}
