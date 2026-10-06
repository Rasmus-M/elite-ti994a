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
2. `java -jar tools/ea5tocart.jar bin\elite "elite"` — wrap the EA5 images into an 8K-banked cartridge `bin/elite8.bin`.
3. Package `elite8.bin` + `layout.xml` into `elite.rpk` (MAME cartridge, PCB type `paged378`).

There are no tests; verification is running the cartridge in an emulator.

## Two-stage conversion workflow

1. **SkoolConverter** (`SkoolConverter/`, plain Java IntelliJ module, no build tool) reads
   `../elite-msx-skoolkit/src/elite.skool` and writes `temp-src/main-code.a99` — a mechanical translation of the
   whole game (`$0100`–`$F81C`, configured in `Main.java`). Run `Main` with the repo root as working directory
   (paths are relative). It only ever writes into `temp-src/`.
2. Routines are then copied by hand from `temp-src/` into `src/` (currently `src/main-code.a99` holds just the
   title screen, `src/ship-blueprints.a99` the blueprint data — not yet `copy`'d into the build) and fixed up
   manually. `src/` is hand-edited; never overwrite it with regenerated converter output.
   `temp-src/main-code.a99` contains ~700 `TODO`/commented-out lines the converter couldn't handle.

Each converted line keeps the original Z80 instruction and comment in trailing columns
(`movb a,@docked   ; LD ($E90A),A   ; comment`), so keep that layout when editing converted code.

## Z80 → TMS9900 conventions (src/equates.a99, src/macros.a99)

- **Fixed register mapping** in workspace `wrksp` = `>8300`: `a`/`af`=R4, `bc`/`b`=R5, `de`/`d`=R6, `hl`/`h`=R7,
  `ix`=R8, `iy`=R9, `sp`=R10, shadow set `af_`..`hl_`=R12–R15. R0–R2 are scratch, R3 (`one`) holds `>0100`.
  8-bit Z80 registers live in the **high byte** of the TMS9900 register; the low-byte registers (`c`, `e`, `l`,
  `f`, `ixl`, `iyl`, `c_`...) are addressed as memory (`wrksp+n`), so they're written `@c`, `@e`, `@l`.
- **Simulated stack** at `pad_stack` in scratchpad, pointed to by `sp`, growing *up*. Macros (invoked with a
  leading dot) `.call`, `.ret`, `.push`, `.pop`, `.proc`/`.endproc`, plus `.ldir`, `.ldi`, `.lddr`, `.exx`,
  `.ex_de_hl`, `.daa`, `.rst` etc. emulate multi-instruction Z80 ops. `.ldir` calls a copy routine placed in
  scratchpad (`ldir_pad`); `copy_to_pad` in `ti-routines.a99` installs scratchpad code at startup.
- Byte constants: `one`, `@b255`, `@b254`, and `@bytes+>NN` (a 0–255 table in `ti-tables.a99`) since TMS9900
  byte ops need memory/register sources. `@bit0`..`@bit7` are bit masks used for `bit`/`set`/`res`.
- Known semantic gaps to watch for: Z80 vs TMS9900 flag behaviour (carry after `cb`, the converter maps `jr c`
  → `jl`/`jhe` only directly after `cb`), shifts/rotates, little- vs big-endian words (`DEFW` data and
  `ld rr,(nn)` loads), MSX I/O ports (`OUT ($98/$99)` → `vdpwd`/`vdpwa`; other ports, BIOS calls, PSG sound
  and keyboard need TI replacements).
- Local labels use xas99 `!` / `-!` / `+!` syntax.

## Source layout

`src/elite.a99` is the top-level file: macros + equates, then `aorg >2000` (low 8K RAM: `lower-memory.a99` —
entry point, `ti-routines.a99` TI-specific hardware routines prefixed `ti_`, `ti-tables.a99`, buffers) and
`aorg >a000` (high 24K RAM: `upper-memory.a99` → `main-code.a99`). VDP table addresses are in `equates.a99`
(screen 2 layout matching the MSX).
