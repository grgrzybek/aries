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
package org.apache.aries.subsystem.itests.obr.felix;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.aries.subsystem.core.internal.OsgiContentCapability;
import org.apache.aries.subsystem.core.internal.OsgiIdentityCapability;
import org.apache.aries.subsystem.core.internal.ResourceHelper;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.service.repository.RepositoryContent;

public class FelixResourceAdapter implements Resource, RepositoryContent {
	private static String toFelixNamespace(String namespace) {
		if (BundleRevision.BUNDLE_NAMESPACE.equals(namespace))
			return org.apache.felix.bundlerepository.Capability.BUNDLE;
		if (BundleRevision.HOST_NAMESPACE.equals(namespace))
			return org.apache.felix.bundlerepository.Capability.FRAGMENT;
		if (BundleRevision.PACKAGE_NAMESPACE.equals(namespace))
			return org.apache.felix.bundlerepository.Capability.PACKAGE;
		return namespace;
	}
	
	private final org.apache.felix.bundlerepository.Resource resource;
	
	public FelixResourceAdapter(final org.apache.felix.bundlerepository.Resource resource) {
		this.resource = resource;
	}
	
	public boolean equals(Object o) {
		if (o == this) 
			return true;
		if (!(o instanceof Resource)) 
			return false;
		Resource that = (Resource)o;
		if (!ResourceHelper.getTypeAttribute(that).equals(ResourceHelper.getTypeAttribute(this))) 
			return false;
		if (!ResourceHelper.getSymbolicNameAttribute(that).equals(ResourceHelper.getSymbolicNameAttribute(this)))
			return false;
		if (!ResourceHelper.getVersionAttribute(that).equals(ResourceHelper.getVersionAttribute(this)))
			return false;
		return true;
	}
	
	public int hashCode() {
		int result = 17;
		result = 31 * result + ResourceHelper.getTypeAttribute(this).hashCode();
		result = 31 * result + ResourceHelper.getSymbolicNameAttribute(this).hashCode();
		result = 31 * result + ResourceHelper.getVersionAttribute(this).hashCode();
		return result;
	}
	
	public List<Capability> getCapabilities(String namespace) {
		namespace = toFelixNamespace(namespace);
		org.apache.felix.bundlerepository.Capability[] capabilities = resource.getCapabilities();
		ArrayList<Capability> result = new ArrayList<Capability>(capabilities.length);
		if (namespace == null || namespace.equals(IdentityNamespace.IDENTITY_NAMESPACE)) {
			result.add(
					// TODO Assuming these are all of type osgi.bundle.
					new OsgiIdentityCapability(
							this,
							resource.getSymbolicName(),
							resource.getVersion()));
		}
		// TODO Add to constants.
		if (namespace == null || namespace.equals("osgi.content")) {
			result.add(
					new OsgiContentCapability(
							this,
							resource.getURI()));
		}
		for (org.apache.felix.bundlerepository.Capability capability : capabilities) {
			if (namespace != null && !capability.getName().equals(namespace)) continue;
			result.add(new FelixCapabilityAdapter(capability, this));
		}
		result.trimToSize();
		return result;
	}
	
	@Override
	public InputStream getContent() {
		try {
			return new URL(resource.getURI()).openStream();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public List<Requirement> getRequirements(String namespace) {
		namespace = toFelixNamespace(namespace);
		org.apache.felix.bundlerepository.Requirement[] requirements = resource.getRequirements();
		ArrayList<Requirement> result = new ArrayList<Requirement>(requirements.length);
		for (final org.apache.felix.bundlerepository.Requirement requirement : requirements) {
			if (namespace == null || requirement.getName().equals(namespace)) 
				result.add(new FelixRequirementAdapter(requirement, this));
		}
		result.trimToSize();
		return result;
	}
}
