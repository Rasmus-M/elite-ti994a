# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A port of the MSX 1 version of *Elite* (Firebird, 1987) to the TI-99/4A with 32K RAM expansion. Both machines
use the TMS9918A VDP; the CPUs differ (Z80 → TMS9900). The source of truth for the original game is the
commented SkoolKit disassembly in the sister repo `../elite-msx-skoolkit` (`src/elite.skool`; see its own
CLAUDE.md for addresses, naming and the MSX memory map). The `#R$xxxx` references and `; >xxxx` address
comments throughout the `.a99` files point at MSX addresses in that disassembly.

Current goal (per README): get the title screen (spinning ELITE letters + wireframe ships) running. Space is
the main constraint: TMS9900 code is much larger than Z80 and there is half the RAM, so the plan is to page
data from extra 8K cartridge ROM banks into `>6000` while main code runs in 32K RAM. Speed is deferred.

## Build

`make.bat` (Windows, run from the repo root). Requires `xas99.py` (xdt99) on PATH and Java:

1. `xas99.py -i -q -R -L elite.lst -E symbols.txt src/elite.a99 -o bin/elite` — assemble to EA5 program
   image(s) (`bin/elite`, `bin/elitf`, ... when larger than one file).
2. `xas99.py src/rom-banks/rom-bank-N.a99 -B ...` — assemble the data banks 4-6 (`bin/bankN.bin`).
3. `java -jar tools/ea5tocart.jar bin\elite "elite"` — pack the program (lower and upper RAM only) with a loader
   into a 32K cartridge image (banks 0-3), then append banks 4-6 and an empty bank 7 (`elite8.bin`).
4. Package `elite8.bin` + `layout.xml` into `elite.rpk` (MAME cartridge, PCB type `paged378`).

There are no tests; verification is running the cartridge in an emulator.

## Two-stage conversion workflow

1. **SkoolConverter** (`SkoolConverter/`, plain Java IntelliJ module, no build tool) reads
   `../elite-msx-skoolkit/src/elite.skool` and writes `temp-src/main-code.a99` — a mechanical translation of the
   whole game (`$0100`–`$F81C`, configured in `Main.java`). Run `Main` with the repo root as working directory
   (paths are relative). It only ever writes into `temp-src/`.
2. Routines are then copied by hand from `temp-src/` into `src/` (currently `src/main-code.a99` holds the
   title screen and everything it depends on, except sound) and fixed up manually. Copying by label misses data
   reached by address arithmetic (page-number table lookups, `label+offset`) - check for those. `src/` is hand-edited; never overwrite it with regenerated converter output.
   `temp-src/main-code.a99` contains ~700 `TODO`/commented-out lines the converter couldn't handle,
   and more that it gets silently wrong - see `SkoolConverter/KNOWN-ISSUES.md` before copying code over.

Each converted line keeps the original Z80 instruction and comment in trailing columns
(`movb a,@docked   ; LD ($E90A),A   ; comment`), so keep that layout when editing converted code.

## Z80 → TMS9900 conventions (src/equates.a99, src/macros.a99)

- **Fixed register mapping** in workspace `wrksp` = `>8300`: `a`/`af`=R4, `bc`/`b`=R5, `de`/`d`=R6, `hl`/`h`=R7,
  `ix`=R8, `iy`=R9, `sp`=R10, shadow set `af_`..`hl_`=R12–R15. R0–R2 are scratch, R3 (`one`) holds `>0100`.
  8-bit Z80 registers live in the **high byte** of the TMS9900 register; the low-byte registers (`c`, `e`, `l`,
  `f`, `ixl`, `iyl`, `c_`...) are addressed as memory (`wrksp+n`), so they're written `@c`, `@e`, `@l`.
- **Simulated stack** at `pad_stack` in scratchpad, pointed to by `sp`, growing *up*. Macros (invoked with a
  leading dot) `.call`, `.ret`, `.push`, `.pop`, `.proc`/`.endproc`, plus `.ldir`, `.ldi`, `.lddr`, `.exx`,
  `.ex_de_hl`, `.daa`, `.rst` etc. emulate multi-instruction Z80 ops. `.ldir` calls `ti_ldir` (in RAM).
- **Calling convention**: routines are called with `bl`. Leaf routines return with `rt`; routines that call
  others start with `.proc` and return with `.endproc`. A routine also reached by a jump gets a `<name>_jmp`
  entry (after `.proc`, or a `.pop r11` entry for leaf routines). `.call`/`.ret` (return address pushed on the
  stack) remain only for routines not converted yet. `.endproc`/`.pop` use `dect`, which changes the carry, so a
  routine that returns a result in carry must use `.retc`/`.retnc`; and the stack macros' `mov` changes the
  status, so re-test flags after a call or push/pop.
- **Calls into ROM banks** use `.far_call <bank select address>,<routine>` (`far_call` in `ti-routines.a99`, in RAM):
  it selects the routine's bank, calls it with `bl`, then selects the caller's bank (`current_bank`) again. It
  changes R0 and R1 and passes back only the carry. No code is in the banks yet; `tools/callgraph.py` shows which
  parts of the game could go there.
- Byte constants: `one`, `@b255`, `@b254`, and `@bytes+>NN` (a 0–255 table in `ti-tables.a99`) since TMS9900
  byte ops need memory/register sources. `@bit0`..`@bit7` are bit masks used for `bit`/`set`/`res`.
