import 'dart:async';
import 'package:flutter/material.dart';
import 'package:bluebubbles/database/database.dart';
import 'package:bluebubbles/database/models.dart';
import 'package:bluebubbles/services/services.dart';
import 'package:bluebubbles/services/unified_inbox/unified_inbox_service.dart';
import 'package:bluebubbles/services/unified_inbox/unified_models.dart';
import 'package:bluebubbles/services/unified_inbox/inbox_scroll_anchor.dart';
import '../conversation_view/pages/conversation_view.dart';
import '../conversation_list/pages/conversation_list.dart';
import '../settings/pages/native_sms_page.dart';
import '../settings/pages/rcs_page.dart';
import '../settings/settings_page.dart';
import 'unified_conversation_page.dart';

List<InboxMessage> _readAppleMessages(InboxRoute route, int limit) {
  if (!ss.settings.finishedSetup.value) return [];
  final chat = Chat.findOne(guid: route.providerId);
  if (chat == null) return [];
  return Chat.getMessages(chat, limit: limit)
      .map((m) => InboxMessage(
            id: 'apple:${m.guid}',
            routeKey: route.key,
            text: m.text ?? '',
            timestamp: m.dateCreated ?? DateTime.fromMillisecondsSinceEpoch(0),
            outgoing: m.isFromMe == true,
            transport: route.transport,
            hasMedia: m.hasAttachments == true,
            label: route.displayLabel,
            status: m.error != 0
                ? 'Failed'
                : m.dateRead != null
                    ? 'Read'
                    : m.dateDelivered != null
                        ? 'Delivered'
                        : '',
          ))
      .toList();
}

class UnifiedInboxPage extends StatefulWidget {
  const UnifiedInboxPage({super.key, this.initialConversationId});
  final String? initialConversationId;
  @override
  State<UnifiedInboxPage> createState() => _UnifiedInboxPageState();
}

