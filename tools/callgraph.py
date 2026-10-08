# Call graph of the MSX game (elite.skool): routines, sizes and references.
# Run from the repo root: python tools/callgraph.py [options]
import re
import sys
from collections import defaultdict

SKOOL = '../elite-msx-skoolkit/src/elite.skool'
END = 0xF81C
RAM_START = 0xD600  # buffers and variables from here on
NUMERIC_TABLES = {0x0400, 0x0800}  # antilog_table and log_table hold numbers, not addresses


class Entry:
    def __init__(self, addr, kind):
        self.addr = addr
        self.kind = kind  # c, b, t, w, s, u
        self.label = None
        self.title = ''
        self.size = 0
        self.refs = set()   # addresses referenced by instructions (calls, jumps, LD rr,nn...)
        self.wrefs = set()  # DEFW values
        self.calls = set()  # addresses called or jumped to
        self.falls_through = False
        self.instructions = 0


def parse():
    entries = []
    entry_points = set()
    label = None
    title = None
    comment = []
    cur = None
    last_op = None
    for line in open(SKOOL, encoding='utf-8'):
        line = line.rstrip('\r\n')
        if line.startswith('@label='):
            label = line[7:]
            continue
        if line.startswith(';'):
            comment.append(line[1:].strip())
            continue
        if not line.strip():
            if cur is None or comment:
                pass
            continue
        m = re.match(r'^([cbtwsu ])\*?\$([0-9A-F]{4}) ?(.*)$', line) or re.match(r'^([*])\$([0-9A-F]{4}) ?(.*)$', line)
        if not m:
            comment = []
            continue
        kind, addr, rest = m.group(1), int(m.group(2), 16), m.group(3)
        if '*' in line[:2]:
            entry_points.add(addr)
        if kind in 'cbtwsu':
            if cur is not None:
                cur.falls_through = cur.kind == 'c' and last_op not in ('ret', 'jp', 'jr', 'reti', 'retn', 'halt_loop')
            cur = Entry(addr, kind)
            cur.label = label
            cur.title = comment[0] if comment else ''
            entries.append(cur)
            last_op = None
        label = None
        comment = []
        instr = rest.split(';')[0].strip()
        if not instr:
            continue
        op = instr.split()[0].lower()
        operands = instr[len(op):].strip()
        if cur.kind == 'c' and not op.startswith('def'):
            cur.instructions += 1
            # unconditional jumps and returns end the flow
            if op in ('ret', 'reti', 'retn') and operands == '':
                last_op = 'ret'
            elif op in ('jp', 'jr') and ',' not in operands:
                last_op = op
            else:
                last_op = op + '_cond' if op in ('ret', 'jp', 'jr') else op
        if op.startswith('def'):
            # Data values are only taken as references if they point at an entry point (see build)
            if op == 'defw':
                for v in re.findall(r'\$([0-9A-F]{4})\b', operands):
                    cur.wrefs.add(int(v, 16))
            continue
        for v in re.findall(r'\$([0-9A-F]{4})\b', operands):
            a = int(v, 16)
            if op in ('call', 'jp', 'jr', 'djnz'):
                cur.refs.add(a)
                cur.calls.add(a)
            else:
                # A constant (LD HL,$2128 may be a VRAM address): like data values
                cur.wrefs.add(a)
        if op == 'rst':
            v = re.search(r'\$([0-9A-F]{2})', operands)
            if v:
                cur.refs.add(int(v.group(1), 16))
                cur.calls.add(int(v.group(1), 16))
    for e, n in zip(entries, entries[1:]):
        e.size = n.addr - e.addr
    entries[-1].size = END - entries[-1].addr
    return entries, entry_points


def build():
    entries, entry_points = parse()
    starts = [e.addr for e in entries]
    targets = set(starts) | entry_points
    by_addr = {e.addr: e for e in entries}

    import bisect

    def owner(a):
        i = bisect.bisect_right(starts, a) - 1
        return entries[i] if i >= 0 else None

    graph = defaultdict(set)
    for i, e in enumerate(entries):
        # Constants and data values count when they point at an entry point, except small values
        # (zeros, RST vectors) and the numbers in the math tables
        wrefs = set() if e.addr in NUMERIC_TABLES else {w for w in e.wrefs if w in targets and w >= 0x100}
        for a in e.refs | wrefs:
            o = owner(a)
            if o is not None and o is not e:
                graph[e.addr].add(o.addr)
        if e.falls_through and i + 1 < len(entries):
            graph[e.addr].add(entries[i + 1].addr)
    return entries, by_addr, graph, owner


