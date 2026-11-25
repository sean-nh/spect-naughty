#!/usr/bin/env python3
"""
Smoke test for Spectr-Acular. Runs against ANY Android device that adb can reach:
the emulator, a phone with USB debugging, or a radio that allows adb (USB or Wi-Fi).

What it does
  1. finds adb and the device, installs the APK, grants the audio permission
  2. starts the app with a self-test request; the app plays its own short test tone and reports what it heard
     (so no music app is needed on the device)
  3. checks: the app is alive, no crash, the audio tap heard the tone, frame rate, no page/draw errors
  4. optional: --media starts a test song in Chrome and also checks the Now Playing pause/resume buttons
  5. optional: --emulator-extras kills the audio service (needs an emulator or a rooted device) to prove the audio tap rebuilds
  6. saves a screenshot and the relevant log lines in tests/results/<time>/

Usage:   python tools/smoke_test.py [--serial SERIAL] [--apk PATH] [--no-install] [--media] [--emulator-extras]
Needs:   Python 3, adb (found automatically in the Android SDK or on PATH). Pillow is optional (blank-screen check).
Safe:    it plays a 4 second test tone through the device speakers, force-stops and restarts the app.
Exit:    0 = no failures, 1 = at least one FAIL
"""
import argparse
import functools
import glob
import http.server
import json
import os
import re
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

PKG = 'com.hector.spectanalyzery'
ACTIVITY = PKG + '/com.hector.spectrum.MainActivity'
ROOT = Path(__file__).resolve().parent.parent

results = []  # (status, name, detail)


def record(status, name, detail=''):
    results.append((status, name, detail))
    print('  [%-4s] %s%s' % (status, name, ('  -  ' + detail) if detail else ''))
    sys.stdout.flush()


