# Elite for the TI-99/4A home computer

This is a project to convert the MSX 1 version of the game Elite (Firebird, 1987) to the TI-99/4A home computer with
32K RAM expansion. The two computers share the TMS9918A video chip (VDP) and have similar sound chips, but different
CPUs: a Z80 in the MSX and a TMS9900 in the TI-99/4A.

## Status

The game is playable from the title screen to the death scene:

- The title screen with its spinning letters, wireframe ships and music.
- The docked screens: commander status, market prices, buying and selling cargo, equipping the ship, the inventory,
  the galactic and local charts, data on system, and the commander menu.
- Launching, flying and docking, with the scanner, the compass and the dashboard gauges.
- All four views: front, rear, left and right.
- Other ships and their behaviour: traders, pirates, police, Thargoids, asteroids and the space station.
- Combat: lasers, missiles, ECM, the energy bomb, the escape capsule, collisions, explosions and scooping cargo.
- Hyperspace, galactic hyperspace and witchspace; the Torus jump drive and the docking computer.
- The cloaking device and the ECM jammer.
- The five missions.
- The sound effects.
- Freezing the game, with its options.

What is still missing or not right:

- Speed. The game runs at only a few frames a second in flight. Nothing has been done about this yet.
- Saving and loading commanders. The commander menu is there, but the MSX's tape routines have not been replaced.
- Timing. The game polls for the vertical blank instead of using an interrupt, and misses some when a frame takes
  long, so the game clock and the countdowns run slow in flight.
- A few details, such as the keys that bring back the chart cursor (HOME and B on the MSX).

The default commander currently starts with 5000 credits instead of 100, to make testing easier.

## How it was made

The MSX game was disassembled first, and the source code was reengineered in the SkoolKit format, in a separate
project (elite-msx-skoolkit). The comments in the TI-99/4A source code refer to the addresses in that disassembly.

The MSX source code was then converted to TMS9900 assembly using a Java program (SkoolConverter). The basis for the
conversion is a fixed mapping of the Z80 registers onto TMS9900 registers (see src/equates.a99), a simulated stack,
and a number of macros for the Z80 instructions that need several TMS9900 instructions, like call, push and pop.

The result (temp-src/main-code.a99) is a fairly accurate conversion, but one that cannot run as it is: there are
peripheral hardware differences, instructions the converter could not handle, differences in how the two CPUs set
their flags, and little- versus big-endian issues. So the routines were copied from there into src/ a few at a time
and fixed by hand, and each line still shows the Z80 instruction it came from.

The biggest problem was space. TMS9900 code takes up much more room than Z80 code, and the TI-99/4A has only half as
much RAM as the MSX 1. The solution is a cartridge with 8K ROM banks that are paged into the 6000H region:

- Banks 0-3 hold the program that is loaded into the 32K RAM: the code that has to be there, because it is used all
  the time or by the code in several banks, and the game's variables.
- Banks 4-6 hold data: the ship blueprints, the cockpit picture, the font and the title music.
- Banks 7-14 hold code: the docked screens, the charts, the title screen and launching, the flight logic, combat,
  hyperspace, the missions and the sound effects. Code in RAM calls into them, and they call each other, through a
  routine in RAM that switches banks.

Some parts were written for the TI-99/4A rather than converted: the keyboard and joystick routines, the music
player and the sound effects player (for the SN76489 sound chip, which differs from the MSX's AY-3-8910), and the
parts of the 3D code where the TMS9900's multiply and divide instructions do the work.

## Building

Run make.bat from the project folder (on Windows). It needs xas99.py from the xdt99 tools on the path, and Java. It
assembles the program and the ROM banks and makes a 128K cartridge image, elite8.bin, and elite.rpk, a cartridge
file for the MAME emulator.

## Controls

The keys are those of the MSX version where the TI-99/4A has them. FCTN combinations stand in for the MSX keys the TI
doesn't have (the function keys, STOP, TAB, Esc and `[`).

### Flying

| Control          | Joystick (1 or 2) | Key        |
|------------------|-------------------|------------|
| Dive             | Up                | S          |
| Climb            | Down              | X          |
| Roll left        | Left              | ,          |
| Roll right       | Right             | .          |
| Fire laser       | Fire              | A or SHIFT |
| Speed up         |                   | SPACE      |
| Slow down        |                   | /          |
| Torus jump drive |                   | J          |

### Views (in flight)

| Key         | View  |
|-------------|-------|
| 1 or FCTN-1 | Front |
| 2 or FCTN-2 | Rear  |
| 3 or FCTN-3 | Left  |
| 4 or FCTN-4 | Right |

### Weapons and equipment (in flight)

| Key          | Action                                 |
|--------------|----------------------------------------|
| T            | Target a missile                       |
| M            | Fire the missile, once it's locked     |
| U            | Unarm the missile                      |
| E            | ECM                                    |
| FCTN-7 (TAB) | Energy bomb                            |
| FCTN-9 (Esc) | Escape capsule                         |
| C            | Docking computer                       |
| Y            | Cloaking device on/off                 |
| L            | ECM jammer on/off                      |
| I            | Identify the next object in the sights |

### Navigation

| Key | Action                                                  |
|-----|---------------------------------------------------------|
| H   | Hyperspace to the selected system (10-second countdown) |
| G   | Galactic hyperspace (30-second countdown)               |
| 5   | Galactic chart                                          |
| 6   | Local chart                                             |
| 7   | Data on system                                          |
| F   | Find a system by name (on a chart)                      |
| D   | Distance to the system nearest the cursor (on a chart)  |

On a chart, the joystick or the S, X, comma and full stop keys move the cursor.

### Docked

| Key        | Action                   |
|------------|--------------------------|
| 1          | Launch                   |
| 2          | Buy cargo                |
| 3          | Sell cargo               |
| 4          | Equip ship               |
| 8          | Market prices            |
| 9          | Commander status         |
| 0          | Inventory                |
| FCTN-R (`[`) | Load/save commander menu |

Keys 5, 6, 7, 8, 9 and 0 also work in flight.

### Other

| Key           | Action                                                     |
|---------------|------------------------------------------------------------|
| FCTN-5 (F5)   | Game time, score and credits, in turn (in flight)          |
| FCTN-6 (STOP) | Freeze the game; again to unfreeze                         |
| Y / N         | Title screen: load a commander, or start with the default  |

### While the game is frozen

| Key     | Action                       |
|---------|------------------------------|
| Y       | Reverse the pitch controls   |
| B       | Reverse both pitch and roll  |
| R, D, F | Toggle the other options     |
| S / Q   | Sound effects on / off       |
| M       | Mute or unmute               |
| 1       | Quit to the title screen     |
