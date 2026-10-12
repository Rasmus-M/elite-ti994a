"""Profile flight frames in MAME: where does the time go?

Runs the cartridge (elite.rpk - build it first) in MAME with a Lua script that
starts a game, launches, sets up each scene in turn and traces two
seconds of it (six of the fight) with the debugger. Then adds up the cycles by routine (from
symbols.txt and the labels in the ROM banks' sources) and prints, for each
scene, the frame time, the work in a frame, the time spent waiting for the
100 ms frame limiter (#R$1540) and the work by category. A screenshot of each
scene goes in bin/profile-<scene>.png, and the traces in bin/profile-<scene>.log.

usage: python tools/profile.py [options] [scene ...]      (from the repo root)

  scenes      planet    front view just after launch: the planet fills the view
              station   rear view: the space station at a middling distance
              close     rear view: the station close up
              empty     left view: only stardust
              fight     front view: six ships (see FIGHT) rolling in place
              (default: all of them)
  -r a,b,c    also print the calls and the time, with what they call, of
              these routines (RAM routines called with BL)
  -t N        list the N routines with the most time of their own in each scene
  -k          keep the traces of an earlier run: only analyse them again

The scenes are set up by writing to the object table with the debugger: the
ships of the fight are copies of the station's record with another ship type,
so they can't be shot or hit, and roll as the station does (it has no AI: this
measures drawing and moving them). Their positions are
written again every few frames, as is the planet's from the empty scene on
(behind the player, far away - otherwise the player flies into it and dies).

MAME is run from the directory in the environment variable MAME_DIR.
"""
import bisect, collections, glob, os, re, shutil, subprocess, sys

ROOT = os.getcwd().replace('\\', '/')
MAME_DIR = os.environ.get('MAME_DIR', 'C:/Users/rasmu/TI-99-4A/mame283')
SCENES = ['planet', 'station', 'close', 'empty', 'fight']
# The fight's ships: ship type, x, y, z (in front of the player), and which of
# the orientations in FACING it starts with
FIGHT = [
    (0x0e, -330, 190, 700, 0),      # Cobra Mk III
    (0x0f, 40, 230, 1100, 1),       # Python
    (0x12, 330, 150, 520, 2),       # Krait
    (0x19, -280, -120, 600, 3),     # Asp Mk II
    (0x18, 60, -170, 450, 1),       # Mamba
    (0x10, 380, -160, 1500, 2),     # Anaconda
]
# Orientations: the side and nose vectors' x, y and z (sign-magnitude words, 1.0 = >4000)
FACING = [
    (0x4000, 0, 0, 0, 0, 0xc000),               # towards the player
    (0x2d41, 0, 0x2d41, 0xad41, 0, 0x2d41),     # turned 45 degrees to the side
    (0x4000, 0, 0, 0, 0x2d41, 0xad41),          # towards the player, nose up
    (0x2d41, 0x2d41, 0, 0, 0, 0x4000),          # away from the player, rolled
]
FIGHT_SLOT = 10                 # their first slot in the object table

CATEGORIES = [
    ('Copy the view to the VDP', r'^(pad_vars|copy_space_view)'),
    ('Clear the view buffer', r'^(clear_space_view|star_)'),
    ('Line drawing (setup, plotting, marking)', r'^(line_\w+|draw_line_inside|setup_bresenham_line|steep_line_loop|shallow_line_loop|view_mark_line)'),
    ('Line clipping and rejecting', r'^(clip_|outcode)'),
    ('Stardust', r'^(stardust|move_and_plot_stardust|update_stardust)'),
    ('Planet circle', r'^(write_vertex_and_draw_edge|draw_circle|circle_)'),
    ('Sun / disc', r'^(draw_disc|fill_disc|draw_small)'),
    ('VBlank work: keyboard, joystick, timer, sound', r'^(ti_poll|ti_read|ti_check_key|ti_timer|sound_|music_|ti_move_chart|b14:)'),
    ('Dashboard: scanner, compass, gauges', r'^(compass|scanner|or_vram|read_vram|set_sprite|gate_dashboard|settle_|b12:distance_to_gauge|b12:scale_rem|b12:update_alt)'),
    ('3D: rotating, projecting, visibility, edges', r'^(rotate_vertex|rotation_matrix|cache_orientation|face_|clear_flags|project_|draw_ship|ship_sprite|add_object_position|draw_object|vertex_|on_screen|ship_screen_size|get_blueprint|draw_face|draw_edge|convert_to_view|view_front|view_rear|view_left|view_right|select_blueprint_bank|check_sights|work_out_roof)'),
    ('Turning the objects (roll and pitch)', r'^(b11:)?(roll_object|pitch_object|rotate_pair|multiply_by_cos|multiply_components|lookup_rotation|straighten_orientation|normalise_vector)'),
    ('Block copies (ti_ldir)', r'^ti_ldir'),
    ('Far calls / bank switching', r'^far_call'),
]
REST = 'Everything else (objects, game logic)'