def name(e):
    return e.label or '$%04X' % e.addr


def reach(graph, by_addr, roots, cuts=(), code_only=True):
    seen = set()
    stack = list(roots)
    while stack:
        a = stack.pop()
        if a in seen:
            continue
        seen.add(a)
        for b in graph[a]:
            if b in cuts or b in seen:
                continue
            if b >= RAM_START:
                continue
            stack.append(b)
    return seen




# Entry points of the parts of the game that aren't run every frame in flight: the docked screens
# (reached through key_table) and one-off events. The graph is cut at these.
CLUSTERS = {
    'startup': [0x6F9F],
    'title screen': [0x1C5C],
    'death': [0x1703],
    'launch': [0x1837],
    'dock': [0x1873],
    'hyperspace': [0x894D, 0x8926],
    'escape capsule': [0x19FC],
    'freeze game': [0x40DE],
    'time, score and credits (F5)': [0x1A8A],
    'market (buy, sell, prices)': [0x5B99, 0x5BBD, 0x5CEE],
    'equip ship': [0x5DBB],
    'inventory': [0x5C8E],
    'commander status': [0x6276],
    'data on system': [0x6164],
    'charts (galactic, local, find, distance)': [0x4D0B, 0x4F64, 0x55A3, 0x52CE, 0x527F],
    'load/save commander': [0xA30A],
    # In flight, but only when a key is pressed
    'switch view (F1-F4)': [0x64A1, 0x64B5, 0x64C9, 0x64DD],
    'select hyperspace target (H)': [0x88AC],
}
FLIGHT_ROOTS = [0x010F, 0x13FD, 0x401C, 0x7202]


def converted_addresses(path='src/main-code.a99'):
    found = set()
    for line in open(path, encoding='utf-8', errors='replace'):
        m = re.match(r'^\w+:\s+; >([0-9a-f]{4})', line)
        if m:
            found.add(int(m.group(1), 16))
    return found


def partition():
    entries, by_addr, graph, owner = build()
    clusters = {c: {owner(a).addr for a in roots} for c, roots in CLUSTERS.items()}
    all_roots = set().union(*clusters.values())
    hot = reach(graph, by_addr, {owner(a).addr for a in FLIGHT_ROOTS}, cuts=all_roots)
    sets = {}
    for c, roots in clusters.items():
        sets[c] = reach(graph, by_addr, roots, cuts=all_roots - roots) - hot
    count = defaultdict(int)
    for s in sets.values():
        for a in s:
            count[a] += 1
    shared = {a for a, n in count.items() if n > 1}
    return entries, by_addr, graph, hot, sets, shared


def sizes(by_addr, addrs):
    out = defaultdict(int)
    for a in addrs:
        e = by_addr[a]
        if a >= RAM_START:
            continue
        out['code' if e.kind == 'c' else 'text' if e.kind == 't' else 'data'] += e.size
    return out


# Roots of the drawing engine (with the math, printing and sound it uses), which reads the
# banked data (blueprints, font) and is the hot path, as opposed to the flight game logic
DRAWING_ROOTS = [0x422E, 0x43C2, 0x2DC8, 0x872E, 0x8205, 0x8380, 0x3846, 0x388F, 0x34A8, 0x85FB, 0x7645]


def report():
    entries, by_addr, graph, hot, sets, shared = partition()
    _, _, _, owner = build()
    all_roots = set().union(*[{owner(a).addr for a in r} for r in CLUSTERS.values()])
    draw = reach(graph, by_addr, DRAWING_ROOTS, cuts=all_roots) & hot
    conv = converted_addresses()

    def row(n, addrs):
        s = sizes(by_addr, addrs)
        cv = sum(by_addr[a].size for a in addrs if by_addr[a].kind == 'c' and a in conv and a < RAM_START)
        return '%-42s %6d %6d %6d %10d' % (n, s['code'], s['data'], s['text'], cv)

    print('%-42s %6s %6s %6s %10s' % ('Z80 bytes', 'code', 'data', 'text', 'converted'))
    print(row('flight: drawing engine', draw))
    print(row('flight: game logic', hot - draw))
    print(row('shared by the clusters below', shared))
    for c, s in sets.items():
        print(row(c, s - shared))
    reached = hot | shared | set().union(*sets.values())
    print(row('not reached', [e.addr for e in entries if e.addr not in reached and e.addr < RAM_START]))


if __name__ == '__main__':
    report()
