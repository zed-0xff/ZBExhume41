package me.zed_0xff.zb_exhume_41;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Logger;

import zombie.scripting.objects.Registries;
import zombie.scripting.objects.Registry;

// expose registerBase() of all registries, so objects could be registered in the "base" namespace again.
// Main.main is called at usual mod load time, for premain-time class and method should be PreMain.premain
public class Main {
    public static void main(String[] args) {
        try {
            for (Field f : Registries.class.getDeclaredFields()) {
                if (Registry.class.isAssignableFrom(f.getType())) {
                    Type g = f.getGenericType();
                    if (g instanceof ParameterizedType pt) {
                        Type arg = pt.getActualTypeArguments()[0];
                        if (arg instanceof Class<?> cls) {
                            Exposer.exposeMethod(cls, "registerBase");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logger.error("ZBExhume41: to expose registerBase():", t);
        }
    }
}