def load_symbols():
    text = open('symbols.txt').read()
    return dict((k, int(v, 16)) for k, v in re.findall(r'^(\w+):\s*\n\s*equ\s+>([0-9A-Fa-f]+)', text, re.M))


def make_lookup(sym):
    """Return a function that names the routine at a PC, given the selected bank."""
    def local(k):
        return re.match(r'^_[0-9a-f]{4}', k) is not None
    bank_labels = {}
    for n in range(4, 15):
        src = open('src/rom-banks/rom-bank-%d.a99' % n, encoding='utf-8').read()
        bank_labels[n] = set(re.findall(r'^(\w+):', src, re.M))
    in_banks = set().union(*bank_labels.values())

    def table(names):
        t = sorted((sym[k], k) for k in names if k in sym and not local(k))
        return [a for a, _ in t], [k for _, k in t]
    ram = table([k for k in sym if k not in in_banks and not 0x6000 <= sym[k] < 0x8000])
    banks = dict((n, table([k for k in bank_labels[n] if 0x6000 <= sym.get(k, 0) < 0x8000])) for n in bank_labels)

    def lookup(pc, bank):
        if 0x6000 <= pc < 0x8000:
            n = (bank - 0x6000) // 2
            t = banks.get(n)
            if not t or not t[0]:
                return 'bank%d' % n
            i = bisect.bisect_right(t[0], pc) - 1
            return 'b%d:' % n + (t[1][i] if i >= 0 else 'tables')
        return ram[1][bisect.bisect_right(ram[0], pc) - 1]
    return lookup


def lua_script(sym, scenes):
    table = sym['ship_object_table']
    station = table + 40
    trace = 'trace %s/bin/profile-%%s.log,maincpu,noloop,{tracelog "%%%%X %%%%X ",totalcycles,w@%x}' % (ROOT, sym['current_bank'])
    out = ['local m = manager.machine', 'local dbg = m.debugger', 'local f = 0',
           "local T = '%s'" % trace,
           'local function w16(a, v) if v < 0 then v = v + 65536 end dbg:command(string.format("w@%x=%x", a, v)) end',
           'local function place(rec, x, y, z) w16(rec + 1, x) w16(rec + 3, y) w16(rec + 5, z) end',
           'local function key(c) m.natkeyboard:post(c) end',
           # in flight a key is put where the keyboard scan puts it, as typed keys are sometimes missed
           'local function press(c) dbg:command(string.format("b@%x=%%x", string.byte(c))) end' % sym['pending_debounced_keypress_byte_status'],
           'local function start(name) dbg:command(string.format(T, name)) end',
           'local function stop(name) dbg:command("trace off") m.video:snapshot() end',
           'sub = emu.add_machine_frame_notifier(function()', '  f = f + 1',
           # start a game and launch
           "  if f == 100 then key(' ') end", "  if f == 200 then key('2') end",
           "  if f == 1500 or f == 1800 or f == 2100 then key('N') end", "  if f == 2600 then key('1') end"]
    t = 3400
    if 'planet' in scenes:
        out += ["  if f == %d then start('planet') end" % t, "  if f == %d then stop() end" % (t + 120)]
    t = 3540
    out += ["  if f == %d or f == %d then press('2') end" % (t, t + 100)]
    if 'station' in scenes:
        out += ["  if f == 3920 then start('station') end", "  if f == 4040 then stop() end"]
    if 'close' in scenes:
        out += ["  if f >= 4100 and f < 4300 and f %% 20 == 0 then place(0x%x, 0, 0, -420) end" % station,
                "  if f == 4160 then start('close') end", "  if f == 4280 then stop() end"]
    out += ["  if f == 4320 or f == 4420 then press('3') end",
            # from here on the planet is kept behind the player, far away
            "  if f >= 4300 and f %% 20 == 0 then place(0x%x, 0, 0, -25000) end" % table]
    if 'empty' in scenes:
        out += ["  if f == 4640 then start('empty') end", "  if f == 4760 then stop() end"]
    if 'fight' in scenes:
        out += ["  if f == 4800 or f == 4900 then press('1') end",
                "  if f == 5100 then place(0x%x, 0, 0, -9000) end" % station]
        for i, (typ, x, y, z, facing) in enumerate(FIGHT):
            rec = table + 40 * (FIGHT_SLOT + i)
            cmds = ['b@%x=b@%x' % (rec, station)]
            cmds += ['w@%x=w@%x' % (rec + o, station + o) for o in range(1, 39, 2)]
            cmds += ['b@%x=b@%x' % (rec + 39, station + 39), 'b@%x=%x' % (rec + 26, typ)]
            cmds += ['b@%x=b@%x&7f' % (rec, station)]     # the station's status, but the roof vector not worked out
            cmds += ['w@%x=%x' % (rec + 7 + 2 * k, v) for k, v in enumerate(FACING[facing])]
            out += ["  if f == 5120 then %s end" % ' '.join("dbg:command('%s')" % c for c in cmds),
                    "  if f >= 5120 and f %% 20 == 0 then place(0x%x, %d, %d, %d) end" % (rec, x, y, z)]
        out += ["  if f == 5300 then start('fight') end", "  if f == 5660 then stop() end"]
    out += ['end)']
    last = 5660 if 'fight' in scenes else 4760 if 'empty' in scenes else 4280 if 'close' in scenes else 4040 if 'station' in scenes else 3520
    return '\n'.join(out) + '\n', last // 60 + 2


