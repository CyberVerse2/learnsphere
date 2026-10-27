package edu.learnsphere.app.dev;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Implements a service interface with the methods an object happens to have: a call goes to the public
 * method of {@code implementation} with the same name and parameter types, and any other call throws
 * {@link UnsupportedOperationException}. Lets the demo answer the calls its screens make without writing
 * every method of every service.
 */
final class Partial {

    private Partial() {
    }

    static <T> T of(Class<T> type, Object implementation) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            switch (method.getName()) {
                case "toString" -> {
                    if (method.getParameterCount() == 0) {
                        return "Demo" + type.getSimpleName();
                    }
                }
                case "hashCode" -> {
                    if (method.getParameterCount() == 0) {
                        return System.identityHashCode(self);
                    }
                }
                case "equals" -> {
                    if (method.getParameterCount() == 1) {
                        return self == args[0];
                    }
                }
                default -> {
                }
            }
            Method target;
            try {
                target = implementation.getClass().getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName() + " is not part of the demo");
            }
            try {
                target.setAccessible(true);
                return target.invoke(implementation, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        return type.cast(proxy);
    }
}
