import 'dart:async';

import 'package:bluebubbles/services/rcs/rcs_service.dart';
import 'package:bluebubbles/services/unified_inbox/unified_inbox_service.dart';
import 'package:bluebubbles/services/unified_inbox/unified_models.dart';
import 'package:flutter/material.dart';
import 'package:secure_application/secure_application.dart';

/// Each composer belongs to an explicitly selected transport and SIM. Live
/// history updates never recreate the draft or change that selection.
class UnifiedConversationPage extends StatefulWidget {
  const UnifiedConversationPage({
    super.key,
    required this.service,
    required this.threadId,
    this.initialRouteKey,
    this.requireRouteChoice = false,
    this.onOpenApple,
    this.hideContactInfo = false,
    this.hideMessageContent = false,
  });

  final UnifiedInboxService service;
  final String threadId;
  final String? initialRouteKey;
  final bool requireRouteChoice;
  final Future<void> Function(InboxRoute)? onOpenApple;
  final bool hideContactInfo;
  final bool hideMessageContent;

  @override
  State<UnifiedConversationPage> createState() =>
      _UnifiedConversationPageState();
}

class _UnifiedConversationPageState extends State<UnifiedConversationPage>
    with WidgetsBindingObserver {
  final TextEditingController _draft = TextEditingController();
  final FocusNode _composerFocus = FocusNode();
  final ScrollController _scroll = ScrollController();
  UnifiedThread? _lastThread;
  SecureApplicationController? _security;
  late String _threadId = widget.threadId;
  String? _routeKey;
  bool _choseInitialRoute = false;
  bool _refreshing = false;
  bool _sending = false;
  bool _visible = false;
  bool _foreground = true;
  bool _markingRead = false;
  bool _openingApple = false;
  String? _visibleThreadId;
  String? _lastReadSignature;
  String? _requestId;
  String? _requestBody;
  String? _requestRoute;
  String? _notice;
  String? _latestMessageId;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.service.addListener(_changed);
    _updateThread();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _syncVisibility();
      unawaited(_load());
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final security = context
        .dependOnInheritedWidgetOfExactType<SecureApplicationProvider>()
        ?.secureData;
    if (!identical(security, _security)) {
      _security?.removeListener(_syncVisibility);
      _security = security;
      _security?.addListener(_syncVisibility);
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _syncVisibility();
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _syncVisibility();
  }

  void _updateThread() {
    var current = widget.service.thread(_threadId);
    if (current == null && _lastThread != null) {
      // More precise provider identity may arrive after the page has opened.
      // Follow the same provider route through a regroup without changing SIM.
      final oldKeys = _lastThread!.routes.map((route) => route.key).toSet();
      current = widget.service.threads
          .where((thread) => thread.routes.any((route) => _routeKey != null
              ? route.key == _routeKey
              : oldKeys.contains(route.key)))
          .firstOrNull;
    }
    if (current == null) return;
    _threadId = current.id;
    _lastThread = current;
    if (!_choseInitialRoute) {
      _routeKey = widget.requireRouteChoice
          ? null
          : widget.initialRouteKey ?? current.preferredRoute?.key;
      _choseInitialRoute = true;
    }
  }

  InboxRoute? get _route => widget.service.threads
      .expand((thread) => thread.routes)
      .where((route) => route.key == _routeKey)
      .firstOrNull;

  void _changed() {
    if (!mounted) return;
    final atLatest = !_scroll.hasClients || _scroll.offset < 80;
    _updateThread();
    final thread = _lastThread;
    final messages = thread == null
        ? const <InboxMessage>[]
        : widget.service.messages(thread);
    final latest = messages.lastOrNull?.id;
    final added = latest != _latestMessageId;
    _latestMessageId = latest;
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (added && atLatest && _scroll.hasClients) _scroll.jumpTo(0);
      _syncVisibility();
      _markRead();
    });
  }

  void _syncVisibility() {
    if (!mounted) return;
    final visible = _foreground &&
        _security?.locked != true &&
        !_openingApple &&
        (ModalRoute.of(context)?.isCurrent ?? true);
    final threadId = visible ? _lastThread?.id : null;
    if (visible != _visible || threadId != _visibleThreadId) {
      _visible = visible;
      _visibleThreadId = threadId;
      unawaited(widget.service.setVisibleThread(visible ? _lastThread : null));
    }
    if (visible) _markRead();
  }

  void _markRead() {
    final thread = _lastThread;
    if (!_visible ||
        _security?.locked == true ||
        _markingRead ||
        thread == null) return;
    final newest = <String, String>{};
    for (final message in widget.service.messages(thread)) {
      if (!message.outgoing) newest[message.routeKey] = message.id;
    }
    final signature =
        (newest.entries.map((entry) => '${entry.key}=${entry.value}').toList()
              ..sort())
            .join('|');
    if (signature.isEmpty || signature == _lastReadSignature) return;
    _lastReadSignature = signature;
    _markingRead = true;
    widget.service.markRead(thread).catchError((Object _) {
      // Reading remains usable if the provider cannot confirm a read receipt.
    }).whenComplete(() {
      _markingRead = false;
      if (mounted && _visible) _markRead();
    });
  }

  Future<void> _load({bool older = false}) async {
    final thread = _lastThread;
    if (thread == null || _refreshing) return;
    setState(() => _refreshing = true);
    try {
      await widget.service.loadThread(thread, older: older);
      if (mounted) _markRead();
    } catch (_) {
      if (mounted) {
        setState(() => _notice =
            'Could not load messages. Check the connection and try again.');
      }
    } finally {
      if (mounted) setState(() => _refreshing = false);
    }
  }

  Future<void> _send() async {
    final route = _route;
    final body = _draft.text;
    if (_sending || route == null || !route.available || body.trim().isEmpty) {
      return;
    }
    if (_requestId == null ||
        _requestBody != body ||
        _requestRoute != route.key) {
      _requestId = widget.service.newRequestId(route);
      _requestBody = body;
      _requestRoute = route.key;
    }
    setState(() {
      _sending = true;
      _notice = null;
    });
    try {
      await widget.service.send(route, body, _requestId!);
      if (!mounted) return;
      if (_draft.text == body) _draft.clear();
      _requestId = null;
      _requestBody = null;
      _requestRoute = null;
      if (_scroll.hasClients) _scroll.jumpTo(0);
    } catch (failure) {
      if (mounted) {
        setState(() => _notice = failure is RcsException
            ? failure.message
            : 'Message submission is not confirmed. Refresh and check the conversation before trying again.');
      }
      // Retain this request ID for the same draft and route. An explicit repeat
      // can ask the native journal for the result without transmitting twice.
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  void _chooseRoute(String? key) {
    if (_sending || key == null) return;
    setState(() {
      _routeKey = key;
      _notice = null;
    });
  }

  Future<void> _openApple(InboxRoute route) async {
    final open = widget.onOpenApple;
    if (open == null || _openingApple) return;
    _openingApple = true;
    _syncVisibility();
    try {
      await open(route);
    } finally {
      _openingApple = false;
      if (mounted) _syncVisibility();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.service.removeListener(_changed);
    _security?.removeListener(_syncVisibility);
    if (_visible) unawaited(widget.service.setVisibleThread(null));
    _draft.dispose();
    _composerFocus.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final thread = _lastThread;
    final route = _route;
    final apple = route?.transport == InboxTransport.iMessage ||
        route?.transport == InboxTransport.appleSms;
    final messages = thread == null
        ? const <InboxMessage>[]
        : widget.service.messages(thread);
    final routes = thread?.routes ?? const <InboxRoute>[];
    final selectedExists = routes.any((route) => route.key == _routeKey);
    final canLoadOlder =
        routes.any((route) => route.transport != InboxTransport.rcs);
    final canSend = route?.available == true && !_sending && !apple;
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
          title: Text(widget.hideContactInfo
              ? 'Conversation'
              : thread?.title ?? 'Conversation'),
          actions: [
            IconButton(
                tooltip: 'Refresh recent messages',
                onPressed: _refreshing ? null : () => _load(),
                icon: const Icon(Icons.refresh)),
          ]),
      body: SafeArea(
          top: false,
          child: Column(children: [
            if (_refreshing) const LinearProgressIndicator(minHeight: 2),
            Expanded(
                child: ListView.builder(
              key: PageStorageKey('unified-messages:${widget.threadId}'),
              controller: _scroll,
              reverse: true,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              itemCount: messages.length + 1,
              itemBuilder: (context, index) {
                if (index == messages.length) {
                  return Padding(
                    padding: const EdgeInsets.all(8),
                    child: Column(children: [
                      if (messages.isEmpty)
                        const Text('No messages loaded yet.'),
                      TextButton(
                          onPressed: _refreshing
                              ? null
                              : () => _load(older: canLoadOlder),
                          child: Text(canLoadOlder
                              ? 'Load older messages'
                              : 'Refresh recent messages')),
                    ]),
                  );
                }
                final message = messages[messages.length - 1 - index];
                return _MessageBubble(
                    key: ValueKey(message.id),
                    message: message,
                    hideContactInfo: widget.hideContactInfo,
                    hideMessageContent: widget.hideMessageContent);
              },
            )),
            if (_notice != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
                child: Text(_notice!, style: TextStyle(color: colors.error)),
              ),
            Padding(
                padding: const EdgeInsets.fromLTRB(12, 4, 12, 6),
                child: DropdownButtonFormField<String>(
                  key: const ValueKey('unified-route'),
                  value: selectedExists ? _routeKey : null,
                  isExpanded: true,
                  decoration: const InputDecoration(
                      labelText: 'Send with',
                      isDense: true,
                      border: OutlineInputBorder()),
                  hint: Text(_routeKey == null
                      ? 'Choose a service and SIM'
                      : 'Selected service is unavailable'),
                  items: routes
                      .map((route) => DropdownMenuItem(
                          value: route.key,
                          child: Text(
                              '${widget.hideContactInfo ? '${route.transportName} · Option ${routes.indexOf(route) + 1}' : route.displayLabel}${route.available ? '' : ' · unavailable'}',
                              overflow: TextOverflow.ellipsis)))
                      .toList(),
                  onChanged: _sending ? null : _chooseRoute,
                )),
            if (apple)
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                child: FilledButton.icon(
                  onPressed: widget.onOpenApple == null
                      ? null
                      : () => _openApple(route!),
                  icon: const Icon(Icons.open_in_new),
                  label: Text('Open ${route!.transportName} conversation'),
                ),
              )
            else
              Padding(
                  padding: const EdgeInsets.fromLTRB(12, 0, 8, 8),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Expanded(
                          child: TextField(
                        key: const ValueKey('unified-composer'),
                        controller: _draft,
                        focusNode: _composerFocus,
                        minLines: 1,
                        maxLines: 5,
                        textCapitalization: TextCapitalization.sentences,
                        decoration: InputDecoration(
                          hintText: route == null
                              ? 'Message'
                              : '${route.transportName} message',
                          border: const OutlineInputBorder(),
                          helperText: route != null && !route.available
                              ? 'Reconnect or choose an available service.'
                              : null,
                        ),
                        onChanged: (_) => setState(() {}),
                      )),
                      IconButton(
                        key: const ValueKey('unified-send'),
                        tooltip: 'Send message',
                        onPressed: canSend && _draft.text.trim().isNotEmpty
                            ? _send
                            : null,
                        icon: _sending
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child:
                                    CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(Icons.send),
                      ),
                    ],
                  )),
          ])),
    );
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble(
      {super.key,
      required this.message,
      required this.hideContactInfo,
      required this.hideMessageContent});
  final InboxMessage message;
  final bool hideContactInfo, hideMessageContent;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final localization = MaterialLocalizations.of(context);
    final date =
        '${localization.formatShortMonthDay(message.timestamp)} ${localization.formatTimeOfDay(TimeOfDay.fromDateTime(message.timestamp))}';
    final details = [
      if (message.label.isNotEmpty && !hideContactInfo) message.label,
      date,
      if (message.outgoing && message.status.isNotEmpty)
        message.status.replaceAll('_', ' ').toLowerCase()
    ];
    return Align(
      alignment:
          message.outgoing ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        constraints:
            BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * .85),
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: message.outgoing
              ? colors.primaryContainer
              : colors.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (hideMessageContent) const Text('Message hidden'),
          if (!hideMessageContent && message.text.isNotEmpty)
            Text(message.text),
          if (!hideMessageContent && message.hasMedia)
            const Text('Attachment · open the original conversation to view'),
          if (!hideMessageContent && message.text.isEmpty && !message.hasMedia)
            const Text('Message content unavailable'),
          const SizedBox(height: 4),
          Text(details.join(' · '),
              style: Theme.of(context).textTheme.labelSmall),
        ]),
      ),
    );
  }
}
