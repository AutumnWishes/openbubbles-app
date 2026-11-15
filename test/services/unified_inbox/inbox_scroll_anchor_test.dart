import 'package:bluebubbles/services/unified_inbox/inbox_scroll_anchor.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('two updates before layout retain the original visible row and offset',
      () {
    final anchor = InboxScrollAnchor(itemExtent: 84);
    anchor.capture(['a', 'b', 'c', 'd'], 84 + 19);
    // A second notification sees changed rows but the old scroll offset.
    anchor.capture(['new', 'a', 'b', 'c', 'd'], 84 + 19);
    expect(anchor.pending, isTrue);
    expect(
        anchor.restore(['newer', 'new', 'a', 'b', 'c', 'd'], maxExtent: 1000),
        3 * 84 + 19);
    expect(anchor.pending, isFalse);
  });

  test(
      'returning to the list retains a moved conversation without jumping to top',
      () {
    final anchor = InboxScrollAnchor(itemExtent: 84);
    anchor.capture(['a', 'b', 'c', 'd'], 2 * 84 + 7);
    expect(anchor.restore(['d', 'a', 'b', 'c'], maxExtent: 1000), 3 * 84 + 7);
    anchor.capture(['d', 'a', 'b', 'c'], 3 * 84 + 7);
    expect(anchor.restore(['d', 'a', 'c'], maxExtent: 150), 150);
  });

  test('top, overscroll, removed anchors and empty lists do not invent a jump',
      () {
    final anchor = InboxScrollAnchor(itemExtent: 84);
    for (final offset in [0.0, -25.0, double.nan, double.infinity, 999.0]) {
      anchor.capture(['a', 'b'], offset);
      expect(anchor.restore(['new', 'a', 'b'], maxExtent: 1000), isNull);
    }
    anchor.capture(['a', 'b'], 84);
    expect(anchor.restore(['a'], maxExtent: 1000), isNull);
    anchor.capture([], 84);
    expect(anchor.restore([], maxExtent: 0), isNull);
  });
}
