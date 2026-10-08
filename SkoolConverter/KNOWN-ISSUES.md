# SkoolConverter known issues

Z80 constructs that the converter can't translate correctly on its own. Those it does handle are listed first, so
it's clear what to expect in `temp-src/`; lines it isn't sure about are marked `TODO: check code.`

## Handled by the converter

- **Carry polarity.** The converter tracks the state of the TI carry compared with the Z80's: the same (after
  ADD, ADC, shifts, SCF), inverted (after SUB, SBC, XOR A - the TMS9900 sets carry when there's no borrow), clear
  (after AND/OR/XOR), or straight after CP (where `cb` sets no carry, but `jl`/`jhe` give it). Carry tests, ADC,
  SBC and rotates through carry use it; when it's unknown (after INC/DEC, POP, calls, labels) they're marked TODO.
- **Shifts and rotates** of 8-bit registers, `(HL)` and `(IX+d)` are emulated exactly, with the bit shifted out
  in the TI carry. A shift of a register pair's two halves (SRL H/RR L, SLA E/RL D, RR H/RR L...) becomes one
  16-bit shift.
- **ADC/SBC** take the carry-in first and produce the Z80's carry-out; `SBC A,A` gives 0 or $FF.
- **AND r / XOR r** with register operands, **NEG** (clears F first), **SCF**, **CCF**, and `LD r,0` (MOVB, which
  leaves the carry alone).
- **Z/NZ/P/M tests** are marked TODO when an instruction in between changes the TI status but not the Z80 flags
  (an LD of anything but the result, INC/DEC rr, ADD HL,rr, PUSH/POP, calls...). After BIT 7, P/M become EQ/NE.
- **Little-endian words**: `LD rr,(nn)` / `LD (nn),rr` become byte pairs (low byte at the address, high byte at
  the address + 1) and DEFW becomes byte pairs, as in the hand-converted variables.
- `JP M`/`RET M`/`CALL M` (`jgt`+`jeq`).

## Still to fix by hand

- **Flag tests after PUSH, POP, CALL or RET**: the stack macros' `mov` sets the TI status and `dect` changes the
  carry. Routines that return a result in carry need `.retc`/`.retnc`; sign/zero tests after a call need a re-test.
  The converter marks the test TODO, but can't fix it.
- **Flag tests after an LD or INC/DEC rr** (marked TODO): move the test, or test the value again (`mov hl,hl`...).
  For example `LD A,(HL) / OR A / INC HL / JP P` becomes `movb *hl+,a / jlt`.
- **CP followed by a carry test with an instruction in between**: `cb`'s status is gone (marked TODO).
- **EX AF,AF'** doesn't save the TI status register.
- **Page-aligned tables** reached by loading a page number (`LD H,$02`, `LD H,$E6`, `ADD A,$08`...) point at MSX
  addresses. They need indexed addressing or base + offset, and the tables need copying (a dependency scan by
  label can't see them).
- **`INC L`/`DEC L` on table or buffer pointers** assume the table doesn't cross a 256-byte page; use `inc hl`.
- **Self-modifying code**: patch offsets are Z80 operand offsets (`+1`/`+2`), not TMS9900 ones; low-byte address
  patches assume page-aligned tables; opcode patches (`RLC`/`RRC`, `INC L`/`DEC L`, `OR`/`XOR`) write Z80 opcodes
  into TI code.
- **Big-endian `data`**: any table the TI code reads with `mov` (e.g. `log_table`) has to be big-endian, unlike
  the DEFW output.
- **`DEFW` snapshot values in variables** are turned into labels, creating false references.
- **MSX hardware**: `IN`/`OUT` on ports other than the VDP (PSG, keyboard, slots), `OUTI`/`OTIR`, BIOS calls, and
  `EI` (interrupts stay off on the TI) are left as TODO comments. So are `CPIR` and other block instructions.
- **Speed**: the exact shift/rotate and ADC/SBC emulations are several instructions each; combine them by hand
  where a routine can be rewritten (e.g. with MPY/DIV).