def run_mame(sym, scenes):
    lua, seconds = lua_script(sym, scenes)
    script = ROOT + '/bin/profile.lua'
    open(script, 'w', newline='\n').write(lua)
    snaps = MAME_DIR + '/snap/ti99_4a'
    for fn in glob.glob(snaps + '/*.png'):
        os.remove(fn)
    cmd = [MAME_DIR + '/mame.exe', 'ti99_4a', '-ioport', 'peb', '-ioport:peb:slot3', '32kmem', '-cart', ROOT + '/elite.rpk',
           '-video', 'none', '-sound', 'none', '-nothrottle', '-skip_gameinfo', '-debug', '-debugger', 'none',
           '-seconds_to_run', str(seconds), '-autoboot_script', script]
    subprocess.run(cmd, cwd=MAME_DIR, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for name, fn in zip([s for s in SCENES if s in scenes], sorted(glob.glob(snaps + '/*.png'))):
        shutil.copy(fn, 'bin/profile-%s.png' % name)


def analyse(sym, lookup, name, routines, top):
    fn = 'bin/profile-%s.log' % name
    if not os.path.exists(fn):
        print('== %s: no trace' % name)
        return
    w0, w1, pf = sym['_1540'], sym['_1548'], sym['process_frame']
    addr = dict((sym[r], r) for r in routines)
    cyc = collections.Counter(); inc = collections.Counter(); cnt = collections.Counter()
    frames = []; wait = 0; ws = None; prev = None; stack = []; previns = ''
    for line in open(fn, errors='replace'):
        p = line.split(None, 3)
        if len(p) < 4 or not p[2].endswith(':'):
            continue
        try:
            c = int(p[0], 16); b = int(p[1], 16); pc = int(p[2][:-1], 16)
        except ValueError:
            continue
        if pc == pf:
            frames.append(c)
        if frames:
            if pc == w0 and ws is None:
                ws = c
            if pc == w1 and ws is not None:
                wait += c - ws; ws = None
            if prev and ws is None and not w0 <= prev[1] < w1:
                cyc[lookup(prev[1], prev[2])] += c - prev[0]
            while stack and stack[-1][0] == pc:
                _, r, c0 = stack.pop(); inc[r] += c - c0; cnt[r] += 1
            if pc in addr and previns.startswith('bl ') and prev:
                stack.append((prev[1] + 4, addr[pc], c))
        prev = (c, pc, b); previns = p[3]
    n = len(frames) - 1
    if n < 1:
        print('== %s: no frames in the trace' % name)
        return
    ms = lambda v: v / n / 3000         # 3,000,000 cycles a second
    per = (frames[-1] - frames[0]) / n
    print('== %s: %d frames; frame %.0f ms (%.1f fps); work %.0f ms; waiting for the limiter %.0f ms'
          % (name, n, per / 3000, 3000000 / per, ms(sum(cyc.values())), ms(wait)))
    out = collections.Counter(); rest = collections.Counter()
    for k, v in cyc.items():
        for cat, rx in CATEGORIES:
            if re.match(rx, k):
                out[cat] += v
                break
        else:
            out[REST] += v; rest[k] += v
    for k, v in out.most_common():
        print('  %5.1f ms  %s' % (ms(v), k))
    print('     else:', ', '.join('%s %.1f' % (k, ms(v)) for k, v in rest.most_common(12)))
    if top:
        print('     top: ', ', '.join('%s %.1f' % (k, ms(v)) for k, v in cyc.most_common(top)))
    for r in routines:
        print('     %-22s %5.1f calls a frame, %6.0f cycles each, %5.2f ms a frame'
              % (r, cnt[r] / n, inc[r] / max(cnt[r], 1), ms(inc[r])))


def main():
    args = sys.argv[1:]
    routines = []; top = 0; keep = False; scenes = []
    while args:
        a = args.pop(0)
        if a == '-r':
            routines = args.pop(0).split(',')
        elif a == '-t':
            top = int(args.pop(0))
        elif a == '-k':
            keep = True
        elif a in SCENES:
            scenes.append(a)
        else:
            sys.exit(__doc__)
    scenes = scenes or SCENES
    sym = load_symbols()
    if not keep:
        run_mame(sym, scenes)
    lookup = make_lookup(sym)
    for name in SCENES:
        if name in scenes:
            analyse(sym, lookup, name, routines, top)


main()
