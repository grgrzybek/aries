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
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.tx.control.jdbc.local.impl;

import static org.osgi.framework.Constants.SERVICE_PID;

import java.util.Dictionary;
import java.util.Hashtable;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.transaction.control.jdbc.JDBCConnectionProviderFactory;

public class Activator implements BundleActivator {

	private ServiceRegistration<JDBCConnectionProviderFactory> reg;
	private ServiceRegistration<ManagedServiceFactory> factoryReg;
	
	@Override
	public void start(BundleContext context) throws Exception {
		reg = context.registerService(JDBCConnectionProviderFactory.class, 
				new JDBCConnectionProviderFactoryImpl(), getProperties());
		
		factoryReg = context.registerService(ManagedServiceFactory.class, 
				new ManagedServiceFactoryImpl(context), getMSFProperties());
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		reg.unregister();
		factoryReg.unregister();
	}

	private Dictionary<String, Object> getProperties() {
		Dictionary<String, Object> props = new Hashtable<>();
		props.put("osgi.local.enabled", Boolean.TRUE);
		return props;
	}

	private Dictionary<String, ?> getMSFProperties() {
		Dictionary<String, Object> props = new Hashtable<>();
		props.put(SERVICE_PID, "org.apache.aries.tx.control.jdbc.local");
		return props;
	}

}
