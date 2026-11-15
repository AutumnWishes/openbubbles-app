/// Public bridge records. Authentication material is never part of these models.
class RcsSim {
  const RcsSim(
      {required this.id,
      required this.label,
      this.number = '',
      this.active = false,
      this.rcsEnabled = false});
  factory RcsSim.fromMap(Map value) => RcsSim(
        id: _string(value['id']),
        label: _string(value['label']),
        number: _string(value['number']),
        active: value['active'] == true,
        rcsEnabled: value['rcsEnabled'] == true,
      );
  final String id;
  final String label;
  final String number;
  final bool active;
  final bool rcsEnabled;
}

class RcsSnapshot {
  const RcsSnapshot(
      {this.supported = true,
      this.state = 'disconnected',
      this.paired = false,
      this.ready = false,
      this.busy = false,
      this.hasUncertainSend = false,
      this.backgroundRequested = false,
      this.backgroundActive = false,
      this.previewsEnabled = false,
      this.notificationsAllowed = false,
      this.status = '',
      this.errorCode = '',
      this.challenge = '',
      this.sims = const []});

  factory RcsSnapshot.fromMap(Map value) => RcsSnapshot(
        supported: value['supported'] != false,
        state: _string(value['state'], 'disconnected'),
        paired: value['paired'] == true,
        ready: value['ready'] == true,
        busy: value['busy'] == true,
        hasUncertainSend: value['hasUncertainSend'] == true,
        backgroundRequested: value['backgroundRequested'] == true,
        backgroundActive: value['backgroundActive'] == true,
        previewsEnabled: value['previewsEnabled'] == true,
        notificationsAllowed: value['notificationsAllowed'] == true,
        status: _string(value['status']),
        errorCode: _string(value['errorCode']),
        challenge: _string(value['challenge']),
        sims: _maps(value['sims']).map(RcsSim.fromMap).toList(growable: false),
      );

  final bool supported;
  final String state;
  final bool paired;
  final bool ready;
  final bool busy;
  final bool hasUncertainSend;
  final bool backgroundRequested;
  final bool backgroundActive;
  final bool previewsEnabled;
  final bool notificationsAllowed;
  final String status;
  final String errorCode;
  final String challenge;
  final List<RcsSim> sims;

  List<RcsSim> get availableSims =>
      sims.where((sim) => sim.active && sim.rcsEnabled).toList(growable: false);

  RcsSnapshot withState(String next) => RcsSnapshot(
        supported: supported,
        state: next,
        paired: paired,
        ready: next == 'ready',
        busy: busy,
        hasUncertainSend: hasUncertainSend,
        backgroundRequested: backgroundRequested,
        backgroundActive: backgroundActive,
        previewsEnabled: previewsEnabled,
        notificationsAllowed: notificationsAllowed,
        status: status,
        errorCode: errorCode,
        challenge: challenge,
        sims: sims,
      );
}

class RcsConversation {
  const RcsConversation(
      {required this.id,
      required this.name,
      this.preview = '',
      this.rcs = false,
      this.readOnly = true,
      this.unread = false,
      this.simId = '',
      this.sendMode = '',
      this.addresses = const [],
      this.localAddresses = const [],
      this.isGroup = false,
      this.timestampMs = 0});

  factory RcsConversation.fromMap(Map value) {
    final raw = value['addresses'] ?? value['participants'];
    final addresses = raw is List
        ? raw
            .whereType<String>()
            .where((address) => address.trim().isNotEmpty)
            .toSet()
            .toList(growable: false)
        : const <String>[];
    return RcsConversation(
      id: _string(value['id']),
      name: _string(value['name']),
      preview: _string(value['preview']),
      rcs: value['rcs'] == true,
      readOnly: value['readOnly'] != false,
      unread: value['unread'] == true,
      simId: _string(value['simId']),
      sendMode: _string(value['sendMode']),
      addresses: addresses,
      localAddresses: value['localAddresses'] is List
          ? (value['localAddresses'] as List)
              .whereType<String>()
              .toList(growable: false)
          : const [],
      isGroup: value['isGroup'] == true || addresses.length > 1,
      timestampMs: _integer(value['timestampMs']),
    );
  }
  final String id;
  final String name;
  final String preview;
  final bool rcs;
  final bool readOnly;
  final bool unread;
  final String simId;
  final String sendMode;

