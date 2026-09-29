package org.wispyr.messenger;

public interface GenericProvider<F, T> {
    T provide(F obj);
}
