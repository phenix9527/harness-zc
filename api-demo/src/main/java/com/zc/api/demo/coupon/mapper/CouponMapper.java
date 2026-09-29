package com.zc.api.demo.coupon.mapper;

import com.zc.api.demo.coupon.entity.Coupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 优惠券 Mapper。
 *
 * <p><b>本场景最核心的两个方法：</b>
 * {@link #selectByUserAndActivity} 用于「幂等命中直接返回上次结果」，
 * {@link #insert} 用于最终落库 —— 它的唯一索引 {@code uk_user_activity} 是超发的最后一道防线。
 */
public interface CouponMapper {

    /**
     * 按「用户 + 活动」查券 —— 幂等探针。
     *
     * <p>注意：这是<b>快路径</b>，只用于挡掉绝大多数重复请求（用户连点两次）。
     * 它<b>不能</b>当作并发正确性保证：两个请求可能同时查到 null。
     * 正确性还是靠 insert 的 DuplicateKeyException。
     */
    @Select("SELECT id, coupon_no, user_id, activity_id, status, create_time, update_time "
            + "FROM t_coupon WHERE user_id = #{userId} AND activity_id = #{activityId} LIMIT 1")
    Coupon selectByUserAndActivity(@Param("userId") Long userId, @Param("activityId") Long activityId);

    @Select("SELECT id, coupon_no, user_id, activity_id, status, create_time, update_time "
            + "FROM t_coupon WHERE coupon_no = #{couponNo}")
    Coupon selectByCouponNo(@Param("couponNo") String couponNo);

    /**
     * 发券落库。
     *
     * <p><b>注意点：</b>
     * <ol>
     *   <li>这里<b>必须</b>让 {@code uk_user_activity} 唯一索引存在，撞了会抛
     *       {@code DuplicateKeyException}（MyBatis-Spring 会把
     *       SQLIntegrityConstraintViolationException 翻译成它），业务侧捕获后
     *       按「幂等命中」处理 —— 这样「并发 + 重复提交」两个问题一次解决。</li>
     *   <li>不要在 insert 前先 select 判重然后「没有才插」：这是典型的
     *       check-then-act，并发下必然出现双写。唯一索引不是可选项。</li>
     *   <li>如果用 {@code INSERT IGNORE} 或 {@code ON DUPLICATE KEY UPDATE}，
     *       要能区分「插入成功」和「被忽略」，否则无法判断是不是自己真的发出了券，
     *       库存就白扣了。所以本示例选择「插 + 捕获异常」，语义最明确。</li>
     * </ol>
     */
    @Insert("INSERT INTO t_coupon (coupon_no, user_id, activity_id, status) "
            + "VALUES (#{couponNo}, #{userId}, #{activityId}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Coupon coupon);

    /**
     * 统计某活动已发出的券数量。
     *
     * <p>用途：Redis 库存重建 —— 正确的重建公式是
     * {@code 剩余库存 = 活动总库存 - DB 已发数量}，<b>而不是直接把总库存写回去</b>。
     * 直接写总量 = 把已经发出去的券又变回库存 = 超发，这是事故级的错误。
     */
    @Select("SELECT COUNT(1) FROM t_coupon WHERE activity_id = #{activityId}")
    int countByActivityId(@Param("activityId") Long activityId);
}
