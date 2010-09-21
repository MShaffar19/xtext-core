/*******************************************************************************
 * Copyright (c) 2008 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package org.eclipse.xtext.resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.linking.ILinker;
import org.eclipse.xtext.parser.IEncodingProvider;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.parser.antlr.IReferableElementsUnloader;
import org.eclipse.xtext.parsetree.CompositeNode;
import org.eclipse.xtext.parsetree.NodeContentAdapter;
import org.eclipse.xtext.parsetree.SyntaxError;
import org.eclipse.xtext.parsetree.reconstr.Serializer;
import org.eclipse.xtext.resource.impl.ListBasedDiagnosticConsumer;
import org.eclipse.xtext.util.IResourceScopeCache;
import org.eclipse.xtext.util.StringInputStream;
import org.eclipse.xtext.util.Tuples;
import org.eclipse.xtext.validation.IConcreteSyntaxValidator;
import org.eclipse.xtext.validation.IConcreteSyntaxValidator.IDiagnosticAcceptor;

import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * An EMF resource that reads and writes models of an Xtext DSL.
 * 
 * @author Jan K�hnlein
 * @author Heiko Behrens
 * @author Dennis H�bner
 * @author Moritz Eysholdt
 * @author Sebastian Zarnekow
 * @author Sven Efftinge
 */
public class XtextResource extends ResourceImpl {

	public static String OPTION_RESOLVE_ALL = XtextResource.class.getName() + ".RESOLVE_ALL";

	/**
	 * @deprecated use {@link SaveOptions#configure(Map)} instead.
	 */
	@Deprecated
	public static String OPTION_FORMAT = XtextResource.class.getName() + ".FORMAT";

	/**
	 * @deprecated use {@link SaveOptions#configure(Map)} instead.
	 */
	@Deprecated
	public static String OPTION_SERIALIZATION_OPTIONS = XtextResource.class.getName() + ".SERIALIZATION_OPTIONS";

	public static String OPTION_ENCODING = XtextResource.class.getName() + ".DEFAULT_ENCODING";

	private boolean validationDisabled;

	private IParser parser;

	@Inject
	private ILinker linker;

	@Inject
	private IFragmentProvider fragmentProvider;
	
	private IFragmentProvider.Fallback fragmentProviderFallback = new IFragmentProvider.Fallback() {
		
		public String getFragment(EObject obj) {
			return XtextResource.super.getURIFragment(obj);
		}
		
		public EObject getEObject(String fragment) {
			return XtextResource.super.getEObject(fragment);
		}
	};

	@Inject
	private Serializer serializer;

	@Inject
	private IReferableElementsUnloader unloader;

	@Inject
	private IResourceServiceProvider resourceServiceProvider;

	@Inject
	private IConcreteSyntaxValidator validator;

	@Inject
	private IResourceScopeCache cache = IResourceScopeCache.NullImpl.INSTANCE;

	@Inject
	private IEncodingProvider encodingProvider;

	private String encoding;

	public IResourceServiceProvider getResourceServiceProvider() {
		return resourceServiceProvider;
	}

	public void setResourceServiceProvider(IResourceServiceProvider resourceServiceProvider) {
		this.resourceServiceProvider = resourceServiceProvider;
	}

	private IParseResult parseResult;

	@Inject
	protected void setInjectedParser(IParser parser) {
		this.parser = parser;
	}

	public XtextResource(URI uri) {
		super(uri);
	}

	public XtextResource() {
		super();
	}

	public IParseResult getParseResult() {
		return parseResult;
	}

	@Override
	protected void doLoad(InputStream inputStream, Map<?, ?> options) throws IOException {
		setEncodingFromOptions(options);
		IParseResult result = parser.parse(new InputStreamReader(inputStream, getEncoding()));
		updateInternalState(result);
	}

	protected void setEncodingFromOptions(Map<?, ?> options) {
		if (options != null) {
			Object encodingOption = options.get(OPTION_ENCODING);
			if (encodingOption instanceof String) {
				encoding = (String) encodingOption;
			}
		}
	}

	public String getEncoding() {
		if (encoding == null) {
			encoding = encodingProvider.getEncoding(getURI());
		}
		return encoding;
	}

	public void reparse(String newContent) throws IOException {
		clearInternalState();
		doLoad(new StringInputStream(newContent, getEncoding()), null);
		setModified(false);
	}

	protected void reattachModificationTracker(EObject element) {
		if (isTrackingModification() && element != null) {
			if (!element.eAdapters().contains(modificationTrackingAdapter))
				element.eAdapters().add(modificationTrackingAdapter);
			// copied from ResourceImpl.setTrackingModification
			for (TreeIterator<EObject> i = getAllProperContents(element); i.hasNext();) {
				EObject eObject = i.next();
				if (!eObject.eAdapters().contains(modificationTrackingAdapter))
					eObject.eAdapters().add(modificationTrackingAdapter);
			}
		}
	}

	@Override
	protected void doUnload() {
		super.doUnload();
		parseResult = null;
	}

	public void update(int offset, int replacedTextLength, String newText) {
		if (!isLoaded()) {
			throw new IllegalStateException("You can't update an unloaded resource.");
		}
		EObject oldRootObject = parseResult.getRootASTElement();
		CompositeNode oldRootNode = parseResult.getRootNode();
		parseResult = parser.reparse(oldRootNode, offset, replacedTextLength, newText);
		if (oldRootObject != null && oldRootObject != parseResult.getRootASTElement()) {
			unload(oldRootObject);
			getContents().remove(oldRootObject);
		}
		updateInternalState(parseResult);
	}

