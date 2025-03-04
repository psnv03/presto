/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.scalar;

import com.facebook.presto.bytecode.BytecodeBlock;
import com.facebook.presto.bytecode.BytecodeNode;
import com.facebook.presto.bytecode.CallSiteBinder;
import com.facebook.presto.bytecode.ClassDefinition;
import com.facebook.presto.bytecode.FieldDefinition;
import com.facebook.presto.bytecode.MethodDefinition;
import com.facebook.presto.bytecode.Parameter;
import com.facebook.presto.bytecode.Scope;
import com.facebook.presto.bytecode.Variable;
import com.facebook.presto.bytecode.control.ForLoop;
import com.facebook.presto.bytecode.control.IfStatement;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.block.BlockBuilder;
import com.facebook.presto.common.block.BlockBuilderStatus;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.metadata.BoundVariables;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.metadata.SqlScalarFunction;
import com.facebook.presto.spi.function.ComplexTypeFunctionDescriptor;
import com.facebook.presto.spi.function.FunctionKind;
import com.facebook.presto.spi.function.LambdaArgumentDescriptor;
import com.facebook.presto.spi.function.LambdaDescriptor;
import com.facebook.presto.spi.function.Signature;
import com.facebook.presto.spi.function.SqlFunctionVisibility;
import com.facebook.presto.sql.gen.lambda.UnaryFunctionInterface;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Primitives;

import java.util.Optional;

import static com.facebook.presto.bytecode.Access.FINAL;
import static com.facebook.presto.bytecode.Access.PRIVATE;
import static com.facebook.presto.bytecode.Access.PUBLIC;
import static com.facebook.presto.bytecode.Access.STATIC;
import static com.facebook.presto.bytecode.Access.a;
import static com.facebook.presto.bytecode.Parameter.arg;
import static com.facebook.presto.bytecode.ParameterizedType.type;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.constantInt;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.constantNull;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.equal;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.getStatic;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.lessThan;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.setStatic;
import static com.facebook.presto.bytecode.instruction.VariableInstruction.incrementVariable;
import static com.facebook.presto.common.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.common.type.UnknownType.UNKNOWN;
import static com.facebook.presto.metadata.BuiltInTypeAndFunctionNamespaceManager.JAVA_BUILTIN_NAMESPACE;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementationChoice.ArgumentProperty.functionTypeArgumentProperty;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementationChoice.ArgumentProperty.valueTypeArgumentProperty;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementationChoice.NullConvention.RETURN_NULL_ON_NULL;
import static com.facebook.presto.spi.function.Signature.typeVariable;
import static com.facebook.presto.sql.gen.SqlTypeBytecodeExpression.constantType;
import static com.facebook.presto.util.CompilerUtils.defineClass;
import static com.facebook.presto.util.CompilerUtils.makeClassName;
import static com.facebook.presto.util.Reflection.methodHandle;

