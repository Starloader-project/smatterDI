package org.stianloader.smatterdi;

import org.jetbrains.annotations.NotNull;

public interface ObjectAllocator {
    @NotNull
    <T> T allocate(@NotNull Class<T> type, @NotNull InjectionContext injectCtx, Object... args);
}
