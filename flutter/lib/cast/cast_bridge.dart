import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:open_tv/cast/cast_state.dart';
import 'package:open_tv/models/channel.dart';
import 'package:open_tv/models/channel_http_headers.dart';
import 'package:open_tv/models/media_type.dart';
import 'package:open_tv/native_bridge.dart';

/// App-wide casting state. Lives outside the widget tree because the app
/// rebuilds its Home/Settings routes with pushAndRemoveUntil; a cast session
/// has to outlive all of them.
///
/// Android only. On other platforms [available] stays false and every call
/// is a no-op. Mirrors CastBridge.kt.
class CastBridge extends ChangeNotifier {
  static final CastBridge instance = CastBridge._();
  CastBridge._();

  static const _method = MethodChannel("dev.fredol.open_tv/cast");
  static const _events = EventChannel("dev.fredol.open_tv/cast_events");
  static const _connectTimeout = Duration(seconds: 30);

  /// Sample video with CORS enabled; used to verify a session end-to-end
  /// without touching the user's IPTV connection.
  static const testStreamUrl =
      "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4";

  bool _initialized = false;
  bool available = false;
  CastSessionState sessionState = CastSessionState.unavailable;
  String? deviceName;
  String? lastError;
  List<CastRoute> routes = const [];
  CastMediaStatus media = CastMediaStatus.empty;
  CastPipelineStatus pipeline = CastPipelineStatus.idle;

  /// The channel we asked the receiver to play (null for the test stream).
  Channel? currentChannel;

  /// Surfaces receiver/session errors to whatever UI is on screen.
  void Function(String message)? onError;

  StreamSubscription? _subscription;
  Completer<bool>? _connectCompleter;
  Timer? _connectTimer;

  bool get isConnected => sessionState == CastSessionState.connected;
  bool get isConnecting => sessionState == CastSessionState.connecting;

  /// Something is (or is about to be) playing on the receiver.
  bool get isCasting => isConnected && (media.isActive || pipeline.isActive);

  /// Call once at startup; safe to call again.
  Future<void> init() async {
    if (_initialized || !Platform.isAndroid) return;
    _initialized = true;
    try {
      available = await _method.invokeMethod<bool>("init") ?? false;
    } catch (e) {
      debugPrint("Cast init failed: $e");
      available = false;
    }
    if (!available) return;
    sessionState = CastSessionState.disconnected;
    _subscription = _events.receiveBroadcastStream().listen(
      _onEvent,
      onError: (e) => debugPrint("Cast event stream error: $e"),
    );
    notifyListeners();
  }

  void _onEvent(dynamic event) {
    final map = Map<dynamic, dynamic>.from(event as Map);
    switch (map["type"]) {
      case "routes":
        routes = (map["routes"] as List)
            .map((r) => CastRoute.fromMap(r as Map))
            .toList();
      case "session":
        _onSession(map);
      case "media":
        media = CastMediaStatus.fromMap(map);
      case "pipeline":
        pipeline = CastPipelineStatus.fromMap(map);
      case "error":
        lastError = map["message"] as String?;
        if (lastError != null) onError?.call(lastError!);
    }
    notifyListeners();
  }

  void _onSession(Map<dynamic, dynamic> map) {
    final previous = sessionState;
    sessionState = switch (map["state"]) {
      "connected" => CastSessionState.connected,
      "connecting" => CastSessionState.connecting,
      _ => CastSessionState.disconnected,
    };
    deviceName = map["device"] as String?;
    final error = map["error"] as String?;
    if (error != null) lastError = error;
    if (sessionState == CastSessionState.connected) {
      _completeConnect(true);
    } else if (sessionState == CastSessionState.disconnected) {
      _completeConnect(false);
      if (previous != CastSessionState.disconnected) {
        _persistPosition();
        currentChannel = null;
      }
    }
  }

  void _completeConnect(bool result) {
    _connectTimer?.cancel();
    _connectTimer = null;
    final c = _connectCompleter;
    _connectCompleter = null;
    if (c != null && !c.isCompleted) c.complete(result);
  }

  // ------------------------------------------------------------ discovery

  Future<void> startDiscovery({bool active = false}) async {
    if (!available) return;
    await _method.invokeMethod("startDiscovery", {"active": active});
  }

  Future<void> stopActiveDiscovery() async {
    if (!available) return;
    await _method.invokeMethod("stopActiveDiscovery");
  }

  // -------------------------------------------------------------- session