	protected void updateInternalState(IParseResult parseResult) {
		this.parseResult = parseResult;
		if (parseResult.getRootASTElement() != null && !getContents().contains(parseResult.getRootASTElement()))
			getContents().add(parseResult.getRootASTElement());
		addAdapterIfNeccessary(parseResult.getRootNode());
		reattachModificationTracker(parseResult.getRootASTElement());
		clearErrorsAndWarnings();
		addSyntaxErrors();
		doLinking();
	}
	
	protected void clearErrorsAndWarnings() {
		getWarnings().clear();
		getErrors().clear();
	}

	protected void addSyntaxErrors() {
		getErrors().addAll(createDiagnostics(parseResult));
	}

	protected void unload(EObject oldRootObject) {
		if (unloader != null) {
			unloader.unloadRoot(oldRootObject);
		}
	}

	protected void clearInternalState() {
		for (EObject content : getContents()) {
			unload(content);
		}
		getContents().clear();
		clearErrorsAndWarnings();
		this.parseResult = null;
	}

	protected void doLinking() {
		if (parseResult == null)
			return;
		if (parseResult.getRootASTElement() == null && !validationDisabled)
			return;

		final ListBasedDiagnosticConsumer consumer = new ListBasedDiagnosticConsumer();
		linker.linkModel(parseResult.getRootASTElement(), consumer);
		getErrors().addAll(consumer.getResult(Severity.ERROR));
		getWarnings().addAll(consumer.getResult(Severity.WARNING));
	}

	private void addAdapterIfNeccessary(CompositeNode node) {
		if (node != null && !NodeContentAdapter.containsNodeContentAdapter(node))
			NodeContentAdapter.createAdapterAndAddToNode(node);
	}

	@Override
	public EObject getEObject(String uriFragment) {
		if (fragmentProvider != null) {
			EObject result = fragmentProvider.getEObject(this, uriFragment, fragmentProviderFallback);
			return result;
		}
		EObject result = super.getEObject(uriFragment);
		return result;
	}

	@Override
	public String getURIFragment(final EObject object) {
		return cache.get(Tuples.pair(object, "fragment"), this, new Provider<String>() {
			public String get() {
				if (fragmentProvider != null) {
					String result = fragmentProvider.getFragment(object, fragmentProviderFallback);
					return result;
				}
				String result = XtextResource.super.getURIFragment(object);
				return result;
			}
		});
	}

	@Override
	public void doSave(OutputStream outputStream, Map<?, ?> options) throws IOException {
		if (contents.size() != 1)
			throw new IllegalStateException("The Xtext resource must contain exactly one root element");
		SaveOptions saveOptions = SaveOptions.getOptions(options);
		setEncodingFromOptions(options);
		serializer.serialize(contents.get(0), new OutputStreamWriter(outputStream, getEncoding()), saveOptions);
	}

	/**
	 * Creates {@link Diagnostic}s from {@link SyntaxError}s in {@link ParseResult}
	 * 
	 * @param list
	 *            of {@link SyntaxError}s
	 * @return list of {@link Diagnostic}
	 */
	private List<Diagnostic> createDiagnostics(IParseResult parseResult) {
		if (validationDisabled)
			return Collections.emptyList();

		List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
		for (SyntaxError error : parseResult.getParseErrors()) {
			diagnostics.add(new XtextSyntaxDiagnostic(error));
		}
		return diagnostics;
	}

	public IParser getParser() {
		return parser;
	}

	public void setParser(IParser parser) {
		this.parser = parser;
	}

	public IConcreteSyntaxValidator getConcreteSyntaxValidator() {
		return validator;
	}

	public List<org.eclipse.emf.common.util.Diagnostic> validateConcreteSyntax() {
		List<org.eclipse.emf.common.util.Diagnostic> diagnostics = new ArrayList<org.eclipse.emf.common.util.Diagnostic>();
		IDiagnosticAcceptor acceptor = new IConcreteSyntaxValidator.DiagnosticListAcceptor(diagnostics);
		for (EObject obj : getContents())
			validator.validateRecursive(obj, acceptor, new HashMap<Object, Object>());
		return diagnostics;
	}

	public ILinker getLinker() {
		return linker;
	}

	public void setLinker(ILinker linker) {
		this.linker = linker;
	}

	public IFragmentProvider getFragmentProvider() {
		return fragmentProvider;
	}

	public void setFragmentProvider(IFragmentProvider fragmentProvider) {
		this.fragmentProvider = fragmentProvider;
	}

	public Serializer getSerializer() {
		return serializer;
	}

	public void setSerializer(Serializer serializer) {
		this.serializer = serializer;
	}

	public void setParseResult(IParseResult parseResult) {
		this.parseResult = parseResult;
	}

	public boolean isValidationDisabled() {
		return validationDisabled;
	}

	public void setValidationDisabled(boolean validationDisabled) {
		this.validationDisabled = validationDisabled;
		if (validationDisabled) {
			clearErrorsAndWarnings();
		}
	}

	public void setUnloader(IReferableElementsUnloader unloader) {
		this.unloader = unloader;
	}

	public IReferableElementsUnloader getUnloader() {
		return unloader;
	}
	
	public IResourceScopeCache getCache() {
		return cache;
	}
	
	public void setCache(IResourceScopeCache cache) {
		this.cache = cache;
	}
}
