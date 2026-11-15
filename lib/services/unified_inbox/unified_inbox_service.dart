import 'dart:async';
import 'package:flutter/foundation.dart';
import '../native_sms/native_sms_service.dart';
import '../rcs/rcs_service.dart';
import 'unified_models.dart';

/// A presentation layer only. Provider histories and sending IDs stay separate.
class UnifiedInboxService extends ChangeNotifier {
  UnifiedInboxService({NativeSmsService? sms, RcsService? rcs})
      : sms = sms ?? NativeSmsService.instance,
        rcs = rcs ?? RcsService.instance {
    this.sms.addListener(_changed);
    this.rcs.addListener(_changed);
  }
  static final instance = UnifiedInboxService();
  final NativeSmsService sms;
  final RcsService rcs;
  List<InboxRoute> appleRoutes = [];
  List<InboxMessage> Function(InboxRoute, int)? readAppleMessages;
  final Map<String, int> _limits = {};
  Future<void>? _initializing;
  final Map<String, Future<void>> _smsReadOperations = {};
  final Map<String, Future<void>> _rcsReadOperations = {};
  final Map<String, String> _rcsReadThrough = {};
  int _readGeneration = 0;
  bool _wasPaired = false;
  void _changed() {
    if (_wasPaired && !rcs.snapshot.paired) {
      _readGeneration++;
      _rcsReadOperations.clear();
      _rcsReadThrough.clear();
    }
    _wasPaired = rcs.snapshot.paired;
    notifyListeners();
  }

  Future<void> initialize() => _initializing ??= refresh();
  Future<void> refresh() async {
    await Future.wait([sms.refresh(), rcs.initialize()]);
    await rcs.refreshSnapshot();
    await rcs.refreshConversations();
  }

  void updateAppleRoutes(List<InboxRoute> routes) {
    appleRoutes = routes;
    notifyListeners();
  }

  List<UnifiedThread> get threads {
    final routes = <InboxRoute>[...appleRoutes];
    for (final c in sms.conversations) {
      final sim =
          sms.subscriptions.where((s) => s.id == c.subscriptionId).firstOrNull;
      routes.add(InboxRoute(
          key: 'sms:${c.key}',
          providerId: c.key,
          transport: InboxTransport.sms,
          title: c.address,
          addresses: [c.address],
          timestamp: c.latest.date,
          preview: c.latest.body,
          unread: c.unreadCount > 0,
          available: sms.state.permissionsGranted && sim != null,
          subscriptionId: c.subscriptionId,
          localAddress: sim?.number ?? '',
          label: sim?.label ?? 'SIM ${c.subscriptionId}'));
    }
    final nativeSmsIdentities = routes
        .where((route) => route.transport == InboxTransport.sms)
        .map((route) => route.identity)
        .where((identity) => identity.startsWith('recipient:'))
        .toSet();
    for (final c in rcs.conversations) {
      // An existing Google SMS thread can contain older RCS/MMS records. Attach
      // that history only to an exactly verified native SMS identity, without
      // adding another SMS row or enabling an RCS send on a non-RCS thread.
      final sims = c.simId.isNotEmpty
          ? rcs.snapshot.availableSims.where((s) => s.id == c.simId).toList()
          : rcs.snapshot.availableSims;
      final candidates = c.simId.isNotEmpty
          ? [c.simId]
          : sims.isEmpty
              ? <String>['']
              : sims.map((s) => s.id).toList();
      for (final simId in candidates) {
        final sim = rcs.snapshot.sims.where((s) => s.id == simId).firstOrNull;
        final simAvailable = sims.any((s) => s.id == simId);
        final route = InboxRoute(
            key: 'rcs:${c.id}:$simId',
            providerId: c.id,
            transport: InboxTransport.rcs,
            title: c.name,
            addresses: c.addresses,
            timestamp: c.timestamp,
            preview: c.preview,
            unread: c.unread,
            isGroup: c.isGroup,
            available:
                rcs.snapshot.ready && c.rcs && !c.readOnly && simAvailable,
            simId: simId,
            localAddress:
                c.localAddresses.length == 1 ? c.localAddresses.single : '',
            label:
                c.rcs ? (sim?.label ?? 'SIM unavailable') : 'Google history');
        if (!c.rcs &&
            sms.state.permissionsGranted &&
            !nativeSmsIdentities.contains(route.identity)) continue;
        routes.add(route);
      }
    }
    return groupInboxRoutes(routes);
  }

  UnifiedThread? thread(String id) =>
      threads.where((t) => t.id == id).firstOrNull;
  List<InboxMessage> messages(UnifiedThread thread) {
    final items = <InboxMessage>[];
    final loadedRcs = <String>{};
    for (final route in thread.routes) {
      switch (route.transport) {
        case InboxTransport.sms:
          final c = sms.conversations
              .where((c) => c.key == route.providerId)
              .firstOrNull;
          for (final m in c?.messages ?? []) {
            items.add(InboxMessage(
                id: 'sms:${m.id}',
                routeKey: route.key,
                text: m.body,
                timestamp: m.date,
                outgoing: !m.incoming,
                transport: route.transport,
                status: m.statusLabel,
                label: route.displayLabel));
          }
        case InboxTransport.rcs:
          if (!loadedRcs.add(route.providerId)) continue;
          for (final m in rcs.messagesFor(route.providerId)) {
            if (m.protocol.toLowerCase() == 'sms' &&
                sms.state.permissionsGranted) continue;
            final actualSim =
                rcs.snapshot.sims.where((sim) => sim.id == m.simId).firstOrNull;
            // Send candidates never determine a historical message's SIM.
            final messageRoute = m.simId.isEmpty
                ? 'rcs:${route.providerId}'
                : 'rcs:${route.providerId}:${m.simId}';
            final rcsLabel = m.simId.isEmpty
                ? 'RCS'
                : 'RCS · ${actualSim?.label ?? 'SIM unavailable'}';
            items.add(InboxMessage(
                id: 'rcs:${route.providerId}:${m.id}',
                routeKey: messageRoute,
                text: m.text,
                timestamp: m.timestamp,
                outgoing: m.outgoing,
                transport: route.transport,
                status: m.status,
                hasMedia: m.hasMedia,
                label:
                    m.protocol == 'RCS' ? rcsLabel : m.protocol.toUpperCase()));
          }
        case InboxTransport.iMessage:
        case InboxTransport.appleSms:
          items.addAll(
              readAppleMessages?.call(route, _limits[thread.id] ?? 100) ?? []);
      }
    }
    return mergeInboxMessages(items);
  }

