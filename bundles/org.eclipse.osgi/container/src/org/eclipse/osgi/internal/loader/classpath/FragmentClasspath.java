/*******************************************************************************
 * Copyright (c) 2005, 2013 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.internal.loader.classpath;

import java.io.IOException;
import org.eclipse.osgi.container.ModuleContainerAdaptor.ContainerEvent;
import org.eclipse.osgi.storage.BundleInfo.Generation;

/**
 * A FragmentClasspath contains all the <code>ClasspathEntry</code> objects for a fragment
 * <code>BaseData</code>.
 * @since 3.2
 */
public class FragmentClasspath {
	private final Generation generation;
	// Note that PDE has internal dependency on this field type/name (bug 267238)
	private final ClasspathEntry[] entries;

	public FragmentClasspath(Generation generation, ClasspathEntry[] entries) {
		this.generation = generation;
		this.entries = entries;
	}

	/**
	 * Returns the fragment Generation for this FragmentClasspath
	 * @return the fragment Generation for this FragmentClasspath
	 */
	public Generation getGeneration() {
		return generation;
	}

	/**
	 * Returns the fragment classpath entries for this FragmentClasspath
	 * @return the fragment classpath entries for this FragmentClasspath
	 */
	public ClasspathEntry[] getEntries() {
		return entries;
	}

	/**
	 * Closes all the classpath entry resources for this FragmentClasspath.
	 *
	 */
	public void close() {
		for (int i = 0; i < entries.length; i++) {
			try {
				entries[i].getBundleFile().close();
			} catch (IOException e) {
				generation.getBundleInfo().getStorage().getAdaptor().publishContainerEvent(ContainerEvent.ERROR, generation.getRevision().getRevisions().getModule(), e);
			}
		}
	}

}
