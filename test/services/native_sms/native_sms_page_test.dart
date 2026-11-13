import 'dart:async';

import 'package:bluebubbles/app/layouts/settings/pages/native_sms_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.openbubbles/native_sms');
  late List<MethodCall> calls;
  late bool granted;
  late List<Map<String, Object>> subscriptions;
  Completer<Map<String, Object>>? pendingSend;

  setUp(() {
    calls = [];
    granted = true;
    subscriptions = [
      {'id': 1, 'displayName': 'Personal', 'number': ''},
    ];
    pendingSend = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getState':
          return {
            'supported': true,
            'isDefault': false,
            'permissionsGranted': granted,
            'canRequestDefault': false,
          };
        case 'getSubscriptions':
          return subscriptions;
        case 'listMessages':
          return [];
        case 'requestPermissions':
          return granted;
        case 'sendSms':
          return pendingSend?.future ??
              {
                'id': -1,
                'address': '12345',
                'body': 'Hello Fold',
                'date': 1000,
                'type': 4,
                'read': true,
                'status': 32,
                'subId': 1,
                'threadId': 0,
              };
        case 'markRead':
          return null;
        default:
          throw MissingPluginException(call.method);
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<void> openDraft(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: NativeSmsPage(initialAddress: '12345', initialBody: 'Hello Fold'),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('prefilled recipient and body open without sending',
      (tester) async {
    await openDraft(tester);
    expect(find.text('12345'), findsOneWidget);
    expect(find.text('Hello Fold'), findsOneWidget);
    expect(calls.where((c) => c.method == 'sendSms'), isEmpty);
    expect(find.text('Carrier SMS'), findsOneWidget);
  });

  testWidgets('denied permissions never open draft or send', (tester) async {
    granted = false;
    await openDraft(tester);
    expect(find.text('Allow SMS access'), findsOneWidget);
    await tester.tap(find.text('Allow SMS access'));
    await tester.pumpAndSettle();
    expect(find.text('Carrier SMS'), findsNothing);
    expect(calls.where((c) => c.method == 'sendSms'), isEmpty);
    expect(calls.where((c) => c.method == 'getSubscriptions'), isEmpty);
  });

  testWidgets('dual SIM requires explicit selection before sending',
      (tester) async {
    subscriptions.add({'id': 2, 'displayName': 'Work', 'number': ''});
    await openDraft(tester);
    var button = tester.widget<IconButton>(find.byWidgetPredicate(
        (widget) => widget is IconButton && widget.tooltip == 'Send SMS'));
    expect(button.onPressed, isNull);
    await tester.tap(find.byType(DropdownButtonFormField<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Work (SIM 2)').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Send SMS'));
    await tester.pumpAndSettle();
    final send = calls.singleWhere((c) => c.method == 'sendSms');
    expect((send.arguments as Map)['subscriptionId'], 2);
  });

  testWidgets('missing active SIM disables send', (tester) async {
    subscriptions = [];
    await openDraft(tester);
    final button = tester.widget<IconButton>(find.byWidgetPredicate(
        (widget) => widget is IconButton && widget.tooltip == 'Send SMS'));
    expect(button.onPressed, isNull);
    expect(calls.where((c) => c.method == 'sendSms'), isEmpty);
  });

  testWidgets('a pending submission disables repeated send taps',
      (tester) async {
    pendingSend = Completer<Map<String, Object>>();
    await openDraft(tester);
    await tester.tap(find.byTooltip('Send SMS'));
    await tester.pump();
    final button = tester.widget<IconButton>(find.byWidgetPredicate(
        (widget) => widget is IconButton && widget.tooltip == 'Send SMS'));
    expect(button.onPressed, isNull);
    await tester.tap(find.byTooltip('Send SMS'));
    await tester.pump();
    expect(calls.where((c) => c.method == 'sendSms'), hasLength(1));
    pendingSend!.complete({
      'id': -1,
      'address': '12345',
      'body': 'Hello Fold',
      'date': 1000,
      'type': 4,
      'read': true,
      'status': 32,
      'subId': 1,
      'threadId': 0,
    });
    await tester.pumpAndSettle();
  });

  testWidgets('phone viewport with keyboard keeps SMS composer usable',
      (tester) async {
    tester.view.physicalSize = const Size(412, 915);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    await openDraft(tester);
    expect(find.byTooltip('Send SMS'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('uncertain send retains draft and reuses idempotency key',
      (tester) async {
    pendingSend = Completer<Map<String, Object>>();
    await openDraft(tester);
    await tester.tap(find.byTooltip('Send SMS'));
    await tester.pump();
    pendingSend!.completeError(PlatformException(code: 'SMS_UNAVAILABLE'));
    await tester.pumpAndSettle();
    expect(find.text('Hello Fold'), findsOneWidget);
    expect(calls.where((c) => c.method == 'sendSms'), hasLength(1));
    // The error snackbar intentionally covers the lower composer briefly.
    // Dismiss it before the user's deliberate second attempt.
    ScaffoldMessenger.of(tester.element(find.byType(Scaffold).last))
        .hideCurrentSnackBar();
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Send SMS'));
    await tester.pumpAndSettle();
    final sends = calls.where((c) => c.method == 'sendSms').toList();
    expect(sends, hasLength(2));
    expect((sends.first.arguments as Map)['clientId'],
        (sends.last.arguments as Map)['clientId']);
  });
}
