package org.apache.aries.subsystem.core.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.aries.subsystem.core.archive.DeploymentManifest;
import org.apache.aries.subsystem.core.archive.Header;
import org.apache.aries.subsystem.core.archive.ImportPackageHeader;
import org.apache.aries.subsystem.core.archive.RequireBundleHeader;
import org.apache.aries.subsystem.core.archive.RequireCapabilityHeader;
import org.apache.aries.subsystem.core.archive.SubsystemContentHeader;
import org.apache.aries.subsystem.core.archive.SubsystemManifest;
import org.apache.aries.subsystem.core.archive.SubsystemSymbolicNameHeader;
import org.apache.aries.subsystem.core.archive.SubsystemTypeHeader;
import org.apache.aries.subsystem.core.archive.SubsystemVersionHeader;
import org.apache.aries.util.filesystem.FileSystem;
import org.apache.aries.util.filesystem.IDirectory;
import org.apache.aries.util.filesystem.IFile;
import org.apache.aries.util.io.IOUtils;
import org.apache.aries.util.manifest.ManifestProcessor;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.service.repository.Repository;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.subsystem.Subsystem.State;
import org.osgi.service.subsystem.SubsystemConstants;

public class RawSubsystemResource implements Resource {
	private static final Pattern PATTERN = Pattern.compile("([^@]+)(?:@(.+))?.esa");
	
	private static SubsystemManifest computeExistingSubsystemManifest(IDirectory directory) throws IOException {
		Manifest manifest = ManifestProcessor.obtainManifestFromAppDir(directory, "OSGI-INF/SUBSYSTEM.MF");
		if (manifest == null)
			return null;
		return new SubsystemManifest(manifest);
	}
	
	private static SubsystemManifest computeNewSubsystemManifest() {
		return new SubsystemManifest.Builder().build();
	}
	
	private static SubsystemManifest computeSubsystemManifest(IDirectory directory) throws IOException {
		SubsystemManifest result = computeExistingSubsystemManifest(directory);
		if (result == null)
			result = computeNewSubsystemManifest();
		return result;
	}
	
	private static String convertFileToLocation(IFile file) throws MalformedURLException {
		String result = convertFileNameToLocation(file.getName());
		if (result == null)
			result = file.toURL().toString();
		return result;
	}
	
	private static String convertFileNameToLocation(String fileName) {
		Matcher matcher = PATTERN.matcher(fileName);
		if (!matcher.matches())
			return null;
		String version = matcher.group(2);
		return new SubsystemUri(matcher.group(1), version == null ? null
				: Version.parseVersion(version), null).toString();
	}
	
	private final List<Capability> capabilities;
	private final DeploymentManifest deploymentManifest;
	private final Repository localRepository;
	private final Location location;
	private final List<Requirement> requirements;
	private final Collection<Resource> resources;
	private final SubsystemManifest subsystemManifest;
	
	public RawSubsystemResource(String location, InputStream content) throws URISyntaxException, IOException, UnsupportedOperationException, ResolutionException {
		this.location = new Location(location);
		if (content == null)
			content = this.location.open();
		IDirectory idir = FileSystem.getFSRoot(content);
		try {
			resources = computeResources(idir);
			localRepository = computeLocalRepository();
			SubsystemManifest manifest = computeSubsystemManifest(idir);
			manifest = computeSubsystemManifestBeforeRequirements(manifest);
			requirements = computeRequirements(manifest);
			subsystemManifest = computeSubsystemManifestAfterRequirements(manifest);
			capabilities = computeCapabilities();
			deploymentManifest = computeDeploymentManifest(idir);
		}
		finally {
			IOUtils.close(idir.toCloseable());
		}
	}
	
	public RawSubsystemResource(File file) throws IOException, URISyntaxException, ResolutionException {
		this(FileSystem.getFSRoot(file));
	}
	
