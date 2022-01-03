/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */


package org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.implementation.bind.annotation.Morph;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.AbstractClassEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.EnhanceContext;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.bootstrap.BootstrapInstrumentBoost;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.DeclaredInstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.EnhanceException;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.util.StringUtil;

import static net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE;
import static net.bytebuddy.jar.asm.Opcodes.ACC_VOLATILE;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * This class controls all enhance operations, including enhance constructors, instance methods and static methods. All
 * the enhances base on three types interceptor point: {@link ConstructorInterceptPoint}, {@link
 * InstanceMethodsInterceptPoint} and {@link StaticMethodsInterceptPoint} If plugin is going to enhance constructors,
 * instance methods, or both, {@link ClassEnhancePluginDefine} will add a field of {@link Object} type.
 *
 * @author wusheng
 */
public abstract class ClassEnhancePluginDefine extends AbstractClassEnhancePluginDefine {
    private static final ILog logger = LogManager.getLogger(ClassEnhancePluginDefine.class);

    /**
     * New field name.
     */
    public static final String CONTEXT_ATTR_NAME = "_$EnhancedClassField_ws";

    /**
     * Begin to define how to enhance class. After invoke this method, only means definition is finished.
     *
     * @param typeDescription target class description
     * @param newClassBuilder byte-buddy's builder to manipulate class bytecode.
     * @return new byte-buddy's builder for further manipulation.
     */
    @Override
    protected DynamicType.Builder<?> enhance(TypeDescription typeDescription,
                                             DynamicType.Builder<?> newClassBuilder, ClassLoader classLoader,
                                             EnhanceContext context) throws PluginException {
        // 增强静态方法
        newClassBuilder = this.enhanceClass(typeDescription, newClassBuilder, classLoader);
        // 增强构造方法和实例方法
        newClassBuilder = this.enhanceInstance(typeDescription, newClassBuilder, classLoader, context);

        return newClassBuilder;
    }

