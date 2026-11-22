package dev.ticbox.companion.healthmodule;

import android.app.Application;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/** Wrap two existing VibrationPatternLoader interfaces. No ART method patch or global hook. */
final class OemAlertAdapter {
    private final AlertPolicy policy;
    private final Class<?> loaderType, patternType, infoType, itemType, dataType, idType, factoryType;
    private final Method load, item, data, identity, pattern, local, mcu, ancs, sound, vibrate, packageName, pureVibration;
    private final Object blockedRingtone;

    OemAlertAdapter(ClassLoader classes, AlertPolicy policy) throws ReflectiveOperationException {
        this.policy = policy;
        loaderType = classes.loadClass("o6.z"); patternType = classes.loadClass("o6.z$a");
        infoType = classes.loadClass("o6.p"); itemType = classes.loadClass("q4.k"); dataType = classes.loadClass("q4.l");
        idType = classes.loadClass("com.google.android.clockwork.sysui.backend.notification.sysuicontract.StreamItemIdAndRevision");
        factoryType = classes.loadClass("p6.i");
        if (!loaderType.isInterface() || !patternType.isInterface() || !loaderType.isAssignableFrom(factoryType)) throw new NoSuchMethodException();
        load = method(loaderType, "a", patternType, infoType, boolean.class);
        method(patternType, "a", void.class); method(patternType, "b", boolean.class); method(patternType, "c", boolean.class);
        item = method(infoType, "a", itemType); pattern = method(infoType, "b", long[].class);
        data = method(itemType, "c", dataType); identity = method(itemType, "d", idType);
        local = method(dataType, "A3", boolean.class); mcu = method(dataType, "w3", boolean.class); ancs = method(dataType, "o3", boolean.class);
        sound = method(dataType, "w2", boolean.class); vibrate = method(dataType, "T2", boolean.class); packageName = method(dataType, "M2", String.class);
        pureVibration = method(factoryType, "j", patternType, String.class, long[].class, idType);
        if (!Modifier.isPrivate(pureVibration.getModifiers())) throw new NoSuchMethodException();
        // In the verified ringtone consumer b()==true means it must not play another sound.
        // This no-op sentinel is private to that consumer; it is never returned to the vibrator.
        blockedRingtone = Proxy.newProxyInstance(classes, new Class<?>[]{patternType}, (proxy, called, args) -> {
            if (called.getDeclaringClass() == Object.class) return objectMethod(proxy, called, args);
            if (called.getName().equals("b")) return true;
            if (called.getName().equals("c")) return false;
            return null;
        });
    }

