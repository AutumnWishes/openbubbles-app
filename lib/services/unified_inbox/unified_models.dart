/// Presentation identities never replace provider thread IDs or sending routes.
enum InboxTransport { iMessage, appleSms, sms, rcs }

String? canonicalRecipient(String address) {
  var value = address.trim();
  if (value.startsWith('tel:')) value = value.substring(4);
  if (value.startsWith('mailto:')) value = value.substring(7);
  if (RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$').hasMatch(value)) {
    return 'email:${value.toLowerCase()}';
  }
  value = value.replaceAll(RegExp(r'[\s().-]'), '');
  // No guessed country codes, suffix matching, contact-name matching or short codes.
  return RegExp(r'^\+[1-9][0-9]{6,14}$').hasMatch(value)
      ? 'phone:$value'
      : null;
}

class InboxRoute {
  const InboxRoute(
      {required this.key,
      required this.providerId,
      required this.transport,
      required this.title,
      required this.addresses,
      required this.timestamp,
      this.preview = '',
      this.unread = false,
      this.isGroup = false,
      this.available = false,
      this.simId,
      this.subscriptionId,
      this.localAddress = '',
      this.label = ''});
  final String key, providerId, title, preview, localAddress, label;
  final InboxTransport transport;
  final List<String> addresses;
  final DateTime timestamp;
  final bool unread, isGroup, available;
  final String? simId;
  final int? subscriptionId;

  String get identity {
    final peers = addresses.map(canonicalRecipient).whereType<String>().toSet();
    final local = canonicalRecipient(localAddress);
    if (isGroup || addresses.length != 1 || peers.length != 1 || local == null) {
      return 'route:${transport.name}:$providerId';
    }
    return 'recipient:$local:${peers.single}';
  }

  String get transportName => switch (transport) {
        InboxTransport.iMessage => 'iMessage',
        InboxTransport.appleSms => 'iPhone SMS',
        InboxTransport.sms => 'SMS',
        InboxTransport.rcs => 'RCS',
      };
  String get displayLabel =>
      label.isEmpty ? transportName : '$transportName · $label';
}

class UnifiedThread {
  UnifiedThread(this.id, Iterable<InboxRoute> source)
      : routes = List.unmodifiable(source);
  final String id;
  final List<InboxRoute> routes;
  InboxRoute get latest =>
      (routes.toList()..sort((a, b) => b.timestamp.compareTo(a.timestamp)))
          .first;
  String get title =>
      routes
          .where((r) => r.transport == InboxTransport.iMessage)
          .firstOrNull
          ?.title ??
      latest.title;
  bool get unread => routes.any((r) => r.unread);
  bool get onlyApple => routes.every((r) =>
      r.transport == InboxTransport.iMessage ||
      r.transport == InboxTransport.appleSms);
  InboxRoute? get preferredRoute {
    for (final transport in [
      InboxTransport.iMessage,
      InboxTransport.rcs,
      InboxTransport.sms,
      InboxTransport.appleSms
    ]) {
      final usable =
          routes.where((r) => r.transport == transport && r.available).toList();
      // Multiple SIMs/routes always require an explicit choice.
      if (usable.length > 1) return null;
      if (usable.length == 1) return usable.single;
    }
    return null;
  }
}

List<UnifiedThread> groupInboxRoutes(Iterable<InboxRoute> routes) {
  final groups = <String, Map<String, InboxRoute>>{};
  for (final route in routes) {
    groups.putIfAbsent(route.identity, () => {})[route.key] = route;
  }
  final threads =
      groups.entries.map((e) => UnifiedThread(e.key, e.value.values)).toList();
  threads.sort((a, b) {
    final order = b.latest.timestamp.compareTo(a.latest.timestamp);
    return order == 0 ? a.id.compareTo(b.id) : order;
  });
  return threads;
}

class InboxMessage {
  const InboxMessage(
      {required this.id,
      required this.routeKey,
      required this.text,
      required this.timestamp,
      required this.outgoing,
      required this.transport,
      this.status = '',
      this.hasMedia = false,
      this.label = ''});
  final String id, routeKey, text, status, label;
  final DateTime timestamp;
  final bool outgoing, hasMedia;
  final InboxTransport transport;
}

/// Only stable provider-qualified IDs identify duplicate records. Repeated text
/// and nearby timestamps can be intentional, so they are never deduplication keys.
List<InboxMessage> mergeInboxMessages(Iterable<InboxMessage> messages) {
  final byId = <String, InboxMessage>{};
  for (final message in messages) {
    byId[message.id] = message;
  }
  return byId.values.toList()
    ..sort((a, b) {
      final order = a.timestamp.compareTo(b.timestamp);
      return order == 0 ? a.id.compareTo(b.id) : order;
    });
}
