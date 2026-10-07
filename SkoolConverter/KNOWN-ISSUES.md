# SkoolConverter known issues

Z80 constructs that the converter translates incorrectly or not at all. Each of these was found and
fixed by hand in `src/main-code.a99`; the converter itself still produces them in `temp-src/`.
Already fixed in the converter: `JP M`/`RET M`/`CALL M` (now `jgt`+`jeq`) and carry tests directly
after `SUB`/`SBC` (now inverted).

## Arithmetic

- **`NEG` → `neg a`** negates the whole of R4, so A is off by one when F (R4's low byte) is non-zero.
  Clear F first (`andi a,>ff00`).
- **`SBC A,A` → `sb a,a`** is always 0. On the Z80 it gives 0 or $FF from the carry. The common
  "negate HL/DE/BC" idiom (`XOR A / SUB L / LD L,A / SBC A,A / SUB H / LD H,A`) should become
  `neg hl` (plus `ori ..,>8000` for the sign-magnitude variant).
- **`ADC`/`SBC` lose the carry-in**: they become plain `a`/`ab`/`s`/`sb`.
- **`AND r` / `XOR r` with a register operand** are left as comments. AND needs `inv` + `szcb`;
  XOR needs the operand in a register.
- **`OR A` to clear carry** (`socb a,a`) doesn't change the TI carry.

## Shifts and rotates

- **`RLA`/`RRA`/`RL r`/`RR r`** (through carry) become word shifts of R4 or are commented out; the
  carry-in is lost and `sla a,1` shifts F's top bit into A.
- **Shifts of H, D or B alone** (`SRL H`, `SLA D`, `SRA B`...) shift the whole register pair,
  corrupting L, E or C. `RR H` becomes `sra h,1`, which copies bit 15 instead of rotating the carry in.
- **`RLC`/`RRC`/`RLCA`** (8-bit circular rotates) become plain shifts and lose the wrapped bit.
- Many pairs are only correct by accident of the register mapping (`SRL H`/`RR L` = `srl h,1`); a
  peephole pass could recognise them and emit one word shift.

## Flags

- **Flag tests after `PUSH`, `POP`, `CALL` or `RET`**: the stack macros' `mov` sets the TI status and
  `dect` changes the carry, while the Z80 preserves flags. Routines that return a result in carry need
  `.retc`/`.retnc`; sign/zero tests after a call or push need a re-test.
- **Carry tests after `CP` with an instruction in between** become `joc`/`jnc`, but `cb` doesn't set
  carry on the TI. Only a test directly after the `cb` becomes `jl`/`jhe`.
- **Carry tests after `SUB`/`SBC` with an instruction in between** aren't inverted.
- **`EX AF,AF'`** doesn't save the TI status register.
- **`BIT n,r` → `czc`** sets only EQ, so a following `JP P`/`JP M` tests stale status.
- **`P` maps to `he`** in `getTMS9900Equivalent`; positive needs `jgt` or `jeq`. Harmless for now
  (only reachable from `JR`, which can't test P).

## Memory layout

- **Page-aligned tables** reached by loading a page number (`LD H,$02`, `ADD A,$08`...) point into
  console ROM on the TI. They need indexed addressing, and the tables need copying (the dependency
  scan can't see them).
- **Self-modifying code**: patch offsets are Z80 operand offsets (`+1`/`+2`), not TMS9900 ones; low-byte
  address patches assume page-aligned tables; opcode patches (`RLC`/`RRC`, `INC L`/`DEC L`, `OR`/`XOR`)
  write Z80 opcodes into TI code.
- **Little-endian reads**: byte-wise loads of words from tables assume little-endian; `data` is
  big-endian on the TI.
- **`data` after an odd number of bytes** is word-aligned by the assembler, changing record layouts.
- **`INC L`/`DEC L` on buffer pointers** assume the buffer doesn't cross a 256-byte page.
- **`DEFW` snapshot values in variables** are turned into labels, creating false references.
