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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.osgi.resource.Resource;
import org.osgi.service.coordinator.Coordination;
import org.osgi.service.coordinator.Participant;
import org.osgi.service.repository.RepositoryContent;
import org.osgi.service.subsystem.Subsystem.State;

public class SubsystemResourceInstaller extends ResourceInstaller {
	public SubsystemResourceInstaller(Coordination coordination, Resource resource, AriesSubsystem subsystem) {
		super(coordination, resource, subsystem);
	}
	
	public Resource install() throws Exception {
		AriesSubsystem result;
		if (resource instanceof RepositoryContent)
			result = installRepositoryContent((RepositoryContent)resource);
		else if (resource instanceof AriesSubsystem)
			result = installAriesSubsystem((AriesSubsystem)resource);
		else if (resource instanceof RawSubsystemResource)
			result = installRawSubsystemResource((RawSubsystemResource)resource);
		else
			result = installSubsystemResource((SubsystemResource)resource);
		return result;
	}
	
	private void addChild(final AriesSubsystem child) {
		// provisionTo will be null if the resource is an already installed
		// dependency.
		if (provisionTo == null)
			return;
		// Don't let a resource become a child of itself.
		if (resource.equals(provisionTo))
			return;
		Activator.getInstance().getSubsystems().addChild(provisionTo, child, !isDependency());
		coordination.addParticipant(new Participant() {
			@Override
			public void ended(Coordination arg0) throws Exception {
				// Nothing
			}

			@Override
			public void failed(Coordination arg0) throws Exception {
				Activator.getInstance().getSubsystems().removeChild(provisionTo, child);
			}
		});
	}
	
	private void addSubsystem(final AriesSubsystem subsystem) {
		Activator.getInstance().getSubsystems().addSubsystem(subsystem);
		coordination.addParticipant(new Participant() {
			@Override
			public void ended(Coordination arg0) throws Exception {
				// Nothing
			}
	
			@Override
			public void failed(Coordination arg0) throws Exception {
				Activator.getInstance().getSubsystems().removeSubsystem(subsystem);
			}
		});
	}
	
	private AriesSubsystem installAriesSubsystem(AriesSubsystem subsystem) throws Exception {
		addChild(subsystem);
		addConstituent(subsystem);
		if (!isDependency())
			addReference(subsystem);
		// TODO Is this check really necessary?
		if (!State.INSTALLING.equals(subsystem.getState()))
			return subsystem;
		addSubsystem(subsystem);
		if (subsystem.isScoped())
			RegionContextBundleHelper.installRegionContextBundle(subsystem);
		Activator.getInstance().getSubsystemServiceRegistrar().register(subsystem, this.subsystem);
		Comparator<Resource> comparator = new InstallResourceComparator();
		// Install dependencies first...
		List<Resource> dependencies = new ArrayList<Resource>(subsystem.getResource().getInstallableDependencies());
		Collections.sort(dependencies, comparator);
		for (Resource dependency : dependencies)
			ResourceInstaller.newInstance(coordination, dependency, subsystem).install();
		for (Resource dependency : subsystem.getResource().getSharedDependencies()) {
			// TODO This needs some more thought. The following check
			// protects against a child subsystem that has its parent as a
			// dependency. Are there other places of concern as well? Is it
			// only the current parent that is of concern or should all
			// parents be checked?
			if (!dependency.equals(this.subsystem))
				ResourceInstaller.newInstance(coordination, dependency, subsystem).install();
		}
		// ...followed by content.
		List<Resource> installableContent = new ArrayList<Resource>(subsystem.getResource().getInstallableContent());
		Collections.sort(installableContent, comparator);
		for (Resource content : installableContent)
			ResourceInstaller.newInstance(coordination, content, subsystem).install();
		// Simulate installation of shared content so that necessary relationships are established.
		for (Resource content : subsystem.getResource().getSharedContent())
			ResourceInstaller.newInstance(coordination, content, subsystem).install();
		subsystem.setState(State.INSTALLED);
		return subsystem;
	}
	
	private AriesSubsystem installRawSubsystemResource(RawSubsystemResource resource) throws Exception {
		SubsystemResource subsystemResource = new SubsystemResource(resource, provisionTo);
		return installSubsystemResource(subsystemResource);
	}
	
	private AriesSubsystem installRepositoryContent(RepositoryContent resource) throws Exception {
		RawSubsystemResource rawSubsystemResource = new RawSubsystemResource(getLocation(), resource.getContent());
		return installRawSubsystemResource(rawSubsystemResource);
	}
	
	private AriesSubsystem installSubsystemResource(SubsystemResource resource) throws Exception {
		AriesSubsystem subsystem = new AriesSubsystem(resource);
		installAriesSubsystem(subsystem);
		return subsystem;
	}
}
