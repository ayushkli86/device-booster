import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  debugPrint('BOOT: main() entered');

  // An exception during build or layout is otherwise invisible on a release
  // build - the framework paints an empty box and the user just sees a blank
  // white window with no clue why.
  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    debugPrint('BOOT: FlutterError: ${details.exceptionAsString()}');
  };

  PlatformDispatcher.instance.onError = (error, stack) {
    debugPrint('BOOT: PlatformDispatcher.onError: $error\n$stack');
    return true;
  };

  runZonedGuarded(
    () {
      runApp(const BoosterApp());
      debugPrint('BOOT: runApp returned');
    },
    (error, stack) {
      debugPrint('BOOT: zone error: $error\n$stack');
    },
  );
}

const _bridge = MethodChannel('com.ff.devicebooster/bridge');

/// A failed bridge call, carrying a message safe to show to the user.
class BridgeFailure implements Exception {
  BridgeFailure(this.message);
  final String message;
  @override
  String toString() => message;
}

/// A faithful preview of the trick button, drawn the same way the native
/// [TrickButton] draws it: a high-contrast disc, a coloured ring, and a
/// centre dot that becomes a cross when armed.
class _TrickPreview extends StatelessWidget {
  const _TrickPreview({
    required this.sizeDp,
    required this.borderDp,
    required this.opacity,
    required this.ringColor,
    required this.dotColor,
    required this.backColor,
    required this.active,
  });

  final double sizeDp;
  final double borderDp;
  final double opacity;
  final Color ringColor;
  final Color dotColor;
  final Color backColor;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final dpr = MediaQuery.of(context).devicePixelRatio;
    final px = (sizeDp * dpr).clamp(24.0, 200.0);
    final border = (borderDp * dpr).clamp(1.0, 40.0);
    final a = (opacity / 100).clamp(0.0, 1.0);

    return SizedBox(
      width: px,
      height: px,
      child: DecoratedBox(
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: backColor.withValues(alpha: a),
          border: Border.all(color: Colors.white.withValues(alpha: a * 0.35), width: 1),
        ),
        child: Center(
          child: Container(
            width: px - border * 4,
            height: px - border * 4,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: ringColor.withValues(alpha: a),
                width: border,
              ),
            ),
            child: Center(
              child: active
                  ? Icon(
                      Icons.close,
                      size: (px * 0.42).clamp(8.0, 90.0),
                      color: dotColor.withValues(alpha: a),
                    )
                  : Container(
                      width: (px * 0.22).clamp(3.0, 44.0),
                      height: (px * 0.22).clamp(3.0, 44.0),
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: dotColor.withValues(alpha: a),
                      ),
                    ),
            ),
          ),
        ),
      ),
    );
  }
}

