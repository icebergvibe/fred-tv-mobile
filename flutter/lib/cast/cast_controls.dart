import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:open_tv/cast/cast_bridge.dart';
import 'package:open_tv/cast/cast_device_dialog.dart';
import 'package:open_tv/cast/cast_state.dart';

/// Full-screen remote control for the receiver. Backing out keeps casting;
/// the mini controller on Home leads back here.
class CastControls extends StatefulWidget {
  const CastControls({super.key});

  @override
  State<CastControls> createState() => _CastControlsState();
}

class _CastControlsState extends State<CastControls> {
  static const _seekStepMs = 10_000;

  double? _dragPositionMs;
  double? _dragVolume;
  bool _popped = false;

  @override
  void initState() {
    super.initState();
    CastBridge.instance.addListener(_onCastChanged);
  }

  @override
  void dispose() {
    CastBridge.instance.removeListener(_onCastChanged);
    super.dispose();
  }

  void _onCastChanged() {
    // Nothing to control once the session is gone.
    if (!CastBridge.instance.isConnected && !_popped && mounted) {
      _popped = true;
      Navigator.of(context).pop();
    }
  }

  String _fmt(int ms) {
    final d = Duration(milliseconds: ms < 0 ? 0 : ms);
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, "0");
    final s = d.inSeconds.remainder(60).toString().padLeft(2, "0");
    return h > 0 ? "$h:$m:$s" : "$m:$s";
  }

  Future<void> _showLog() async {
    final lines = await CastBridge.instance.pipelineLog();
    if (!mounted) return;
    final text = lines.isEmpty ? "(no log yet)" : lines.join("\n");
    await showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text("Stream log"),
        content: SizedBox(
          width: double.maxFinite,
          child: SingleChildScrollView(
            child: SelectableText(
              text,
              style: const TextStyle(fontFamily: "monospace", fontSize: 11),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Clipboard.setData(ClipboardData(text: text)),
            child: const Text("Copy"),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text("Close"),
          ),
        ],
      ),
    );
  }

  String _statusText(CastBridge cast) {
    final media = cast.media;
    final pipeline = cast.pipeline;
    // While the phone is still preparing the stream the receiver has nothing
    // to say yet; once it plays, its state is what matters.
    if (pipeline.state == CastPipelineState.failed) return pipeline.label!;
    if (pipeline.state == CastPipelineState.starting ||
        pipeline.state == CastPipelineState.reconnecting) {
      return pipeline.label!;
    }
    if (!media.hasMedia) return "Nothing playing";
    return media.stateLabel;
  }

  @override
  Widget build(BuildContext context) {
    final cast = CastBridge.instance;
    return ListenableBuilder(
      listenable: cast,
      builder: (context, _) {
        final media = cast.media;
        final pipeline = cast.pipeline;
        final channel = cast.currentChannel;
        final title = channel?.name ?? media.title ?? "Casting";
        return Scaffold(
          appBar: AppBar(
            title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
            actions: [
              IconButton(
                tooltip: "Stream log",
                onPressed: _showLog,
                icon: const Icon(Icons.article_outlined),
              ),
              IconButton(
                tooltip: "Cast device",
                onPressed: () => showCastDeviceDialog(context),
                icon: const Icon(Icons.cast_connected, color: Colors.blue),
              ),
            ],
          ),
          body: SafeArea(
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 520),
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      _poster(channel?.image),
                      const SizedBox(height: 24),
                      Text(
                        "Casting to ${cast.deviceName ?? "device"}",
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _statusText(cast),
                        style: Theme.of(context).textTheme.titleMedium,
                        textAlign: TextAlign.center,
                      ),
                      if (pipeline.isActive && pipeline.label != null &&
                          pipeline.state == CastPipelineState.ready) ...[
                        const SizedBox(height: 4),
                        Text(
                          pipeline.codecs.isEmpty
                              ? pipeline.label!
                              : "${pipeline.label!} · ${pipeline.codecs}",
                          style: Theme.of(context).textTheme.bodySmall,
                          textAlign: TextAlign.center,
                        ),
                      ],
                      const SizedBox(height: 24),
                      if (media.hasDuration) _seekBar(cast),
                      _transport(cast),
                      const SizedBox(height: 16),
                      _volume(cast),
                      const SizedBox(height: 24),
                      TextButton.icon(
                        onPressed: () async {
                          await cast.disconnect();
                        },
                        icon: const Icon(Icons.cast),
                        label: const Text("Disconnect"),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _poster(String? image) {
    return SizedBox(
      height: 180,
      child: image != null
          ? CachedNetworkImage(
              imageUrl: image,
              fit: BoxFit.contain,
              errorWidget: (_, _, _) =>
                  const Icon(Icons.tv, size: 96, color: Colors.grey),
            )
          : const Icon(Icons.tv, size: 96, color: Colors.grey),
    );
  }

  Widget _seekBar(CastBridge cast) {
    final media = cast.media;
    final max = media.durationMs.toDouble();
    final value = (_dragPositionMs ?? media.positionMs.toDouble()).clamp(
      0.0,
      max,
    );
    return Column(
      children: [
        Slider(
          value: value,
          max: max,
          onChanged: (v) => setState(() => _dragPositionMs = v),
          onChangeEnd: (v) {
            setState(() => _dragPositionMs = null);
            cast.seekTo(v.round());
          },
        ),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(_fmt(value.round())),
            Text(_fmt(media.durationMs)),
          ],
        ),
      ],
    );
  }

  Widget _transport(CastBridge cast) {
    final media = cast.media;
    final enabled = media.isActive;
    final canStop = media.isActive || cast.pipeline.isActive;
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        if (!media.isLive) ...[
          IconButton(
            iconSize: 36,
            onPressed: enabled ? () => cast.seekBy(-_seekStepMs) : null,
            icon: const Icon(Icons.replay_10),
          ),
          const SizedBox(width: 16),
          IconButton.filled(
            iconSize: 48,
            autofocus: true,
            onPressed: enabled ? cast.togglePlayPause : null,
            icon: Icon(media.isPlaying ? Icons.pause : Icons.play_arrow),
          ),
          const SizedBox(width: 16),
          IconButton(
            iconSize: 36,
            onPressed: enabled ? () => cast.seekBy(_seekStepMs) : null,
            icon: const Icon(Icons.forward_10),
          ),
          const SizedBox(width: 16),
        ],
        IconButton(
          iconSize: 36,
          tooltip: "Stop",
          autofocus: media.isLive,
          onPressed: canStop ? cast.stop : null,
          icon: const Icon(Icons.stop),
        ),
      ],
    );
  }

  Widget _volume(CastBridge cast) {
    final media = cast.media;
    final value = (_dragVolume ?? (media.muted ? 0.0 : media.volume)).clamp(
      0.0,
      1.0,
    );
    return Row(
      children: [
        IconButton(
          onPressed: () => cast.setMuted(!media.muted),
          icon: Icon(media.muted ? Icons.volume_off : Icons.volume_down),
        ),
        Expanded(
          child: Slider(
            value: value,
            onChanged: (v) => setState(() => _dragVolume = v),
            onChangeEnd: (v) {
              setState(() => _dragVolume = null);
              cast.setVolume(v);
            },
          ),
        ),
        const Icon(Icons.volume_up),
      ],
    );
  }
}
