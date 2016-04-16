/*******************************************************************************
 * Copyright (c) 2012, 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.container;

import java.security.Permission;
import java.util.*;
import org.apache.felix.resolver.*;
import org.eclipse.osgi.container.ModuleRequirement.DynamicModuleRequirement;
import org.eclipse.osgi.container.namespaces.EquinoxFragmentNamespace;
import org.eclipse.osgi.internal.container.InternalUtils;
import org.eclipse.osgi.internal.debug.Debug;
import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.internal.framework.EquinoxContainer;
import org.eclipse.osgi.internal.messages.Msg;
import org.eclipse.osgi.report.resolution.ResolutionReport;
import org.eclipse.osgi.report.resolution.ResolutionReport.Entry;
import org.eclipse.osgi.report.resolution.ResolutionReport.Entry.Type;
import org.eclipse.osgi.service.debug.DebugOptions;
import org.osgi.framework.BundleException;
import org.osgi.framework.Version;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.namespace.*;
import org.osgi.framework.wiring.*;
import org.osgi.resource.*;
import org.osgi.service.resolver.*;

/**
 * The module resolver handles calls to the {@link Resolver} service for resolving modules
 * in a module {@link ModuleContainer container}.
 */
final class ModuleResolver {
	static final String SEPARATOR = System.getProperty("line.separator"); //$NON-NLS-1$
	static final char TAB = '\t';

	private static final String OPTION_RESOLVER = EquinoxContainer.NAME + "/resolver"; //$NON-NLS-1$
	private static final String OPTION_ROOTS = OPTION_RESOLVER + "/roots"; //$NON-NLS-1$
	private static final String OPTION_PROVIDERS = OPTION_RESOLVER + "/providers"; //$NON-NLS-1$
	private static final String OPTION_HOOKS = OPTION_RESOLVER + "/hooks"; //$NON-NLS-1$
	private static final String OPTION_USES = OPTION_RESOLVER + "/uses"; //$NON-NLS-1$
	private static final String OPTION_WIRING = OPTION_RESOLVER + "/wiring"; //$NON-NLS-1$
	private static final String OPTION_REPORT = OPTION_RESOLVER + "/report"; //$NON-NLS-1$

	boolean DEBUG_ROOTS = false;
	boolean DEBUG_PROVIDERS = false;
	boolean DEBUG_HOOKS = false;
	boolean DEBUG_USES = false;
	boolean DEBUG_WIRING = false;
	boolean DEBUG_REPORT = false;

	private final int DEFAULT_BATCH_SIZE = 1;
	final int resolverRevisionBatchSize;

	void setDebugOptions() {
		DebugOptions options = adaptor.getDebugOptions();
		// may be null if debugging is not enabled
		if (options == null)
			return;
		boolean debugAll = options.getBooleanOption(OPTION_RESOLVER, false);
		DEBUG_ROOTS = debugAll || options.getBooleanOption(OPTION_ROOTS, false);
		DEBUG_PROVIDERS = debugAll || options.getBooleanOption(OPTION_PROVIDERS, false);
		DEBUG_HOOKS = debugAll || options.getBooleanOption(OPTION_HOOKS, false);
		DEBUG_USES = debugAll || options.getBooleanOption(OPTION_USES, false);
		DEBUG_WIRING = debugAll || options.getBooleanOption(OPTION_WIRING, false);
		DEBUG_REPORT = debugAll || options.getBooleanOption(OPTION_REPORT, false);
	}

	private static final Collection<String> NON_PAYLOAD_CAPABILITIES = Arrays.asList(IdentityNamespace.IDENTITY_NAMESPACE);
	static final Collection<String> NON_PAYLOAD_REQUIREMENTS = Arrays.asList(HostNamespace.HOST_NAMESPACE, ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE);

	final ThreadLocal<Boolean> threadResolving = new ThreadLocal<Boolean>() {
		@Override
		protected Boolean initialValue() {
			return Boolean.FALSE;
		}
	};
	final ModuleContainerAdaptor adaptor;

	/**
	 * Constructs the module resolver with the specified resolver hook factory
	 * and resolver.
	 * @param adaptor the container adaptor
	 */
	ModuleResolver(ModuleContainerAdaptor adaptor) {
		this.adaptor = adaptor;
		setDebugOptions();
		String batchSizeConfig = this.adaptor.getProperty(EquinoxConfiguration.PROP_RESOLVER_REVISION_BATCH_SIZE);
		int tempBatchSize;
		try {
			tempBatchSize = batchSizeConfig == null ? DEFAULT_BATCH_SIZE : Integer.parseInt(batchSizeConfig);
		} catch (NumberFormatException e) {
			tempBatchSize = DEFAULT_BATCH_SIZE;
		}
		if (tempBatchSize < 1) {
			tempBatchSize = DEFAULT_BATCH_SIZE;
		}
		this.resolverRevisionBatchSize = tempBatchSize;
	}

	/**
	 * Attempts to resolve all unresolved modules installed in the specified module database.
	 * returns a delta containing the new wirings or modified wirings that should be 
	 * merged into the specified moduleDatabase.
	 * <p>
	 * This method only does read operations on the database no wirings are modified
	 * directly by this method.  The returned wirings need to be merged into 
	 * the database.
	 * @param triggers the triggers that caused the resolver operation to occur
	 * @param triggersMandatory true if the triggers must be resolved by the resolve process
	 * @param unresolved a snapshot of unresolved revisions
	 * @param wiringCopy the wirings snapshot of the currently resolved revisions
	 * @param moduleDatabase the module database.
	 * @return a delta container the new wirings or modified wirings that should be
	 * merged into the moduleDatabase
	 * @throws ResolutionException
	 */
	ModuleResolutionReport resolveDelta(Collection<ModuleRevision> triggers, boolean triggersMandatory, Collection<ModuleRevision> unresolved, Map<ModuleRevision, ModuleWiring> wiringCopy, ModuleDatabase moduleDatabase) {
		ResolveProcess resolveProcess = new ResolveProcess(unresolved, triggers, triggersMandatory, wiringCopy, moduleDatabase);
		return resolveProcess.resolve();
	}

	ModuleResolutionReport resolveDynamicDelta(DynamicModuleRequirement dynamicReq, Collection<ModuleRevision> unresolved, Map<ModuleRevision, ModuleWiring> wiringCopy, ModuleDatabase moduleDatabase) {
		ResolveProcess resolveProcess = new ResolveProcess(unresolved, dynamicReq, wiringCopy, moduleDatabase);
		return resolveProcess.resolve();
	}

	Map<ModuleRevision, ModuleWiring> generateDelta(Map<Resource, List<Wire>> result, Map<ModuleRevision, ModuleWiring> wiringCopy) {
		Map<ModuleRevision, Map<ModuleCapability, List<ModuleWire>>> provided = new HashMap<ModuleRevision, Map<ModuleCapability, List<ModuleWire>>>();
		Map<ModuleRevision, List<ModuleWire>> required = new HashMap<ModuleRevision, List<ModuleWire>>();
		// First populate the list of provided and required wires for revision
		// This is done this way to share the wire object between both the provider and requirer
		for (Map.Entry<Resource, List<Wire>> resultEntry : result.entrySet()) {
			ModuleRevision revision = (ModuleRevision) resultEntry.getKey();
			List<ModuleWire> requiredWires = new ArrayList<ModuleWire>(resultEntry.getValue().size());
			for (Wire wire : resultEntry.getValue()) {
				ModuleWire moduleWire = new ModuleWire((ModuleCapability) wire.getCapability(), (ModuleRevision) wire.getProvider(), (ModuleRequirement) wire.getRequirement(), (ModuleRevision) wire.getRequirer());
				requiredWires.add(moduleWire);
				Map<ModuleCapability, List<ModuleWire>> providedWiresMap = provided.get(moduleWire.getProvider());
				if (providedWiresMap == null) {
					providedWiresMap = new HashMap<ModuleCapability, List<ModuleWire>>();
					provided.put(moduleWire.getProvider(), providedWiresMap);
				}
				List<ModuleWire> providedWires = providedWiresMap.get(moduleWire.getCapability());
				if (providedWires == null) {
					providedWires = new ArrayList<ModuleWire>();
					providedWiresMap.put(moduleWire.getCapability(), providedWires);
				}
				providedWires.add(moduleWire);
			}
			required.put(revision, requiredWires);
		}

		Map<ModuleRevision, ModuleWiring> delta = new HashMap<ModuleRevision, ModuleWiring>();
		// now create the ModuleWiring for the newly resolved revisions
		for (ModuleRevision revision : required.keySet()) {
			ModuleWiring existingWiring = wiringCopy.get(revision);
			if (existingWiring == null) {
				delta.put(revision, createNewWiring(revision, provided, required));
			} else {
				// this is to handle dynamic imports
				delta.put(revision, createWiringDelta(revision, existingWiring, provided.get(revision), required.get(revision)));
			}
		}
		// Also need to create the wiring deltas for already resolved bundles
		// This should only include updating provided wires and
		// for fragments it may include new hosts
		for (ModuleRevision revision : provided.keySet()) {
			ModuleWiring existingWiring = wiringCopy.get(revision);
			if (existingWiring != null && !delta.containsKey(revision)) {
				delta.put(revision, createWiringDelta(revision, existingWiring, provided.get(revision), required.get(revision)));
			}
		}
		return delta;
	}

