package com.alimtalk.usage;

@FunctionalInterface
public interface UsageRecorder {

    void record(UsageRecord record);
}
