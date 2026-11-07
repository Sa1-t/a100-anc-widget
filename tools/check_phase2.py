#!/usr/bin/env python3
"""Offline source/resource/protocol checks using existing SDK/JDK/Kotlin caches.

Does not replace ./gradlew assembleDebug. All output stays in app/build/phase2-check.
No Gradle home override, network access or device required.
WorkManager is API-stubbed if its real AAR is unavailable; never produces an APK.
"""
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
out = root / 'app/build/phase2-check'
out.mkdir(parents=True, exist_ok=True)
for name in ['generated', 'java', 'dex']:
    (out / name).mkdir(exist_ok=True)
cache = Path('/home/hatch/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin')
compiler = next((cache / 'kotlin-compiler-embeddable/1.9.24').glob('*/*.jar'))
stdlib = next((cache / 'kotlin-stdlib/1.9.24').glob('*/*.jar'))
script_runtime = next((cache / 'kotlin-script-runtime/1.9.24').glob('*/*.jar'))
compiler_cp = ':'.join(map(str, [compiler, stdlib, script_runtime,
    root / 'gradle-dist/lib/kotlin-reflect-1.9.22.jar',
    root / 'gradle-dist/lib/trove4j-1.0.20200330.jar',
    root / 'gradle-dist/lib/annotations-24.0.1.jar']))
java = root / 'jdk17/bin/java'
android = root / 'android-sdk/platforms/android-34/android.jar'
build_tools = root / 'android-sdk/build-tools/34.0.0'
source = root / 'app/src/main/java/com/alpha/ancwidget'

def run(*args):
    subprocess.run(list(map(str, args)), check=True, cwd=root)

def kotlin(output, files, classpath):
    run(java, '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-nowarn',
        '-classpath', ':'.join(map(str, classpath)), '-d', output, *files)

# Widget declarations and minimal UI must agree with the size policy.
a = '{http://schemas.android.com/apk/res/android}'
provider = ET.parse(root / 'app/src/main/res/xml/anc_widget_info.xml').getroot()
assert provider.get(a + 'targetCellWidth') == provider.get(a + 'targetCellHeight') == '2'
assert provider.get(a + 'minWidth') == provider.get(a + 'minHeight') == '110dp'
assert provider.get(a + 'initialLayout') == '@layout/widget_anc_large'
main = ET.parse(root / 'app/src/main/res/layout/activity_main.xml').getroot()
assert main.tag == 'ScrollView' and len(list(main)) == 1 and main[0].tag == 'LinearLayout'
assert main[0][0].tag == 'Button' and main[0][0].get(a + 'text') == '加入到桌面'
assert main[0][0].get(a + 'id') == '@+id/btn_add_widget'
assert [node.tag for node in main.iter() if node.get(a + 'id') in
        ['@+id/ring_colors', '@+id/card_opacity', '@+id/mode_switch_enabled']] == ['LinearLayout', 'SeekBar', 'Switch']
seek = next(node for node in main.iter() if node.tag == 'SeekBar')
assert seek.get(a + 'max') == '60' and seek.get(a + 'progress') == '40'
assert next(node for node in main.iter() if node.tag == 'Switch').get(a + 'checked') == 'true'
ui = (source / 'WidgetSettingsUi.kt').read_text()
assert 'WidgetSettingsUi.bind(this)' in (source / 'MainActivity.kt').read_text()
assert 'for (slot in WidgetSettings.ColorSlot.values())' in ui
assert 'WidgetSettings.setColor(activity, slot, swatch.color)' in ui
assert 'if (fromUser) commit.schedule()' in ui
assert 'slider.postDelayed(this, 100L)' in ui and 'override fun onStopTrackingTouch(seekBar: SeekBar) { commit.flush() }' in ui
assert 'WidgetSettings.setOpacity(activity, slider.progress + WidgetSettings.MIN_OPACITY)' in ui
assert 'WidgetSettingsUi.flush(this)' in (source / 'MainActivity.kt').read_text()
assert 'WidgetSettings.resetAppearance(activity)' in ui and 'container.removeAllViews()' in ui
controls = (source / 'DeviceControlsUi.kt').read_text()
assert 'AncModeController.changeMode(' not in controls and 'AncModeController.refreshBattery(context).message' in controls
assert 'AncModeController.mono(context, target)' in controls and 'monoSwitch.isEnabled = !working && mono != null' in controls
assert 'registerOnSharedPreferenceChangeListener(listener)' in controls and 'unregisterOnSharedPreferenceChangeListener(listener)' in controls
assert 'DeviceControlsUi(this)' in (source / 'MainActivity.kt').read_text()
assert any(node.get(a + 'id') == '@+id/device_controls' and node.tag == 'LinearLayout' for node in main.iter())
assert 'WidgetSettings.setModeSwitchEnabled(activity, checked)' in ui
grid = ET.parse(root / 'app/src/main/res/layout/widget_anc_large.xml').getroot()[1]
assert len(list(grid)) == 2 and all(len(list(row)) == 2 for row in grid)
cell_ids = ['@+id/cell_left', '@+id/cell_right', '@+id/cell_case', '@+id/mode_button']
assert [cell.get(a + 'id') for cell in grid.iter() if cell.get(a + 'id') in cell_ids] == [
    '@+id/cell_left', '@+id/cell_right', '@+id/cell_case', '@+id/mode_button']
