package com.zc.api.demo.order.mapper;

import com.zc.api.demo.order.constant.OrderStatus;
import com.zc.api.demo.order.entity.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * 订单 Mapper —— 本工程「性价比最高的写法」全在这里。
 *
 * <p><b>★ 核心思想：{@code UPDATE ... WHERE status = 期望值} 这一行 CAS，
 * 同时解决「幂等」和「状态机守卫」。</b>
 *
 * <p>以支付回调为例：
 * <pre>
 * UPDATE txm_order SET status = 2, pay_time = NOW()
 * WHERE id = #{id} AND status = 1        -- ← 精髓在这一行
 * </pre>
 * 靠 {@code affected rows} 判断「这次操作是不是有效的那一次」：
 * <ul>
 *   <li>rows = 1：我是第一个把它从「待支付」改成「已支付」的人 → 继续执行后续业务</li>
 *   <li>rows = 0：要么重复通知（已经是已支付），要么状态不对（已被关闭/已完成）→ 静默返回</li>
 * </ul>
 * 不需要分布式锁、不需要额外的幂等表，天然并发安全、天然幂等。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>{@code affected rows == 0} <b>不能</b>直接报「订单不存在」。必须回查一次订单，
 *       区分「不存在」「已处理过」「状态非法」，否则排查问题时全是误导信息。</li>
 *   <li>MySQL 有个坑：如果 UPDATE 的<b>值与原值相同</b>，默认返回的 affected rows 是 0
 *       （因为没实际变更）。所以 CAS 里的 to 状态必须和 from 不同 —— 幂等场景本来如此，
 *       但别写出 <code>SET status = 1 WHERE status = 1</code> 这种自杀式 SQL。
 *       连接参数 {@code useAffectedRows=true} 会让它返回「匹配行数」，行为因环境而异，
 *       不要依赖这个配置差异。</li>
 *   <li>时间统一用 DB 的 {@code NOW()}，不要用应用服务器时间：多副本时钟漂移会让
 *       「支付时间早于创建时间」这种脏数据永久留在库里，对账时非常难解释。</li>
 *   <li>CAS 语句必须走主键或唯一索引，否则会升级成范围锁，高并发下直接死锁。
 *       本表的所有 CAS 都命中 PRIMARY KEY，放心用。</li>
 * </ol>
 * @author admin
 * TODO zhoucong order/mapper/OrderMapper.java:99-109  为什么关单不需要分布式锁
 */
public interface OrderMapper {

    /* ==================== 查询 ==================== */

    @Select("SELECT id, order_no, out_trade_no, user_id, goods_id, num, amount, pay_channel, "
            + "status, pay_time, close_time, create_time, update_time "
            + "FROM txm_order WHERE id = #{orderId}")
    Order selectById(@Param("orderId") Long orderId);

    /** 支付回调按商户订单号定位订单（走唯一索引 uk_out_trade_no） */
    @Select("SELECT id, order_no, out_trade_no, user_id, goods_id, num, amount, pay_channel, "
            + "status, pay_time, close_time, create_time, update_time "
            + "FROM txm_order WHERE out_trade_no = #{outTradeNo}")
    Order selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /**
     * 兜底扫表：找出「仍然是待支付、且创建时间早于 deadline」的订单。
     *
     * <p>为什么必须存在：延迟消息会丢（MQ 重启、队列被误删、消息过期），
     * 丢了就意味着订单永远挂着占库存。这是第二道防线，不是可选优化。
     *
     * <p><b>注意点：</b>
     * <ol>
     *   <li>必须带 LIMIT 且分批处理，别一次捞 100 万行把内存打爆。</li>
     *   <li>必须走 (status, create_time) 组合索引（见 schema.sql），否则全表扫描拖垮 DB。</li>
     *   <li>扫描窗口不要太小：给延迟消息留出容差（例如扫 30min 之前的），
     *       否则会和正常流程抢同一批订单，做无用的 CAS。</li>
     * </ol>
     */
    @Select("SELECT id, order_no, out_trade_no, user_id, goods_id, num, amount, pay_channel, "
            + "status, pay_time, close_time, create_time, update_time "
            + "FROM txm_order WHERE status = 1 AND create_time < #{deadline} "
            + "ORDER BY id LIMIT #{limit}")
    List<Order> selectWaitPayBefore(@Param("deadline") Date deadline, @Param("limit") int limit);

    /* ==================== CAS 状态流转 ==================== */

    /**
     * 支付成功（场景2 的核心里程碑）。
     *
     * <p>{@code rows == 0} 时代表：重复回调 或 状态非法（已关闭/已完成）。
     * 调用方必须静默返回，绝不能报错、更不能继续往下发券。
     */
    @Update("UPDATE txm_order SET status = #{toStatus}, pay_channel = #{payChannel}, pay_time = NOW() "
            + "WHERE id = #{orderId} AND status = #{fromStatus}")
    int casToPaid(@Param("orderId") Long orderId,
                  @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus,
                  @Param("payChannel") Integer payChannel);

    /**
     * 关闭订单（场景4 的核心）。
     *
     * <p>这一条 SQL 一次解决两个问题：
     * <ol>
     *   <li>用户 29:59 支付、30:00 超时消息到达 → {@code status} 已是「已支付」，
     *       匹配不到行 → 不会误关已支付订单。</li>
     *   <li>消息重复投递 → 第二次匹配不到行 → 不会关两次。</li>
     * </ol>
     * 所以：<b>关单不需要分布式锁，CAS 足够，而且天然幂等。</b>
     */
    @Update("UPDATE txm_order SET status = " + OrderStatus.CLOSED + ", close_time = NOW() "
            + "WHERE id = #{orderId} AND status = " + OrderStatus.WAIT_PAY)
    int closeIfWaitPay(@Param("orderId") Long orderId);

    /** 通用 CAS 流转（发货、完成等），fromStatus 必填，禁止写成无条件 update */
    @Update("UPDATE txm_order SET status = #{toStatus} "
            + "WHERE id = #{orderId} AND status = #{fromStatus}")
    int casUpdateStatus(@Param("orderId") Long orderId,
                        @Param("fromStatus") int fromStatus,
                        @Param("toStatus") int toStatus);

    /* ==================== 写入 ==================== */

    @Insert("INSERT INTO txm_order (order_no, out_trade_no, user_id, goods_id, num, amount, "
            + "pay_channel, status) VALUES (#{orderNo}, #{outTradeNo}, #{userId}, #{goodsId}, "
            + "#{num}, #{amount}, #{payChannel}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);
}
