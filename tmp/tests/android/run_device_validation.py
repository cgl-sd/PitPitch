#!/usr/bin/env python3
"""Run maintained Compose tests on the selected existing device; keep evidence in tmp/."""
import argparse
from pathlib import Path
import subprocess
import re

parser = argparse.ArgumentParser()
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
parser.add_argument('--skip-suite', action='store_true', help='Resume persistence and display checks after the suite was run separately')
args = parser.parse_args()
root = Path(__file__).resolve().parents[3]
out = root / 'tmp/validation'
out.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]


def adb(*parts, timeout=180):
    return subprocess.run(base + list(parts), capture_output=True, timeout=timeout, check=True).stdout


def instrument(name, test):
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test,
                 'com.example.pitpitch.test/androidx.test.runner.AndroidJUnitRunner', timeout=600)
    (out / (name + '.log')).write_bytes(result)
    text = result.decode(errors='replace')
    if 'FAILURES!!!' in text or not re.search(r'OK \(\d+ tests?\)', text):
        raise RuntimeError(f'{name} failed: inspect {out / (name + ".log")}')
    print(name + ': ' + re.search(r'OK \(\d+ tests?\)', text).group(), flush=True)


def screenshots(folder, baseline_only=False, real_only=False):
    destination = out / folder
    destination.mkdir(exist_ok=True)
    names = adb('shell', 'run-as', 'com.example.pitpitch', 'ls', 'cache/pitpitch-validation').decode().splitlines()
    for name in names:
        if name.endswith(('.png', '.json')) and name.startswith('v05-') and '/' not in name and (not baseline_only or name.startswith(('v05-tab-', 'v05-football-detail-'))) and (not real_only or name.startswith(('v05-real-', 'v05-f1-results', 'v05-f1-driver-standings', 'v05-f1-constructor-standings'))):
            (destination / name).write_bytes(adb('exec-out', 'run-as', 'com.example.pitpitch', 'cat', 'cache/pitpitch-validation/' + name))

for relative, name in [('app/build/outputs/apk/debug/app-debug.apk', 'local-v05-install'),
                       ('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 'local-v05-test-install')]:
    result = adb('install', '-r', str(root / relative))
    (out / (name + '.log')).write_bytes(result)
    if b'Success' not in result:
        raise RuntimeError(name + ' failed')

if not args.skip_suite:
    classes = ','.join('com.example.pitpitch.' + name for name in [
        'LocalFlowTest', 'NavigationFlowTest', 'HomeFlowTest', 'SportsPagesFlowTest', 'RemoteDataTest', 'RemoteFlowTest', 'RemoteErrorUiTest', 'PerformanceSampleTest', 'EuropeanPagesFlowTest', 'EuropeanArchiveTest'])
    # Capture the actual test interaction; this recording is a local, disposable artifact.
    recording = subprocess.Popen(base + ['shell', 'screenrecord', '--time-limit', '180', '/sdcard/pitpitch-v05.mp4'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        instrument('local-v05-device-tests', classes)
    finally:
        subprocess.run(base + ['shell', 'pkill', '-INT', 'screenrecord'], capture_output=True)
        try: recording.wait(timeout=15)
        except subprocess.TimeoutExpired: recording.terminate()
        subprocess.run(base + ['pull', '/sdcard/pitpitch-v05.mp4', str(out / 'local-v05-interactions.mp4')], capture_output=True)

screenshots('v05-411-1.0')
instrument('local-v05-process-seed', 'com.example.pitpitch.ProcessPersistenceTest#aSeed')
adb('shell', 'am', 'force-stop', 'com.example.pitpitch')
instrument('local-v05-process-restore', 'com.example.pitpitch.ProcessPersistenceTest#bVerify')

# Save and restore the selected emulator's existing display settings; never wipe app data.
old_density = adb('shell', 'wm', 'density').decode()
old_size = adb('shell', 'wm', 'size').decode()
old_font = adb('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
(out / 'local-v05-display-before.txt').write_text(old_density + old_size + 'font_scale=' + old_font)
try:
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    for density, width in [(480, 360), (440, 393)]:
        adb('shell', 'wm', 'density', str(density))
        instrument(f'local-v05-{width}-font13', 'com.example.pitpitch.LocalFlowTest#captureWhitePageBaselines')
        screenshots(f'v05-{width}-1.3', baseline_only=True)
        instrument(f'local-v05-{width}-real-font13', 'com.example.pitpitch.RemoteFlowTest#realFootballAndF1ResultsRankingsShowSourceAndNoDemo')
        screenshots(f'v05-{width}-1.3', real_only=True)
finally:
    match = re.search(r'Override density: (\d+)', old_density)
    adb('shell', 'wm', 'density', match.group(1) if match else 'reset')
    adb('shell', 'settings', 'put', 'system', 'font_scale', old_font if old_font != 'null' else '1.0')

adb('shell', 'am', 'force-stop', 'com.example.pitpitch')
(out / 'local-v05-launch.log').write_bytes(adb('shell', 'am', 'start', '-W', '-n', 'com.example.pitpitch/.MainActivity'))
print('Display restored; latest app relaunched.', flush=True)
