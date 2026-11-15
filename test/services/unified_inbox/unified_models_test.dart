import 'package:bluebubbles/services/unified_inbox/unified_models.dart';
import 'package:flutter_test/flutter_test.dart';

InboxRoute route(String key,
        {String peer = '+15550101234',
        String local = '+15550104321',
        InboxTransport transport = InboxTransport.rcs,
        String title = 'Same contact name',
        bool available = true,
        bool group = false,
        List<String>? peers,
        int timestamp = 100,
        String? simId}) =>
    InboxRoute(
        key: key,
        providerId: key.split(':')[1],
        transport: transport,
        title: title,
        addresses: peers ?? [peer],
        localAddress: local,
        available: available,
        isGroup: group,
        timestamp: DateTime.fromMillisecondsSinceEpoch(timestamp),
        simId: simId);

InboxMessage message(String id,
        {String routeKey = 'rcs:chat',
        InboxTransport transport = InboxTransport.rcs,
        String status = '',
        int timestamp = 100}) =>
    InboxMessage(
        id: id,
        routeKey: routeKey,
        text: 'The same intentional reply',
        timestamp: DateTime.fromMillisecondsSinceEpoch(timestamp),
        outgoing: true,
        transport: transport,
        status: status);

void main() {
  test('normalizes explicit international numbers and email, never guesses',
      () {
    expect(canonicalRecipient(' tel:+1 (555) 010-1234 '), 'phone:+15550101234');
    expect(canonicalRecipient(' MAIL@Example.com '), 'email:mail@example.com');
    expect(canonicalRecipient('mailto:MAIL@Example.com'),
        'email:mail@example.com');
    for (final input in [
      '5550101234',
      '15550101234',
      '12345',
      '+123',
      '+0123456789',
      'Daniel',
      '0015550101234',
      'me@example'
    ]) {
      expect(canonicalRecipient(input), isNull, reason: input);
    }
  });

  test('combines only matching normalized local and peer identities', () {
    final threads = groupInboxRoutes([
      route('apple:1',
          transport: InboxTransport.iMessage,
          peer: '+1 (555) 010-1234',
          local: '+1 (555) 010-4321'),
      route('rcs:1'),
      route('sms:1', transport: InboxTransport.sms),
    ]);
    expect(threads, hasLength(1));
    expect(threads.single.routes, hasLength(3));
    expect(threads.single.preferredRoute!.key, 'apple:1');
  });

  test('email identity can match exactly without being inferred from a name',
      () {
    expect(
        groupInboxRoutes([
          route('apple:1', peer: 'Person@Example.com', local: 'Me@Example.com'),
          route('apple:2',
              peer: 'mailto:person@example.com', local: 'me@example.com'),
        ]),
        hasLength(1));
    expect(
        groupInboxRoutes([
          route('apple:1', peer: 'person@example.com'),
          route('rcs:1', peer: '+15550101234'),
          route('rcs:2', peer: '+15550105678'),
        ]),
        hasLength(3));
  });

  test('different and unknown local identities remain separate', () {
    expect(
        groupInboxRoutes([
          route('rcs:personal', local: '+15550104321'),
          route('rcs:work', local: '+15550109999'),
          route('sms:unknown', local: '', transport: InboxTransport.sms),
          route('apple:unknown', local: '', transport: InboxTransport.iMessage),
          route('sms:national',
              local: '5550104321', transport: InboxTransport.sms),
        ]),
        hasLength(5));
  });

  test('group, ambiguous, and unverified recipients stay provider-qualified',
      () {
    final threads = groupInboxRoutes([
      route('rcs:group', group: true),
      route('sms:group', group: true, transport: InboxTransport.sms),
      route('rcs:many', peers: ['+15550101234', '+15550105678']),
      route('sms:none', peers: []),
      route('rcs:name', peer: 'Same contact name'),
      route('sms:name',
          peer: 'Same contact name', transport: InboxTransport.sms),
    ]);
    expect(threads, hasLength(6));
    expect(threads.every((thread) => thread.id.startsWith('route:')), isTrue);
  });

  test(
      'provider-qualified message IDs update records without erasing equal text',
      () {
    final merged = mergeInboxMessages([
      message('rcs:chat:1'),
      message('sms:chat:1',
          routeKey: 'sms:chat', transport: InboxTransport.sms),
      message('rcs:chat:2'),
      message('rcs:chat:1', status: 'Read'),
    ]);
    expect(merged.map((item) => item.id),
        ['rcs:chat:1', 'rcs:chat:2', 'sms:chat:1']);
    expect(merged.first.status, 'Read');
    expect(merged.map((item) => item.text).toSet(), hasLength(1));
  });

  test('multiple available SIM routes require an explicit choice', () {
    final thread = groupInboxRoutes([
      route('rcs:chat:personal', simId: 'personal'),
      route('rcs:chat:work', simId: 'work'),
      route('sms:chat', transport: InboxTransport.sms),
    ]).single;
    expect(thread.preferredRoute, isNull);
    expect(thread.routes.map((item) => item.simId),
        containsAll(['personal', 'work']));
  });

  test('ambiguous local identity keeps SIM choices in one provider thread', () {
    final threads = groupInboxRoutes([
      route('rcs:chat:personal', simId: 'personal', local: ''),
      route('rcs:chat:work', simId: 'work', local: ''),
      route('sms:chat', transport: InboxTransport.sms, local: ''),
    ]);
    expect(threads, hasLength(2));
    final rcsThread = threads
        .singleWhere((thread) => thread.latest.transport == InboxTransport.rcs);
    expect(rcsThread.routes, hasLength(2));
    expect(rcsThread.preferredRoute, isNull);
  });

  test('initial preference does not mutate a previously selected route', () {
    final initial = groupInboxRoutes([
      route('rcs:chat', simId: 'work'),
      route('sms:chat', transport: InboxTransport.sms),
    ]).single;
    final selectedKey = initial.preferredRoute!.key;
    final refreshed = groupInboxRoutes([
      route('rcs:chat', simId: 'work', available: false),
      route('sms:chat', transport: InboxTransport.sms),
    ]).single;
    final frozenSelection =
        refreshed.routes.singleWhere((route) => route.key == selectedKey);
    expect(frozenSelection.transport, InboxTransport.rcs);
    expect(frozenSelection.available, isFalse);
    expect(frozenSelection.simId, 'work');
  });

  test('latest activity sorts threads while provider route keys remain stable',
      () {
    final threads = groupInboxRoutes([
      route('rcs:a', timestamp: 200),
      route('sms:b', peer: '+15550105678', timestamp: 300),
      route('rcs:a', timestamp: 250),
    ]);
    expect(threads.map((item) => item.latest.key), ['sms:b', 'rcs:a']);
    expect(threads.last.routes, hasLength(1));
  });
}
