/*******************************************************************************
 * Copyright (c) 2012 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.xbase.typesystem.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.impl.ENotificationImpl;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.xtext.common.types.JvmExecutable;
import org.eclipse.xtext.common.types.JvmFormalParameter;
import org.eclipse.xtext.common.types.JvmIdentifiableElement;
import org.eclipse.xtext.common.types.JvmOperation;
import org.eclipse.xtext.common.types.JvmType;
import org.eclipse.xtext.common.types.JvmTypeParameter;
import org.eclipse.xtext.common.types.JvmTypeParameterDeclarator;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.xbase.XAssignment;
import org.eclipse.xtext.xbase.XExpression;
import org.eclipse.xtext.xbase.scoping.batch.BucketedEObjectDescription;
import org.eclipse.xtext.xbase.typesystem.computation.ILinkingCandidate;
import org.eclipse.xtext.xbase.typesystem.computation.ITypeComputationState;
import org.eclipse.xtext.xbase.typesystem.computation.ITypeExpectation;
import org.eclipse.xtext.xbase.typesystem.conformance.ConformanceHint;
import org.eclipse.xtext.xbase.typesystem.references.AnyTypeReference;
import org.eclipse.xtext.xbase.typesystem.references.ArrayTypeReference;
import org.eclipse.xtext.xbase.typesystem.references.CompoundTypeReference;
import org.eclipse.xtext.xbase.typesystem.references.ITypeReferenceOwner;
import org.eclipse.xtext.xbase.typesystem.references.LightweightMergedBoundTypeArgument;
import org.eclipse.xtext.xbase.typesystem.references.LightweightTypeReference;
import org.eclipse.xtext.xbase.typesystem.references.ParameterizedTypeReference;
import org.eclipse.xtext.xbase.typesystem.references.UnboundTypeReference;
import org.eclipse.xtext.xbase.typesystem.references.WildcardTypeReference;
import org.eclipse.xtext.xbase.typesystem.util.BoundTypeArgumentSource;
import org.eclipse.xtext.xbase.typesystem.util.ConstraintVisitingInfo;
import org.eclipse.xtext.xbase.typesystem.util.DeferredTypeParameterHintCollector;
import org.eclipse.xtext.xbase.typesystem.util.StateAwareDeferredTypeParameterHintCollector;
import org.eclipse.xtext.xbase.typesystem.util.TypeArgumentFromComputedTypeCollector;
import org.eclipse.xtext.xbase.typesystem.util.TypeParameterByConstraintSubstitutor;
import org.eclipse.xtext.xbase.typesystem.util.TypeParameterSubstitutor;
import org.eclipse.xtext.xbase.typesystem.util.UnboundTypeParameterPreservingSubstitutor;
import org.eclipse.xtext.xbase.typesystem.util.VarianceInfo;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @author Sebastian Zarnekow - Initial contribution and API
 * TODO Javadoc
 */
public abstract class AbstractLinkingCandidate implements ILinkingCandidate { 
	
	@NonNullByDefault
	protected class LinkingTypeComputationState extends AbstractStackedTypeComputationState {

		private final LightweightTypeReference expectedType;
		private final ConformanceHint defaultHint;

		public LinkingTypeComputationState(AbstractTypeComputationState parent,
				LightweightTypeReference expectedType, @Nullable ConformanceHint defaultHint) {
			super(parent.getResolvedTypes(), parent.getFeatureScopeSession(), parent.getResolver(), parent);
			this.expectedType = expectedType;
			this.defaultHint = defaultHint;
		}

		@Override
		public List<AbstractTypeExpectation> getImmediateExpectations(AbstractTypeComputationState actualState) {
			AbstractTypeExpectation result = createTypeExpectation(expectedType, actualState, false, defaultHint);
			return Collections.singletonList(result);
		}
		
		protected AbstractTypeExpectation createTypeExpectation(@Nullable LightweightTypeReference expectedType,
				AbstractTypeComputationState actualState, boolean returnType, @Nullable ConformanceHint hint) {
			AbstractTypeExpectation result = null;
			if (expectedType != null) {
				LightweightTypeReference copied = expectedType.copyInto(actualState.getReferenceOwner());
				result = new ObservableTypeExpectation(copied, actualState, returnType, hint);
			} else {
				result = new NoExpectation(actualState, returnType);
			}
			return result;
		}
		
