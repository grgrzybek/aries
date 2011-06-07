/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.proxy.impl.weaving;

import java.util.Map;
import java.util.Set;

import org.apache.aries.proxy.FinalModifierException;
import org.apache.aries.proxy.UnableToProxyException;
import org.apache.aries.proxy.impl.NLS;
import org.apache.aries.proxy.impl.common.AbstractWovenProxyAdapter;
import org.apache.aries.proxy.impl.common.TypeMethod;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.EmptyVisitor;
import org.objectweb.asm.commons.Method;

/**
 * This class is used to copy methods from a super-class into a sub-class, but
 * then delegate up to the super-class implementation. We expect to be called
 * with {@link ClassReader#SKIP_CODE}. This class is used when we can't weave
 * all the way up the Class hierarchy and need to override methods on the first
 * subclass we can weave.
 */
final class MethodCopyingClassAdapter extends EmptyVisitor implements Opcodes {
  /** The sub-class visitor to write to */
  private final ClassVisitor cv;
  /** The super-class to copy from */
  private final Class<?> superToCopy;
  /** Is the sub-class in the same package as the super */
  private final boolean samePackage;
  /** The ASM {@link Type} of the sub-class */
  private final Type overridingClassType;
  /** 
   * The Set of {@link Method}s that exist in the sub-class. This set must be
   * live so modifications will be reflected in the parent and prevent clashes 
   */
  private final Set<Method> knownMethods;
  /**
   * The map of field names to methods being added
   */
  private final Map<String, TypeMethod> transformedMethods;
  
  public MethodCopyingClassAdapter(ClassVisitor cv, Class<?> superToCopy,
      Type overridingClassType, Set<Method> knownMethods, 
      Map<String, TypeMethod> transformedMethods) {
    this.cv = cv;
    this.superToCopy = superToCopy;
    this.overridingClassType = overridingClassType;
    this.knownMethods = knownMethods;
    this.transformedMethods = transformedMethods;
    
    String overridingClassName = overridingClassType.getClassName();
    int lastIndex1 = superToCopy.getName().lastIndexOf('.');
    int lastIndex2 = overridingClassName.lastIndexOf('.');
    
    samePackage = (lastIndex1 == lastIndex2) &&
       superToCopy.getName().substring(0, (lastIndex1 == -1)? 1 : lastIndex1)
       .equals(overridingClassName.substring(0, (lastIndex2 == -1)? 1 : lastIndex2));
  }
  
  @Override
  public final MethodVisitor visitMethod(final int access, String name, String desc,
      String sig, String[] exceptions) {
    
    MethodVisitor mv = null;
    //As in WovenProxyAdapter, we only care about "real" methods.
    if (!!!name.equals("<init>") && !!!name.equals("<clinit>")
        && (access & (ACC_STATIC | ACC_PRIVATE | ACC_SYNTHETIC | ACC_ABSTRACT
            | ACC_NATIVE | ACC_BRIDGE)) == 0) {

      // identify the target method parameters and return type
      Method currentTransformMethod = new Method(name, desc);
      // We don't want to duplicate a method we already overrode! 
      if(!!!knownMethods.add(currentTransformMethod))
        return null;
      
      // found a method we should weave
      // We can't override a final method
      if((access & ACC_FINAL) != 0)
        throw new RuntimeException(new FinalModifierException(
            superToCopy, name));
      // We can't call up to a package protected method if we aren't in the same
      // package
      if((access & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE)) == 0) {
        if(!!!samePackage)
          throw new RuntimeException(NLS.MESSAGES.getMessage("method.from.superclass.is.hidden", name, superToCopy.getName(), overridingClassType.getClassName()),
                                     new UnableToProxyException(superToCopy));
      }
      //Safe to copy a call to this method!
      Type superType = Type.getType(superToCopy);
      
      // identify the target method parameters and return type
      String methodStaticFieldName = "methodField" + AbstractWovenProxyAdapter.getSanitizedUUIDString();
      transformedMethods.put(methodStaticFieldName, new TypeMethod(
          superType, currentTransformMethod));  
      
      //Remember we need to copy the fake method *and* weave it, use a 
      //WovenProxyMethodAdapter as well as a CopyingMethodAdapter
      mv = new CopyingMethodAdapter(new WovenProxyMethodAdapter(cv.visitMethod(
          access, name, desc, sig, exceptions), access, name, desc, exceptions,
          methodStaticFieldName, currentTransformMethod, overridingClassType),
          superType, currentTransformMethod);
    }
    