- Known semantic gaps to watch for: Z80 vs TMS9900 flag behaviour (carry after `cb`, the converter maps `jr c`
  → `jl`/`jhe` only directly after `cb`), shifts/rotates, little- vs big-endian words (`DEFW` data and
  `ld rr,(nn)` loads), MSX I/O ports (`OUT ($98/$99)` → `vdpwd`/`vdpwa`; other ports, BIOS calls, PSG sound
  and keyboard need TI replacements).
- Local labels use xas99 `!` / `-!` / `+!` syntax.

## Memory layout conventions

- **Variables ($E600-$F81B) are an exact image of the MSX variable area**, so records copied with LDIR or
  indexed through IX/IY keep their layout. `.msxalign >addr` pads so a label has the same address parity as its
  MSX address. Words are stored **little-endian**, as the Z80 code reads and writes them byte by byte: a word
  variable is accessed as a byte pair (`movb @x+1,hl` / `movb @x,@l`), never with `mov`. The exceptions are the
  blueprint pointers `bp_vertices`/`bp_edges`/`bp_faces`/`bp_name`, which are big-endian because the blueprint
  headers use `data`. Also big-endian `data` words, for speed in
  `rotate_vertex`, are `rotated_vector`/`_y`/`_z` (the code that copies blueprint vertices and normals into it
  swaps the bytes of each word) and the orientation logs `side_vector_logs`, `roof_vector_logs` and
  `nose_vector_logs` (also used by the MSX code at $869A, not converted yet), and the per-edge and per-vertex
  variables `clip_point1_x/_y`, `clip_point2_x/_y`, `view_x`, `view_z2`, `view_z`, `vertex_cache_slot`,
  `face_visible_workspace_1/_2`, `screen_radius` and `on_screen_left/right/top/bottom`. A single-byte access to
  one of these goes to the high byte at the label and the low byte at the label + 1. Constant tables read only by TI
  code (`log_table`, `antilog_table`) are big-endian `data`.
- **No MSX page assumptions**: Z80 code that builds addresses from page numbers (`LD H,$E6`, `LD H,$02` for the
  $0200 table, `ADD A,$08` for $0800) or steps pointers with `INC L` relies on MSX page-aligned tables. On the TI,
  compute the real address (indexed addressing, e.g. `@bit_length_table(r1)`, or base + offset) and use `inc hl`.
- **Variables cleared by the MSX startup code** ($D600-$EFAA) start at 0, except the values it sets afterwards
  (see `temp-src/startup-code.a99`); values above that keep the snapshot.
- **Scratchpad** (`equates.a99`): `pad_stack`, then the routines `copy_to_pad` installs at startup:
  `copy_space_view_row_pad` (`>26`), `clear_space_view_rows_pad` (`>20`) and `line_loops_pad` (`>50`), ending at
  `pad_max` (`>83F8`, overlapping GPLWS - fine while interrupts and console routines are unused). The slot sizes
  are constants; update them if those routines change. Code there runs without wait states.
- **ROM banks** (`src/rom-banks/`): every data bank starts with the math tables (`math-tables.a99`) at `>6040`,
  so they're readable whichever bank is selected. Bank 4 then holds the blueprints for ship types 1-22, bank 5
  types 23+ (`blueprints_2_first_type`), bank 6 the cockpit picture, the font and the title music. Select a bank with
  `.select_bank ..._bank_select` (`equates.a99`; it changes R0) before reading its data - never a plain `seto`, as
  the music player selects the bank in `current_bank` again after each tick; `draw_object` and `get_blueprint_data` select
  the blueprint bank themselves (`select_blueprint_bank`). RAM routines that select a bank select the caller's bank
  again before returning (`.save_bank`/`.restore_bank`), so code in a ROM bank can call them: `draw_object`,
  `get_blueprint_data` (which also copies the name to `bp_name_buffer`, so `bp_name` points at RAM), `print_char`
  and the cockpit picture routine. The exception is `get_blueprint`, whose callers read the blueprint through IY. The bank files are copied into `elite.a99` under `dorg`
  only for their labels (`main_program` is defined); banks 5 and 6 then reserve the tables' space with `bss`.
- Interrupts stay disabled (`limi 0` once at startup); the converted `DI`/`EI` are commented out. Instead,
  `ti_poll_vblank` (`music-player.a99`) reads the VDP status and, on a new frame, does the MSX VBlank handler's
  work: it counts the frame (`vblank_frame_counter`, `vblank_subtick_counter`) and plays a tick of the music. It
  ignores every sixth frame, to keep the MSX's 50 Hz timing. It changes no registers, but must be called at least
  once a frame (it's in `clear_space_view`, `copy_space_view`, `draw_object`'s face loop and after each edge) and
  never between the two bytes of a VDP address.
- **Music**: a native SN76489 player (`music-player.a99`, low RAM) with its own workspace, called with `blwp`
  (`music_start_vector` with HL = the tune, `music_stop_vector`, `music_tick_vector`). The title music data
  (`title-music.a99`, bank 6) is generated by `python tools/convert-title-music.py` from the disassembly. Channel 3, the bass, is too low for
  an SN76489 tone, so it plays as periodic noise clocked by tone 3 (`bass_tone_periods`, the period / 15).

## Source layout

`src/elite.a99` is the top-level file: macros + equates, then `aorg >2000` (low 8K RAM: `lower-memory.a99` —
entry point, `ti-routines.a99` TI-specific hardware routines prefixed `ti_`, `ti-tables.a99`, buffers) and
`aorg >a000` (high 24K RAM: `upper-memory.a99` → `main-code.a99`). VDP table addresses are in `equates.a99`
(screen 2 layout matching the MSX).
