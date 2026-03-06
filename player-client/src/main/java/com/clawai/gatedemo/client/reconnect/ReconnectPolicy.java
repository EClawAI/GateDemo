package com.clawai.gatedemo.client.reconnect;

public interface ReconnectPolicy {

    long getNextDelay();

    void reset();

    boolean shouldRetry();

    int getRetryCount();

    void onRetry();
}
