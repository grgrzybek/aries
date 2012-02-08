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
package org.apache.aries.subsystem.core.obr.felix;

import static org.apache.aries.application.utils.AppConstants.LOG_ENTRY;
import static org.apache.aries.application.utils.AppConstants.LOG_EXIT;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.apache.felix.bundlerepository.Resource;
import org.osgi.framework.resource.Capability;
import org.osgi.framework.resource.Requirement;
import org.osgi.framework.resource.ResourceConstants;
import org.osgi.service.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepositoryAdminRepository implements Repository {
	private static final Logger logger = LoggerFactory.getLogger(RepositoryAdminRepository.class);
	
	private Collection<Repository> repositories = new ArrayList<Repository>();
	private final RepositoryAdmin repositoryAdmin;
	
	public RepositoryAdminRepository(RepositoryAdmin repositoryAdmin) {
		org.apache.felix.bundlerepository.Repository[] repositories = repositoryAdmin.listRepositories();
		for (org.apache.felix.bundlerepository.Repository repository : repositories) {
			FelixRepositoryAdapter r = new FelixRepositoryAdapter(repository);
			this.repositories.add(r);
		}
		this.repositoryAdmin = repositoryAdmin;
	}
	
	@Override
	public Collection<Capability> findProviders(Requirement requirement) {
		logger.debug(LOG_ENTRY, "findProviders", requirement);
		Collection<Capability> result = Collections.emptyList();
		if (ResourceConstants.IDENTITY_NAMESPACE.equals(requirement.getNamespace())) {
			result = new ArrayList<Capability>();
			for (Repository repository : repositories) {
				result.addAll(repository.findProviders(requirement));
			}
			return result;
		}
		else {
			Resource[] resources = repositoryAdmin.discoverResources(
					new org.apache.felix.bundlerepository.Requirement[]{
							new OsgiRequirementAdapter(requirement)});
			logger.debug("Found {} resources with capabilities satisfying {}", resources == null ? 0 : resources.length, requirement);
			if (resources != null  && resources.length != 0) {
				result = new ArrayList<Capability>(result.size());
				OsgiRequirementAdapter adapter = new OsgiRequirementAdapter(requirement);
				for (Resource resource : resources) {
					logger.debug("Evaluating resource {}", resource);
					for (org.apache.felix.bundlerepository.Capability capability : resource.getCapabilities()) {
						logger.debug("Evaluating capability {}", capability);
						if (adapter.isSatisfied(capability)) {
							logger.debug("Adding capability {}", capability);
							result.add(new FelixCapabilityAdapter(capability, new FelixResourceAdapter(resource)));
						}
					}
				}
			}
		}
		logger.debug(LOG_EXIT, "findProviders", result);
		return result;

	}
	
	@Override
	public Map<Requirement, Collection<Capability>> findProviders(Collection<? extends Requirement> requirements) {
		Map<Requirement, Collection<Capability>> result = new HashMap<Requirement, Collection<Capability>>(requirements.size());
		for (Requirement requirement : requirements)
			result.put(requirement, findProviders(requirement));
		return result;
	}
}
