import 'dart:async';

import 'package:bluebubbles/app/layouts/unified_inbox/unified_conversation_page.dart';
import 'package:bluebubbles/services/native_sms/native_sms_service.dart';
import 'package:bluebubbles/services/rcs/rcs_service.dart';
import 'package:bluebubbles/services/unified_inbox/unified_inbox_service.dart';
import 'package:bluebubbles/services/unified_inbox/unified_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:secure_application/secure_application.dart';

InboxRoute route(String key,
        {bool available = true,
        InboxTransport transport = InboxTransport.rcs,
        String label = 'Personal',
        String? simId = 'personal'}) =>
    InboxRoute(
      key: key,
      providerId: 'chat',
      transport: transport,
      title: 'Synthetic Recipient',
      addresses: const ['+15550101234'],
      localAddress: '+15550104321',
      timestamp: DateTime.fromMillisecondsSinceEpoch(100),
      available: available,
      simId: simId,
      label: label,
    );
InboxMessage message(int index, {bool outgoing = true, String? text}) =>
    InboxMessage(
      id: 'rcs:chat:$index',
      routeKey: 'rcs:chat:personal',
      text: text ?? 'Message $index',
      timestamp: DateTime.fromMillisecondsSinceEpoch(index * 1000),
      outgoing: outgoing,
      transport: InboxTransport.rcs,
      label: 'RCS · +15550104321',
    );

class FakeInbox extends UnifiedInboxService {
  FakeInbox()
      : super(
          sms: NativeSmsService(
              channel: const MethodChannel('test/unified_sms')),
          rcs: RcsService(
              channel: const MethodChannel('test/unified_rcs'),
              events: const Stream.empty()),
        );
  List<InboxRoute> routes = [route('rcs:chat:personal')];
  List<InboxMessage> history = [];
  final List<({String route, String text, String id})> submissions = [];
  final List<String?> visibleTargets = [];
  Future<void> Function()? onSend;
  Completer<void>? pendingHistory;
  int reads = 0, marks = 0, requestIds = 0;
  @override
  List<UnifiedThread> get threads => [UnifiedThread('thread', routes)];
  @override
  UnifiedThread? thread(String id) => id == 'thread' ? threads.single : null;
  @override
  List<InboxMessage> messages(UnifiedThread thread) => history;
  @override
  Future<void> loadThread(UnifiedThread thread, {bool older = false}) async {
    reads++;
    await pendingHistory?.future;
    notifyListeners();
  }

  @override
  Future<void> markRead(UnifiedThread thread) async {
    marks++;
    notifyListeners();
  }

  @override
  Future<void> setVisibleThread(UnifiedThread? thread) async {
    visibleTargets.add(thread?.id);
  }

  @override
  String newRequestId(InboxRoute route) => 'request-${++requestIds}';
  @override
  Future<void> send(InboxRoute route, String body, String requestId) async {
    submissions.add((route: route.key, text: body, id: requestId));
    await onSend?.call();
  }

  void update() => notifyListeners();
  @override
  void dispose() {
    super.dispose();
    sms.dispose();
    rcs.dispose();
  }
}