		protected LightweightTypeReference getExpectedType() {
			return expectedType;
		}
		
	}
	
	@NonNullByDefault
	protected class ObservableTypeExpectation extends TypeExpectation {

		private ConformanceHint conformanceHint;

		public ObservableTypeExpectation(LightweightTypeReference expectedType, AbstractTypeComputationState state, boolean returnType, @Nullable ConformanceHint conformanceHint) {
			super(expectedType, state, returnType);
			this.conformanceHint = conformanceHint;
		}
		
		@Override
		public void acceptActualType(LightweightTypeReference type, ConformanceHint... hints) {
			ConformanceHint[] actualHints = hints;
			if (this.conformanceHint != null) {
				actualHints = new ConformanceHint[hints.length + 1];
				System.arraycopy(hints, 0, actualHints, 0, hints.length);
				actualHints[hints.length] = conformanceHint;
			}				
			accept(this, type, actualHints);
			super.acceptActualType(type, actualHints);
		}

		@Override
		public ObservableTypeExpectation copyInto(ITypeReferenceOwner referenceOwner) {
			LightweightTypeReference expectedType = getExpectedType();
			if (expectedType == null || expectedType.isOwnedBy(referenceOwner))
				return this;
			return new ObservableTypeExpectation(expectedType.copyInto(referenceOwner), getState(), isReturnType(), conformanceHint);
		}
		
	}
	
	private final IEObjectDescription description;
	private final ExpressionTypeComputationState state;
	private final XExpression expression;
	private final Map<JvmTypeParameter, LightweightMergedBoundTypeArgument> typeParameterMapping;
	
	protected AbstractLinkingCandidate(XExpression expression, IEObjectDescription description,
			ExpressionTypeComputationState state) {
		this.expression = expression;
		this.description = description;
		this.state = state;
		this.typeParameterMapping = initializeTypeParameterMapping();
	}

	protected Map<JvmTypeParameter, LightweightMergedBoundTypeArgument> initializeTypeParameterMapping() {
		Map<JvmTypeParameter, LightweightMergedBoundTypeArgument> typeParameterMapping;
		state.getResolvedTypes().acceptLinkingInformation(expression, this);
		List<JvmTypeParameter> declaredTypeParameters = getDeclaredTypeParameters();
		if (declaredTypeParameters.isEmpty()) {
			typeParameterMapping = Collections.emptyMap();
		} else {
			typeParameterMapping = Maps.newLinkedHashMap();
			List<LightweightTypeReference> explicitTypeArguments = getExplicitTypeArguments();
			int size = Math.min(declaredTypeParameters.size(), explicitTypeArguments.size());
			for(int i = 0; i < size; i++) {
				JvmTypeParameter declaredTypeParameter = declaredTypeParameters.get(i);
				LightweightTypeReference explicitTypeArgument = explicitTypeArguments.get(i);
				UnboundTypeReference typeReference = state.getResolvedTypes().createUnboundTypeReference(expression, declaredTypeParameter);
				// TODO create error if explicit type argument is wildcard
				typeReference.acceptHint(explicitTypeArgument.getUpperBoundSubstitute(), BoundTypeArgumentSource.EXPLICIT, expression, VarianceInfo.INVARIANT, VarianceInfo.INVARIANT);
				typeParameterMapping.put(declaredTypeParameter, new LightweightMergedBoundTypeArgument(typeReference, VarianceInfo.INVARIANT));
			}
			for(int i = size; i < declaredTypeParameters.size(); i++) {
				JvmTypeParameter declaredTypeParameter = declaredTypeParameters.get(i);
				// TODO add declaration hint
				UnboundTypeReference typeReference = state.getResolvedTypes().createUnboundTypeReference(expression, declaredTypeParameter);
				typeParameterMapping.put(declaredTypeParameter, new LightweightMergedBoundTypeArgument(typeReference, VarianceInfo.INVARIANT));
			}
		}
		return typeParameterMapping;
	}