  /// Resolves true once the session is connected, false on failure/timeout.
  Future<bool> connect(String routeId) async {
    if (!available) return false;
    if (isConnected) return true;
    _completeConnect(false);
    final completer = Completer<bool>();
    _connectCompleter = completer;
    _connectTimer = Timer(_connectTimeout, () {
      lastError = "Timed out connecting to the device";
      _completeConnect(false);
      notifyListeners();
    });
    final started = await _method.invokeMethod<bool>("connect", {
      "routeId": routeId,
    });
    if (started != true) {
      lastError = "Device is no longer available";
      _completeConnect(false);
      notifyListeners();
      return false;
    }
    sessionState = CastSessionState.connecting;
    notifyListeners();
    return completer.future;
  }

  Future<void> disconnect() async {
    if (!available) return;
    await _persistPosition();
    currentChannel = null;
    await _method.invokeMethod("disconnect");
  }

  // ---------------------------------------------------------------- media

  Future<void> castChannel(Channel channel, {int startPositionMs = 0}) async {
    if (!isConnected) return;
    await _persistPosition();
    currentChannel = channel;
    notifyListeners();
    final url = channel.url;
    if (url == null || url.isEmpty) {
      onError?.call("Channel has no stream URL");
      return;
    }
    // Same per-channel headers the local player uses (EXTVLCOPT etc.).
    ChannelHttpHeaders? headers;
    if (channel.id != null) {
      try {
        headers = await NativeBridge.instance.getChannelHeaders(channel.id!);
      } catch (e) {
        debugPrint("Could not load channel headers: $e");
      }
    }
    // The phone's FFmpeg pipeline is the only client of the provider; the
    // receiver plays the HLS the phone serves (see CHROMECAST.md).
    await _call("castChannel", {
      "url": url,
      "title": channel.name,
      "subtitle": channel.group,
      "image": channel.image,
      "isLive": channel.mediaType == MediaType.livestream,
      "startPositionMs": startPositionMs,
      "userAgent": headers?.userAgent,
      "referer": headers?.referrer,
      "origin": headers?.httpOrigin,
    });
  }

  Future<List<String>> pipelineLog() async {
    if (!available) return const [];
    final result = await _method.invokeMethod<List<dynamic>>("getPipelineLog");
    return result?.cast<String>() ?? const [];
  }

  Future<void> castTestStream() async {
    if (!isConnected) return;
    await _persistPosition();
    currentChannel = null;
    notifyListeners();
    await _method.invokeMethod("load", {
      "url": testStreamUrl,
      "title": "Cast test: Big Buck Bunny",
      "isLive": false,
      "contentType": "video/mp4",
      "startPositionMs": 0,
    });
  }

  Future<void> play() => _call("play");
  Future<void> pause() => _call("pause");

  Future<void> stop() async {
    await _persistPosition();
    await _call("stop");
  }

  Future<void> togglePlayPause() => media.isPlaying ? pause() : play();

  Future<void> seekTo(int positionMs) =>
      _call("seekTo", {"positionMs": positionMs.clamp(0, 1 << 62)});

  Future<void> seekBy(int deltaMs) {
    final target = media.positionMs + deltaMs;
    final max = media.hasDuration ? media.durationMs : target;
    return seekTo(target.clamp(0, max));
  }

  Future<void> setVolume(double volume) =>
      _call("setVolume", {"volume": volume.clamp(0.0, 1.0)});

  Future<void> setMuted(bool muted) => _call("setMuted", {"muted": muted});

  Future<void> _call(String method, [Map<String, dynamic>? args]) async {
    if (!available) return;
    try {
      await _method.invokeMethod(method, args);
    } on PlatformException catch (e) {
      lastError = e.message;
      if (e.message != null) onError?.call(e.message!);
      notifyListeners();
    }
  }

  /// Same behaviour as the local players: remember where a movie stopped.
  Future<void> _persistPosition() async {
    final channel = currentChannel;
    if (channel == null ||
        channel.mediaType != MediaType.movie ||
        channel.id == null ||
        !media.hasMedia ||
        media.positionMs <= 0) {
      return;
    }
    try {
      await NativeBridge.instance.setMoviePosition(
        channel.id!,
        media.positionMs ~/ 1000,
      );
    } catch (e) {
      debugPrint("Failed to save cast position: $e");
    }
  }

  static String contentTypeFor(String url) {
    final path = Uri.tryParse(url)?.path.toLowerCase() ?? url.toLowerCase();
    if (path.endsWith(".m3u8")) return "application/x-mpegURL";
    if (path.endsWith(".mpd")) return "application/dash+xml";
    if (path.endsWith(".webm")) return "video/webm";
    if (path.endsWith(".ts")) return "video/mp2t";
    if (path.endsWith(".mkv")) return "video/x-matroska";
    return "video/mp4";
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _connectTimer?.cancel();
    super.dispose();
  }
}
