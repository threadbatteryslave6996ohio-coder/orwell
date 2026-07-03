#!/usr/bin/gjs

const {Gio, GLib} = imports.gi;
const System = imports.system;

const [outputPath, durationValue, frameRateValue, drawCursorValue] = ARGV;
const durationSeconds = Number.parseInt(durationValue, 10);
const frameRate = Number.parseInt(frameRateValue, 10);
const drawCursor = drawCursorValue !== 'false';

if (!outputPath || !Number.isInteger(durationSeconds) || durationSeconds < 1 ||
    !Number.isInteger(frameRate) || frameRate < 1) {
    printerr('Usage: gnome_wayland_recorder.js OUTPUT DURATION_SECONDS FRAME_RATE [DRAW_CURSOR]');
    System.exit(2);
}

const proxy = Gio.DBusProxy.new_for_bus_sync(
    Gio.BusType.SESSION,
    Gio.DBusProxyFlags.NONE,
    null,
    'org.gnome.Shell.Screencast',
    '/org/gnome/Shell/Screencast',
    'org.gnome.Shell.Screencast',
    null
);

const supported = proxy.get_cached_property('ScreencastSupported');
if (supported && !supported.deep_unpack()) {
    printerr('GNOME Shell reports that screencasting is unavailable');
    System.exit(1);
}

const options = {
    'draw-cursor': new GLib.Variant('b', drawCursor),
    'framerate': new GLib.Variant('i', frameRate),
};
const startResult = proxy.call_sync(
    'Screencast',
    new GLib.Variant('(sa{sv})', [outputPath, options]),
    Gio.DBusCallFlags.NONE,
    -1,
    null
).deep_unpack();

if (!startResult[0]) {
    printerr('GNOME Shell refused to start the screencast');
    System.exit(1);
}

print(`GNOME Wayland screencast started: ${startResult[1]}`);

const loop = new GLib.MainLoop(null, false);
let stopped = false;
let exitCode = 0;

function stopRecording() {
    if (stopped)
        return GLib.SOURCE_REMOVE;

    stopped = true;
    try {
        const stopResult = proxy.call_sync(
            'StopScreencast',
            new GLib.Variant('()', []),
            Gio.DBusCallFlags.NONE,
            -1,
            null
        ).deep_unpack();
        if (!stopResult[0]) {
            printerr('GNOME Shell refused to stop the screencast cleanly');
            exitCode = 1;
        }
    } catch (error) {
        printerr(`Failed to stop GNOME Wayland screencast: ${error.message}`);
        exitCode = 1;
    }
    loop.quit();
    return GLib.SOURCE_REMOVE;
}

GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, durationSeconds, stopRecording);
GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, 2, stopRecording);
GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, 15, stopRecording);
loop.run();
System.exit(exitCode);
