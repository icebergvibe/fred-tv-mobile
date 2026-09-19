import 'package:flutter/material.dart';
import 'package:open_tv/cast/cast_bridge.dart';
import 'package:open_tv/cast/cast_device_dialog.dart';

/// Cast icon for app bars / toolbars. Renders nothing when casting isn't
/// available on this device.
class CastButton extends StatelessWidget {
  final FocusNode? focusNode;
  const CastButton({super.key, this.focusNode});

  @override
  Widget build(BuildContext context) {
    final cast = CastBridge.instance;
    return ListenableBuilder(
      listenable: cast,
      builder: (context, _) {
        if (!cast.available) return const SizedBox.shrink();
        return IconButton(
          focusNode: focusNode,
          tooltip: cast.isConnected
              ? "Casting to ${cast.deviceName ?? "device"}"
              : "Cast",
          onPressed: () => showCastDeviceDialog(context),
          icon: Icon(
            cast.isConnected ? Icons.cast_connected : Icons.cast,
            color: cast.isConnected ? Colors.blue : null,
          ),
        );
      },
    );
  }
}
