package com.zc.api.demo.coupon.mapper;

import com.zc.api.demo.coupon.entity.Activity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 活动 Mapper。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>查询结果列不要用 {@code SELECT *}。表加字段后实体没跟着改，
 *       就会出现「莫名其妙多了一个字段」的隐蔽 bug，而且 SELECT * 无法走覆盖索引。</li>
 *   <li>这里返回的是 @Select 注解 SQL，本工程为了「代码即文档」把 SQL 和注释放一起。
 *       复杂动态 SQL 还是放 XML 更可维护，两者混用完全没问题。</li>
 * </ol>
 */
public interface ActivityMapper {

    @Select("SELECT id, activity_code, name, status, start_time, end_time, total_stock "
            + "FROM t_activity WHERE activity_code = #{activityCode}")
    Activity selectByCode(@Param("activityCode") String activityCode);

    @Select("SELECT id, activity_code, name, status, start_time, end_time, total_stock "
            + "FROM t_activity WHERE id = #{id}")
    Activity selectById(@Param("id") Long id);

    /**
     * 查出「进行中」的活动，供库存预热任务使用。
     *
     * <p>注意点：只查 status 是惰性的（时间窗口交给 Java 判断，见 {@code Activity#isRunning}），
     * 因为「进行中」的定义包含时间条件，而时间条件用 SQL 写会让索引失效 + 依赖 DB 时区。
     * 活动表是小表，全查也没问题。
     */
    @Select("SELECT id, activity_code, name, status, start_time, end_time, total_stock "
            + "FROM t_activity WHERE status = 1")
    java.util.List<Activity> selectRunningActivities();
}
