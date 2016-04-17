/*******************************************************************************
 * Copyright (c) 2016 TypeFox GmbH (http://www.typefox.io) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.ide.editor.hierarchy

import org.eclipse.xtend.lib.annotations.Accessors
import org.eclipse.xtext.resource.IEObjectDescription
import org.eclipse.xtext.util.Wrapper

/**
 * @author kosyakov - Initial contribution and API
 * @since 2.10
 */
class DefaultHierarchyNode implements HierarchyNode {

	@Accessors
	HierarchyNode parent

	@Accessors(PUBLIC_SETTER)
	boolean mayHaveChildren

	@Accessors
	IEObjectDescription element

	@Accessors(PUBLIC_GETTER)
	val references = <HierarchyNodeReference>newArrayList

	Wrapper<Boolean> recursive

	override getNavigationElement() {
		return references.head ?: element
	}

	override boolean isRecursive() {
		if (recursive === null)
			recursive = Wrapper.wrap(internalIsRecursive)
		return recursive.get
	}

	protected def boolean internalIsRecursive() {
		var node = parent
		while (node !== null) {
			if (node.element.EObjectURI == element.EObjectURI)
				return true
			node = node.parent
		}
		return false
	}

	override mayHaveChildren() {
		mayHaveChildren
	}

}
