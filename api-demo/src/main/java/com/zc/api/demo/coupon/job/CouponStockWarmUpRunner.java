package com.zc.api.demo.coupon.job;

import com.zc.api.demo.coupon.entity.Activity;
import com.zc.api.demo.coupon.mapper.ActivityMapper;
import com.zc.api.demo.coupon.mapper.CouponMapper;
import com.zc.api.demo.coupon.service.CouponClaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * 活动库存预热：应用启动时把「进行中」活动的剩余库存写入 Redis。
 *
 * <p><b>为什么必须有这一步：</b>{@code coupon_claim.lua} 里库存 key 不存在时按 0 处理
 * （宁可不发也不能超发）。所以没预热 = 活动一开就是「已抢光」。
 * 这不是 bug，是「安全默认值」的代价，预热任务就是补上这个前提。
 *
 * <p><b>★ 库存重建公式（这里最容易出事故）：</b>
 * <pre>
 * 剩余库存 = 活动总库存 - DB 已发券数量
 * </pre>
 * 绝<b>不能</b>直接把 {@code totalStock} 写回去 —— 那等于把已经发出去的券又变回库存，
 * 直接超发。所以预热用 {@code SETNX} + 「总量 - 已发」，两个动作都为了保护这一点。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>{@code SETNX} 保证重启不会把已经扣减过的库存重置（见 {@code CouponClaimService#warmUpStock}）。</li>
 *   <li>预热要在活动<b>开始之前</b>跑完。生产环境建议用运营后台的「活动发布」动作显式触发，
 *       而不是靠应用启动时顺手做（多副本会重复跑，虽然 SETNX 安全，但逻辑上不清晰）。</li>
 *   <li>这里只做「启动时兜底」，线上还应该有定时巡检：定时比对 Redis 剩余量与
 *       「总量 - DB 已发」，偏差超过阈值就告警（Redis 被清空、被误删时能第一时间发现）。</li>
 * </ol>
 */
@Component
public class CouponStockWarmUpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CouponStockWarmUpRunner.class);

    private final ActivityMapper activityMapper;
    private final CouponMapper couponMapper;
    private final CouponClaimService couponClaimService;

    /** 预热开关：测试环境/排查时可以通过配置关掉 */
    @Value("${demo.coupon.warm-up-on-startup:true}")
    private boolean warmUpOnStartup;

    /** 库存 key 的存活时间（小时），必须覆盖「活动结束 + 余量」 */
    @Value("${demo.coupon.stock-key-ttl-hours:72}")
    private int stockKeyTtlHours;

    public CouponStockWarmUpRunner(ActivityMapper activityMapper,
                                   CouponMapper couponMapper,
                                   CouponClaimService couponClaimService) {
        this.activityMapper = activityMapper;
        this.couponMapper = couponMapper;
        this.couponClaimService = couponClaimService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!warmUpOnStartup) {
            return;
        }
        try {
            List<Activity> activities = activityMapper.selectRunningActivities();
            Date now = new Date();
            for (Activity activity : activities) {
                if (!activity.isRunning(now)) {
                    continue;
                }
                int totalStock = activity.getTotalStock() == null ? 0 : activity.getTotalStock();
                int claimed = couponMapper.countByActivityId(activity.getId());
                int leftStock = totalStock - claimed;
                if (leftStock < 0) {
                    // 已发 > 总量，说明历史上超发过或数据被改过：不要静默修正，要告警
                    log.error("coupon stock inconsistent! activityId={}, totalStock={}, claimed={}",
                            activity.getId(), totalStock, claimed);
                    leftStock = 0;
                }
                couponClaimService.warmUpStock(activity.getId(), leftStock, ttl(activity, now));
            }
        } catch (Exception e) {
            // 预热失败不应该阻断应用启动：活动开局前人工确认 + 巡检任务会兜住
            log.error("coupon stock warm up fail, manual check needed", e);
        }
    }

    /**
     * key 的 TTL：以「活动结束时间 + 余量小时」为准。
     *
     * <p>注意点：<b>活动还在进行中 key 就自己过期了 = 全场瞬间「已抢光」</b>。
     * 所以余量要够大（默认 72 小时），并且要有巡检任务在 key 缺失时告警/重建。
     * 这里再兜一个最小 1 小时，避免「活动已结束但还在发券」的边界情况下 TTL 为负。
     */
    private Duration ttl(Activity activity, Date now) {
        long endMillis = activity.getEndTime() == null
                ? now.getTime() + Duration.ofHours(stockKeyTtlHours).toMillis()
                : activity.getEndTime().getTime() + Duration.ofHours(stockKeyTtlHours).toMillis();
        long millis = Math.max(endMillis - now.getTime(), Duration.ofHours(1).toMillis());
        return Duration.ofMillis(millis);
    }
}
