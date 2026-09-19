enum CastSessionState { unavailable, disconnected, connecting, connected }

enum CastPlayerState { idle, loading, buffering, playing, paused }

class CastRoute {
  final String id;
  final String name;
  final String? description;
  final bool connected;

  const CastRoute({
    required this.id,
    required this.name,
    this.description,
    this.connected = false,
  });

  factory CastRoute.fromMap(Map<dynamic, dynamic> m) => CastRoute(
    id: m["id"] as String,
    name: (m["name"] as String?) ?? "Unknown device",
    description: m["description"] as String?,
    connected: (m["connected"] as bool?) ?? false,
  );
}

class CastMediaStatus {
  final bool hasMedia;
  final CastPlayerState playerState;
  final String idleReason;
  final int positionMs;
  final int durationMs;
  final bool isLive;
  final double volume;
  final bool muted;
  final String? contentId;
  final String? title;

  const CastMediaStatus({
    this.hasMedia = false,
    this.playerState = CastPlayerState.idle,
    this.idleReason = "none",
    this.positionMs = 0,
    this.durationMs = -1,
    this.isLive = false,
    this.volume = 1.0,
    this.muted = false,
    this.contentId,
    this.title,
  });

  static const empty = CastMediaStatus();

  factory CastMediaStatus.fromMap(Map<dynamic, dynamic> m) => CastMediaStatus(
    hasMedia: (m["hasMedia"] as bool?) ?? false,
    playerState: _parseState(m["playerState"] as String?),
    idleReason: (m["idleReason"] as String?) ?? "none",
    positionMs: (m["positionMs"] as num?)?.toInt() ?? 0,
    durationMs: (m["durationMs"] as num?)?.toInt() ?? -1,
    isLive: (m["isLive"] as bool?) ?? false,
    volume: (m["volume"] as num?)?.toDouble() ?? 1.0,
    muted: (m["muted"] as bool?) ?? false,
    contentId: m["contentId"] as String?,
    title: m["title"] as String?,
  );

  static CastPlayerState _parseState(String? s) => switch (s) {
    "loading" => CastPlayerState.loading,
    "buffering" => CastPlayerState.buffering,
    "playing" => CastPlayerState.playing,
    "paused" => CastPlayerState.paused,
    _ => CastPlayerState.idle,
  };

  bool get isActive => hasMedia && playerState != CastPlayerState.idle;
  bool get isPlaying => playerState == CastPlayerState.playing;
  bool get hasDuration => !isLive && durationMs > 0;

  String get stateLabel => switch (playerState) {
    CastPlayerState.loading => "Loading…",
    CastPlayerState.buffering => "Buffering…",
    CastPlayerState.playing => "Playing",
    CastPlayerState.paused => "Paused",
    CastPlayerState.idle => switch (idleReason) {
      "finished" => "Finished",
      "error" => "Playback error on receiver",
      "interrupted" => "Interrupted",
      _ => "Stopped",
    },
  };
}

enum CastPipelineState { idle, starting, ready, finished, reconnecting, failed }

/// What the phone-side FFmpeg pipeline is doing for the current cast.
class CastPipelineStatus {
  final CastPipelineState state;
  final String? message;
  final int attempt;
  final String? mode; // remux | transcode_hw | transcode_sw
  final String? videoCodec;
  final String? audioCodec;

  const CastPipelineStatus({
    this.state = CastPipelineState.idle,
    this.message,
    this.attempt = 0,
    this.mode,
    this.videoCodec,
    this.audioCodec,
  });

  static const idle = CastPipelineStatus();

  factory CastPipelineStatus.fromMap(Map<dynamic, dynamic> m) =>
      CastPipelineStatus(
        state: switch (m["state"] as String?) {
          "starting" => CastPipelineState.starting,
          "ready" => CastPipelineState.ready,
          "finished" => CastPipelineState.finished,
          "reconnecting" => CastPipelineState.reconnecting,
          "failed" => CastPipelineState.failed,
          _ => CastPipelineState.idle,
        },
        message: m["message"] as String?,
        attempt: (m["attempt"] as num?)?.toInt() ?? 0,
        mode: m["mode"] as String?,
        videoCodec: m["videoCodec"] as String?,
        audioCodec: m["audioCodec"] as String?,
      );

  bool get isActive =>
      state != CastPipelineState.idle && state != CastPipelineState.failed;

  String get modeLabel => switch (mode) {
    "remux" => "direct (no re-encoding)",
    "transcode_hw" => "transcoding (hardware)",
    "transcode_sw" => "transcoding (software)",
    _ => "",
  };

  String? get label => switch (state) {
    CastPipelineState.starting =>
      attempt > 0 ? "Preparing stream… (retry $attempt)" : "Preparing stream…",
    CastPipelineState.reconnecting => "Reconnecting… (attempt $attempt)",
    CastPipelineState.failed => "Stream failed: ${message ?? "unknown error"}",
    CastPipelineState.ready || CastPipelineState.finished =>
      modeLabel.isEmpty ? null : "Stream: $modeLabel",
    CastPipelineState.idle => null,
  };

  String get codecs => [?videoCodec, ?audioCodec].join(" / ");
}

/// Returned by the local player screen when the user pressed its cast button:
/// the screen has been popped (releasing the local player) and casting should
/// continue from [positionMs].
class CastHandoff {
  final int positionMs;
  const CastHandoff(this.positionMs);
}
