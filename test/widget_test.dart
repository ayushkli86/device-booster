import 'package:device_booster/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // Mirrors the native SharedPreferences store so a save is visible to the
  // config reload that follows it; a static map would snap the switch back to
  // its default and hide exactly the bug this suite guards against.
  final trickConfig = <String, dynamic>{
    'enabled': true,
    'sizeDp': 76,
    'borderDp': 4,
    'opacity': 85,
    'preventOverPull': true,
    'pullSpeed': 1.0,
    'ringColor': 0xFF7EE787,
    'dotColor': 0xFFFF6B6B,
    'backColor': 0xFF101418,
  };
  final trickSaves = <Map<Object?, Object?>>[];

  setUp(() {
    trickConfig
      ..clear()
      ..addAll(const {
        'enabled': true,
        'sizeDp': 76,
        'borderDp': 4,
        'opacity': 85,
        'preventOverPull': true,
        'pullSpeed': 1.0,
        'ringColor': 0xFF7EE787,
        'dotColor': 0xFFFF6B6B,
        'backColor': 0xFF101418,
      });
    trickSaves.clear();

    // The home page talks to a native MethodChannel on first frame; stub it so
    // the widget tree can be pumped without a live Android side.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('com.ff.devicebooster/bridge'),
            (call) async {
          if (call.method == 'trickSave') {
            final changes = (call.arguments as Map).cast<Object?, Object?>();
            trickSaves.add(changes);
            trickConfig.addAll(changes.cast<String, dynamic>());
            return true;
          }
          return switch (call.method) {
            'shizukuStatus' => <String, dynamic>{
                'running': false,
                'granted': false,
                'text': 'Shizuku is not running - start it, then re-check',
              },
            'canDrawOverlays' => false,
            'probeActions' => <String, dynamic>{'actions': <dynamic>[], 'gpuTuning': false},
            'trickConfig' => Map<String, dynamic>.from(trickConfig),
            'trickRunning' => false,
            'trickStart' => true,
            'trickStop' => true,
            'sample' => <String, dynamic>{
                'cpuLoad': 0.25,
                'cpuFreqMHz': 1800,
                'cpuMaxMHz': 3000,
                'ramPercent': 42,
                'batteryTempC': 33.5,
                'batteryPercent': 80,
                'charging': false,
                'cores': 8,
              },
            _ => null,
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.ff.devicebooster/bridge'),
      null,
    );
  });

  /// The home page loads status and a first sample over the bridge on mount.
  /// The page also runs a 1s poll timer, so pumpAndSettle would never return -
  /// a fixed number of pumps lets the async futures resolve instead.
  Future<void> settleInitialLoad(WidgetTester tester) async {
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  testWidgets('shows Shizuku state and live stats', (WidgetTester tester) async {
    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    expect(find.text('Shizuku not running'), findsOneWidget);
    expect(find.textContaining('MHz'), findsOneWidget);
  });

  testWidgets('hides tuning switches until Shizuku is authorised',
      (WidgetTester tester) async {
    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    expect(find.text('Tuning'), findsNothing);
    // The permission card sits below the fold, so scroll to it rather than
    // assuming it is built.
    await tester.scrollUntilVisible(find.text('Overlay permission needed'), 200);
    expect(find.text('Overlay permission needed'), findsOneWidget);
  });

  testWidgets('offers the trick button with size, border and opacity controls',
      (WidgetTester tester) async {
    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    await tester.scrollUntilVisible(find.text('Trick button'), 200);
    expect(find.text('Trick button'), findsOneWidget);
    expect(find.text('Button size'), findsOneWidget);
    expect(find.text('Border width'), findsOneWidget);
    expect(find.text('Opacity'), findsOneWidget);
    expect(find.text('Pull speed'), findsOneWidget);
    expect(find.text('Prevent over pulling'), findsOneWidget);
  });

  testWidgets('disables the trick button start when the overlay is not granted',
      (WidgetTester tester) async {
    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    await tester.scrollUntilVisible(find.text('Start trick button'), 200);
    final button = tester.widget<FilledButton>(
      find.ancestor(
        of: find.text('Start trick button'),
        matching: find.byType(FilledButton),
      ),
    );
    expect(button.onPressed, isNull);
  });

  testWidgets('trick button switch can be turned off and back on',
      (WidgetTester tester) async {
    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    await tester.scrollUntilVisible(find.text('Trick button'), 200);

    // The switch that lives in the same header Row as the 'Trick button' title.
    final headerRow = find
        .ancestor(of: find.text('Trick button'), matching: find.byType(Row))
        .first;
    final toggle = find.descendant(of: headerRow, matching: find.byType(Switch));

    expect(tester.widget<Switch>(toggle).value, isTrue);

    // Off...
    await tester.tap(toggle);
    await settleInitialLoad(tester);
    expect(tester.widget<Switch>(toggle).value, isFalse,
        reason: 'tapping the switch must persist the disabled state');

    // ...and back on again. A switch that disables its own onChanged when off
    // would strand the user here with no way to re-enable the button.
    await tester.tap(toggle);
    await settleInitialLoad(tester);
    expect(tester.widget<Switch>(toggle).value, isTrue,
        reason: 'a disabled trick button must still be re-enableable');

    expect(
      trickSaves.where((m) => m.containsKey('enabled')).map((m) => m['enabled']),
      [false, true],
    );
  });

  testWidgets('shows a failure instead of a blank screen when the bridge throws',
      (WidgetTester tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.ff.devicebooster/bridge'),
      (call) async => throw PlatformException(code: 'boom', message: 'no sensors'),
    );

    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    expect(find.text('Something went wrong'), findsOneWidget);
    expect(find.textContaining('no sensors'), findsOneWidget);
  });
}
