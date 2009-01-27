/*******************************************************************************
 * Copyright (c) 2008 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package org.eclipse.xtext.parser.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.parser.ParseException;
import org.eclipse.xtext.parser.ParseResult;
import org.eclipse.xtext.parser.antlr.IAntlrParser;
import org.eclipse.xtext.parsetree.AbstractNode;
import org.eclipse.xtext.parsetree.CompositeNode;
import org.eclipse.xtext.parsetree.LeafNode;
import org.eclipse.xtext.parsetree.NodeUtil;
import org.eclipse.xtext.parsetree.SyntaxError;
import org.eclipse.xtext.util.StringInputStream;

/**
 * @author Jan K�hnlein - Initial contribution and API
 * 
 */
public class PartialParsingUtil {

	private static final Logger log = Logger.getLogger(PartialParsingUtil.class);

	@SuppressWarnings("unchecked")
	public static IParseResult reparse(IAntlrParser parser, CompositeNode rootNode, int offset, int replacedTextLength,
			String newText) {
		if (offset + replacedTextLength > rootNode.getTotalLength()) {
			log.error("Invalid replace region offset=" + offset + " length=" + replacedTextLength + " originalLength="
					+ rootNode.getTotalLength());
			return fullyReparse(parser, rootNode, offset, replacedTextLength, newText);
		}
		PartialParsingPointers parsingPointers = calculatePartialParsingPointers(rootNode, offset, replacedTextLength);
		List<CompositeNode> validReplaceRootNodes = parsingPointers.getValidReplaceRootNodes();
		CompositeNode replaceNode = null;
		String reparseRegion = "";
		for (int i = validReplaceRootNodes.size() - 1; i >= 0; --i) {
			replaceNode = validReplaceRootNodes.get(i);
			reparseRegion = insertChangeIntoReplaceRegion(replaceNode, offset, replacedTextLength, newText);
			if (!"".equals(reparseRegion)) {
				break;
			}
		}
		if (replaceNode == null || reparseRegion.equals("")) {
			// no replaceNode -> no rule to call
			// If region is empty, any entryRule is likely to fail.
			// Let parser decide whether empty model is valid
			return parser.parse(new StringInputStream(""));
		}
		String entryRuleName = parsingPointers.findEntryRuleName(replaceNode);
		ParseResult parseResult = null;
		try {
			parseResult = (ParseResult) parser.parse(entryRuleName, new StringInputStream(
					reparseRegion));
		}
		catch (ParseException exc) {
		}
		if (parseResult == null || parseResult.getParseErrors() != null && !parseResult.getParseErrors().isEmpty() && !rootNode.equals(replaceNode)) {
			// on error fully reparse
			return fullyReparse(parser, rootNode, offset, replacedTextLength, newText);
		}
		if (rootNode.equals(replaceNode))
			return parseResult;
		EObject astParentElement = parsingPointers.findASTParentElement(replaceNode);
		EObject replaceAstElement = parsingPointers.findASTReplaceElement(replaceNode);
		if (astParentElement != null) {
			String featureName = parsingPointers.findASTContainmentFeatureName(replaceNode);
			EClass astParentEClass = astParentElement.eClass();
			EStructuralFeature feature = astParentEClass.getEStructuralFeature(featureName);
			if (feature.isMany()) {
				List featureValueList = (List) astParentElement.eGet(feature);
				int astElementChildIndex = featureValueList.indexOf(replaceAstElement);
				featureValueList.set(astElementChildIndex, parseResult.getRootASTElement());
			}
			else {
				astParentElement.eSet(feature, parseResult.getRootASTElement());
			}
			parseResult.setRootASTElement(NodeUtil.getASTElementForRootNode(rootNode));
		}
		parseResult.getRootNode().setGrammarElement(replaceNode.getGrammarElement());
		if (replaceNode != rootNode) {
			CompositeNode replaceNodeParent = replaceNode.getParent();
			EList<AbstractNode> replaceNodeSiblings = replaceNodeParent.getChildren();
			int nodeChildIndex = replaceNodeSiblings.indexOf(replaceNode);
			transferLookAhead(replaceNode, parseResult.getRootNode());
			replaceNodeSiblings.set(nodeChildIndex, parseResult.getRootNode());
			parseResult.setRootNode(rootNode);
		}
		return parseResult;
	}
	
