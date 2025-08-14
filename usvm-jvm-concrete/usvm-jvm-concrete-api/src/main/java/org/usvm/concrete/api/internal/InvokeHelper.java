package org.usvm.concrete.api.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

@SuppressWarnings("unused")
public class InvokeHelper {
    @SuppressWarnings("deprecation")
    public static Object invokeMethod(Method method, Object obj, Object[] args) throws InvocationTargetException, IllegalAccessException {
        boolean accessibility = method.isAccessible();
        method.setAccessible(true);
        try {
            return method.invoke(obj, args);
        } finally {
            method.setAccessible(accessibility);
        }
    }

    @SuppressWarnings("deprecation")
    public static Object newInstance(Constructor<?> ctor, Object[] params) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        boolean accessibility = ctor.isAccessible();
        ctor.setAccessible(true);
        try {
            return ctor.newInstance(params);
        } finally {
            ctor.setAccessible(accessibility);
        }
    }
}
