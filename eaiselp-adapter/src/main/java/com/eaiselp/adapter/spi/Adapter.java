package com.eaiselp.adapter.spi;

public interface Adapter {
    String getType();
    String getProvider();
    boolean isAvailable();
}
