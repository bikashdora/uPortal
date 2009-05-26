/**
 * Copyright 2007 The JA-SIG Collaborative.  All rights reserved.
 * See license distributed with this file and
 * available online at http://www.uportal.org/license.html
 */
package org.jasig.portal.url;

/**
 * Represents the rendering state of the portal, all available states should be enumerated here
 * 
 * @author Eric Dalquist
 * @version $Revision$
 */
public enum UrlState {
    /**
     * Rendering with multiple portlets in the view
     */
    NORMAL,
    /**
     * Rendering a single portlet with portal provided UI
     */
    MAX,
    /**
     * Rendering a single portlet with portal markup but no portal rendered UI
     */
    DETACHED,
    /**
     * Rendering a single portlet where the portlet is responsible for all output, binary output is supported.
     */
    EXCLUSIVE,
    /**
     * Used by legacy content that is not using the new portal URL APIs
     */
    LEGACY;
}