	private ModuleWiring createNewWiring(ModuleRevision revision, Map<ModuleRevision, Map<ModuleCapability, List<ModuleWire>>> provided, Map<ModuleRevision, List<ModuleWire>> required) {
		Map<ModuleCapability, List<ModuleWire>> providedWireMap = provided.get(revision);
		if (providedWireMap == null)
			providedWireMap = Collections.emptyMap();
		List<ModuleWire> requiredWires = required.get(revision);
		if (requiredWires == null)
			requiredWires = Collections.emptyList();

		List<ModuleCapability> capabilities = new ArrayList<ModuleCapability>(revision.getModuleCapabilities(null));
		ListIterator<ModuleCapability> iCapabilities = capabilities.listIterator(capabilities.size());
		List<ModuleRequirement> requirements = new ArrayList<ModuleRequirement>(revision.getModuleRequirements(null));
		ListIterator<ModuleRequirement> iRequirements = requirements.listIterator(requirements.size());

		// if revision is a fragment remove payload requirements and capabilities
		if ((BundleRevision.TYPE_FRAGMENT & revision.getTypes()) != 0) {
			removePayloadContent(iCapabilities, iRequirements);
		} else {
			// add fragment capabilities and requirements
			List<ModuleCapability> hostCapabilities = revision.getModuleCapabilities(HostNamespace.HOST_NAMESPACE);
			ModuleCapability hostCapability = hostCapabilities.isEmpty() ? null : hostCapabilities.get(0);
			if (hostCapability != null) {
				addPayloadContent(providedWireMap.get(hostCapability), iCapabilities, iRequirements);
			}
		}

		removeNonEffectiveCapabilities(iCapabilities);
		removeNonEffectiveRequirements(iRequirements, requiredWires);
		Collection<String> substituted = removeSubstitutedCapabilities(iCapabilities, requiredWires);

		List<ModuleWire> providedWires = new ArrayList<ModuleWire>();
		addProvidedWires(providedWireMap, providedWires, capabilities);

		InternalUtils.filterCapabilityPermissions(capabilities);
		return new ModuleWiring(revision, capabilities, requirements, providedWires, requiredWires, substituted);
	}

	private static void removePayloadContent(ListIterator<ModuleCapability> iCapabilities, ListIterator<ModuleRequirement> iRequirements) {
		rewind(iCapabilities);
		while (iCapabilities.hasNext()) {
			if (!NON_PAYLOAD_CAPABILITIES.contains(iCapabilities.next().getNamespace())) {
				iCapabilities.remove();
			}
		}

		rewind(iRequirements);
		while (iRequirements.hasNext()) {
			if (!NON_PAYLOAD_REQUIREMENTS.contains(iRequirements.next().getNamespace())) {
				iRequirements.remove();
			}
		}
	}

