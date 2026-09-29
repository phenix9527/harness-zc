package com.zc.api.demo.pay;

import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.api.ErrorCode;
import com.zc.api.demo.common.api.SystemException;
import com.zc.api.demo.order.constant.OrderStatus;
import com.zc.api.demo.order.entity.Order;
import com.zc.api.demo.order.mapper.OrderMapper;
import com.zc.api.demo.pay.dto.PayNotify;
import com.zc.api.demo.pay.service.PayNotifyService;
import com.zc.api.demo.pay.service.WxPayVerifier;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 场景2 的关键分支单测：把「幂等」「状态机」「业务/系统错误语义」这三条线钉死。
 *
 * <p>JUnit 4 差异点：{@code @BeforeEach} -> {@code @Before}；
 * 断言统一走 {@code org.junit.Assert}（静态导入会掩盖 assertThrows 的来源，
 * 所以这里刻意用 {@code Assert.xxx} 全限定调用，读起来更明确）。
 */
@RunWith(MockitoJUnitRunner.class)
public class PayNotifyServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private WxPayVerifier wxPayVerifier;

    @InjectMocks
    private PayNotifyService payNotifyService;

    private Order waitPayOrder;

    @Before
    public void setUp() {
        waitPayOrder = new Order();
        waitPayOrder.setId(9L);
        waitPayOrder.setOutTradeNo("OT20260929001");
        waitPayOrder.setAmount(new BigDecimal("99.00"));
        waitPayOrder.setStatus(OrderStatus.WAIT_PAY);
    }

    private PayNotify successNotify(int amountFen) {
        PayNotify notify = new PayNotify();
        notify.setNotifyId("N-1");
        notify.setOutTradeNo("OT20260929001");
        notify.setTradeState("SUCCESS");
        notify.setAmountTotal(amountFen);
        notify.setPayChannel(2);
        return notify;
    }

    /** 正常支付：CAS 成功 -> 发支付成功事件 */
    @Test
    public void shouldSendEventWhenCasSuccess() {
        when(wxPayVerifier.tryMarkProcessing(anyString())).thenReturn(true);
        when(orderMapper.selectByOutTradeNo("OT20260929001")).thenReturn(waitPayOrder);
        when(orderMapper.casToPaid(9L, OrderStatus.WAIT_PAY, OrderStatus.PAID, 2)).thenReturn(1);

        payNotifyService.handlePaid(successNotify(9900));

        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(wxPayVerifier, times(1)).extendMark(anyString(), any());
    }

    /** 重复通知：CAS 影响 0 行 -> 静默返回，不再执行后续业务 */
    @Test
    public void shouldIgnoreWhenCasAffectedZeroRows() {
        when(wxPayVerifier.tryMarkProcessing(anyString())).thenReturn(true);
        when(orderMapper.selectByOutTradeNo("OT20260929001")).thenReturn(waitPayOrder);
        when(orderMapper.casToPaid(9L, OrderStatus.WAIT_PAY, OrderStatus.PAID, 2)).thenReturn(0);
        when(orderMapper.selectById(9L)).thenReturn(waitPayOrder);

        payNotifyService.handlePaid(successNotify(9900));

        // ★ 幂等的核心：没有真正完成流转，就不该发事件
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    /** 订单查不到 -> 系统类异常（返回 FAIL 让网关重试，而不是吞掉这笔支付） */
    @Test
    public void shouldThrowSystemExceptionWhenOrderNotFound() {
        when(wxPayVerifier.tryMarkProcessing(anyString())).thenReturn(true);
        when(orderMapper.selectByOutTradeNo(anyString())).thenReturn(null);

        SystemException e = Assert.assertThrows(SystemException.class,
                () -> payNotifyService.handlePaid(successNotify(9900)));

        Assert.assertEquals(ErrorCode.ORDER_NOT_FOUND, e.getErrorCode());
        // 失败必须释放「处理中」标记，否则网关的重试会被自己的去重逻辑挡掉
        verify(wxPayVerifier, times(1)).releaseMark(anyString());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    /** 金额不一致 -> 业务异常（停止重试，等人工介入），且绝不置为已支付 */
    @Test
    public void shouldThrowBizExceptionOnAmountMismatch() {
        when(wxPayVerifier.tryMarkProcessing(anyString())).thenReturn(true);
        when(orderMapper.selectByOutTradeNo("OT20260929001")).thenReturn(waitPayOrder);

        BizException e = Assert.assertThrows(BizException.class,
                () -> payNotifyService.handlePaid(successNotify(100)));

        Assert.assertEquals(ErrorCode.ORDER_AMOUNT_MISMATCH, e.getErrorCode());
        verify(orderMapper, never()).casToPaid(any(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 同一通知并发处理：拿不到处理权 -> 系统类异常（让网关稍后重试）。
     *
     * <p>注意点：这里的 {@code any()} 传 {@code null} 给 int 参数位置是安全的 ——
     * {@code any()} 只做匹配、不做拆箱，比 {@code anyInt()} 更适合这种「参数类型不确定」的场合。
     */
    @Test
    public void shouldRejectWhenConcurrentProcessing() {
        when(wxPayVerifier.tryMarkProcessing(anyString())).thenReturn(false);

        Assert.assertThrows(SystemException.class, () -> payNotifyService.handlePaid(successNotify(9900)));

        verify(orderMapper, never()).casToPaid(any(), anyInt(), anyInt(), anyInt());
    }
}
