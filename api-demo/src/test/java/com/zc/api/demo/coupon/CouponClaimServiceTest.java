package com.zc.api.demo.coupon;

import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.api.ErrorCode;
import com.zc.api.demo.common.limiter.RedisRateLimiter;
import com.zc.api.demo.coupon.cache.ActivityCache;
import com.zc.api.demo.coupon.dto.ClaimCouponReq;
import com.zc.api.demo.coupon.dto.ClaimCouponResp;
import com.zc.api.demo.coupon.entity.Activity;
import com.zc.api.demo.coupon.entity.Coupon;
import com.zc.api.demo.coupon.mapper.CouponMapper;
import com.zc.api.demo.coupon.service.CouponClaimService;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 场景1 的关键分支单测。
 *
 * <p><b>只测「幂等 / 并发 / 状态机」这几条容易写错的分支，不测 CRUD。</b>
 * 这些分支的共同点是：正常流程走不到，只有并发或重复请求时才触发 ——
 * 而它们恰恰是造成资金/库存事故的地方。
 *
 * <p><b>JUnit 4 写法注意点：</b>
 * <ul>
 *   <li>{@link MockitoJUnitRunner} 默认是 Strict 模式，语义与 JUnit 5 的
 *       {@code MockitoExtension}（STRICT_STUBS）一致：<b>stub 了却没被调用会直接失败</b>。
 *       这是好事 —— 它能抓出「用例改了、stub 忘了删」的过期测试，别为了图省事换成 Silent。</li>
 *   <li>{@code @DisplayName} 是 JUnit 5 的东西，JUnit 4 没有。这里改用 javadoc 写明意图，
 *       报错时靠「有意义的方法名 + 注释」定位，而不是靠一行描述文字。</li>
 *   <li>JUnit 4 的测试类与方法都必须是 {@code public}，否则报
 *       {@code Method xxx() should be public}（这是个很常见的迁移踩坑点）。</li>
 *   <li>{@code Assert.assertThrows} 需要 JUnit 4.13+（本项目 4.13.2）。
 *       早期版本只能用 {@code @Rule ExpectedException}，那种写法拿不到异常对象做后续断言。</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class CouponClaimServiceTest {

    @Mock
    private ActivityCache activityCache;
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private DefaultRedisScript<Long> couponClaimScript;
    @Mock
    private RedisRateLimiter rateLimiter;

    @InjectMocks
    private CouponClaimService couponClaimService;

    private void prepareConfig() {
        ReflectionTestUtils.setField(couponClaimService, "userMarkTtlSeconds", 604800L);
        ReflectionTestUtils.setField(couponClaimService, "userLimitPerSecond", 1);
        ReflectionTestUtils.setField(couponClaimService, "ipLimitPerMinute", 10);
    }

    private Activity runningActivity() {
        Activity activity = new Activity();
        activity.setId(1L);
        activity.setActivityCode("SPRING_2026");
        activity.setStatus(Activity.STATUS_RUNNING);
        activity.setStartTime(new Date(System.currentTimeMillis() - 3600_000L));
        activity.setEndTime(new Date(System.currentTimeMillis() + 3600_000L));
        activity.setTotalStock(100);
        return activity;
    }

    /** 已领过 -> 返回上次结果（成功），而不是报错 */
    @Test
    public void shouldReturnExistingCouponWhenAlreadyClaimed() {
        prepareConfig();
        ClaimCouponReq req = new ClaimCouponReq();
        req.setUserId(1001L);
        when(activityCache.getByCode("SPRING_2026")).thenReturn(runningActivity());
        Coupon existing = new Coupon();
        existing.setCouponNo("C-001");
        existing.setStatus(Coupon.STATUS_UNUSED);
        when(couponMapper.selectByUserAndActivity(1001L, 1L)).thenReturn(existing);

        ClaimCouponResp resp = couponClaimService.claim("SPRING_2026", req, "127.0.0.1");

        // 幂等的正确语义：返回上次结果，newlyClaimed=false
        Assert.assertEquals("C-001", resp.getCouponNo());
        Assert.assertFalse(resp.getNewlyClaimed());
        // 关键断言：绝不能走到 Redis 预扣和 insert（否则会白扣库存）
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), any(), any());
        verify(couponMapper, never()).insert(any(Coupon.class));
    }

    /** 活动未开始/已结束 -> 业务失败（不要返回 5xx） */
    @Test
    public void shouldRejectWhenActivityNotRunning() {
        prepareConfig();
        ClaimCouponReq req = new ClaimCouponReq();
        req.setUserId(1001L);
        Activity activity = runningActivity();
        activity.setStatus(Activity.STATUS_ENDED);
        when(activityCache.getByCode("SPRING_2026")).thenReturn(activity);

        BizException e = Assert.assertThrows(BizException.class,
                () -> couponClaimService.claim("SPRING_2026", req, "127.0.0.1"));

        Assert.assertEquals(ErrorCode.COUPON_NOT_RUNNING, e.getErrorCode());
        verify(couponMapper, never()).insert(any(Coupon.class));
    }

    /** Lua 返回 -2（库存不足）-> 已抢光，业务失败 */
    @Test
    public void shouldRejectWhenSoldOut() {
        prepareConfig();
        ClaimCouponReq req = new ClaimCouponReq();
        req.setUserId(1001L);
        when(activityCache.getByCode("SPRING_2026")).thenReturn(runningActivity());
        when(couponMapper.selectByUserAndActivity(1001L, 1L)).thenReturn(null);
        when(rateLimiter.tryAcquire(anyString(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(0L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(), any())).thenReturn(-2L);

        BizException e = Assert.assertThrows(BizException.class,
                () -> couponClaimService.claim("SPRING_2026", req, "127.0.0.1"));

        Assert.assertEquals(ErrorCode.COUPON_SOLD_OUT, e.getErrorCode());
        verify(couponMapper, never()).insert(any(Coupon.class));
    }

    /** 并发穿透：insert 撞唯一索引 -> 回补库存 + 返回已领取（不能超发） */
    @Test
    public void shouldRollbackStockWhenDuplicateKey() {
        prepareConfig();
        ClaimCouponReq req = new ClaimCouponReq();
        req.setUserId(1001L);
        when(activityCache.getByCode("SPRING_2026")).thenReturn(runningActivity());
        when(rateLimiter.tryAcquire(anyString(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(0L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(), any())).thenReturn(99L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 第一次查不到（并发那笔还没提交），第二次查到（重试后拿到）
        Coupon existing = new Coupon();
        existing.setCouponNo("C-002");
        existing.setStatus(Coupon.STATUS_UNUSED);
        when(couponMapper.selectByUserAndActivity(1001L, 1L)).thenReturn(null, existing);
        when(couponMapper.insert(any(Coupon.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_user_activity"));

        ClaimCouponResp resp = couponClaimService.claim("SPRING_2026", req, "127.0.0.1");

        Assert.assertEquals("C-002", resp.getCouponNo());
        Assert.assertFalse(resp.getNewlyClaimed());
        // ★ 关键断言：预扣过的库存必须回补，否则并发穿越会漏库存（超发）
        verify(valueOperations, times(1)).increment(anyString());
    }

    /** 限流命中 -> 业务失败并带 retryAfter（前端可做倒计时） */
    @Test
    public void shouldRejectWhenRateLimited() {
        prepareConfig();
        ClaimCouponReq req = new ClaimCouponReq();
        req.setUserId(1001L);
        when(rateLimiter.tryAcquire(anyString(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(7L);

        BizException e = Assert.assertThrows(BizException.class,
                () -> couponClaimService.claim("SPRING_2026", req, "127.0.0.1"));

        Assert.assertEquals(ErrorCode.REQUEST_TOO_FREQUENT, e.getErrorCode());
        // 错误语义的一部分：retryAfter 必须回给前端
        Assert.assertNotNull(e.getData());
        // 限流在最前面：不该碰活动缓存与 DB
        verify(activityCache, never()).getByCode(anyString());
        verify(couponMapper, never()).insert(any(Coupon.class));
        verify(couponMapper, never()).selectByUserAndActivity(anyLong(), anyLong());
    }
}