assert [[cell.get(a + 'id') for cell in row.iter() if cell.get(a + 'id') in cell_ids]
        for row in grid] == [cell_ids[:2], cell_ids[2:]]
assert not any(node.tag in ('Button', 'ImageButton') for node in grid.iter())
assert not any(node.get(a + 'name') == 'android.permission.INTERNET' for node in
    ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot().findall('uses-permission'))
assert 'android.useAndroidX=true' in (root / 'gradle.properties').read_text()
# Latest UI specification removes all text, including names and mode captions.
# Keep checking cell order and exactly one ImageView per cell through wrappers.
variant_cells = {
    'widget_anc_large.xml': cell_ids,
    'widget_anc_wide.xml': cell_ids[2:],
    'widget_anc_medium.xml': cell_ids[2:],
    'widget_anc.xml': cell_ids[3:],
}
for layout in (root / 'app/src/main/res/layout').glob('widget_anc*.xml'):
    frame = ET.parse(layout).getroot()
    assert frame.tag == 'FrameLayout' and len(frame) == 2
    background, content = frame
    assert background.tag == 'ImageView' and background.get(a + 'id') == '@+id/card_background'
    assert background.get(a + 'scaleType') == 'fitXY'
    assert background.get(a + 'src') == ('@drawable/widget_card_compact' if layout.name == 'widget_anc.xml'
                                         else '@drawable/widget_card')
    assert content.get(a + 'id') == '@+id/widget_card' and content.get(a + 'background') is None
    nodes = list(frame.iter())
    ids = [node.get(a + 'id', '') for node in nodes]
    assert '@+id/mode_ring' in ids and '@+id/mode_button' in ids
    assert [value for value in ids if value in cell_ids] == variant_cells[layout.name]
    assert not any(node.tag in ('TextView', 'Button', 'ImageButton') or node.get(a + 'text')
                   for node in nodes), layout.name
    assert not any(value.startswith('@+id/battery_') for value in ids)
    for node in nodes:
        if node.get(a + 'id') in cell_ids:
            assert len(list(node)) == 1 and node[0].tag == 'ImageView'
            expected_image = node.get(a + 'id').replace('cell_', 'ring_').replace('mode_button', 'mode_ring')
            assert node[0].get(a + 'id') == expected_image
            assert node[0].get(a + 'scaleType') == 'fitCenter'
            if node.get(a + 'id') == '@+id/mode_button':
                assert node.get(a + 'background') == '@drawable/mode_normal'
                # InsetDrawable must not add default padding to the image slot.
                assert node.get(a + 'padding') == '0dp'
permissions = [node.get(a + 'name') for node in
    ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot().findall('uses-permission')]