	@NonNullByDefault
	public void accept(ObservableTypeExpectation expectation, LightweightTypeReference actual, ConformanceHint... hints) {
		LightweightTypeReference expectedType = expectation.getExpectedType();
		if (expectedType == null || actual instanceof AnyTypeReference) {
			return;
		}
		resolveAgainstActualType(expectedType, actual, expectation.getState());
	}
	
	public List<JvmFormalParameter> getDeclaredParameters() {
		JvmIdentifiableElement feature = getFeature();
		if (feature instanceof JvmExecutable) {
			return ((JvmExecutable) feature).getParameters();
		}
		return Collections.emptyList();
	}
	
	protected abstract boolean hasExplicitArguments();
	
	public List<JvmTypeParameter> getDeclaredTypeParameters() {
		JvmIdentifiableElement feature = getFeature();
		if (feature instanceof JvmTypeParameterDeclarator) {
			return ((JvmTypeParameterDeclarator) feature).getTypeParameters();
		}
		return Collections.emptyList();
	}
	
	public void apply() {
		computeArgumentTypes();
		JvmIdentifiableElement feature = getFeature();
		LightweightTypeReference featureType = getDeclaredType(feature);
		for(ITypeExpectation expectation: state.getImmediateExpectations()) {
			// TODO implement bounds / type parameter resolution
			// TODO consider expectation if any
			Map<JvmTypeParameter, LightweightMergedBoundTypeArgument> declaratorParameterMapping = getDeclaratorParameterMapping();
			TypeParameterSubstitutor<?> substitutor = new TypeParameterByConstraintSubstitutor(declaratorParameterMapping, expectation.getReferenceOwner()) {
				
				@Override
				@NonNull 
				public LightweightTypeReference doVisitUnboundTypeReference(@NonNull UnboundTypeReference reference,
						@NonNull ConstraintVisitingInfo param) {
					return copy(reference);
				}
				
				@Override
				@NonNull
				protected LightweightTypeReference getUnmappedSubstitute(@NonNull ParameterizedTypeReference reference, @NonNull JvmTypeParameter type, @NonNull ConstraintVisitingInfo visiting) {
					UnboundTypeReference result = getState().getResolvedTypes().createUnboundTypeReference(getExpression(), type);
					return result;
				}
				
			};
			substitutor.enhanceMapping(typeParameterMapping);
			// TODO enhance with expectation
			LightweightTypeReference substitutedFeatureType = substitutor.substitute(featureType).getUpperBoundSubstitute();
			deferredBindTypeArgument(expectation, substitutedFeatureType);
			expectation.acceptActualType(substitutedFeatureType, ConformanceHint.UNCHECKED); // TODO NATIVE, EXPECTATION_INDEPENDENT ?
		}
		getState().getResolvedTypes().mergeIntoParent();
	}
	
	@NonNullByDefault
	protected void deferredBindTypeArgument(ITypeExpectation expectation, LightweightTypeReference type) {
		LightweightTypeReference expectedType = expectation.getExpectedType();
		if (expectedType != null) { 
			DeferredTypeParameterHintCollector collector = new StateAwareDeferredTypeParameterHintCollector(getState().getReferenceOwner());
			collector.processPairedReferences(expectedType, type);
		}
	}
	
	private IExpressionArguments arguments;
	
	public void computeArgumentTypes() {
		initializeArgumentTypeComputation();
		while(arguments.hasUnprocessedArguments())
			computeArgumentType(arguments.getNextUnprocessedNextArgument());
	}

	private void initializeArgumentTypeComputation() {
		if (arguments != null)
			return;
		if (expression instanceof XAssignment && !(getFeature() instanceof JvmExecutable))
			arguments = new AssignmentArguments(this);
		else
			arguments = new FeatureCallArguments(this);
	}
	
