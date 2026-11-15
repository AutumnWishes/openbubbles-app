import 'package:bluebubbles/app/layouts/unified_inbox/rcs_app_scope.dart';
import 'package:bluebubbles/services/rcs/rcs_service.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:secure_application/secure_application.dart';

class PendingRcs extends RcsService {
  PendingRcs() : super(events: const Stream.empty());
  @override
  Future<void> initialize() async {}
}

void main() {

  testWidgets('locked notification stays pending until unlock, then opens once',
      (tester) async {
    final rcs = PendingRcs();
    final security =
        SecureApplicationController(SecureApplicationState(locked: true));
    final opened = <String>[];
    rcs.notificationConversation.value = 'conversation-1';
    await tester.pumpWidget(MaterialApp(
        home: SecureApplicationProvider(
            secureData: security,
            child: RcsAppScope(
                service: rcs,
                openConversation: opened.add,
                child: const SizedBox()))));
    await tester.pump();
    expect(opened, isEmpty);
    expect(rcs.notificationConversation.value, 'conversation-1');
    security.value = security.value.copyWith(locked: false);
    await tester.pump();
    expect(opened, ['conversation-1']);
    expect(rcs.notificationConversation.value, isNull);
    security.value = security.value.copyWith(authenticated: true);
    await tester.pump();
    expect(opened, hasLength(1));
    await tester.pumpWidget(const SizedBox());
    security.dispose();
    rcs.dispose();
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets('lock between tap and scheduled frame cannot consume target',
      (tester) async {
    final rcs = PendingRcs();
    final security = SecureApplicationController(SecureApplicationState());
    final opened = <String>[];
    await tester.pumpWidget(MaterialApp(
        home: SecureApplicationProvider(
            secureData: security,
            child: RcsAppScope(
                service: rcs,
                openConversation: opened.add,
                child: const SizedBox()))));
    rcs.notificationConversation.value = 'conversation-2';
    security.value = security.value.copyWith(locked: true);
    await tester.pump();
    expect(opened, isEmpty);
    expect(rcs.notificationConversation.value, 'conversation-2');
    security.value = security.value.copyWith(locked: false);
    await tester.pump();
    expect(opened, ['conversation-2']);
    await tester.pumpWidget(const SizedBox());
    security.dispose();
    rcs.dispose();
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));
}
