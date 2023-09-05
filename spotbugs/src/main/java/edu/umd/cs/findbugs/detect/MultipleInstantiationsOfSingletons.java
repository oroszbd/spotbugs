/*
 * SpotBugs - Find bugs in Java programs
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
package edu.umd.cs.findbugs.detect;

import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.bcel.OpcodeStackDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import org.apache.bcel.Const;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import edu.umd.cs.findbugs.ba.XField;
import edu.umd.cs.findbugs.ba.PruneUnconditionalExceptionThrowerEdges;
import edu.umd.cs.findbugs.ba.XMethod;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.EnumMap;

public class MultipleInstantiationsOfSingletons extends OpcodeStackDetector {

    private enum Methods {
        CONSTRUCTOR,
        CLONE,
        INSTANCE_GETTER
    };

    private final BugReporter bugReporter;

    private JavaClass cloneableInterface;
    private JavaClass serializableInterface;

    private boolean isSingleton;
    private boolean isGetterMethodSynchronized;

    private boolean isCloneable;
    private boolean implementsCloneableDirectly;
    private boolean hasCloneMethod;
    private boolean cloneOnlyThrowsException;
    private boolean cloneOnlyThrowsCloneNotSupportedException;

    private boolean isSerializable;

    private EnumMap<Methods, XMethod> methods;
    private List<XMethod> methodsUsingMonitor;
    private Set<XMethod> constructors;

    public MultipleInstantiationsOfSingletons(BugReporter bugReporter) {
        this.bugReporter = bugReporter;

        try {
            cloneableInterface = Repository.getInterfaces("java.lang.Cloneable")[0];
            serializableInterface = Repository.getInterfaces("java.io.Serializable")[0];
        } catch (ClassNotFoundException e) {
            bugReporter.reportMissingClass(e);
        }
    }

    @Override
    public void visit(JavaClass obj) {
        isSingleton = false;
        isGetterMethodSynchronized = false;

        isCloneable = false;
        implementsCloneableDirectly = false;
        hasCloneMethod = false;
        cloneOnlyThrowsException = false;
        cloneOnlyThrowsCloneNotSupportedException = false;

        isSerializable = false;

        constructors = new HashSet<>();
        methods = new EnumMap<>(Methods.class);
        methodsUsingMonitor = new ArrayList<>();

        if (obj.getClassName().endsWith("Singleton")) {
            isSingleton = true;
        }

        // Does this class directly implement Cloneable or Serializable?
        try {
            JavaClass[] interfaces = obj.getAllInterfaces();
            isCloneable = java.util.stream.Stream.of(interfaces).anyMatch(i -> i.equals(cloneableInterface));
            isSerializable = java.util.stream.Stream.of(interfaces).anyMatch(i -> i.equals(serializableInterface));

            implementsCloneableDirectly = java.util.stream.Stream.of(obj.getInterfaces()).anyMatch(i -> i.equals(cloneableInterface));
        } catch (ClassNotFoundException e) {
            bugReporter.reportMissingClass(e);
        }

        super.visit(obj);
    }

    @Override
    public void visit(Method obj) {
        if ("clone".equals(getMethodName()) && "()Ljava/lang/Object;".equals(getMethodSig())) {
            cloneOnlyThrowsException = PruneUnconditionalExceptionThrowerEdges.doesMethodUnconditionallyThrowException(getXMethod());
            methods.put(Methods.CLONE, getXMethod());
            hasCloneMethod = true;
        }

        if (Const.CONSTRUCTOR_NAME.equals(getMethodName())) {
            constructors.add(getXMethod());
        }

        super.visit(obj);
    }

    @Override
    public boolean beforeOpcode(int seen) {
        if (seen == Const.ATHROW && "clone".equals(getMethodName())) {
            return true;
        }
        if (seen == Const.ARETURN || seen == Const.MONITORENTER) {
            return true;
        }
        return false;
    }

    @Override
    public void sawOpcode(int seen) {
        if (seen == Const.ATHROW && stack.getStackDepth() > 0) {
            OpcodeStack.Item item = stack.getStackItem(0);
            if (item != null && item.getSignature().equals("Ljava/lang/CloneNotSupportedException;") && cloneOnlyThrowsException) {
                cloneOnlyThrowsCloneNotSupportedException = true;
            }
        } else if (seen == Const.ARETURN && stack.getStackDepth() > 0) {
            OpcodeStack.Item item = stack.getStackItem(0);
            XField field = item.getXField();
            if (field != null) {
                String className = "L" + getClassName() + ";";
                if (field.isPrivate() && field.isStatic() && className.equals(field.getSignature())) {
                    isSingleton = true;
                    isGetterMethodSynchronized = getMethod().isSynchronized();
                    methods.put(Methods.INSTANCE_GETTER, getXMethod());
                }
            }
        } else if (seen == Const.MONITORENTER) {
            methodsUsingMonitor.add(getXMethod());
        }
    }

    @Override
    public void visitAfter(JavaClass javaClass) {
        if (!isSingleton) {
            return;
        }

        boolean hasNonPrivateConstructor = false;
        for (XMethod constructor : constructors) {
            if (!constructor.isPrivate()) {
                hasNonPrivateConstructor = true;
                methods.put(Methods.CONSTRUCTOR, constructor);
                break;
            }
        }

        boolean isGetterMethodUsingMonitor = methodsUsingMonitor.contains(methods.get(Methods.INSTANCE_GETTER));

        if (hasNonPrivateConstructor) {
            bugReporter.reportBug(new BugInstance(this, "SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR", NORMAL_PRIORITY).addClass(this)
                    .addMethod(methods.get(Methods.CONSTRUCTOR)));
        }

        if (methods.containsKey(Methods.INSTANCE_GETTER) && !isGetterMethodSynchronized && !isGetterMethodUsingMonitor) {
            bugReporter.reportBug(new BugInstance(this, "SING_SINGLETON_GETTER_NOT_SYNCHRONIZED", NORMAL_PRIORITY).addClass(this)
                    .addMethod(methods.get(Methods.INSTANCE_GETTER)));
        }

        if (isCloneable) {
            if (implementsCloneableDirectly) { // directly
                bugReporter.reportBug(new BugInstance(this, "SING_SINGLETON_IMPLEMENTS_CLONEABLE", NORMAL_PRIORITY).addClass(this)
                        .addMethod(methods.get(Methods.CLONE)));
            } else { // indirectly
                if (!cloneOnlyThrowsCloneNotSupportedException) { // no or not only CloneNotSupportedException
                    bugReporter.reportBug(new BugInstance(this, "SING_SINGLETON_INDIRECTLY_IMPLEMENTS_CLONEABLE", NORMAL_PRIORITY).addClass(this));
                }
            }
        } else if (hasCloneMethod && !cloneOnlyThrowsCloneNotSupportedException) {
            bugReporter.reportBug(new BugInstance(this, "SING_SINGLETON_IMPLEMENTS_CLONE_METHOD", NORMAL_PRIORITY).addClass(this)
                    .addMethod(methods.get(Methods.CLONE)));
        }

        if (isSerializable) {
            if (javaClass.isEnum()) {
                int numberOfEnumValues = getNumberOfEnumValues(javaClass);
                if (numberOfEnumValues > 0) {
                    bugReporter.reportBug(new BugInstance(this, "SING_SINGLETON_IMPLEMENTS_SERIALIZABLE", NORMAL_PRIORITY).addClass(this));
                }
            } else {
                bugReporter.reportBug(new BugInstance(this, "SING_SINGLETON_IMPLEMENTS_SERIALIZABLE", NORMAL_PRIORITY).addClass(this));
            }
        }

        super.visitAfter(javaClass);
    }

    private static int getNumberOfEnumValues(JavaClass javaClass) {
        try {
            java.lang.reflect.Method valuesMethod = javaClass.getClass().getDeclaredMethod("values");
            Object[] result = (Object[]) valuesMethod.invoke(null);
            return result.length;
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            e.printStackTrace();
            return 0;
        }
    }
}
