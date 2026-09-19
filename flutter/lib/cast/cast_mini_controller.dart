import 'package:flutter/material.dart';
import 'package:open_tv/cast/cast_bridge.dart';
import 'package:open_tv/cast/cast_controls.dart';
import 'package:open_tv/cast/cast_device_dialog.dart';
import 'package:open_tv/cast/cast_state.dart';

/// Thin bar above the bottom navigation while a cast session is connected.
/// Tapping it opens the full remote-control screen.
class CastMiniController extends StatelessWidget {
  const CastMiniController({super.key});

  @override
  Widget build(BuildContext context) {
    final cast = CastBridge.instance;
    return ListenableBuilder(
      listenable: cast,
      builder: (context, _) {
        if (!cast.isConnected) return const SizedBox.shrink();
        final media = cast.media;
        final pipeline = cast.pipeline;
        final title = cast.currentChannel?.name ?? media.title;
        final hasMedia = cast.isCasting;
        final preparing = pipeline.state == CastPipelineState.starting ||
            pipeline.state == CastPipelineState.reconnecting;
        final status = preparing ? pipeline.label! : media.stateLabel;
        return Material(
          color: Theme.of(context).colorScheme.surfaceContainerHigh,
          child: InkWell(
            onTap: () {
              if (hasMedia) {
                Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const CastControls()),
                );
              } else {
                showCastDeviceDialog(context);
              }
            },
            child: SizedBox(
              height: 56,
              child: Row(
                children: [
                  const SizedBox(width: 16),
                  const Icon(Icons.cast_connected, color: Colors.blue),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title ?? "Ready to cast",
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(fontWeight: FontWeight.w600),
                        ),
                        Text(
                          hasMedia
                              ? "$status · ${cast.deviceName ?? ""}"
                              : "Connected to ${cast.deviceName ?? "device"}",
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  if (media.isActive && !media.isLive)
                    IconButton(
                      onPressed: cast.togglePlayPause,
                      icon: Icon(
                        media.isPlaying ? Icons.pause : Icons.play_arrow,
                      ),
                    ),
                  if (hasMedia)
                    IconButton(
                      tooltip: "Stop",
                      onPressed: cast.stop,
                      icon: const Icon(Icons.stop),
                    ),
                  const SizedBox(width: 4),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
