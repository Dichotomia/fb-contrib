/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2019 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.detect;

import java.util.BitSet;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Method;

import com.mebigfatguy.fbcontrib.utils.OpcodeUtils;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;

/**
 * looks for methods that copy data from one array to another using a loop. It is better performing to use System.arraycopy to do such copying as this is a
 * native method.
 */
public class ManualArrayCopy extends BytecodeScanningDetector {
    enum State {
        SAW_NOTHING, SAW_ICMP, SAW_ARRAY1_LOAD, SAW_ARRAY1_INDEX, SAW_ARRAY2_LOAD, SAW_ARRAY2_INDEX, SAW_ELEM_LOAD, SAW_ELEM_STORE
    }

    private static final BitSet arrayLoadOps = new BitSet();

    static {
        arrayLoadOps.set(Const.AALOAD);
        arrayLoadOps.set(Const.BALOAD);
        arrayLoadOps.set(Const.CALOAD);
        arrayLoadOps.set(Const.SALOAD);
        arrayLoadOps.set(Const.IALOAD);
        arrayLoadOps.set(Const.LALOAD);
        arrayLoadOps.set(Const.DALOAD);
        arrayLoadOps.set(Const.FALOAD);
    }

    private final BugReporter bugReporter;
    private State state;
    private int arrayIndexReg;
    private int loadInstruction;

    /**
     * constructs a MAC detector given the reporter to report bugs on
     *
     * @param bugReporter
     *            the sync of bug reports
     */
    public ManualArrayCopy(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    /**
     * looks for methods that contain array load opcodes
     *
     * @param method
     *            the context object of the current method
     * @return if the class loads array contents
     */
    private boolean prescreen(Method method) {
        BitSet bytecodeSet = getClassContext().getBytecodeSet(method);
        return (bytecodeSet != null) && bytecodeSet.intersects(arrayLoadOps);
    }

    /**
     * implements the visitor to reset the state
     *
     * @param obj
     *            the context object of the currently parsed code block
     */
    @Override
    public void visitCode(Code obj) {
        if (prescreen(getMethod())) {
            state = State.SAW_NOTHING;
            super.visitCode(obj);
        }
    }

    /**
     * implements the visitor to find loops where array elements are copied to another array
     *
     * @param seen
     *            = the currently parsed opcode
     */
    @Override
    public void sawOpcode(int seen) {
        switch (state) {
            case SAW_NOTHING:
                if ((seen == Const.IF_ICMPGE) || (seen == Const.IF_ICMPGT)) {
                    state = State.SAW_ICMP;
                }
            break;

            case SAW_ICMP:
                if (OpcodeUtils.isALoad(seen)) {
                    state = State.SAW_ARRAY1_LOAD;
                }
            break;

            case SAW_ARRAY1_LOAD:
                if (seen == Const.ILOAD) {
                    arrayIndexReg = getRegisterOperand();
                    state = State.SAW_ARRAY1_INDEX;
                } else if ((seen >= Const.ILOAD_0) && (seen <= Const.ILOAD_3)) {
                    arrayIndexReg = seen - Const.ILOAD_0;
                    state = State.SAW_ARRAY1_INDEX;
                } else {
                    state = State.SAW_NOTHING;
                }
            break;

            case SAW_ARRAY1_INDEX:
                if (OpcodeUtils.isALoad(seen)) {
                    state = State.SAW_ARRAY2_LOAD;
                } else {
                    state = State.SAW_NOTHING;
                }
            break;

            case SAW_ARRAY2_LOAD:
                if (seen == Const.ILOAD) {
                    if (arrayIndexReg == this.getRegisterOperand()) {
                        state = State.SAW_ARRAY2_INDEX;
                    } else {
                        state = State.SAW_NOTHING;
                    }
                } else if ((seen >= Const.ILOAD_0) && (seen <= Const.ILOAD_3)) {
                    if (arrayIndexReg == (seen - Const.ILOAD_0)) {
                        state = State.SAW_ARRAY2_INDEX;
                    } else {
                        state = State.SAW_NOTHING;
                    }
                } else {
                    state = State.SAW_NOTHING;
                }
            break;

            case SAW_ARRAY2_INDEX:
                if ((seen == Const.AALOAD) || (seen == Const.BALOAD) || (seen == Const.CALOAD) || (seen == Const.SALOAD) || (seen == Const.IALOAD)
                        || (seen == Const.LALOAD) || (seen == Const.DALOAD) || (seen == Const.FALOAD)) {
                    loadInstruction = seen;
                    state = State.SAW_ELEM_LOAD;
                } else {
                    state = State.SAW_NOTHING;
                }
            break;

            case SAW_ELEM_LOAD:
                if ((seen == Const.AASTORE) || (seen == Const.BASTORE) || (seen == Const.CASTORE) || (seen == Const.SASTORE) || (seen == Const.IASTORE)
                        || (seen == Const.LASTORE) || (seen == Const.DASTORE) || (seen == Const.FASTORE)) {
                    if (similarArrayInstructions(loadInstruction, seen)) {
                        state = State.SAW_ELEM_STORE;
                    } else {
                        state = State.SAW_NOTHING;
                    }
                } else {
                    state = State.SAW_NOTHING;
                }
            break;

            case SAW_ELEM_STORE:
                if (seen == Const.IINC) {
                    bugReporter.reportBug(new BugInstance(this, "MAC_MANUAL_ARRAY_COPY", NORMAL_PRIORITY).addClass(this).addMethod(this).addSourceLine(this));
                }
                state = State.SAW_NOTHING;
            break;
        }
    }

    /**
     * looks to see if a load and store operation are working on the same type of array
     *
     * @param load
     *            the load instruction on an array
     * @param store
     *            the store instruction on an array
     * @return whether the type of the load and store are the same
     */
    private static boolean similarArrayInstructions(int load, int store) {
        return ((load == Const.AALOAD) && (store == Const.AASTORE)) || ((load == Const.IALOAD) && (store == Const.IASTORE))
                || ((load == Const.DALOAD) && (store == Const.DASTORE)) || ((load == Const.LALOAD) && (store == Const.LASTORE))
                || ((load == Const.FALOAD) && (store == Const.FASTORE)) || ((load == Const.BALOAD) && (store == Const.BASTORE))
                || ((load == Const.CALOAD) && (store == Const.CASTORE)) || ((load == Const.SALOAD) && (store == Const.SASTORE));
    }
}