	private static void transferLookAhead(CompositeNode from, CompositeNode to) {
		if (!from.getLookaheadLeafNodes().isEmpty()) {
			final boolean wasEmpty = to.getLookaheadLeafNodes().isEmpty();
			int lookAhead = from.getLookaheadLeafNodes().size();
			// add every old lookAhead leaf node to the new lookAhead if
			// it precedes the old node
			final Iterator<LeafNode> lookAheadIter = from.getLookaheadLeafNodes().iterator();
			int idx = 0;
			while(lookAheadIter.hasNext()) {
				final LeafNode leaf = lookAheadIter.next();
				if (leaf.getTotalOffset() < from.getTotalOffset())
				{
					to.getLookaheadLeafNodes().add(idx, leaf);
					idx++;
					lookAhead--;
				} else
					break;
			}
			if (wasEmpty) {
				// We assume, that the lookahead-length should be the same as before
				final Iterator<LeafNode> leafIter = to.getLeafNodes().iterator();
				while(leafIter.hasNext() && lookAhead > 0) {
					LeafNode nextLeaf = leafIter.next();
					while(nextLeaf.isHidden() && leafIter.hasNext())
						nextLeaf = leafIter.next();
					if (!nextLeaf.isHidden()) {
						to.getLookaheadLeafNodes().add(nextLeaf);
						lookAhead--;
					}
				}
			}
		}
	}

	private static IParseResult fullyReparse(IParser parser, CompositeNode rootNode, int offset,
			int replacedTextLength, String newText) {
		String reparseRegion = insertChangeIntoReplaceRegion(rootNode, offset, replacedTextLength, newText);
		return parser.parse(new StringInputStream(reparseRegion));
	}

	/**
	 * @param replaceRootNode
	 * @param offset
	 * @param replacedTextLength
	 */
	public static String insertChangeIntoReplaceRegion(CompositeNode replaceRootNode, int offset,
			int replacedTextLength, String newText) {
		String originalRegion = replaceRootNode.serialize();
		int changeOffset = offset - replaceRootNode.getTotalOffset();
		StringBuffer reparseRegion = new StringBuffer();
		reparseRegion.append(originalRegion.substring(0, changeOffset));
		reparseRegion.append(newText);
		// TODO: rausragende region
		reparseRegion.append(originalRegion.substring(changeOffset + replacedTextLength));
		return reparseRegion.toString();
	}

	public static PartialParsingPointers calculatePartialParsingPointers(CompositeNode rootNode, int offset,
			int replacedTextLength) {
		if (offset == rootNode.getTotalLength() && offset != 0) {
			// newText is appended, so look for the last original character instead 
			--offset;
			replacedTextLength = 1;
		}
		// include any existing parse errors
		Range range = new Range(offset, offset + replacedTextLength);
		mergeErrorRange(rootNode, range);
		
		offset = range.fromOffset;
//		EList<SyntaxError> allErrors = rootNode.allSyntaxErrors(); // uses TreeIterator and is not as fast as it should be
		List<CompositeNode> nodesEnclosingRegion = collectNodesEnclosingChangeRegion(rootNode, range.fromOffset,
				range.toOffset - range.fromOffset);
		List<CompositeNode> validReplaceRootNodes = internalFindValidReplaceRootNodeForChangeRegion(
				nodesEnclosingRegion, range.fromOffset,
				range.toOffset - range.fromOffset);
		
		if (validReplaceRootNodes.isEmpty()) {
			validReplaceRootNodes = Collections.<CompositeNode> singletonList(rootNode);
		}
		return new PartialParsingPointers(rootNode, offset, replacedTextLength, validReplaceRootNodes,
				nodesEnclosingRegion);
	}
	
	public static void mergeErrorRange(AbstractNode node, Range result) {
		if (node.getSyntaxError() != null)
			result.merge(node.getSyntaxError());
		else if (node instanceof CompositeNode) {
			for(AbstractNode child: ((CompositeNode)node).getChildren()) {
				mergeErrorRange(child, result);
			}
		}
	}

	static class Range {
		int fromOffset;
		int toOffset;
		
