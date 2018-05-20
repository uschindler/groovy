/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.codehaus.groovy.classgen.asm.sc;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.LambdaExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.classgen.asm.BytecodeHelper;
import org.codehaus.groovy.classgen.asm.CompileStack;
import org.codehaus.groovy.classgen.asm.LambdaWriter;
import org.codehaus.groovy.classgen.asm.OperandStack;
import org.codehaus.groovy.classgen.asm.WriterController;
import org.codehaus.groovy.classgen.asm.WriterControllerFactory;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.codehaus.groovy.transform.stc.StaticTypesMarker.INFERRED_LAMBDA_TYPE;
import static org.codehaus.groovy.transform.stc.StaticTypesMarker.PARAMETER_TYPE;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.NEW;

/**
 * Writer responsible for generating lambda classes in statically compiled mode.
 */
public class StaticTypesLambdaWriter extends LambdaWriter {
    private static final String DO_CALL = "doCall";
    private static final String ORIGINAL_PARAMETERS_WITH_EXACT_TYPE = "__ORIGINAL_PARAMETERS_WITH_EXACT_TYPE";
    private static final String LAMBDA_SHARED_VARIABLES = "__LAMBDA_SHARED_VARIABLES";
    private static final String ENCLOSING_THIS = "__enclosing_this";
    private static final String LAMBDA_THIS = "__lambda_this";
    private static final String INIT = "<init>";
    private static final String IS_GENERATED_CONSTRUCTOR = "__IS_GENERATED_CONSTRUCTOR";
    private StaticTypesClosureWriter staticTypesClosureWriter;
    private WriterController controller;
    private WriterControllerFactory factory;
    private final Map<Expression,ClassNode> lambdaClassMap = new HashMap<>();

    public StaticTypesLambdaWriter(WriterController wc) {
        super(wc);
        this.staticTypesClosureWriter = new StaticTypesClosureWriter(wc);
        this.controller = wc;
        this.factory = new WriterControllerFactory() {
            public WriterController makeController(final WriterController normalController) {
                return controller;
            }
        };
    }

    @Override
    public void writeLambda(LambdaExpression expression) {
        ClassNode lambdaType = getLambdaType(expression);
        ClassNode redirect = lambdaType.redirect();

        if (null == lambdaType || !ClassHelper.isFunctionalInterface(redirect)) {
            // if the parameter type is not real FunctionInterface or failed to be inferred, generate the default bytecode, which is actually a closure
            super.writeLambda(expression);
            return;
        }

        MethodNode abstractMethodNode = ClassHelper.findSAM(redirect);
        String abstractMethodDesc = createMethodDescriptor(abstractMethodNode);

        ClassNode classNode = controller.getClassNode();
        boolean isInterface = classNode.isInterface();
        ClassNode lambdaWrapperClassNode = getOrAddLambdaClass(expression, ACC_PUBLIC | ACC_FINAL | (isInterface ? ACC_STATIC : 0) | ACC_SYNTHETIC, abstractMethodNode);
        MethodNode syntheticLambdaMethodNode = lambdaWrapperClassNode.getMethods(DO_CALL).get(0);

        newGroovyLambdaWrapperAndLoad(lambdaWrapperClassNode, syntheticLambdaMethodNode);

        loadEnclosingClassInstance();


        MethodVisitor mv = controller.getMethodVisitor();
        OperandStack operandStack = controller.getOperandStack();

        mv.visitInvokeDynamicInsn(
                abstractMethodNode.getName(),
                createAbstractMethodDesc(lambdaType, lambdaWrapperClassNode),
                createBootstrapMethod(isInterface),
                createBootstrapMethodArguments(abstractMethodDesc, lambdaWrapperClassNode, syntheticLambdaMethodNode)
        );
        operandStack.replace(redirect, 2);

        if (null != expression.getNodeMetaData(INFERRED_LAMBDA_TYPE)) {
            // FIXME declaring variable whose initial value is a lambda, e.g. `Function<Integer, String> f = (Integer e) -> 'a' + e`
            //       Groovy will `POP` automatically, use `DUP` to duplicate the element of operand stack:
            /*
                INVOKEDYNAMIC apply(LTest1$_p_lambda1;LTest1;)Ljava/util/function/Function; [
                  // handle kind 0x6 : INVOKESTATIC
                  java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
                  // arguments:
                  (Ljava/lang/Object;)Ljava/lang/Object;,
                  // handle kind 0x5 : INVOKEVIRTUAL
                  Test1$_p_lambda1.doCall(LTest1;Ljava/lang/Integer;)Ljava/lang/String;,
                  (Ljava/lang/Integer;)Ljava/lang/String;
                ]
                DUP           <-------------- FIXME ADDED ON PURPOSE, WE SHOULD REMOVE IT AFTER FIND BETTER SOLUTION
                ASTORE 0
               L2
                ALOAD 0
                POP           <-------------- Since operand stack is not empty, the `POP`s are issued by `controller.getOperandStack().popDownTo(mark);` in the method `org.codehaus.groovy.classgen.asm.StatementWriter.writeExpressionStatement`, but when we try to `operandStack.pop();` instead of `mv.visitInsn(DUP);`, we will get AIOOBE...
                POP
            */

            mv.visitInsn(DUP);

            /*
                org.codehaus.groovy.control.MultipleCompilationErrorsException: startup failed:
                General error during class generation: size==0

                java.lang.ArrayIndexOutOfBoundsException: size==0
                    at org.codehaus.groovy.classgen.asm.OperandStack.getTopOperand(OperandStack.java:693)
                    at org.codehaus.groovy.classgen.asm.BinaryExpressionHelper.evaluateEqual(BinaryExpressionHelper.java:397)
                    at org.codehaus.groovy.classgen.asm.sc.StaticTypesBinaryExpressionMultiTypeDispatcher.evaluateEqual(StaticTypesBinaryExpressionMultiTypeDispatcher.java:179)
                    at org.codehaus.groovy.classgen.AsmClassGenerator.visitDeclarationExpression(AsmClassGenerator.java:694)
                    at org.codehaus.groovy.ast.expr.DeclarationExpression.visit(DeclarationExpression.java:89)
                    at org.codehaus.groovy.classgen.asm.StatementWriter.writeExpressionStatement(StatementWriter.java:633)
                    at org.codehaus.groovy.classgen.AsmClassGenerator.visitExpressionStatement(AsmClassGenerator.java:681)
                    at org.codehaus.groovy.ast.stmt.ExpressionStatement.visit(ExpressionStatement.java:42)
                             */
                //            operandStack.pop();
        }

    }

