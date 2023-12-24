package org.stianloader.smatterdi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class SimpleInjectionContextImpl implements InjectionContext {

    private static final class InstanceValue<T> {
        @NotNull
        private final Lazy<T> value = new Lazy<>(() -> {
            return this.supplier.get();
        });
        @NotNull
        private Supplier<T> supplier;

        public InstanceValue(@NotNull Supplier<T> supplier) {
            this.supplier = supplier;
        }

        @Contract(mutates = "this", pure = false, value = "-> this")
        public InstanceValue<T> set() {
            this.value.get();
            return this;
        }
    }

    @NotNull
    private final Map<Class<?>, InstanceValue<Object>> instances;

    public SimpleInjectionContextImpl() {
        this.instances = new ConcurrentHashMap<>();
    }

    public SimpleInjectionContextImpl(@NotNull SimpleInjectionContextImpl impl) {
        this.instances = new ConcurrentHashMap<>(impl.instances);
    }

    @Override
    public <T> T getInstance(Class<T> type) {
        @SuppressWarnings("unchecked")
        InstanceValue<T> instance = (InstanceValue<T>) this.instances.get(type);
        if (instance == null) {
            throw new IllegalArgumentException("No implementation of template type '" + type + "'!");
        }
        return instance.value.get();
    }

    public <T> void setImplementation(@NotNull Class<T> type, @NotNull T value) {
        this.instances.compute(type, (ignore, instanceValue) -> {
            if (instanceValue != null && !instanceValue.value.isDone()) {
                instanceValue.supplier = () -> value;
                if (!instanceValue.value.isDone()) {
                    return instanceValue;
                }
            }
            return new InstanceValue<>(() -> value);
        });
    }

    @SuppressWarnings({ "unchecked" })
    public <T> void setProvider(@NotNull Class<T> type, @NotNull Supplier<T> value) {
        this.instances.compute(type, (ignore, instanceValue) -> {
            if (instanceValue != null && !instanceValue.value.isDone()) {
                instanceValue.supplier = (Supplier<Object>) value;
                if (!instanceValue.value.isDone()) {
                    return instanceValue;
                }
            }
            return new InstanceValue<>((Supplier<Object>) value);
        });
    }

    public void removeImplementation(@NotNull Class<?> type) {
        this.instances.remove(type);
    }

    @Override
    public <T> void autowire(Class<T> type, T instance) {
        this.instances.putIfAbsent(type, new InstanceValue<Object>(() -> instance).set());
    }
}