Future<void> openPage(
  WidgetTester tester,
  FakeInbox service, {
  bool redacted = false,
  bool requireRouteChoice = false,
  String? initialRouteKey,
  SecureApplicationController? security,
  Future<void> Function(InboxRoute)? onOpenApple,
}) async {
  final app = MaterialApp(
      home: Builder(
          builder: (context) => Scaffold(
                body: TextButton(
                    onPressed: () =>
                        Navigator.of(context).push(MaterialPageRoute<void>(
                          builder: (_) => UnifiedConversationPage(
                              service: service,
                              threadId: 'thread',
                              requireRouteChoice: requireRouteChoice,
                              initialRouteKey: initialRouteKey,
                              hideContactInfo: redacted,
                              hideMessageContent: redacted,
                              onOpenApple: onOpenApple),
                        )),
                    child: const Text('Conversation list')),
              )));
  await tester.pumpWidget(security == null
      ? app
      : SecureApplicationProvider(secureData: security, child: app));
  await tester.tap(find.text('Conversation list'));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 400));
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FakeInbox service;
  setUp(() {
    service = FakeInbox();
  });
  tearDown(() {
    service.dispose();
  });

  testWidgets('opens at newest message and back returns to the existing list',
      (tester) async {
    service.history = List.generate(100, (index) => message(index));
    await openPage(tester, service);
    await tester.pumpAndSettle();
    expect(find.text('Message 99'), findsOneWidget);
    expect(find.text('Message 0'), findsNothing);
    final list = tester.widget<ListView>(find.byType(ListView));
    expect(list.reverse, isTrue);
    expect(list.controller!.offset, 0);
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    expect(find.text('Conversation list'), findsOneWidget);
    expect(find.byType(UnifiedConversationPage), findsNothing);
    expect(service.visibleTargets.last, isNull);
  });

  testWidgets('live events preserve draft and focus without rereading history',
      (tester) async {
    await openPage(tester, service);
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const ValueKey('unified-composer')), 'Draft stays here');
    final before = tester
        .widget<TextField>(find.byKey(const ValueKey('unified-composer')));
    expect(before.focusNode!.hasFocus, isTrue);
    service.history = [message(1, outgoing: false)];
    service.update();
    await tester.pumpAndSettle();
    for (var index = 0; index < 5; index++) {
      service.update();
      await tester.pump();
    }
    final after = tester
        .widget<TextField>(find.byKey(const ValueKey('unified-composer')));
    expect(identical(before.controller, after.controller), isTrue);
    expect(after.controller!.text, 'Draft stays here');
    expect(after.focusNode!.hasFocus, isTrue);
    expect(service.reads, 1);
    expect(service.marks, 1);
  });

  testWidgets(
      'connection loss preserves the selected RCS route without SMS fallback',
      (tester) async {
    service.routes
        .add(route('sms:chat', transport: InboxTransport.sms, label: 'Work'));
    await openPage(tester, service);
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const ValueKey('unified-composer')), 'My unsent draft');
    service.routes[0] = route('rcs:chat:personal', available: false);
    service.update();
    await tester.pumpAndSettle();
    final selection = tester.widget<DropdownButtonFormField<String>>(
        find.byKey(const ValueKey('unified-route')));
    expect(selection.initialValue, 'rcs:chat:personal');
    final send =
        tester.widget<IconButton>(find.byKey(const ValueKey('unified-send')));
    expect(send.onPressed, isNull);
    expect(service.submissions, isEmpty);
    expect(find.text('My unsent draft'), findsOneWidget);
    expect(
        find.text('Reconnect or choose an available service.'), findsOneWidget);
  });

  testWidgets(
      'multiple SIMs require a choice and explicit repeat retains the request ID',
      (tester) async {
    service.routes.add(route('rcs:chat:work', label: 'Work', simId: 'work'));
    service.onSend = () async => throw const RcsException(
        'SEND_UNCERTAIN', 'Not confirmed. Refresh first.');
    await openPage(tester, service);
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const ValueKey('unified-composer')), 'Synthetic reply');
    expect(
        tester
            .widget<IconButton>(find.byKey(const ValueKey('unified-send')))
            .onPressed,
        isNull);
    await tester.tap(find.byKey(const ValueKey('unified-route')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('RCS · Work').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('unified-send')));
    await tester.pumpAndSettle();
    expect(service.submissions, hasLength(1));
    expect(service.submissions.single.route, 'rcs:chat:work');
    expect(find.text('Synthetic reply'), findsOneWidget);
    service.update();
    await tester.pumpAndSettle();
    expect(service.submissions, hasLength(1));
    service.onSend = null;
    await tester.tap(find.byKey(const ValueKey('unified-send')));
    await tester.pumpAndSettle();
    expect(service.submissions, hasLength(2));
    expect(service.submissions.last.id, service.submissions.first.id);
    expect(service.requestIds, 1);
    expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('unified-composer')))
            .controller!
            .text,
        isEmpty);
  });

  testWidgets(
      'history loading does not lock the composer or disable a ready send',
      (tester) async {
    service.pendingHistory = Completer<void>();
    await openPage(tester, service);
    await tester.enterText(find.byKey(const ValueKey('unified-composer')),
        'Ready while history loads');
    await tester.pump();
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(
        tester
            .widget<IconButton>(find.byKey(const ValueKey('unified-send')))
            .onPressed,
        isNotNull);
    service.pendingHistory!.complete();
    await tester.pumpAndSettle();
    expect(find.text('Ready while history loads'), findsOneWidget);
  });

  testWidgets('redacted mode hides recipient, message contents and SIM numbers',
      (tester) async {
    service.routes = [route('rcs:chat:personal', label: '+15550104321')];
    service.history = [message(1, text: 'Private message contents')];
    await openPage(tester, service, redacted: true);
    await tester.pumpAndSettle();
    expect(find.text('Synthetic Recipient'), findsNothing);
    expect(find.textContaining('+15550104321'), findsNothing);
    expect(find.text('Private message contents'), findsNothing);
    expect(find.text('Message hidden'), findsOneWidget);
    expect(find.text('Conversation'), findsOneWidget);
  });

  testWidgets(
      'opening the Apple view suspends RCS suppression and restores it on return',
      (tester) async {
    service.routes.add(route('apple:chat',
        transport: InboxTransport.iMessage, label: 'Apple'));
    final appleView = Completer<void>();
    await openPage(tester, service, onOpenApple: (_) => appleView.future);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Open iMessage conversation'));
    await tester.pump();
    expect(service.visibleTargets.last, isNull);
    expect(service.submissions, isEmpty);
    appleView.complete();
    await tester.pumpAndSettle();
    expect(service.visibleTargets.last, 'thread');
  });

  testWidgets(
      'an ambiguous notification requires a choice even with a preferred Apple route',
      (tester) async {
    service.routes.addAll([
      route('rcs:chat:work', label: 'Work', simId: 'work'),
      route('apple:chat', transport: InboxTransport.iMessage, label: 'Apple'),
    ]);
    expect(service.threads.single.preferredRoute!.transport,
        InboxTransport.iMessage);
    await openPage(tester, service,
        requireRouteChoice: true, initialRouteKey: 'rcs:chat:personal');
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('unified-composer')),
        'Choose the correct SIM');
    service.update();
    await tester.pumpAndSettle();
    final selection = tester.widget<DropdownButtonFormField<String>>(
        find.byKey(const ValueKey('unified-route')));
    expect(selection.initialValue, isNull);
    expect(
        tester
            .widget<IconButton>(find.byKey(const ValueKey('unified-send')))
            .onPressed,
        isNull);
    expect(find.text('Open iMessage conversation'), findsNothing);
    expect(service.submissions, isEmpty);
  });

  testWidgets(
      'locked mounted conversations do not mark read or suppress notifications',
      (tester) async {
    final security =
        SecureApplicationController(SecureApplicationState(locked: true));
    service.history = [message(1, outgoing: false)];
    await openPage(tester, service, security: security);
    await tester.pumpAndSettle();
    expect(service.marks, 0);
    expect(service.visibleTargets.whereType<String>(), isEmpty);

    security.value = security.value.copyWith(locked: false);
    await tester.pumpAndSettle();
    expect(service.marks, 1);
    expect(service.visibleTargets.last, 'thread');

    security.value = security.value.copyWith(locked: true);
    expect(service.visibleTargets.last, isNull);
    service.history = [...service.history, message(2, outgoing: false)];
    service.update();
    await tester.pumpAndSettle();
    expect(service.marks, 1);
    expect(service.visibleTargets.last, isNull);

    security.value = security.value.copyWith(locked: false);
    await tester.pumpAndSettle();
    expect(service.marks, 2);
    expect(service.visibleTargets.last, 'thread');
    await tester.pumpWidget(const SizedBox.shrink());
    security.value = security.value.copyWith(locked: true);
    security.dispose();
  });
}