assert 'android.permission.BLUETOOTH_SCAN' not in permissions
assert 'BLUETOOTH_SCAN' not in (source / 'MainActivity.kt').read_text()
assert 'adapter.cancelDiscovery()' not in (source / 'GaiaClient.kt').read_text()
views = (source / 'AncWidgetProvider.kt').read_text()
assert 'setTextViewText(value' not in views
assert 'setTextViewText' not in views and 'setTextColor' not in views
assert all(f'R.drawable.mode_{mode}' in views for mode in ['normal', 'anc', 'transparency'])
assert 'mode = shownMode' in views and 'when (shownMode)' in views
assert 'status, battery, shownMode, artwork' in views
assert 'setViewPadding(R.id.mode_button, 0, 0, 0, 0)' in views
assert 'context.resources.displayMetrics.density' in views and 'scaledDensity' not in views
assert 'setImageViewBitmap(image, artwork.getValue(device))' in views and 'bindContent(R.id.mode_button, R.id.mode_ring, WidgetSettings.Content.MODE)' in views
assert 'setContentDescription(cell, if (content == WidgetSettings.Content.MODE) description' in views
assert 'BatteryState.display(level)' in views and 'battery.caption()' in views and 'battery.systemLevel' in views and 'battery_system_level' not in views
assert 'views.setInt(R.id.card_background, "setImageAlpha", WidgetSettings.opacityAlpha(opacity))' in views
assert 'R.drawable.widget_card_compact_opaque else R.drawable.widget_card_opaque' in views
assert 'views.setOnClickPendingIntent(id, null)' in views
assert views.index('if (!WidgetSettings.modeSwitchEnabled(context)) return') < views.index('val pending = goAsync()')
assert 'setBackgroundColor' not in views
assert views.count('palette = palette') == 5
renderer = (source / 'BatteryRingRenderer.kt').read_text()
assert 'val modeColor = WidgetAppearance.modeColor(mode, palette)' in renderer
assert 'device == Device.MODE -> modeColor' in renderer and 'paint.color = modeColor' in renderer
assert 'level == 0 -> palette.low' in renderer
assert 'WidgetAppearance.batteryColor(level, palette = palette)' in renderer
print('PASS all 4 layouts: ordered ring-only cells, no text, shared mode snapshot/background binding, accessibility retained; no SCAN or discovery call', flush=True)

# Palette and actual backgrounds must match the plan, independently of Kotlin compilation.
colors = {node.get('name'): node.text.upper() for node in
          ET.parse(root / 'app/src/main/res/values/colors.xml').getroot()}
expected_colors = dict(surface='CC191C22', surface_opaque='191C22', track='363C46', icon='E3E7ED', label='C3CAD3', caption='A3ACB9',
    battery_green='65CC88', battery_amber='E8BC60', battery_red='EE7B82', battery_unknown='697381',
    mode_normal='D3D9E2', mode_anc='8DBBFA', mode_transparency='78D2BD',
    mode_normal_surface='242931', mode_anc_surface='202D3E', mode_transparency_surface='20342F')
assert colors == {'widget_' + key: '#' + value for key, value in expected_colors.items()}
appearance = (source / 'WidgetAppearance.kt').read_text()
for name, resource in dict(TRACK='track', ICON='icon', GREEN='battery_green', AMBER='battery_amber',
                           RED='battery_red', NEUTRAL='battery_unknown').items():
    assert f'const val {name} = 0xFF{expected_colors[resource]}.toInt()' in appearance
for mode in ['normal', 'anc', 'transparency']:
    assert f'AmbientMode.{mode.upper()} -> 0xFF{expected_colors["mode_" + mode]}.toInt()' in appearance
    drawable = ET.parse(root / f'app/src/main/res/drawable/mode_{mode}.xml').getroot()
    assert drawable.tag == 'inset'
    assert all(drawable.get(a + 'inset' + edge) == '2dp' for edge in ['Left', 'Top', 'Right', 'Bottom'])
    assert drawable.find('shape/solid').get(a + 'color') == '@android:color/transparent'
    assert drawable.find('shape/corners').get(a + 'radius') == '10dp'
for name, radius in [('widget_card', '20dp'), ('widget_card_compact', '16dp')]:
    drawable = ET.parse(root / f'app/src/main/res/drawable/{name}.xml').getroot()
    assert drawable.find('solid').get(a + 'color') == '@color/widget_surface'
    assert drawable.find('corners').get(a + 'radius') == radius
    opaque = ET.parse(root / f'app/src/main/res/drawable/{name}_opaque.xml').getroot()
    assert opaque.find('solid').get(a + 'color') == '@color/widget_surface_opaque'
    assert opaque.find('corners').get(a + 'radius') == radius
print('PASS exact widget palette, matching Kotlin colors, mode insets and card corners', flush=True)

print('PASS default 2x2 declaration, four-cell grid, pin button above settings, independent rounded alpha layer, disabled click guard, AndroidX enabled, no Internet permission', flush=True)

# AGP normally supplies package/min/target; standalone aapt2 needs these explicitly.
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
(out / 'AndroidManifest.xml').write_text(manifest.replace(
    '<manifest ', '<manifest package="com.alpha.ancwidget" ', 1))
run(build_tools / 'aapt2', 'compile', '--dir', root / 'app/src/main/res', '-o', out / 'resources.zip')
run(build_tools / 'aapt2', 'link', '-o', out / 'resources.ap_', '--manifest', out / 'AndroidManifest.xml',
    '-I', android, '--java', out / 'generated', '--min-sdk-version', '26',
    '--target-sdk-version', '34', out / 'resources.zip')