	protected void computeArgumentType(int argumentIndex) {
		initializeArgumentTypeComputation();
		if (arguments.isProcessed(argumentIndex))
			return;
		UnboundTypeParameterPreservingSubstitutor substitutor = new UnboundTypeParameterPreservingSubstitutor(getDeclaratorParameterMapping(), state.getReferenceOwner());
		substitutor.enhanceMapping(typeParameterMapping);
		if (argumentIndex < arguments.getFixedArityArgumentCount()) {
			LightweightTypeReference parameterType = arguments.getDeclaredType(argumentIndex);
			LightweightTypeReference substitutedParameterType = substitutor.substitute(parameterType);
			XExpression argument = arguments.getArgument(argumentIndex);
			AbstractTypeComputationState argumentState = createLinkingTypeComputationState(substitutedParameterType);
			resolveArgumentType(argument, substitutedParameterType, argumentState);
			arguments.markProcessed(argumentIndex);
			return;
		}
		if (arguments.isVarArgs()) {
			ArrayTypeReference lastParameterType = arguments.getVarArgType();
			final LightweightTypeReference componentType = lastParameterType.getComponentType();
			
			ITypeComputationState argumentState = null;
			LightweightTypeReference substitutedComponentType = substitutor.substitute(componentType);
			if (arguments.isExactArity()) {
				LinkingTypeComputationState first = createVarArgTypeComputationState(substitutedComponentType);
				ArrayTypeReference arrayTypeReference = new ArrayTypeReference(substitutedComponentType.getOwner(), substitutedComponentType);
				LinkingTypeComputationState second = createLinkingTypeComputationState(arrayTypeReference);
				argumentState = new CompoundTypeComputationState(substitutedComponentType.getOwner(), first, second);
			} else {
				argumentState = createVarArgTypeComputationState(substitutedComponentType);
			}
			while(arguments.hasUnprocessedArguments()) {
				int localNextArgumentIndex = arguments.getNextUnprocessedNextArgument();
				XExpression argument = arguments.getArgument(localNextArgumentIndex);
				resolveArgumentType(argument, substitutedComponentType, argumentState);
				arguments.markProcessed(localNextArgumentIndex);
			}
		} else {
			XExpression argument = arguments.getArgument(argumentIndex);
			resolveArgumentType(argument, null, getState().withNonVoidExpectation());
			arguments.markProcessed(argumentIndex);
		}
	}

	protected LinkingTypeComputationState createLinkingTypeComputationState(LightweightTypeReference expectedType) {
		return new LinkingTypeComputationState(state, expectedType.getLowerBoundSubstitute(), null);
	}
	
	protected LinkingTypeComputationState createVarArgTypeComputationState(LightweightTypeReference expectedType) {
		return new LinkingTypeComputationState(state, expectedType.getLowerBoundSubstitute(), ConformanceHint.VAR_ARG);
	}
	
	protected JvmTypeParameter getTypeParameter(@Nullable LightweightTypeReference referenceToTypeParameter) {
		if (referenceToTypeParameter == null)
			return null;
		if (referenceToTypeParameter instanceof UnboundTypeReference) {
			UnboundTypeReference unbound = (UnboundTypeReference) referenceToTypeParameter;
			if (unbound.getResolvedTo() != null) {
				return getTypeParameter(unbound.getResolvedTo());
			}
			return unbound.getTypeParameter();
		}
		JvmType result = referenceToTypeParameter.getType();
		if (result instanceof JvmTypeParameter) {
			return (JvmTypeParameter) result;
		} else if (result == null) { 
			if (referenceToTypeParameter instanceof WildcardTypeReference) {
				// TODO multiple type parameters? Invalid in Java but possible during type inference?
				WildcardTypeReference wildcard = (WildcardTypeReference) referenceToTypeParameter;
				for(LightweightTypeReference upperBound: wildcard.getUpperBounds()) {
					JvmTypeParameter wildcardTypeParam = getTypeParameter(upperBound);
					if (wildcardTypeParam != null) {
						return wildcardTypeParam;
					}
				}
				// either null or ok
				return getTypeParameter(wildcard.getLowerBound());
			} else if (referenceToTypeParameter instanceof CompoundTypeReference) {
				// TODO multiple type parameters?
				CompoundTypeReference compound = (CompoundTypeReference) referenceToTypeParameter;
				for(LightweightTypeReference component: compound.getComponents()) {
					JvmTypeParameter componentTypeParam = getTypeParameter(component);
					if (componentTypeParam != null) {
						return componentTypeParam;
					}
				}
			}
		}
		return null;
	}
	
