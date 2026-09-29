package com.zc.api.demo.coupon.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zc.api.demo.coupon.entity.Activity;
import com.zc.api.demo.coupon.mapper.ActivityMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ActivityCache 两级缓存的行为契约测试（Caffeine 作为 L1）。
 *
 * <p>要守住的三条：
 * <ol>
 *   <li><b>L1 必须真的挡住回源</b>：第二次读同一个活动不能再打 Redis/DB，
 *       否则「本地缓存」只是个装饰。</li>
 *   <li><b>evict 必须能穿透两级</b>：运营停活动靠的就是它，失效不彻底 = 事故。</li>
 *   <li><b>不能被 null 污染</b>：活动不存在时不能把「空」缓存下来 ——
 *       否则运营刚建好的活动会被自己的缓存判成「不存在」。</li>
 * </ol>
 *
 * <p><b>JUnit 4 差异点：</b>
 * <ul>
 *   <li>{@code @BeforeEach} -> {@code @Before}。注意 Mockito 的 runner 会把
 *       {@code @Mock} 注入放在 {@code @Before} <b>之前</b>，所以在 setUp 里
 *       对 mock 打桩（{@code when(...)}）是安全的。</li>
 *   <li>失败消息在参数<b>第一位</b>：{@code assertTrue("msg", cond)} /
 *       {@code assertNotNull("msg", obj)}（JUnit 5 是反过来的）。</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class ActivityCacheTest {

    private static final String CODE = "SPRING_2026";

    @Mock
    private ActivityMapper activityMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ActivityCache activityCache;

    @Before
    public void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 本地 TTL 给足，避免用例跑到一半本地缓存自然过期导致偶发失败
        activityCache = new ActivityCache(activityMapper, redisTemplate,
                new ObjectMapper(), 60_000L, 100L, 60L);
    }

    private Activity runningActivity() {
        Activity activity = new Activity();
        activity.setId(1L);
        activity.setActivityCode(CODE);
        activity.setName("春节领券活动");
        activity.setStatus(Activity.STATUS_RUNNING);
        activity.setStartTime(new Date(System.currentTimeMillis() - 3600_000L));
        activity.setEndTime(new Date(System.currentTimeMillis() + 3600_000L));
        activity.setTotalStock(100);
        return activity;
    }

    /** 首次读回源（Redis 未命中 -> DB），并写回缓存 */
    @Test
    public void shouldLoadFromDbOnFirstRead() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(activityMapper.selectByCode(CODE)).thenReturn(runningActivity());

        Activity first = activityCache.getByCode(CODE);
        Activity second = activityCache.getByCode(CODE);

        Assert.assertNotNull(first);
        // 第二次是同一个对象引用 —— 说明走的是本地缓存，没有反序列化重建
        Assert.assertSame(first, second);
        verify(activityMapper, times(1)).selectByCode(CODE);
        // Redis 也只读了一次：L1 真的挡住了
        verify(valueOperations, times(1)).get(anyString());
        Assert.assertTrue("本地缓存应有命中记录", activityCache.localHitRate() > 0);
    }

    /** 活动不存在：不缓存 null，下次仍会回源（否则新建的活动永远读不到） */
    @Test
    public void shouldNotCacheNull() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(activityMapper.selectByCode(CODE)).thenReturn(null);

        Assert.assertNull(activityCache.getByCode(CODE));
        Assert.assertNull(activityCache.getByCode(CODE));
        verify(activityMapper, times(2)).selectByCode(CODE);
    }

    /** evict 后必须重新回源（L1 与 Redis 一起失效） */
    @Test
    public void shouldReloadAfterEvict() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(activityMapper.selectByCode(CODE)).thenReturn(runningActivity());

        activityCache.getByCode(CODE);
        activityCache.evict(CODE);
        activityCache.getByCode(CODE);

        verify(activityMapper, times(2)).selectByCode(CODE);
        // 本地被 invalidate 后，Redis 也要一并删掉
        verify(redisTemplate).delete(anyString());
    }

    /** Redis 里的脏 JSON 不能让接口挂掉：要回源 DB 并覆盖缓存 */
    @Test
    public void shouldFallbackToDbWhenRedisJsonBroken() {
        when(valueOperations.get(anyString())).thenReturn("{not-a-valid-json");
        when(activityMapper.selectByCode(CODE)).thenReturn(runningActivity());

        Activity activity = activityCache.getByCode(CODE);

        Assert.assertNotNull("反序列化失败必须降级到 DB，而不是抛异常给调用方", activity);
        verify(activityMapper).selectByCode(CODE);
        // 脏缓存要被主动清掉，避免每次请求都白跑一次反序列化
        verify(redisTemplate).delete(anyString());
    }
}
