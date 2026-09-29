package com.zc.api.demo.order.service;

import com.zc.api.demo.order.entity.Order;
import com.zc.api.demo.order.mapper.OrderMapper;
import com.zc.api.demo.order.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存占用/释放/扣减服务。
 *
 * <p><b>核心原则：每个关联资源各自幂等。</b>
 * 订单状态变化（关单/支付）不能作为「库存处理过没有」的判据 ——
 * 关单成功但释放库存失败、关单消息重复投递，这两种情况都会让「靠订单状态判断」的方案出错。
 * 所以库存侧用 {@code t_order_stock_lock} 的状态 CAS 作为幂等判据。
 */
@Service
public class OrderStockService {

    private static final Logger log = LoggerFactory.getLogger(OrderStockService.class);

    private final StockMapper stockMapper;
    private final OrderMapper orderMapper;

    public OrderStockService(StockMapper stockMapper, OrderMapper orderMapper) {
        this.stockMapper = stockMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * 下单占用库存。
     *
     * <p><b>注意点：</b>
     * <ol>
     *   <li>{@code lockStock} 返回 0 = 库存不足，属于<b>业务失败</b>（HTTP 200 + 业务码），
     *       不是异常。用返回值表达「可预期的失败」比抛异常更清爽。</li>
     *   <li>这个方法要在下单的<b>同一个本地事务</b>里：订单插入 + 库存占用 + 流水插入必须一起成功。
     *       真实项目里如果库存和订单不同库，那就是分布式事务（Seata TCC / 本地消息表），
     *       此时接口必须满足 TCC 三约束：幂等、允许空回滚、防悬挂。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean lock(Long goodsId, int num) {
        int rows = stockMapper.lockStock(goodsId, num);
        if (rows == 0) {
            log.info("stock not enough, goodsId={}, num={}", goodsId, num);
            return false;
        }
        return true;
    }

    /**
     * 关单后释放库存 —— <b>幂等</b>。
     *
     * <p>流程：先用流水表 CAS 把 1占用中 改成 2已释放；
     * <ul>
     *   <li>改到（rows=1）→ 再回加库存。这是我释放的，放心加。</li>
     *   <li>没改到（rows=0）→ 说明已经被释放/已扣减，<b>直接返回，不能重复加库存</b>。</li>
     * </ul>
     *
     * <p><b>注意点：</b>两个 update 必须在同一事务；否则会出现「流水已标记释放、库存却没加回去」
     * 的永久性裂缝（对账时表现为库存少，而所有单据看起来都正常）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void releaseByClosedOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            // 订单查不到：可能被误删/归档。不能盲目释放，留日志人工核查
            log.warn("release skip, order not found. orderId={}", orderId);
            return;
        }
        int rows = stockMapper.markReleased(orderId, order.getGoodsId());
        if (rows == 0) {
            // ★ 幂等出口：已释放过 / 已扣减，什么都不做
            log.info("stock already released/deducted, skip. orderId={}", orderId);
            return;
        }
        int num = order.getNum() == null ? 1 : order.getNum();
        int stockRows = stockMapper.releaseLockedStock(order.getGoodsId(), num);
        if (stockRows == 0) {
            // 锁定库存不够，说明数据已经不一致了：抛异常回滚，让流水回到 1占用中 等待人工介入
            // 这里宁可回滚 + 告警，也不要「凑合加上去」把账做烂
            throw new IllegalStateException("release locked stock failed, data inconsistent. orderId=" + orderId);
        }
        log.info("stock released, orderId={}, goodsId={}, num={}", orderId, order.getGoodsId(), num);
    }

    /**
     * 支付成功后把「占用」转为「真实扣减」—— 同样幂等。
     *
     * <p>注意：这一步在真实项目里通常是 MQ 异步做的（见 {@code OrderPaidListener}），
     * 因为它不阻塞用户，而且下游可能还有积分、通知、发货单等一堆动作。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deductByPaidOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            log.warn("deduct skip, order not found. orderId={}", orderId);
            return;
        }
        int rows = stockMapper.markDeducted(orderId, order.getGoodsId());
        if (rows == 0) {
            log.info("stock already released/deducted, skip. orderId={}", orderId);
            return;
        }
        int num = order.getNum() == null ? 1 : order.getNum();
        int stockRows = stockMapper.deductLockedStock(order.getGoodsId(), num);
        if (stockRows == 0) {
            throw new IllegalStateException("deduct locked stock failed, data inconsistent. orderId=" + orderId);
        }
        log.info("stock deducted, orderId={}, goodsId={}, num={}", orderId, order.getGoodsId(), num);
    }
}
