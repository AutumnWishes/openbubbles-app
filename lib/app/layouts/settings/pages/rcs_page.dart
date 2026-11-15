import 'dart:async';
import 'dart:convert';

import 'package:bluebubbles/services/rcs/rcs_service.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:universal_io/io.dart';

/// Local pairing/settings. Login JSON is read once and never stored in Dart UI state.
class RcsPage extends StatefulWidget {
  const RcsPage({super.key, this.service});
  final RcsService? service;
  @override
  State<RcsPage> createState() => _RcsPageState();
}

class _RcsPageState extends State<RcsPage> with WidgetsBindingObserver {
  late final RcsService _rcs = widget.service ?? RcsService.instance;
  bool _working = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_rcs.initialize());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_rcs.refreshSnapshot());
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_working) return;
    setState(() => _working = true);
    try {
      await action();
    } catch (failure) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(
          failure is RcsException
              ? failure.message
              : 'Could not complete the action. Check RCS settings and try again.',
        )));
      }
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Future<void> _importAndPair() async {
    final selected = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['json'],
      withReadStream: true,
      withData: false,
    );
    if (selected == null || selected.files.isEmpty) return;
    final file = selected.files.single;
    if (file.size > 64 * 1024) {
      throw const RcsException('INVALID_SIGN_IN_FILE',
          'The sign-in file must be smaller than 64 KB.');
    }
    final stream = file.readStream ??
        (file.path == null ? null : File(file.path!).openRead());
    if (stream == null) {
      throw const RcsException(
          'INVALID_SIGN_IN_FILE', 'Could not read that local sign-in file.');
    }
    final bytes = <int>[];
    await for (final chunk in stream) {
      if (bytes.length + chunk.length > 64 * 1024) {
        throw const RcsException('INVALID_SIGN_IN_FILE',
            'The sign-in file must be smaller than 64 KB.');
      }
      bytes.addAll(chunk);
    }
    String json;
    try {
      json = utf8.decode(bytes);
    } catch (_) {
      throw const RcsException('INVALID_SIGN_IN_FILE',
          'Choose the sign-in JSON file created by the computer helper.');
    }
    await _rcs.pair(json);
  }

  Future<void> _allowNotifications() async {
    final permission = await Permission.notification.request();
    if (permission.isPermanentlyDenied) await openAppSettings();
    await _rcs.refreshSnapshot();
  }

  Future<void> _forget() async {
    final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
              title: const Text('Forget RCS pairing?'),
              content: const Text(
                  'This removes the saved Google Messages pairing from OpenBubbles. You will need to pair again.'),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('Cancel')),
                FilledButton(
                    onPressed: () => Navigator.pop(context, true),
                    child: const Text('Forget pairing')),
              ],
            ));
    if (confirmed == true) await _rcs.forget();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // The shared service remains alive for the inbox and Android notifications.
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
      animation: _rcs,
      builder: (context, _) {
        final state = _rcs.snapshot;
        final busy = _working || _rcs.commandBusy || state.busy;
        return Scaffold(
          appBar:
              AppBar(title: const Text('RCS with Google Messages'), actions: [
            IconButton(
                tooltip: 'Refresh connection status',
                onPressed: _working ? null : () => _run(_rcs.refreshSnapshot),
                icon: const Icon(Icons.refresh)),
          ]),
          body: ListView(padding: const EdgeInsets.all(16), children: [
            const Text(
                'Keep Google Messages as the default messaging app with RCS connected. OpenBubbles uses its paired-device connection.'),
            const SizedBox(height: 16),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(state.ready
                  ? Icons.cloud_done_outlined
                  : Icons.cloud_off_outlined),
              title: Text(!state.supported
                  ? 'RCS requires Android'
                  : state.ready
                      ? 'Connected'
                      : state.paired
                          ? 'Paired · ${state.state.replaceAll('_', ' ')}'
                          : 'Not paired'),
              subtitle: state.status.isEmpty ? null : Text(state.status),
            ),
            if (_rcs.error != null)
              Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(_rcs.error!,
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error))),
            if (state.challenge.isNotEmpty && !state.ready)
              Card(
                  child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(children: [
                        const Text(
                            'Open Google Messages and confirm the matching symbol, then return here.'),
                        const SizedBox(height: 12),
                        Text(state.challenge,
                            style: Theme.of(context).textTheme.headlineLarge),
                      ]))),
            if (state.supported && !state.paired) ...[
              const Text(
                  'Create a local sign-in JSON file with the supplied computer helper, then import it here to pair. Its contents are never shown in the app.'),
              const SizedBox(height: 12),
              FilledButton.icon(
                  onPressed: busy ? null : () => _run(_importAndPair),
                  icon: const Icon(Icons.link),
                  label: const Text('Import sign-in file and pair')),
            ],
            if (state.paired)
              Wrap(spacing: 8, runSpacing: 8, children: [
                FilledButton(
                    onPressed:
                        busy || state.ready ? null : () => _run(_rcs.connect),
                    child: const Text('Connect')),
                OutlinedButton(
                    onPressed: _working ? null : () => _run(_rcs.disconnect),
                    child: const Text('Disconnect')),
                TextButton(
                    onPressed: _working ? null : () => _run(_forget),
                    child: const Text('Forget pairing')),
              ]),
            const Divider(height: 32),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Show message previews'),
              subtitle: const Text(
                  'Include message text in notifications. Android controls what appears on the lock screen.'),
              value: state.previewsEnabled,
              onChanged: _working || !state.supported
                  ? null
                  : (value) => _run(() => _rcs.setPreviews(value)),
            ),
            if (state.supported && !state.notificationsAllowed)
              OutlinedButton.icon(
                  onPressed: _working ? null : () => _run(_allowNotifications),
                  icon: const Icon(Icons.notifications_outlined),
                  label: const Text('Allow message notifications')),
            if (state.backgroundActive)
              const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                      'Background connection active. You can leave the app; Disconnect stops it.')),
            if (state.sims.isNotEmpty) ...[
              const Divider(height: 32),
              Text('Phone SIMs',
                  style: Theme.of(context).textTheme.titleMedium),
              ...state.sims.map((sim) => ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.sim_card_outlined),
                    title: Text(sim.label.isEmpty ? 'SIM' : sim.label),
                    subtitle: Text(sim.active && sim.rcsEnabled
                        ? 'Available for RCS'
                        : 'RCS unavailable'),
                  )),
            ],
          ]),
        );
      });
}
