/*******************************************************************************
 * Copyright (c) 2003, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Danail Nachev -  ProSyst - bug 218625
 *     Rob Harrop - SpringSource Inc. (bug 247522 and 255520)
 *******************************************************************************/
package org.eclipse.osgi.internal.resolver;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import org.eclipse.osgi.framework.adaptor.BundleClassLoader;
import org.eclipse.osgi.framework.internal.core.BundleHost;
import org.eclipse.osgi.framework.internal.core.Constants;
import org.eclipse.osgi.framework.util.KeyedElement;
import org.eclipse.osgi.internal.loader.BundleLoaderProxy;
import org.eclipse.osgi.service.resolver.*;
import org.osgi.framework.*;
import org.osgi.framework.wiring.*;

public final class BundleDescriptionImpl extends BaseDescriptionImpl implements BundleDescription, KeyedElement {
	static final String[] EMPTY_STRING = new String[0];
	static final ImportPackageSpecification[] EMPTY_IMPORTS = new ImportPackageSpecification[0];
	static final BundleSpecification[] EMPTY_BUNDLESPECS = new BundleSpecification[0];
	static final ExportPackageDescription[] EMPTY_EXPORTS = new ExportPackageDescription[0];
	static final BundleDescription[] EMPTY_BUNDLEDESCS = new BundleDescription[0];
	static final GenericSpecification[] EMPTY_GENERICSPECS = new GenericSpecification[0];
	static final GenericDescription[] EMPTY_GENERICDESCS = new GenericDescription[0];
	static final RuntimePermission GET_CLASSLOADER_PERM = new RuntimePermission("getClassLoader"); //$NON-NLS-1$

	static final int RESOLVED = 0x01;
	static final int SINGLETON = 0x02;
	static final int REMOVAL_PENDING = 0x04;
	static final int FULLY_LOADED = 0x08;
	static final int LAZY_LOADED = 0x10;
	static final int HAS_DYNAMICIMPORT = 0x20;
	static final int ATTACH_FRAGMENTS = 0x40;
	static final int DYNAMIC_FRAGMENTS = 0x80;

	// set to fully loaded and allow dynamic fragments by default
	private volatile int stateBits = FULLY_LOADED | ATTACH_FRAGMENTS | DYNAMIC_FRAGMENTS;

	private volatile long bundleId = -1;
	private volatile HostSpecification host; //null if the bundle is not a fragment. volatile to allow unsynchronized checks for null
	private volatile StateImpl containingState;

	private volatile Object userObject;
	private volatile int lazyDataOffset = -1;
	private volatile int lazyDataSize = -1;

	private List<BundleDescription> dependencies;
	private List<BundleDescription> dependents;

	private volatile LazyData lazyData;
	private volatile int equinox_ee = -1;

	private DescriptionWiring bundleWiring;

	public BundleDescriptionImpl() {
		// 
	}

	public long getBundleId() {
		return bundleId;
	}

	public String getSymbolicName() {
		return getName();
	}

	public BundleDescription getSupplier() {
		return this;
	}