	protected void resolveAgainstActualType(LightweightTypeReference declaredType, LightweightTypeReference actualType, final AbstractTypeComputationState state) {
		// TODO this(..) and super(..) for generic types
		List<JvmTypeParameter> typeParameters = getDeclaredTypeParameters();
		if (!typeParameters.isEmpty()) {
			// TODO actualType -(hint for)-> declared type == inferred
			// declared type -(hint for)-> actual type == expected
			TypeArgumentFromComputedTypeCollector.resolveAgainstActualType(declaredType, actualType, typeParameters, typeParameterMapping, BoundTypeArgumentSource.EXPECTATION, state.getReferenceOwner());
		}
	}
	
	protected LightweightTypeReference getDeclaredType(JvmIdentifiableElement feature) {
		return state.getResolvedTypes().getActualType(feature);
	}

	protected Map<JvmTypeParameter, LightweightMergedBoundTypeArgument> getDeclaratorParameterMapping() {
		return Collections.<JvmTypeParameter, LightweightMergedBoundTypeArgument>emptyMap();
	}

	protected void resolveArgumentType(XExpression argument, LightweightTypeReference declaredType, ITypeComputationState argumentState) {
		argumentState.computeTypes(argument);
	}

	public List<XExpression> getArguments() {
		List<XExpression> arguments = getSyntacticArguments();
		// TODO handle static feature etc
		JvmIdentifiableElement feature = getFeature();
		if (feature instanceof JvmOperation && ((JvmOperation) feature).isStatic()) {
			XExpression receiver = getReceiver();
			if (receiver != null && !arguments.contains(receiver)) {
				List<XExpression> result = Lists.newArrayListWithCapacity(1 + arguments.size());
				result.add(receiver);
				result.addAll(arguments);
				return result;
			}
		}
		return arguments;
	}
	
	public boolean isPreferredOver(ILinkingCandidate other) {
		if (other instanceof AbstractLinkingCandidate) {
			AbstractLinkingCandidate right = (AbstractLinkingCandidate) other;
			boolean visible = isVisible();
			if (visible != right.isVisible()) {
				if (visible)
					return true;
				return false;
			}
			int arityCompareResult = compareByArityWith(right);
			if (arityCompareResult != 0)
				return arityCompareResult <= 0;
			int typeArityCompareResult = compareByArity(getTypeArityMismatch(), right.getTypeArityMismatch());
			if (typeArityCompareResult != 0)
				return typeArityCompareResult <= 0;
			int argumentTypeCompareResult = compareByArgumentTypes(right);
			if (argumentTypeCompareResult != 0)
				return argumentTypeCompareResult <= 0;
			return true;
		}
		throw new IllegalArgumentException("other was " + other);
	}
	
	protected boolean isVisible() {
		if (description instanceof BucketedEObjectDescription)
			return ((BucketedEObjectDescription) description).isVisible();
		return true;
	}
	
	protected int compareByArgumentTypes(AbstractLinkingCandidate right) {
		initializeArgumentTypeComputation();
		right.initializeArgumentTypeComputation();
		
		int upTo = Math.min(arguments.getArgumentSize(), right.arguments.getArgumentSize());
		int leftBoxing = 0;
		int rightBoxing = 0;
		for(int i = 0; i < upTo; i++) {
			EnumSet<ConformanceHint> leftConformance = getConformanceHints(i);
			EnumSet<ConformanceHint> rightConformance = right.getConformanceHints(i);
			int hintCompareResult = ConformanceHint.compareHints(leftConformance, rightConformance);
			if (hintCompareResult != 0)
				return hintCompareResult;
			if (leftConformance.contains(ConformanceHint.BOXING) || leftConformance.contains(ConformanceHint.UNBOXING)) {
				leftBoxing++;
			}
			if (rightConformance.contains(ConformanceHint.BOXING) || rightConformance.contains(ConformanceHint.UNBOXING)) {
				rightBoxing++;
			}
		}
		if (leftBoxing != rightBoxing) {
			if (leftBoxing < rightBoxing)
				return -1;
			return 1;
		}
		int result = compareDeclaredArgumentTypes(right);
		if (result != 0)
			return result;
		return result;
	}
	