    private ClassNode getLambdaType(LambdaExpression expression) {
        ClassNode type = expression.getNodeMetaData(PARAMETER_TYPE);

        if (null == type) {
            type = expression.getNodeMetaData(INFERRED_LAMBDA_TYPE);
        }
        return type;
    }

    private void loadEnclosingClassInstance() {
        MethodVisitor mv = controller.getMethodVisitor();
        OperandStack operandStack = controller.getOperandStack();
        CompileStack compileStack = controller.getCompileStack();

        if (controller.isStaticMethod() || compileStack.isInSpecialConstructorCall()) {
            operandStack.pushConstant(ConstantExpression.NULL);
        } else {
            mv.visitVarInsn(ALOAD, 0);
            operandStack.push(controller.getClassNode());
        }
    }

    private void newGroovyLambdaWrapperAndLoad(ClassNode lambdaWrapperClassNode, MethodNode syntheticLambdaMethodNode) {
        MethodVisitor mv = controller.getMethodVisitor();
        String lambdaWrapperClassInternalName = BytecodeHelper.getClassInternalName(lambdaWrapperClassNode);
        mv.visitTypeInsn(NEW, lambdaWrapperClassInternalName);
        mv.visitInsn(DUP);

        loadEnclosingClassInstance();
        loadEnclosingClassInstance();

        loadSharedVariables(syntheticLambdaMethodNode);

        List<ConstructorNode> constructorNodeList =
                lambdaWrapperClassNode.getDeclaredConstructors().stream()
                        .filter(e -> Boolean.TRUE.equals(e.getNodeMetaData(IS_GENERATED_CONSTRUCTOR)))
                        .collect(Collectors.toList());

        if (constructorNodeList.size() == 0) {
            throw new GroovyBugError("Failed to find the generated constructor");
        }

        ConstructorNode constructorNode = constructorNodeList.get(0);
        Parameter[] lambdaWrapperClassConstructorParameters = constructorNode.getParameters();
        mv.visitMethodInsn(INVOKESPECIAL, lambdaWrapperClassInternalName, INIT, BytecodeHelper.getMethodDescriptor(ClassHelper.VOID_TYPE, lambdaWrapperClassConstructorParameters), lambdaWrapperClassNode.isInterface());
        OperandStack operandStack = controller.getOperandStack();
        operandStack.replace(ClassHelper.CLOSURE_TYPE, lambdaWrapperClassConstructorParameters.length);
    }

