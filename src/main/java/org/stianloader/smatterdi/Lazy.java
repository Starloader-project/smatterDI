package org.stianloader.smatterdi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// This class is thread safe. At least we believe that
public class Lazy<T> {

    @NotNull
    private final Supplier<T> lazySupplier;
    @NotNull
    private final List<Consumer<T>> lazyConfigurations;
    @NotNull
    private volatile Optional<T> value;
    @Nullable
    private volatile Throwable computeEx = null;

    public Lazy(@NotNull Supplier<T> supplier) {
        this.lazySupplier = supplier;
        this.lazyConfigurations = new ArrayList<>();
        this.value = Optional.empty();
    }

    public Lazy(@NotNull T value) {
        this.value = Optional.of(value);
        this.lazySupplier = () -> {
            throw new UnsupportedOperationException();
        };
        this.lazyConfigurations = Collections.emptyList();
    }

    /**
     * Obtains the value of the lazy.
     *
     * <p>If the value hasn't yet been computed, the value will get computed and all registered configurators
     * are called on the computed value.
     *
     * <p>A lazy will not be computed twice if the supplier or a configurator throws a {@link Throwable}.
     * Instead, the exception will be rethrown in a packaged form.
     *
     * @return The value of the lazy
     */
    @NotNull
    @Contract(pure = false)
    public T get() {
        if (this.value.isEmpty()) {
            Throwable t = this.computeEx;
            if (t != null) {
                throw new IllegalStateException("Previous attempt at initializing the value failed.", t);
            }

            synchronized (this) {
                if (this.value.isPresent()) {
                    T val = this.value.get();
                    assert val != null; // Eclipse doesn't seem to comprehend my EEAs, oh well.
                    return val;
                }

                if ((t = this.computeEx) != null) {
                    throw new IllegalStateException("Previous attempt at initializing the value failed. Warning: This exception is possibly caused by a race condition!", t);
                }

                try {
                    T val = this.lazySupplier.get();
                    val = Objects.requireNonNull(val, "'val' may not be null!");
                    for (Consumer<T> consumer : this.lazyConfigurations) {
                        consumer.accept(val);
                    }
                    this.value = Optional.of(val);
                } catch (Throwable t2) {
                    this.computeEx = t2;
                    if (t2 instanceof Error && !(t2 instanceof AssertionError)) {
                        throw t2;
                    }
                    throw new IllegalStateException("Attempt at initializing the value failed.", t2);
                } finally {
                    this.lazyConfigurations.clear();
                }
            }
        }

        T val = this.value.get();
        assert val != null; // Eclipse doesn't seem to comprehend my EEAs, oh well.
        return val;
    }

    /**
     * If the value of the lazy object was already calculated, this method returns a non-empty
     * options. Otherwise (as {@link #get()} was not called yet), it will return an empty optional.
     *
     * @return The current value of the optional
     */
    @NotNull
    @Contract(pure = true)
    public Optional<T> getIfPresent() {
        return this.value;
    }

    @NotNull
    @Contract(mutates = "this", pure = false, value = "null -> fail; !null -> this")
    public Lazy<T> configure(@NotNull Consumer<T> action) {
        if (this.value.isPresent()) {
            action.accept(this.value.get());
        } else {
            synchronized (this) {
                if (this.value.isPresent()) {
                    // Configuration completed while waiting for the lock
                    action.accept(this.value.get());
                    return this;
                }
                this.lazyConfigurations.add(Objects.requireNonNull(action));
            }
        }
        return this;
    }

    public boolean isDone() {
        return this.value.isPresent() || this.computeEx != null;
    }
}