    return mv;
  }
  
  /**
   * This class is used to prevent any method body being copied, instead replacing
   * the body with a call to the super-types implementation. The original annotations
   * attributes etc are all copied.
   */
  private static final class CopyingMethodAdapter extends EmptyVisitor {
    /** The visitor to delegate to */
    private final MethodVisitor mv;
    /** The type that declares this method (not the one that will override it) */
    private final Type superType;
    /** The method we are weaving */
    private final Method currentTransformMethod;
    
    public CopyingMethodAdapter(MethodVisitor mv, Type superType, 
        Method currentTransformMethod) {
      this.mv = mv;
      this.superType = superType;
      this.currentTransformMethod = currentTransformMethod;
    }

    @Override
    public final AnnotationVisitor visitAnnotation(String arg0, boolean arg1) {
      return mv.visitAnnotation(arg0, arg1);
    }

    @Override
    public final AnnotationVisitor visitAnnotationDefault() {
      return mv.visitAnnotationDefault();
    }

    @Override
    public final AnnotationVisitor visitParameterAnnotation(int arg0, String arg1,
        boolean arg2) {
      return mv.visitParameterAnnotation(arg0, arg1, arg2);
    }
    
    @Override
    public final void visitAttribute(Attribute attr) {
      mv.visitAttribute(attr);
    }

    /**
     * We skip code for speed when processing super-classes, this means we
     * need to manually drive some methods here!
     */
    @Override
    public final void visitEnd() {
      mv.visitCode();
      writeBody();
      mv.visitMaxs(currentTransformMethod.getArgumentTypes().length + 1, 0);
      mv.visitEnd();
    }
    
    /**
     * This method loads this, any args, then invokes the super version of this
     */
    private final void writeBody() {
      mv.visitVarInsn(ALOAD, 0);
      
      int nargs = currentTransformMethod.getArgumentTypes().length;
      
      for(int i = 1 ; i <= nargs ; i++) {
        switch(currentTransformMethod.
               getArgumentTypes()[i - 1].getSort()) {
          case (Type.BOOLEAN) :
          case (Type.BYTE) :
          case (Type.CHAR) :
          case (Type.SHORT) :
          case (Type.INT) :
            mv.visitVarInsn(ILOAD, i);
            break;
          case (Type.FLOAT) :
            mv.visitVarInsn(FLOAD, i);
            break;
          case (Type.DOUBLE) :
            mv.visitVarInsn(DLOAD, i);
            break;
          case (Type.LONG) :
            mv.visitVarInsn(LLOAD, i);
            break;
          default :
            mv.visitVarInsn(ALOAD, i);
        }
      }
      
      mv.visitMethodInsn(INVOKESPECIAL, superType.getInternalName(),
          currentTransformMethod.getName(), currentTransformMethod.getDescriptor());
      
      switch(currentTransformMethod.getReturnType().getSort()) {
        case (Type.BOOLEAN) :
        case (Type.BYTE) :
        case (Type.CHAR) :
        case (Type.SHORT) :
        case (Type.INT) :
          mv.visitInsn(IRETURN);
          break;
        case (Type.VOID) :
          mv.visitInsn(RETURN);
          break;
        case (Type.FLOAT) :
          mv.visitInsn(FRETURN);
          break;
        case (Type.DOUBLE) :
          mv.visitInsn(DRETURN);
          break;
        case (Type.LONG) :
          mv.visitInsn(LRETURN);
          break;
        default :
          mv.visitInsn(ARETURN);
      }
    }
  }
}