    private Parameter[] loadSharedVariables(MethodNode syntheticLambdaMethodNode) {
        Parameter[] lambdaSharedVariableParameters = syntheticLambdaMethodNode.getNodeMetaData(LAMBDA_SHARED_VARIABLES);
        for (Parameter parameter : lambdaSharedVariableParameters) {
            String parameterName = parameter.getName();
            loadReference(parameterName, controller);
            if (parameter.getNodeMetaData(LambdaWriter.UseExistingReference.class) == null) {
                parameter.setNodeMetaData(LambdaWriter.UseExistingReference.class, Boolean.TRUE);
            }
        }

        return lambdaSharedVariableParameters;
    }

    private String createAbstractMethodDesc(ClassNode parameterType, ClassNode lambdaClassNode) {
        List<Parameter> lambdaSharedVariableList = new LinkedList<>();

        prependEnclosingThis(lambdaSharedVariableList);
        prependParameter(lambdaSharedVariableList, LAMBDA_THIS, lambdaClassNode);

        return BytecodeHelper.getMethodDescriptor(parameterType.redirect(), lambdaSharedVariableList.toArray(Parameter.EMPTY_ARRAY));
    }

    private Handle createBootstrapMethod(boolean isInterface) {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                isInterface
        );
    }

    private Object[] createBootstrapMethodArguments(String abstractMethodDesc, ClassNode lambdaClassNode, MethodNode syntheticLambdaMethodNode) {
        return new Object[]{
                Type.getType(abstractMethodDesc),
                new Handle(
                        Opcodes.H_INVOKEVIRTUAL,
                        lambdaClassNode.getName(),
                        syntheticLambdaMethodNode.getName(),
                        BytecodeHelper.getMethodDescriptor(syntheticLambdaMethodNode),
                        lambdaClassNode.isInterface()
                ),
                Type.getType(BytecodeHelper.getMethodDescriptor(syntheticLambdaMethodNode.getReturnType(), syntheticLambdaMethodNode.getNodeMetaData(ORIGINAL_PARAMETERS_WITH_EXACT_TYPE)))
        };
    }

    private String createMethodDescriptor(MethodNode abstractMethodNode) {
        return BytecodeHelper.getMethodDescriptor(
                abstractMethodNode.getReturnType().getTypeClass(),
                Arrays.stream(abstractMethodNode.getParameters())
                        .map(e -> e.getType().getTypeClass())
                        .toArray(Class[]::new)
        );
    }

    public ClassNode getOrAddLambdaClass(LambdaExpression expression, int mods, MethodNode abstractMethodNode) {
        ClassNode lambdaClass = lambdaClassMap.get(expression);
        if (lambdaClass == null) {
            lambdaClass = createLambdaClass(expression, mods, abstractMethodNode);
            lambdaClassMap.put(expression, lambdaClass);
            controller.getAcg().addInnerClass(lambdaClass);
            lambdaClass.addInterface(ClassHelper.GENERATED_LAMBDA_TYPE);
            lambdaClass.putNodeMetaData(WriterControllerFactory.class, factory);
        }
        lambdaClass.putNodeMetaData(StaticCompilationMetadataKeys.STATIC_COMPILE_NODE, Boolean.TRUE);
        return lambdaClass;
    }

    protected ClassNode createLambdaClass(LambdaExpression expression, int mods, MethodNode abstractMethodNode) {
        ClassNode outerClass = controller.getOutermostClass();
        ClassNode classNode = controller.getClassNode();
        String name = genLambdaClassName();
        boolean staticMethodOrInStaticClass = controller.isStaticMethod() || classNode.isStaticClass();

        InnerClassNode answer = new InnerClassNode(classNode, name, mods, ClassHelper.CLOSURE_TYPE.getPlainNodeReference());
        answer.setEnclosingMethod(controller.getMethodNode());
        answer.setSynthetic(true);
        answer.setUsingGenerics(outerClass.isUsingGenerics());
        answer.setSourcePosition(expression);

        if (staticMethodOrInStaticClass) {
            answer.setStaticClass(true);
        }
        if (controller.isInScriptBody()) {
            answer.setScriptBody(true);
        }

        MethodNode syntheticLambdaMethodNode = addSyntheticLambdaMethodNode(expression, answer, abstractMethodNode);
        Parameter[] localVariableParameters = syntheticLambdaMethodNode.getNodeMetaData(LAMBDA_SHARED_VARIABLES);

        addFieldsAndGettersForLocalVariables(answer, localVariableParameters);
        ConstructorNode constructorNode = addConstructor(expression, localVariableParameters, answer, createBlockStatementForConstructor(expression));
        constructorNode.putNodeMetaData(IS_GENERATED_CONSTRUCTOR, Boolean.TRUE);

        Parameter enclosingThisParameter = syntheticLambdaMethodNode.getParameters()[0];
        new TransformationVisitor(answer, enclosingThisParameter).visitMethod(syntheticLambdaMethodNode);

        return answer;
    }

    private String genLambdaClassName() {
        ClassNode classNode = controller.getClassNode();
        ClassNode outerClass = controller.getOutermostClass();
        MethodNode methodNode = controller.getMethodNode();

        return classNode.getName() + "$"
                + controller.getContext().getNextLambdaInnerName(outerClass, classNode, methodNode);
    }

    private MethodNode addSyntheticLambdaMethodNode(LambdaExpression expression, InnerClassNode answer, MethodNode abstractMethodNode) {
        Parameter[] parametersWithExactType = createParametersWithExactType(expression); // expression.getParameters();
//        ClassNode returnType = expression.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE); //abstractMethodNode.getReturnType();
        Parameter[] localVariableParameters = getLambdaSharedVariables(expression);
        removeInitialValues(localVariableParameters);

        List<Parameter> methodParameterList = new LinkedList<Parameter>(Arrays.asList(parametersWithExactType));
        prependEnclosingThis(methodParameterList);

        MethodNode methodNode =
                answer.addMethod(
                        DO_CALL,
                        Opcodes.ACC_PUBLIC,
                        abstractMethodNode.getReturnType() /*ClassHelper.OBJECT_TYPE*/ /*returnType*/,
                        methodParameterList.toArray(Parameter.EMPTY_ARRAY),
                        ClassNode.EMPTY_ARRAY,
                        expression.getCode()
                );
        methodNode.putNodeMetaData(ORIGINAL_PARAMETERS_WITH_EXACT_TYPE, parametersWithExactType);
        methodNode.putNodeMetaData(LAMBDA_SHARED_VARIABLES, localVariableParameters);
        methodNode.setSourcePosition(expression);

        return methodNode;
    }

    private Parameter prependEnclosingThis(List<Parameter> methodParameterList) {
        return prependParameter(methodParameterList, ENCLOSING_THIS, controller.getClassNode().getPlainNodeReference());
    }

    private Parameter prependParameter(List<Parameter> methodParameterList, String parameterName, ClassNode parameterType) {
        Parameter parameter = new Parameter(parameterType, parameterName);

        parameter.setOriginType(parameterType);
        parameter.setClosureSharedVariable(false);

        methodParameterList.add(0, parameter);

        return parameter;
    }

    private Parameter[] createParametersWithExactType(LambdaExpression expression) {
        Parameter[] parameters = expression.getParameters();
        if (parameters == null) {
            parameters = Parameter.EMPTY_ARRAY;
        }

        for (int i = 0; i < parameters.length; i++) {
            ClassNode inferredType = parameters[i].getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);

            if (null == inferredType) {
                continue;
            }

            parameters[i].setType(inferredType);
            parameters[i].setOriginType(inferredType);
        }

        return parameters;
    }

    @Override
    protected ClassNode createClosureClass(final ClosureExpression expression, final int mods) {
        return staticTypesClosureWriter.createClosureClass(expression, mods);
    }

    private static final class TransformationVisitor extends ClassCodeVisitorSupport {
        private CorrectAccessedVariableVisitor correctAccessedVariableVisitor;
        private Parameter enclosingThisParameter;

        public TransformationVisitor(InnerClassNode icn, Parameter enclosingThisParameter) {
            correctAccessedVariableVisitor = new CorrectAccessedVariableVisitor(icn);
            this.enclosingThisParameter = enclosingThisParameter;
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {
            correctAccessedVariableVisitor.visitVariableExpression(expression);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            if (!call.getMethodTarget().isStatic()) {
                Expression objectExpression = call.getObjectExpression();

                if (objectExpression instanceof VariableExpression) {
                    VariableExpression originalObjectExpression = (VariableExpression) objectExpression;
                    if (null == originalObjectExpression.getAccessedVariable()) {
                        VariableExpression thisVariable = new VariableExpression(enclosingThisParameter);
                        thisVariable.setSourcePosition(originalObjectExpression);

                        call.setObjectExpression(thisVariable);
                        call.setImplicitThis(false);
                    }
                }
            }

            super.visitMethodCallExpression(call);
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }
    }
}