	protected int compareByConformanceHints(EnumSet<ConformanceHint> leftConformance, EnumSet<ConformanceHint> rightConformance) {
		for(ConformanceHint hint: Arrays.asList(ConformanceHint.DEMAND_CONVERSION, ConformanceHint.SYNONYM, ConformanceHint.VAR_ARG, 
				ConformanceHint.BOXING, ConformanceHint.UNBOXING, ConformanceHint.PRIMITIVE_WIDENING)) {
			boolean leftContains = leftConformance.contains(hint);
			boolean rightContains = rightConformance.contains(hint);
			if (leftContains != rightContains) {
				if (leftContains)
					return 1;
				return -1;
			}
		}
		return 0;
	}
	
	protected int compareByConformanceHint(EnumSet<ConformanceHint> leftConformance, EnumSet<ConformanceHint> rightConformance, ConformanceHint unexpectedHint) {
		boolean leftContains = leftConformance.contains(unexpectedHint);
		boolean rightContains = rightConformance.contains(unexpectedHint);
		if (leftContains != rightContains) {
			if (leftContains)
				return 1;
			return -1;
		}
		return 0;
	}
	
	protected EnumSet<ConformanceHint> getConformanceHints(int idx) {
		while(!arguments.isProcessed(idx)) {
			computeArgumentType(arguments.getNextUnprocessedNextArgument());
		}
		XExpression argument = arguments.getArgument(idx);
		return state.getResolvedTypes().getConformanceHints(argument);
	}

	protected int compareDeclaredArgumentTypes(AbstractLinkingCandidate right) {
		int result = 0;
		int upTo = Math.min(arguments.getArgumentSize(), right.arguments.getArgumentSize());
		for(int i = 0; i < upTo; i++) {
			LightweightTypeReference expectedArgumentType = getSubstitutedExpectedType(arguments.getArgument(i));
			LightweightTypeReference rightExpectedArgumentType = right.getSubstitutedExpectedType(right.arguments.getArgument(i));
			if (expectedArgumentType == null) {
				if (rightExpectedArgumentType != null)
					return 1;
			} else {
				if (rightExpectedArgumentType == null) {
					return -1;
				}
				if (expectedArgumentType.isAssignableFrom(rightExpectedArgumentType)) {
					if (!rightExpectedArgumentType.isAssignableFrom(expectedArgumentType))
						result++;
				} else if (rightExpectedArgumentType.isAssignableFrom(expectedArgumentType)) {
					result--;
				}
			}
		}
		return result;
	}
	
	protected LightweightTypeReference getActualType(XExpression expression) {
		return state.getResolvedTypes().getActualType(expression);
	}
	
	protected LightweightTypeReference getExpectedType(XExpression expression) {
		return state.getResolvedTypes().getExpectedType(expression);
	}
	
	protected LightweightTypeReference getSubstitutedExpectedType(XExpression expression) {
		LightweightTypeReference expectedType = getExpectedType(expression);
		if (expectedType != null) {
			TypeParameterByConstraintSubstitutor substitutor = new TypeParameterByConstraintSubstitutor(getDeclaratorParameterMapping(), getState().getReferenceOwner()) {
				@Override
				@NonNullByDefault
				public LightweightTypeReference substitute(LightweightTypeReference original) {
					if (original instanceof ArrayTypeReference) {
						LightweightTypeReference componentType = original.getComponentType();
						if (componentType instanceof UnboundTypeReference) {
							ConstraintVisitingInfo visitingInfo = createVisiting();
							JvmTypeParameter typeParameter = ((UnboundTypeReference) componentType).getTypeParameter();
							JvmTypeParameterDeclarator declarator = typeParameter.getDeclarator();
							visitingInfo.pushInfo(declarator, declarator.getTypeParameters().indexOf(typeParameter));
							LightweightTypeReference result = original.accept(this, visitingInfo);
							return result;
						}
					} 
					return super.substitute(original);
				}
			};
			LightweightTypeReference result = substitutor.substitute(expectedType);
			return result;
		}
		return null;
	}

