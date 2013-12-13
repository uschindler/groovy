/*
 * Copyright 2003-2013 the original author or authors.
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
package org.codehaus.groovy.transform.trait;

import groovy.transform.CompileStatic;
import groovy.transform.Trait;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.Verifier;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.ExceptionUtils;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Handles generation of code for the @Trait annotation. A class annotated with @Trait will generate, instead: <ul>
 * <li>an <i>interface</i> with the same name</li> <li>an utility inner class that will be used by the compiler to
 * handle the trait</li> </ul>
 *
 * @author Cedric Champeau
 */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public class TraitASTTransformation extends AbstractASTTransformation {

    static final Class MY_CLASS = Trait.class;
    static final ClassNode MY_TYPE = ClassHelper.make(MY_CLASS);
    static final String MY_TYPE_NAME = "@" + MY_TYPE.getNameWithoutPackage();
    static final String TRAIT_HELPER = "$Trait$Helper";
    static final String FIELD_HELPER = "$Trait$FieldHelper";

    static final String DIRECT_SETTER_SUFFIX = "$set";
    static final String DIRECT_GETTER_SUFFIX = "$get";
    static final String STATIC_INIT_METHOD = "$init$";

    /**
     * This comparator is used to make sure that generated direct getters appear first in the list of method
     * nodes.
     */
    private static final Comparator<MethodNode> GETTER_FIRST_COMPARATOR = new Comparator<MethodNode>() {
        public int compare(final MethodNode o1, final MethodNode o2) {
            if (o1.getName().endsWith(DIRECT_GETTER_SUFFIX)) return -1;
            return 1;
        }
    };
    private static final String THIS_OBJECT = "$self";

    private SourceUnit unit;

    public void visit(ASTNode[] nodes, SourceUnit source) {
        AnnotatedNode parent = (AnnotatedNode) nodes[1];
        AnnotationNode anno = (AnnotationNode) nodes[0];
        if (!MY_TYPE.equals(anno.getClassNode())) return;
        unit = source;
        if (parent instanceof ClassNode) {
            ClassNode cNode = (ClassNode) parent;
            checkNotInterface(cNode, MY_TYPE_NAME);
            checkNoConstructor(cNode);
            createHelperClass(cNode);
        }
    }

    private void checkNoConstructor(final ClassNode cNode) {
        if (!cNode.getDeclaredConstructors().isEmpty()) {
            addError("Error processing trait '" + cNode.getName() + "'. " +
                    " Constructors are not allowed.", cNode);
        }
    }

    private void createHelperClass(final ClassNode cNode) {
        ClassNode helper = new InnerClassNode(
                cNode,
                helperClassName(cNode),
                ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_SYNTHETIC,
                ClassHelper.OBJECT_TYPE,
                ClassNode.EMPTY_ARRAY,
                null
        );
        cNode.setModifiers(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);

        MethodNode initializer = new MethodNode(
                STATIC_INIT_METHOD,
                ACC_STATIC | ACC_PUBLIC | ACC_SYNTHETIC,
                ClassHelper.VOID_TYPE,
                new Parameter[]{new Parameter(cNode.getPlainNodeReference(), THIS_OBJECT)},
                ClassNode.EMPTY_ARRAY,
                new BlockStatement()
        );
        helper.addMethod(initializer);

        // apply the verifier to have the property nodes generated
        generatePropertyMethods(cNode);

        // prepare fields
        List<FieldNode> fields = new ArrayList<FieldNode>();
        for (FieldNode field : cNode.getFields()) {
            if (!"metaClass".equals(field.getName()) && (!field.isSynthetic() || field.getName().indexOf('$') < 0)) {
                fields.add(field);
            }
        }
        ClassNode fieldHelper = null;
        if (!fields.isEmpty()) {
            fieldHelper = new InnerClassNode(
                    cNode,
                    fieldHelperClassName(cNode),
                    ACC_STATIC | ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
                    ClassHelper.OBJECT_TYPE
            );
        }

        // add methods
        List<MethodNode> methods = cNode.getMethods();
        for (final MethodNode methodNode : methods) {
            if (methodNode.isPrivate() || methodNode.isProtected()) {
                ExceptionUtils.sneakyThrow(new SyntaxException("Cannot have " + (methodNode.isPrivate() ? "private" : "protected") + " method in a trait",
                        methodNode.getLineNumber(), methodNode.getColumnNumber()));
            }
            if (methodNode.getDeclaringClass() == cNode) {
                helper.addMethod(processMethod(cNode, methodNode, fieldHelper));
            }

        }

        // add fields
        for (FieldNode field : fields) {
            processField(field, initializer, fieldHelper);
        }

        // clear properties to avoid generation of methods
        cNode.getProperties().clear();

        fields = new ArrayList<FieldNode>(cNode.getFields()); // reuse the full list of fields
        for (FieldNode field : fields) {
            cNode.removeField(field.getName());
        }

        unit.getAST().addClass(helper);
        if (fieldHelper != null) {
            unit.getAST().addClass(fieldHelper);
        }
    }

    private void generatePropertyMethods(final ClassNode cNode) {
        for (PropertyNode node : cNode.getProperties()) {
            processProperty(cNode, node);
        }
    }

    static String remappedFieldName(final ClassNode traitNode, final String name) {
        return traitNode.getName().replace('.','_')+"__"+name;
    }

    /**
     * Mostly copied from the {@link Verifier} class but does *not* generate bytecode
     *
     * @param cNode
     * @param node
     */
    private static void processProperty(final ClassNode cNode, PropertyNode node) {
        String name = node.getName();
        FieldNode field = node.getField();
        int propNodeModifiers = node.getModifiers();

        String getterName = "get" + Verifier.capitalize(name);
        String setterName = "set" + Verifier.capitalize(name);

        // GROOVY-3726: clear volatile, transient modifiers so that they don't get applied to methods
        if ((propNodeModifiers & Modifier.VOLATILE) != 0) {
            propNodeModifiers = propNodeModifiers - Modifier.VOLATILE;
        }
        if ((propNodeModifiers & Modifier.TRANSIENT) != 0) {
            propNodeModifiers = propNodeModifiers - Modifier.TRANSIENT;
        }

        Statement getterBlock = node.getGetterBlock();
        if (getterBlock == null) {
            MethodNode getter = cNode.getGetterMethod(getterName);
            if (getter == null && ClassHelper.boolean_TYPE == node.getType()) {
                String secondGetterName = "is" + Verifier.capitalize(name);
                getter = cNode.getGetterMethod(secondGetterName);
            }
            if (!node.isPrivate() && methodNeedsReplacement(cNode, getter)) {
                getterBlock = new ExpressionStatement(new FieldExpression(field));
            }
        }
        Statement setterBlock = node.getSetterBlock();
        if (setterBlock == null) {
            // 2nd arg false below: though not usual, allow setter with non-void return type
            MethodNode setter = cNode.getSetterMethod(setterName, false);
            if (!node.isPrivate() &&
                    (propNodeModifiers & ACC_FINAL) == 0 &&
                    methodNeedsReplacement(cNode, setter)) {
                setterBlock = new ExpressionStatement(
                        new BinaryExpression(
                                new FieldExpression(field),
                                Token.newSymbol(Types.EQUAL, 0, 0),
                                new VariableExpression("value")
                        )
                );
            }
        }

        if (getterBlock != null) {
            MethodNode getter =
                    new MethodNode(getterName, propNodeModifiers, node.getType(), Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, getterBlock);
            getter.setSynthetic(true);
            cNode.addMethod(getter);

            if (ClassHelper.boolean_TYPE == node.getType() || ClassHelper.Boolean_TYPE == node.getType()) {
                String secondGetterName = "is" + Verifier.capitalize(name);
                MethodNode secondGetter =
                        new MethodNode(secondGetterName, propNodeModifiers, node.getType(), Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, getterBlock);
                secondGetter.setSynthetic(true);
                cNode.addMethod(secondGetter);
            }
        }
        if (setterBlock != null) {
            Parameter[] setterParameterTypes = {new Parameter(node.getType(), "value")};
            VariableExpression var = (VariableExpression) ((BinaryExpression) ((ExpressionStatement) setterBlock).getExpression()).getRightExpression();
            var.setAccessedVariable(setterParameterTypes[0]);
            MethodNode setter =
                    new MethodNode(setterName, propNodeModifiers, ClassHelper.VOID_TYPE, setterParameterTypes, ClassNode.EMPTY_ARRAY, setterBlock);
            setter.setSynthetic(true);
            cNode.addMethod(setter);
        }
    }

    private static boolean methodNeedsReplacement(ClassNode classNode, MethodNode m) {
        // no method found, we need to replace
        if (m == null) return true;
        // method is in current class, nothing to be done
        if (m.getDeclaringClass() == classNode) return false;
        // do not overwrite final
        if ((m.getModifiers() & ACC_FINAL) != 0) return false;
        return true;
    }


    private static String helperClassName(final ClassNode traitNode) {
        return traitNode.getName() + TRAIT_HELPER;
    }

    private static String fieldHelperClassName(final ClassNode traitNode) {
        return traitNode.getName() + FIELD_HELPER;
    }

    private void processField(final FieldNode field, final MethodNode initializer, final ClassNode fieldHelper) {
        Expression initialExpression = field.getInitialExpression();
        if (initialExpression != null) {
            VariableExpression thisObject = new VariableExpression(initializer.getParameters()[0]);
            BlockStatement code = (BlockStatement) initializer.getCode();
            MethodCallExpression mce = new MethodCallExpression(
                    new CastExpression(fieldHelper, thisObject),
                    helperSetterName(field),
                    initialExpression
            );
            mce.setImplicitThis(false);
            mce.setSourcePosition(initialExpression);
            code.addStatement(new ExpressionStatement(mce));
        }
        // define setter/getter helper methods
        fieldHelper.addMethod(
                helperSetterName(field),
                ACC_PUBLIC | ACC_ABSTRACT,
                ClassHelper.VOID_TYPE,
                new Parameter[]{new Parameter(field.getOriginType(), "val")},
                ClassNode.EMPTY_ARRAY,
                null
        );
        fieldHelper.addMethod(
                helperGetterName(field),
                ACC_PUBLIC | ACC_ABSTRACT,
                field.getOriginType(),
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                null
        );
    }

    static String helperGetterName(final FieldNode field) {
        return field.getName() + DIRECT_GETTER_SUFFIX;
    }

    static String helperSetterName(final FieldNode field) {
        return field.getName() + DIRECT_SETTER_SUFFIX;
    }

    private MethodNode processMethod(final ClassNode traitClass, final MethodNode methodNode, final ClassNode fieldHelper) {
        Parameter[] initialParams = methodNode.getParameters();
        Parameter[] newParams = new Parameter[initialParams.length + 1];
        newParams[0] = new Parameter(traitClass.getPlainNodeReference(), THIS_OBJECT);
        System.arraycopy(initialParams, 0, newParams, 1, initialParams.length);
        MethodNode mNode = new MethodNode(
                methodNode.getName(),
                ACC_PUBLIC | ACC_STATIC,
                methodNode.getReturnType(),
                newParams,
                methodNode.getExceptions(),
                processBody(new VariableExpression(newParams[0]), methodNode.getCode(), fieldHelper)
        );

        if (methodNode.isAbstract()) {
            mNode.setModifiers(ACC_PUBLIC | ACC_ABSTRACT);
        }
        methodNode.setCode(null);

        methodNode.setModifiers(ACC_PUBLIC | ACC_ABSTRACT);
        return mNode;
    }

    private Statement processBody(VariableExpression thisObject, Statement code, ClassNode fieldHelper) {
        if (code == null) return null;
        TraitReceiverTransformer trn = new TraitReceiverTransformer(thisObject, unit, fieldHelper);
        code.visit(trn);
        return code;
    }

    public static void doExtendTraits(final ClassNode cNode, SourceUnit unit) {
        ClassNode[] interfaces = cNode.getInterfaces();
        for (ClassNode trait : interfaces) {
            List<AnnotationNode> traitAnn = trait.getAnnotations(MY_TYPE);
            if (traitAnn != null && !traitAnn.isEmpty() && !cNode.getNameWithoutPackage().endsWith(TRAIT_HELPER)) {
                Iterator<InnerClassNode> innerClasses = trait.redirect().getInnerClasses();
                if (innerClasses != null && innerClasses.hasNext()) {
                    // trait defined in same source unit
                    ClassNode helperClassNode = null;
                    ClassNode fieldHelperClassNode = null;
                    while (innerClasses.hasNext()) {
                        ClassNode icn = innerClasses.next();
                        if (icn.getName().endsWith(FIELD_HELPER)) {
                            fieldHelperClassNode = icn;
                        } else if (icn.getName().endsWith(TRAIT_HELPER)) {
                            helperClassNode = icn;
                        }
                    }
                    applyTrait(trait, cNode, helperClassNode, fieldHelperClassNode);
                } else {
                    applyPrecompiledTrait(trait, cNode);
                }
            }
        }
    }

    private static void applyPrecompiledTrait(final ClassNode trait, final ClassNode cNode) {
        try {
            final ClassLoader classLoader = trait.getTypeClass().getClassLoader();
            String helperClassName = helperClassName(trait);
            final ClassNode helperClassNode = ClassHelper.make(classLoader.loadClass(helperClassName));
            ClassNode fieldHelperClassNode;
            try {
                fieldHelperClassNode = ClassHelper.make(classLoader.loadClass(fieldHelperClassName(trait)));
            } catch (ClassNotFoundException e) {
                fieldHelperClassNode = null;
            }

            applyTrait(trait, cNode, helperClassNode, fieldHelperClassNode);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private static void applyTrait(final ClassNode trait, final ClassNode cNode, final ClassNode helperClassNode, final ClassNode fieldHelperClassNode) {
        for (MethodNode methodNode : helperClassNode.getAllDeclaredMethods()) {
            String name = methodNode.getName();
            int access = methodNode.getModifiers();
            Parameter[] argumentTypes = methodNode.getParameters();
            ClassNode[] exceptions = methodNode.getExceptions();
            ClassNode returnType = methodNode.getReturnType();
            boolean isAbstract = methodNode.isAbstract();
            if (!isAbstract && argumentTypes.length > 0 && ((access & ACC_STATIC) == ACC_STATIC) && !name.contains("$")) {
                ArgumentListExpression argList = new ArgumentListExpression();
                argList.addExpression(new VariableExpression("this"));
                Parameter[] params = new Parameter[argumentTypes.length - 1];
                for (int i = 1; i < argumentTypes.length; i++) {
                    Parameter parameter = argumentTypes[i];
                    params[i - 1] = new Parameter(parameter.getOriginType(), "arg" + i);
                    argList.addExpression(new VariableExpression(params[i - 1]));
                }
                MethodNode existingMethod = cNode.getDeclaredMethod(name, params);
                if (existingMethod != null || isExistingProperty(name, cNode, params)) {
                    // override exists in the weaved class
                    continue;
                }
                ClassNode[] exceptionNodes = new ClassNode[exceptions == null ? 0 : exceptions.length];
                System.arraycopy(exceptions, 0, exceptionNodes, 0, exceptionNodes.length);
                MethodCallExpression mce = new MethodCallExpression(
                        new ClassExpression(helperClassNode),
                        name,
                        argList
                );
                mce.setImplicitThis(false);
                MethodNode forwarder = new MethodNode(
                        name,
                        access ^ ACC_STATIC,
                        returnType,
                        params,
                        exceptionNodes,
                        new ExpressionStatement(mce)
                );
                cNode.addMethod(forwarder);
            }
        }
        cNode.addObjectInitializerStatements(new ExpressionStatement(
                new MethodCallExpression(
                        new ClassExpression(helperClassNode),
                        STATIC_INIT_METHOD,
                        new ArgumentListExpression(new VariableExpression("this")))
        ));
        if (fieldHelperClassNode != null) {
            // we should implement the field helper interface too
            cNode.addInterface(fieldHelperClassNode);
            // implementation of methods
            List<MethodNode> declaredMethods = fieldHelperClassNode.getAllDeclaredMethods();
            Collections.sort(declaredMethods, GETTER_FIRST_COMPARATOR);
            for (MethodNode methodNode : declaredMethods) {
                String fieldName = methodNode.getName();
                if (fieldName.endsWith(DIRECT_GETTER_SUFFIX) || fieldName.endsWith(DIRECT_SETTER_SUFFIX)) {
                    int suffixIdx = fieldName.lastIndexOf("$");
                    fieldName = fieldName.substring(0, suffixIdx);
                    String operation = methodNode.getName().substring(suffixIdx + 1);
                    boolean getter = "get".equals(operation);
                    if (getter) {
                        // add field
                        cNode.addField(remappedFieldName(trait, fieldName), ACC_PRIVATE, methodNode.getReturnType(), null);
                    }
                    Parameter[] newParams = getter ? Parameter.EMPTY_ARRAY :
                            new Parameter[]{new Parameter(methodNode.getParameters()[0].getOriginType(), "val")};
                    Expression fieldExpr = new VariableExpression(cNode.getField(remappedFieldName(trait, fieldName)));
                    Statement body =
                            getter ? new ReturnStatement(fieldExpr) :
                                    new ExpressionStatement(
                                            new BinaryExpression(
                                                    fieldExpr,
                                                    Token.newSymbol(Types.EQUAL, 0, 0),
                                                    new VariableExpression(newParams[0])
                                            )
                                    );
                    MethodNode impl = new MethodNode(
                            methodNode.getName(),
                            ACC_PUBLIC,
                            methodNode.getReturnType(),
                            newParams,
                            ClassNode.EMPTY_ARRAY,
                            body
                    );
                    impl.addAnnotation(new AnnotationNode(ClassHelper.make(CompileStatic.class)));
                    cNode.addMethod(impl);
                }
            }
        }
    }

    private static boolean isExistingProperty(final String methodName, final ClassNode cNode, final Parameter[] params) {
        String propertyName = methodName;
        boolean getter = false;
        if (methodName.startsWith("get")) {
            propertyName = propertyName.substring(3);
            getter = true;
        } else if (methodName.startsWith("is")) {
            propertyName = propertyName.substring(2);
            getter = true;
        } else if (methodName.startsWith("set")) {
            propertyName = propertyName.substring(3);
        } else {
            return false;
        }
        if (getter && params.length>0) {
            return false;
        }
        if (!getter && params.length!=1) {
            return false;
        }
        propertyName = MetaClassHelper.convertPropertyName(propertyName);
        PropertyNode pNode = cNode.getProperty(propertyName);
        return pNode != null;
    }
}
