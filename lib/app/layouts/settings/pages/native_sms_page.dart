import 'dart:async';

import 'package:bluebubbles/services/native_sms/native_sms_models.dart';
import 'package:bluebubbles/services/native_sms/native_sms_service.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Carrier SMS on this Android phone, kept separate from Apple SMS forwarding.
/// An initial recipient/body opens the composer but never sends automatically.
class NativeSmsPage extends StatefulWidget {
  const NativeSmsPage(
      {super.key,
      this.initialAddress,
      this.initialBody,
      this.standalone = false});

  final String? initialAddress;
  final String? initialBody;
  final bool standalone;

  @override
  State<NativeSmsPage> createState() => _NativeSmsPageState();
}

class _NativeSmsPageState extends State<NativeSmsPage>
    with WidgetsBindingObserver {
  final NativeSmsService _sms = NativeSmsService();
  bool _openingAccess = false;
  bool _openedInitial = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_refresh());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_refresh());
    }
  }

  Future<void> _refresh() async {
    await _sms.refresh();
    if (!mounted || _openedInitial || !_sms.state.permissionsGranted) {
      return;
    }
    final address = widget.initialAddress?.trim();
    if (address == null || address.isEmpty) {
      return;
    }
    _openedInitial = true;
    await _openConversation(address: address, body: widget.initialBody);
  }

  Future<void> _access({bool makeDefault = false}) async {
    setState(() => _openingAccess = true);
    try {
      if (makeDefault) {
        await _sms.requestDefault();
      } else {
        await _sms.requestPermissions();
      }
      await _refresh();
    } catch (e) {
      if (mounted) {
        _showError(context, e);
      }
    } finally {
      if (mounted) {
        setState(() => _openingAccess = false);
      }
    }
  }

  Future<void> _openConversation({
    required String address,
    int? subscriptionId,
    int? threadId,
    String? body,
  }) async {
    await Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => _NativeSmsConversationPage(
        sms: _sms,
        address: address,
        subscriptionId: subscriptionId,
        threadId: threadId,
        initialBody: body,
      ),
    ));
  }

  Future<void> _compose() async {
    final controller = TextEditingController();
    final address = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('New SMS'),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: TextInputType.phone,
          decoration:
              const InputDecoration(labelText: 'Phone number or short code'),
          onSubmitted: (value) {
            if (value.trim().isNotEmpty) {
              Navigator.pop(context, value.trim());
            }
          },
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              if (controller.text.trim().isNotEmpty) {
                Navigator.pop(context, controller.text.trim());
              }
            },
            child: const Text('Continue'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (address != null && mounted) {
      await _openConversation(address: address);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sms.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _sms,
        builder: (context, _) {
          final conversations = _sms.conversations;
          return Scaffold(
            appBar: AppBar(
              title: const Text('SMS on this phone'),
              actions: [
                IconButton(
                  tooltip: 'Refresh SMS',
                  onPressed: _sms.loading ? null : _refresh,
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
            floatingActionButton: _sms.state.permissionsGranted
                ? FloatingActionButton.extended(
                    onPressed: _compose,
                    icon: const Icon(Icons.edit_outlined),
                    label: const Text('New SMS'),
                  )
                : null,
            body: SafeArea(
              child: Column(
                children: [
                  if (_sms.loading) const LinearProgressIndicator(),
                  _InfoCard(
                    text: widget.standalone
                        ? 'Test SMS through this phone’s SIM. This test app does '
                            'not include iMessage. Photos, group MMS and RCS stay '
                            'in your current messaging app.'
                        : 'Send plain SMS through this phone’s SIM. '
                            'iMessages stay in the main inbox. Photos, group MMS and '
                            'RCS remain in your current messaging app.',
                    icon: Icons.sim_card_outlined,
                  ),
                  if (_sms.error != null)
                    _InfoCard(
                        text: _sms.error!,
                        icon: Icons.error_outline,
                        error: true),
                  if (!_sms.state.supported && !_sms.loading)
                    const Expanded(
                      child: Center(
                          child: Text(
                              'Native SMS is available on Android phones.')),
                    )
                  else if (!_sms.state.permissionsGranted)
                    Expanded(
                      child: Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const Text(
                                'Allow access to SMS and the phone’s SIMs to read '
                                'and send carrier texts here.',
                                textAlign: TextAlign.center,
                              ),
                              const SizedBox(height: 16),
                              FilledButton(
                                onPressed:
                                    _openingAccess || !_sms.state.supported
                                        ? null
                                        : () => _access(),
                                child: const Text('Allow SMS access'),
                              ),
                            ],
                          ),
                        ),
                      ),
                    )
                  else ...[
                    if (_sms.state.canRequestDefault && !_sms.state.isDefault)
                      TextButton(
                        onPressed: _openingAccess
                            ? null
                            : () => _access(makeDefault: true),
                        child: const Text('Set as default SMS app'),
                      ),
                    if (!_sms.state.isDefault)
                      const Padding(
                        padding:
                            EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                        child: Text(
                          'Your default messaging app continues to receive notifications. '
                          'Reading here does not clear its unread count.',
                          style: TextStyle(fontSize: 12),
                        ),
                      ),
                    Expanded(
                      child: RefreshIndicator(
                        onRefresh: _refresh,
                        child: ListView.builder(
                          physics: const AlwaysScrollableScrollPhysics(),
                          padding: const EdgeInsets.only(bottom: 96),
                          itemCount: conversations.length + 1,
                          itemBuilder: (context, index) {
                            if (index == conversations.length) {
                              if (_sms.hasOlder) {
                                return _LoadOlderButton(onLoad: _sms.loadOlder);
                              }
                              if (conversations.isEmpty) {
                                return const Padding(
                                  padding: EdgeInsets.all(40),
                                  child: Text('No SMS messages found.',
                                      textAlign: TextAlign.center),
                                );
                              }
                              return const SizedBox.shrink();
                            }
                            final conversation = conversations[index];
                            final latest = conversation.latest;
                            return ListTile(
                              leading: CircleAvatar(
                                child: Icon(conversation.unreadCount > 0
                                    ? Icons.mark_chat_unread_outlined
                                    : Icons.sms_outlined),
                              ),
                              title: Text(conversation.address,
                                  style: TextStyle(
                                    fontWeight: conversation.unreadCount > 0
                                        ? FontWeight.bold
                                        : FontWeight.normal,
                                  )),
                              subtitle: Text(
                                '${_simLabel(_sms, latest.subscriptionId)} · '
                                '${latest.statusLabel.isEmpty ? '' : '${latest.statusLabel}: '}'
                                '${latest.body}',
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                              ),
                              trailing: Text(MaterialLocalizations.of(context)
                                  .formatShortDate(latest.date)),
                              onTap: () => _openConversation(
                                address: conversation.address,
                                subscriptionId: conversation.subscriptionId,
                                threadId: latest.threadId,
                              ),
                            );
                          },
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          );
        },
      );
}

class _NativeSmsConversationPage extends StatefulWidget {
  const _NativeSmsConversationPage({
    required this.sms,
    required this.address,
    this.subscriptionId,
    this.threadId,
    this.initialBody,
  });

  final NativeSmsService sms;
  final String address;
  final int? subscriptionId;
  final int? threadId;
  final String? initialBody;

  @override
  State<_NativeSmsConversationPage> createState() =>
      _NativeSmsConversationPageState();
}

class _NativeSmsConversationPageState
    extends State<_NativeSmsConversationPage> {
  late final TextEditingController _body;
  int? _subscriptionId;
  bool _sending = false;
  String? _clientId;
  String? _clientBody;
  int? _clientSubscription;
  final Set<int> _markedRead = {};

  @override
  void initState() {
    super.initState();
    _body = TextEditingController(text: widget.initialBody ?? '');
    _subscriptionId = widget.subscriptionId;
    if (_subscriptionId == null && widget.sms.subscriptions.length == 1) {
      _subscriptionId = widget.sms.subscriptions.single.id;
    }
    widget.sms.addListener(_readIncoming);
    WidgetsBinding.instance.addPostFrameCallback((_) => _readIncoming());
  }

  List<NativeSmsMessage> get _messages => widget.sms.messages.where((m) {
        final sameThread = widget.threadId != null &&
            widget.threadId! > 0 &&
            m.threadId == widget.threadId;
        final sameAddress =
            smsAddressKey(m.address) == smsAddressKey(widget.address);
        // Choosing another SIM is explicit. History from the original SIM is
        // never silently merged into the new line's conversation.
        return (sameThread || sameAddress) &&
            m.subscriptionId == _subscriptionId;
      }).toList();

  void _readIncoming() {
    if (!mounted) {
      return;
    }
    final unread = _messages
        .where((m) => m.incoming && !m.read && !_markedRead.contains(m.id))
        .toList();
    if (unread.isEmpty) {
      return;
    }
    _markedRead.addAll(unread.map((m) => m.id));
    unawaited(widget.sms.markRead(unread).catchError((Object e) {
      if (mounted) {
        _showError(context, e);
      }
    }));
  }

  Future<void> _send() async {
    if (_sending || _body.text.trim().isEmpty || _subscriptionId == null) {
      return;
    }
    final submittedBody = _body.text;
    final submittedSubscription = _subscriptionId!;
    // Preserve the idempotency key after errors. Editing the draft or changing
    // the SIM deliberately creates a different send operation.
    if (_clientId == null ||
        _clientBody != submittedBody ||
        _clientSubscription != submittedSubscription) {
      _clientId = widget.sms.newClientId();
      _clientBody = submittedBody;
      _clientSubscription = submittedSubscription;
    }
    setState(() => _sending = true);
    try {
      await widget.sms.send(
        address: widget.address,
        body: submittedBody,
        subscriptionId: submittedSubscription,
        clientId: _clientId!,
      );
      if (!mounted) {
        return;
      }
      if (_body.text == submittedBody) {
        _body.clear();
      }
      _clientId = null;
      _clientBody = null;
    } catch (e) {
      if (mounted) {
        _showError(context, e,
            suffix: 'Refresh and check this conversation before resending.');
      }
    } finally {
      if (mounted) {
        setState(() => _sending = false);
      }
    }
  }

  @override
  void dispose() {
    widget.sms.removeListener(_readIncoming);
    _body.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: widget.sms,
        builder: (context, _) {
          final messages = _messages;
          final activeSim =
              widget.sms.subscriptions.any((s) => s.id == _subscriptionId);
          return Scaffold(
            appBar: AppBar(
              title: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(widget.address),
                  Text('Carrier SMS',
                      style: Theme.of(context).textTheme.labelMedium),
                ],
              ),
              actions: [
                IconButton(
                  tooltip: 'Refresh SMS',
                  onPressed: widget.sms.refresh,
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
            body: SafeArea(
              child: Column(
                children: [
                  if (widget.sms.error != null)
                    _InfoCard(
                        text: widget.sms.error!,
                        icon: Icons.error_outline,
                        error: true),
                  Expanded(
                    child: ListView.builder(
                      reverse: true,
                      padding: const EdgeInsets.all(12),
                      itemCount: messages.length + 1,
                      itemBuilder: (context, index) {
                        if (index == messages.length) {
                          return widget.sms.hasOlder
                              ? _LoadOlderButton(onLoad: widget.sms.loadOlder)
                              : const SizedBox.shrink();
                        }
                        final message = messages[index];
                        final colors = Theme.of(context).colorScheme;
                        return Align(
                          alignment: message.incoming
                              ? Alignment.centerLeft
                              : Alignment.centerRight,
                          child: Container(
                            constraints: BoxConstraints(
                                maxWidth:
                                    MediaQuery.sizeOf(context).width * .82),
                            margin: const EdgeInsets.symmetric(vertical: 4),
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                              color: message.incoming
                                  ? colors.surfaceContainerHighest
                                  : colors.primaryContainer,
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                SelectableText(message.body),
                                const SizedBox(height: 4),
                                Text(
                                  '${_dateTimeLabel(context, message.date)}'
                                  '${message.statusLabel.isEmpty ? '' : ' · ${message.statusLabel}'}',
                                  style: Theme.of(context).textTheme.labelSmall,
                                ),
                                if (message.failed)
                                  Text(
                                      'Some parts may have sent. Check before resending.',
                                      style: TextStyle(
                                          color: colors.error, fontSize: 12)),
                                if (message.errorCode == -1000 &&
                                    message.pending)
                                  Text(
                                      'Send confirmation is unavailable. Check before resending.',
                                      style: TextStyle(
                                          color: colors.error, fontSize: 12)),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                  const Divider(height: 1),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                    child: DropdownButtonFormField<int>(
                      value: activeSim ? _subscriptionId : null,
                      isExpanded: true,
                      decoration: InputDecoration(
                        labelText: 'Send using SIM',
                        errorText: widget.sms.subscriptions.isEmpty
                            ? 'No active SIM found. Check the phone’s SIM settings.'
                            : !activeSim && _subscriptionId != null
                                ? 'The original SIM is unavailable. Choose a SIM.'
                                : null,
                      ),
                      hint: const Text('Choose the number to send from'),
                      items: widget.sms.subscriptions
                          .map((s) => DropdownMenuItem(
                              value: s.id, child: Text(s.label)))
                          .toList(),
                      onChanged: _sending
                          ? null
                          : (value) {
                              setState(() => _subscriptionId = value);
                              _readIncoming();
                            },
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _body,
                            enabled: !_sending,
                            minLines: 1,
                            maxLines: 5,
                            textCapitalization: TextCapitalization.sentences,
                            decoration: const InputDecoration(
                              hintText: 'SMS message',
                              border: OutlineInputBorder(),
                            ),
                            onChanged: (_) => setState(() {}),
                          ),
                        ),
                        const SizedBox(width: 8),
                        IconButton.filled(
                          tooltip: 'Send SMS',
                          onPressed: !_sending &&
                                  activeSim &&
                                  widget.sms.state.permissionsGranted &&
                                  _body.text.trim().isNotEmpty
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
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      );
}

class _LoadOlderButton extends StatefulWidget {
  const _LoadOlderButton({required this.onLoad});
  final Future<void> Function() onLoad;

  @override
  State<_LoadOlderButton> createState() => _LoadOlderButtonState();
}

class _LoadOlderButtonState extends State<_LoadOlderButton> {
  bool _loading = false;

  @override
  Widget build(BuildContext context) => TextButton(
        onPressed: _loading
            ? null
            : () async {
                setState(() => _loading = true);
                try {
                  await widget.onLoad();
                } finally {
                  if (mounted) {
                    setState(() => _loading = false);
                  }
                }
              },
        child: Text(_loading ? 'Loading…' : 'Load older SMS'),
      );
}

class _InfoCard extends StatelessWidget {
  const _InfoCard({required this.text, required this.icon, this.error = false});
  final String text;
  final IconData icon;
  final bool error;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        margin: const EdgeInsets.all(8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: error
              ? Theme.of(context).colorScheme.errorContainer
              : Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 20),
            const SizedBox(width: 8),
            Expanded(child: Text(text)),
          ],
        ),
      );
}

String _simLabel(NativeSmsService sms, int id) {
  for (final subscription in sms.subscriptions) {
    if (subscription.id == id) {
      return subscription.displayName;
    }
  }
  return id < 0 ? 'Unknown SIM' : 'SIM $id';
}

String _dateTimeLabel(BuildContext context, DateTime date) {
  final localizations = MaterialLocalizations.of(context);
  return '${localizations.formatShortDate(date)} '
      '${localizations.formatTimeOfDay(TimeOfDay.fromDateTime(date))}';
}

void _showError(BuildContext context, Object error, {String? suffix}) {
  final message = error is PlatformException
      ? error.message ?? error.code
      : error.toString();
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
    content: Text('$message${suffix == null ? '' : '\n$suffix'}'),
    duration: const Duration(seconds: 8),
  ));
}
