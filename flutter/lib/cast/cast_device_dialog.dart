import 'package:flutter/material.dart';
import 'package:flutter_spinkit/flutter_spinkit.dart';
import 'package:open_tv/cast/cast_bridge.dart';
import 'package:open_tv/cast/cast_state.dart';

/// Shows the device picker. Resolves true if a session is connected when the
/// dialog closes (either it already was, or the user connected).
Future<bool> showCastDeviceDialog(BuildContext context) async {
  final result = await showDialog<bool>(
    context: context,
    barrierDismissible: true,
    builder: (_) => const CastDeviceDialog(),
  );
  return result ?? CastBridge.instance.isConnected;
}

class CastDeviceDialog extends StatefulWidget {
  const CastDeviceDialog({super.key});

  @override
  State<CastDeviceDialog> createState() => _CastDeviceDialogState();
}

class _CastDeviceDialogState extends State<CastDeviceDialog> {
  String? _connectingRouteId;

  @override
  void initState() {
    super.initState();
    // Active scanning only while the picker is open (battery).
    CastBridge.instance.startDiscovery(active: true);
  }

  @override
  void dispose() {
    CastBridge.instance.stopActiveDiscovery();
    super.dispose();
  }

  Future<void> _connect(CastRoute route) async {
    setState(() => _connectingRouteId = route.id);
    final ok = await CastBridge.instance.connect(route.id);
    if (!mounted) return;
    if (ok) {
      Navigator.of(context).pop(true);
    } else {
      setState(() => _connectingRouteId = null);
      final error = CastBridge.instance.lastError ?? "Could not connect";
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(content: Text(error)),
      );
    }
  }

  Future<void> _disconnect() async {
    await CastBridge.instance.disconnect();
    if (mounted) Navigator.of(context).pop(false);
  }

  @override
  Widget build(BuildContext context) {
    final cast = CastBridge.instance;
    return ListenableBuilder(
      listenable: cast,
      builder: (context, _) {
        if (cast.isConnected) return _connectedView(cast);
        return AlertDialog(
          title: const Text("Cast to"),
          content: SizedBox(
            width: double.maxFinite,
            child: cast.routes.isEmpty
                ? const _Searching()
                : ListView(
                    shrinkWrap: true,
                    children: cast.routes
                        .asMap()
                        .entries
                        .map((e) => _routeTile(e.value, e.key == 0))
                        .toList(),
                  ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text("Cancel"),
            ),
          ],
        );
      },
    );
  }

  Widget _routeTile(CastRoute route, bool autofocus) {
    final connecting = _connectingRouteId == route.id;
    return ListTile(
      autofocus: autofocus,
      leading: const Icon(Icons.tv),
      title: Text(route.name),
      subtitle: route.description != null ? Text(route.description!) : null,
      trailing: connecting
          ? const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : null,
      enabled: _connectingRouteId == null,
      onTap: () => _connect(route),
    );
  }

  Widget _connectedView(CastBridge cast) {
    return AlertDialog(
      title: const Text("Casting"),
      content: Row(
        children: [
          const Icon(Icons.cast_connected),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              "Connected to ${cast.deviceName ?? "device"}",
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: _disconnect, child: const Text("Disconnect")),
        TextButton(
          autofocus: true,
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text("Close"),
        ),
      ],
    );
  }
}

class _Searching extends StatelessWidget {
  const _Searching();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SpinKitPulsingGrid(color: Colors.blue, size: 40),
          const SizedBox(height: 16),
          const Text("Looking for devices…"),
          const SizedBox(height: 6),
          Text(
            "Make sure the phone and the Chromecast are on the same Wi-Fi.",
            style: Theme.of(context).textTheme.bodySmall,
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }
}