    void install(Application application) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main_thread_required");
        Method componentMethod = method(application.getClass(), "l", Object.class);
        Object component = call(componentMethod, application);
        if (component == null || !AlertProfile.COMPONENT.equals(component.getClass().getName())) throw new NoSuchFieldException();
        Object vibrator = provider(component, "C3", "o6.x");
        Object ringtone = provider(component, "D3", "o6.w");
        Field vibrationField = uniqueField(vibrator.getClass(), loaderType), ringtoneField = uniqueField(ringtone.getClass(), loaderType);
        Object originalVibration = vibrationField.get(vibrator), originalRingtone = ringtoneField.get(ringtone);
        if (originalVibration != originalRingtone || originalVibration == null || originalVibration.getClass() != factoryType) throw new NoSuchFieldException();
        Object vibrationProxy = wrap(originalVibration, false), ringtoneProxy = wrap(originalRingtone, true);
        try {
            vibrationField.set(vibrator, vibrationProxy); ringtoneField.set(ringtone, ringtoneProxy);
            if (vibrationField.get(vibrator) != vibrationProxy || ringtoneField.get(ringtone) != ringtoneProxy) throw new IllegalAccessException();
            // Until both writes/readbacks succeed every wrapper delegates to the original loader.
            policy.installed();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            policy.failed();
            if (vibrationField.get(vibrator) == vibrationProxy) vibrationField.set(vibrator, originalVibration);
            if (ringtoneField.get(ringtone) == ringtoneProxy) ringtoneField.set(ringtone, originalRingtone);
            throw failure;
        }
    }

    private Object wrap(Object original, boolean ringtone) {
        InvocationHandler handler = (proxy, called, args) -> {
            if (called.getDeclaringClass() == Object.class) return objectMethod(proxy, called, args);
            if (!called.equals(load) || !policy.active() || policy.mode() == AlertPolicy.Mode.OFF) return delegate(called, original, args);
            try {
                if (args == null || args.length != 2 || !infoType.isInstance(args[0]) || !(args[1] instanceof Boolean)) throw new IllegalArgumentException();
                Object selectedItem = call(item, args[0]), selectedData = call(data, selectedItem);
                if (!(Boolean) call(local, selectedData) && !(Boolean) call(mcu, selectedData) && !(Boolean) call(ancs, selectedData)) {
                    String source = (String) call(packageName, selectedData);
                    boolean hasSound = (Boolean) call(sound, selectedData), shouldVibrate = (Boolean) call(vibrate, selectedData);
                    boolean plugged = (Boolean) args[1], patch = policy.patch(source);
                    policy.observe(source, hasSound, shouldVibrate, call(pattern, args[0]) != null, plugged, ringtone, patch);
                    if (patch) {
                        boolean allowSound = policy.soundAllowed(hasSound);
                        int intensity = policy.vibrationIntensity();
                        boolean allowVibration = shouldVibrate && intensity > 0;
                        if (ringtone) {
                            if (!allowSound) return blockedRingtone;
                            // A sound-bearing haptic which we did not execute must not suppress the tone.
                            if (!allowVibration || plugged) return null;
                        } else {
                            if (!allowVibration || plugged) return null;
                            if (!allowSound) {
                                long[] requested = (long[]) call(pattern, args[0]);
                                return call(pureVibration, original, source, vibration(requested, intensity), call(identity, selectedItem));
                            }
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // A changed contract stops the extension instead of throwing into SystemUI.
                policy.failed();
            }
            // Keep the OEM call outside the extension's catch: its own failure must not
            // be mistaken for a wrapper failure and cause the same effect to run twice.
            return delegate(called, original, args);
        };
        return Proxy.newProxyInstance(loaderType.getClassLoader(), new Class<?>[]{loaderType}, handler);
    }

    static long[] vibration(long[] requested, int intensity) {
        if (requested != null && requested.length >= 2 && requested.length <= 128) {
            long total = 0;
            boolean valid = true;
            for (long length : requested) { if (length < 0 || length > 30_000 || (total += length) > 30_000) { valid = false; break; } }
            // All-zero patterns explicitly request no motor time. Do not invent vibration.
            if (valid) return requested.clone();
        }
        return intensity == 3 ? new long[]{0, 200, 150, 500} : new long[]{0, intensity == 2 ? 350 : 250};
    }
    private static Object provider(Object component, String name, String type) throws ReflectiveOperationException {
        Field field = component.getClass().getDeclaredField(name); field.setAccessible(true);
        if (!field.getType().getName().equals("hd.a")) throw new NoSuchFieldException();
        Object provider = field.get(component);
        Object value = call(method(provider.getClass(), "get", Object.class), provider);
        if (value == null || !value.getClass().getName().equals(type)) throw new NoSuchFieldException();
        return value;
    }
    private static Field uniqueField(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        Field result = null;
        for (Field field : owner.getDeclaredFields()) if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
            if (result != null) throw new NoSuchFieldException();
            field.setAccessible(true); result = field;
        }
        if (result == null) throw new NoSuchFieldException();
        return result;
    }
    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... arguments) throws NoSuchMethodException {
        Method found;
        try { found = owner.getMethod(name, arguments); }
        catch (NoSuchMethodException notPublic) { found = owner.getDeclaredMethod(name, arguments); }
        if (found.getReturnType() != result || !Arrays.equals(found.getParameterTypes(), arguments)) throw new NoSuchMethodException();
        found.setAccessible(true); return found;
    }
    private static Object call(Method method, Object receiver, Object... arguments) throws ReflectiveOperationException {
        return method.invoke(receiver, arguments);
    }
    private static Object delegate(Method method, Object receiver, Object[] arguments) throws Throwable {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException original) { throw original.getCause(); }
    }
    private static Object objectMethod(Object receiver, Method method, Object[] arguments) {
        switch (method.getName()) {
            case "hashCode": return System.identityHashCode(receiver);
            case "equals": return receiver == arguments[0];
            default: return "TicBoxAlertPolicy";
        }
    }
}
