package edu.learnsphere.core.service.impl;

import java.lang.reflect.Proxy;

/**
 * Stand-ins for services whose implementation is not written yet: every call throws
 * {@link UnsupportedOperationException} naming the service and method. {@link WorkspaceImpl} uses them so the
 * workspace contract is complete while the pipelines land one by one.
 */
final class Unimplemented {

    private Unimplemented() {
    }

    static <T> T service(Class<T> type) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) ->
                switch (method.getName()) {
                    case "toString" -> "Unimplemented[" + type.getSimpleName() + "]";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> throw new UnsupportedOperationException(
                            type.getSimpleName() + "." + method.getName() + " is not implemented");
                });
        return type.cast(proxy);
    }
}
