/**
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
package org.apache.aries.spifly.dynamic;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import java.util.logging.Level;

import org.apache.aries.spifly.Util;
import org.apache.aries.spifly.WeavingData;
import org.apache.aries.spifly.weaver.TCCLSetterVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

public class ClientWeavingHook implements WeavingHook {
    private final String addedImport;
    private final DynamicWeavingActivator activator;

    ClientWeavingHook(BundleContext context, DynamicWeavingActivator dwActivator) {
        activator = dwActivator;

        addedImport = Util.class.getPackage().getName();
    }

    @Override
    public void weave(WovenClass wovenClass) {
        Bundle consumerBundle = wovenClass.getBundleWiring().getBundle();
        Set<WeavingData> wd = activator.getWeavingData(consumerBundle);
        if (wd != null) {
            activator.log(Level.FINE, "Weaving class " + wovenClass.getClassName());

            ClassReader cr = new ClassReader(wovenClass.getBytes());
            ClassWriter cw = new OSGiFriendlyClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES,
                wovenClass.getBundleWiring().getClassLoader());
            TCCLSetterVisitor tsv = new TCCLSetterVisitor(cw, wovenClass.getClassName(), wd);
            cr.accept(tsv, ClassReader.SKIP_FRAMES);
            if (tsv.isWoven()) {
                wovenClass.setBytes(cw.toByteArray());
                if (tsv.additionalImportRequired())
                    wovenClass.getDynamicImports().add(addedImport);
                if (activator.isLogEnabled(Level.FINEST)) {
                    StringWriter stringWriter = new StringWriter();
                    ClassReader reader = new ClassReader(wovenClass.getBytes());
                    ClassVisitor tracer = new TraceClassVisitor(new PrintWriter(stringWriter));
                    ClassVisitor checker = new CheckClassAdapter(tracer, true);
                    reader.accept(checker, 0);
                    activator.log(Level.FINEST, "Woven class bytecode: \n" + stringWriter.toString());
                }
            }
        }
    }
}
