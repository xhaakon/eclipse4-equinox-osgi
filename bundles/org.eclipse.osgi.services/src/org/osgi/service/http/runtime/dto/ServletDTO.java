/*
 * Copyright (c) OSGi Alliance (2012, 2014). All Rights Reserved.
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

package org.osgi.service.http.runtime.dto;


/**
 * Represents a {@code javax.servlet.Servlet} currently being used by a servlet
 * context.
 * 
 * @NotThreadSafe
 * @author $Id: 4b078f335cc57226a758012e797af8a25b2bc1ef $
 */
public class ServletDTO extends BaseServletDTO {
	/**
	 * The request mappings for the servlet.
	 * 
	 * <p>
	 * The specified patterns are used to determine whether a request is mapped
	 * to the servlet. This array is never empty.
	 */
	public String[]				patterns;
}
