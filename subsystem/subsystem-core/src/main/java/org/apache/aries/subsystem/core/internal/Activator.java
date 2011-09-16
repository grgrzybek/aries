/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aries.subsystem.core.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.Executor;

import org.apache.aries.subsystem.core.obr.SubsystemResolver;
import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.eclipse.equinox.region.RegionDigraph;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.coordinator.Coordinator;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.repository.Repository;
import org.osgi.service.resolver.Resolver;
import org.osgi.service.subsystem.Subsystem;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bundle activator for the this bundle. When the bundle is starting, this
 * activator will create and register the SubsystemAdmin service.
 */
public class Activator implements BundleActivator {
	private static final Logger LOGGER = LoggerFactory.getLogger(Activator.class);

	private static BundleContext context;
	
	public static BundleContext getBundleContext() {
		return context;
	}
	
	public static Coordinator getCoordinator() {
		return context.getService(context.getServiceReference(Coordinator.class));
	}
	
	public static EventAdmin getEventAdmin() {
		ServiceReference<EventAdmin> ref = context.getServiceReference(EventAdmin.class);
		if (ref == null) return null;
		return context.getService(ref);
	}
	
	public static Executor getExecutor() {
		return context.getService(context.getServiceReference(Executor.class));
	}
	
	public static FrameworkWiring getFrameworkWiring() {
		return context.getBundle(0).adapt(FrameworkWiring.class);
	}
	
	public static RegionDigraph getRegionDigraph() {
		return context.getService(context.getServiceReference(RegionDigraph.class));
	}
	
	public static Collection<Repository> getRepositories() {
		Collection<ServiceReference<Repository>> references;
		try {
			references = context.getServiceReferences(Repository.class, null);
		}
		catch (InvalidSyntaxException e) {
			// This should never happen, but I don't want to cover it up. Nor do I want to force clients to handle it.
			throw new RuntimeException(e);
		}
		ArrayList<Repository> repositories = new ArrayList<Repository>(references.size());
		for (ServiceReference<Repository> reference : references) {
			Repository repository = context.getService(reference);
			if (repository != null)
				repositories.add(repository);
		}
		repositories.trimToSize();
		return repositories;
	}
	
	public static RepositoryAdmin getRepositoryAdmin() {
		ServiceTracker st = new ServiceTracker(context, RepositoryAdmin.class.getName(), null);
		try {
			st.open();
			return (RepositoryAdmin)st.waitForService(5000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		finally {
			st.close();
		}
	}
	
	/*
	 * TODO
	 * A naive implementation serving as a placeholder until a real Resolver comes along.
	 */
	public static Resolver getResolver() {
//		return new SubsystemResolver();
		ServiceTracker st = new ServiceTracker(context, Resolver.class.getName(), null);
		try {
			st.open();
			return (Resolver)st.waitForService(5000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		finally {
			st.close();
		}
	}
	
	private final BundleListener bundleListener = new SubsystemSynchronousBundleListener();
	private final List<ServiceRegistration<?>> registrations = new ArrayList<ServiceRegistration<?>>();
	
	public void start(final BundleContext context) throws Exception {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("subsystem activator starting");
		}
		Activator.context = context;
		register(Subsystem.class.getName(), new SubsystemServiceFactory(), null);
		register(ResolverHookFactory.class, new SubsystemResolverHookFactory(), null);
		context.getBundle(0).getBundleContext().addBundleListener(bundleListener);
		Dictionary<String, Object> properties = new Hashtable<String, Object>();
		properties.put(EventConstants.EVENT_TOPIC, new String[]{"org/osgi/framework/BundleEvent/*"});
		register(EventHandler.class, new BundleEventHandler(), properties);
	}

	public void stop(BundleContext context) throws Exception {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("subsystem activator stopping");
		}
		context.getBundle(0).getBundleContext().removeBundleListener(bundleListener);
		for (ServiceRegistration<?> r : registrations) {
			try {
				r.unregister();
			} catch (Exception e) {
				LOGGER.warn("Subsystem Activator shut down", e);
			}
		}
	}

	private <T> void register(Class<T> clazz, T service, Dictionary<String, ?> props) {
		registrations.add(context.registerService(clazz.getName(), service, props));
	}
	
	private <T> void register(String clazz, T service, Dictionary<String, ?> props) {
		registrations.add(context.registerService(clazz, service, props));
	}
}
