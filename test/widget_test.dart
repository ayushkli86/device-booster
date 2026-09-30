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

  // The tuning and performance cards are only rendered once Shizuku is
  // authorised, so a test that needs them has to flip this.
  var shizukuGranted = false;

  // Mirrors whether the native service is on screen; the card's subtitle
  // changes with it.
  var trickRunning = false;

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
        'locked': false,
      });
    trickSaves.clear();
    shizukuGranted = false;
    trickRunning = false;

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
                'running': shizukuGranted,
                'granted': shizukuGranted,
                'text': 'Shizuku is not running - start it, then re-check',
              },
            'canDrawOverlays' => false,
            'probeTierActions' => <String, dynamic>{
              'tierActions': <dynamic>[
                <String, dynamic>{
                  'id': 'phantom_processes',
                  'label': 'Phantom process limit',
                  'detail': 'How many unowned child processes may exist.',
                  'note': 'Held in memory only.',
                  'current': 'max',
                  'tiers': <dynamic>[
                    <String, dynamic>{'id': 'low', 'label': 'Low', 'value': '32'},
                    <String, dynamic>{'id': 'max', 'label': 'Max', 'value': '1024'},
                  ],
                },
              ],
            },
            'computilityLevels' => <String, dynamic>{
              'cpuLevel': '1',
              'gpuLevel': '1',
              'version': '2025',
              'present': true,
              'writable': false,
            },
            'probeActions' => <String, dynamic>{'actions': <dynamic>[], 'gpuTuning': false},
            'trickConfig' => Map<String, dynamic>.from(trickConfig),
            'trickRunning' => trickRunning,
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

  testWidgets('lock toggle can be turned on and off again',
      (WidgetTester tester) async {
    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    await tester.scrollUntilVisible(find.text('Lock button position'), 200);
    final tile = find.widgetWithText(SwitchListTile, 'Lock button position');
    final toggle = find.descendant(of: tile, matching: find.byType(Switch));
    // The row can still sit below the fold of the 800x600 test surface even
    // after its label scrolled into view.
    await tester.ensureVisible(toggle);
    await tester.pumpAndSettle();

    expect(tester.widget<Switch>(toggle).value, isFalse);

    await tester.tap(toggle);
    await settleInitialLoad(tester);
    expect(tester.widget<Switch>(toggle).value, isTrue);

    await tester.tap(toggle);
    await settleInitialLoad(tester);
    expect(tester.widget<Switch>(toggle).value, isFalse);

    expect(
      trickSaves.where((m) => m.containsKey('locked')).map((m) => m['locked']),
      [true, false],
    );
  });

  testWidgets('shows phantom process tiers and the read-only vendor level',
      (WidgetTester tester) async {
    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    // The tuning and performance cards only appear once Shizuku is authorised.
    expect(find.text('Performance tiers'), findsNothing);

    shizukuGranted = true;
    await tester.tap(find.byIcon(Icons.refresh));
    await settleInitialLoad(tester);

    await tester.scrollUntilVisible(find.text('Performance tiers'), 200);
    expect(find.text('Performance tiers'), findsOneWidget);
    expect(find.text('Phantom process limit'), findsOneWidget);
    expect(find.text('Held in memory only.'), findsOneWidget);
    expect(find.text('Low'), findsOneWidget);
    expect(find.text('Max'), findsOneWidget);

    // The device's actual computility level is reported, not offered as a control.
    expect(find.textContaining('CPU level 1'), findsOneWidget);
    expect(find.textContaining('Read-only.'), findsOneWidget);
  });

  testWidgets('a locked running button describes pass-through until unlocked',
      (WidgetTester tester) async {
    trickRunning = true;
    trickConfig['locked'] = true;
    await tester.pumpWidget(const BoosterApp());
    await settleInitialLoad(tester);

    await tester.scrollUntilVisible(find.text('Lock button position'), 200);
    expect(find.textContaining('taps pass straight through'), findsOneWidget,
        reason: 'locked state must promise touch pass-through, not dragging');
    // The preview mirrors the native locked face: the padlock icon on the disc.
    expect(find.byIcon(Icons.lock), findsOneWidget);

    // Unlocking from the app must update the subtitle through the same
    // save-reload path the device uses, so the copy can never go stale.
    final tile = find.widgetWithText(SwitchListTile, 'Lock button position');
    final toggle = find.descendant(of: tile, matching: find.byType(Switch));
    await tester.ensureVisible(toggle);
    await tester.pumpAndSettle();
    await tester.tap(toggle);
    await settleInitialLoad(tester);

    expect(tester.widget<Switch>(toggle).value, isFalse);
    expect(find.textContaining('Drag to move it'), findsOneWidget,
        reason: 'unlocked state must describe dragging, not pass-through');
    expect(find.byIcon(Icons.lock), findsNothing);
    expect(
      trickSaves.where((m) => m.containsKey('locked')).map((m) => m['locked']),
      [false],
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