		Range(SyntaxError error) {
			this(error.getNode());
		}
		
		Range(AbstractNode node) {
			this(node.getTotalOffset(), node.getTotalOffset() + node.getTotalLength());
		}
		
		Range(int fromOffest, int toOffset) {
			this.fromOffset = fromOffest;
			this.toOffset = toOffset;
		}
		
		void merge(Range range) {
			this.fromOffset = Math.min(fromOffset, range.fromOffset);
			this.toOffset = Math.max(toOffset, range.toOffset);
		}
		
		void merge(SyntaxError error) {
			merge(new Range(error));
		}
		
		@Override
		public String toString() {
			return fromOffset + " - " + toOffset;
		}
	}
	
	/**
	 * Collects a list of all nodes containing the change region
	 */
	private static List<CompositeNode> collectNodesEnclosingChangeRegion(CompositeNode parent, int offset, int length) {
		List<CompositeNode> nodesEnclosingRegion = new ArrayList<CompositeNode>();
		if (nodeEnclosesRegion(parent, offset, length)) {
			collectNodesEnclosingChangeRegion(parent, offset, length, nodesEnclosingRegion);
		}
		return nodesEnclosingRegion;
	}

	private static void collectNodesEnclosingChangeRegion(CompositeNode parent, int offset, int length,
			List<CompositeNode> nodesEnclosingRegion) {
		nodesEnclosingRegion.add(parent);
		EList<AbstractNode> children = parent.getChildren();
		// Hack: iterate children backward, so we evaluate the node.length()
		// function less often
		for (int i = children.size() - 1; i >= 0; --i) {
			AbstractNode child = children.get(i);
			if (child instanceof CompositeNode) {
				CompositeNode childCompositeNode = (CompositeNode) child;
				if (nodeEnclosesRegion(childCompositeNode, offset, length)) {
					collectNodesEnclosingChangeRegion(childCompositeNode, offset, length, nodesEnclosingRegion);
					break;
				}
			}
		}
	}

	private static boolean nodeEnclosesRegion(CompositeNode node, int offset, int length) {
		return node.getTotalOffset() <= offset && node.getTotalOffset() + node.getTotalLength() >= offset + length;
	}

	/**
	 * Investigates the composite nodes containing the changed region and
	 * collects a list of nodes which could possibly replaced by a partial
	 * parse. Such a node has a parent that consumes all his current lookahead
	 * tokens and all of these tokens are located before the changed region.
	 * 
	 * @param nodesEnclosingRegion
	 * @param offset
	 * @param length
	 * @return
	 */
	private static List<CompositeNode> internalFindValidReplaceRootNodeForChangeRegion(
			List<CompositeNode> nodesEnclosingRegion, int offset, int length) {
		List<LeafNode> lookaheadNodes = new ArrayList<LeafNode>();
		List<CompositeNode> validReplaceRootNodes = new ArrayList<CompositeNode>();

		boolean lookaheadChanged = false;
		for (CompositeNode node : nodesEnclosingRegion) {
			EList<LeafNode> parentsLookaheadNodes = node.getLookaheadLeafNodes();
			if (!parentsLookaheadNodes.isEmpty()) {
				int index = lookaheadNodes.indexOf(parentsLookaheadNodes.get(0));
				if (index > 0) {
					// remove lookahead nodes common with grandpa
					while (lookaheadNodes.size() > index) {
						lookaheadNodes.remove(index);
					}
				}
				lookaheadNodes.addAll(parentsLookaheadNodes);
				lookaheadChanged = true;
			}
			if (lookaheadChanged) {
				LeafNode lastLookaheadLeafNode = lookaheadNodes.get(lookaheadNodes.size() - 1);
				if (nodeIsBeforeRegion(lastLookaheadLeafNode, offset)) {
					// last lookahead token is before changed region
					// and parent has consumed all current lookahead tokens
					NodeUtil.dumpCompositeNodeInfo("Possible entry node: ", node);
					validReplaceRootNodes.add(node);
					lookaheadChanged = false;
				}
			}
		}
		return validReplaceRootNodes;
	}

	private static boolean nodeIsBeforeRegion(LeafNode node, int offset) {
		return node.getTotalOffset() + node.getTotalLength() <= offset;
	}

}