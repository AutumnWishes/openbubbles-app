import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:secure_application/secure_application.dart';
import 'package:bluebubbles/services/rcs/rcs_service.dart';
import 'unified_inbox_page.dart';

/// Installed inside the existing secure gate. A tap builds an inbox underneath
/// the target conversation, so Android Back returns to the conversation list.
class RcsAppScope extends StatefulWidget {
  const RcsAppScope(
      {super.key, required this.child, this.service, this.openConversation});
  final Widget child;
  final RcsService? service;
  final void Function(String)? openConversation;
  @override
  State<RcsAppScope> createState() => _RcsAppScopeState();
}

class _RcsAppScopeState extends State<RcsAppScope> {
  late final service = widget.service ?? RcsService.instance;
  SecureApplicationController? security;
  bool scheduled = false;
  bool get supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
  @override
  void initState() {
    super.initState();
    if (supported) {
      service.notificationConversation.addListener(_notification);
      unawaited(service.initialize().then((_) => _notification()));
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final current = context
        .dependOnInheritedWidgetOfExactType<SecureApplicationProvider>()
        ?.secureData;
    if (current != security) {
      security?.removeListener(_notification);
      security = current;
      security?.addListener(_notification);
    }
    if (supported) _notification();
  }

  void _notification() {
    if (!mounted ||
        !supported ||
        scheduled ||
        security?.locked == true ||
        service.notificationConversation.value == null) {
      return;
    }
    scheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      scheduled = false;
      if (!mounted || security?.locked == true) return;
      final navigator = Get.key.currentState;
      if (navigator == null && widget.openConversation == null) return;
      final target = service.consumeNotificationConversation();
      if (target != null) {
        if (widget.openConversation != null) {
          widget.openConversation!(target);
        } else {
          navigator!.push(MaterialPageRoute<void>(
              builder: (_) => UnifiedInboxPage(initialConversationId: target)));
        }
      }
    });
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  @override
  void dispose() {
    security?.removeListener(_notification);
    if (supported) {
      service.notificationConversation.removeListener(_notification);
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (supported) _notification();
    return widget.child;
  }
}

class UnifiedInboxGate extends StatelessWidget {
  const UnifiedInboxGate({super.key, required this.child, this.enabled = true});
  final bool enabled;
  final Widget child;
  @override
  Widget build(BuildContext context) {
    if (!enabled || kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return child;
    }
    return AnimatedBuilder(
        animation: RcsService.instance,
        builder: (_, __) => RcsService.instance.snapshot.paired
            ? const UnifiedInboxPage()
            : child);
  }
}