run(root / 'jdk17/bin/javac', '-source', '17', '-target', '17', '-d', out / 'java',
    out / 'generated/com/alpha/ancwidget/R.java')
# Prefer the actual dependency when cached or provided, otherwise explicitly report the boundary.
work_aars = list(Path('/home/hatch/.gradle/caches/modules-2/files-2.1').glob(
    'androidx.work/work-runtime/2.9.1/*/*.aar')) + list((out / 'deps').glob('work-runtime-2.9.1.aar'))
work_sources = []
work_classpath = []
if work_aars:
    with zipfile.ZipFile(work_aars[0]) as archive:
        (out / 'work-runtime.jar').write_bytes(archive.read('classes.jar'))
    work_classpath = [out / 'work-runtime.jar']
else:
    work_sources = sorted((root / 'tools/work-tests').glob('*.kt'))
    print('LIMIT: real WorkManager AAR is not cached; compiling its call sites against test API stubs.', flush=True)
kotlin(out / 'app.jar', sorted(source.glob('*.kt')) + work_sources,
    [android, stdlib, out / 'java'] + work_classpath)
with zipfile.ZipFile(out / 'r.jar', 'w') as archive:
    for file in sorted((out / 'java').rglob('*.class')):
        archive.write(file, file.relative_to(out / 'java'))
run(java, '-cp', build_tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', android,
    '--min-api', '26', '--output', out / 'dex', out / 'app.jar', out / 'r.jar', stdlib)
print('PASS production resources, Kotlin 1.9.24 sources and D8 dex compilation (WorkManager boundary as above)', flush=True)

