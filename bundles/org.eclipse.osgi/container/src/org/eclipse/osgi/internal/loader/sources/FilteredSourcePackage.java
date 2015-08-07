/*******************************************************************************
 * Copyright (c) 2004, 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.loader.sources;

import java.net.URL;
import java.util.*;
import org.eclipse.osgi.internal.loader.BundleLoader;
import org.eclipse.osgi.util.ManifestElement;

public class FilteredSourcePackage extends SingleSourcePackage {
	private static final char ALL = '*';
	private final String[] includes;
	private final String[] excludes;

	public FilteredSourcePackage(String name, BundleLoader supplier, String includes, String excludes) {
		super(name, supplier);
		this.includes = includes != null ? ManifestElement.getArrayFromList(includes) : null;
		this.excludes = excludes != null ? ManifestElement.getArrayFromList(excludes) : null;
	}

	public URL getResource(String name) {
		if (isFiltered(name, getId()))
			return null;
		return super.getResource(name);
	}

	public Enumeration<URL> getResources(String name) {
		if (isFiltered(name, getId()))
			return null;
		return super.getResources(name);
	}

	public Class<?> loadClass(String name) throws ClassNotFoundException {
		if (isFiltered(name, getId()))
			return null;
		return super.loadClass(name);
	}

	private boolean isFiltered(String name, String pkgName) {
		String lastName = getName(name, pkgName);
		return !isIncluded(lastName) || isExcluded(lastName);
	}

	private String getName(String name, String pkgName) {
		if (!BundleLoader.DEFAULT_PACKAGE.equals(pkgName) && pkgName.length() + 1 <= name.length())
			return name.substring(pkgName.length() + 1);
		return name;
	}

	private boolean isIncluded(String name) {
		if (includes == null)
			return true;
		return isInList(name, includes);
	}

	private boolean isExcluded(String name) {
		if (excludes == null)
			return false;
		return isInList(name, excludes);
	}

	private boolean isInList(String name, String[] list) {
		for (int i = 0; i < list.length; i++) {
			int len = list[i].length();
			if (len == 0)
				continue;
			if (list[i].charAt(0) == ALL && len == 1)
				return true; // handles "*" wild card
			if (list[i].charAt(len - 1) == ALL)
				if (name.startsWith(list[i].substring(0, len - 1)))
					return true;
			if (name.equals(list[i]))
				return true;

		}
		return false;
	}

	@Override
	public Collection<String> listResources(String path, String filePattern) {
		Collection<String> result = super.listResources(path, filePattern);
		for (Iterator<String> resources = result.iterator(); resources.hasNext();) {
			String resource = resources.next();
			int lastSlash = resource.lastIndexOf('/');
			String fileName = lastSlash >= 0 ? resource.substring(lastSlash + 1) : resource;
			if (!isIncluded(fileName) || isExcluded(fileName))
				resources.remove();
		}
		return result;
	}

}
