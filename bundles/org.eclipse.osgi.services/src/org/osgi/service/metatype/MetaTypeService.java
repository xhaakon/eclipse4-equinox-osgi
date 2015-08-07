/*
 * Copyright (c) OSGi Alliance (2005, 2015). All Rights Reserved.
 * 
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

package org.osgi.service.metatype;

import org.osgi.annotation.versioning.ProviderType;
import org.osgi.framework.Bundle;

/**
 * The MetaType Service can be used to obtain meta type information for a
 * bundle. The MetaType Service will examine the specified bundle for meta type
 * documents to create the returned {@code MetaTypeInformation} object.
 * 
 * <p>
 * If the specified bundle does not contain any meta type documents, then a
 * {@code MetaTypeInformation} object will be returned that wrappers any
 * {@code ManagedService} or {@code ManagedServiceFactory} services registered
 * by the specified bundle that implement {@code MetaTypeProvider}. Thus the
 * MetaType Service can be used to retrieve meta type information for bundles
 * which contain a meta type documents or which provide their own
 * {@code MetaTypeProvider} objects.
 * 
 * @ThreadSafe
 * @author $Id: 3a507c14a83c95e92bfd5d4714928ec949fb8a14 $
 * @since 1.1
 */
@ProviderType
public interface MetaTypeService {
	/**
	 * Return the MetaType information for the specified bundle.
	 * 
	 * @param bundle The bundle for which meta type information is requested.
	 * @return A MetaTypeInformation object for the specified bundle.
	 */
	public MetaTypeInformation getMetaTypeInformation(Bundle bundle);

	/**
	 * Location of meta type documents. The MetaType Service will process each
	 * entry in the meta type documents directory.
	 */
	public final static String	METATYPE_DOCUMENTS_LOCATION	= "OSGI-INF/metatype";

	/**
	 * Capability name for meta type document processors.
	 * 
	 * <p>
	 * Used in {@code Provide-Capability} and {@code Require-Capability}
	 * manifest headers with the {@code osgi.extender} namespace. For example:
	 * 
	 * <pre>
	 * Require-Capability: osgi.extender;
	 *  filter:="(&amp;(osgi.extender=osgi.metatype)(version&gt;=1.3)(!(version&gt;=2.0)))"
	 * </pre>
	 * 
	 * @since 1.3
	 */
	public static final String	METATYPE_CAPABILITY_NAME	= "osgi.metatype";
}