# Compile the actual protocol/controller/cache against fake framework boundaries.
files = [source / name for name in ['GaiaClient.kt', 'MonoProtocol.kt', 'BatteryRefreshGate.kt', 'BatteryState.kt', 'BatteryStore.kt', 'AncModeController.kt', 'WidgetLayoutPolicy.kt', 'BatteryRefreshWorker.kt', 'BatteryMonitorService.kt', 'WidgetAppearance.kt', 'WidgetSettings.kt']]
files += sorted((root / 'tools/protocol-tests').glob('*.kt'))
files += sorted((root / 'tools/work-tests').glob('*.kt'))
# Exercise the production pure geometry helper without Android font or rendering stubs.
geometry_checks = out / 'GeometryChecks.kt'
geometry_checks.write_text('''package com.alpha.ancwidget
import kotlin.math.roundToInt

fun main() {
    // Expected Bitmap sizes after removing the entire text budget (L1=L2=Q=0).
    val cases = listOf(
        Triple(110f, 110f, 43), Triple(130f, 220f, 44), Triple(160f, 160f, 56),
        Triple(180f, 180f, 64), Triple(220f, 220f, 64), Triple(269f, 117f, 44),
        Triple(220f, 64f, 40), Triple(48f, 110f, 32), Triple(48f, 48f, 32),
        Triple(109f, 110f, 40), Triple(110f, 109f, 40), Triple(219f, 64f, 40),
        Triple(220f, 63f, 40), Triple(159f, 159f, 44))
    var checks = 0
    for ((width, height, expectedSide) in cases) {
        val layout = WidgetLayoutPolicy.select(width, height)
        val standard = layout == WidgetLayoutPolicy.Layout.GRID && minOf(width, height) >= 160f
        val columns = when (layout) {
            WidgetLayoutPolicy.Layout.GRID -> 2
            WidgetLayoutPolicy.Layout.WIDE -> 2
            else -> 1
        }
        val rows = if (layout == WidgetLayoutPolicy.Layout.GRID || layout == WidgetLayoutPolicy.Layout.MEDIUM) 2 else 1
        for (density in listOf(1f, 1.25f, 1.5f, 2.625f, 3f, 3.5f)) {
            // The helper receives physical density, never fontScale/scaledDensity.
            for (fontScale in listOf(1f, 1.3f)) {
                val g = WidgetRingGeometry.calculate(layout, width, height, density)
                if (density == 1f) check(g.side == expectedSide) {
                    "$width x $height, fontScale $fontScale: ${g.side} != $expectedSide"
                }
                check(g.side >= 0 && g.cells.size == rows * columns)
                val cap = if (standard) 64f else if (layout == WidgetLayoutPolicy.Layout.GRID) 44f else 40f
                check(g.side <= cap * density)
                val gap = ((if (standard) 8f else 4f) * density).roundToInt()
                val p = g.cardPadding
                check(p == ((if (standard) 12f else 6f) * density).roundToInt())
                val innerWidth = (width * density).roundToInt() - 2 * p
                val innerHeight = (height * density).roundToInt() - 2 * p
                check(g.cells.take(columns).sumOf { it.width } + gap * (columns - 1) == innerWidth)
                check((0 until rows).sumOf { g.cells[it * columns].height } + gap * (rows - 1) == innerHeight)
                for ((index, cell) in g.cells.withIndex()) {
                    val i = cell.image
                    val w = cell.wrapper
                    check(listOf(i.left, i.top, i.right, i.bottom, w.left, w.top, w.right, w.bottom).all { it >= 0 })
                    check(cell.width - i.left - i.right == g.side)
                    check(cell.height - i.top - i.bottom == g.side) // No text/gap budget remains.
                    check(i.right - i.left in 0..1 && i.bottom - i.top in 0..1)
                    if (index % columns + 1 < columns) check(w.right + g.cells[index + 1].wrapper.left == gap)
                    if (layout == WidgetLayoutPolicy.Layout.MEDIUM && index == 0)
                        check(w.bottom + g.cells[1].wrapper.top == gap)
                }
                if (layout == WidgetLayoutPolicy.Layout.GRID)
                    check(g.rows[0].bottom + g.rows[1].top == gap)
                if (layout == WidgetLayoutPolicy.Layout.GRID || layout == WidgetLayoutPolicy.Layout.WIDE) {
                    // Check real weighted wrapper budgets, including odd pixel remainders.
                    for (row in 0 until rows) {
                        var remaining = innerWidth
                        for (column in 0 until columns) {
                            val wrapperWidth = remaining / (columns - column)
                            remaining -= wrapperWidth
                            val cell = g.cells[row * columns + column]
                            check(cell.width + cell.wrapper.left + cell.wrapper.right == wrapperWidth)
                        }
                    }
                }
                if (rows == 2) {
                    var remaining = innerHeight
                    for (row in 0 until rows) {
                        val wrapperHeight = remaining / (rows - row)
                        remaining -= wrapperHeight
                        val cell = g.cells[row * columns]
                        val insets = if (layout == WidgetLayoutPolicy.Layout.GRID) g.rows[row] else cell.wrapper
                        check(cell.height + insets.top + insets.bottom == wrapperHeight)
                    }
                }
                checks++
            }
        }
    }
    for ((level, color) in listOf(null to WidgetAppearance.NEUTRAL, -1 to WidgetAppearance.NEUTRAL,
        101 to WidgetAppearance.NEUTRAL, 0 to WidgetAppearance.RED, 1 to WidgetAppearance.RED,
        19 to WidgetAppearance.RED, 20 to WidgetAppearance.AMBER, 49 to WidgetAppearance.AMBER,
        50 to WidgetAppearance.GREEN, 99 to WidgetAppearance.GREEN, 100 to WidgetAppearance.GREEN))
        check(WidgetAppearance.batteryColor(level) == color)
    println("PASS $checks geometry cases: requested sizes/boundaries, 6 densities, fontScale 1.0/1.3; equal ring S, budgets and caps; all battery boundaries")
}
''')
files += [geometry_checks]
kotlin(out / 'checks.jar', files, [stdlib])
run(java, '-cp', f'{out / "checks.jar"}:{stdlib}', 'com.alpha.ancwidget.ProtocolChecksKt')
run(java, '-cp', f'{out / "checks.jar"}:{stdlib}', 'com.alpha.ancwidget.GeometryChecksKt')
run(java, '-cp', f'{out / "checks.jar"}:{stdlib}', 'com.alpha.ancwidget.SettingsChecksKt')
run(java, '-cp', f'{out / "checks.jar"}:{stdlib}', 'com.alpha.ancwidget.MonoChecksKt')

# Compile the actual Activity against observable framework boundaries.
pin_files = [source / name for name in ['MainActivity.kt', 'BatteryStore.kt', 'BatteryState.kt']]
pin_files += sorted((root / 'tools/pin-tests').glob('*.kt'))
pin_files += [root / 'tools/protocol-tests' / name for name in
    ['AndroidBuild.kt', 'AndroidManifest.kt', 'AndroidPackageManager.kt']]
kotlin(out / 'pin-checks.jar', pin_files, [stdlib])
run(java, '-cp', f'{out / "pin-checks.jar"}:{stdlib}', 'com.alpha.ancwidget.PinChecksKt')
