package io.github.mihirraj.perflens.core.util;

import java.util.List;

/** Finds the first stack frame that belongs to the application's own code. */
public final class CallerResolver {
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final String CGLIB = "$$SpringCGLIB$$";
    public static final String AFTER_BODY = " (after the method body: transaction commit / flush)";

    private final List<String> basePackages;

    public CallerResolver(List<String> basePackages) {
        this.basePackages = basePackages.stream().map(p -> p.endsWith(".") ? p : p + ".").toList();
    }

    /** True for real application classes; false for generated proxies (Spring CGLIB, Hibernate, ByteBuddy). */
    public boolean isUserClass(String className) {
        if (className == null || className.startsWith("io.github.mihirraj.perflens.") || className.contains("$$")
                || className.contains("$HibernateProxy") || className.contains("$ByteBuddy")) return false;
        return inBasePackage(className);
    }

    private boolean inBasePackage(String className) {
        for (String p : basePackages) if (className.startsWith(p)) return true;
        return false;
    }

    /**
     * "com.acme.OrderService.load:42", or null when no user frame is on the stack. If the first
     * application frame is a Spring proxy, the SQL ran in an interceptor after the method body
     * returned (typically Hibernate flushing at transaction commit); that is reported explicitly.
     */
    public String currentUserFrame() {
        return WALKER.walk(s -> {
            for (StackWalker.StackFrame f : (Iterable<StackWalker.StackFrame>) s::iterator) {
                String cls = f.getClassName();
                if (isUserClass(cls)) return cls + "." + f.getMethodName() + ":" + f.getLineNumber();
                int proxy = cls.indexOf(CGLIB);
                if (proxy > 0 && inBasePackage(cls)) return cls.substring(0, proxy) + "." + f.getMethodName() + AFTER_BODY;
            }
            return null;
        });
    }

    /** Configured package names, without the trailing dot used internally for prefix matching. */
    public List<String> basePackages() {
        return basePackages.stream().map(p -> p.substring(0, p.length() - 1)).toList();
    }
}