	public String getLocation() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			return currentData.location;
		}
	}

	public String getPlatformFilter() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			return currentData.platformFilter;
		}
	}

	public String[] getExecutionEnvironments() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.executionEnvironments == null)
				return EMPTY_STRING;
			return currentData.executionEnvironments;
		}
	}

	public ImportPackageSpecification[] getImportPackages() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.importPackages == null)
				return EMPTY_IMPORTS;
			return currentData.importPackages;
		}
	}

	public ImportPackageSpecification[] getAddedDynamicImportPackages() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.addedDynamicImports == null)
				return EMPTY_IMPORTS;
			return currentData.addedDynamicImports.toArray(new ImportPackageSpecification[currentData.addedDynamicImports.size()]);
		}
	}

	public BundleSpecification[] getRequiredBundles() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.requiredBundles == null)
				return EMPTY_BUNDLESPECS;
			return currentData.requiredBundles;
		}
	}

	public GenericSpecification[] getGenericRequires() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.genericRequires == null)
				return EMPTY_GENERICSPECS;
			return currentData.genericRequires;
		}
	}

	public GenericDescription[] getGenericCapabilities() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.genericCapabilities == null)
				return EMPTY_GENERICDESCS;
			return currentData.genericCapabilities;
		}
	}

	public NativeCodeSpecification getNativeCodeSpecification() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			return currentData.nativeCode;
		}
	}

	public ExportPackageDescription[] getExportPackages() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			return currentData.exportPackages == null ? EMPTY_EXPORTS : currentData.exportPackages;
		}
	}

	public boolean isResolved() {
		return (stateBits & RESOLVED) != 0;
	}

	public State getContainingState() {
		return containingState;
	}

	public BundleDescription[] getFragments() {
		if (host != null)
			return EMPTY_BUNDLEDESCS;
		StateImpl currentState = (StateImpl) getContainingState();
		if (currentState == null)
			throw new IllegalStateException("BundleDescription does not belong to a state."); //$NON-NLS-1$
		return currentState.getFragments(this);
	}

	public HostSpecification getHost() {
		return host;
	}

	public boolean isSingleton() {
		return (stateBits & SINGLETON) != 0;
	}

	public boolean isRemovalPending() {
		return (stateBits & REMOVAL_PENDING) != 0;
	}

	public boolean hasDynamicImports() {
		return (stateBits & HAS_DYNAMICIMPORT) != 0;
	}

	public boolean attachFragments() {
		return (stateBits & ATTACH_FRAGMENTS) != 0;
	}

	public boolean dynamicFragments() {
		return (stateBits & DYNAMIC_FRAGMENTS) != 0;
	}

	public ExportPackageDescription[] getSelectedExports() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.selectedExports == null)
				return EMPTY_EXPORTS;
			return currentData.selectedExports;
		}
	}

	public GenericDescription[] getSelectedGenericCapabilities() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.selectedCapabilities == null)
				return EMPTY_GENERICDESCS;
			return currentData.selectedCapabilities;
		}
	}

	public ExportPackageDescription[] getSubstitutedExports() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.substitutedExports == null)
				return EMPTY_EXPORTS;
			return currentData.substitutedExports;
		}
	}

	public BundleDescription[] getResolvedRequires() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.resolvedRequires == null)
				return EMPTY_BUNDLEDESCS;
			return currentData.resolvedRequires;
		}
	}

	public ExportPackageDescription[] getResolvedImports() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.resolvedImports == null)
				return EMPTY_EXPORTS;
			return currentData.resolvedImports;
		}
	}

	public GenericDescription[] getResolvedGenericRequires() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.resolvedCapabilities == null)
				return EMPTY_GENERICDESCS;
			return currentData.resolvedCapabilities;
		}
	}

	protected void setBundleId(long bundleId) {
		this.bundleId = bundleId;
	}

	protected void setSymbolicName(String symbolicName) {
		setName(symbolicName);
	}

	protected void setLocation(String location) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.location = location;
		}
	}

	protected void setPlatformFilter(String platformFilter) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.platformFilter = platformFilter;
		}
	}

	protected void setExecutionEnvironments(String[] executionEnvironments) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.executionEnvironments = executionEnvironments;
		}
	}

	protected void setExportPackages(ExportPackageDescription[] exportPackages) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.exportPackages = exportPackages;
			if (exportPackages != null) {
				for (int i = 0; i < exportPackages.length; i++) {
					((ExportPackageDescriptionImpl) exportPackages[i]).setExporter(this);
				}
			}
		}
	}

	protected void setImportPackages(ImportPackageSpecification[] importPackages) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.importPackages = importPackages;
			if (importPackages != null) {
				for (int i = 0; i < importPackages.length; i++) {
					((ImportPackageSpecificationImpl) importPackages[i]).setBundle(this);
					if (ImportPackageSpecification.RESOLUTION_DYNAMIC.equals(importPackages[i].getDirective(Constants.RESOLUTION_DIRECTIVE)))
						stateBits |= HAS_DYNAMICIMPORT;
				}
			}
		}
	}

	protected void setRequiredBundles(BundleSpecification[] requiredBundles) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.requiredBundles = requiredBundles;
			if (requiredBundles != null)
				for (int i = 0; i < requiredBundles.length; i++) {
					((VersionConstraintImpl) requiredBundles[i]).setBundle(this);
				}
		}
	}

	protected void setGenericCapabilities(GenericDescription[] genericCapabilities) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.genericCapabilities = genericCapabilities;
			if (genericCapabilities != null)
				for (int i = 0; i < genericCapabilities.length; i++)
					((GenericDescriptionImpl) genericCapabilities[i]).setSupplier(this);
		}
	}

	protected void setGenericRequires(GenericSpecification[] genericRequires) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.genericRequires = genericRequires;
			if (genericRequires != null)
				for (int i = 0; i < genericRequires.length; i++)
					((VersionConstraintImpl) genericRequires[i]).setBundle(this);
		}
	}

	protected void setNativeCodeSpecification(NativeCodeSpecification nativeCode) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.nativeCode = nativeCode;
			if (nativeCode != null) {
				((NativeCodeSpecificationImpl) nativeCode).setBundle(this);
				NativeCodeDescription[] suppliers = nativeCode.getPossibleSuppliers();
				if (suppliers != null)
					for (int i = 0; i < suppliers.length; i++)
						((NativeCodeDescriptionImpl) suppliers[i]).setSupplier(this);
			}
		}
	}

	protected int getStateBits() {
		return stateBits;
	}

	protected void setStateBit(int stateBit, boolean on) {
		synchronized (this.monitor) {
			if (on) {
				stateBits |= stateBit;
			} else {
				stateBits &= ~stateBit;
				if (stateBit == RESOLVED) {
					if (bundleWiring != null)
						bundleWiring.invalidate();
					bundleWiring = null;
				}
			}
		}
	}

	protected void setContainingState(State value) {
		synchronized (this.monitor) {
			containingState = (StateImpl) value;
			if (containingState != null && containingState.getReader() != null) {
				if (containingState.getReader().isLazyLoaded())
					stateBits |= LAZY_LOADED;
				else
					stateBits &= ~LAZY_LOADED;
			} else {
				stateBits &= ~LAZY_LOADED;
			}
		}
	}

	protected void setHost(HostSpecification host) {
		synchronized (this.monitor) {
			this.host = host;
			if (host != null) {
				((VersionConstraintImpl) host).setBundle(this);
			}
		}
	}

	protected void setLazyLoaded(boolean lazyLoad) {
		loadLazyData();
		synchronized (this.monitor) {
			if (lazyLoad)
				stateBits |= LAZY_LOADED;
			else
				stateBits &= ~LAZY_LOADED;
		}
	}

	protected void setSelectedExports(ExportPackageDescription[] selectedExports) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.selectedExports = selectedExports;
			if (selectedExports != null) {
				for (int i = 0; i < selectedExports.length; i++) {
					((ExportPackageDescriptionImpl) selectedExports[i]).setExporter(this);
				}
			}
		}
	}

	protected void setSelectedCapabilities(GenericDescription[] selectedCapabilities) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.selectedCapabilities = selectedCapabilities;
			if (selectedCapabilities != null) {
				for (GenericDescription capability : selectedCapabilities) {
					((GenericDescriptionImpl) capability).setSupplier(this);
				}
			}
		}
	}

	protected void setSubstitutedExports(ExportPackageDescription[] substitutedExports) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.substitutedExports = substitutedExports;
		}
	}

	protected void setResolvedImports(ExportPackageDescription[] resolvedImports) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.resolvedImports = resolvedImports;
		}
	}

	protected void setResolvedRequires(BundleDescription[] resolvedRequires) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.resolvedRequires = resolvedRequires;
		}
	}

	protected void setResolvedCapabilities(GenericDescription[] resolvedCapabilities) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.resolvedCapabilities = resolvedCapabilities;
		}
	}

	void clearAddedDynamicImportPackages() {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.addedDynamicImports = null;
		}
	}

	public String toString() {
		if (getSymbolicName() == null)
			return "[" + getBundleId() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
		return getSymbolicName() + "_" + getVersion(); //$NON-NLS-1$
	}

	public Object getKey() {
		return new Long(bundleId);
	}

	public boolean compare(KeyedElement other) {
		if (!(other instanceof BundleDescriptionImpl))
			return false;
		BundleDescriptionImpl otherBundleDescription = (BundleDescriptionImpl) other;
		return bundleId == otherBundleDescription.bundleId;
	}

	public int getKeyHashCode() {
		return (int) (bundleId ^ (bundleId >>> 32));
	}

	/* TODO Determine if we need more than just Object ID type of hashcode.
	 public int hashCode() {
	 if (getSymbolicName() == null)
	 return (int) (bundleId % Integer.MAX_VALUE);
	 return (int) ((bundleId * (getSymbolicName().hashCode())) % Integer.MAX_VALUE);
	 }
	 */

	protected void removeDependencies() {
		synchronized (this.monitor) {
			if (dependencies == null)
				return;
			Iterator<BundleDescription> iter = dependencies.iterator();
			while (iter.hasNext()) {
				((BundleDescriptionImpl) iter.next()).removeDependent(this);
			}
			dependencies = null;
		}
	}

	protected void addDependencies(BaseDescription[] newDependencies, boolean checkDups) {
		synchronized (this.monitor) {
			if (newDependencies == null)
				return;
			if (!checkDups && dependencies == null)
				dependencies = new ArrayList<BundleDescription>(newDependencies.length);
			for (int i = 0; i < newDependencies.length; i++) {
				addDependency((BaseDescriptionImpl) newDependencies[i], checkDups);
			}
		}
	}

	protected void addDependency(BaseDescriptionImpl dependency, boolean checkDups) {
		synchronized (this.monitor) {
			BundleDescriptionImpl bundle = (BundleDescriptionImpl) dependency.getSupplier();
			if (bundle == this)
				return;
			if (dependencies == null)
				dependencies = new ArrayList<BundleDescription>(10);
			if (!checkDups || !dependencies.contains(bundle)) {
				bundle.addDependent(this);
				dependencies.add(bundle);
			}
		}
	}

	/*
	 * Gets all the bundle dependencies as a result of import-package or require-bundle.
	 * Self and fragment bundles are removed.
	 */
	List<BundleDescription> getBundleDependencies() {
		synchronized (this.monitor) {
			if (dependencies == null)
				return new ArrayList<BundleDescription>(0);
			ArrayList<BundleDescription> required = new ArrayList<BundleDescription>(dependencies.size());
			for (Iterator<BundleDescription> iter = dependencies.iterator(); iter.hasNext();) {
				BundleDescription dep = iter.next();
				if (dep != this && dep.getHost() == null)
					required.add(dep);
			}
			return required;
		}
	}

	public Object getUserObject() {
		return userObject;
	}

	public void setUserObject(Object userObject) {
		this.userObject = userObject;
	}

	protected void addDependent(BundleDescription dependent) {
		synchronized (this.monitor) {
			if (dependents == null)
				dependents = new ArrayList<BundleDescription>(10);
			// no need to check for duplicates here; this is only called in addDepenency which already checks for dups.
			dependents.add(dependent);
		}
	}

	protected void removeDependent(BundleDescription dependent) {
		synchronized (this.monitor) {
			if (dependents == null)
				return;
			dependents.remove(dependent);
		}
	}

	public BundleDescription[] getDependents() {
		synchronized (this.monitor) {
			if (dependents == null)
				return EMPTY_BUNDLEDESCS;
			return dependents.toArray(new BundleDescription[dependents.size()]);
		}
	}

	boolean hasDependents() {
		synchronized (this.monitor) {
			return dependents == null ? false : dependents.size() > 0;
		}
	}

	void setFullyLoaded(boolean fullyLoaded) {
		synchronized (this.monitor) {
			if (fullyLoaded) {
				stateBits |= FULLY_LOADED;
			} else {
				stateBits &= ~FULLY_LOADED;
			}
		}
	}

	boolean isFullyLoaded() {
		return (stateBits & FULLY_LOADED) != 0;
	}

	void setLazyDataOffset(int lazyDataOffset) {
		this.lazyDataOffset = lazyDataOffset;
	}

	int getLazyDataOffset() {
		return this.lazyDataOffset;
	}

	void setLazyDataSize(int lazyDataSize) {
		this.lazyDataSize = lazyDataSize;
	}

	int getLazyDataSize() {
		return this.lazyDataSize;
	}

	// DO NOT call while holding this.monitor
	private LazyData loadLazyData() {
		// TODO add back if ee min 1.2 adds holdsLock method
		//if (Thread.holdsLock(this.monitor)) {
		//	throw new IllegalStateException("Should not call fullyLoad() holding monitor."); //$NON-NLS-1$
		//}
		if ((stateBits & LAZY_LOADED) == 0)
			return this.lazyData;

		StateImpl currentState = (StateImpl) getContainingState();
		StateReader reader = currentState == null ? null : currentState.getReader();
		if (reader == null)
			throw new IllegalStateException("No valid reader for the bundle description"); //$NON-NLS-1$

		synchronized (reader) {
			if (isFullyLoaded()) {
				reader.setAccessedFlag(true); // set reader accessed flag
				return this.lazyData;
			}
			try {
				reader.fullyLoad(this);
				return this.lazyData;
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e); // TODO not sure what to do here!!
			}
		}
	}

	void addDynamicResolvedImport(ExportPackageDescriptionImpl result) {
		synchronized (this.monitor) {
			// mark the dependency
			addDependency(result, true);
			// add the export to the list of the resolvedImports
			checkLazyData();
			if (lazyData.resolvedImports == null) {
				lazyData.resolvedImports = new ExportPackageDescription[] {result};
				return;
			}
			ExportPackageDescription[] newImports = new ExportPackageDescription[lazyData.resolvedImports.length + 1];
			System.arraycopy(lazyData.resolvedImports, 0, newImports, 0, lazyData.resolvedImports.length);
			newImports[newImports.length - 1] = result;
			lazyData.resolvedImports = newImports;
		}
		setLazyLoaded(false);
	}

	void addDynamicImportPackages(ImportPackageSpecification[] dynamicImport) {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			if (currentData.addedDynamicImports == null)
				currentData.addedDynamicImports = new ArrayList<ImportPackageSpecification>();
			for (ImportPackageSpecification addImport : dynamicImport) {
				if (!ImportPackageSpecification.RESOLUTION_DYNAMIC.equals(addImport.getDirective(Constants.RESOLUTION_DIRECTIVE)))
					throw new IllegalArgumentException("Import must be a dynamic import."); //$NON-NLS-1$
			}
			adding: for (ImportPackageSpecification addImport : dynamicImport) {
				for (ImportPackageSpecification currentImport : currentData.addedDynamicImports) {
					if (equalImports(addImport, currentImport))
						continue adding;
				}
				((ImportPackageSpecificationImpl) addImport).setBundle(this);
				currentData.addedDynamicImports.add(addImport);
			}
		}
		setLazyLoaded(false);
	}

	private boolean equalImports(ImportPackageSpecification addImport, ImportPackageSpecification currentImport) {
		if (!isEqual(addImport.getName(), currentImport.getName()))
			return false;
		if (!isEqual(addImport.getVersionRange(), currentImport.getVersionRange()))
			return false;
		if (!isEqual(addImport.getBundleSymbolicName(), currentImport.getBundleSymbolicName()))
			return false;
		if (!isEqual(addImport.getBundleVersionRange(), currentImport.getBundleVersionRange()))
			return false;
		return isEqual(addImport.getAttributes(), currentImport.getAttributes());
	}

	private boolean isEqual(Object o1, Object o2) {
		return (o1 == null) ? o2 == null : o1.equals(o2);
	}

	void unload() {
		StateImpl currentState = (StateImpl) getContainingState();
		StateReader reader = currentState == null ? null : currentState.getReader();
		if (reader == null)
			throw new IllegalStateException("BundleDescription does not belong to a reader."); //$NON-NLS-1$
		synchronized (reader) {
			if ((stateBits & LAZY_LOADED) == 0)
				return;
			if (!isFullyLoaded())
				return;
			synchronized (this.monitor) {
				setFullyLoaded(false);
				lazyData = null;
			}
		}
	}

	void setDynamicStamps(Map<String, Long> dynamicStamps) {
		synchronized (this.monitor) {
			checkLazyData();
			lazyData.dynamicStamps = dynamicStamps;
		}
	}

	void setDynamicStamp(String requestedPackage, Long timestamp) {
		synchronized (this.monitor) {
			checkLazyData();
			if (lazyData.dynamicStamps == null) {
				if (timestamp == null)
					return;
				lazyData.dynamicStamps = new HashMap<String, Long>();
			}
			if (timestamp == null)
				lazyData.dynamicStamps.remove(requestedPackage);
			else
				lazyData.dynamicStamps.put(requestedPackage, timestamp);
		}
	}

	long getDynamicStamp(String requestedPackage) {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			Long stamp = currentData.dynamicStamps == null ? null : (Long) currentData.dynamicStamps.get(requestedPackage);
			return stamp == null ? 0 : stamp.longValue();
		}
	}

	Map<String, Long> getDynamicStamps() {
		LazyData currentData = loadLazyData();
		synchronized (this.monitor) {
			return currentData.dynamicStamps;
		}
	}

	public void setEquinoxEE(int equinox_ee) {
		this.equinox_ee = equinox_ee;
	}

	public int getEquinoxEE() {
		return equinox_ee;
	}

	private void checkLazyData() {
		if (lazyData == null)
			lazyData = new LazyData();
	}

	final class LazyData {
		String location;
		String platformFilter;

		BundleSpecification[] requiredBundles;
		ExportPackageDescription[] exportPackages;
		ImportPackageSpecification[] importPackages;
		GenericDescription[] genericCapabilities;
		GenericSpecification[] genericRequires;
		NativeCodeSpecification nativeCode;

		ExportPackageDescription[] selectedExports;
		GenericDescription[] selectedCapabilities;
		BundleDescription[] resolvedRequires;
		ExportPackageDescription[] resolvedImports;
		GenericDescription[] resolvedCapabilities;
		ExportPackageDescription[] substitutedExports;
		String[] executionEnvironments;

		Map<String, Long> dynamicStamps;
		// Note that this is not persisted in the state cache
		List<ImportPackageSpecification> addedDynamicImports;
	}

	public Map<String, String> getDeclaredDirectives() {
		Map<String, String> result = new HashMap<String, String>(2);
		if (!attachFragments()) {
			result.put(Constants.FRAGMENT_ATTACHMENT_DIRECTIVE, Constants.FRAGMENT_ATTACHMENT_NEVER);
		} else {
			if (dynamicFragments())
				result.put(Constants.FRAGMENT_ATTACHMENT_DIRECTIVE, Constants.FRAGMENT_ATTACHMENT_ALWAYS);
			else
				result.put(Constants.FRAGMENT_ATTACHMENT_DIRECTIVE, Constants.FRAGMENT_ATTACHMENT_RESOLVETIME);
		}
		if (isSingleton())
			result.put(Constants.SINGLETON_DIRECTIVE, Boolean.TRUE.toString());
		return Collections.unmodifiableMap(result);
	}

	public Map<String, Object> getDeclaredAttributes() {
		Map<String, Object> result = new HashMap<String, Object>(1);
		result.put(Capability.BUNDLE_CAPABILITY, getName());
		result.put(Constants.BUNDLE_VERSION_ATTRIBUTE, getVersion());
		return Collections.unmodifiableMap(result);
	}

	public List<Capability> getDeclaredCapabilities(String namespace) {
		List<Capability> result = new ArrayList<Capability>();
		if (namespace == null || Capability.BUNDLE_CAPABILITY.equals(namespace)) {
			result.add(BundleDescriptionImpl.this.getWiredCapability());
		}
		if (namespace == null || Capability.PACKAGE_CAPABILITY.equals(namespace)) {
			ExportPackageDescription[] exports = getExportPackages();
			for (ExportPackageDescription importPkg : exports)
				result.add(importPkg.getWiredCapability());
		}
		GenericDescription[] genericCapabilities = getGenericCapabilities();
		for (GenericDescription capabilitiy : genericCapabilities) {
			if (namespace == null || namespace.equals(capabilitiy.getType()))
				result.add(capabilitiy.getWiredCapability());
		}
		return result;
	}

	public int getTypes() {
		return getHost() != null ? BundleRevision.TYPE_FRAGMENT : 0;
	}

	public Bundle getBundle() {
		Object ref = getUserObject();
		if (ref instanceof BundleReference)
			return ((BundleReference) ref).getBundle();
		return null;
	}

	String getInternalNameSpace() {
		return Capability.BUNDLE_CAPABILITY;
	}

	public BundleWiring getBundleWiring() {
		synchronized (this.monitor) {
			if (bundleWiring != null || !isResolved() || (getTypes() & BundleRevision.TYPE_FRAGMENT) != 0)
				return bundleWiring;
			return bundleWiring = new DescriptionWiring();
		}
	}

	// Note that description wiring are identity equality based
	class DescriptionWiring implements BundleWiring {
		private volatile boolean valid = true;

		public Bundle getBundle() {
			return BundleDescriptionImpl.this.getBundle();
		}

		public boolean isInUse() {
			return valid && (isCurrent() || BundleDescriptionImpl.this.hasDependents());
		}

		void invalidate() {
			valid = false;
		}

		public boolean isCurrent() {
			return valid && !BundleDescriptionImpl.this.isRemovalPending();
		}

		public List<WiredCapability> getRequiredCapabilities(String capabilityNamespace) {
			if (!isInUse())
				return null;
			List<WiredCapability> result = new ArrayList<WiredCapability>();
			if (capabilityNamespace == null || Capability.BUNDLE_CAPABILITY.equals(capabilityNamespace)) {
				BundleDescription[] requires = getResolvedRequires();
				for (BundleDescription require : requires)
					result.add(require.getWiredCapability());
			}
			if (capabilityNamespace == null || Capability.PACKAGE_CAPABILITY.equals(capabilityNamespace)) {
				ExportPackageDescription[] imports = getResolvedImports();
				for (ExportPackageDescription importPkg : imports)
					result.add(importPkg.getWiredCapability());
			}
			GenericDescription[] genericRequires = getResolvedGenericRequires();
			for (GenericDescription require : genericRequires) {
				if (capabilityNamespace == null || capabilityNamespace.equals(require.getType()))
					result.add(require.getWiredCapability());
			}
			return result;
		}

		public List<WiredCapability> getProvidedCapabilities(String capabilityNamespace) {
			if (!isInUse())
				return null;
			List<WiredCapability> result = new ArrayList<WiredCapability>();
			if (capabilityNamespace == null || Capability.BUNDLE_CAPABILITY.equals(capabilityNamespace)) {
				result.add(BundleDescriptionImpl.this.getWiredCapability());
			}
			if (capabilityNamespace == null || Capability.PACKAGE_CAPABILITY.equals(capabilityNamespace)) {
				ExportPackageDescription[] exports = getSelectedExports();
				for (ExportPackageDescription importPkg : exports)
					result.add(importPkg.getWiredCapability());
			}
			GenericDescription[] genericCapabilities = getSelectedGenericCapabilities();
			for (GenericDescription capabilitiy : genericCapabilities) {
				if (capabilityNamespace == null || capabilityNamespace.equals(capabilitiy.getType()))
					result.add(capabilitiy.getWiredCapability());
			}
			return result;
		}

		public List<BundleRevision> getFragmentRevisions() {
			if (!isInUse())
				return null;
			return new ArrayList<BundleRevision>(Arrays.asList(getFragments()));
		}

		public ClassLoader getClassLoader() {
			SecurityManager sm = System.getSecurityManager();
			if (sm != null)
				sm.checkPermission(GET_CLASSLOADER_PERM);
			if (!isInUse())
				return null;
			return (ClassLoader) getBundleClassLoader();
		}

		private BundleClassLoader getBundleClassLoader() {
			Object proxy = BundleDescriptionImpl.this.getUserObject();
			if (proxy instanceof BundleHost)
				proxy = ((BundleHost) proxy).getLoaderProxy();
			if (proxy instanceof BundleLoaderProxy)
				return ((BundleLoaderProxy) proxy).getBundleLoader().createClassLoader();
			return null;
		}

		public BundleRevision getBundleRevision() {
			return BundleDescriptionImpl.this;
		}

		private boolean hasResourcePermission() {
			SecurityManager sm = System.getSecurityManager();
			if (sm != null) {
				try {
					sm.checkPermission(new AdminPermission(getBundle(), AdminPermission.RESOURCE));
				} catch (SecurityException e) {
					return false;
				}
			}
			return true;
		}

		public List<URL> findEntries(String path, String filePattern, int options) {
			if (!hasResourcePermission() || !isInUse())
				return null;
			@SuppressWarnings("unchecked")
			List<URL> result = Collections.EMPTY_LIST;
			BundleClassLoader bcl = getBundleClassLoader();
			if (bcl != null)
				result = bcl.findEntries(path, filePattern, options);
			return Collections.unmodifiableList(result);
		}

		public Collection<String> listResources(String path, String filePattern, int options) {
			if (!hasResourcePermission() || !isInUse())
				return null;
			@SuppressWarnings("unchecked")
			Collection<String> result = Collections.EMPTY_LIST;
			BundleClassLoader bcl = getBundleClassLoader();
			if (bcl != null)
				result = bcl.listResources(path, filePattern, options);
			return Collections.unmodifiableCollection(result);
		}

		public String toString() {
			return BundleDescriptionImpl.this.toString();
		}
	}
}
