import 'dart:async';

import 'package:bluebubbles/services/rcs/rcs_service.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, Object?> state({bool ready = true, bool paired = true}) => {
      'state': ready ? 'ready' : 'disconnected',
      'ready': ready,
      'paired': paired,
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
Map<String, Object?> chat() => {
      'id': 'chat',
      'name': 'Synthetic recipient',
      'rcs': true,
      'readOnly': false,
      'simId': 'work',
      'addresses': ['+15550101234'],
      'localAddresses': ['+15550104321'],
      'timestampMs': 100,
      'preview': 'Existing message',
    };
Map<String, Object?> message(String id,
        {int status = 100, String text = 'Same words', bool old = false}) =>
    {
      'id': id,
      'conversationId': 'chat',
      'text': text,
      'timestampMs': 100,
      'protocol': 'RCS',
      'statusCode': status,
      'old': old,
      'outgoing': false,
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('test/rcs_contract');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late StreamController<dynamic> events;
  late RcsService service;
  late List<MethodCall> calls;
  Future<Object?> Function(MethodCall)? custom;

  setUp(() {
    calls = [];
    custom = null;
    events = StreamController<dynamic>.broadcast(sync: true);
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (custom != null) return custom!(call);
      switch (call.method) {
        case 'snapshot':
          return state();
        case 'takePendingConversation':
          return null;
        case 'conversations':
          return {
            'conversations': [chat()]
          };
        case 'messages':
          return {
            'conversation': chat(),
            'messages': [message('m1')]
          };
        case 'setVisibleConversation':
          return null;
        case 'forget':
          return state(ready: false, paired: false);
        case 'disconnect':
          return state(ready: false);
        default:
          throw StateError('Unexpected bridge operation ${call.method}');
      }
    });
    service = RcsService(channel: channel, events: events.stream);
  });
  tearDown(() async {
    service.dispose();
    await events.close();
    messenger.setMockMethodCallHandler(channel, null);
  });

  test(
      'initialization installs one event subscription and loads typed identities',
      () async {
    final first = service.initialize();
    final second = service.initialize();
    expect(identical(first, second), isTrue);
    await first;
    expect(calls.where((call) => call.method == 'snapshot'), hasLength(1));
    expect(calls.where((call) => call.method == 'takePendingConversation'),
        hasLength(1));
    expect(service.snapshot.availableSims.map((sim) => sim.id),
        ['personal', 'work']);
    expect(service.conversations.single.addresses, ['+15550101234']);
    expect(service.conversations.single.localAddresses, ['+15550104321']);
  });

  test(
      'provider IDs deduplicate updates but equal text does not erase real messages',
      () async {
    await service.initialize();
    events.add({'type': 'message', 'message': message('m1')});
    events.add({'type': 'message', 'message': message('m1')});
    events.add({'type': 'message', 'message': message('m2')});
    events.add({'type': 'message', 'message': message('m1', status: 109)});
    expect(service.messagesFor('chat').map((item) => item.id), ['m1', 'm2']);
    expect(service.messagesFor('chat').first.statusCode, 109);
    events.add({'type': 'message', 'message': message('m1', old: true)});
    expect(service.messagesFor('chat').first.statusCode, 109);
    expect(calls.where((call) => call.method == 'messages'), isEmpty);
  });

  test('live delivery state wins over an older in-flight history response',
      () async {
    await service.initialize();
    final pending = Completer<Object?>();
    custom = (call) async {
      if (call.method == 'messages') return pending.future;
      return state();
    };
    final read = service.loadMessages('chat');
    expect(service.loadingMessages('chat'), isTrue);
    expect(service.snapshot.ready, isTrue);
    expect(service.commandBusy, isFalse);
    events.add({'type': 'message', 'message': message('m1', status: 109)});
    pending.complete({
      'conversation': chat(),
      'messages': [message('m1', old: true)]
    });
    await read;
    expect(service.messagesFor('chat').single.statusCode, 109);
    expect(service.loadingMessages('chat'), isFalse);
  });

  test('forget discards stale account history responses', () async {
    await service.initialize();
    final pending = Completer<Object?>();
    custom = (call) async {
      if (call.method == 'messages') return pending.future;
      if (call.method == 'forget') return state(ready: false, paired: false);
      throw StateError('Unexpected method');
    };
    final read = service.loadMessages('chat');
    await service.forget();
    pending.complete({
      'conversation': chat(),
      'messages': [message('m1')]
    });
    await read;
    expect(service.conversations, isEmpty);
    expect(service.messagesFor('chat'), isEmpty);
    expect(service.snapshot.paired, isFalse);
  });

  test('confirmed mark-read is not undone by an older in-flight history read',
      () async {
    await service.initialize();
    final pending = Completer<Object?>();
    custom = (call) async {
      if (call.method == 'messages') return await pending.future;
      if (call.method == 'markRead') return {'ok': true};
      return state();
    };
    final reading = service.loadMessages('chat');
    await service.markRead(conversationId: 'chat', messageId: 'm1');
    pending.complete({
      'conversation': {...chat(), 'unread': true},
      'messages': [message('m1')]
    });
    await reading;
    expect(service.conversation('chat')!.unread, isFalse);
  });

  test('late read acknowledgement cannot change the replacement pairing',
      () async {
    await service.initialize();
    final pending = Completer<Object?>();
    custom = (call) async {
      if (call.method == 'markRead') return await pending.future;
      if (call.method == 'forget') return state(ready: false, paired: false);
      return state();
    };
    final marking = service.markRead(conversationId: 'chat', messageId: 'm1');
    await service.forget();
    events.add({'type': 'snapshot', 'snapshot': state()});
    events.add({
      'type': 'conversation',
      'conversation': {...chat(), 'unread': true}
    });
    pending.complete({'ok': true});
    await marking;
    expect(service.conversation('chat')!.unread, isTrue);
  });

  test(
      'send retains explicit SIM and request ID without retry on uncertain response',
      () async {
    await service.initialize();
    custom = (call) async {
      if (call.method == 'send') {
        return {
          'requestId': (call.arguments as Map)['requestId'],
          'conversationId': 'chat',
          'simId': 'work',
          'status': 'unknown',
          'protocol': 'unconfirmed',
        };
      }
      if (call.method == 'snapshot') return state();
      return {
        'conversation': chat(),
        'messages': [],
        'conversations': [chat()]
      };
    };
    final id = service.newRequestId();
    final result = await service.send(
        conversationId: 'chat',
        simId: 'work',
        text: 'Synthetic reply',
        requestId: id);
    expect(result.uncertain, isTrue);
    await Future<void>.delayed(const Duration(milliseconds: 450));
    final sends = calls.where((call) => call.method == 'send').toList();
    expect(sends, hasLength(1));
    expect((sends.single.arguments as Map)['simId'], 'work');
    expect((sends.single.arguments as Map)['requestId'], id);
  });

  test('inactive SIM and same in-flight request cannot send a second message',
      () async {
    await service.initialize();
    await expectLater(
        service.send(
            conversationId: 'chat',
            simId: 'missing',
            text: 'x',
            requestId: service.newRequestId()),
        throwsA(isA<RcsException>()));
    expect(calls.where((call) => call.method == 'send'), isEmpty);
    final pending = Completer<Object?>();
    custom = (call) async {
      if (call.method == 'send') return await pending.future;
      return state();
    };
    final id = service.newRequestId();
    final first = service.send(
        conversationId: 'chat', simId: 'work', text: 'x', requestId: id);
    await expectLater(
        service.send(
            conversationId: 'chat', simId: 'work', text: 'x', requestId: id),
        throwsA(isA<RcsException>()));
    pending.complete({
      'requestId': id,
      'conversationId': 'chat',
      'simId': 'work',
      'status': 'accepted',
      'protocol': 'unconfirmed'
    });
    await first;
    expect(calls.where((call) => call.method == 'send'), hasLength(1));
  });

  test(
      'an explicit repeat asks the journal with the unchanged ID, body and SIM',
      () async {
    await service.initialize();
    final id = service.newRequestId();
    var attempts = 0;
    custom = (call) async {
      if (call.method == 'send') {
        attempts++;
        return {
          'requestId': id,
          'conversationId': 'chat',
          'simId': 'work',
          'status': attempts == 1 ? 'unknown' : 'delivered',
          'protocol': attempts == 1 ? 'unconfirmed' : 'RCS'
        };
      }
      if (call.method == 'snapshot') {
        return {...state(), 'hasUncertainSend': true};
      }
      return {
        'conversations': [chat()],
        'messages': []
      };
    };
    await service.send(
        conversationId: 'chat',
        simId: 'work',
        text: 'Same saved draft',
        requestId: id);
    await service.refreshSnapshot();
    expect(service.snapshot.hasUncertainSend, isTrue);
    expect(attempts, 1);
    final result = await service.send(
        conversationId: 'chat',
        simId: 'work',
        text: 'Same saved draft',
        requestId: id);
    expect(result.status, 'delivered');
    final sends = calls.where((call) => call.method == 'send').toList();
    expect(sends, hasLength(2));
    expect(sends.first.arguments, sends.last.arguments);
  });

  test(
      'native exception details and login data never become displayed error text',
      () async {
    custom = (_) async => throw PlatformException(
        code: 'BRIDGE_ERROR', message: 'SID=synthetic-sensitive-cookie');
    await service.initialize();
    expect(service.error, isNot(contains('synthetic-sensitive-cookie')));
    expect(service.error, isNot(contains('SID=')));
    await expectLater(service.pair('["synthetic-sensitive-cookie"]'),
        throwsA(isA<RcsException>()));
    expect(calls.where((call) => call.method == 'pair'), isEmpty);
  });

  test('notification event and initial pending target navigate only once',
      () async {
    var navigations = 0;
    service.notificationConversation.addListener(() {
      if (service.notificationConversation.value != null) {
        navigations++;
        expect(service.consumeNotificationConversation(), 'chat');
      }
    });
    custom = (call) async {
      if (call.method == 'snapshot') return state();
      if (call.method == 'takePendingConversation') {
        events.add({'type': 'openConversation', 'conversationId': 'chat'});
        return 'chat';
      }
      return {
        'conversations': [chat()]
      };
    };
    await service.initialize();
    expect(navigations, 1);
    events.add({'type': 'openConversation', 'conversationId': 'chat'});
    expect(navigations, 2);
  });

  test('replayed send events trigger at most one refresh and no feedback loop',
      () async {
    await service.initialize();
    await service.setVisibleConversation('chat');
    final event = {
      'type': 'send',
      'requestId': 'synthetic-request',
      'conversationId': 'chat',
      'status': 'delivered',
      'protocol': 'RCS'
    };
    events.add(event);
    events.add(event);
    await Future<void>.delayed(const Duration(milliseconds: 450));
    final reads = calls.where((call) => call.method == 'messages').length;
    expect(reads, 1);
    events.add(event);
    await Future<void>.delayed(const Duration(milliseconds: 450));
    expect(calls.where((call) => call.method == 'messages'), hasLength(reads));
  });

  test('notification actions coalesce an inbox and visible history refresh',
      () async {
    await service.initialize();
    await service.setVisibleConversation('chat');
    calls.clear();
    events.add({'type': 'invalidate', 'conversationId': 'chat'});
    events.add({'type': 'invalidate', 'conversationId': 'chat'});
    await Future<void>.delayed(const Duration(milliseconds: 450));
    expect(calls.where((call) => call.method == 'conversations'), hasLength(1));
    expect(calls.where((call) => call.method == 'messages'), hasLength(1));
    calls.clear();
    events.add({'type': 'invalidate', 'conversationId': 'unopened'});
    await Future<void>.delayed(const Duration(milliseconds: 450));
    expect(calls.where((call) => call.method == 'conversations'), hasLength(1));
    expect(calls.where((call) => call.method == 'messages'), isEmpty);
  });

  test(
      'missing native component reports unavailable without crashing initialization',
      () async {
    custom = (_) async => throw MissingPluginException('native unavailable');
    await service.initialize();
    expect(service.snapshot.supported, isFalse);
    expect(service.conversations, isEmpty);
  });
}