	private static Collection<String> removeSubstitutedCapabilities(ListIterator<ModuleCapability> iCapabilities, List<ModuleWire> requiredWires) {
		Collection<String> substituted = null;
		for (ModuleWire moduleWire : requiredWires) {
			if (!PackageNamespace.PACKAGE_NAMESPACE.equals(moduleWire.getCapability().getNamespace()))
				continue;
			String packageName = (String) moduleWire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
			rewind(iCapabilities);
			while (iCapabilities.hasNext()) {
				ModuleCapability capability = iCapabilities.next();
				if (PackageNamespace.PACKAGE_NAMESPACE.equals(capability.getNamespace())) {
					if (packageName.equals(capability.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE))) {
						// found a package capability with the same name as a package that got imported
						// this indicates a substitution
						iCapabilities.remove();
						if (substituted == null) {
							substituted = new ArrayList<String>();
						}
						substituted.add(packageName);
						if (!substituted.contains(packageName)) {
							substituted.add(packageName);
						}
					}
				}
			}
		}
		return substituted == null ? Collections.<String> emptyList() : substituted;
	}

	private static void removeNonEffectiveRequirements(ListIterator<ModuleRequirement> iRequirements, List<ModuleWire> requiredWires) {
		rewind(iRequirements);
		while (iRequirements.hasNext()) {
			ModuleRequirement requirement = iRequirements.next();
			// check the effective directive;
			Object effective = requirement.getDirectives().get(Namespace.REQUIREMENT_EFFECTIVE_DIRECTIVE);
			if (effective != null && !Namespace.EFFECTIVE_RESOLVE.equals(effective)) {
				iRequirements.remove();
			} else {
				// check the resolution directive
				Object resolution = requirement.getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE);
				if (Namespace.RESOLUTION_OPTIONAL.equals(resolution)) {
					boolean found = false;
					// need to check the wires to see if the optional requirement is resolved
					wires: for (ModuleWire wire : requiredWires) {
						if (wire.getRequirement().equals(requirement)) {
							found = true;
							break wires;
						}
					}
					if (!found) {
						// optional requirement is not resolved
						iRequirements.remove();
					}
				}
			}
		}
	}

	void removeNonEffectiveCapabilities(ListIterator<ModuleCapability> iCapabilities) {
		rewind(iCapabilities);
		while (iCapabilities.hasNext()) {
			Capability capability = iCapabilities.next();
			Object effective = capability.getDirectives().get(Namespace.CAPABILITY_EFFECTIVE_DIRECTIVE);
			if (effective != null && !Namespace.EFFECTIVE_RESOLVE.equals(effective)) {
				iCapabilities.remove();
				if (DEBUG_PROVIDERS) {
					Debug.println(new StringBuilder("RESOLVER: Capability filtered because it was not effective") //$NON-NLS-1$
							.append(SEPARATOR).append(TAB) //
							.append(capability) //
							.append(SEPARATOR).append(TAB).append(TAB) //
							.append("of resource") //$NON-NLS-1$
							.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
							.append(capability.getResource()) //
							.toString());
				}
			}
		}
	}

	private static void addPayloadContent(List<ModuleWire> hostWires, ListIterator<ModuleCapability> iCapabilities, ListIterator<ModuleRequirement> iRequirements) {
		if (hostWires == null)
			return;
		for (ModuleWire hostWire : hostWires) {
			// add fragment capabilities
			String currentNamespace = null;
			List<ModuleCapability> fragmentCapabilities = hostWire.getRequirer().getModuleCapabilities(null);
			for (ModuleCapability fragmentCapability : fragmentCapabilities) {
				if (NON_PAYLOAD_CAPABILITIES.contains(fragmentCapability.getNamespace())) {
					continue; // don't include, not a payload capability
				}
				if (!fragmentCapability.getNamespace().equals(currentNamespace)) {
					currentNamespace = fragmentCapability.getNamespace();
					fastForward(iCapabilities);
					while (iCapabilities.hasPrevious()) {
						if (iCapabilities.previous().getNamespace().equals(currentNamespace)) {
							iCapabilities.next(); // put position after the last one
							break;
						}
					}
				}
				iCapabilities.add(fragmentCapability);
			}
			// add fragment requirements
			currentNamespace = null;
			List<ModuleRequirement> fragmentRequriements = hostWire.getRequirer().getModuleRequirements(null);
			for (ModuleRequirement fragmentRequirement : fragmentRequriements) {
				if (NON_PAYLOAD_REQUIREMENTS.contains(fragmentRequirement.getNamespace())) {
					continue; // don't inlcude, not a payload requirement
				}
				if (!fragmentRequirement.getNamespace().equals(currentNamespace)) {
					currentNamespace = fragmentRequirement.getNamespace();
					boolean isDynamic = isDynamic(fragmentRequirement);
					fastForward(iRequirements);
					while (iRequirements.hasPrevious()) {
						ModuleRequirement previous = iRequirements.previous();
						if (previous.getNamespace().equals(currentNamespace)) {
							if (isDynamic || !isDynamic(previous)) {
								iRequirements.next(); // put position after the last one
								break;
							}
						}
					}
				}
				iRequirements.add(fragmentRequirement);
			}
		}
	}

	static boolean isDynamic(Requirement requirement) {
		return PackageNamespace.PACKAGE_NAMESPACE.equals(requirement.getNamespace()) && PackageNamespace.RESOLUTION_DYNAMIC.equals(requirement.getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE));
	}

	private static void addProvidedWires(Map<ModuleCapability, List<ModuleWire>> toAdd, List<ModuleWire> existing, final List<ModuleCapability> orderedCapabilities) {
		if (toAdd == null)
			return;
		int originalSize = existing.size();
		for (ModuleCapability capability : orderedCapabilities) {
			List<ModuleWire> newWires = toAdd.get(capability);
			if (newWires != null) {
				existing.addAll(newWires);
			}
		}
		if (originalSize != 0) {
			Collections.sort(existing, new Comparator<ModuleWire>() {
				@Override
				public int compare(ModuleWire w1, ModuleWire w2) {
					int index1 = orderedCapabilities.indexOf(w1.getCapability());
					int index2 = orderedCapabilities.indexOf(w2.getCapability());
					return index1 - index2;
				}
			});
		}
	}

	private static void addRequiredWires(List<ModuleWire> toAdd, List<ModuleWire> existing, final List<ModuleRequirement> orderedRequirements) {
		if (toAdd == null)
			return;
		int originalSize = existing.size();
		existing.addAll(toAdd);
		if (originalSize != 0) {
			Collections.sort(existing, new Comparator<ModuleWire>() {
				@Override
				public int compare(ModuleWire w1, ModuleWire w2) {
					int index1 = orderedRequirements.indexOf(w1.getRequirement());
					int index2 = orderedRequirements.indexOf(w2.getRequirement());
					return index1 - index2;
				}
			});
		}
	}

	private static void fastForward(ListIterator<?> listIterator) {
		while (listIterator.hasNext())
			listIterator.next();
	}

	static void rewind(ListIterator<?> listIterator) {
		while (listIterator.hasPrevious())
			listIterator.previous();
	}

	private static ModuleWiring createWiringDelta(ModuleRevision revision, ModuleWiring existingWiring, Map<ModuleCapability, List<ModuleWire>> providedWireMap, List<ModuleWire> requiredWires) {
		// No null checks are done here on the wires since this is a copy.
		List<ModuleWire> existingProvidedWires = existingWiring.getProvidedModuleWires(null);
		List<ModuleCapability> existingCapabilities = existingWiring.getModuleCapabilities(null);
		List<ModuleWire> existingRequiredWires = existingWiring.getRequiredModuleWires(null);
		List<ModuleRequirement> existingRequirements = existingWiring.getModuleRequirements(null);

		// First, add newly resolved fragment capabilities and requirements
		if (providedWireMap != null) {
			List<ModuleCapability> hostCapabilities = revision.getModuleCapabilities(HostNamespace.HOST_NAMESPACE);
			ModuleCapability hostCapability = hostCapabilities.isEmpty() ? null : hostCapabilities.get(0);
			List<ModuleWire> newHostWires = hostCapability == null ? null : providedWireMap.get(hostCapability);
			if (newHostWires != null) {
				addPayloadContent(newHostWires, existingCapabilities.listIterator(), existingRequirements.listIterator());
			}
		}

		// Create a ModuleWiring that only contains the new ordered list of provided wires
		addProvidedWires(providedWireMap, existingProvidedWires, existingCapabilities);

		// Also need to include any new required wires that may have be added for fragment hosts
		// Also will be needed for dynamic imports
		addRequiredWires(requiredWires, existingRequiredWires, existingRequirements);

		InternalUtils.filterCapabilityPermissions(existingCapabilities);
		return new ModuleWiring(revision, existingCapabilities, existingRequirements, existingProvidedWires, existingRequiredWires, Collections.EMPTY_LIST);
	}

	static boolean isSingleton(ModuleRevision revision) {
		List<Capability> identities = revision.getCapabilities(IdentityNamespace.IDENTITY_NAMESPACE);
		if (identities.isEmpty())
			return false;
		return "true".equals(identities.get(0).getDirectives().get(IdentityNamespace.CAPABILITY_SINGLETON_DIRECTIVE)); //$NON-NLS-1$
	}

	static Version getVersion(Capability c) {
		String versionAttr = null;
		String namespace = c.getNamespace();
		if (IdentityNamespace.IDENTITY_NAMESPACE.equals(namespace)) {
			versionAttr = IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE;
		} else if (PackageNamespace.PACKAGE_NAMESPACE.equals(namespace)) {
			versionAttr = PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE;
		} else if (BundleNamespace.BUNDLE_NAMESPACE.equals(namespace)) {
			versionAttr = BundleNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE;
		} else if (HostNamespace.HOST_NAMESPACE.equals(namespace)) {
			versionAttr = HostNamespace.CAPABILITY_BUNDLE_VERSION_ATTRIBUTE;
		} else {
			// Just default to version attribute
			versionAttr = IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE;
		}
		Object version = c.getAttributes().get(versionAttr);
		return version instanceof Version ? (Version) version : Version.emptyVersion;
	}

	class ResolveProcess extends ResolveContext implements Comparator<Capability>, FelixResolveContext {

		class ResolveLogger extends Logger {
			private Map<Resource, ResolutionException> errors = null;

			public ResolveLogger() {
				super(0);
			}

			@Override
			public void logUsesConstraintViolation(Resource resource, ResolutionException error) {
				if (errors == null) {
					errors = new HashMap<Resource, ResolutionException>();
				}
				errors.put(resource, error);
				if (DEBUG_USES) {
					Debug.println(new StringBuilder("RESOLVER: Uses constraint violation") //$NON-NLS-1$
							.append(SEPARATOR).append(TAB) //
							.append("Resource") //$NON-NLS-1$
							.append(SEPARATOR).append(TAB).append(TAB) //
							.append(resource) //
							.append(SEPARATOR).append(TAB) //
							.append("Error") //$NON-NLS-1$
							.append(SEPARATOR).append(TAB).append(TAB) //
							.append(error.getMessage()) //
							.toString());
				}
			}

			Map<Resource, ResolutionException> getUsesConstraintViolations() {
				return errors == null ? Collections.<Resource, ResolutionException> emptyMap() : errors;
			}
		}

		private final ModuleResolutionReport.Builder reportBuilder = new ModuleResolutionReport.Builder();
		/*
		 * Contains the revisions that were requested to be resolved and is not
		 * modified post instantiation.
		 */
		private final Collection<ModuleRevision> unresolved;
		/*
		 * Contains unresolved revisions that should not be resolved as part of
		 * this process. The reasons they should not be resolved will vary. For 
		 * example, some might have been filtered out by the resolver hook while
		 * others represent singleton collisions. It is assumed that all
		 * unresolved revisions are disabled at the start of the resolve
		 * process (see initialization in constructors). Any not filtered out
		 * by ResolverHook.filterResolvable are then removed but may be added 
		 * back later for other reasons.
		 */
		private final Collection<ModuleRevision> disabled;
		private final Collection<ModuleRevision> triggers;
		private final List<ModuleRevision> optionals;
		private final boolean triggersMandatory;
		private final ModuleDatabase moduleDatabase;
		private final Map<ModuleRevision, ModuleWiring> wirings;
		private final Set<ModuleRevision> previouslyResolved;
		private final DynamicModuleRequirement dynamicReq;
		private volatile ResolverHook hook = null;
		private volatile Map<String, Collection<ModuleRevision>> byName = null;
		private volatile List<Resource> currentlyResolving = null;
		private volatile boolean currentlyResolvingMandatory = false;
		private final Set<Resource> transitivelyResolveFailures = new LinkedHashSet<Resource>();
		private final Set<Resource> failedToResolve = new HashSet<Resource>();
		/*
		 * Used to generate the UNRESOLVED_PROVIDER resolution report entries.
		 * 
		 * The inner map associates a requirement to the set of all matching
		 * capabilities that were found. The outer map associates the requiring
		 * resource to the inner map so that its contents may easily be looked
		 * up from the set of unresolved resources, if any, after the resolution
		 * has occurred.
		 */
		private final Map<Resource, Map<Requirement, Set<Capability>>> unresolvedProviders = new HashMap<Resource, Map<Requirement, Set<Capability>>>();

		ResolveProcess(Collection<ModuleRevision> unresolved, Collection<ModuleRevision> triggers, boolean triggersMandatory, Map<ModuleRevision, ModuleWiring> wirings, ModuleDatabase moduleDatabase) {
			this.unresolved = unresolved;
			this.disabled = new HashSet<ModuleRevision>(unresolved);
			this.triggers = new ArrayList<ModuleRevision>(triggers);
			this.triggersMandatory = triggersMandatory;
			this.optionals = new ArrayList<ModuleRevision>(unresolved);
			if (this.triggersMandatory) {
				this.optionals.removeAll(triggers);
			}
			this.wirings = new HashMap<ModuleRevision, ModuleWiring>(wirings);
			this.previouslyResolved = new HashSet<ModuleRevision>(wirings.keySet());
			this.moduleDatabase = moduleDatabase;
			this.dynamicReq = null;
		}

		ResolveProcess(Collection<ModuleRevision> unresolved, DynamicModuleRequirement dynamicReq, Map<ModuleRevision, ModuleWiring> wirings, ModuleDatabase moduleDatabase) {
			this.unresolved = unresolved;
			this.disabled = new HashSet<ModuleRevision>(unresolved);
			ModuleRevision revision = dynamicReq.getRevision();
			this.triggers = new ArrayList<ModuleRevision>(1);
			this.triggers.add(revision);
			this.triggersMandatory = false;
			this.optionals = new ArrayList<ModuleRevision>(unresolved);
			this.wirings = wirings;
			this.previouslyResolved = new HashSet<ModuleRevision>(wirings.keySet());
			this.moduleDatabase = moduleDatabase;
			this.dynamicReq = dynamicReq;
		}

		@Override
		public List<Capability> findProviders(Requirement requirement) {
			return findProviders0(requirement, requirement);
		}

		private List<Capability> findProviders0(Requirement origReq, Requirement lookupReq) {
			if (DEBUG_PROVIDERS) {
				Debug.println(new StringBuilder("RESOLVER: Finding capabilities for requirement") //$NON-NLS-1$
						.append(SEPARATOR).append(TAB) //
						.append(origReq) //
						.append(SEPARATOR).append(TAB).append(TAB) //
						.append("of resource") //$NON-NLS-1$
						.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
						.append(origReq.getResource()) //
						.toString());
			}
			List<ModuleCapability> candidates = moduleDatabase.findCapabilities(lookupReq);
			List<Capability> result = filterProviders(origReq, candidates);
			if (DEBUG_PROVIDERS) {
				StringBuilder builder = new StringBuilder("RESOLVER: Capabilities being returned to the resolver"); //$NON-NLS-1$
				int i = 0;
				for (Capability capability : result) {
					builder.append(SEPARATOR).append(TAB) //
							.append("[").append(++i).append("] ") //$NON-NLS-1$ //$NON-NLS-2$
							.append(capability) //
							.append(SEPARATOR).append(TAB).append(TAB) //
							.append("of resource") //$NON-NLS-1$
							.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
							.append(capability.getResource());
				}
				Debug.println(builder.toString());
			}
			return result;
		}

		private List<Capability> filterProviders(Requirement requirement, List<ModuleCapability> candidates) {
			return filterProviders(requirement, candidates, true);
		}

		private List<Capability> filterProviders(Requirement requirement, List<ModuleCapability> candidates, boolean filterResolvedHosts) {
			ListIterator<ModuleCapability> iCandidates = candidates.listIterator();
			filterDisabled(iCandidates);
			removeNonEffectiveCapabilities(iCandidates);
			removeSubstituted(iCandidates);
			filterPermissions((BundleRequirement) requirement, iCandidates);

			List<ModuleCapability> filteredMatches = null;
			if (DEBUG_PROVIDERS || DEBUG_HOOKS) {
				filteredMatches = new ArrayList<ModuleCapability>(candidates);
			}
			hook.filterMatches((BundleRequirement) requirement, InternalUtils.asListBundleCapability(candidates));
			if (DEBUG_PROVIDERS || DEBUG_HOOKS) {
				filteredMatches.removeAll(candidates);
				if (!filteredMatches.isEmpty()) {
					StringBuilder builder = new StringBuilder("RESOLVER: Capabilities filtered by ResolverHook.filterMatches"); //$NON-NLS-1$
					int i = 0;
					for (Capability capability : filteredMatches) {
						builder.append(SEPARATOR).append(TAB) //
								.append("[").append(++i).append("] ") //$NON-NLS-1$ //$NON-NLS-2$
								.append(capability) //
								.append(SEPARATOR).append(TAB).append(TAB) //
								.append("of resource") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
								.append(capability.getResource());
					}
					Debug.println(builder.toString());
				}
			}

			// filter resolved hosts after calling hooks to allow hooks to see the host capability
			filterResolvedHosts(requirement, candidates, filterResolvedHosts);

			if (candidates.isEmpty()) {
				if (!wirings.containsKey(requirement.getResource()) || isDynamic(requirement)) {
					reportBuilder.addEntry(requirement.getResource(), Entry.Type.MISSING_CAPABILITY, requirement);
					String resolution = requirement.getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE);
					if ((resolution == null || Namespace.RESOLUTION_MANDATORY.equals(resolution))) {
						transitivelyResolveFailures.add(requirement.getResource());
					}
				}
			} else {
				computeUnresolvedProviders(requirement, candidates);
			}

			filterFailedToResolve(candidates);

			Collections.sort(candidates, this);
			return InternalUtils.asListCapability(candidates);
		}

		private void filterFailedToResolve(List<ModuleCapability> candidates) {
			for (Iterator<ModuleCapability> iCandidates = candidates.iterator(); iCandidates.hasNext();) {
				ModuleCapability capability = iCandidates.next();
				if (failedToResolve.contains(capability.getRevision())) {
					iCandidates.remove();
					if (DEBUG_PROVIDERS) {
						Debug.println(new StringBuilder("RESOLVER: Capability filtered because its resource was not resolved") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB) //
								.append(capability) //
								.append(SEPARATOR).append(TAB).append(TAB) //
								.append("of resource") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
								.append(capability.getResource()) //
								.toString());
					}
				}
			}
		}

		private void filterResolvedHosts(Requirement requirement, List<ModuleCapability> candidates, boolean filterResolvedHosts) {
			if (filterResolvedHosts && HostNamespace.HOST_NAMESPACE.equals(requirement.getNamespace())) {
				for (Iterator<ModuleCapability> iCandidates = candidates.iterator(); iCandidates.hasNext();) {
					if (wirings.containsKey(iCandidates.next().getRevision())) {
						iCandidates.remove();
					}
				}
			}
		}

		private void filterPermissions(BundleRequirement requirement, ListIterator<ModuleCapability> iCandidates) {
			rewind(iCandidates);
			if (System.getSecurityManager() == null || !iCandidates.hasNext()) {
				return;
			}

			if (requirement.getRevision().getBundle() == null) {
				// this container is not modeling a real framework, no permission check is done
				return;
			}

			candidates: while (iCandidates.hasNext()) {
				ModuleCapability candidate = iCandidates.next();
				// TODO this is a hack for when a bundle imports and exports the same package
				if (PackageNamespace.PACKAGE_NAMESPACE.equals(requirement.getNamespace())) {
					if (requirement.getRevision().equals(candidate.getRevision())) {
						continue candidates;
					}
				}
				Permission requirePermission = InternalUtils.getRequirePermission(candidate);
				Permission providePermission = InternalUtils.getProvidePermission(candidate);
				if (!requirement.getRevision().getBundle().hasPermission(requirePermission)) {
					iCandidates.remove();
					if (DEBUG_PROVIDERS) {
						Debug.println(new StringBuilder("RESOLVER: Capability filtered because requirer did not have permission") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB) //
								.append(candidate) //
								.append(SEPARATOR).append(TAB).append(TAB) //
								.append("of resource") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
								.append(candidate.getResource()) //
								.toString());
					}
				} else if (!candidate.getRevision().getBundle().hasPermission(providePermission)) {
					iCandidates.remove();
					if (DEBUG_PROVIDERS) {
						Debug.println(new StringBuilder("RESOLVER: Capability filtered because provider did not have permission") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB) //
								.append(candidate) //
								.append(SEPARATOR).append(TAB).append(TAB) //
								.append("of resource") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
								.append(candidate.getResource()) //
								.toString());
					}
				}
			}
		}

		private void filterDisabled(ListIterator<ModuleCapability> iCandidates) {
			rewind(iCandidates);
			while (iCandidates.hasNext()) {
				Capability capability = iCandidates.next();
				if (disabled.contains(capability.getResource())) {
					iCandidates.remove();
					if (DEBUG_PROVIDERS) {
						Debug.println(new StringBuilder("RESOLVER: Capability filtered because it was disabled") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB) //
								.append(capability) //
								.append(SEPARATOR).append(TAB).append(TAB) //
								.append("of resource") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
								.append(capability.getResource()) //
								.toString());
					}
				}
			}
		}

		private void removeSubstituted(ListIterator<ModuleCapability> iCapabilities) {
			rewind(iCapabilities);
			while (iCapabilities.hasNext()) {
				ModuleCapability capability = iCapabilities.next();
				ModuleWiring wiring = wirings.get(capability.getRevision());
				if (wiring != null && wiring.isSubtituted(capability)) {
					iCapabilities.remove();
					if (DEBUG_PROVIDERS) {
						Debug.println(new StringBuilder("RESOLVER: Capability filtered because it was substituted") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB) //
								.append(capability) //
								.append(SEPARATOR).append(TAB).append(TAB) //
								.append("of resource") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB).append(TAB).append(TAB) //
								.append(capability.getResource()) //
								.toString());
					}
				}
			}
		}

		@Override
		public int insertHostedCapability(List<Capability> capabilities, HostedCapability hostedCapability) {
			int index = Collections.binarySearch(capabilities, hostedCapability, this);
			if (index < 0)
				index = -index - 1;
			capabilities.add(index, hostedCapability);
			return index;
		}

		@Override
		public boolean isEffective(Requirement requirement) {
			String effective = requirement.getDirectives().get(Namespace.REQUIREMENT_EFFECTIVE_DIRECTIVE);
			return effective == null || Namespace.EFFECTIVE_RESOLVE.equals(effective);
		}

		@SuppressWarnings("unchecked")
		@Override
		public Map<Resource, Wiring> getWirings() {
			Map<?, ?> raw = wirings;
			return Collections.unmodifiableMap((Map<Resource, Wiring>) raw);
		}

		@Override
		public Collection<Resource> getMandatoryResources() {
			if (currentlyResolvingMandatory) {
				return Collections.unmodifiableList(currentlyResolving);
			}
			return Collections.emptyList();
		}

		@Override
		public Collection<Resource> getOptionalResources() {
			if (!currentlyResolvingMandatory) {
				return Collections.unmodifiableList(currentlyResolving);
			}
			return Collections.emptyList();
		}

		@Override
		public Collection<Resource> getOndemandResources(Resource host) {
			String hostBSN = ((ModuleRevision) host).getSymbolicName();
			if (hostBSN == null) {
				return Collections.emptyList();
			}
			List<ModuleCapability> hostCaps = ((ModuleRevision) host).getModuleCapabilities(HostNamespace.HOST_NAMESPACE);
			if (hostCaps.isEmpty()) {
				return Collections.emptyList();
			}
			ModuleCapability hostCap = hostCaps.get(0);
			String matchFilter = "(" + EquinoxFragmentNamespace.FRAGMENT_NAMESPACE + "=" + hostBSN + ")"; //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
			Requirement fragmentRequirement = ModuleContainer.createRequirement(EquinoxFragmentNamespace.FRAGMENT_NAMESPACE, Collections.<String, String> singletonMap(Namespace.REQUIREMENT_FILTER_DIRECTIVE, matchFilter), Collections.<String, Object> emptyMap());
			List<ModuleCapability> candidates = moduleDatabase.findCapabilities(fragmentRequirement);
			// filter out disabled fragments and singletons
			filterDisabled(candidates.listIterator());
			Collection<Resource> ondemandFragments = new ArrayList<Resource>(candidates.size());
			for (Iterator<ModuleCapability> iCandidates = candidates.iterator(); iCandidates.hasNext();) {
				ModuleCapability candidate = iCandidates.next();
				ModuleRequirement hostReq = candidate.getRevision().getModuleRequirements(HostNamespace.HOST_NAMESPACE).get(0);
				if (hostReq.matches(hostCap)) {
					ondemandFragments.add(candidate.getResource());
				}
			}
			return ondemandFragments;
		}

		ModuleResolutionReport resolve() {
			if (threadResolving.get().booleanValue()) {
				// throw up a runtime exception, if this is caused by a resolver hook
				// then it will get caught at the call to the resolver hook and a proper exception is thrown
				throw new IllegalStateException(Msg.ModuleResolver_RecursiveError);
			}
			threadResolving.set(Boolean.TRUE);
			try {
				try {
					hook = adaptor.getResolverHookFactory().begin(InternalUtils.asListBundleRevision((List<? extends BundleRevision>) triggers));
				} catch (RuntimeException e) {
					if (e.getCause() instanceof BundleException) {
						BundleException be = (BundleException) e.getCause();
						if (be.getType() == BundleException.REJECTED_BY_HOOK) {
							return new ModuleResolutionReport(null, Collections.<Resource, List<Entry>> emptyMap(), new ResolutionException(be));
						}
					}
					throw e;
				}
				Map<Resource, List<Wire>> result = null;
				ResolutionException re = null;
				ModuleResolutionReport report;
				ResolveLogger logger = new ResolveLogger();
				try {
					filterResolvable();
					selectSingletons();
					// remove disabled from optional and triggers to prevent the resolver from resolving them
					optionals.removeAll(disabled);
					if (triggers.removeAll(disabled) && triggersMandatory) {
						throw new ResolutionException(Msg.ModuleResolver_SingletonDisabledError + disabled);
					}
					if (dynamicReq != null) {
						result = resolveDynamic();
					} else {
						result = new HashMap<Resource, List<Wire>>();
						Map<Resource, List<Wire>> dynamicAttachWirings = resolveNonPayLoadFragments();
						applyInterimResultToWiringCopy(dynamicAttachWirings);
						if (!dynamicAttachWirings.isEmpty()) {
							// be sure to remove the revisions from the optional and triggers 
							// so they no longer attempt to be resolved
							Set<Resource> fragmentResources = dynamicAttachWirings.keySet();
							triggers.removeAll(fragmentResources);
							optionals.removeAll(fragmentResources);

							result.putAll(dynamicAttachWirings);
						}
						if (triggersMandatory) {
							resolveRevisionsInBatch(triggers, true, logger, result);
						}

						resolveRevisionsInBatch(optionals, false, logger, result);
					}
				} catch (ResolutionException e) {
					re = e;
				} finally {
					computeUnresolvedProviderResolutionReportEntries(result);
					computeUsesConstraintViolations(logger.getUsesConstraintViolations());
					if (DEBUG_WIRING) {
						printWirings(result);
					}
					report = reportBuilder.build(result, re);
					if (DEBUG_REPORT) {
						if (report.getResolutionException() != null) {
							Debug.printStackTrace(report.getResolutionException());
						}
						Set<Resource> resources = report.getEntries().keySet();
						if (!resources.isEmpty()) {
							Debug.println("RESOLVER: Resolution report"); //$NON-NLS-1$
							for (Resource resource : report.getEntries().keySet()) {
								Debug.println(report.getResolutionReportMessage(resource));
							}
						}
					}
					if (hook instanceof ResolutionReport.Listener)
						((ResolutionReport.Listener) hook).handleResolutionReport(report);
					hook.end();
				}
				return report;
			} finally {
				threadResolving.set(Boolean.FALSE);
			}
		}

		private void printWirings(Map<Resource, List<Wire>> wires) {
			StringBuilder builder = new StringBuilder("RESOLVER: Wirings for resolved bundles:"); //$NON-NLS-1$
			if (wires == null) {
				Debug.println(" null wires!"); //$NON-NLS-1$
				return;
			}
			for (Map.Entry<Resource, List<Wire>> entry : wires.entrySet()) {
				builder.append(SEPARATOR).append(TAB) //
						.append("Resource") //$NON-NLS-1$
						.append(SEPARATOR).append(TAB).append(TAB) //
						.append(entry.getKey()) //
						.append(SEPARATOR).append(TAB) //
						.append("Wiring"); //$NON-NLS-1$
				int i = 0;
				for (Wire wire : entry.getValue()) {
					builder.append(SEPARATOR).append(TAB).append(TAB) //
							.append('[').append(++i).append("] ") //$NON-NLS-1$
							.append(wire);
				}

			}
			Debug.println(builder);
		}

		private void resolveRevisionsInBatch(Collection<ModuleRevision> revisions, boolean isMandatory, ResolveLogger logger, Map<Resource, List<Wire>> result) throws ResolutionException {
			long startTime = System.currentTimeMillis();
			long initialFreeMemory = Runtime.getRuntime().freeMemory();
			long maxUsedMemory = 0;

			// make a copy so we do not modify the input
			revisions = new LinkedList<ModuleRevision>(revisions);
			List<Resource> toResolve = new ArrayList<Resource>();
			for (Iterator<ModuleRevision> iResources = revisions.iterator(); iResources.hasNext();) {
				ModuleRevision single = iResources.next();
				iResources.remove();
				if (!wirings.containsKey(single) && !failedToResolve.contains(single)) {
					toResolve.add(single);
				}
				if (toResolve.size() == resolverRevisionBatchSize || !iResources.hasNext()) {
					if (DEBUG_ROOTS) {
						Debug.println("Resolver: resolving " + toResolve.size() + " in batch."); //$NON-NLS-1$ //$NON-NLS-2$
						for (Resource root : toResolve) {
							Debug.println("    Resolving root bundle: " + root); //$NON-NLS-1$
						}
					}
					resolveRevisions(toResolve, isMandatory, logger, result);
					toResolve.clear();
				}
				maxUsedMemory = Math.max(maxUsedMemory, Runtime.getRuntime().freeMemory() - initialFreeMemory);
			}

			if (DEBUG_ROOTS) {
				Debug.println("Resolver: resolve batch size:  " + resolverRevisionBatchSize); //$NON-NLS-1$
				Debug.println("Resolver: time to resolve:  " + (System.currentTimeMillis() - startTime) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
				Debug.println("Resolver: max used memory: " + maxUsedMemory / (1024 * 1024) + "Mo"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		private void resolveRevisions(List<Resource> revisions, boolean isMandatory, ResolveLogger logger, Map<Resource, List<Wire>> result) throws ResolutionException {
			currentlyResolving = revisions;
			currentlyResolvingMandatory = isMandatory;
			transitivelyResolveFailures.clear();
			Map<Resource, List<Wire>> interimResults = null;
			try {
				transitivelyResolveFailures.addAll(revisions);
				interimResults = new ResolverImpl(logger).resolve(this);
				applyInterimResultToWiringCopy(interimResults);
				if (DEBUG_ROOTS) {
					Debug.println("Resolver: resolved " + interimResults.size() + " bundles."); //$NON-NLS-1$ //$NON-NLS-2$
				}
				// now apply the simple wires to the results
				for (Map.Entry<Resource, List<Wire>> interimResultEntry : interimResults.entrySet()) {
					if (DEBUG_ROOTS) {
						Debug.println("    Resolved bundle: " + interimResultEntry.getKey()); //$NON-NLS-1$
					}
					List<Wire> existingWires = result.get(interimResultEntry.getKey());
					if (existingWires != null) {
						existingWires.addAll(interimResultEntry.getValue());
					} else {
						result.put(interimResultEntry.getKey(), interimResultEntry.getValue());
					}
				}
			} finally {
				transitivelyResolveFailures.addAll(logger.getUsesConstraintViolations().keySet());
				if (interimResults != null) {
					transitivelyResolveFailures.removeAll(interimResults.keySet());
				}
				// what is left did not resolve
				failedToResolve.addAll(transitivelyResolveFailures);
				currentlyResolving = null;
				currentlyResolvingMandatory = false;
			}
		}

		private void applyInterimResultToWiringCopy(Map<Resource, List<Wire>> interimResult) {
			if (!interimResult.isEmpty()) {
				// update the copy of wirings to include interim results
				Map<ModuleRevision, ModuleWiring> updatedWirings = generateDelta(interimResult, wirings);
				for (Map.Entry<ModuleRevision, ModuleWiring> updatedWiring : updatedWirings.entrySet()) {
					wirings.put(updatedWiring.getKey(), updatedWiring.getValue());
				}
			}
		}

		private void computeUsesConstraintViolations(Map<Resource, ResolutionException> usesConstraintViolations) {
			for (Map.Entry<Resource, ResolutionException> usesConstraintViolation : usesConstraintViolations.entrySet()) {
				reportBuilder.addEntry(usesConstraintViolation.getKey(), Type.USES_CONSTRAINT_VIOLATION, usesConstraintViolation.getValue());
			}
		}

		/*
		 * Given the results of a resolution, compute which, if any, of the
		 * enabled, resolving resources are still unresolved. For those that are
		 * unresolved, generate resolution report entries for unresolved
		 * providers, if necessary.
		 */
		private void computeUnresolvedProviderResolutionReportEntries(Map<Resource, List<Wire>> resolution) {
			// Create a collection representing the resources asked to be
			// resolved.
			Collection<Resource> shouldHaveResolvedResources = new ArrayList<Resource>(unresolved);
			// Remove disabled resources.
			shouldHaveResolvedResources.removeAll(disabled);
			// Remove resolved resources, if necessary. The resolution will be
			// null if the resolver threw an exception because the triggers
			// were mandatory but didn't resolve.
			if (resolution != null)
				shouldHaveResolvedResources.removeAll(resolution.keySet());
			// What remains are resources that should have resolved but didn't.
			// For each resource, add report entries for any unresolved
			// providers.
			for (Resource shouldHaveResolvedResource : shouldHaveResolvedResources) {
				Map<Requirement, Set<Capability>> requirementToCapabilities = unresolvedProviders.get(shouldHaveResolvedResource);
				if (requirementToCapabilities == null)
					continue;
				// If nothing resolved then there are no resolved resources to
				// filter out.
				if (resolution != null) {
					// Filter out capability providers that resolved.
					for (Iterator<Set<Capability>> values = requirementToCapabilities.values().iterator(); values.hasNext();) {
						Set<Capability> value = values.next();
						for (Iterator<Capability> capabilities = value.iterator(); capabilities.hasNext();)
							if (resolution.containsKey(capabilities.next().getResource()))
								// Remove the resolved capability provider.
								capabilities.remove();
						if (value.isEmpty())
							// Remove the requirement that has no unresolved
							// capability providers.
							values.remove();
					}
				}
				// Add a report entry if there are any remaining requirements
				// pointing to unresolved capability providers.
				if (!requirementToCapabilities.isEmpty())
					reportBuilder.addEntry(shouldHaveResolvedResource, Entry.Type.UNRESOLVED_PROVIDER, requirementToCapabilities);
			}
		}

		/*
		 * Given a requirement and its matching capabilities, map the
		 * requirement's resource to the requirement and matching capabilities.
		 * This data is used to compute report entries for resources that did
		 * not resolve because a provider did not resolve.
		 */
		private void computeUnresolvedProviders(Requirement requirement, Collection<? extends Capability> capabilities) {
			Resource requirer = requirement.getResource();
			Map<Requirement, Set<Capability>> requirementToCapabilities = unresolvedProviders.get(requirer);
			if (requirementToCapabilities == null) {
				requirementToCapabilities = new HashMap<Requirement, Set<Capability>>();
				unresolvedProviders.put(requirer, requirementToCapabilities);
			}
			Set<Capability> value = requirementToCapabilities.get(requirement);
			if (value == null) {
				value = new HashSet<Capability>(capabilities.size());
				requirementToCapabilities.put(requirement, value);
			}
			for (Capability capability : capabilities)
				if (!wirings.containsKey(capability.getResource()))
					value.add(capability);
		}

		private Map<Resource, List<Wire>> resolveNonPayLoadFragments() {
			// This is to support dynamic attachment of fragments that do not
			// add any payload requirements to their host.
			// This is needed for framework extensions since the system bundle
			// host is always resolved.
			// It is also useful for things like NLS fragments that are installed later
			// without the need to refresh the host.
			Collection<ModuleRevision> nonPayLoadFrags = new ArrayList<ModuleRevision>();
			if (triggersMandatory) {
				for (ModuleRevision moduleRevision : triggers) {
					if (nonPayLoad(moduleRevision)) {
						nonPayLoadFrags.add(moduleRevision);
					}
				}
			}
			for (ModuleRevision moduleRevision : optionals) {
				if (nonPayLoad(moduleRevision)) {
					nonPayLoadFrags.add(moduleRevision);
				}
			}

			if (nonPayLoadFrags.isEmpty()) {
				return Collections.emptyMap();
			}
			Map<Resource, List<Wire>> dynamicAttachment = new HashMap<Resource, List<Wire>>(0);
			for (ModuleRevision nonPayLoad : nonPayLoadFrags) {
				List<Wire> allNonPayloadWires = new ArrayList<Wire>(0);
				for (ModuleRequirement requirement : nonPayLoad.getModuleRequirements(null)) {
					List<ModuleCapability> matching = moduleDatabase.findCapabilities(requirement);
					List<Wire> newWires = new ArrayList<Wire>(0);
					filterProviders(requirement, matching, false);
					for (ModuleCapability candidate : matching) {
						if (HostNamespace.HOST_NAMESPACE.equals(requirement.getNamespace())) {
							String attachDirective = candidate.getDirectives().get(HostNamespace.CAPABILITY_FRAGMENT_ATTACHMENT_DIRECTIVE);
							boolean attachAlways = attachDirective == null || HostNamespace.FRAGMENT_ATTACHMENT_ALWAYS.equals(attachDirective);
							// only do this if the candidate host is already resolved and it allows dynamic attachment
							if (!attachAlways || wirings.get(candidate.getRevision()) == null) {
								continue;
							}
						}
						// if there are multiple candidates; then check for cardinality
						if (newWires.isEmpty() || Namespace.CARDINALITY_MULTIPLE.equals(requirement.getDirectives().get(Namespace.REQUIREMENT_CARDINALITY_DIRECTIVE))) {
							newWires.add(new ModuleWire(candidate, candidate.getRevision(), requirement, nonPayLoad));
						}
					}
					if (newWires.isEmpty()) {
						if (!Namespace.RESOLUTION_OPTIONAL.equals(requirement.getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE))) {
							// could not resolve mandatory requirement;
							// clear out the wires to prevent resolution
							allNonPayloadWires.clear();
							break;
						}
					} else {
						allNonPayloadWires.addAll(newWires);
					}
				}
				if (!allNonPayloadWires.isEmpty()) {
					// we found some wires to resolve the fragment
					dynamicAttachment.put(nonPayLoad, allNonPayloadWires);
				}
			}
			return dynamicAttachment;
		}

		private boolean nonPayLoad(ModuleRevision moduleRevision) {
			if ((moduleRevision.getTypes() & BundleRevision.TYPE_FRAGMENT) == 0) {
				// not a fragment
				return false;
			}
			for (Requirement req : moduleRevision.getRequirements(null)) {
				if (!NON_PAYLOAD_REQUIREMENTS.contains(req.getNamespace())) {
					// fragment adds payload to host
					return false;
				}
			}
			return true;
		}

		private Map<Resource, List<Wire>> resolveDynamic() throws ResolutionException {
			List<Capability> dynamicMatches = findProviders0(dynamicReq.getOriginal(), dynamicReq);
			return new ResolverImpl(new Logger(0)).resolve(this, dynamicReq.getRevision(), dynamicReq.getOriginal(), dynamicMatches);
		}

		private void filterResolvable() {
			Collection<ModuleRevision> enabledCandidates = new ArrayList<ModuleRevision>(unresolved);
			hook.filterResolvable(InternalUtils.asListBundleRevision((List<? extends BundleRevision>) enabledCandidates));
			disabled.removeAll(enabledCandidates);
			for (ModuleRevision revision : disabled) {
				reportBuilder.addEntry(revision, Entry.Type.FILTERED_BY_RESOLVER_HOOK, null);
				if (DEBUG_HOOKS) {
					Debug.println("RESOLVER: Resource filtered by ResolverHook.filterResolvable: " + revision); //$NON-NLS-1$
				}
			}
		}

		private void selectSingletons() {
			Map<String, Collection<ModuleRevision>> selectedSingletons = new HashMap<String, Collection<ModuleRevision>>();
			for (ModuleRevision revision : unresolved) {
				if (!isSingleton(revision) || disabled.contains(revision))
					continue;
				String bsn = revision.getSymbolicName();
				Collection<ModuleRevision> selected = selectedSingletons.get(bsn);
				if (selected != null)
					continue; // already processed the bsn
				selected = new ArrayList<ModuleRevision>(1);
				selectedSingletons.put(bsn, selected);

				Collection<ModuleRevision> sameBSN = getRevisions(bsn);
				if (sameBSN.size() < 2) {
					selected.add(revision);
					continue;
				}
				// prime selected with resolved singleton bundles
				for (ModuleRevision singleton : sameBSN) {
					if (isSingleton(singleton) && wirings.containsKey(singleton))
						selected.add(singleton);
				}
				// get the collision map for the BSN
				Map<ModuleRevision, Collection<ModuleRevision>> collisionMap = getCollisionMap(sameBSN);
				// process the collision map
				for (ModuleRevision singleton : sameBSN) {
					if (selected.contains(singleton))
						continue; // no need to process resolved bundles
					Collection<ModuleRevision> collisions = collisionMap.get(singleton);
					if (collisions == null || disabled.contains(singleton))
						continue; // not a singleton or not resolvable
					Collection<ModuleRevision> pickOneToResolve = new ArrayList<ModuleRevision>();
					for (ModuleRevision collision : collisions) {
						if (selected.contains(collision)) {
							// Must fail since there is already a selected bundle which is a collision of the singleton bundle
							disabled.add(singleton);
							reportBuilder.addEntry(singleton, Type.SINGLETON_SELECTION, collision);
							break;
						}
						if (!pickOneToResolve.contains(collision))
							pickOneToResolve.add(collision);
					}
					if (!disabled.contains(singleton)) {
						// need to make sure the bundle does not collide from the POV of another entry
						for (Map.Entry<ModuleRevision, Collection<ModuleRevision>> collisionEntry : collisionMap.entrySet()) {
							if (collisionEntry.getKey() != singleton && collisionEntry.getValue().contains(singleton)) {
								if (selected.contains(collisionEntry.getKey())) {
									// Must fail since there is already a selected bundle for which the singleton bundle is a collision
									disabled.add(singleton);
									reportBuilder.addEntry(singleton, Type.SINGLETON_SELECTION, collisionEntry.getKey());
									break;
								}
								if (!pickOneToResolve.contains(collisionEntry.getKey()))
									pickOneToResolve.add(collisionEntry.getKey());
							}
						}
					}
					if (!disabled.contains(singleton)) {
						pickOneToResolve.add(singleton);
						selected.add(pickOneToResolve(pickOneToResolve));
					}
				}
			}
		}

		private Collection<ModuleRevision> getRevisions(String name) {
			Map<String, Collection<ModuleRevision>> current = byName;
			if (current == null) {
				// generate the map using unresolved collection and wiring snap shot
				// this is to avoid interacting with the module database
				Set<ModuleRevision> revisions = new HashSet<ModuleRevision>();
				revisions.addAll(unresolved);
				revisions.addAll(previouslyResolved);
				current = new HashMap<String, Collection<ModuleRevision>>();
				for (ModuleRevision revision : revisions) {
					Collection<ModuleRevision> sameName = current.get(revision.getSymbolicName());
					if (sameName == null) {
						sameName = new ArrayList<ModuleRevision>();
						current.put(revision.getSymbolicName(), sameName);
					}
					sameName.add(revision);
				}
				byName = current;
			}
			Collection<ModuleRevision> result = current.get(name);
			if (result == null) {
				return Collections.emptyList();
			}
			return result;
		}

		private ModuleRevision pickOneToResolve(Collection<ModuleRevision> pickOneToResolve) {
			ModuleRevision selectedVersion = null;
			for (ModuleRevision singleton : pickOneToResolve) {
				if (selectedVersion == null)
					selectedVersion = singleton;
				boolean higherVersion = selectedVersion.getVersion().compareTo(singleton.getVersion()) < 0;
				if (higherVersion)
					selectedVersion = singleton;
			}

			for (ModuleRevision singleton : pickOneToResolve) {
				if (singleton != selectedVersion) {
					disabled.add(singleton);
					reportBuilder.addEntry(singleton, Type.SINGLETON_SELECTION, selectedVersion);
				}
			}
			return selectedVersion;
		}

		private Map<ModuleRevision, Collection<ModuleRevision>> getCollisionMap(Collection<ModuleRevision> sameBSN) {
			Map<ModuleRevision, Collection<ModuleRevision>> result = new HashMap<ModuleRevision, Collection<ModuleRevision>>();
			for (ModuleRevision singleton : sameBSN) {
				if (!isSingleton(singleton) || disabled.contains(singleton))
					continue; // ignore non-singleton and non-resolvable
				List<BundleCapability> capabilities = new ArrayList<BundleCapability>(sameBSN.size() - 1);
				for (ModuleRevision collision : sameBSN) {
					if (collision == singleton || !isSingleton(collision) || disabled.contains(collision))
						continue; // Ignore the bundle we are checking and non-singletons and non-resolvable
					capabilities.add(getIdentity(collision));
				}
				hook.filterSingletonCollisions(getIdentity(singleton), capabilities);
				Collection<ModuleRevision> collisionCandidates = new ArrayList<ModuleRevision>(capabilities.size());
				for (BundleCapability identity : capabilities) {
					collisionCandidates.add((ModuleRevision) identity.getRevision());
				}
				if (DEBUG_HOOKS) {
					Collection<ModuleRevision> filteredSingletons = new ArrayList<ModuleRevision>(sameBSN);
					filteredSingletons.removeAll(collisionCandidates);
					filteredSingletons.remove(singleton);
					if (!filteredSingletons.isEmpty()) {
						StringBuilder builder = new StringBuilder("RESOLVER: Resources filtered by ResolverHook.filterSingletonCollisions") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB) //
								.append("Singleton") //$NON-NLS-1$
								.append(SEPARATOR).append(TAB).append(TAB) //
								.append(singleton) //
								.append(" [id=") //$NON-NLS-1$
								.append(singleton.getRevisions().getModule().getId()) //
								.append(", location=") //$NON-NLS-1$
								.append(singleton.getRevisions().getModule().getLocation()) //
								.append(']') //
								.append(SEPARATOR).append(TAB) //
								.append("Collisions"); //$NON-NLS-1$
						int i = 0;
						for (ModuleRevision revision : filteredSingletons) {
							builder.append(SEPARATOR).append(TAB).append(TAB) //
									.append("[").append(++i).append("] ") //$NON-NLS-1$ //$NON-NLS-2$
									.append(revision) //
									.append(" [id=") //$NON-NLS-1$
									.append(revision.getRevisions().getModule().getId()) //
									.append(", location=") //$NON-NLS-1$
									.append(revision.getRevisions().getModule().getLocation()) //
									.append(']');
						}
						Debug.println(builder.toString());
					}
				}
				result.put(singleton, collisionCandidates);
			}
			return result;
		}

		private BundleCapability getIdentity(ModuleRevision bundle) {
			List<BundleCapability> identities = bundle.getDeclaredCapabilities(IdentityNamespace.IDENTITY_NAMESPACE);
			return identities.isEmpty() ? null : identities.get(0);
		}

		@Override
		public int compare(Capability c1, Capability c2) {
			// TODO Ideally this policy should be handled by the ModuleDatabase.
			// To do that the wirings would have to be provided since the wirings may
			// be a subset of the current wirings provided by the ModuleDatabase
			boolean resolved1 = previouslyResolved.contains(c1.getResource());
			boolean resolved2 = previouslyResolved.contains(c2.getResource());
			if (resolved1 != resolved2)
				return resolved1 ? -1 : 1;

			Version v1 = getVersion(c1);
			Version v2 = getVersion(c2);
			int versionCompare = -(v1.compareTo(v2));
			if (versionCompare != 0)
				return versionCompare;

			ModuleRevision m1 = getModuleRevision(c1);
			ModuleRevision m2 = getModuleRevision(c2);
			Long id1 = m1.getRevisions().getModule().getId();
			Long id2 = m2.getRevisions().getModule().getId();

			if (id1.equals(id2) && !m1.equals(m2)) {
				// sort based on revision ordering
				List<ModuleRevision> revisions = m1.getRevisions().getModuleRevisions();
				int index1 = revisions.indexOf(m1);
				int index2 = revisions.indexOf(m2);
				// we want to sort the indexes from highest to lowest
				return index2 - index1;
			}
			return id1.compareTo(id2);
		}

		ModuleRevision getModuleRevision(Capability c) {
			// We assume all capabilities here either come from us and have ModuleRevision resources or
			// they are HostedCapabilities which have ModuleRevision resources as the host revision
			if (c instanceof HostedCapability) {
				c = ((HostedCapability) c).getDeclaredCapability();
			}
			if (c instanceof ModuleCapability) {
				return ((ModuleCapability) c).getRevision();
			}
			// TODO is there some bug in the resolver?
			return null;
		}
	}
}
