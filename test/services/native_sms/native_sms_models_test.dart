import 'package:bluebubbles/services/native_sms/native_sms_models.dart';
import 'package:flutter_test/flutter_test.dart';

NativeSmsMessage message({
  int id = 1,
  String address = '+1 (555) 123-4567',
  int type = 1,
  int status = -1,
  int subscriptionId = 1,
  int threadId = 8,
  int date = 1000,
}) =>
    NativeSmsMessage.fromMap({
      'id': id,
      'address': address,
      'body': 'test',
      'type': type,
      'status': status,
      'subId': subscriptionId,
      'threadId': threadId,
      'date': date,
      'read': 0,
    });

void main() {
  test('keeps the same contact on different SIMs in separate conversations',
      () {
    final groups = groupSmsConversations([
      message(id: 1, subscriptionId: 1),
      message(id: 2, subscriptionId: 2),
    ]);
    expect(groups, hasLength(2));
    expect(groups.map((g) => g.subscriptionId).toSet(), {1, 2});
  });

  test('pending native outbox IDs sort by time, not negative ID', () {
    final groups = groupSmsConversations([
      message(id: 100, date: 1000),
      message(id: -1, date: 2000, type: 4),
    ]);
    expect(groups.single.latest.id, -1);
    expect(groups.single.latest.statusLabel, 'Sending');
  });

  test(
      'unassigned outbox thread joins matching provider conversation on same SIM',
      () {
    final groups = groupSmsConversations([
      message(id: 100, date: 1000),
      message(id: -1, date: 2000, type: 4, threadId: 0),
      message(id: -2, date: 2000, type: 4, threadId: 0, subscriptionId: 2),
    ]);
    expect(groups, hasLength(2));
    expect(
        groups.firstWhere((g) => g.subscriptionId == 1).messages, hasLength(2));
  });

  test('carrier accepted is not presented as recipient delivered', () {
    expect(message(type: 2, status: -1).statusLabel, 'Sent');
    expect(message(type: 2, status: 32).statusLabel, 'Sent');
    expect(message(type: 2, status: 0).statusLabel, 'Delivered');
    expect(message(type: 2, status: 64).statusLabel, 'Delivery failed');
    expect(message(type: 5).statusLabel, 'Failed');
    expect(message(type: 6).statusLabel, 'Sending');
  });

  test('phone normalization never guesses country code or merges short codes',
      () {
    expect(smsAddressKey('+1 (555) 123-4567'), '+15551234567');
    expect(smsAddressKey('5551234567'), isNot('+15551234567'));
    expect(smsAddressKey('12345'), '12345');
    expect(smsAddressKey('BANK'), 'BANK');
  });

  test('missing capability flags fail closed', () {
    final state = NativeSmsState.fromMap({'supported': true});
    expect(state.supported, true);
    expect(state.isDefault, false);
    expect(state.canRequestDefault, false);
    expect(state.permissionsGranted, false);
  });
}