class _UnifiedInboxPageState extends State<UnifiedInboxPage>
    with WidgetsBindingObserver {
  final service = UnifiedInboxService.instance;
  final scroll = ScrollController();
  final anchor = InboxScrollAnchor(itemExtent: 84);
  StreamSubscription? appleSubscription;
  StreamSubscription? appleMessageSubscription;
  StreamSubscription? appleSetupSubscription;
  bool appleRefreshScheduled = false;
  List<UnifiedThread> rows = [];
  bool opening = false, initialOpened = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    service.addListener(_updated);
    service.readAppleMessages = _readAppleMessages;
    appleSetupSubscription =
        ss.settings.finishedSetup.listen((_) => _bindApple());
    _bindApple();
    rows = service.threads;
    unawaited(service.initialize().then((_) => _updated()));
  }

  void _bindApple() {
    appleSubscription?.cancel();
    appleMessageSubscription?.cancel();
    appleSubscription = null;
    appleMessageSubscription = null;
    if (ss.settings.finishedSetup.value) {
      // DB changes include read/unread and delivery state updates that the
      // existing Rx chat list intentionally does not publish.
      appleSubscription =
          Database.chats.query().watch().listen((_) => _scheduleAppleRefresh());
      appleMessageSubscription = Database.messages
          .query()
          .watch()
          .listen((_) => _scheduleAppleRefresh());
    }
    _scheduleAppleRefresh();
    if (!ss.settings.finishedSetup.value) {
      // Clear the shared projection even if logout disposes this page before
      // the next frame. The reader itself also checks the current setup state.
      scheduleMicrotask(() {
        if (!ss.settings.finishedSetup.value) service.updateAppleRoutes([]);
      });
    }
  }

  void _scheduleAppleRefresh() {
    if (!mounted || appleRefreshScheduled) return;
    appleRefreshScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      appleRefreshScheduled = false;
      if (mounted) _updateApple();
    });
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  void _updateApple() {
    if (!ss.settings.finishedSetup.value) {
      service.updateAppleRoutes([]);
      return;
    }
    final routes = <InboxRoute>[];
    for (final c in Database.chats.getAll()) {
      if (c.dateDeleted != null ||
          c.isArchived == true ||
          c.isRoutingStub ||
          c.telephonyId != null) continue;
      if (ss.settings.filterUnknownSenders.value &&
          !c.isGroup &&
          c.participants.firstOrNull?.contact == null) continue;
      routes.add(InboxRoute(
          key: 'apple:${c.guid}',
          providerId: c.guid,
          transport: c.isTextForwarding
              ? InboxTransport.appleSms
              : InboxTransport.iMessage,
          title: c.properTitle,
          addresses: c.participants.map((p) => p.address).toList(),
          timestamp: c.latestMessage.dateCreated ??
              DateTime.fromMillisecondsSinceEpoch(0),
          preview: c.latestMessage.text ?? '',
          unread: c.hasUnreadMessage == true,
          isGroup: c.isGroup,
          available: true,
          localAddress: c.usingHandle ?? '',
          label: c.usingHandle
                  ?.replaceFirst('tel:', '')
                  .replaceFirst('mailto:', '') ??
              ''));
    }
    service.updateAppleRoutes(routes);
  }

  void _updated() {
    if (!mounted || opening) return;
    final pendingFrame = anchor.pending;
    anchor.capture(rows.map((row) => row.id).toList(),
        scroll.hasClients ? scroll.offset : 0.0);
    setState(() => rows = service.threads);
    if (pendingFrame) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final offset = anchor.restore(rows.map((row) => row.id).toList(),
          maxExtent: scroll.hasClients ? scroll.position.maxScrollExtent : 0.0);
      if (offset != null && scroll.hasClients) scroll.jumpTo(offset);
      final target = widget.initialConversationId;
      if (!initialOpened && target != null) {
        final thread = rows
            .where((t) => t.routes.any((r) =>
                r.transport == InboxTransport.rcs && r.providerId == target))
            .firstOrNull;
        if (thread != null) {
          initialOpened = true;
          final candidates = thread.routes
              .where((r) =>
                  r.transport == InboxTransport.rcs && r.providerId == target)
              .toList();
          _open(thread,
              initialRouteKey:
                  candidates.length == 1 ? candidates.single.key : null,
              requireRouteChoice: candidates.length > 1);
        }
      }
    });
  }

  Future<void> _openApple(InboxRoute route) async {
    final chat = Chat.findOne(guid: route.providerId);
    if (chat != null) {
      await Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => ConversationView(chat: chat)));
    }
    if (mounted) _scheduleAppleRefresh();
  }

  Future<void> _open(UnifiedThread thread,
      {String? initialRouteKey, bool requireRouteChoice = false}) async {
    opening = true;
    if (thread.onlyApple && thread.routes.length == 1) {
      final chat = Chat.findOne(guid: thread.routes.single.providerId);
      if (chat != null) {
        await Navigator.of(context).push(MaterialPageRoute<void>(
            builder: (_) => ConversationView(chat: chat)));
      }
    } else {
      await Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => UnifiedConversationPage(
              service: service,
              threadId: thread.id,
              initialRouteKey: initialRouteKey,
              requireRouteChoice: requireRouteChoice,
              onOpenApple: _openApple,
              hideContactInfo: ss.settings.redactedMode.value &&
                  ss.settings.hideContactInfo.value,
              hideMessageContent: ss.settings.redactedMode.value &&
                  ss.settings.hideMessageContent.value)));
    }
    opening = false;
    if (!mounted) return;
    _scheduleAppleRefresh();
    _updated();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _scheduleAppleRefresh();
      unawaited(service.refresh());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    service.removeListener(_updated);
    appleSubscription?.cancel();
    appleMessageSubscription?.cancel();
    appleSetupSubscription?.cancel();
    scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Messages'), actions: [
          IconButton(
              tooltip: 'Refresh',
              icon: const Icon(Icons.refresh),
              onPressed: service.refresh),
          PopupMenuButton<String>(
              onSelected: (value) async {
                await Navigator.of(context).push(MaterialPageRoute<void>(
                    builder: (_) => switch (value) {
                          'rcs' => const RcsPage(),
                          'apple' => ConversationList(
                              showArchivedChats: false,
                              showUnknownSenders: false,
                              includePhoneMessages: false),
                          'settings' => SettingsPage(),
                          _ => const NativeSmsPage(),
                        }));
                _scheduleAppleRefresh();
                await service.refresh();
              },
              itemBuilder: (_) => [
                    if (ss.settings.finishedSetup.value)
                      const PopupMenuItem(
                          value: 'apple',
                          child: Text('iMessage inbox and new iMessage')),
                    const PopupMenuItem(
                        value: 'rcs',
                        child: Text('RCS connection and notifications')),
                    const PopupMenuItem(
                        value: 'sms', child: Text('SMS access and new SMS')),
                    const PopupMenuItem(
                        value: 'settings', child: Text('OpenBubbles settings')),
                  ]),
        ]),
        body: Column(children: [
          if (!service.rcs.snapshot.ready)
            ListTile(
                leading: const Icon(Icons.chat_bubble_outline),
                title: Text(service.rcs.snapshot.paired
                    ? 'RCS is reconnecting or offline'
                    : 'Connect Google Messages for RCS'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(builder: (_) => const RcsPage()))),
          if (!service.sms.state.permissionsGranted)
            TextButton(
                onPressed: () async {
                  await service.sms.requestPermissions();
                },
                child: const Text('Allow SMS access for this phone')),
          Expanded(
              child: rows.isEmpty
                  ? const Center(
                      child: Padding(
                          padding: EdgeInsets.all(24),
                          child: Text(
                              'Connect RCS or allow SMS access to load your conversations.',
                              textAlign: TextAlign.center)))
                  : ListView.builder(
                      key: const PageStorageKey('unified-inbox'),
                      controller: scroll,
                      itemExtent: 84,
                      itemCount: rows.length,
                      itemBuilder: (_, index) {
                        final t = rows[index];
                        return ListTile(
                            key: ValueKey(t.id),
                            contentPadding: const EdgeInsets.symmetric(
                                horizontal: 20, vertical: 8),
                            leading: CircleAvatar(
                                child: Icon(t.routes.any((r) =>
                                        r.transport == InboxTransport.iMessage)
                                    ? Icons.chat
                                    : Icons.sms)),
                            title: Text(
                                ss.settings.redactedMode.value &&
                                        ss.settings.hideContactInfo.value
                                    ? 'Conversation'
                                    : (t.title.isEmpty
                                        ? 'Conversation'
                                        : t.title),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                    fontWeight: t.unread
                                        ? FontWeight.bold
                                        : FontWeight.normal)),
                            subtitle: Text(
                                '${t.routes.map((r) => r.transportName).toSet().join(' · ')}\n${ss.settings.redactedMode.value && ss.settings.hideMessageContent.value ? 'Message preview hidden' : t.latest.preview}',
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis),
                            trailing:
                                t.unread ? const Icon(Icons.circle, size: 10) : null,
                            onTap: () => _open(t));
                      })),
        ]),
      );
}