class BoosterApp extends StatelessWidget {
  const BoosterApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Device Booster',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF00E5A0)),
        useMaterial3: true,
      ),
      darkTheme: ThemeData.dark(useMaterial3: true),
      home: const HomePage(),
      builder: (context, child) {
        // Tell the native side the UI is up, so it does not draw its own
        // "failed to start" message over a working screen.
        if (child != null) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            _bridge.invokeMethod('booted').catchError((Object e) {
              debugPrint('booted notification failed: $e');
            });
          });
        }
        return child ?? const SizedBox.shrink();
      },
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  bool _shizukuRunning = false;
  bool _shizukuGranted = false;
  bool _canDraw = false;

  List<Map<Object?, Object?>> _actions = const [];
  bool? _gpuTuning;
  Map<Object?, Object?>? _sample;

  // Trick button settings. Defaults mirror TrickButtonConfig on the native side.
  bool _trickEnabled = true;
  bool _trickRunning = false;
  double _trickSize = 76;
  double _trickBorder = 4;
  double _trickOpacity = 85;
  bool _preventOverPull = true;
  double _pullSpeed = 1.0;
  int _ringColor = 0xFF7EE787;
  int _dotColor = 0xFFFF6B6B;
  int _backColor = 0xFF101418;
  bool _trickLoading = true;

  String? _loadError;
  bool _loading = true;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    debugPrint('BOOT: HomePage.initState');
    _bridge.setMethodCallHandler((call) async {
      debugPrint('BOOT: native -> dart ${call.method}');
      if (call.method == 'permissionChanged') unawaited(_load());
    });

    unawaited(_load());
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => unawaited(_poll()));
    debugPrint('BOOT: HomePage.initState done');
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  /// Calls the native side, converting every failure mode into a [BridgeFailure].
  ///
  /// [MethodChannel] can throw [PlatformException] for a rejected call and
  /// [MissingPluginException] when the channel is not registered (for example
  /// if the activity was recreated without the engine). Catching only
  /// [PlatformException] let the latter escape as an unhandled async error,
  /// which is how this screen ended up silently blank.
  Future<T?> _invoke<T>(String method, [Map<String, dynamic>? args]) async {
    try {
      return await _bridge.invokeMethod<T>(method, args);
    } on PlatformException catch (e) {
      debugPrint('BRIDGE $method failed: ${e.code} ${e.message}');
      return _fail('$method: ${e.message ?? e.code}');
    } on MissingPluginException catch (e) {
      debugPrint('BRIDGE $method missing plugin: ${e.message}');
      return _fail('The native bridge is not ready. Reopen the app.');
    } catch (e) {
      debugPrint('BRIDGE $method unexpected: $e');
      return _fail('$method failed unexpectedly: $e');
    }
  }

  T? _fail<T>(String message) {
    _reportError(message);
    return null;
  }

  void _reportError(String message) {
    if (!mounted) return;
    setState(() => _loadError = message);
  }

  Future<void> _load() async {
    final status = await _invoke<Map<Object?, Object?>>('shizukuStatus');
    final canDraw = await _invoke<bool>('canDrawOverlays');
    final probe = await _invoke<Map<Object?, Object?>>('probeActions');
    final sample = await _invoke<Map<Object?, Object?>>('sample');
    await _loadTrick();

    if (!mounted) return;

    final rawActions = probe?['actions'];
    final actions = rawActions is List
        ? rawActions.whereType<Map<Object?, Object?>>().toList()
        : const <Map<Object?, Object?>>[];

    setState(() {
      _shizukuRunning = status?['running'] == true;
      _shizukuGranted = status?['granted'] == true;
      _canDraw = canDraw ?? false;
      _actions = actions;
      _gpuTuning = probe?['gpuTuning'] as bool?;
      _sample = sample;
      _loading = false;
    });
    debugPrint(
      'BOOT: _load done running=$_shizukuRunning granted=$_shizukuGranted '
      'canDraw=$_canDraw actions=${_actions.length} err=$_loadError',
    );
  }

  Future<void> _loadTrick() async {
    final config = await _invoke<Map<Object?, Object?>>('trickConfig');
    final running = await _invoke<bool>('trickRunning');
    if (!mounted) return;
    if (config != null && !config.containsKey('error')) {
      setState(() {
        _trickEnabled = config['enabled'] as bool? ?? true;
        _trickSize = (config['sizeDp'] as num?)?.toDouble() ?? 76;
        _trickBorder = (config['borderDp'] as num?)?.toDouble() ?? 4;
        _trickOpacity = (config['opacity'] as num?)?.toDouble() ?? 85;
        _preventOverPull = config['preventOverPull'] as bool? ?? true;
        _pullSpeed = (config['pullSpeed'] as num?)?.toDouble() ?? 1.0;
        _ringColor = (config['ringColor'] as num?)?.toInt() ?? 0xFF7EE787;
        _dotColor = (config['dotColor'] as num?)?.toInt() ?? 0xFFFF6B6B;
        _backColor = (config['backColor'] as num?)?.toInt() ?? 0xFF101418;
      });
    }
    setState(() {
      _trickRunning = running ?? false;
      _trickLoading = false;
    });
  }

  Future<void> _poll() async {
    final s = await _invoke<Map<Object?, Object?>>('sample');
    if (!mounted || s == null) return;
    setState(() => _sample = s);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Device Booster'),
        actions: [
          IconButton(
            onPressed: () {
              setState(() => _loadError = null);
              unawaited(_load());
            },
            icon: const Icon(Icons.refresh),
            tooltip: 'Re-check',
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (_loadError != null) _errorCard(theme, _loadError!),
          if (_loading) const LinearProgressIndicator(),
          _statusCard(theme),
          const SizedBox(height: 16),
          _statsCard(theme),
          if (_shizukuGranted) ...[
            const SizedBox(height: 16),
            _tuningCard(theme),
          ],
          const SizedBox(height: 16),
          _trickCard(theme),
          const SizedBox(height: 16),
          _permissionCard(theme),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _canDraw ? _toggleBooster : null,
        icon: Icon(_boosterOn ? Icons.stop : Icons.play_arrow),
        label: Text(_boosterOn ? 'Stop booster' : 'Start booster'),
      ),
    );
  }

  bool _boosterOn = false;

  Widget _errorCard(ThemeData theme, String message) => Card(
        color: theme.colorScheme.errorContainer,
        margin: const EdgeInsets.only(bottom: 16),
        child: ListTile(
          leading: const Icon(Icons.error_outline),
          title: const Text('Something went wrong'),
          subtitle: Text(message),
        ),
      );

  Widget _statusCard(ThemeData theme) {
    final (color, text) = switch ((_shizukuRunning, _shizukuGranted)) {
      (true, true) => (Colors.greenAccent, 'Shizuku authorised'),
      (true, false) => (Colors.orangeAccent, 'Shizuku running - not authorised'),
      _ => (Colors.redAccent, 'Shizuku not running'),
    };

    return Card(
      child: ListTile(
        leading: Icon(Icons.security, color: color),
        title: Text(text, style: theme.textTheme.titleMedium),
        subtitle: const Text('Tuning switches need Shizuku. Stats do not.'),
        trailing: _shizukuGranted
            ? Icon(Icons.check_circle, color: color)
            : TextButton(
                onPressed: () => unawaited(
                  _guard(() async {
                    if (!_shizukuRunning) {
                      await _invoke('openShizukuApp');
                    } else {
                      await _invoke('requestShizuku');
                    }
                    await _load();
                  }),
                ),
                child: Text(_shizukuRunning ? 'Grant' : 'Start'),
              ),
      ),
    );
  }

  Widget _statsCard(ThemeData theme) {
    final s = _sample;
    final temp = (s?['batteryTempC'] as num?)?.toDouble() ?? -1;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Live', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            if (s == null)
              const Text('Reading sensors...')
            else ...[
              _bar('CPU', (s['cpuLoad'] as num?)?.toDouble() ?? 0, _cpuLabel(s)),
              _bar(
                'RAM',
                ((s['ramPercent'] as num?)?.toDouble() ?? 0) / 100,
                '${s['ramPercent']}%  (${s['cores']} cores)',
              ),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Battery  ${s['batteryPercent']}%'
                      '${(s['charging'] == true) ? ' (charging)' : ''}'),
                  if (temp > 0)
                    Text(
                      temp > 40 ? '${temp.toStringAsFixed(1)} C  hot' : '${temp.toStringAsFixed(1)} C',
                      style: TextStyle(color: temp > 40 ? Colors.redAccent : null),
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _cpuLabel(Map<Object?, Object?> s) {
    final now = (s['cpuFreqMHz'] as num?)?.toInt() ?? 0;
    final max = (s['cpuMaxMHz'] as num?)?.toInt() ?? 0;
    if (now <= 0) return 'frequency unavailable';
    return max > 0 ? '$now / $max MHz' : '$now MHz';
  }

  Widget _bar(String label, double fraction, String trailing) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('$label  $trailing'),
            const SizedBox(height: 4),
            LinearProgressIndicator(value: fraction.clamp(0.0, 1.0)),
          ],
        ),
      );

  Widget _tuningCard(ThemeData theme) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Tuning', style: theme.textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(
                'Only switches this device actually supports are listed.',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 8),
              if (_actions.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text('This ROM exposes no tuning hooks to the shell user.'),
                ),
              for (final a in _actions)
                if (a['reversible'] == true)
                  SwitchListTile(
                    dense: true,
                    value: false,
                    contentPadding: EdgeInsets.zero,
                    title: Text(a['label'] as String? ?? ''),
                    subtitle: Text(a['detail'] as String? ?? ''),
                    onChanged: (on) => unawaited(
                      _guard(() async {
                        await _invoke('runAction', {'id': a['id'], 'enable': on});
                        await _poll();
                      }),
                    ),
                  )
                else
                  // A one-shot action has nothing to switch off, so showing it
                  // as a switch would promise a reversal that does not exist.
                  ListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    title: Text(a['label'] as String? ?? ''),
                    subtitle: Text(a['detail'] as String? ?? ''),
                    trailing: FilledButton(
                      onPressed: () => unawaited(
                        _guard(() async {
                          await _invoke('runAction', {'id': a['id'], 'enable': true});
                          await _poll();
                        }),
                      ),
                      child: const Text('Run'),
                    ),
                  ),
              if (_actions.any((a) => a['id'] == 'thermal'))
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text(
                    'Thermal override disables overheat protection. It can damage '
                    'the battery if left on in a hot environment.',
                    style: TextStyle(color: Colors.orangeAccent, fontSize: 12),
                  ),
                ),
              const SizedBox(height: 8),
              Text(
                _gpuTuning == true
                    ? 'GPU frequency control: writable on this device.'
                    : 'GPU frequency control: root-only here, so it is not offered.',
                style: theme.textTheme.bodySmall,
              ),
            ],
          ),
        ),
      );

  Widget _trickCard(ThemeData theme) {
    final preview = _TrickPreview(
      sizeDp: _trickSize,
      borderDp: _trickBorder,
      opacity: _trickOpacity,
      ringColor: Color(_ringColor),
      dotColor: Color(_dotColor),
      backColor: Color(_backColor),
      active: _trickRunning,
    );

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text('Trick button', style: theme.textTheme.titleMedium)),
                Switch(
                  value: _trickEnabled,
                  // Never null: a disabled switch would strand the user in
                  // the off state with no way to turn it back on.
                  onChanged: (v) => unawaited(_saveTrick({'enabled': v})),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              _trickRunning
                  ? 'Showing on screen. Tap it to arm, tap again to disarm.'
                  : 'A draggable ring with a dot in the middle, over any app.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 12),
            if (_trickLoading)
              const Center(
                child: SizedBox(
                  width: 96,
                  height: 96,
                  child: CircularProgressIndicator(),
                ),
              )
            else
              Center(child: preview),
            const SizedBox(height: 12),
            _slider(
              theme,
              'Button size',
              _trickSize,
              48,
              180,
              132,
              '${_trickSize.round()} dp',
              (v) => _trickSize = v,
              (v) => unawaited(_saveTrick({'sizeDp': v.round()})),
            ),
            _slider(
              theme,
              'Border width',
              _trickBorder,
              1,
              16,
              15,
              '${_trickBorder.round()} dp',
              (v) => _trickBorder = v,
              (v) => unawaited(_saveTrick({'borderDp': v.round()})),
            ),
            _slider(
              theme,
              'Opacity',
              _trickOpacity,
              10,
              100,
              90,
              '${_trickOpacity.round()}%',
              (v) => _trickOpacity = v,
              (v) => unawaited(_saveTrick({'opacity': v.round()})),
            ),
            _slider(
              theme,
              'Pull speed',
              _pullSpeed,
              0.25,
              1.0,
              15,
              '${(_pullSpeed * 100).round()}%',
              (v) => _pullSpeed = v,
              (v) => unawaited(_saveTrick({'pullSpeed': v})),
            ),
            SwitchListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              value: _preventOverPull,
              onChanged: (v) => unawaited(_saveTrick({'preventOverPull': v})),
              title: const Text('Prevent over pulling'),
              subtitle: const Text(
                'Resists drags that would pull the button under the status bar '
                'or off the screen.',
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Text('Colours', style: theme.textTheme.labelLarge),
                const Spacer(),
                _swatch('Ring', _ringColor, (c) => _saveTrick({'ringColor': c})),
                const SizedBox(width: 8),
                _swatch('Dot', _dotColor, (c) => _saveTrick({'dotColor': c})),
                const SizedBox(width: 8),
                _swatch('Back', _backColor, (c) => _saveTrick({'backColor': c})),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _canDraw ? () => unawaited(_toggleTrick()) : null,
                    icon: Icon(_trickRunning ? Icons.stop : Icons.play_arrow),
                    label: Text(_trickRunning ? 'Stop trick button' : 'Start trick button'),
                  ),
                ),
              ],
            ),
            if (!_canDraw)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'Grant the overlay permission below to use the trick button.',
                  style: TextStyle(color: Colors.orangeAccent, fontSize: 12),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _slider(
    ThemeData theme,
    String label,
    double value,
    double min,
    double max,
    int divisions,
    String display,
    ValueChanged<double> onPreview,
    ValueChanged<double> onCommit,
  ) =>
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(label)),
              Text(display, style: theme.textTheme.bodySmall),
            ],
          ),
          Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            divisions: divisions,
            onChanged: (v) => setState(() => onPreview(v)),
            onChangeEnd: onCommit,
          ),
        ],
      );

  Widget _swatch(String label, int value, ValueChanged<int> onPicked) {
    const palette = [
      0xFF7EE787, 0xFFFF6B6B, 0xFF5C8DFF, 0xFFFFD166,
      0xFFFFFFFF, 0xFF101418, 0xFF00E5A0, 0xFFFF6BFF,
    ];
    return PopupMenuButton<int>(
      tooltip: '$label colour',
      onSelected: onPicked,
      itemBuilder: (_) => [
        for (final c in palette)
          PopupMenuItem(value: c, child: Text('$label #${c.toRadixString(16).substring(2)}')),
      ],
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 22,
            height: 22,
            decoration: BoxDecoration(
              color: Color(value),
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white24),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _saveTrick(Map<String, Object?> changes) async {
    // Reflect the change immediately so the preview stays responsive, then
    // persist. The native side clamps anything out of range.
    setState(() {
      if (changes['enabled'] != null) _trickEnabled = changes['enabled']! as bool;
      if (changes['sizeDp'] != null) _trickSize = (changes['sizeDp']! as num).toDouble();
      if (changes['borderDp'] != null) _trickBorder = (changes['borderDp']! as num).toDouble();
      if (changes['opacity'] != null) _trickOpacity = (changes['opacity']! as num).toDouble();
      if (changes['preventOverPull'] != null) {
        _preventOverPull = changes['preventOverPull']! as bool;
      }
      if (changes['pullSpeed'] != null) _pullSpeed = (changes['pullSpeed']! as num).toDouble();
      if (changes['ringColor'] != null) _ringColor = (changes['ringColor']! as num).toInt();
      if (changes['dotColor'] != null) _dotColor = (changes['dotColor']! as num).toInt();
      if (changes['backColor'] != null) _backColor = (changes['backColor']! as num).toInt();
    });

    final saved = await _invoke<bool>('trickSave', changes);
    if (saved == true) await _loadTrick();
  }

  Future<void> _toggleTrick() async {
    if (_trickRunning) {
      await _invoke('trickStop');
    } else {
      await _invoke('trickStart');
    }
    await _loadTrick();
    if (!mounted) return;
    _toast(_trickRunning
        ? 'Trick button is on screen'
        : 'Trick button is off. ${_loadError ?? ''}');
  }

  Widget _permissionCard(ThemeData theme) => Card(
        child: ListTile(
          leading: Icon(
            Icons.layers,
            color: _canDraw ? Colors.greenAccent : Colors.orangeAccent,
          ),
          title: Text(_canDraw ? 'Overlay permission granted' : 'Overlay permission needed'),
          subtitle: const Text('Required to draw the booster above other apps.'),
          trailing: _canDraw
              ? null
              : TextButton(
                  onPressed: () => unawaited(
                    _guard(() async {
                      await _invoke('openOverlaySettings');
                      await _load();
                    }),
                  ),
                  child: const Text('Grant'),
                ),
        ),
      );

  Future<void> _guard(Future<void> Function() action) async {
    try {
      await action();
    } catch (e, stack) {
      debugPrint('GUARD caught: $e\n$stack');
      _reportError('Unexpected failure: $e');
    }
  }

  Future<void> _toggleBooster() async {
    if (_boosterOn) {
      final r = await _invoke('stopBooster');
      if (r == null && _loadError != null) return;
      if (mounted) setState(() => _boosterOn = false);
      _toast('Booster stopped');
    } else {
      final r = await _invoke('startBooster');
      if (!mounted) return;
      if (r == null && _loadError != null) {
        _toast('Could not start: $_loadError');
        return;
      }
      setState(() => _boosterOn = true);
      _toast('Look for the Booster pill on your screen');
    }
  }

  void _toast(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }
}
