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
package org.codehaus.groovy.reflection;

import groovy.lang.*;

import org.codehaus.groovy.classgen.asm.BytecodeHelper;
import org.codehaus.groovy.runtime.callsite.CallSiteClassLoader;
import org.codehaus.groovy.runtime.metaclass.ClosureMetaClass;
import org.codehaus.groovy.util.LazyReference;
import org.codehaus.groovy.util.FastArray;
import org.codehaus.groovy.util.ReferenceBundle;

import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;

/**
 * @author Alex.Tkachman
 */
public class CachedClass {
    private static final Method[] EMPTY_METHOD_ARRAY = new Method[0];
    private final Class cachedClass;
    public ClassInfo classInfo;
    
    private static ReferenceBundle softBundle = ReferenceBundle.getSoftBundle();
    
    private static final MethodHandle MH_MODULE_CHECK, MH_CAN_ACCESS;
    static {
      final Lookup lookup = MethodHandles.lookup();
      MethodHandle mh_check, mh_canAccess;
      try {
        // MethodHandle to check if we can access a member without setAccessible:
        mh_canAccess = lookup.findVirtual(AccessibleObject.class, "canAccess", methodType(boolean.class, Object.class));
        
        /* create a pre-compiled MethodHandle that is identical to following Java 9 code:
         *  boolean moduleCheck(Class clazz) {
         *   Module m = clazz.getModule();
         *   return m.isOpen(CachedClass.class.getPackage().getName(), CachedClass.class.getModule());
         *  }
         */
        final Class<?> moduleClass = Class.forName("java.lang.Module");
        final MethodHandle mh_Class_getModule = lookup.findVirtual(Class.class, "getModule", methodType(moduleClass));
        final MethodHandle mh_Module_isOpen = lookup.findVirtual(moduleClass, "isOpen",
            methodType(boolean.class, String.class, moduleClass));
        final Object module = mh_Class_getModule.invoke(CachedClass.class);
        mh_check = MethodHandles.insertArguments(mh_Module_isOpen, 1, CachedClass.class.getPackage().getName(), module);
        mh_check = MethodHandles.filterArguments(mh_check, 0, mh_Class_getModule);
      } catch (SecurityException/*should never happen for public methods*/ | ReflectiveOperationException e) {
        mh_check = mh_canAccess = null;
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
      MH_MODULE_CHECK = mh_check;
      MH_CAN_ACCESS = mh_canAccess;
    }

    private final LazyReference<CachedField[]> fields = new LazyReference<CachedField[]>(softBundle) {
        public CachedField[] initValue() {
            final Field[] declaredFields = (Field[])
               AccessController.doPrivileged(new PrivilegedAction<Field[]>() {
                   public Field[] run() {
                       Field[] df = getTheClass().getDeclaredFields();
                       df = (Field[]) makeAccessible(df);
                       return df;                                                   
                   }
               });
            CachedField [] fields = new CachedField[declaredFields.length];
            for (int i = 0; i != fields.length; ++i)
                fields[i] = new CachedField(declaredFields[i]);
            return fields;
        }
    };

    private final LazyReference<CachedConstructor[]> constructors = new LazyReference<CachedConstructor[]>(softBundle) {
        public CachedConstructor[] initValue() {
            final Constructor[] declaredConstructors = (Constructor[])
               AccessController.doPrivileged(new PrivilegedAction/*<Constructor[]>*/() {
                   public /*Constructor[]*/ Object run() {
                       return getTheClass().getDeclaredConstructors();
                   }
               });
            CachedConstructor [] constructors = new CachedConstructor[declaredConstructors.length];
            for (int i = 0; i != constructors.length; ++i)
                constructors[i] = new CachedConstructor(CachedClass.this, declaredConstructors[i]);
            return constructors;
        }
    };
    
    /**
     * Returns {@code true} if the cached class' module is open (allows reflection with {@code setAccessible()}
     * to Groovy's own module, {@code false} if it's from a foreign module (e.g., the Java runtime)
     * and it has not opened the package.
     * For previous Java versions it always returns {@code true}.
     */
    private boolean isClassOpen() {
        if (MH_MODULE_CHECK != null) {
            try {
                return (boolean) MH_MODULE_CHECK.invokeExact(getTheClass());
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new AssertionError("Should never happen", t);
            }
        }
        return true;
    }

    // to be run in PrivilegedAction!
    private AccessibleObject[] makeAccessible(final AccessibleObject[] aoa) {
        if (!isClassOpen()) {
            assert MH_CAN_ACCESS != null;
            // if the class is from a different / not open module, we can only make public members available in Java 9:
            final ArrayList<AccessibleObject> ret = new ArrayList<>(aoa.length);
            for (final AccessibleObject ao : aoa) {
                try {
                    final int modifiers = ((Member) ao).getModifiers();
                    if (Modifier.isStatic(modifiers)) {
                        // if the member is static we can use Java9's AccessibleObject#canAccess(null):
                        final boolean canAccess = (boolean) MH_CAN_ACCESS.invokeExact(ao, (Object) null);
                        if (canAccess) {
                          // no need to call setAccessible, as it's accessible already
                          ret.add(ao);
                        }
                    } else if (Modifier.isPublic(modifiers)) {
                        // if it's public we have good chances to access it, we cannot do better checks
                        // as we have no instance to call Java9's AccessibleObject#canAccess(null):
                        ret.add(ao);
                    }
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new AssertionError(t); 
                }
            }
            return ret.toArray((AccessibleObject[]) Array.newInstance(aoa.getClass().getComponentType(), ret.size()));
        }
        
        try {
            AccessibleObject.setAccessible(aoa, true);
            return aoa;
        } catch (Throwable outer) {
            // swallow for strict security managers, module systems, android or others,
            // but try one-by-one to get the allowed ones at least
            final ArrayList<AccessibleObject> ret = new ArrayList<>(aoa.length);
            for (final AccessibleObject ao : aoa) {
                try {
                    ao.setAccessible(true);
                    ret.add(ao);
                } catch (Throwable inner) {
                    // swallow for strict security managers, android or others
                }
            }
            return ret.toArray((AccessibleObject[]) Array.newInstance(aoa.getClass().getComponentType(), ret.size()));
        }
    }

    private final LazyReference<CachedMethod[]> methods = new LazyReference<CachedMethod[]>(softBundle) {
        public CachedMethod[] initValue() {
            final Method[] declaredMethods = (Method[])
               AccessController.doPrivileged(new PrivilegedAction/*<Method[]>*/() {
                   public /*Method[]*/ Object run() {
                       try {
                           Method[] dm = getTheClass().getDeclaredMethods();
                           dm = (Method[]) makeAccessible(dm);
                           return dm;
                       } catch (Throwable e) {
                           // Typically, Android can throw ClassNotFoundException
                           return EMPTY_METHOD_ARRAY;
                       }
                   }
               });
            List<CachedMethod> methods = new ArrayList<CachedMethod>(declaredMethods.length);
            List<CachedMethod> mopMethods = new ArrayList<CachedMethod>(declaredMethods.length);
            for (int i = 0; i != declaredMethods.length; ++i) {
                final CachedMethod cachedMethod = new CachedMethod(CachedClass.this, declaredMethods[i]);
                final String name = cachedMethod.getName();

                if (declaredMethods[i].isBridge() || name.indexOf('+') >= 0) {
                    // Skip Synthetic methods inserted by JDK 1.5 compilers and later
                    continue;
                } /*else if (Modifier.isAbstract(reflectionMethod.getModifiers())) {
                   continue;
                }*/

                if (name.startsWith("this$") || name.startsWith("super$"))
                  mopMethods.add(cachedMethod);
                else
                  methods.add(cachedMethod);
            }
            CachedMethod [] resMethods = methods.toArray(new CachedMethod[methods.size()]);
            Arrays.sort(resMethods);

            final CachedClass superClass = getCachedSuperClass();
            if (superClass != null) {
                superClass.getMethods();
                final CachedMethod[] superMopMethods = superClass.mopMethods;
                for (int i = 0; i != superMopMethods.length; ++i)
                  mopMethods.add(superMopMethods[i]);
            }
            CachedClass.this.mopMethods = mopMethods.toArray(new CachedMethod[mopMethods.size()]);
            Arrays.sort(CachedClass.this.mopMethods, CachedMethodComparatorByName.INSTANCE);

            return resMethods;
        }
    };

    private final LazyReference<CachedClass> cachedSuperClass = new LazyReference<CachedClass>(softBundle) {
        public CachedClass initValue() {
            if (!isArray)
              return ReflectionCache.getCachedClass(getTheClass().getSuperclass());
            else
              if (cachedClass.getComponentType().isPrimitive() || cachedClass.getComponentType() == Object.class)
                return ReflectionCache.OBJECT_CLASS;
              else
                return ReflectionCache.OBJECT_ARRAY_CLASS;
        }
    };

    private final LazyReference<CallSiteClassLoader> callSiteClassLoader = new LazyReference<CallSiteClassLoader>(softBundle) {
        public CallSiteClassLoader initValue() {
            return
               AccessController.doPrivileged(new PrivilegedAction<CallSiteClassLoader>() {
                   public CallSiteClassLoader run() {
                       return new CallSiteClassLoader(CachedClass.this.cachedClass);
                   }
               });
        }
    };

    private final LazyReference<LinkedList<ClassInfo>> hierarchy = new LazyReference<LinkedList<ClassInfo>>(softBundle) {
        public LinkedList<ClassInfo> initValue() {
            Set<ClassInfo> res = new LinkedHashSet<ClassInfo> ();

            res.add(classInfo);

            for (CachedClass iface : getDeclaredInterfaces())
              res.addAll(iface.getHierarchy());

            final CachedClass superClass = getCachedSuperClass();
            if (superClass != null)
              res.addAll(superClass.getHierarchy());

            if (isInterface)
              res.add(ReflectionCache.OBJECT_CLASS.classInfo);

            return new LinkedList<ClassInfo> (res);
        }
    };

    static final MetaMethod[] EMPTY = new MetaMethod[0];

    int hashCode;

    public  CachedMethod [] mopMethods;
    public static final CachedClass[] EMPTY_ARRAY = new CachedClass[0];

    private final LazyReference<Set<CachedClass>> declaredInterfaces = new LazyReference<Set<CachedClass>> (softBundle) {
        public Set<CachedClass> initValue() {
            Set<CachedClass> res = new HashSet<CachedClass> (0);

            Class[] classes = getTheClass().getInterfaces();
            for (Class cls : classes) {
                res.add(ReflectionCache.getCachedClass(cls));
            }
            return res;
        }
    };

    private final LazyReference<Set<CachedClass>> interfaces = new LazyReference<Set<CachedClass>> (softBundle) {
        public Set<CachedClass> initValue() {
            Set<CachedClass> res = new HashSet<CachedClass> (0);

            if (getTheClass().isInterface())
              res.add(CachedClass.this);

            Class[] classes = getTheClass().getInterfaces();
            for (Class cls : classes) {
                final CachedClass aClass = ReflectionCache.getCachedClass(cls);
                if (!res.contains(aClass))
                    res.addAll(aClass.getInterfaces());
            }

            final CachedClass superClass = getCachedSuperClass();
            if (superClass != null)
              res.addAll(superClass.getInterfaces());

            return res;
        }
    };

    public final boolean isArray;
    public final boolean isPrimitive;
    public final int modifiers;
    int distance = -1;
    public final boolean isInterface;
    public final boolean isNumber;

    public CachedClass(Class klazz, ClassInfo classInfo) {
        cachedClass = klazz;
        this.classInfo = classInfo;
        isArray = klazz.isArray();
        isPrimitive = klazz.isPrimitive();
        modifiers = klazz.getModifiers();
        isInterface = klazz.isInterface();
        isNumber = Number.class.isAssignableFrom(klazz);

        for (CachedClass inf : getInterfaces()) {
            ReflectionCache.isAssignableFrom(klazz, inf.cachedClass);
        }

        for (CachedClass cur = this; cur != null; cur = cur.getCachedSuperClass()) {
            ReflectionCache.setAssignableFrom(cur.cachedClass, klazz);
        }
    }

    public CachedClass getCachedSuperClass() {
        return cachedSuperClass.get();
    }

    public Set<CachedClass> getInterfaces() {
        return interfaces.get();
    }

    public Set<CachedClass> getDeclaredInterfaces() {
        return declaredInterfaces.get();
    }

    public CachedMethod[] getMethods() {
        return methods.get();
    }

    public CachedField[] getFields() {
        return fields.get();
    }

    public CachedConstructor[] getConstructors() {
        return constructors.get();
    }

    public CachedMethod searchMethods(String name, CachedClass[] parameterTypes) {
        CachedMethod[] methods = getMethods();

        CachedMethod res = null;
        for (CachedMethod m : methods) {
            if (m.getName().equals(name)
                    && ReflectionCache.arrayContentsEq(parameterTypes, m.getParameterTypes())
                    && (res == null || res.getReturnType().isAssignableFrom(m.getReturnType())))
                res = m;
        }

        return res;
    }

    public int getModifiers() {
        return modifiers;
    }

    public Object coerceArgument(Object argument) {
        return argument;
    }
    
    public int getSuperClassDistance() {
        if (distance>=0) return distance;

        int distance = 0;
        for (Class klazz= getTheClass(); klazz != null; klazz = klazz.getSuperclass()) {
            distance++;
        }
        this.distance = distance;
        return distance;
    }

    public int hashCode() {
        if (hashCode == 0) {
          hashCode = super.hashCode();
          if (hashCode == 0)
            hashCode = 0xcafebebe;
        }
        return hashCode;
    }

    public boolean isPrimitive() {
        return isPrimitive;
    }

    public boolean isVoid() {
        return getTheClass() == void.class;
    }
    
    public boolean isInterface() {
        return isInterface;
    }

    public String getName() {
        return getTheClass().getName();
    }

    public String getTypeDescription() {
        return BytecodeHelper.getTypeDescription(getTheClass());
    }

    public final Class getTheClass() {
        return cachedClass;
    }

    public MetaMethod[] getNewMetaMethods() {
        List<MetaMethod> arr = new ArrayList<MetaMethod>();
        arr.addAll(Arrays.asList(classInfo.newMetaMethods));

        final MetaClass metaClass = classInfo.getStrongMetaClass();
        if (metaClass != null && metaClass instanceof ExpandoMetaClass) {
            arr.addAll(((ExpandoMetaClass)metaClass).getExpandoMethods());
        }

        if (isInterface) {
            MetaClass mc = ReflectionCache.OBJECT_CLASS.classInfo.getStrongMetaClass();
            addSubclassExpandos(arr, mc);
        }
        else {
            for (CachedClass cls = this; cls != null; cls = cls.getCachedSuperClass()) {
                MetaClass mc = cls.classInfo.getStrongMetaClass();
                addSubclassExpandos(arr, mc);
            }
        }

        for (CachedClass inf : getInterfaces()) {
            MetaClass mc = inf.classInfo.getStrongMetaClass();
            addSubclassExpandos(arr, mc);
        }

        return arr.toArray(new MetaMethod[arr.size()]);
    }

    private void addSubclassExpandos(List<MetaMethod> arr, MetaClass mc) {
        if (mc != null && mc instanceof ExpandoMetaClass) {
            ExpandoMetaClass emc = (ExpandoMetaClass) mc;
            for (Object mm : emc.getExpandoSubclassMethods()) {
                if (mm instanceof MetaMethod) {
                    MetaMethod method = (MetaMethod) mm;
                    if (method.getDeclaringClass() == this)
                      arr.add(method);
                }
                else {
                    FastArray farr = (FastArray) mm;
                    for (int i = 0; i != farr.size; ++i) {
                        MetaMethod method = (MetaMethod) farr.get(i);
                        if (method.getDeclaringClass() == this)
                          arr.add(method);
                    }
                }
            }
        }
    }

    public void setNewMopMethods(List<MetaMethod> arr) {
        final MetaClass metaClass = classInfo.getStrongMetaClass();
        if (metaClass != null) {
          if (metaClass.getClass() == MetaClassImpl.class) {
              classInfo.setStrongMetaClass(null);
              updateSetNewMopMethods(arr);
              classInfo.setStrongMetaClass(new MetaClassImpl(metaClass.getTheClass()));
              return;
          }

          if (metaClass.getClass() == ExpandoMetaClass.class) {
              classInfo.setStrongMetaClass(null);
              updateSetNewMopMethods(arr);
              ExpandoMetaClass newEmc = new ExpandoMetaClass(metaClass.getTheClass());
              newEmc.initialize();
              classInfo.setStrongMetaClass(newEmc);
              return;
          }

          throw new GroovyRuntimeException("Can't add methods to class " + getTheClass().getName() + ". Strong custom meta class already set.");
        }

        classInfo.setWeakMetaClass(null);
        updateSetNewMopMethods(arr);
    }

    private void updateSetNewMopMethods(List<MetaMethod> arr) {
        if (arr != null) {
            final MetaMethod[] metaMethods = arr.toArray(new MetaMethod[arr.size()]);
            classInfo.dgmMetaMethods = metaMethods;
            classInfo.newMetaMethods = metaMethods;
        }
        else
            classInfo.newMetaMethods = classInfo.dgmMetaMethods;
    }

    public void addNewMopMethods(List<MetaMethod> arr) {
        final MetaClass metaClass = classInfo.getStrongMetaClass();
        if (metaClass != null) {
          if (metaClass.getClass() == MetaClassImpl.class) {
              classInfo.setStrongMetaClass(null);
              List<MetaMethod> res = new ArrayList<MetaMethod>();
              Collections.addAll(res, classInfo.newMetaMethods);
              res.addAll(arr);
              updateSetNewMopMethods(res);
              MetaClassImpl answer = new MetaClassImpl(((MetaClassImpl)metaClass).getRegistry(),metaClass.getTheClass());
              answer.initialize();
              classInfo.setStrongMetaClass(answer);
              return;
          }

          if (metaClass.getClass() == ExpandoMetaClass.class) {
              ExpandoMetaClass emc = (ExpandoMetaClass)metaClass;
              classInfo.setStrongMetaClass(null);
              updateAddNewMopMethods(arr);
              ExpandoMetaClass newEmc = new ExpandoMetaClass(metaClass.getTheClass());
              for (MetaMethod mm : emc.getExpandoMethods()) {
                  newEmc.registerInstanceMethod(mm);
              }
              newEmc.initialize();
              classInfo.setStrongMetaClass(newEmc);
              return;
          }

          throw new GroovyRuntimeException("Can't add methods to class " + getTheClass().getName() + ". Strong custom meta class already set.");
        }

        classInfo.setWeakMetaClass(null);

        updateAddNewMopMethods(arr);
    }

    private void updateAddNewMopMethods(List<MetaMethod> arr) {
        List<MetaMethod> res = new ArrayList<MetaMethod>();
        res.addAll(Arrays.asList(classInfo.newMetaMethods));
        res.addAll(arr);
        classInfo.newMetaMethods = res.toArray(new MetaMethod[res.size()]);
        Class theClass = classInfo.getCachedClass().getTheClass();
        if (theClass==Closure.class || theClass==Class.class) {
            ClosureMetaClass.resetCachedMetaClasses();
        }
    }

    public boolean isAssignableFrom(Class argument) {
        return argument == null || ReflectionCache.isAssignableFrom(getTheClass(), argument);
    }

    public boolean isDirectlyAssignable(Object argument) {
        return ReflectionCache.isAssignableFrom(getTheClass(), argument.getClass());
    }

    public CallSiteClassLoader getCallSiteLoader() {
        return callSiteClassLoader.get();
    }

    public Collection<ClassInfo> getHierarchy() {
        return hierarchy.get();
    }

    public static class CachedMethodComparatorByName implements Comparator {
        public static final Comparator INSTANCE = new CachedMethodComparatorByName();

        public int compare(Object o1, Object o2) {
              return ((CachedMethod)o1).getName().compareTo(((CachedMethod)o2).getName());
        }
    }

    public static class CachedMethodComparatorWithString implements Comparator {
        public static final Comparator INSTANCE = new CachedMethodComparatorWithString();

        public int compare(Object o1, Object o2) {
            if (o1 instanceof CachedMethod)
              return ((CachedMethod)o1).getName().compareTo((String)o2);
            else
              return ((String)o1).compareTo(((CachedMethod)o2).getName());
        }
    }

    public String toString() {
        return cachedClass.toString();
    }

    /**
     * compatibility method
     * @return this
     */
    public CachedClass getCachedClass () {
        return this;
    }
}