    /**
     * Enhance a class to intercept constructors and class instance methods.
     *
     * @param typeDescription target class description
     * @param newClassBuilder byte-buddy's builder to manipulate class bytecode.
     * @return new byte-buddy's builder for further manipulation.
     */
    private DynamicType.Builder<?> enhanceInstance(TypeDescription typeDescription,
                                                   DynamicType.Builder<?> newClassBuilder, ClassLoader classLoader,
                                                   EnhanceContext context) throws PluginException {
        // 获取生效插件定义的构造方法拦截点
        ConstructorInterceptPoint[] constructorInterceptPoints = getConstructorsInterceptPoints();
        // 获取生效插件定义的实例方法拦截点
        InstanceMethodsInterceptPoint[] instanceMethodsInterceptPoints = getInstanceMethodsInterceptPoints();
        String enhanceOriginClassName = typeDescription.getTypeName();
        boolean existedConstructorInterceptPoint = false;
        if (constructorInterceptPoints != null && constructorInterceptPoints.length > 0) {
            existedConstructorInterceptPoint = true;
        }
        boolean existedMethodsInterceptPoints = false;
        if (instanceMethodsInterceptPoints != null && instanceMethodsInterceptPoints.length > 0) {
            existedMethodsInterceptPoints = true;
        }

        /**
         * nothing need to be enhanced in class instance, maybe need enhance static methods.
         */
        if (!existedConstructorInterceptPoint && !existedMethodsInterceptPoints) {
            return newClassBuilder;
        }

        /**
         * Manipulate class source code.<br/>
         *
         * new class need:<br/>
         * 1.Add field, name {@link #CONTEXT_ATTR_NAME}.
         * 2.Add a field accessor for this field.
         *
         * And make sure the source codes manipulation only occurs once.
         * 判断当前类是否已经被增强过了, 因为不能重复定义字段, 所以这里需要判断一下
         *
         */
        if (!context.isObjectExtended()) {
            // 未增强过就给这个类定义一个字段 _$EnhancedClassField_ws 同时实现 EnhancedInstance 接口
            newClassBuilder = newClassBuilder.defineField(CONTEXT_ATTR_NAME, Object.class, ACC_PRIVATE | ACC_VOLATILE)
                    .implement(EnhancedInstance.class)
                    .intercept(FieldAccessor.ofField(CONTEXT_ATTR_NAME));
            // 标记已经增强过, 防止重复定义
            context.extendObjectCompleted();
        }

        /**
         * 2. enhance constructors
         * 增强构造方法
         */
        if (existedConstructorInterceptPoint) {
            for (ConstructorInterceptPoint constructorInterceptPoint : constructorInterceptPoints) {
                // 是 jdk 的类库
                if (isBootstrapInstrumentation()) {
                    newClassBuilder = newClassBuilder.constructor(constructorInterceptPoint.getConstructorMatcher()).intercept(SuperMethodCall.INSTANCE
                            .andThen(MethodDelegation.withDefaultConfiguration()
                                    .to(BootstrapInstrumentBoost.forInternalDelegateClass(constructorInterceptPoint.getConstructorInterceptor()))
                            )
                    );
                }
                // 非 jdk 类库
                else {
                    newClassBuilder = newClassBuilder.constructor(constructorInterceptPoint.getConstructorMatcher()).intercept(SuperMethodCall.INSTANCE
                            .andThen(MethodDelegation.withDefaultConfiguration()
                                    // 使用 ByteBuddy api 将真正的代理逻辑交给 ConstructorInter
                                    .to(new ConstructorInter(constructorInterceptPoint.getConstructorInterceptor(), classLoader))
                            )
                    );
                }
            }
        }

        /**
         * 3. enhance instance methods
         * 增强实例方法
         */
        if (existedMethodsInterceptPoints) {
            for (InstanceMethodsInterceptPoint instanceMethodsInterceptPoint : instanceMethodsInterceptPoints) {
                String interceptor = instanceMethodsInterceptPoint.getMethodsInterceptor();
                if (StringUtil.isEmpty(interceptor)) {
                    throw new EnhanceException("no InstanceMethodsAroundInterceptor define to enhance class " + enhanceOriginClassName);
                }
                // 获取每个拦截点的方法匹配器
                ElementMatcher.Junction<MethodDescription> junction = not(isStatic()).and(instanceMethodsInterceptPoint.getMethodsMatcher());
                // 判断是不是声明式拦截点, 没有办法直接定位到具体的方法名称, 类似标注了 @RestController
                if (instanceMethodsInterceptPoint instanceof DeclaredInstanceMethodsInterceptPoint) {
                    junction = junction.and(ElementMatchers.<MethodDescription>isDeclaredBy(typeDescription));
                }
                // 重写参数
                if (instanceMethodsInterceptPoint.isOverrideArgs()) {
                    // 是 jdk 类库
                    if (isBootstrapInstrumentation()) {
                        newClassBuilder =
                                newClassBuilder.method(junction)
                                        .intercept(
                                                MethodDelegation.withDefaultConfiguration()
                                                        .withBinders(
                                                                Morph.Binder.install(OverrideCallable.class)
                                                        )
                                                        .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor))
                                        );
                    }
                    // 非 jdk 类库
                    else {
                        newClassBuilder =
                                newClassBuilder.method(junction)
                                        .intercept(
                                                MethodDelegation.withDefaultConfiguration()
                                                        .withBinders(
                                                                Morph.Binder.install(OverrideCallable.class)
                                                        )
                                                        .to(new InstMethodsInterWithOverrideArgs(interceptor, classLoader))
                                        );
                    }
                }
                // 不重写参数
                else {
                    // jdk 类库
                    if (isBootstrapInstrumentation()) {
                        newClassBuilder =
                                newClassBuilder.method(junction)
                                        .intercept(
                                                MethodDelegation.withDefaultConfiguration()
                                                        .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor))
                                        );
                    }
                    // 非 jdk 类库
                    else {
                        newClassBuilder =
                                newClassBuilder.method(junction)
                                        .intercept(
                                                MethodDelegation.withDefaultConfiguration()
                                                        .to(new InstMethodsInter(interceptor, classLoader))
                                        );
                    }
                }
            }
        }

        return newClassBuilder;
    }

    /**
     * Enhance a class to intercept class static methods.
     *
     * @param typeDescription target class description
     * @param newClassBuilder byte-buddy's builder to manipulate class bytecode.
     * @return new byte-buddy's builder for further manipulation.
     */
    private DynamicType.Builder<?> enhanceClass(TypeDescription typeDescription,
                                                DynamicType.Builder<?> newClassBuilder, ClassLoader classLoader) throws PluginException {
        // 获取生效插件定义的静态方法拦截点
        StaticMethodsInterceptPoint[] staticMethodsInterceptPoints = getStaticMethodsInterceptPoints();
        String enhanceOriginClassName = typeDescription.getTypeName();
        if (staticMethodsInterceptPoints == null || staticMethodsInterceptPoints.length == 0) {
            return newClassBuilder;
        }

        for (StaticMethodsInterceptPoint staticMethodsInterceptPoint : staticMethodsInterceptPoints) {
            // 获取自定义的方法拦截点, 它是一个字符串(类限定名), 后面会通过反射创建
            String interceptor = staticMethodsInterceptPoint.getMethodsInterceptor();
            if (StringUtil.isEmpty(interceptor)) {
                throw new EnhanceException("no StaticMethodsAroundInterceptor define to enhance class " + enhanceOriginClassName);
            }
            // 判断是否重写参数
            if (staticMethodsInterceptPoint.isOverrideArgs()) {
                // 是 jdk 的类库
                if (isBootstrapInstrumentation()) {
                    newClassBuilder = newClassBuilder.method(isStatic().and(staticMethodsInterceptPoint.getMethodsMatcher()))
                            .intercept(
                                    MethodDelegation.withDefaultConfiguration()
                                            .withBinders(
                                                    Morph.Binder.install(OverrideCallable.class)
                                            )
                                            .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor))
                            );
                }
                // 非 jdk 类库
                else {
                    newClassBuilder = newClassBuilder.method(isStatic().and(staticMethodsInterceptPoint.getMethodsMatcher()))
                            .intercept(
                                    MethodDelegation.withDefaultConfiguration()
                                            .withBinders(
                                                    Morph.Binder.install(OverrideCallable.class)
                                            )
                                            .to(new StaticMethodsInterWithOverrideArgs(interceptor))
                            );
                }
            }
            // 不修改重写参数
            else {
                // 是 jdk 的类库
                if (isBootstrapInstrumentation()) {
                    newClassBuilder = newClassBuilder.method(isStatic().and(staticMethodsInterceptPoint.getMethodsMatcher()))
                            .intercept(
                                    MethodDelegation.withDefaultConfiguration()
                                            .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor))
                            );
                }
                // 不是 jdk 类库
                else {
                    // 获取生效插件定义的静态方法拦截点
                    newClassBuilder = newClassBuilder.method(isStatic().and(staticMethodsInterceptPoint.getMethodsMatcher()))
                            .intercept(
                                    MethodDelegation.withDefaultConfiguration()
                                            // 使用 ByteBuddy api 将真正的代理逻辑交给 StaticMethodsInter
                                            .to(new StaticMethodsInter(interceptor))
                            );
                }
            }

        }

        return newClassBuilder;
    }
}
