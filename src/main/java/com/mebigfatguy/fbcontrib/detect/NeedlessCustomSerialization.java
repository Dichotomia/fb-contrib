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

import org.apache.bcel.Const;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.SignatureBuilder;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * looks for classes that implement Serializable and implements readObject and writeObject by just calling the readDefaultObject or writeDefaultObject of the
 * stream parameter. As this is the standard behavior implementing these methods is not needed.
 */
public class NeedlessCustomSerialization extends BytecodeScanningDetector {

    public static final String SIG_WRITE_OBJECT = new SignatureBuilder().withMethodName("writeObject").withParamTypes("java/io/ObjectOutputStream").toString();

    enum State {
        SEEN_NOTHING, SEEN_ALOAD1, SEEN_INVOKEVIRTUAL, SEEN_RETURN, SEEN_INVALID
    }

    private BugReporter bugReporter;
    private JavaClass serializableClass;
    private boolean inReadObject;
    private boolean inWriteObject;
    private State state;

    /**
     * constructs a NCS detector given the reporter to report bugs on
     *
     * @param bugReporter
     *            the sync of bug reports
     */
    public NeedlessCustomSerialization(BugReporter bugReporter) {
        this.bugReporter = bugReporter;

        try {
            serializableClass = Repository.lookupClass("java/io/Serializable");
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
        }

    }

    /**
     * overrides the method to check for Serializable
     */
    @Override
    public void visitClassContext(ClassContext classContext) {
        try {
            JavaClass cls = classContext.getJavaClass();
            if ((serializableClass != null) && cls.implementationOf(serializableClass)) {
                super.visitClassContext(classContext);
            }
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
        }
    }

    /**
     * overrides the method to check for either readObject or writeObject methods and if so, reset the stack
     */
    @Override
    public void visitCode(Code obj) {
        String nameAndSignature = getMethod().getName() + getMethod().getSignature();
        if (SignatureBuilder.SIG_READ_OBJECT.equals(nameAndSignature)) {
            inReadObject = true;
            inWriteObject = false;
        } else if (SIG_WRITE_OBJECT.equals(nameAndSignature)) {
            inReadObject = false;
            inWriteObject = true;
        }

        if (inReadObject || inWriteObject) {
            state = State.SEEN_NOTHING;
            super.visitCode(obj);
            if (state != State.SEEN_INVALID) {
                bugReporter.reportBug(new BugInstance(this, BugType.NCS_NEEDLESS_CUSTOM_SERIALIZATION.name(), NORMAL_PRIORITY).addClass(this).addMethod(this)
                        .addSourceLine(this, 1));
            }
        }
    }

    @Override
    public void sawOpcode(int seen) {
        switch (state) {
            case SEEN_NOTHING:
                if (seen == Const.ALOAD_1) {
                    state = State.SEEN_ALOAD1;
                } else {
                    state = State.SEEN_INVALID;
                }
            break;

            case SEEN_ALOAD1:
                state = State.SEEN_INVALID;
                if (seen == Const.INVOKEVIRTUAL) {
                    String nameAndSignature = getNameConstantOperand() + getSigConstantOperand();
                    if ((inReadObject && "defaultReadObject()V".equals(nameAndSignature))
                            || (inWriteObject && "defaultWriteObject()V".equals(nameAndSignature))) {
                        state = State.SEEN_INVOKEVIRTUAL;
                    }
                }
            break;

            case SEEN_INVOKEVIRTUAL:
                if (seen == Const.RETURN) {
                    state = State.SEEN_RETURN;
                } else {
                    state = State.SEEN_INVALID;
                }
            break;

            case SEEN_RETURN:
                state = State.SEEN_INVALID;
            break;

            case SEEN_INVALID:
            // if you reach here, never leave
            break;
        }
    }

}
