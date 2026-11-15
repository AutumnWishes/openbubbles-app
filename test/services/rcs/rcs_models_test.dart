import 'package:bluebubbles/services/rcs/rcs_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('a display name is never promoted into a transport address', () {
    final chat = RcsConversation.fromMap({'id': 'c', 'name': '+15550101234'});
    expect(chat.addresses, isEmpty);
    expect(chat.readOnly, isTrue);
  });
  test('group and local identities remain separate from recipient display text',
      () {
    final chat = RcsConversation.fromMap({
      'id': 'c',
      'name': 'Synthetic family',
      'addresses': ['+15550100001', '+15550100002'],
      'localAddresses': ['+15550100003'],
      'readOnly': false
    });
    expect(chat.isGroup, isTrue);
    expect(chat.addresses, hasLength(2));
    expect(chat.localAddresses, ['+15550100003']);
  });
  test(
      'accepted is submission rather than delivered and route mismatch is explicit',
      () {
    final accepted = RcsSendResult.fromMap(
        {'status': 'accepted', 'simId': 'a', 'protocol': 'unconfirmed'});
    expect(accepted.accepted, isTrue);
    expect(accepted.status, isNot('delivered'));
    final mismatch = RcsSendResult.fromMap({
      'status': 'sent',
      'simId': 'a',
      'actualSimId': 'b',
      'protocol': 'SMS'
    });
    expect(mismatch.routeMismatch, isTrue);
  });
}