	protected int compareByArityWith(AbstractLinkingCandidate right) {
		int arityCompareResult = compareByArity(getArityMismatch(), right.getArityMismatch());
		return arityCompareResult;
	}

	protected int compareByArity(int leftArityMismatch, int rightArityMismatch) {
		if (leftArityMismatch != rightArityMismatch) {
			if (leftArityMismatch == 0)
				return -1;
			if (rightArityMismatch == 0)
				return 1;
			if (Math.abs(leftArityMismatch) < Math.abs(rightArityMismatch))
				return -1;
			if (Math.abs(leftArityMismatch) > Math.abs(rightArityMismatch))
				return 1;
			if (leftArityMismatch > 0)
				return -1;
			if (rightArityMismatch > 0)
				return 1;
		}
		if (Math.abs(leftArityMismatch) < Math.abs(rightArityMismatch))
			return -1;
		if (Math.abs(leftArityMismatch) > Math.abs(rightArityMismatch))
			return 1;
		if (leftArityMismatch > 0)
			return -1;
		if (rightArityMismatch > 0)
			return 1;
		return 0;
	}
	
	public int getArityMismatch() {
		JvmIdentifiableElement identifiable = getFeature();
		if (identifiable instanceof JvmExecutable) {
			return getArityMismatch((JvmExecutable) identifiable, getArguments());
		} else if (getExpression() instanceof XAssignment) {
			return getArguments().size() - 1;
		} else {
			return getArguments().size();
		}
	}
	
	public int getTypeArityMismatch() {
		List<LightweightTypeReference> explicitTypeArguments = getExplicitTypeArguments();
		if (explicitTypeArguments.size() == 0)
			return 0;
		return getDeclaredTypeParameters().size() - explicitTypeArguments.size();
	}
	
	protected void resolveLinkingProxy(EReference structuralFeature, int featureId) {
		XExpression expression = getExpression();
		InternalEObject internalView = (InternalEObject) expression;
		JvmIdentifiableElement newFeature = getFeature();
		if (newFeature.eIsProxy()) {
			newFeature = (JvmIdentifiableElement) internalView.eResolveProxy((InternalEObject) newFeature);
		}
		EObject oldFeature = (EObject) expression.eGet(structuralFeature, false);
		if (oldFeature == null || !(oldFeature.eIsProxy())) {
			throw new IllegalStateException("Feature was already resolved to " + oldFeature);
		}
		if (internalView.eNotificationRequired()) {
			boolean wasDeliver = internalView.eDeliver();
			internalView.eSetDeliver(false);
			expression.eSet(structuralFeature, newFeature);
			internalView.eSetDeliver(wasDeliver);
			if (newFeature != oldFeature) {
				internalView.eNotify(new ENotificationImpl(internalView, Notification.RESOLVE, featureId, oldFeature, newFeature));
			}
		} else {
			expression.eSet(structuralFeature, newFeature);
		}
	}
	
	protected abstract List<LightweightTypeReference> getExplicitTypeArguments();

	protected int getArityMismatch(JvmExecutable executable, List<XExpression> arguments) {
		int fixedArityParamCount = executable.getParameters().size();
		if (executable.isVarArgs()) {
			fixedArityParamCount--;
			if (arguments.size() >= fixedArityParamCount) {
				return 0;
			}
		}
		return fixedArityParamCount - arguments.size();
	}
	
	protected abstract List<XExpression> getSyntacticArguments();
	
	protected IEObjectDescription getDescription() {
		return description;
	}
	
	public JvmIdentifiableElement getFeature() {
		return (JvmIdentifiableElement) getDescription().getEObjectOrProxy();
	}
	
	protected XExpression getReceiver() {
		if (getDescription() instanceof BucketedEObjectDescription) {
			return ((BucketedEObjectDescription) getDescription()).getReceiver();
		}
		return null;
	}
	
	protected XExpression getExpression() {
		return expression;
	}
	
	protected ExpressionTypeComputationState getState() {
		return state;
	}
	
	@Override
	public String toString() {
		return getClass().getSimpleName() + " [" + description.toString() + "]";
	}
}
