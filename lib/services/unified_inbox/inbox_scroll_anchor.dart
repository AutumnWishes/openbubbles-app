/// Capture once before a batch of row updates, then restore after layout.
/// Repeated notifications in the same frame must not anchor to already-moved rows.
class InboxScrollAnchor {
  InboxScrollAnchor({required this.itemExtent});
  final double itemExtent;
  bool _pending = false;
  String? _row;
  double _withinRow = 0;
  bool get pending => _pending;

  void capture(List<String> rows, double offset) {
    if (_pending) return;
    _pending = true;
    _row = null;
    if (!offset.isFinite || offset <= 0 || itemExtent <= 0) return;
    final index = (offset / itemExtent).floor();
    if (index >= rows.length) return;
    _row = rows[index];
    _withinRow = offset % itemExtent;
  }

  double? restore(List<String> rows, {required double maxExtent}) {
    final row = _row;
    _row = null;
    _pending = false;
    if (row == null) return null;
    final index = rows.indexOf(row);
    if (index < 0) return null;
    return (index * itemExtent + _withinRow)
        .clamp(0.0, maxExtent < 0 ? 0.0 : maxExtent);
  }
}