	public RawSubsystemResource(IDirectory idir) throws IOException, URISyntaxException, ResolutionException {
		resources = Collections.emptyList();
		localRepository = computeLocalRepository();
		subsystemManifest = initializeSubsystemManifest(idir);
		requirements = subsystemManifest.toRequirements(this);
		capabilities = subsystemManifest.toCapabilities(this);
		deploymentManifest = initializeDeploymentManifest(idir);
		location = new Location(deploymentManifest.getHeaders().get(DeploymentManifest.ARIESSUBSYSTEM_LOCATION).getValue());
	}
	
	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof RawSubsystemResource))
			return false;
		RawSubsystemResource that = (RawSubsystemResource)o;
		return getLocation().equals(that.getLocation());
	}

	@Override
	public List<Capability> getCapabilities(String namespace) {
		if (namespace == null)
			return Collections.unmodifiableList(capabilities);
		ArrayList<Capability> result = new ArrayList<Capability>(capabilities.size());
		for (Capability capability : capabilities)
			if (namespace.equals(capability.getNamespace()))
				result.add(capability);
		result.trimToSize();
		return Collections.unmodifiableList(result);
	}
	
	public DeploymentManifest getDeploymentManifest() {
		return deploymentManifest;
	}
	
	public Repository getLocalRepository() {
		return localRepository;
	}
	
	public Location getLocation() {
		return location;
	}

	@Override
	public List<Requirement> getRequirements(String namespace) {
		if (namespace == null)
			return Collections.unmodifiableList(requirements);
		ArrayList<Requirement> result = new ArrayList<Requirement>(requirements.size());
		for (Requirement requirement : requirements)
			if (namespace.equals(requirement.getNamespace()))
				result.add(requirement);
		result.trimToSize();
		return Collections.unmodifiableList(result);
	}
	
	public Collection<Resource> getResources() {
		return Collections.unmodifiableCollection(resources);
	}
	
	public SubsystemManifest getSubsystemManifest() {
		return subsystemManifest;
	}
	
	@Override
	public int hashCode() {
		int result = 17;
		result = 31 * result + getLocation().hashCode();
		return result;
	}
	
	private void addHeader(SubsystemManifest.Builder builder, Header<?> header) {
		if (header == null)
			return;
		builder.header(header);
	}
	
	private void addImportPackageHeader(SubsystemManifest.Builder builder) {
		addHeader(builder, computeImportPackageHeader());
	}
	
	private void addRequireBundleHeader(SubsystemManifest.Builder builder) {
		addHeader(builder, computeRequireBundleHeader());
	}
	
	private void addRequireCapabilityHeader(SubsystemManifest.Builder builder) {
		addHeader(builder, computeRequireCapabilityHeader());
	}
	
	private void addSubsystemContentHeader(SubsystemManifest.Builder builder, SubsystemManifest manifest) {
		SubsystemContentHeader header = computeSubsystemContentHeader(manifest);
		if (header == null)
			return;
		addHeader(builder, header);
	}
	
	private void addSubsystemSymbolicNameHeader(SubsystemManifest.Builder builder, SubsystemManifest manifest) {
		addHeader(builder, computeSubsystemSymbolicNameHeader(manifest));
	}
	
	private void addSubsystemVersionHeader(SubsystemManifest.Builder builder, SubsystemManifest manifest) {
		addHeader(builder, computeSubsystemVersionHeader(manifest));
	}
	
	private List<Capability> computeCapabilities() {
		return subsystemManifest.toCapabilities(this);
	}
	
	private DeploymentManifest computeDeploymentManifest(IDirectory directory) throws IOException {
		return computeExistingDeploymentManifest(directory);
	}
	
	private DeploymentManifest computeExistingDeploymentManifest(IDirectory directory) throws IOException {
		Manifest manifest = ManifestProcessor.obtainManifestFromAppDir(directory, "OSGI-INF/DEPLOYMENT.MF");
		if (manifest == null)
			return null;
		return new DeploymentManifest(manifest);
	}
	
	private ImportPackageHeader computeImportPackageHeader() {
		if (requirements.isEmpty())
			return null;
		ArrayList<ImportPackageHeader.Clause> clauses = new ArrayList<ImportPackageHeader.Clause>(requirements.size());
		for (Requirement requirement : requirements) {
			if (!PackageNamespace.PACKAGE_NAMESPACE.equals(requirement.getNamespace()))
				continue;
			clauses.add(new ImportPackageHeader.Clause(requirement));
		}
		if (clauses.isEmpty())
			return null;
		clauses.trimToSize();
		return new ImportPackageHeader(clauses);
	}
	
	private Repository computeLocalRepository() {
		return new LocalRepository(resources);
	}
	
	private RequireBundleHeader computeRequireBundleHeader() {
		if (requirements.isEmpty())
			return null;
		ArrayList<RequireBundleHeader.Clause> clauses = new ArrayList<RequireBundleHeader.Clause>(requirements.size());
		for (Requirement requirement : requirements) {
			if (!BundleNamespace.BUNDLE_NAMESPACE.equals(requirement.getNamespace()))
				continue;
			clauses.add(new RequireBundleHeader.Clause(requirement));
		}
		if (clauses.isEmpty())
			return null;
		clauses.trimToSize();
		return new RequireBundleHeader(clauses);
	}
	
	private RequireCapabilityHeader computeRequireCapabilityHeader() {
		if (requirements.isEmpty())
			return null;
		ArrayList<RequireCapabilityHeader.Clause> clauses = new ArrayList<RequireCapabilityHeader.Clause>();
		for (Requirement requirement : requirements) {
			if (requirement.getNamespace().startsWith("osgi."))
				continue;
			clauses.add(new RequireCapabilityHeader.Clause(requirement));
		}
		if (clauses.isEmpty())
			return null;
		clauses.trimToSize();
		return new RequireCapabilityHeader(clauses);
	}
	
	private List<Requirement> computeRequirements(SubsystemManifest manifest) throws ResolutionException {
		if (isComposite(manifest))
			return manifest.toRequirements(this);
		SubsystemContentHeader header = manifest.getSubsystemContentHeader();
		if (header == null)
			return Collections.emptyList();
		// TODO Need the system repository in here. Preferred provider as well?
		LocalRepository localRepo = new LocalRepository(resources);
		RepositoryServiceRepository serviceRepo = new RepositoryServiceRepository(Activator.getInstance().getBundleContext());
		CompositeRepository compositeRepo = new CompositeRepository(localRepo, serviceRepo);
		List<Requirement> requirements = header.toRequirements(this);
		List<Resource> resources = new ArrayList<Resource>(requirements.size());
		for (Requirement requirement : requirements) {
			Collection<Capability> capabilities = compositeRepo.findProviders(requirement);
			if (capabilities.isEmpty())
				continue;
			resources.add(capabilities.iterator().next().getResource());
		}
		return new DependencyCalculator(resources).calculateDependencies();
	}
	
	private Collection<Resource> computeResources(IDirectory directory) throws IOException, URISyntaxException, ResolutionException {
		List<IFile> files = directory.listFiles();
		if (files.isEmpty())
			return Collections.emptyList();
		ArrayList<Resource> result = new ArrayList<Resource>(files.size());
		for (IFile file : directory.listFiles()) {
			String name = file.getName();
			if (name.endsWith(".jar"))
				result.add(BundleResource.newInstance(file.toURL()));
			else if (name.endsWith(".esa"))
				result.add(new RawSubsystemResource(convertFileToLocation(file), file.open()));
		}
		result.trimToSize();
		return result;
	}
	
	private SubsystemContentHeader computeSubsystemContentHeader(SubsystemManifest manifest) {
		SubsystemContentHeader header = manifest.getSubsystemContentHeader();
		if (header == null && !resources.isEmpty())
			header = SubsystemContentHeader.newInstance(resources);
		return header;
	}
	
	private SubsystemManifest computeSubsystemManifestAfterRequirements(SubsystemManifest manifest) {
		if (isComposite(manifest))
			return manifest;
		SubsystemManifest.Builder builder = new SubsystemManifest.Builder().manifest(manifest);
		addImportPackageHeader(builder);
		addRequireBundleHeader(builder);
		addRequireCapabilityHeader(builder);
		return builder.build();
	}
	
	private SubsystemManifest computeSubsystemManifestBeforeRequirements(SubsystemManifest manifest) {
		SubsystemManifest.Builder builder = new SubsystemManifest.Builder().manifest(manifest);
		addSubsystemSymbolicNameHeader(builder, manifest);
		addSubsystemVersionHeader(builder, manifest);
		addSubsystemContentHeader(builder, manifest);
		return builder.build();
	}
	
	private SubsystemSymbolicNameHeader computeSubsystemSymbolicNameHeader(SubsystemManifest manifest) {
		SubsystemSymbolicNameHeader header = manifest.getSubsystemSymbolicNameHeader();
		if (header == null)
			header = new SubsystemSymbolicNameHeader(location.getSymbolicName());
		return header;
	}
	
	private SubsystemVersionHeader computeSubsystemVersionHeader(SubsystemManifest manifest) {
		SubsystemVersionHeader header = manifest.getSubsystemVersionHeader();
		if (header.getVersion().equals(Version.emptyVersion) && location.getVersion() != null)
			header = new SubsystemVersionHeader(location.getVersion());
		return header;
	}
	
	private DeploymentManifest initializeDeploymentManifest(IDirectory idir)
			throws IOException {
		Manifest manifest = ManifestProcessor.obtainManifestFromAppDir(idir,
				"OSGI-INF/DEPLOYMENT.MF");
		if (manifest != null)
			return new DeploymentManifest(manifest);
		else
			return new DeploymentManifest.Builder()
					.manifest(getSubsystemManifest())
					.location(AriesSubsystem.ROOT_LOCATION).autostart(true).id(0)
					.lastId(SubsystemIdentifier.getLastId())
					.region(AriesSubsystem.ROOT_REGION).state(State.INSTALLING)
					.build();
	}
	
	private SubsystemManifest initializeSubsystemManifest(IDirectory idir)
			throws IOException {
		Manifest manifest = ManifestProcessor.obtainManifestFromAppDir(idir,
				"OSGI-INF/SUBSYSTEM.MF");
		if (manifest != null)
			return new SubsystemManifest(manifest);
		else
			return new SubsystemManifest.Builder()
					.symbolicName(AriesSubsystem.ROOT_SYMBOLIC_NAME)
					.version(AriesSubsystem.ROOT_VERSION)
					.type(SubsystemTypeHeader.TYPE_APPLICATION
							+ ';'
							+ SubsystemTypeHeader.DIRECTIVE_PROVISION_POLICY
							+ ":="
							+ SubsystemTypeHeader.PROVISION_POLICY_ACCEPT_DEPENDENCIES)
					.build();
	}
	
	private boolean isComposite(SubsystemManifest manifest) {
		return SubsystemConstants.SUBSYSTEM_TYPE_COMPOSITE.equals(manifest.getSubsystemTypeHeader().getType());
	}
}
