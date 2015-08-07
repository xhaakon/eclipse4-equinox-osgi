/*******************************************************************************
 * Copyright (c) 2006, 2012 Cognos Incorporated, IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 *******************************************************************************/
package org.eclipse.osgi.internal.url;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.internal.framework.EquinoxBundle;
import org.eclipse.osgi.internal.framework.EquinoxContainer;
import org.osgi.framework.*;

/*
 * An abstract class for handler factory impls (Stream and Content) that can 
 * handle environments running multiple osgi frameworks with the same VM.
 */
public abstract class MultiplexingFactory {
	protected EquinoxContainer container;
	protected BundleContext context;
	private List<Object> factories; // list of multiplexed factories

	// used to get access to the protected SecurityManager#getClassContext method
	static class InternalSecurityManager extends SecurityManager {
		public Class<?>[] getClassContext() {
			return super.getClassContext();
		}
	}

	private static InternalSecurityManager internalSecurityManager = new InternalSecurityManager();

	MultiplexingFactory(BundleContext context, EquinoxContainer container) {
		this.context = context;
		this.container = container;

	}

	abstract public void setParentFactory(Object parentFactory);

	abstract public Object getParentFactory();

	public boolean isMultiplexing() {
		return getFactories() != null;
	}

	public void register(Object factory) {
		// set parent for each factory so they can do proper delegation
		try {
			Class<?> clazz = factory.getClass();
			Method setParentFactory = clazz.getMethod("setParentFactory", new Class[] {Object.class}); //$NON-NLS-1$
			setParentFactory.invoke(factory, new Object[] {getParentFactory()});
		} catch (Exception e) {
			container.getLogServices().log(MultiplexingFactory.class.getName(), FrameworkLogEntry.ERROR, "register", e); //$NON-NLS-1$
			throw new RuntimeException(e.getMessage(), e);
		}
		addFactory(factory);
	}

	public void unregister(Object factory) {
		removeFactory(factory);
		// close the service tracker
		try {
			// this is brittle; if class does not directly extend MultplexingFactory then this method will not exist, but we do not want a public method here
			Method closeTracker = factory.getClass().getSuperclass().getDeclaredMethod("closePackageAdminTracker", (Class[]) null); //$NON-NLS-1$
			closeTracker.setAccessible(true); // its a private method
			closeTracker.invoke(factory, (Object[]) null);
		} catch (Exception e) {
			container.getLogServices().log(MultiplexingFactory.class.getName(), FrameworkLogEntry.ERROR, "unregister", e); //$NON-NLS-1$
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	public Object designateSuccessor() {
		List<Object> released = releaseFactories();
		// Note that we do this outside of the sync block above.
		// This is only possible because we do additional locking outside of
		// this class to ensure no other threads are trying to manipulate the
		// list of registered factories.  See Framework class the following methods:
		// Framework.installURLStreamHandlerFactory(BundleContext, FrameworkAdaptor)
		// Framework.installContentHandlerFactory(BundleContext, FrameworkAdaptor)
		// Framework.uninstallURLStreamHandlerFactory
		// Framework.uninstallContentHandlerFactory()
		if (released == null || released.isEmpty())
			return getParentFactory();
		Object successor = released.remove(0);
		try {
			Class<?> clazz = successor.getClass();
			Method register = clazz.getMethod("register", new Class[] {Object.class}); //$NON-NLS-1$		
			for (Object r : released) {
				register.invoke(successor, new Object[] {r});
			}
		} catch (Exception e) {
			container.getLogServices().log(MultiplexingFactory.class.getName(), FrameworkLogEntry.ERROR, "designateSuccessor", e); //$NON-NLS-1$
			throw new RuntimeException(e.getMessage(), e);
		}
		closePackageAdminTracker(); // close tracker
		return successor;
	}

	private void closePackageAdminTracker() {
		// Do nothing, just here for posterity
	}

	public Object findAuthorizedFactory(List<Class<?>> ignoredClasses) {
		List<Object> current = getFactories();
		Class<?>[] classStack = internalSecurityManager.getClassContext();
		for (int i = 0; i < classStack.length; i++) {
			Class<?> clazz = classStack[i];
			if (clazz == InternalSecurityManager.class || clazz == MultiplexingFactory.class || ignoredClasses.contains(clazz))
				continue;
			if (hasAuthority(clazz))
				return this;
			if (current == null)
				continue;
			for (Object factory : current) {
				try {
					Method hasAuthorityMethod = factory.getClass().getMethod("hasAuthority", new Class[] {Class.class}); //$NON-NLS-1$
					if (((Boolean) hasAuthorityMethod.invoke(factory, new Object[] {clazz})).booleanValue()) {
						return factory;
					}
				} catch (Exception e) {
					container.getLogServices().log(MultiplexingFactory.class.getName(), FrameworkLogEntry.ERROR, "findAuthorizedURLStreamHandler-loop", e); //$NON-NLS-1$
					throw new RuntimeException(e.getMessage(), e);
				}
			}
		}
		// Instead of returning null here, this factory is returned;
		// This means the root factory may provide protocol handlers for call stacks
		// that have no classes loaded by an bundle class loader.
		return this;
	}

	public boolean hasAuthority(Class<?> clazz) {
		Bundle b = FrameworkUtil.getBundle(clazz);
		if (!(b instanceof EquinoxBundle)) {
			return false;
		}
		return (container.getStorage().getModuleContainer() == ((EquinoxBundle) b).getModule().getContainer());
	}

	private synchronized List<Object> getFactories() {
		return factories;
	}

	private synchronized List<Object> releaseFactories() {
		if (factories == null)
			return null;

		List<Object> released = new LinkedList<Object>(factories);
		factories = null;
		return released;
	}

	private synchronized void addFactory(Object factory) {
		List<Object> updated = (factories == null) ? new LinkedList<Object>() : new LinkedList<Object>(factories);
		updated.add(factory);
		factories = updated;
	}

	private synchronized void removeFactory(Object factory) {
		List<Object> updated = new LinkedList<Object>(factories);
		updated.remove(factory);
		factories = updated.isEmpty() ? null : updated;
	}
}
