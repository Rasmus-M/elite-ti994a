# Elite for the TI-99/4A home computer

This is a project to convert the MSX 1 version of the game Elite to the TI-99/4A home computer with 32K RAM expansion.
The two computers are sharing the TMS9918A video chip (VDP) and have similar sound chips, but different CPUs.

The MSX game was disassembled first, and the source code was reengineered in the Skoolkit format
(C:\Users\rasmu\git\elite-msx-skoolkit).

The MSX source code was then converted to TMS9900 assembly suitable for the TI-99/4A using a Java program 
(SkoolConverter). The basis for the conversion is a fixed mapping of the Z80 registers into TMS9900 registers 
(see src/equates.a99), a simulated stack, and a number of macros to handle some of the instructions that require 
multiple TMS9900 instructions, like call, push or pop.

The result is a fairly accurate conversion (temp-src/main-code.a99) that still has lots of issues to sort out, including 
peripheral hardware differences, unconverted (commented) instructions, differences in CPU flags handling, and little vs 
big endian issues.

The biggest problem, however, is space, since TMS9900 code takes up much more space than Z80 code, and the RAM size is 
only half as big as on the MSX 1, so there is no way the code can be assembled as it is. The intention is to use 
additional 8K ROM banks on the TI-99/4A (of which we have an almost unlimited number) for data that can be paged into 
the 6000H region while the main code is running in the 32K RAM.

Another big problem is speed, but we will worry about that later. For now, the first step is to try to convert the title
screen with its spinning letters and wireframe spaceships to the TI-99/4A.