def find_adb():
    cands = []
    for var in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(var):
            cands.append(os.path.join(os.environ[var], 'platform-tools', 'adb'))
    if os.environ.get('LOCALAPPDATA'):
        cands.append(os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk', 'platform-tools', 'adb'))
    cands.append(os.path.expanduser('~/Android/Sdk/platform-tools/adb'))
    cands.append(os.path.expanduser('~/Library/Android/sdk/platform-tools/adb'))
    for c in cands:
        for ext in ('', '.exe'):
            if os.path.isfile(c + ext):
                return c + ext
    return 'adb'  # hope it is on PATH


class Adb:
    def __init__(self, exe, serial=None):
        self.exe, self.serial = exe, serial

    def cmd(self, *args, timeout=60, binary=False, serial=True):
        full = [self.exe] + (['-s', self.serial] if (self.serial and serial) else []) + list(args)
        try:
            p = subprocess.run(full, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            # adb hung (device went away, or the emulator died): report it instead of crashing the whole run
            return 124, (b'' if binary else ''), 'adb timed out after %ss: %s' % (timeout, ' '.join(args[:3]))
        out = p.stdout if binary else p.stdout.decode('utf-8', 'replace')
        return p.returncode, out, p.stderr.decode('utf-8', 'replace')

    def shell(self, *args, timeout=60):
        rc, out, err = self.cmd('shell', *args, timeout=timeout)
        return out.strip()


def pick_device(adb, wanted):
    rc, out, err = adb.cmd('devices', serial=False)
    devs = [l.split('\t')[0] for l in out.splitlines()[1:] if l.strip().endswith('device')]
    if wanted:
        if wanted not in devs:
            sys.exit('Device %s is not connected. Connected: %s' % (wanted, devs or 'none'))
        return wanted
    if len(devs) == 1:
        return devs[0]
    if not devs:
        sys.exit('No device found. Start the emulator, or connect a phone / radio with USB debugging on, then try again.\n' + err)
    sys.exit('More than one device is connected: %s\nChoose one with --serial.' % devs)


def aapt_version(apk):
    pats = []
    for var in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(var):
            pats.append(os.path.join(os.environ[var], 'build-tools', '*', 'aapt2*'))
    if os.environ.get('LOCALAPPDATA'):
        pats.append(os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk', 'build-tools', '*', 'aapt2*'))
    for pat in pats:
        found = sorted(glob.glob(pat))
        if found:
            try:
                out = subprocess.run([found[-1], 'dump', 'badging', apk], capture_output=True, timeout=30).stdout.decode('utf-8', 'replace')
                m = re.search(r"versionName='([^']+)'", out)
                if m:
                    return m.group(1)
            except Exception:
                pass
    return None


def screen_size(a):
    m = re.search(r'(\d+)x(\d+)', a.shell('wm', 'size'))
    return (int(m.group(1)), int(m.group(2))) if m else (1280, 720)


def start_media(a, tmpdir):
    """Serve tests/mediatest from this PC and play it in Chrome on the device. Returns the server, or None."""
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass
    handler = functools.partial(Quiet, directory=str(ROOT / 'tests' / 'mediatest'))
    srv = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    a.cmd('reverse', 'tcp:%d' % port, 'tcp:%d' % port)
    if 'com.android.chrome' not in a.shell('pm', 'list', 'packages', 'com.android.chrome'):
        record('SKIP', 'test song', 'Chrome is not installed on this device')
        return srv, None
    a.shell('am', 'force-stop', 'com.android.chrome')
    a.shell('am', 'start', '-a', 'android.intent.action.VIEW', '-d', 'http://localhost:%d/t.html' % port, 'com.android.chrome')
    time.sleep(9)
    w, h = screen_size(a)
    a.shell('input', 'tap', str(w // 2), str(h // 2))
    time.sleep(4)
    dump = a.shell('dumpsys', 'media_session')
    if re.search(r'package=com\.android\.chrome.*?state=PlaybackState \{state=3', dump, re.S):
        record('PASS', 'test song', 'Chrome is playing the test track')
        return srv, port
    record('SKIP', 'test song', 'Chrome did not start playing (open Chrome once by hand and dismiss its welcome screens, then retry)')
    return srv, None


def read_selftests(a):
    rc, out, err = a.cmd('logcat', '-d', '-s', 'SpectTest:I', timeout=30)
    found = []
    for line in out.splitlines():
        m = re.search(r'SpectTest: (\{.*\})\s*$', line)
        if m:
            try:
                found.append(json.loads(m.group(1)))
            except ValueError:
                pass
    return found


def wait_selftest(a, count, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        found = read_selftests(a)
        if len(found) >= count:
            return found[count - 1]
        time.sleep(2)
    return None


def check_selftest(res, is_emu, want_controls):
    if res is None:
        record('FAIL', 'self-test result', 'the app never reported back (did it start? is USB debugging on?)')
        return
    tone = res.get('tone') or {}
    record('PASS', 'self-test result', '%s, Android SDK %s, app %s, WebView %s' % (res.get('model') or '?', res.get('sdk'), res.get('app'), res.get('webView')))
    if res.get('audioGranted') is False:
        record('FAIL', 'audio permission', 'not granted')
    if not tone.get('ok'):
        record('FAIL', 'test tone', 'could not be played: %s' % tone.get('err'))
    elif tone.get('heard') and tone.get('via') == 'native':
        record('PASS', 'direct audio heard the tone', '%d frames with sound' % tone.get('frames', 0))
    elif tone.get('heard'):
        record('WARN', 'tone heard only by the microphone', 'direct audio did not hear it, the app fell back to the microphone')
    else:
        record('FAIL' if is_emu else 'WARN', 'tone NOT heard',
               'via %s (%s frames, peak %s). On a real radio this can be normal if its audio bypasses Android.' % (tone.get('via'), tone.get('frames'), tone.get('peak')))
    fps = res.get('fps', 0)
    record('PASS' if fps >= 20 else 'WARN', 'frame rate', '%s fps%s' % (fps, ' (Performance: Light)' if res.get('perf') else ''))
    record('PASS' if not res.get('drawErrors') and not res.get('jsErrors') else 'FAIL', 'no page or draw errors',
           'page errors %s, draw errors %s' % (res.get('jsErrors'), res.get('drawErrors')))
    record('PASS' if not res.get('tapRebuilt') else 'WARN', 'audio tap stayed up', 'rebuilt %s time(s)' % res.get('tapRebuilt'))
    if want_controls:
        c = res.get('controls')
        if not c or 'error' in c:
            record('FAIL', 'Now Playing buttons', 'no result (%s)' % (c or {}).get('error', 'is notification access on?'))
        elif c.get('before') and not c.get('after') and c.get('back'):
            record('PASS', 'Now Playing pause + resume', 'playing -> paused -> playing, sent via %s' % c.get('via'))
        else:
            record('FAIL', 'Now Playing pause + resume', 'before=%s after=%s back=%s via=%s' % (c.get('before'), c.get('after'), c.get('back'), c.get('via')))
    else:
        np = res.get('np') or {}
        record('SKIP', 'Now Playing buttons', 'no music app was playing' + ('' if np.get('enabled') else ' (and notification access is off)'))


def main():
    ap = argparse.ArgumentParser(description='Smoke test for Spectr-Acular (emulator, phone or radio over adb)')
    ap.add_argument('--serial', help='which device, if more than one is connected')
    ap.add_argument('--apk', default=None, help='default: Spectr-Acular.apk (or Spectr-Acular-Phone.apk with --phone)')
    ap.add_argument('--phone', action='store_true', help='test the phone build (app id com.hector.spectanalyzery.phone) instead of the radio build')
    ap.add_argument('--no-install', action='store_true', help='test what is already installed')
    ap.add_argument('--media', action='store_true', help='also play a test song in Chrome and test the Now Playing buttons')
    ap.add_argument('--emulator-extras', action='store_true', help='also kill the audio service to prove the audio tap rebuilds (emulator / rooted only)')
    ap.add_argument('--wait', type=int, default=90, help='seconds to wait for the self-test result')
    args = ap.parse_args()
    global PKG, ACTIVITY
    if args.phone:
        PKG = 'com.hector.spectanalyzery.phone'
        ACTIVITY = PKG + '/com.hector.spectrum.MainActivity'
    if not args.apk:
        args.apk = str(ROOT / ('Spectr-Acular-Phone.apk' if args.phone else 'Spectr-Acular.apk'))

    adb = Adb(find_adb())
    serial = pick_device(adb, args.serial)
    a = Adb(adb.exe, serial)
    out_dir = ROOT / 'tests' / 'results' / datetime.now().strftime('%Y%m%d-%H%M%S')
    out_dir.mkdir(parents=True, exist_ok=True)

    model = a.shell('getprop', 'ro.product.model')
    release = a.shell('getprop', 'ro.build.version.release')
    sdk = a.shell('getprop', 'ro.build.version.sdk')
    is_emu = serial.startswith('emulator-') or a.shell('getprop', 'ro.hardware') in ('ranchu', 'goldfish')
    print('Device: %s (%s), Android %s (API %s)%s' % (model, serial, release, sdk, '  [emulator]' if is_emu else ''))
    print('Results will be saved in: %s\n' % out_dir)
    record('PASS', 'device reachable', '%s, Android %s' % (model, release))

    # ---- install --------------------------------------------------------------------------------
    if args.no_install:
        record('SKIP', 'install', '--no-install')
    else:
        if not os.path.isfile(args.apk):
            record('FAIL', 'install', 'APK not found: %s' % args.apk)
            return finish(out_dir)
        rc, o, e = a.cmd('install', '-r', args.apk, timeout=180)
        record('PASS' if 'Success' in o else 'FAIL', 'install', (o.strip() or e.strip())[:140])
    installed = re.search(r'versionName=(\S+)', a.shell('dumpsys', 'package', PKG))
    expected = aapt_version(args.apk) if not args.no_install else None
    if not installed:
        record('FAIL', 'app installed', 'package %s is not installed' % PKG)
        return finish(out_dir)
    record('PASS' if (not expected or expected == installed.group(1)) else 'FAIL', 'app version',
           'installed %s%s' % (installed.group(1), (', APK says %s' % expected) if expected else ''))

    # ---- permissions ----------------------------------------------------------------------------
    rc, o, e = a.cmd('shell', 'pm', 'grant', PKG, 'android.permission.RECORD_AUDIO')
    record('PASS' if rc == 0 and 'Exception' not in (o + e) else 'WARN', 'audio permission granted', (o + e).strip()[:100])
    rc, o, e = a.cmd('shell', 'cmd', 'notification', 'allow_listener', PKG + '/com.hector.spectrum.NowPlayingListener')
    record('PASS' if rc == 0 and 'xception' not in (o + e) else 'SKIP', 'notification access', (o + e).strip()[:100] or 'allowed')

    # ---- optional test song -----------------------------------------------------------------------
    srv, media_ok = (None, None)
    if args.media:
        print('\nStarting a test song...')
        srv, media_ok = start_media(a, out_dir)

    # ---- launch with a self-test --------------------------------------------------------------
    kind = 'tone+controls' if media_ok else 'tone'
    print('\nLaunching the app (self-test: %s, takes about 20 seconds)...' % kind)
    a.shell('am', 'force-stop', PKG)
    a.cmd('logcat', '-c')
    time.sleep(1)
    a.shell('am', 'start', '-n', ACTIVITY, '--es', 'selftest', kind)
    res = wait_selftest(a, 1, args.wait)
    check_selftest(res, is_emu, bool(media_ok))

    # ---- health ---------------------------------------------------------------------------------
    pid = a.shell('pidof', PKG)
    record('PASS' if pid else 'FAIL', 'app still running', 'pid %s' % pid if pid else 'the process is gone')
    rc, crash, e = a.cmd('logcat', '-d', '-s', 'AndroidRuntime:E')
    mine = [l for l in crash.splitlines() if PKG in l or 'FATAL EXCEPTION' in l]
    record('PASS' if not mine else 'FAIL', 'no crash in the log', '' if not mine else mine[0][:120])

    # ---- screenshot -------------------------------------------------------------------------------
    rc, png, e = a.cmd('exec-out', 'screencap', '-p', binary=True, timeout=30)
    shot = out_dir / 'screen.png'
    if png and png[:4] == b'\x89PNG':
        shot.write_bytes(png)
        try:
            from PIL import Image, ImageStat
            im = Image.open(shot).convert('L').resize((160, 100))
            sd = ImageStat.Stat(im).stddev[0]
            record('PASS' if sd > 3 else 'FAIL', 'screen is not blank', 'picture variation %.1f, saved %s' % (sd, shot.name))
        except ImportError:
            record('SKIP', 'screen is not blank', 'Pillow is not installed; screenshot saved as %s' % shot.name)
    else:
        record('WARN', 'screenshot', 'could not capture the screen')

    # ---- emulator extras --------------------------------------------------------------------------------
    if args.emulator_extras:
        print('\nKilling the audio service to check the audio tap rebuilds...')
        rc, o, e = adb.cmd('-s', serial, 'root')
        time.sleep(3)
        if 'cannot run as root' in (o + e) or rc != 0:
            record('SKIP', 'audio tap rebuild', 'this device is not rooted (use an emulator without Play Store)')
        else:
            a.cmd('logcat', '-c')
            a.shell('killall', 'audioserver')
            time.sleep(10)
            rc, o, e = a.cmd('logcat', '-d', '-s', 'SpectViz:V')
            ok = 'audio tap restored' in o
            record('PASS' if ok else 'FAIL', 'audio tap rebuilt after the audio service died', 'found "restored" in the log' if ok else 'no "restored" line in the log')
            before = len(read_selftests(a))
            a.shell('am', 'start', '-n', ACTIVITY, '--es', 'selftest', 'tone')
            res2 = wait_selftest(a, before + 1, args.wait)
            t2 = (res2 or {}).get('tone') or {}
            record('PASS' if t2.get('heard') and t2.get('via') == 'native' else 'FAIL', 'tone heard again after the rebuild',
                   '%s frames' % t2.get('frames') if res2 else 'no result')

    # ---- save the log lines ---------------------------------------------------------------------------
    rc, log, e = a.cmd('logcat', '-d', '-s', 'SpectViz:V', 'SpectMedia:V', 'SpectTest:V', 'AndroidRuntime:E')
    (out_dir / 'log.txt').write_text(log, encoding='utf-8')
    if res:
        (out_dir / 'selftest.json').write_text(json.dumps(res, indent=2), encoding='utf-8')
    if srv:
        srv.shutdown()
    return finish(out_dir)


def finish(out_dir):
    n = {s: sum(1 for r in results if r[0] == s) for s in ('PASS', 'WARN', 'SKIP', 'FAIL')}
    print('\n==== %d passed, %d warnings, %d skipped, %d FAILED ====' % (n['PASS'], n['WARN'], n['SKIP'], n['FAIL']))
    print('Saved: %s' % out_dir)
    lines = ['%s  %s  %s' % r for r in results]
    try:
        (out_dir / 'summary.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    except OSError:
        pass
    return 1 if n['FAIL'] else 0


if __name__ == '__main__':
    sys.exit(main())
