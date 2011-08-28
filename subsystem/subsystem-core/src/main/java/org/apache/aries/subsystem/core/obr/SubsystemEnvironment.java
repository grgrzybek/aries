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
package org.apache.aries.subsystem.core.obr;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.aries.subsystem.core.archive.Archive;
import org.apache.aries.subsystem.core.internal.Activator;
import org.apache.aries.subsystem.core.internal.AriesSubsystem;
import org.apache.aries.subsystem.core.internal.OsgiIdentityRequirement;
import org.osgi.framework.wiring.Capability;
import org.osgi.framework.wiring.Requirement;
import org.osgi.framework.wiring.Resource;
import org.osgi.framework.wiring.Wire;
import org.osgi.service.repository.Repository;
import org.osgi.service.resolver.Environment;
import org.osgi.service.subsystem.Subsystem;

/*
 * TODO
 * The locating of providers for transitive dependencies needs to have subsystem type and share policies taken into account.
 * So does the locating of providers for feature content with respect to children of the first parent that is not a feature.
 */
public class SubsystemEnvironment implements Environment {
	private final Set<Resource> resources = new HashSet<Resource>();
	private final Map<Resource, Repository> resourceToRepository = new HashMap<Resource, Repository>();
	private final AriesSubsystem subsystem;
	
	public SubsystemEnvironment(AriesSubsystem subsystem) throws IOException, URISyntaxException {
		this.subsystem = subsystem;
	}
	
	@Override
	public Collection<Capability> findProviders(Requirement requirement) {
		Collection<Capability> capabilities = new ArrayList<Capability>();
		if (requirement instanceof OsgiIdentityRequirement) {
			// TODO Consider returning only the first capability matched by the requirement in this case.
			// This means we're looking for a content resource.
			OsgiIdentityRequirement identity = (OsgiIdentityRequirement)requirement;
			if (subsystem.isFeature()) {
				// Features share content resources as well as transitive dependencies.
				findConstituentProviders(requirement, capabilities);
			}
			findArchiveProviders(capabilities, identity, !identity.isTransitiveDependency());
			findRepositoryServiceProviders(capabilities, identity, !identity.isTransitiveDependency());
			return capabilities;
		}
		// This means we're looking for capabilities satisfying a requirement within a content resource or transitive dependency.
		findArchiveProviders(capabilities, requirement, false);
		findRepositoryServiceProviders(capabilities, requirement, false);
		return capabilities;
	}
	
	public URL getContent(Resource resource) {
		Repository repository = resourceToRepository.get(resource);
		if (repository == null)
			return null;
		return repository.getContent(resource);
	}

	@Override
	public Map<Resource, List<Wire>> getWiring() {
		Map<Resource, List<Wire>> wiring = new HashMap<Resource, List<Wire>>();
		for (Resource resource : resourceToRepository.keySet()) {
			wiring.put(resource, Collections.EMPTY_LIST);
		}
		return wiring;
	}
	
	public boolean isContentResource(Resource resource) {
		return resources.contains(resource);
	}

	@Override
	public boolean isEffective(Requirement requirement) {
		return true;
	}
	
	private void findConstituentProviders(Requirement requirement, Collection<Capability> capabilities) {
		Subsystem subsystem = this.subsystem;
		while (subsystem.getParent() != null) {
			subsystem = subsystem.getParent();
		}
		findConstituentProviders(subsystem, requirement, capabilities);
	}
	
	private void findConstituentProviders(Subsystem subsystem, Requirement requirement, Collection<Capability> capabilities) {
		for (Resource resource : subsystem.getConstituents()) {
			for (Capability capability : resource.getCapabilities(requirement.getNamespace())) {
				if (requirement.matches(capability)) {
					capabilities.add(capability);
				}
			}
		}
		findConstituentProviders(subsystem.getChildren(), requirement, capabilities);
	}
	
	private void findConstituentProviders(Collection<Subsystem> children, Requirement requirement, Collection<Capability> capabilities) {
		for (Subsystem child : children) {
			findConstituentProviders(child, requirement, capabilities);
		}
	}
	
	private void findArchiveProviders(Collection<Capability> capabilities, Requirement requirement, boolean content) {
		AriesSubsystem subsystem = this.subsystem;
		while (subsystem.getParent() != null) {
			subsystem = subsystem.getParent();
		}
		findArchiveProviders(capabilities, requirement, subsystem, content);
	}
	
	private void findArchiveProviders(Collection<Capability> capabilities, Requirement requirement, AriesSubsystem subsystem, boolean content) {
		Archive archive = subsystem.getArchive();
		// Archive will be null for the root subsystem and for any subsystem that had no content resources included in the archive.
		if (archive != null) {
			for (Capability capability : archive.findProviders(requirement)) {
				capabilities.add(capability);
				resourceToRepository.put(capability.getResource(), archive);
				if (content)
					resources.add(capability.getResource());
			}
		}
		findArchiveProviders(capabilities, requirement, subsystem.getChildren(), content);
	}
	
	private void findArchiveProviders(Collection<Capability> capabilities, Requirement requirement, Collection<Subsystem> children, boolean content) {
		for (Subsystem child : children) {
			findArchiveProviders(capabilities, requirement, (AriesSubsystem)child, content);
		}
	}
	
//	private void findContentProviders(Collection<Capability> capabilities, OsgiIdentityRequirement requirement) {
//		findArchiveProviders(capabilities, requirement);
//		findRepositoryServiceProviders(capabilities, requirement, !requirement.isTransitiveDependency());
//	}
	
//	private void findFeatureContentProviders(Collection<Capability> capabilities, OsgiIdentityRequirement requirement) {
//		Subsystem subsystem = this.subsystem;
//		while (subsystem.getParent() != null && "osgi.feature".equals(subsystem.getParent().getHeaders().get("Subsystem-Type"))) // TODO Add to constants.
//			subsystem = subsystem.getParent();
//		findFeatureContentProviders(capabilities, requirement, subsystem);
//	}
//	
//	private void findFeatureContentProviders(Collection<Capability> capabilities, OsgiIdentityRequirement requirement, Subsystem subsystem) {
//		for (Resource resource : subsystem.getConstituents()) {
//			for (Capability capability : resource.getCapabilities(requirement.getNamespace())) {
//				if (requirement.matches(capability)) {
//					capabilities.add(capability);
//					resourceToRepository.put(capability.getResource(), repository);
//					resources.add(capability.getResource());
//				}
//			}
//		}
//		findFeatureContentProviders(capabilities, requirement, subsystem.getChildren());
//	}
//	
//	private void findFeatureContentProviders(Collection<Capability> capabilities, OsgiIdentityRequirement requirement, Collection<Subsystem> subsystems) {
//		for (Subsystem subsystem : subsystems)
//			if ("osgi.feature".equals(subsystem.getParent().getHeaders().get("Subsystem-Type"))) // TODO Add to constants.
//				findFeatureContentProviders(capabilities, requirement, subsystem);
//	}
	
	private void findRepositoryServiceProviders(Collection<Capability> capabilities, Requirement requirement, boolean content) {
		Collection<Repository> repositories = Activator.getRepositories();
		for (Repository repository : repositories) {
			for (Capability capability : repository.findProviders(requirement)) {
				capabilities.add(capability);
				resourceToRepository.put(capability.getResource(), repository);
				if (content)
					resources.add(capability.getResource());
			}
		}
	}
}
