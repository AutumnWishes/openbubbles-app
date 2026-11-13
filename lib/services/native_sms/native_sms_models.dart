/// A carrier SMS stored in Android's SMS provider. These records never enter
/// the Apple IDS send path or the iMessage database.
class NativeSmsMessage {
  const NativeSmsMessage({
    required this.id,
    required this.address,
    required this.body,
    required this.date,
    required this.type,
    required this.read,
    required this.status,
    required this.subscriptionId,
    required this.threadId,
    this.errorCode,
  });

  factory NativeSmsMessage.fromMap(Map<dynamic, dynamic> value) {
    int integer(String key, [int fallback = 0]) =>
        (value[key] as num?)?.toInt() ?? fallback;
    return NativeSmsMessage(
      id: integer('id'),
      address: value['address'] as String? ?? '',
      body: value['body'] as String? ?? '',
      date: DateTime.fromMillisecondsSinceEpoch(integer('date')),
      type: integer('type'),
      read: value['read'] == true || value['read'] == 1,
      status: integer('status', -1),
      subscriptionId: integer('subId', -1),
      threadId: integer('threadId'),
      errorCode: (value['errorCode'] as num?)?.toInt(),
    );
  }

  final int id;
  final String address;
  final String body;
  final DateTime date;
  final int type;
  final bool read;
  final int status;
  final int subscriptionId;
  final int threadId;
  final int? errorCode;

  bool get incoming => type == 1;
  bool get pending => type == 4 || type == 6;
  bool get failed => type == 5;

  /// TYPE_SENT means the carrier accepted the SMS, not that it was read.
  String get statusLabel {
    if (incoming) {
      return '';
    }
    if (errorCode == -1000 && pending) {
      return 'Status unknown';
    }
    if (failed) {
      return 'Failed';
    }
    if (pending) {
      return 'Sending';
    }
    if (type == 3) {
      return 'Draft';
    }
    if (type == 2 && status == 0) {
      return 'Delivered';
    }
    if (type == 2 && status >= 64) {
      return 'Delivery failed';
    }
    if (type == 2) {
      return 'Sent';
    }
    return 'Status unavailable';
  }

  String get conversationKey =>
      '$subscriptionId:${threadId > 0 ? 'thread:$threadId' : 'address:${smsAddressKey(address)}'}';
}

/// Strip visual phone-number punctuation only. Never guess a country code or
/// combine a short code with a full number (or two different SIMs).
String smsAddressKey(String address) =>
    address.trim().replaceAll(RegExp(r'[\s().-]'), '');

class NativeSmsSubscription {
  const NativeSmsSubscription({
    required this.id,
    required this.displayName,
    required this.number,
  });

  factory NativeSmsSubscription.fromMap(Map<dynamic, dynamic> value) =>
      NativeSmsSubscription(
        id: (value['id'] as num).toInt(),
        displayName: value['displayName'] as String? ?? 'SIM',
        number: value['number'] as String? ?? '',
      );

  final int id;
  final String displayName;
  final String number;

  String get label =>
      number.isEmpty ? '$displayName (SIM $id)' : '$displayName · $number';
}

class NativeSmsState {
  const NativeSmsState({
    required this.supported,
    required this.isDefault,
    required this.permissionsGranted,
    required this.canRequestDefault,
  });

  const NativeSmsState.unavailable()
      : supported = false,
        isDefault = false,
        permissionsGranted = false,
        canRequestDefault = false;

  factory NativeSmsState.fromMap(Map<dynamic, dynamic> value) => NativeSmsState(
        supported: value['supported'] == true,
        isDefault: value['isDefault'] == true,
        permissionsGranted: value['permissionsGranted'] == true,
        canRequestDefault: value['canRequestDefault'] == true,
      );

  final bool supported;
  final bool isDefault;
  final bool permissionsGranted;
  final bool canRequestDefault;
}

class NativeSmsConversation {
  NativeSmsConversation(List<NativeSmsMessage> messages)
      : messages = List.unmodifiable(messages);

  final List<NativeSmsMessage> messages;
  NativeSmsMessage get latest => messages.first;
  String get key => latest.conversationKey;
  String get address => latest.address;
  int get subscriptionId => latest.subscriptionId;
  int get unreadCount => messages.where((m) => m.incoming && !m.read).length;
}

List<NativeSmsConversation> groupSmsConversations(
    Iterable<NativeSmsMessage> messages) {
  final all = messages.toList();
  final knownThreads = <String, String>{};
  for (final message in all.where((m) => m.threadId > 0)) {
    knownThreads[
            '${message.subscriptionId}:${smsAddressKey(message.address)}'] =
        message.conversationKey;
  }
  final groups = <String, List<NativeSmsMessage>>{};
  for (final message in all) {
    final matchingThread = message.threadId <= 0
        ? knownThreads[
            '${message.subscriptionId}:${smsAddressKey(message.address)}']
        : null;
    (groups[matchingThread ?? message.conversationKey] ??= []).add(message);
  }
  for (final group in groups.values) {
    group.sort((a, b) {
      final byDate = b.date.compareTo(a.date);
      return byDate != 0 ? byDate : b.id.compareTo(a.id);
    });
  }
  final conversations = groups.values.map(NativeSmsConversation.new).toList();
  conversations.sort((a, b) => b.latest.date.compareTo(a.latest.date));
  return conversations;
}
