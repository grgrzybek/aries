package org.apache.aries.subsystem.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;

import org.osgi.framework.BundleException;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.coordinator.Coordination;
import org.osgi.service.coordinator.CoordinationException;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.subsystem.SubsystemException;

public class InstallAction implements PrivilegedAction<AriesSubsystem> {
	private final InputStream content;
	private final AccessControlContext context;
	private final Coordination coordination;
	private final boolean embedded;
	private final String location;
	private final AriesSubsystem parent;
	
	public InstallAction(String location, InputStream content, AriesSubsystem parent, AccessControlContext context) {
		this(location, content, parent, context, null, false);
	}
	
	public InstallAction(String location, InputStream content, AriesSubsystem parent, AccessControlContext context, Coordination coordination, boolean embedded) {
		this.location = location;
		this.content = content;
		this.parent = parent;
		this.context = context;
		this.coordination = coordination;
		this.embedded = embedded;
	}
	
	@Override
	public AriesSubsystem run() {
		// Initialization of a null coordination must be privileged and,
		// therefore, occur in the run() method rather than in the constructor.
		Coordination coordination = this.coordination;
		if (coordination == null)
			coordination = Utils.createCoordination(parent);
		AriesSubsystem result = null;
		try {
			TargetRegion region = new TargetRegion(parent);
			SubsystemResource ssr = createSubsystemResource(location, content, parent);
			result = Activator.getInstance().getSubsystems().getSubsystemByLocation(location);
			if (result != null) {
				checkLifecyclePermission(result);
				if (!region.contains(result))
					throw new SubsystemException("Location already exists but existing subsystem is not part of target region: " + location);
				if (!(result.getSymbolicName().equals(ssr.getSubsystemManifest().getSubsystemSymbolicNameHeader().getSymbolicName())
						&& result.getVersion().equals(ssr.getSubsystemManifest().getSubsystemVersionHeader().getVersion())
						&& result.getType().equals(ssr.getSubsystemManifest().getSubsystemTypeHeader().getType())))
					throw new SubsystemException("Location already exists but symbolic name, version, and type are not the same: " + location);
				return (AriesSubsystem)ResourceInstaller.newInstance(coordination, result, parent).install();
			}
			result = (AriesSubsystem)region.find(
					ssr.getSubsystemManifest().getSubsystemSymbolicNameHeader().getSymbolicName(), 
					ssr.getSubsystemManifest().getSubsystemVersionHeader().getVersion());
			if (result != null) {
				checkLifecyclePermission(result);
				if (!result.getType().equals(ssr.getSubsystemManifest().getSubsystemTypeHeader().getType()))
					throw new SubsystemException("Subsystem already exists in target region but has a different type: " + location);
				return (AriesSubsystem)ResourceInstaller.newInstance(coordination, result, parent).install();
			}
			result = createSubsystem(ssr);
			checkLifecyclePermission(result);
			return (AriesSubsystem)ResourceInstaller.newInstance(coordination, result, parent).install();
		}
		catch (Throwable t) {
			coordination.fail(t);
		}
		finally {
			if (!embedded) {
				try {
					coordination.end();
				}
				catch (CoordinationException e) {
					Throwable t = e.getCause();
					if (t instanceof SubsystemException)
						throw (SubsystemException)t;
					if (t instanceof SecurityException)
						throw (SecurityException)t;
					throw new SubsystemException(t);
				}
			}
		}
		return result;
	}

	private void checkLifecyclePermission(final AriesSubsystem subsystem) {
		if (embedded)
			return;
		AccessController.doPrivileged(new PrivilegedAction<Object>() {
			@Override
			public Object run() {
				SecurityManager.checkLifecyclePermission(subsystem);
				return null;
			}
		},
		context);
	}
	
	private AriesSubsystem createSubsystem(SubsystemResource resource) throws URISyntaxException, IOException, BundleException, InvalidSyntaxException {
		final AriesSubsystem result = new AriesSubsystem(resource);
		return result;
		
	}
	
	private SubsystemResource createSubsystemResource(String location, InputStream content, AriesSubsystem parent) throws URISyntaxException, IOException, ResolutionException, UnsupportedOperationException, BundleException, InvalidSyntaxException {
		final SubsystemResource result = new SubsystemResource(location, content, parent);
		return result;
	}
}
