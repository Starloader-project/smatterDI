package org.stianloader.smatterdi;

public interface InjectionContext {
    <T> T getInstance(Class<T> type);
    <T> void autowire(Class<T> type, T instance);
}
