# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A port of the MSX 1 version of *Elite* (Firebird, 1987) to the TI-99/4A with 32K RAM expansion. Both machines
use the TMS9918A VDP; the CPUs differ (Z80 → TMS9900). The source of truth for the original game is the
commented SkoolKit disassembly in the sister repo `../elite-msx-skoolkit` (`src/elite.skool`; see its own
CLAUDE.md for addresses, naming and the MSX memory map). The `#R$xxxx` references and `; >xxxx` address
comments throughout the `.a99` files point at MSX addresses in that disassembly.

The whole game is converted and playable (see the README's status). Space was the main constraint: TMS9900 code
is much larger than Z80 and there is half the RAM, so data and most of the code are in 8K cartridge ROM banks paged
into `>6000`, while the code that everything shares runs in 32K RAM. What remains is speed: the game runs at only a
few frames a second in flight.

## Build

`make.bat` (Windows, run from the repo root). Requires `xas99.py` (xdt99) on PATH and Java:

1. `xas99.py -i -q -R -L elite.lst -E symbols.txt src/elite.a99 -o bin/elite` — assemble to EA5 program
   image(s) (`bin/elite`, `bin/elitf`, ... when larger than one file).
2. `xas99.py src/math-tables-bin.a99 -b ...` and `xas99.py src/code-bank-N.a99 -B ...` — assemble the code banks 7-14
   (`bin/bankN.bin`), which include the math tables (`bin/math-tables.bin`) with `bcopy`.
3. `xas99.py src/rom-banks/rom-bank-N.a99 -B ...` — assemble the data banks 4-6 (`bin/bankN.bin`).
4. `java -jar tools/ea5tocart.jar bin\elite "ELITE"` — pack the program (lower and upper RAM only) with a loader
   into a 32K cartridge image (banks 0-3), then append banks 4-14 and 1 empty bank: a 128K image (`elite8.bin`).
5. Package `elite8.bin` + `layout.xml` into `elite.rpk` (MAME cartridge, PCB type `paged378`).

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
- **Simulated stack** at `ram_stack` (256 bytes in low RAM: the system description recurses deeply), pointed to by
  `sp`, growing *up*. Macros (invoked with a
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
  changes R0 and R1 and passes back only the carry. `tools/callgraph.py` shows which
  routines call which, for deciding what can move to a bank.
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
  MSX address. Words are stored **little-endian** by default, as the Z80 code reads and writes them byte by byte: such a word
  variable is accessed as a byte pair (`movb @x+1,hl` / `movb @x,@l`), not with `mov`. But many have been made
  big-endian `data` words, accessed with `mov` (shorter and faster): check a variable's definition before accessing it.
  One at an odd MSX address can't be a word in place, so it is defined in `word_variables` (in `main-code.a99`, after
  `rotate_vertex`) and its two bytes in the image are left unused: the line ends (`line_end_1_x`...), `circle_centre_x/_y`,
  `clip_reference_x/_y`, the stardust logs, `sun_distance`, `planet_distance` and others. Only variables that nothing
  reaches by address (a block copy, an index register, another label + offset, the commander file's field list) can be
  converted; `tools/words.py var[,var...]` does it (the pairs become `mov`, single-byte accesses swap `@x` and `@x+1`,
  and it reports what it can't handle - then check that the other variables' addresses only shift by the same even
  amount). The first exceptions were the
  blueprint pointers `bp_vertices`/`bp_edges`/`bp_faces`/`bp_name`, which are big-endian because the blueprint
  headers use `data`. Also big-endian `data` words, for speed in
  `rotate_vertex`, are `rotated_vector`/`_y`/`_z` (the code that copies blueprint vertices and normals into it
  swaps the bytes of each word) and the logs `nose_vector_logs` (used only by `move_along_axis` now; `side_vector_logs`
  and `roof_vector_logs` are unused), and the per-edge and per-vertex
  variables `clip_point1_x/_y`, `clip_point2_x/_y`, `view_x`, `view_z2`, `view_z`, `vertex_cache_slot`,
  `face_visible_workspace_1/_2`, `screen_radius` and `on_screen_left/right/top/bottom`. So are the object
workspace's position and orientation words (`object_position_x/_y/_z`, `side_vector*`, `nose_vector*`,
`roof_vector*`, $F7CE-$F7E5) - and so the same words in the object table's records, which are copies of
it, and the data copied into it (`station_launch_orientation`, `title_ship_start`,
`title_letters_orientation`); their high-byte labels (`object_z_sign`, `position_x_hi`...) are `equ`s of the
words. A single-byte access to
  one of these goes to the high byte at the label and the low byte at the label + 1. Constant tables read only by TI
  code (`log_table`, `antilog_table`) are big-endian `data`.
- **No MSX page assumptions**: Z80 code that builds addresses from page numbers (`LD H,$E6`, `LD H,$02` for the
  $0200 table, `ADD A,$08` for $0800) or steps pointers with `INC L` relies on MSX page-aligned tables. On the TI,
  compute the real address (indexed addressing, e.g. `@bit_length_table(r1)`, or base + offset) and use `inc hl`.
- **Variables cleared by the MSX startup code** ($D600-$EFAA) start at 0, except the values it sets afterwards
  (see `temp-src/startup-code.a99`); values above that keep the snapshot.
- **Scratchpad** (`equates.a99`): `pad_stack` (no longer used), then the routines `copy_to_pad` installs at startup:
  `copy_space_view_row_pad` (`>26`), `clear_space_view_rows_pad` (`>20`) and `line_loops_pad` (`>48`), ending at
  `pad_max` (`>83C0`), then `pad_ws` (`>83C0`-`>83DF`, overlapping GPLWS - fine while interrupts and console routines
  are unused): the one workspace the music, sound effects, keyboard and joystick routines share, as they never call each
  other and keep nothing in registers between calls. The slot sizes
  are constants; update them if those routines change. The line loops plot with a single `socb r1,*hl`; `tunnel_rings`,
  the only thing drawn with XOR, patches that and the `sb` after it to a call of `line_xor_plot` meanwhile. Code there runs without wait states.
- **Code in ROM banks**: `program.a99` holds the whole program and is assembled once per output: by `elite.a99` with
  RAM real (`aorg`) and the code banks `dorg`, and by `code-bank-N.a99` with only bank N real, so RAM and the banks
  can use each other's labels. Banks 7-14 (`code_bank_N_select`, at >6000 + 2N) hold code - 7 the docked screens, 8 the data on system screen,
  9 the charts, 10 the title screen (entered with `.select_bank` + `b`, as it never returns) and launching (key '1', `key_handler_1`), 11 the flight logic `process_frame` calls with `.far_call` (collisions, docking, the safe zone, spawning, the object handlers and steering), 12 more of it (scooping, destroying objects, explosions, the score, the lasers, recharging, the missile, ECM, energy bomb and escape capsule keys, switching views (keys 2-4 and F1-F4), the Torus jump drive (J) and the docking computer (C), freezing the game (STOP), the altitude and cabin temperature, death and the death scene), 13 hyperspace (H, G, the countdown, the jump and witchspace), the alert level, F5, the missions, and the cloaking device, ECM jammer and identify keys (Y, L, I), 14 the sound effects player and the disk routines; call into it with `.far_call`. Code in a bank may
  read RAM, the math tables and its own bank only. As a pilot, bank 7 holds the status page (`status_page`,
  `print_commander_name` and their own text and data); the routines and text other screens share (printing,
  number formatting, system names, clearing the page) are in RAM. 'N' on the title screen shows it, and 'Y' the
  commander menu (also in bank 7); then the main loop (`main_loop_wait_tick`) runs - in flight it calls `process_frame` (RAM), and when the game is over it shows the death scene and goes back to the title screen - dispatching keys
  through a TI `key_table` of `data key*256,bank select,handler` entries (only the converted handlers, called
  with `far_call_r0_r1`). Add a screen's key there when it's converted.
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
  work: it counts the frame (`vblank_frame_counter`, `vblank_subtick_counter`), counts down the ECM and hyperspace, ticks the game clock, sounds the alarm, plays a tick of the sound effects and of the music and (every second frame; the joysticks every fourth, as scanning them takes long) reads
  the keyboard (`ti_read_keyboard`: each new key press's character goes to `pending_debounced_keypress_byte_status`). The
  MSX runs at 50 Hz, and frames are missed when it isn't called for more than a frame, so on a new frame it reads the
  TMS9901's timer (`ti_timer_frames`; `ti_setup_environment` starts it) to find how many MSX frames have passed, and does
  the work that many times. It changes no registers, but must be called at least
  once a frame (it's in `clear_space_view`, `copy_space_view`, `draw_object`'s face loop and after each edge, and for
  the docked screens in `print_char`, `generate_system` and `clear_page`, or a sound effect plays on until a screen is drawn) and
  never between the two bytes of a VDP address.
- **The space view** is drawn in a buffer in low RAM (`space_view_buffer`, 4K) and copied to the pattern table
  (`copy_space_view`). Copying and clearing all of it takes long, so each of the 16 character rows is divided into 11
  cells of two characters, and has a word in `view_cells` with a bit for each cell drawn in since the buffer was cleared
  (bit 15 the first cell, bit 5 the last; `view_prev_cells`: what was copied last, so is on the screen):
  `clear_space_view` clears only those cells and `copy_space_view` copies only those and the previous ones, a run of
  cells at a time. So everything that writes to the buffer must mark what it covers: `view_mark_line` (lines, the
  rectangle they're in; `draw_line_inside`, the fast path for lines that need no clipping, does the same in line), and
  inline in `plot_single_pixel`, `move_and_plot_stardust`, `draw_view_message` and `fill_disc_row_span` (the rows of the
  sun's disc, a native routine in low RAM where the MSX has #R$3F68). The tables `view_cell_bit`, `view_cells_from` and
  `view_cells_to_inv` (`ti-routines.a99`) give the bit of a cell, the bits from a cell on and the bits after a cell: a
  range is `mov @view_cells_from(first*2),r` + `szc @view_cells_to_inv(last*2),r`. `view_mark_all` marks everything
  (`enter_flight_mode`, as then neither the buffer nor the screen is known). `clear_space_view` mustn't change BC: its
  callers count with it.
- **Rotating vertices** (`rotate_vertex`, and `face_visible` with it) is native code that multiplies with `MPY` where
  the MSX adds logarithms: `cache_orientation` caches the object's orientation in `rotation_matrix` as nine unsigned
  words (the component + 2.0), not as the logarithms at `side_vector_logs` and `roof_vector_logs`, which are no longer
  used. The results differ from the MSX's by a few units (they are slightly more accurate).
  Projecting (`project_point`, `project_coordinate`) is native too: 128 * v / depth with `DIV`, exact where the MSX
  subtracts logarithms, with the depth itself in `projection_depth` (set by `project_point` and `ship_screen_size`)
  rather than its logarithm; `log_depth` is now only `ship_screen_size`'s own, for the planet's and the sun's radius.
- **Lines** (`clip_and_draw_line`) are native on the TI: a line with both points inside the view is drawn by
  `draw_line_inside`; one with both points beyond the same edge is skipped; and any other is clipped against each edge
  in turn (`clip_line_low`, `clip_line_high`: the crossing point with `MPY` and `DIV`) where the MSX bisects it. The MSX's
  bisection code ($3B26-$3C47) is not in `src`. `setup_bresenham_line` and `view_mark_line` remain for #R$2DC8.
- **The program image** (low and high RAM) is packed into the 32K of cartridge banks 0-3 with a loader: `ea5tocart`
  prints "Cartridge full" (and leaves a broken cartridge) if it doesn't fit, so check `make.bat`'s output for that. The
  space view buffer is last in low RAM and defined with `equ`, so it isn't in the image: nothing may be assembled after
  it. High RAM has about 1220 bytes free; low RAM has about 440 bytes free.
- **Disk operations** (the routines in ROM bank 14, `rom-bank-14.a99`; their entry point `disk_entry`, which selects the bank, and their data in `ti-disk.a99`, low RAM): the commander menu (bank 7) loads and saves commanders on disk where the
  MSX uses tape: the file `DSK1.<name>`, a program file of 139 bytes (the record at #R$D606: the name and the commander
  file). Set `disk_opcode`, `disk_name` and `disk_name_length`, `blwp @disk_vector`, and check `disk_error`. The disk DSR
  needs the scratchpad as the console left it and its buffers at the top of the VDP RAM (from >37D8, starting in the
  last 40 bytes of the color table), so `ti_save_console_state` saves both as the first thing the program does (before
  `lwpi wrksp`), `ti_setup_environment` doesn't clear the VDP RAM from >37D8, and `ti_disk_operation` swaps them in
  around the DSR call (`ti_dsrlnk`), clears the DSR's buffers under the name table and the sprite attribute table first,
  and sets those two tables up again afterwards (so the screen is blank meanwhile). It all runs in `disk_ws`, a
  workspace in low RAM, as the main workspace is in the scratchpad. The PAB and the file's buffer are in VDP RAM at
  >1900 and >1940.
- **Sound effects**: a native SN76489 player in ROM bank 14 (`rom-bank-14.a99`) rather than a conversion of the MSX's
  AY-3-8910 engine (#R$7468, #R$7645): the same effects (0-13), handlers and three channels, with its state in the MSX's
  sound variables (#R$E894). Play one with `.sfx N` (a `blwp @sound_effect_vector` and the number): it runs in its own
  workspace (`pad_ws`), through entry points in `music-player.a99` that select bank 14 and then `current_bank` again,
  so it changes no registers and not the status, and works from RAM and from any bank.
- **Music**: a native SN76489 player (`music-player.a99`, low RAM) with its own workspace, called with `blwp`
  (`music_start_vector` with HL = the tune, `music_stop_vector`, `music_tick_vector`). The title music data
  (`title-music.a99`, bank 6) is generated by `python tools/convert-title-music.py` from the disassembly. Channel 3, the bass, is too low for
  an SN76489 tone, so it plays as periodic noise clocked by tone 3 (`bass_tone_periods`, the period / 15).

## Source layout

`src/elite.a99` is the top-level file: macros + equates, then `program.a99`: the data banks' labels, `aorg >2000` (low 8K RAM: `lower-memory.a99` —
entry point, `ti-routines.a99` TI-specific hardware routines prefixed `ti_`, `ti-tables.a99`, buffers) and
`aorg >a000` (high 24K RAM: `upper-memory.a99` → `main-code.a99`) and the code bank (`rom-bank-7.a99`). VDP table addresses are in `equates.a99`
(screen 2, as on the MSX but for three tables: the disk DSR keeps its buffers at the top of the VDP RAM, from >37D8, so
the name table (>3800) and the sprite attribute table (>3B00), which are easy to set up again, are there, and the sprite
patterns are at >1800. Use `sprite_attr_table`, `@bytes+sprite_attr_write_hi` and `@bytes+sprite_pattern_write_hi` where
the MSX code has $1B00, $5B and $78; the MSX addresses in the comments are the MSX's. Sprite patterns 0-31 are used, and
the font for the view messages is at >1B80->1E7F, so >1900->1B7F and >1E80->1FFF are free).
