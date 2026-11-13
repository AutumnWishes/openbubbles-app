import 'dart:async';

import 'package:bluebubbles/services/native_sms/native_sms_service.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, Object?> smsRow(int id, {int type = 1, int subId = 7}) => {
      'id': id,
      'address': '+15551234567',
      'body': 'Message $id',
      'date': 1700000000000 + id.abs(),
      'type': type,
      'read': false,
      'status': -1,
      'subId': subId,
      'threadId': 11,
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('test/native_sms_contract');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late NativeSmsService service;
  late List<Map<String, Object?>> rows;
  late List<MethodCall> calls;
  late bool permissionGranted;
  late bool denyQuery;
  Future<Object?> Function(MethodCall)? sendHandler;

  setUp(() {
    rows = [];
    calls = [];
    permissionGranted = true;
    denyQuery = false;
    sendHandler = null;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getState':
          return {
            'supported': true,
            'isDefault': false,
            'permissionsGranted': permissionGranted,
            'canRequestDefault': false,
          };
        case 'getSubscriptions':
          return [
            {'id': 7, 'displayName': 'Personal', 'number': ''},
            {'id': 8, 'displayName': 'Work', 'number': ''},
          ];
        case 'listMessages':
          if (denyQuery) {
            throw PlatformException(
                code: 'SMS_PERMISSION_DENIED', message: 'Access revoked');
          }
          final args = Map<Object?, Object?>.from(call.arguments as Map);
          if (args['ids'] != null) {
            final ids = Set<Object?>.from(args['ids'] as List);
            return rows.where((row) => ids.contains(row['id'])).toList();
          }
          final beforeId = args['beforeId'] as int?;
          final provider = rows
              .where((row) =>
                  (row['id']! as int) > 0 &&
                  (beforeId == null || (row['id']! as int) < beforeId))
              .toList()
            ..sort((a, b) => (b['id']! as int).compareTo(a['id']! as int));
          return [
            ...provider.take(args['limit']! as int),
            if (beforeId == null)
              ...rows.where((row) => (row['id']! as int) < 0),
          ];
        case 'sendSms':
          if (sendHandler != null) return sendHandler!(call);
          throw StateError('Unexpected carrier send in a read-only test');
        default:
          throw StateError('Unexpected method ${call.method}');
      }
    });
    service = NativeSmsService(channel: channel);
  });

  tearDown(() {
    service.dispose();
    messenger.setMockMethodCallHandler(channel, null);
  });

  test(
      'a provider receipt replaces the temporary outbox row without duplication',
      () async {
    rows = [smsRow(9), smsRow(-1, type: 4)];
    await service.refresh();
    expect(service.messages.map((m) => m.id), contains(-1));

    rows = [smsRow(9), smsRow(10, type: 2)];
    await service.refresh();

    expect(service.messages.map((m) => m.id).toSet(), {9, 10});
    expect(service.messages.singleWhere((m) => m.id == 10).statusLabel, 'Sent');
  });

  test('history pagination ignores negative outbox IDs', () async {
    rows = [
      for (var id = 1; id <= 205; id++) smsRow(id),
      smsRow(-30, type: 4),
    ];
    await service.refresh();
    expect(service.hasOlder, isTrue);
    await service.loadOlder();

    final olderCall = calls.lastWhere((call) =>
        call.method == 'listMessages' &&
        (call.arguments as Map).containsKey('beforeId'));
    expect((olderCall.arguments as Map)['beforeId'], 6);
    expect(service.messages.length, 206);
    expect(service.hasOlder, isFalse);
  });

  test(
      'refresh removes deleted loaded history and updates older message status',
      () async {
    rows = [for (var id = 1; id <= 205; id++) smsRow(id)];
    await service.refresh();
    await service.loadOlder();
    rows.removeWhere((row) => row['id'] == 1);
    rows[rows.indexWhere((row) => row['id'] == 2)] = smsRow(2, type: 5);

    await service.refresh();

    expect(service.messages.any((m) => m.id == 1), isFalse);
    expect(service.messages.singleWhere((m) => m.id == 2).failed, isTrue);
    expect(
        calls.any((call) =>
            call.method == 'listMessages' &&
            (call.arguments as Map).containsKey('ids')),
        isTrue);
  });

  test('revoked permissions clear cached SMS and SIM information', () async {
    rows = [smsRow(1)];
    await service.refresh();
    permissionGranted = false;

    await service.refresh();

    expect(service.messages, isEmpty);
    expect(service.subscriptions, isEmpty);
    expect(service.state.permissionsGranted, isFalse);
  });

  test(
      'permission lost between state check and provider query clears cached SMS',
      () async {
    rows = [smsRow(1)];
    await service.refresh();
    denyQuery = true;

    await service.refresh();

    expect(service.messages, isEmpty);
    expect(service.subscriptions, isEmpty);
    expect(service.state.permissionsGranted, isFalse);
    expect(service.error, 'Access revoked');
  });

  test('permission revoked during history loading also clears cached SMS', () async {
    rows = [for (var id = 1; id <= 205; id++) smsRow(id)];
    await service.refresh();
    denyQuery = true;

    await service.loadOlder();

    expect(service.messages, isEmpty);
    expect(service.subscriptions, isEmpty);
    expect(service.state.permissionsGranted, isFalse);
    expect(service.error, 'Access revoked');
  });

  test(
      'send passes the chosen SIM and never retries an uncertain carrier request',
      () async {
    await service.refresh();
    sendHandler = (_) async => throw PlatformException(
        code: 'SMS_UNAVAILABLE', message: 'Submission status is unknown');

    await expectLater(
        service.send(
          address: '+15551234567',
          body: 'Hello',
          subscriptionId: 8,
          clientId: 'once',
        ),
        throwsA(isA<PlatformException>()));
    await service.refresh();

    final sends = calls.where((call) => call.method == 'sendSms').toList();
    expect(sends, hasLength(1));
    expect((sends.single.arguments as Map)['subscriptionId'], 8);
    expect((sends.single.arguments as Map)['clientId'], 'once');
  });

  test('an inactive SIM fails before any carrier request', () async {
    await service.refresh();

    await expectLater(
        service.send(
          address: '+15551234567',
          body: 'Hello',
          subscriptionId: 99,
          clientId: 'inactive',
        ),
        throwsStateError);

    expect(calls.where((call) => call.method == 'sendSms'), isEmpty);
  });

  test('the same in-flight client ID cannot submit a second SMS', () async {
    await service.refresh();
    final pending = Completer<Object?>();
    sendHandler = (_) => pending.future;
    final first = service.send(
      address: '+15551234567',
      body: 'Hello',
      subscriptionId: 7,
      clientId: 'one-send',
    );

    await expectLater(
        service.send(
          address: '+15551234567',
          body: 'Hello',
          subscriptionId: 7,
          clientId: 'one-send',
        ),
        throwsStateError);
    final row = smsRow(-1, type: 4);
    rows = [row];
    pending.complete(row);
    await first;
    await service.refresh();

    expect(calls.where((call) => call.method == 'sendSms'), hasLength(1));
  });
}
