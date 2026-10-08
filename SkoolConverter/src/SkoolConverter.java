import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkoolConverter {

    private final static Pattern commentReferencePattern = Pattern.compile("#R([0-9]{4,5}|\\$[0-9a-fA-F]{4})");
    private final static Pattern lsbRegisterPattern = Pattern.compile("[cel]|ixl|iyl");
    private final static int[] labelsExcludedFromReferencing = {
        0x8000
    };

    private final boolean hexOutput;
    private Set<Integer> references;
    private List<Label> labels;
    private int numberOfLinesToKeepAddresses;
    private int resolveLabelsStartAddress = 0;
    private int resolveLabelsEndAddress = 0xFFFF;

    public SkoolConverter(boolean hexOutput) {
        this.hexOutput = hexOutput;
    }

    public void convert(File skoolFile, File tms9900File, int startAddress, int endAddress) throws IOException  {
        resolveLabelsStartAddress = startAddress;
        resolveLabelsEndAddress = endAddress;
        List<Z80Line> z80Lines = readSkoolFile(skoolFile);
        System.out.println(z80Lines.size() + " lines read from: " + skoolFile.getPath());
        references = findReferences(z80Lines, startAddress, endAddress);
        labels = findLabels(z80Lines, startAddress);
        List<TMS9900Line> tms9900Lines = convert(z80Lines, startAddress, endAddress);
        writeTMS9900File(tms9900File, tms9900Lines);
        System.out.println(tms9900Lines.size() + " lines written to: " + tms9900File.getPath());
    }

    private Set<Integer> findReferences(List<Z80Line> z80Lines, int startAddress, int endAddress) {
        Set<Integer> references = new HashSet<>();
        for (Z80Line z80Line : z80Lines) {
            if (z80Line.isTarget()) {
                references.add(z80Line.getAddress());
            }
            switch (z80Line.getType()) {
                case Directive:
                    if (z80Line.getDirective().equals("keep")) {
                        numberOfLinesToKeepAddresses = 2;
                    }
                    break;
//                case Instruction:
//                    Instruction instruction = new Instruction(z80Line.getInstruction());
//                    Operand opr1 = instruction.getOpr1();
//                    if (opr1 != null && opr1.isWordOperand() && (opr1.getType() == Operand.Type.Immediate || opr1.getType() == Operand.Type.Indirect)) {
//                        references.add(opr1.getValue());
//                    }
//                    Operand opr2 = instruction.getOpr2();
//                    if (opr2 != null && opr2.isWordOperand() && (opr2.getType() == Operand.Type.Immediate || opr2.getType() == Operand.Type.Indirect)) {
//                        references.add(opr2.getValue());
//                    }
//                    break;
                case Data:
                    if (z80Line.getInstruction().startsWith("DEFW") && numberOfLinesToKeepAddresses == 0) {
                        String[] words = z80Line.getInstruction().substring(4).trim().split(",");
                        for (String word : words) {
                            int addr = Util.parseInt(word);
                            if (addr >= startAddress && addr < endAddress) {
                                references.add(addr);
                            }
                        }
                    }
                case Comment:
                case ContinuationComment:
                    String comment = z80Line.getComment();
                    if (comment != null) {
                        Matcher matcher = commentReferencePattern.matcher(z80Line.getComment());
                        while (matcher.find()) {
                            references.add(Util.parseInt(matcher.group(1)));
                        }
                    }
                    break;
            }
            if (numberOfLinesToKeepAddresses > 0) {
                numberOfLinesToKeepAddresses--;
            }
        }
        return references;
    }

    private List<Label> findLabels(List<Z80Line> z80Lines, int startAddress) {
        List<Label> labels = new ArrayList<>();
        String labelText = null;
        for (Z80Line z80Line : z80Lines) {
            if (z80Line.getType() == Z80Line.Type.Label) {
                labelText = z80Line.getLabel();
            } else {
                if (z80Line.getType() == Z80Line.Type.Data || z80Line.getType() == Z80Line.Type.Instruction) {
                    if (references.contains(z80Line.getAddress()) && z80Line.getAddress() >= startAddress || labelText != null) {
                        labels.add(new Label(z80Line.getAddress(), labelText));
                        references.add(z80Line.getAddress());
                        labelText = null;
                    }
                }
            }
        }
        return labels;
    }

    private List<TMS9900Line> convert(List<Z80Line> z80Lines, int startAddress, int endAddress) {
        List<TMS9900Line> tms9900Lines = new ArrayList<>();
        int i = 0;
        while (i < z80Lines.size()) {
            Z80Line z80Line = z80Lines.get(i);
            switch (z80Line.getType()) {
                case Empty:
                    tms9900Lines.add(new TMS9900Line(TMS9900Line.Type.Empty));
                    break;
                case Directive:
                    if (z80Line.getDirective().equals("keep")) {
                        numberOfLinesToKeepAddresses = 2;
                    }
                    break;
                case Comment:
                    tms9900Lines.add(new TMS9900Line(TMS9900Line.Type.Comment, z80Line.getComment()));
                    break;
                case ContinuationComment:
                    int j = i;
                    Z80Line previousLine = j > 0 ? z80Lines.get(--j) : null;
                    while (previousLine != null && previousLine.getType() == Z80Line.Type.ContinuationComment && j > 0) {
                        previousLine = z80Lines.get(--j);
                    }
                    Z80Line.Type previousLineType = previousLine != null ? previousLine.getType() : null;
                    tms9900Lines.add(new TMS9900Line(previousLineType == Z80Line.Type.Data ? TMS9900Line.Type.ContinuationCommentData : TMS9900Line.Type.ContinuationCommentInstruction, z80Line.getComment()));
                    break;
                case Data:
                case Instruction:
                    if (z80Line.getAddress() >= startAddress && z80Line.getAddress() < endAddress) {
                        if (references.contains(z80Line.getAddress())) {
                            if (lastLineWasAGlobalLabel(tms9900Lines)) {
                                TMS9900Line directive = new TMS9900Line(TMS9900Line.Type.Directive);
                                directive.setDirective("equ  $");
                                tms9900Lines.add(directive);
                            }
                            TMS9900Line label = new TMS9900Line(TMS9900Line.Type.Label);
                            label.setLabel(getValidLabel(z80Line.getAddress(), true));
                            String addressString = Util.intToStr(z80Line.getAddress(), hexOutput, true);
                            if (!label.getLabel().contains(addressString)) {
                                label.setComment(addressString);
                            }
                            tms9900Lines.add(label);
                        }
                        if (z80Line.getType() == Z80Line.Type.Data) {
                            i += convertData(z80Line, tms9900Lines);
                        } else {
                            i += convertInstruction(z80Lines, i, tms9900Lines);
                        }
                    }
                    break;
            }
            if (numberOfLinesToKeepAddresses > 0) {
                numberOfLinesToKeepAddresses--;
            }
            i++;
        }
        return tms9900Lines;
    }

    private int convertData(Z80Line z80Line, List<TMS9900Line> tms9900Lines) {
        String instruction;
        if (z80Line.getInstruction().startsWith("DEFS")) {
            instruction = "bss " + Util.parseInt(z80Line.getInstruction().substring(4).trim());
        } else if (z80Line.getInstruction().startsWith("DEFM")) {
            String[] messageParts = z80Line.getInstruction().substring(4).trim().split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (int i = 0; i < messageParts.length; i++) {
                String messagePart = messageParts[i];
                java.util.regex.Matcher charPlus = java.util.regex.Pattern.compile("^\"(.)\"\\+(\\$[0-9A-Fa-f]+|\\d+)$").matcher(messagePart.trim());
                if (charPlus.matches()) {
                    // A character with a value added, like "t"+$80 (the last character, with bit 7 set)
                    messagePart = ">" + Util.hexString((charPlus.group(1).charAt(0) + Util.parseInt(charPlus.group(2))) & 0xff, 2);
                } else if (messagePart.startsWith("\"")) {
                    messagePart = "'" + messagePart.substring(1, messagePart.length() - 1).replace("\"", "").replace("'", "''") + "'";
                } else {
                    messagePart = ">" + Util.hexString(Util.parseInt(messagePart), 2);
                }
                messageParts[i] = messagePart;
            }
            instruction = "text " + String.join(",", messageParts);
        } else {
            boolean isWord = z80Line.getInstruction().startsWith("DEFW");
            instruction = z80Line.getInstruction().replace("DEFB", "byte");
            if (isWord) {
                // Words are stored little-endian, as the Z80 code reads them byte by byte
                String[] words = instruction.substring(4).trim().split(",");
                List<String> bytes = new ArrayList<>();
                for (String word : words) {
                    word = word.trim();
                    if (word.isEmpty()) {
                        continue;
                    }
                    int value = Util.parseInt(word);
                    String label = references.contains(value) && !isLabelExcluded(value) ? getValidLabel(value) : null;
                    if (label != null && !label.startsWith(">")) {
                        bytes.add(label + "%%256");
                        bytes.add(label + "//256");
                    } else {
                        bytes.add(Util.tiHexString(value & 0xff, false));
                        bytes.add(Util.tiHexString(value >> 8, false));
                    }
                }
                instruction = "byte " + String.join(",", bytes);
            } else {
                if (z80Line.getComment() != null) {
                    Matcher matcher = commentReferencePattern.matcher(z80Line.getComment());
                    while (matcher.find()) {
                        int address = Util.parseInt(matcher.group(1));
                        int msb = address / 256;
                        int lsb = address % 256;
                        String z80Bytes = lsb + "," + msb;
                        String label = getValidLabel(address);
                        String tms9900Bytes = label + "%%256," + label + "//256";
                        instruction = instruction.replace(z80Bytes, tms9900Bytes);
                    }
                }
            }
        }
        tms9900Lines.add(new TMS9900Line(TMS9900Line.Type.Data, z80Line.getComment(), instruction.replace("$", ">")));
        return 0;
    }

    private int convertInstruction(List<Z80Line> z80Lines, int index, List<TMS9900Line> tms9900Lines) {
        Z80Line z80Line = z80Lines.get(index);
        Instruction instruction = new Instruction(z80Line.getInstruction());
        Operand opr1 = instruction.getOpr1();
        Operand opr2 = instruction.getOpr2();
        TMS9900Line tms9900Line = new TMS9900Line(TMS9900Line.Type.Instruction, z80Line.getComment());
        tms9900Line.setZ80Instruction(z80Line.getInstruction());
        List<TMS9900Line> additionalLines = new ArrayList<>();
        int consumed = 0;
        String opcode = instruction.getOpcode();
        switch (opcode) {
            case "add":
                if (opr1 != null) {
                    boolean isWord = opr1.isWordOperand();
                    tms9900Line.setInstruction((isWord ? "a    " : "ab   ") + getTMS9900Equivalent(opr2) + "," + getTMS9900Equivalent(opr1));
                }
                break;
            case "adc":
                if (opr1 != null) {
                    convertAdcSbc(true, opr1, opr2, carryState(tms9900Lines), tms9900Line, additionalLines);
                }
                break;
            case "and":
                if (opr1 != null) {
                    if (opr1.getType() == Operand.Type.Immediate) {
                        tms9900Line.setInstruction("andi a," + (hexOutput ? Util.tiHexString(opr1.getValue() * 256, true) : opr1.getValue() + "*256"));
                    } else if (opr1.getType() == Operand.Type.Register && opr1.getRegister().equals("a")) {
                        tms9900Line.setInstruction("socb a,a");  // AND A: just tests A
                    } else {
                        emit(tms9900Line, additionalLines, "movb " + getTMS9900Equivalent(opr1) + ",r0", "inv  r0", "szcb r0,a");
                    }
                }
                break;
            case "bit":
                if (opr2.getType() == Operand.Type.Register && !lsbRegisterPattern.matcher(opr2.getRegister()).matches()) {
                    tms9900Line.setInstruction("czc  @bit" + opr1.getValue() + "," + getTMS9900Equivalent(opr2));
                } else {
                    tms9900Line.setInstruction("movb " + getTMS9900Equivalent(opr2) + ",r0");
                    additionalLines.add(new TMS9900Line(TMS9900Line.Type.Instruction, null, "andi r0," + (hexOutput ? Util.tiHexString((1 << opr1.getValue()) * 256, true) : (1 << opr1.getValue()) + "*256")));
                }
                break;
            case "call":
                if (opr1 != null && opr2 == null) {
                    tms9900Line.setInstruction(".call @" + getTMS9900Equivalent(opr1));
                } else if (opr1 != null && opr1.getType() == Operand.Type.Flag) {
                    setInverseJumpInstruction(tms9900Line, tms9900Lines, additionalLines, opr1);
                    additionalLines.add(new TMS9900Line(TMS9900Line.Type.Instruction, null, ".call @" + getTMS9900Equivalent(opr2)));
                    TMS9900Line label = new TMS9900Line(TMS9900Line.Type.Label);
                    label.setLabel("!");
                    additionalLines.add(label);
                } else {
                    unhandledInstruction(tms9900Line, instruction);
                }
                break;
            case "ccf":
                Carry before = carryState(tms9900Lines);
                if (before == Carry.CLEAR) {
                    emit(tms9900Line, additionalLines, "seto r0", "inc  r0");
                } else if (before == Carry.COMPARE) {
                    // The carry was A < the operand: set it when A >= the operand
                    emit(tms9900Line, additionalLines, "jhe  !", "clr  r0", "inc  r0", "jmp  !!", "!", "seto r0", "inc  r0", "!");
                } else {
                    emit(tms9900Line, additionalLines, "joc  !", "seto r0", "inc  r0", "jmp  !!", "!", "clr  r0", "inc  r0", "!");
                }
                break;
            case "cp":
                tms9900Line.setInstruction("cb   a," + getTMS9900Equivalent(opr1));
                break;
            case "cpl":
                tms9900Line.setInstruction("inv  a");
                break;
            case "daa":
                tms9900Line.setInstruction(".daa");
                break;
            case "dec":
                if (opr1 != null) {
                    boolean isWord = opr1.isWordOperand();
                    if (isWord) {
                        tms9900Line.setInstruction("dec  " + getTMS9900Equivalent(opr1));
                    } else {
                        tms9900Line.setInstruction("sb   one," + getTMS9900Equivalent(opr1));
                    }
                }
                break;
            case "di":
                tms9900Line.setInstruction("limi 0");
                break;
            case "djnz":
                tms9900Line.setInstruction("sb   one,b");
                additionalLines.add(new TMS9900Line(TMS9900Line.Type.Instruction, null, "jne  " + getTMS9900Equivalent(opr1)));
                break;
            case "ex":
                tms9900Line.setInstruction(".ex_" + getTMS9900Equivalent(opr1).replace("*", "") + "_" + getTMS9900Equivalent(opr2));
                break;
            case "exx":
                tms9900Line.setInstruction(".exx");
                break;
            case "halt":
                tms9900Line.setInstruction(".halt");
                break;
            case "in":
                if (opr1 != null && opr1.getType() == Operand.Type.Register && opr2 != null && opr2.getType() == Operand.Type.Indirect) {
                    if (opr2.getValue() == 0x98 || opr2.getValue() == 0x99) {
                        tms9900Line.setInstruction("movb @vdp" + (opr2.getValue() == 0x98 ? "rd" : "sta") + "," + getTMS9900Equivalent(opr1));
                    } else {
                        unhandledInstruction(tms9900Line, instruction);
                    }
                }
                break;
            case "inc":
                if (opr1 != null) {
                    boolean isWord = opr1.isWordOperand();
                    if (isWord) {
                        tms9900Line.setInstruction("inc  " + getTMS9900Equivalent(opr1));
                    } else {
                        tms9900Line.setInstruction("ab   one," + getTMS9900Equivalent(opr1));
                    }
                }
                break;
            case "jp":
                if (opr1 != null && opr2 == null) {
                    if (opr1.getValue() != 0) {
                        tms9900Line.setInstruction("b    @" + getTMS9900Equivalent(opr1));
                    } else {
                        tms9900Line.setInstruction("b    " + getTMS9900Equivalent(opr1));
                        tms9900Line.setComment("TODO. ");
                    }
                } else if (opr1 != null && opr1.getType() == Operand.Type.Flag) {
                    setInverseJumpInstruction(tms9900Line, tms9900Lines, additionalLines, opr1);
                    additionalLines.add(new TMS9900Line(TMS9900Line.Type.Instruction, null, "b    @" + getTMS9900Equivalent(opr2)));
                    TMS9900Line label = new TMS9900Line(TMS9900Line.Type.Label);
                    label.setLabel("!");
                    additionalLines.add(label);
                } else {
                    unhandledInstruction(tms9900Line, instruction);
                }
                break;
            case "jr":
                if (opr1 != null && opr2 == null) {
                    tms9900Line.setInstruction("jmp  " + getTMS9900Equivalent(opr1));
                } else if (opr1 != null && opr1.getType() == Operand.Type.Flag) {
                    String flag = opr1.getFlag();
                    String target = getTMS9900Equivalent(opr2);
                    if (flag.equals("c") || flag.equals("nc")) {
                        boolean c = flag.equals("c");
                        if (getLastInstruction(tms9900Lines).startsWith("cb")) {
                            tms9900Line.setInstruction((c ? "jl   " : "jhe  ") + target);
                        } else {
                            switch (carryState(tms9900Lines)) {
                                case SAME:
                                    tms9900Line.setInstruction((c ? "joc  " : "jnc  ") + target);
                                    break;
                                case INVERTED:
                                    tms9900Line.setInstruction((c ? "jnc  " : "joc  ") + target);
                                    break;
                                case CLEAR:
                                    tms9900Line.setInstruction(c ? ";" : "jmp  " + target);
                                    tms9900Line.setComment("(carry is clear here) " + (tms9900Line.getComment() != null ? tms9900Line.getComment() : ""));
                                    break;
                                default:
                                    tms9900Line.setInstruction((c ? "joc  " : "jnc  ") + target);
                                    setUnhandledComment(tms9900Line);
                                    break;
                            }
                        }
                    } else {
                        tms9900Line.setInstruction("j" + getTMS9900Equivalent(opr1) + "  " + target);
                        if (!flagsValid(tms9900Lines, flag)) {
                            setUnhandledComment(tms9900Line);
                        }
                    }
                } else {
                    unhandledInstruction(tms9900Line, instruction);
                }
                break;
            case "ld":
                if (opr1 != null && opr2 != null) {
                    boolean isWord;
                    if (opr2.getType() == Operand.Type.Immediate) {
                        isWord = opr1.isWordOperand();
                        if (isWord) {
                            tms9900Line.setInstruction("li   " + getTMS9900Equivalent(opr1) + "," + getTMS9900Equivalent(opr2, isWord));
                        } else {
                            // MOVB leaves the carry alone, as LD does
                            tms9900Line.setInstruction("movb " + getTMS9900Equivalent(opr2) + "," + getTMS9900Equivalent(opr1));
                        }
                    } else if (opr2.getType() == Operand.Type.Indirect && isWordRegister(opr1)) {
                        // Words in memory are little-endian
                        String address = getTMS9900Equivalent(opr2);
                        String register = getTMS9900Equivalent(opr1);
                        emit(tms9900Line, additionalLines, "movb " + address + "+1," + register, "movb " + address + "," + lowByte(register));
                    } else if (opr1.getType() == Operand.Type.Indirect && isWordRegister(opr2)) {
                        String address = getTMS9900Equivalent(opr1);
                        String register = getTMS9900Equivalent(opr2);
                        emit(tms9900Line, additionalLines, "movb " + register + "," + address + "+1", "movb " + lowByte(register) + "," + address);
                    } else {
                        isWord = opr1.isWordOperand() && opr2.isWordOperand();
                        tms9900Line.setInstruction((isWord ? "mov " : "movb") + " " + getTMS9900Equivalent(opr2) + "," + getTMS9900Equivalent(opr1));
                    }
                }
                break;
            case "lddr":
                tms9900Line.setInstruction(".lddr");
                break;
            case "ldi":
                tms9900Line.setInstruction(".ldi");
                break;
            case "ldir":
                tms9900Line.setInstruction(".ldir");
                break;
            case "neg":
                // NEG negates the whole of R4, so clear F first
                emit(tms9900Line, additionalLines, "andi a,>ff00", "neg  a");
                break;
            case "nop":
                tms9900Line.setInstruction("nop");
                break;
            case "or":
                tms9900Line.setInstruction("socb " + getTMS9900Equivalent(opr1) + ",a");
                break;
            case "out":
                if (opr1 != null && opr1.getType() == Operand.Type.Indirect && opr2 != null && opr2.getType() == Operand.Type.Register) {
                    if (opr1.getValue() == 0x98 || opr1.getValue() == 0x99) {
                        tms9900Line.setInstruction("movb " + getTMS9900Equivalent(opr2) + ",@vdpw" + (opr1.getValue() == 0x98 ? "d" : "a"));
                    } else {
                        unhandledInstruction(tms9900Line, instruction);
                    }
                }
                break;
            case "push":
                tms9900Line.setInstruction(".push " + getTMS9900Equivalent(opr1));
                break;
            case "pop":
                tms9900Line.setInstruction(".pop " + getTMS9900Equivalent(opr1));
                break;
            case "res":
                tms9900Line.setInstruction("szcb @bit" + opr1.getValue() + "," + getTMS9900Equivalent(opr2));
                break;
            case "ret":
                if (opr1 == null) {
                    tms9900Line.setInstruction(".ret");
                } else if (opr1.getType() == Operand.Type.Flag) {
                    setInverseJumpInstruction(tms9900Line, tms9900Lines, additionalLines, opr1);
                    additionalLines.add(new TMS9900Line(TMS9900Line.Type.Instruction, null, ".ret"));
                    TMS9900Line label = new TMS9900Line(TMS9900Line.Type.Label);
                    label.setLabel("!");
                    additionalLines.add(label);
                } else {
                    unhandledInstruction(tms9900Line, instruction);
                }
                break;
            case "rl":
            case "rr":
            case "rlc":
            case "rrc":
            case "sla":
            case "sra":
            case "srl":
                if (isShiftable(opr1)) {
                    consumed = convertShift(opcode, opr1, z80Lines, index, tms9900Line, tms9900Lines, additionalLines);
                } else {
                    unhandledInstruction(tms9900Line, instruction);
                }
                break;
            case "rla":
            case "rra":
            case "rlca":
            case "rrca":
                emitShift(opcode.substring(0, opcode.length() - 1), "a", carryState(tms9900Lines), tms9900Line, additionalLines);
                break;
            case "rst":
                tms9900Line.setInstruction(".rst " + Util.intToStr(opr1.getValue(), hexOutput, false));
                break;
            case "scf":
                emit(tms9900Line, additionalLines, "seto r0", "inc  r0");
                break;
            case "set":
                tms9900Line.setInstruction("socb @bit" + opr1.getValue() + "," + getTMS9900Equivalent(opr2));
                break;
            case "sub":
                tms9900Line.setInstruction("sb   " + getTMS9900Equivalent(opr1) + ",a");
                break;
            case "sbc":
                if (opr1 != null) {
                    boolean isWord = opr1.isWordOperand();
                    Carry carry = carryState(tms9900Lines);
                    if (!isWord && opr2 != null && opr2.getType() == Operand.Type.Register && opr2.getRegister().equals("a")) {
                        // SBC A,A: 0 or $FF from the carry, which MOVB leaves alone
                        if (carry == Carry.CLEAR) {
                            tms9900Line.setInstruction("movb @zero,a");
                        } else {
                            emit(tms9900Line, additionalLines, carrySkip(carry) + "  !", "movb @b255,a", "jmp  !!", "!", "movb @zero,a", "!");
                            if (carry == Carry.UNKNOWN) {
                                setUnhandledComment(tms9900Line);
                            }
                        }
                    } else {
                        convertAdcSbc(false, opr1, opr2, carry, tms9900Line, additionalLines);
                    }
                }
                break;
            case "xor":
                if (opr1.getType() == Operand.Type.Register && opr1.getRegister().equals("a")) {
                    tms9900Line.setInstruction("sb   a,a");
                } else if (opr1.getType() == Operand.Type.Immediate) {
                    emit(tms9900Line, additionalLines, "li   r0," + (hexOutput ? Util.tiHexString(opr1.getValue() * 256, true) : opr1.getValue() + "*256"), "xor  a,r0", "movb r0,a");
                } else {
                    emit(tms9900Line, additionalLines, "movb " + getTMS9900Equivalent(opr1) + ",r0", "xor  a,r0", "movb r0,a");
                }
                break;
            default:
                unhandledInstruction(tms9900Line, instruction);
                break;
        }
        tms9900Lines.add(tms9900Line);
        tms9900Lines.addAll(additionalLines);
        if (consumed > 0) {
            // The next line was combined into this one - keep its Z80 instruction and comment
            Z80Line next = z80Lines.get(index + 1);
            TMS9900Line combined = new TMS9900Line(TMS9900Line.Type.Instruction, next.getComment(), ";");
            combined.setZ80Instruction(next.getInstruction());
            tms9900Lines.add(combined);
        }
        return consumed;
    }

    // ----- Shifts and rotates

    private boolean isShiftable(Operand operand) {
        return operand != null && (operand.getType() == Operand.Type.Register || operand.getType() == Operand.Type.IndirectRegister || operand.getType() == Operand.Type.Indexed);
    }

    private static final String[][] REGISTER_PAIRS = {{"h", "l", "hl"}, {"d", "e", "de"}, {"b", "c", "bc"}};

    private int convertShift(String opcode, Operand operand, List<Z80Line> z80Lines, int index, TMS9900Line tms9900Line, List<TMS9900Line> tms9900Lines, List<TMS9900Line> additionalLines) {
        Carry carry = carryState(tms9900Lines);
        // A shift of a register pair's high and low halves is a single 16-bit shift
        Instruction next = nextInstruction(z80Lines, index);
        if (next != null && operand.getType() == Operand.Type.Register && next.getOpr1() != null && next.getOpr1().getType() == Operand.Type.Register && next.getOpr2() == null) {
            String reg = operand.getRegister();
            String nextReg = next.getOpr1().getRegister();
            String nextOpcode = next.getOpcode();
            for (String[] pair : REGISTER_PAIRS) {
                String high = pair[0], low = pair[1], rp = pair[2];
                if (reg.equals(high) && nextReg.equals(low) && nextOpcode.equals("rr")) {
                    switch (opcode) {
                        case "srl":
                            tms9900Line.setInstruction("srl  " + rp + ",1");
                            return 1;
                        case "sra":
                            tms9900Line.setInstruction("sra  " + rp + ",1");
                            return 1;
                        case "rr":
                            if (carry == Carry.CLEAR) {
                                tms9900Line.setInstruction("srl  " + rp + ",1");
                            } else {
                                emit(tms9900Line, additionalLines, carrySkip(carry) + "  !", "srl  " + rp + ",1", "ori  " + rp + ",>8000", "jmp  !!", "!", "srl  " + rp + ",1", "!");
                                if (carry == Carry.UNKNOWN) {
                                    setUnhandledComment(tms9900Line);
                                }
                            }
                            return 1;
                    }
                }
                if (reg.equals(low) && nextReg.equals(high) && nextOpcode.equals("rl")) {
                    switch (opcode) {
                        case "sla":
                            tms9900Line.setInstruction("sla  " + rp + ",1");
                            return 1;
                        case "rl":
                            if (carry == Carry.CLEAR) {
                                tms9900Line.setInstruction("sla  " + rp + ",1");
                            } else {
                                emit(tms9900Line, additionalLines, carrySkip(carry) + "  !", "sla  " + rp + ",1", "ori  " + rp + ",1", "jmp  !!", "!", "sla  " + rp + ",1", "!");
                                if (carry == Carry.UNKNOWN) {
                                    setUnhandledComment(tms9900Line);
                                }
                            }
                            return 1;
                    }
                }
            }
        }
        emitShift(opcode, getTMS9900Equivalent(operand), carry, tms9900Line, additionalLines);
        return 0;
    }

    // An 8-bit shift or rotate of x, with the result in x and the bit shifted out in the TI carry, as on the Z80.
    // Left shifts work on the byte in R0's high byte, right shifts on the byte in its low byte.
    private void emitShift(String opcode, String x, Carry carry, TMS9900Line tms9900Line, List<TMS9900Line> additionalLines) {
        switch (opcode) {
            case "sla":
                emit(tms9900Line, additionalLines, "clr  r0", "movb " + x + ",r0", "sla  r0,1", "movb r0," + x);
                break;
            case "srl":
                emit(tms9900Line, additionalLines, "clr  r0", "movb " + x + ",@r0lb", "srl  r0,1", "movb @r0lb," + x);
                break;
            case "sra":
                emit(tms9900Line, additionalLines, "movb " + x + ",r0", "sra  r0,8", "sra  r0,1", "movb @r0lb," + x);
                break;
            case "rlc":
                emit(tms9900Line, additionalLines, "movb " + x + ",r0", "movb " + x + ",@r0lb", "sla  r0,1", "movb r0," + x);
                break;
            case "rrc":
                emit(tms9900Line, additionalLines, "movb " + x + ",r0", "movb " + x + ",@r0lb", "srl  r0,1", "movb @r0lb," + x);
                break;
            case "rl":
                if (carry == Carry.CLEAR) {
                    emitShift("sla", x, carry, tms9900Line, additionalLines);
                } else {
                    emit(tms9900Line, additionalLines, "clr  r0", carrySkip(carry) + "  !", "ori  r0,>0080", "!", "movb " + x + ",r0", "sla  r0,1", "movb r0," + x);
                    if (carry == Carry.UNKNOWN) {
                        setUnhandledComment(tms9900Line);
                    }
                }
                break;
            case "rr":
                if (carry == Carry.CLEAR) {
                    emitShift("srl", x, carry, tms9900Line, additionalLines);
                } else {
                    emit(tms9900Line, additionalLines, "clr  r0", carrySkip(carry) + "  !", "ori  r0,>0100", "!", "movb " + x + ",@r0lb", "srl  r0,1", "movb @r0lb," + x);
                    if (carry == Carry.UNKNOWN) {
                        setUnhandledComment(tms9900Line);
                    }
                }
                break;
        }
    }

    private Instruction nextInstruction(List<Z80Line> z80Lines, int index) {
        if (index + 1 >= z80Lines.size()) {
            return null;
        }
        Z80Line next = z80Lines.get(index + 1);
        if (next.getType() != Z80Line.Type.Instruction || references.contains(next.getAddress())) {
            return null;
        }
        return new Instruction(next.getInstruction());
    }

    // ----- Emitting

    private void emit(TMS9900Line tms9900Line, List<TMS9900Line> additionalLines, String... instructions) {
        tms9900Line.setInstruction(instructions[0]);
        for (int i = 1; i < instructions.length; i++) {
            if (instructions[i].equals("!")) {
                TMS9900Line label = new TMS9900Line(TMS9900Line.Type.Label);
                label.setLabel("!");
                additionalLines.add(label);
            } else {
                additionalLines.add(new TMS9900Line(TMS9900Line.Type.Instruction, null, instructions[i]));
            }
        }
    }

    // ADC and SBC. With the Z80 carry clear they're a plain add or subtract. Otherwise the carry-in is taken first
    // (before anything changes the status), and the carry-out is worked out to match the Z80's: for 8 bits by doing
    // the sum on words, for 16 bits by handling the case where adding the carry-in wraps the register round.
    private void convertAdcSbc(boolean add, Operand opr1, Operand opr2, Carry carry, TMS9900Line tms9900Line, List<TMS9900Line> additionalLines) {
        String dst = getTMS9900Equivalent(opr1);
        String src = getTMS9900Equivalent(opr2);
        boolean isWord = opr1.isWordOperand();
        if (carry == Carry.CLEAR) {
            tms9900Line.setInstruction((add ? (isWord ? "a    " : "ab   ") : (isWord ? "s    " : "sb   ")) + src + "," + dst);
            return;
        }
        String skip = carrySkip(carry) + "  !";
        if (isWord) {
            if (add) {
                emit(tms9900Line, additionalLines, "clr  r1", skip, "inc  r1", "!", "a    r1," + dst, "jnc  !",
                    "a    " + src + "," + dst, "seto r0", "inc  r0", "jmp  !!", "!", "a    " + src + "," + dst, "!");
            } else {
                emit(tms9900Line, additionalLines, "clr  r1", skip, "inc  r1", "!", "s    r1," + dst, "joc  !",
                    "s    " + src + "," + dst, "clr  r0", "inc  r0", "jmp  !!", "!", "s    " + src + "," + dst, "!");
            }
        } else {
            if (add) {
                emit(tms9900Line, additionalLines, "clr  r0", skip, "inc  r0", "!", "clr  r1", "movb " + src + ",@r1lb", "a    r1,r0",
                    "movb " + dst + ",@r1lb", "a    r1,r0", "movb @r0lb," + dst, "srl  r0,9", "movb " + dst + "," + dst);
            } else {
                emit(tms9900Line, additionalLines, "clr  r1", skip, "inc  r1", "!", "clr  r0", "movb " + src + ",@r0lb", "a    r0,r1",
                    "clr  r0", "movb " + dst + ",@r0lb", "s    r1,r0", "movb @r0lb," + dst);
            }
        }
        if (carry == Carry.UNKNOWN) {
            setUnhandledComment(tms9900Line);
        }
    }

    // The jump that skips code when the Z80 carry is clear
    private String carrySkip(Carry carry) {
        switch (carry) {
            case INVERTED:
                return "joc";
            case COMPARE:
                return "jhe";  // A >= the operand: no borrow
            default:
                return "jnc";
        }
    }

    private boolean isByteRegister(Operand operand) {
        return operand.getType() == Operand.Type.Register && operand.getRegister().matches("[abcdehl]|ixh|ixl|iyh|iyl");
    }

    private boolean isWordRegister(Operand operand) {
        return operand.getType() == Operand.Type.Register && operand.getRegister().matches("hl|de|bc|ix|iy|sp");
    }

    private String lowByte(String register) {
        switch (register) {
            case "hl": return "@l";
            case "de": return "@e";
            case "bc": return "@c";
            case "ix": return "@ixl";
            case "iy": return "@iyl";
            case "sp": return "@splb";
        }
        return "?";
    }

    private void unhandledInstruction(TMS9900Line tms9900Line, Instruction instruction) {
        tms9900Line.setInstruction("; " + instruction.getInstruction().replaceAll("\\t", " "));
        setUnhandledComment(tms9900Line);
    }

    private void setUnhandledComment(TMS9900Line tms9900Line) {
        tms9900Line.setComment("TODO: check code. " + (tms9900Line.getComment() != null ? tms9900Line.getComment() : ""));
    }

    // Sets the jump that skips the following instruction (a call, a jump or a return) when the condition is false
    private void setInverseJumpInstruction(TMS9900Line tms9900Line, List<TMS9900Line> tms9900Lines, List<TMS9900Line> additionalLines, Operand opr1) {
        String flag = opr1.getFlag();
        if (flag.equals("c") || flag.equals("nc")) {
            boolean c = flag.equals("c");
            if (getLastInstruction(tms9900Lines).startsWith("cb")) {
                tms9900Line.setInstruction(c ? "jhe  !" : "jl   !");
                return;
            }
            switch (carryState(tms9900Lines)) {
                case SAME:
                    tms9900Line.setInstruction(c ? "jnc  !" : "joc  !");
                    break;
                case INVERTED:
                    tms9900Line.setInstruction(c ? "joc  !" : "jnc  !");
                    break;
                case CLEAR:
                    tms9900Line.setInstruction(c ? "jmp  !" : ";");
                    tms9900Line.setComment("(carry is clear here) " + (tms9900Line.getComment() != null ? tms9900Line.getComment() : ""));
                    break;
                default:
                    tms9900Line.setInstruction(c ? "jnc  !" : "joc  !");
                    setUnhandledComment(tms9900Line);
                    break;
            }
            return;
        }
        if ((flag.equals("p") || flag.equals("m")) && lastZ80Instruction(tms9900Lines, "bit")) {
            Instruction bit = lastPrimaryInstruction(tms9900Lines);
            if (bit.getOpr1() != null && bit.getOpr1().getValue() == 7) {
                // After BIT 7, P means the bit is clear (EQ) and M that it's set
                tms9900Line.setInstruction(flag.equals("p") ? "jne  !" : "jeq  !");
                return;
            }
        }
        switch (flag) {
            case "z":
                tms9900Line.setInstruction("jne  !");
                break;
            case "nz":
                tms9900Line.setInstruction("jeq  !");
                break;
            case "p":
                tms9900Line.setInstruction("jlt  !");
                break;
            case "m":
                // Not negative: there is no single TMS9900 jump for A> or EQ
                tms9900Line.setInstruction("jgt  !");
                additionalLines.add(new TMS9900Line(TMS9900Line.Type.Instruction, null, "jeq  !"));
                break;
        }
        if (!flagsValid(tms9900Lines, flag)) {
            setUnhandledComment(tms9900Line);
        }
    }

    // The state of the TI carry compared with the Z80's at the end of the code converted so far
    private enum Carry {
        SAME,       // The TI carry is the Z80 carry
        INVERTED,   // The TI carry is the opposite (after a subtraction: the TMS9900 sets it when there's no borrow)
        CLEAR,      // The Z80 carry is clear (after AND/OR/XOR), whatever the TI carry is
        COMPARE,    // Right after CP: CB sets no carry, but its status gives it (L> = A < the operand)
        UNKNOWN
    }

    private Carry carryState(List<TMS9900Line> tms9900Lines) {
        if (getLastInstruction(tms9900Lines).startsWith("cb")) {
            return Carry.COMPARE;
        }
        for (int i = tms9900Lines.size() - 1; i >= 0; i--) {
            TMS9900Line line = tms9900Lines.get(i);
            if (line.getType() == TMS9900Line.Type.Label) {
                if (!"!".equals(line.getLabel())) {
                    return Carry.UNKNOWN;  // other code can jump here
                }
                continue;  // the end of a conditional jump/return/call: the fallthrough path continues
            }
            if (line.getType() != TMS9900Line.Type.Instruction || line.getZ80Instruction() == null) {
                continue;
            }
            Instruction z80 = new Instruction(line.getZ80Instruction());
            Operand opr1 = z80.getOpr1();
            Operand opr2 = z80.getOpr2();
            switch (z80.getOpcode()) {
                case "add":
                case "adc":
                case "rl":
                case "rr":
                case "rla":
                case "rra":
                case "rlc":
                case "rrc":
                case "rlca":
                case "rrca":
                case "sla":
                case "sra":
                case "srl":
                case "scf":
                    return Carry.SAME;
                case "sub":
                    return Carry.INVERTED;
                case "sbc":
                    if (opr1 != null && opr2 != null && opr1.getOperand().equals("a") && opr2.getOperand().equals("a")) {
                        continue;  // SBC A,A leaves the carry as it was
                    }
                    return Carry.INVERTED;
                case "xor":
                    // XOR A is converted to SB A,A, which sets the TI carry
                    return opr1 != null && opr1.getOperand().equals("a") ? Carry.INVERTED : Carry.CLEAR;
                case "and":
                case "or":
                    return Carry.CLEAR;
                case "ccf": {
                    Carry before = carryState(tms9900Lines.subList(0, i));
                    return before == Carry.CLEAR || before == Carry.COMPARE ? Carry.SAME : before;
                }
                // Instructions that leave the Z80 carry alone, converted to TI instructions that leave it alone too
                case "ld":
                case "push":
                case "bit":
                case "set":
                case "res":
                case "out":
                case "in":
                case "di":
                case "ei":
                case "nop":
                case "cpl":
                    continue;
                case "ex":
                    if (opr1 != null && opr1.getOperand().startsWith("af")) {
                        return Carry.UNKNOWN;
                    }
                    continue;
                case "jr":
                case "jp":
                case "ret":
                    if (opr1 != null && opr1.getType() == Operand.Type.Flag) {
                        continue;  // the fallthrough path of a conditional jump
                    }
                    return Carry.UNKNOWN;
                default:
                    // INC/DEC, POP, CP, calls...: the TI carry is changed (or wasn't set) where the Z80's isn't
                    return Carry.UNKNOWN;
            }
        }
        return Carry.UNKNOWN;
    }

    private static final Set<String> FLAG_SETTERS = new HashSet<>(Arrays.asList(
        "add", "adc", "sub", "sbc", "and", "or", "xor", "cp", "inc", "dec", "neg", "rl", "rr", "rlc", "rrc", "sla", "sra", "srl", "bit", "daa"
    ));

    // Whether the TI status still reflects the Z80 flag a Z/NZ/P/M test is about. LDs between the instruction that
    // set the flags and the test change the TI status (MOVB tests the byte it moves) but not the Z80 flags, so
    // they're fine only if the last byte moved is the result itself.
    private boolean flagsValid(List<TMS9900Line> tms9900Lines, String flag) {
        List<Instruction> loads = new ArrayList<>();  // the LDs in between, last first
        for (int i = tms9900Lines.size() - 1; i >= 0; i--) {
            TMS9900Line line = tms9900Lines.get(i);
            if (line.getType() == TMS9900Line.Type.Label) {
                if (!"!".equals(line.getLabel())) {
                    return false;
                }
                continue;
            }
            if (line.getType() != TMS9900Line.Type.Instruction || line.getZ80Instruction() == null) {
                continue;
            }
            Instruction z80 = new Instruction(line.getZ80Instruction());
            String opcode = z80.getOpcode();
            Operand opr1 = z80.getOpr1();
            if (FLAG_SETTERS.contains(opcode)) {
                if ((opcode.equals("inc") || opcode.equals("dec") || opcode.equals("add")) && opr1 != null && opr1.isWordOperand()) {
                    return false;  // INC/DEC rr and ADD rr,rr leave the Z80 Z and S flags alone
                }
                if ((flag.equals("p") || flag.equals("m")) && (opcode.equals("cp") || opcode.equals("bit"))) {
                    return false;  // CB compares signed, BIT sets no sign
                }
                if (loads.isEmpty()) {
                    return true;
                }
                // The register holding the result, and whether only its sign is known (a 16-bit result's high byte)
                String result;
                boolean signOnly = false;
                if (opr1 != null && opr1.isWordOperand()) {
                    result = opr1.getOperand().substring(0, 1);  // hl -> h
                    signOnly = true;
                } else if (opcode.equals("inc") || opcode.equals("dec")) {
                    result = opr1 != null ? opr1.getOperand() : "?";
                } else if (opcode.equals("cp") || opcode.equals("bit")) {
                    return false;  // no result register
                } else {
                    result = "a";
                }
                if (signOnly && !(flag.equals("p") || flag.equals("m"))) {
                    return false;
                }
                // Follow the result through the loads, in program order
                Set<String> holders = new HashSet<>(Collections.singletonList(result));
                String lastSource = null;
                for (int k = loads.size() - 1; k >= 0; k--) {
                    Instruction ld = loads.get(k);
                    String dst = ld.getOpr1().getOperand(), src = ld.getOpr2().getOperand();
                    if (holders.contains(src)) {
                        holders.add(dst);
                    } else {
                        holders.remove(dst);
                    }
                    lastSource = src;
                }
                return lastSource != null && holders.contains(lastSource) && !lastSource.startsWith("(");
            }
            if ((opcode.equals("jr") || opcode.equals("jp") || opcode.equals("ret")) && opr1 != null && opr1.getType() == Operand.Type.Flag) {
                continue;  // the fallthrough path of a conditional jump, which doesn't change the status
            }
            if (opcode.equals("di") || opcode.equals("ei") || opcode.equals("nop")) {
                continue;
            }
            if (opcode.equals("ld") && opr1 != null && z80.getOpr2() != null && (isByteRegister(opr1) || isByteRegister(z80.getOpr2()))) {
                loads.add(z80);  // an 8-bit LD: MOVB sets the status from the byte it moves
                continue;
            }
            // Anything else leaves the Z80 flags alone but changes the TI status
            return false;
        }
        return false;
    }

    private Instruction lastPrimaryInstruction(List<TMS9900Line> tms9900Lines) {
        for (int i = tms9900Lines.size() - 1; i >= 0; i--) {
            TMS9900Line line = tms9900Lines.get(i);
            if (line.getType() == TMS9900Line.Type.Instruction && line.getZ80Instruction() != null) {
                return new Instruction(line.getZ80Instruction());
            }
        }
        return null;
    }

    private boolean lastZ80Instruction(List<TMS9900Line> tms9900Lines, String opcode) {
        Instruction last = lastPrimaryInstruction(tms9900Lines);
        return last != null && last.getOpcode().equals(opcode);
    }

    private String getTMS9900Equivalent(Operand operand) {
        return getTMS9900Equivalent(operand, operand.isWordOperand());
    }

    private String getTMS9900Equivalent(Operand operand, boolean isWord) {
        if (operand == null) {
            return "?";
        }
        switch (operand.getType()) {
            case Immediate:
                if (!isWord) {
                    if (operand.getValue() == 0) {
                        return "@zero";
                    } else if (operand.getValue() == 1) {
                        return "one";
                    } else if (operand.getValue() == 254) {
                        return "@b254";
                    } else if (operand.getValue() == 255) {
                        return "@b255";
                    } else {
                        return "@bytes+" + Util.intToStr(operand.getValue(), hexOutput, false);
                    }
                } else {
                    return getValidLabel(operand.getValue());
                }
            case Register:
                String reg = operand.getRegister();
                return (lsbRegisterPattern.matcher(reg).matches() ? "@" : "") + (reg.equals("af'") ? "af_" : reg);
            case Indirect:
                return "@" + getValidLabel(operand.getValue());
            case IndirectRegister:
                return "*" + operand.getRegister();
            case Indexed:
                if (operand.getValue() != 0) {
                    return "@" + Util.intToStr(operand.getValue(), hexOutput, false) + "(" + operand.getRegister() + ")";
                } else {
                    return "*" + operand.getRegister();
                }
            case Flag:
                switch (operand.getFlag()) {
                    case "z":
                        return "eq";
                    case "nz":
                        return "ne";
                    case "c":
                        return "oc";
                    case "nc":
                        return "nc";
                    case "p":
                        return "he";
                    case "m":
                        return "lt";
                    default:
                        return "";
                }
            default:
                return operand.getOperand();
        }
    }

    private String getValidLabel(int address) {
        return getValidLabel(address, false);
    }

    private String getValidLabel(int address, boolean forceLabel) {
        if (address >= resolveLabelsStartAddress && address < resolveLabelsEndAddress && (forceLabel || numberOfLinesToKeepAddresses == 0)) {
            for (int i = 0; i < labels.size(); i++) {
                Label label = labels.get(i);
                int labelAddress = label.getAddress();
                if (labelAddress == address) {
                    return label.toString(hexOutput);
                }
                if (labelAddress > address) {
                    if (i > 0) {
                        Label previousLabel = labels.get(i - 1);
                        int previousAddr = previousLabel.getAddress();
                        int offset = address - previousAddr;
                        return previousLabel.toString(hexOutput) + "+" + Util.intToStr(offset, hexOutput, true);
                    } else {
                        return Util.intToStr(address, hexOutput, true);
                    }
                }
            }
        }
        return Util.intToStr(address, hexOutput, true);
    }

    private boolean isLabelExcluded(int addr) {
        return Arrays.stream(labelsExcludedFromReferencing).anyMatch(a -> a == addr);
    }

    private String getLastInstruction(List<TMS9900Line> tms9900Lines) {
        for (int i = tms9900Lines.size() - 1; i >= 0; i--) {
            TMS9900Line tms9900Line = tms9900Lines.get(i);
            if (tms9900Line.getType() == TMS9900Line.Type.Instruction) {
                return tms9900Line.getInstruction();
            }
        }
        return "";
    }

    private boolean lastLineWasAGlobalLabel(List<TMS9900Line> tms9900Lines) {
        for (int i = tms9900Lines.size() - 1; i >= 0; i--) {
            TMS9900Line tms9900Line = tms9900Lines.get(i);
            TMS9900Line.Type type = tms9900Line.getType();
            if (type == TMS9900Line.Type.Label) {
                return !("!".equals(tms9900Line.getLabel()));
            } else if (type == TMS9900Line.Type.Instruction || type == TMS9900Line.Type.Data) {
                return false;
            }
        }
        return false;
    }

    private List<Z80Line> readSkoolFile(File skoolFile) throws IOException {
        List<Z80Line> z80Lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(skoolFile));
        String line = reader.readLine();
        while (line != null) {
            z80Lines.add(new Z80Line(line));
            line = reader.readLine();
        }
        return z80Lines;
    }

    private void writeTMS9900File(File tms9900File, List<TMS9900Line> tms9900Lines) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(tms9900File));
        int numberOfEmptyLines = 0;
        for (TMS9900Line tms9900Line : tms9900Lines) {
            if (tms9900Line.getType() != TMS9900Line.Type.Empty && numberOfEmptyLines > 0) {
                writer.write(Util.repeat("*", 80));
                writer.newLine();
                numberOfEmptyLines = 0;
            }
            writer.write(tms9900Line.toString());
            writer.newLine();
            if (tms9900Line.getType() == TMS9900Line.Type.Empty) {
                numberOfEmptyLines++;
            }
        }
        writer.close();
    }
}