  Future<void> loadThread(UnifiedThread thread, {bool older = false}) async {
    if (older) {
      _limits[thread.id] = (_limits[thread.id] ?? 100) + 100;
      await sms.loadOlder();
    }
    await Future.wait(thread.routes
        .where((r) => r.transport == InboxTransport.rcs)
        .map((r) => r.providerId)
        .toSet()
        .map(rcs.loadMessages));
    notifyListeners();
  }

  String newRequestId(InboxRoute route) => route.transport == InboxTransport.sms
      ? sms.newClientId()
      : rcs.newRequestId();
  Future<void> send(InboxRoute route, String body, String requestId) async {
    final current = threads
        .expand((t) => t.routes)
        .where((r) => r.key == route.key)
        .firstOrNull;
    if (current == null || !current.available) {
      throw StateError(
          'This route is unavailable. Reconnect or choose a service.');
    }
    if (route.transport == InboxTransport.sms) {
      await sms.send(
          address: current.addresses.single,
          body: body,
          subscriptionId: current.subscriptionId!,
          clientId: requestId);
    } else if (route.transport == InboxTransport.rcs) {
      final result = await rcs.send(
          conversationId: current.providerId,
          simId: current.simId!,
          text: body,
          requestId: requestId);
      if (result.routeMismatch || !result.accepted) {
        throw const RcsException('SEND_UNCERTAIN',
            'Delivery is not confirmed. Refresh before retrying; the same request will not be sent twice.');
      }
    } else {
      throw StateError(
          'Open the iMessage conversation to send with this service.');
    }
  }

  Future<void> markRead(UnifiedThread thread) async {
    final generation = _readGeneration;
    final seen = <String>{};
    for (final route in thread.routes) {
      if (generation != _readGeneration) return;
      if (!seen.add('${route.transport.name}:${route.providerId}')) continue;
      if (route.transport == InboxTransport.sms) {
        await _markSmsRead(route.providerId);
      } else if (route.transport == InboxTransport.rcs) {
        await _markRcsRead(route.providerId);
      }
    }
  }

  Future<void> _markSmsRead(String providerId) {
    final pending = _smsReadOperations[providerId];
    if (pending != null) return pending;
    final conversation =
        sms.conversations.where((c) => c.key == providerId).firstOrNull;
    if (conversation == null || conversation.unreadCount == 0) {
      return Future.value();
    }
    final done = Completer<void>();
    _smsReadOperations[providerId] = done.future;
    () async {
      try {
        await sms.markRead(conversation.messages);
        done.complete();
      } catch (error, stack) {
        done.completeError(error, stack);
      } finally {
        _smsReadOperations.remove(providerId);
      }
    }();
    return done.future;
  }

  Future<void> _markRcsRead(String providerId) {
    final pending = _rcsReadOperations[providerId];
    if (pending != null) {
      final generation = _readGeneration;
      return pending.then((_) {
        if (generation == _readGeneration) return _markRcsRead(providerId);
      });
    }
    if (!rcs.snapshot.ready ||
        !rcs.snapshot.paired ||
        rcs.conversation(providerId)?.rcs != true) return Future.value();
    final incoming = rcs
        .messagesFor(providerId)
        .where((m) =>
            !m.outgoing &&
            m.protocol == 'RCS' &&
            const {100, 108, 109}.contains(m.statusCode))
        .toList()
      ..sort((a, b) {
        final time = b.timestampMs.compareTo(a.timestampMs);
        return time == 0 ? b.id.compareTo(a.id) : time;
      });
    if (incoming.isEmpty || _rcsReadThrough[providerId] == incoming.first.id) {
      return Future.value();
    }
    final messageId = incoming.first.id;
    final generation = _readGeneration;
    final done = Completer<void>();
    _rcsReadOperations[providerId] = done.future;
    () async {
      try {
        await rcs.markRead(conversationId: providerId, messageId: messageId);
        if (generation == _readGeneration) {
          _rcsReadThrough[providerId] = messageId;
          if (_rcsReadThrough.length > 512) {
            _rcsReadThrough.remove(_rcsReadThrough.keys.first);
          }
        }
        done.complete();
      } catch (error, stack) {
        done.completeError(error, stack);
      } finally {
        if (identical(_rcsReadOperations[providerId], done.future)) {
          _rcsReadOperations.remove(providerId);
        }
      }
    }();
    return done.future;
  }

  Future<void> setVisibleThread(UnifiedThread? thread) =>
      rcs.setVisibleConversation(thread?.routes
          .where((r) => r.transport == InboxTransport.rcs)
          .firstOrNull
          ?.providerId);
  @override
  void dispose() {
    sms.removeListener(_changed);
    rcs.removeListener(_changed);
    super.dispose();
  }
}
