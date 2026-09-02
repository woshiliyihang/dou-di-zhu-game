package com.example.vtrans.util;

/** 滚动窗口的延迟统计，用于通知栏展示与 PerfGuard 的降级判断。 */
public final class Stats {
    private final int window;
    private final long[] samples;
    private int idx;
    private int count;
    private long sum;

    public Stats(int window) {
        this.window = window;
        this.samples = new long[window];
    }

    public synchronized void add(long valueMs) {
        if (count < window) {
            count++;
        } else {
            sum -= samples[idx];
        }
        samples[idx] = valueMs;
        sum += valueMs;
        idx = (idx + 1) % window;
    }

    public synchronized long avgMs() {
        return count == 0 ? 0 : sum / count;
    }

    public synchronized int count() {
        return count;
    }

    public synchronized void reset() {
        idx = 0;
        count = 0;
        sum = 0;
    }
}