public final class ArrayTransformFunction
        extends SqlScalarFunction
{
    public static final ArrayTransformFunction ARRAY_TRANSFORM_FUNCTION = new ArrayTransformFunction();

    private final ComplexTypeFunctionDescriptor descriptor;

    private ArrayTransformFunction()
    {
        super(new Signature(
                QualifiedObjectName.valueOf(JAVA_BUILTIN_NAMESPACE, "transform"),
                FunctionKind.SCALAR,
                ImmutableList.of(typeVariable("T"), typeVariable("U")),
                ImmutableList.of(),
                parseTypeSignature("array(U)"),
                ImmutableList.of(parseTypeSignature("array(T)"), parseTypeSignature("function(T,U)")),
                false));
        descriptor = new ComplexTypeFunctionDescriptor(
                true,
                ImmutableList.of(new LambdaDescriptor(1, ImmutableMap.of(0, new LambdaArgumentDescriptor(0, ComplexTypeFunctionDescriptor::prependAllSubscripts)))),
                Optional.of(ImmutableSet.of(0)),
                Optional.of(ComplexTypeFunctionDescriptor::clearRequiredSubfields),
                getSignature());
    }

    @Override
    public SqlFunctionVisibility getVisibility()
    {
        return SqlFunctionVisibility.PUBLIC;
    }

    @Override
    public boolean isDeterministic()
    {
        return false;
    }

    @Override
    public String getDescription()
    {
        return "apply lambda to each element of the array";
    }

    @Override
    public BuiltInScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, FunctionAndTypeManager functionAndTypeManager)
    {
        Type inputType = boundVariables.getTypeVariable("T");
        Type outputType = boundVariables.getTypeVariable("U");
        Class<?> generatedClass = generateTransform(inputType, outputType);
        return new BuiltInScalarFunctionImplementation(
                false,
                ImmutableList.of(
                        valueTypeArgumentProperty(RETURN_NULL_ON_NULL),
                        functionTypeArgumentProperty(UnaryFunctionInterface.class)),
                methodHandle(generatedClass, "transform", Block.class, UnaryFunctionInterface.class));
    }

    @Override
    public ComplexTypeFunctionDescriptor getComplexTypeFunctionDescriptor()
    {
        return descriptor;
    }

    private static Class<?> generateTransform(Type inputType, Type outputType)
    {
        CallSiteBinder binder = new CallSiteBinder();
        Class<?> inputJavaType = Primitives.wrap(inputType.getJavaType());
        Class<?> outputJavaType = Primitives.wrap(outputType.getJavaType());

        ClassDefinition definition = new ClassDefinition(
                a(PUBLIC, FINAL),
                makeClassName("ArrayTransform"),
                type(Object.class));
        definition.declareDefaultConstructor(a(PRIVATE));

        // define transform method
        Parameter block = arg("block", Block.class);
        Parameter function = arg("function", UnaryFunctionInterface.class);

        FieldDefinition rootBlockBuilder = definition.declareField(a(PRIVATE, STATIC, FINAL), "BLOCK_BUILDER", BlockBuilder.class);
        definition.getClassInitializer().getBody().append(setStatic(rootBlockBuilder, constantType(binder, outputType).invoke("createBlockBuilder", BlockBuilder.class, constantNull(BlockBuilderStatus.class), constantInt(10))));

        MethodDefinition method = definition.declareMethod(
                a(PUBLIC, STATIC),
                "transform",
                type(Block.class),
                ImmutableList.of(block, function));

        BytecodeBlock body = method.getBody();
        Scope scope = method.getScope();
        Variable positionCount = scope.declareVariable(int.class, "positionCount");
        Variable position = scope.declareVariable(int.class, "position");
        Variable blockBuilder = scope.declareVariable(BlockBuilder.class, "blockBuilder");
        Variable inputElement = scope.declareVariable(inputJavaType, "inputElement");
        Variable outputElement = scope.declareVariable(outputJavaType, "outputElement");

        // invoke block.getPositionCount()
        body.append(positionCount.set(block.invoke("getPositionCount", int.class)));

        // get block builder
        body.append(blockBuilder.set(getStatic(rootBlockBuilder).invoke("newBlockBuilderLike", BlockBuilder.class, constantNull(BlockBuilderStatus.class), positionCount)));

        BytecodeNode loadInputElement;
        if (!inputType.equals(UNKNOWN)) {
            loadInputElement = new IfStatement()
                    .condition(block.invoke("isNull", boolean.class, position))
                    .ifTrue(inputElement.set(constantNull(inputJavaType)))
                    .ifFalse(inputElement.set(constantType(binder, inputType).getValue(block, position).cast(inputJavaType)));
        }
        else {
            loadInputElement = new BytecodeBlock().append(inputElement.set(constantNull(inputJavaType)));
        }

        BytecodeNode writeOutputElement;
        if (!outputType.equals(UNKNOWN)) {
            writeOutputElement = new IfStatement()
                    .condition(equal(outputElement, constantNull(outputJavaType)))
                    .ifTrue(blockBuilder.invoke("appendNull", BlockBuilder.class).pop())
                    .ifFalse(constantType(binder, outputType).writeValue(blockBuilder, outputElement.cast(outputType.getJavaType())));
        }
        else {
            writeOutputElement = new BytecodeBlock().append(blockBuilder.invoke("appendNull", BlockBuilder.class).pop());
        }

        body.append(new ForLoop()
                .initialize(position.set(constantInt(0)))
                .condition(lessThan(position, positionCount))
                .update(incrementVariable(position, (byte) 1))
                .body(new BytecodeBlock()
                        .append(loadInputElement)
                        .append(outputElement.set(function.invoke("apply", Object.class, inputElement.cast(Object.class)).cast(outputJavaType)))
                        .append(writeOutputElement)));

        body.append(blockBuilder.invoke("build", Block.class).ret());

        return defineClass(definition, Object.class, binder.getBindings(), ArrayTransformFunction.class.getClassLoader());
    }
}
