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

package org.apache.skywalking.apm.plugin.asf.dubbo;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassInstanceMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;

import static net.bytebuddy.matcher.ElementMatchers.named;

public class DubboInstrumentation extends ClassInstanceMethodsEnhancePluginDefine {

    private static final String ENHANCE_CLASS = "org.apache.dubbo.monitor.support.MonitorFilter";

    private static final String INTERCEPT_CLASS = "org.apache.skywalking.apm.plugin.asf.dubbo.DubboInterceptor";

    /**
     * 需要拦截的类
     */
    @Override
    protected ClassMatch enhanceClass() {
        return NameMatch.byName(ENHANCE_CLASS);
    }

    /**
     * 是否拦截构造器, 返回 null 表示不拦截
     */
    @Override
    public ConstructorInterceptPoint[] getConstructorsInterceptPoints() {
        return null;
    }

    /**
     * 是否拦截实例方法
     */
    @Override
    public InstanceMethodsInterceptPoint[] getInstanceMethodsInterceptPoints() {
        // 返回多个拦截点
        return new InstanceMethodsInterceptPoint[] {
            // 自定义一个拦截点
            new InstanceMethodsInterceptPoint() {
                /**
                 * 拦截 invoke 方法
                 */
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named("invoke");
                }

                /**
                 * 拦截逻辑
                 */
                @Override
                public String getMethodsInterceptor() {
                    return INTERCEPT_CLASS;
                }

                /**
                 * 是否重写参数
                 */
                @Override
                public boolean isOverrideArgs() {
                    return false;
                }
            }
        };
    }
}
