package dev.perflens.autoconfigure.method;

import dev.perflens.core.PerfLens;
import dev.perflens.core.method.MethodRegistry;
import dev.perflens.core.trace.TraceContext;
import dev.perflens.core.trace.TraceWindow;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Times public methods of Spring beans in the application's base packages and keeps the slowest
 * calls with their arguments. Works through Spring's existing auto-proxy creator (no agent);
 * like any Spring AOP it cannot see private methods or self-invocation ({@code this.foo()}).
 * When no trace window is open (scheduled jobs, listeners, async), the call opens one.
 */
public final class PerfLensMethodAdvisor extends AbstractPointcutAdvisor {
    private final transient ObjectProvider<PerfLens> perfLens;
    private final transient Pointcut pointcut;
    private final transient Advice advice;

    public PerfLensMethodAdvisor(ObjectProvider<PerfLens> perfLens, List<String> basePackages) {
        this.perfLens = perfLens;
        List<String> prefixes = basePackages.stream().map(p -> p.endsWith(".") ? p : p + ".").toList();
        this.pointcut = new StaticMethodMatcherPointcut() {
            {
                setClassFilter(clazz -> eligibleClass(clazz, prefixes));
            }

            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                int mod = method.getModifiers();
                return Modifier.isPublic(mod) && !Modifier.isStatic(mod) && !method.isSynthetic()
                        && !method.isBridge() && method.getDeclaringClass() != Object.class
                        && !isTrivialAccessor(method);
            }
        };
        this.advice = (MethodInterceptor) this::invoke;
        setOrder(LOWEST_PRECEDENCE);
    }

    static boolean eligibleClass(Class<?> clazz, List<String> prefixes) {
        String name = clazz.getName();
        if (name.startsWith("dev.perflens.") || name.contains("$$")) return false;
        boolean inBase = false;
        for (String p : prefixes) if (name.startsWith(p)) { inBase = true; break; }
        if (!inBase) return false;
        int mod = clazz.getModifiers();
        if (clazz.isInterface() || clazz.isEnum() || clazz.isRecord() || clazz.isAnnotation()
                || Modifier.isFinal(mod) || clazz.isAnonymousClass() || clazz.isLocalClass()) return false;
        if (AnnotatedElementUtils.hasAnnotation(clazz, Configuration.class)) return false;
        return !(BeanPostProcessor.class.isAssignableFrom(clazz) || BeanFactoryPostProcessor.class.isAssignableFrom(clazz)
                || FactoryBean.class.isAssignableFrom(clazz) || Advisor.class.isAssignableFrom(clazz)
                || Advice.class.isAssignableFrom(clazz));
    }

    private static boolean isTrivialAccessor(Method m) {
        String n = m.getName();
        int params = m.getParameterCount();
        return (params == 0 && ((n.startsWith("get") && n.length() > 3) || (n.startsWith("is") && n.length() > 2)))
                || (params == 1 && n.startsWith("set") && n.length() > 3)
                || n.equals("toString") || n.equals("hashCode") || n.equals("equals");
    }

    private Object invoke(MethodInvocation inv) throws Throwable {
        PerfLens pl = perfLens.getIfAvailable();
        if (pl == null || !pl.isCollecting()) return inv.proceed();
        Method m = inv.getMethod();
        Object target = inv.getThis();
        Class<?> targetClass = target == null ? m.getDeclaringClass() : ClassUtils.getUserClass(AopUtils.getTargetClass(target));
        MethodRegistry.MethodStat stat = pl.methods().stat(m, targetClass);

        TraceWindow opened = TraceContext.openIfAbsent(targetClass.getSimpleName() + "." + m.getName());
        TraceWindow window = opened != null ? opened : TraceContext.current();
        MethodRegistry.Before before = MethodRegistry.Before.of(window);
        Throwable error = null;
        long t0 = System.nanoTime();
        try {
            return inv.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            long micros = (System.nanoTime() - t0) / 1000;
            try {
                if (stat != null) pl.methods().record(stat, micros, error, inv.getArguments(), before, window);
                if (opened != null) pl.traces().complete(opened, error != null);
            } finally {
                if (opened != null) TraceContext.close(opened);
            }
        }
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return advice;
    }
}
