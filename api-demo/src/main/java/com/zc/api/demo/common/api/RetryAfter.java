package com.zc.api.demo.common.api;

/**
 * 限流/频控类错误需要回传的补充数据。
 *
 * <p><b>为什么要把 retryAfter 返回给前端：</b>
 * 只告诉用户「操作过于频繁」，用户不知道该等多久，就会一直点；
 * 带上剩余秒数，前端可以直接做倒计时，点击自然收敛。
 * 这是「错误语义」这一器官最容易被忽略、但直接影响前端体验的部分。
 */
public class RetryAfter {

    /** 建议等待秒数 */
    private long retryAfter;

    public RetryAfter() {
    }

    public RetryAfter(long retryAfter) {
        this.retryAfter = retryAfter;
    }

    public static RetryAfter of(long seconds) {
        return new RetryAfter(seconds);
    }

    public long getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(long retryAfter) {
        this.retryAfter = retryAfter;
    }
}