  /// Transport-supplied addresses only. Never infer identity from display names.
  final List<String> addresses;
  final List<String> localAddresses;
  final bool isGroup;
  final int timestampMs;
  DateTime get timestamp => DateTime.fromMillisecondsSinceEpoch(timestampMs);

  RcsConversation copyWith({String? preview, bool? unread, int? timestampMs}) =>
      RcsConversation(
        id: id,
        name: name,
        preview: preview ?? this.preview,
        rcs: rcs,
        readOnly: readOnly,
        unread: unread ?? this.unread,
        simId: simId,
        sendMode: sendMode,
        addresses: addresses,
        localAddresses: localAddresses,
        isGroup: isGroup,
        timestampMs: timestampMs ?? this.timestampMs,
      );
}

class RcsMessage {
  const RcsMessage(
      {required this.id,
      required this.conversationId,
      this.text = '',
      this.timestampMs = 0,
      this.outgoing = false,
      this.protocol = 'unknown',
      this.status = '',
      this.statusCode = 0,
      this.requestId = '',
      this.old = false,
      this.hasMedia = false,
      this.simId = ''});
  factory RcsMessage.fromMap(Map value) => RcsMessage(
        id: _string(value['id']),
        conversationId: _string(value['conversationId']),
        text: _string(value['text']),
        timestampMs: _integer(value['timestampMs']),
        outgoing: value['outgoing'] == true,
        protocol: _string(value['protocol'], 'unknown'),
        status: _string(value['status']),
        statusCode: _integer(value['statusCode']),
        requestId: _string(value['requestId']),
        old: value['old'] == true,
        hasMedia: value['hasMedia'] == true,
        simId: _string(value['simId']),
      );
  final String id;
  final String conversationId;
  final String text;
  final int timestampMs;
  final bool outgoing;
  final String protocol;
  final String status;
  final int statusCode;
  final String requestId;
  final bool old;
  final bool hasMedia;
  final String simId;
  DateTime get timestamp => DateTime.fromMillisecondsSinceEpoch(timestampMs);
  bool sameContent(RcsMessage other) =>
      id == other.id &&
      conversationId == other.conversationId &&
      text == other.text &&
      timestampMs == other.timestampMs &&
      outgoing == other.outgoing &&
      protocol == other.protocol &&
      statusCode == other.statusCode &&
      requestId == other.requestId &&
      hasMedia == other.hasMedia &&
      simId == other.simId;
}

class RcsSendResult {
  const RcsSendResult(
      {required this.requestId,
      required this.conversationId,
      required this.simId,
      required this.status,
      required this.protocol,
      this.actualSimId = ''});
  factory RcsSendResult.fromMap(Map value) => RcsSendResult(
        requestId: _string(value['requestId']),
        conversationId: _string(value['conversationId']),
        simId: _string(value['simId']),
        status: _string(value['status'], 'unknown'),
        protocol: _string(value['protocol'], 'unconfirmed'),
        actualSimId: _string(value['actualSimId']),
      );
  final String requestId;
  final String conversationId;
  final String simId;
  final String status;
  final String protocol;
  final String actualSimId;
  bool get accepted =>
      const {'accepted', 'sent', 'delivered', 'read'}.contains(status);
  bool get uncertain => const {'submitting', 'unknown'}.contains(status);
  bool get routeMismatch =>
      protocol == 'SMS' ||
      protocol == 'MMS' ||
      (actualSimId.isNotEmpty && actualSimId != simId);
}

String _string(Object? value, [String fallback = '']) =>
    value is String ? value : fallback;
int _integer(Object? value) => value is num ? value.toInt() : 0;
Iterable<Map> _maps(Object? value) =>
    value is List ? value.whereType<Map>() : const <Map>[];
