package com.zc.api.demo.order.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 库存 Mapper：把「库存数字变更」和「占用流水状态变更」拆成两步，各自 CAS。
 *
 * <p><b>为什么要有 t_order_stock_lock 这张流水表？</b>
 * 因为「关单」和「释放库存」是两个动作，关单成功不代表释放成功（可能中间挂了、可能重复消费）。
 * 如果只靠「订单已关闭」来判断「要不要释放库存」，就会出现：
 * <ul>
 *   <li>重复消费 → 释放两次 → 库存虚增（超卖）；</li>
 *   <li>释放失败后重试 → 无法判断上次是否已释放。</li>
 * </ul>
 * 正确做法是<b>每个关联资源各自幂等</b>：用流水表的状态 CAS 做「这个资源是否已释放」的唯一判据。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>{@code locked_stock >= #{num}} 这种带条件的写法，比「先 SELECT 再 UPDATE」安全得多，
 *       本质是乐观锁 + 原子扣减。</li>
 *   <li>库存变更一定要带 {@code WHERE} 里的边界条件（不允许出现负库存），
 *       哪怕上游已经判断过。防御性 SQL 是最后一道保险。</li>
 *   <li>扣减/释放都是<b>幂等动作</b>，但幂等判据在流水表上（1→2 / 1→3），
 *       所以两个 update 必须在同一个本地事务里，否则会出现「流水已释放、库存没加回去」的裂缝。</li>
 * </ol>
 */
public interface StockMapper {

    /* ==================== 占用流水状态 CAS（幂等判据） ==================== */

    /**
     * 释放占用（关单场景）：1占用中 -> 2已释放。
     *
     * <p>返回 1 才代表「这次是我释放的」，调用方再去改库存数字。
     * 返回 0 说明已经释放过了（或本来就是扣减状态），必须直接返回，不能重复加库存。
     */
    @Update("UPDATE t_order_stock_lock SET status = 2 "
            + "WHERE order_id = #{orderId} AND goods_id = #{goodsId} AND status = 1")
    int markReleased(@Param("orderId") Long orderId, @Param("goodsId") Long goodsId);

    /** 支付成功转扣减：1占用中 -> 3已扣减（锁定量减掉，真实库存不再回加） */
    @Update("UPDATE t_order_stock_lock SET status = 3 "
            + "WHERE order_id = #{orderId} AND goods_id = #{goodsId} AND status = 1")
    int markDeducted(@Param("orderId") Long orderId, @Param("goodsId") Long goodsId);

    /* ==================== 库存数字变更 ==================== */

    /**
     * 下单占用：可售库存 - num，锁定库存 + num。
     *
     * <p>{@code stock >= #{num}} 保证不会超卖（并发下由行锁串行化）。
     * 返回 0 = 库存不足，业务侧应返回「库存不足」而不是抛 500。
     */
    @Update("UPDATE t_goods_stock SET stock = stock - #{num}, locked_stock = locked_stock + #{num} "
            + "WHERE goods_id = #{goodsId} AND stock >= #{num}")
    int lockStock(@Param("goodsId") Long goodsId, @Param("num") int num);

    /** 关单释放：锁定库存 - num，可售库存 + num（仅当锁定库存够，防止负值） */
    @Update("UPDATE t_goods_stock SET stock = stock + #{num}, locked_stock = locked_stock - #{num} "
            + "WHERE goods_id = #{goodsId} AND locked_stock >= #{num}")
    int releaseLockedStock(@Param("goodsId") Long goodsId, @Param("num") int num);

    /** 支付成功扣减：锁定库存 - num（可售库存不恢复，意味着商品真的卖出去了） */
    @Update("UPDATE t_goods_stock SET locked_stock = locked_stock - #{num} "
            + "WHERE goods_id = #{goodsId} AND locked_stock >= #{num}")
    int deductLockedStock(@Param("goodsId") Long goodsId, @Param("num") int num);
}
