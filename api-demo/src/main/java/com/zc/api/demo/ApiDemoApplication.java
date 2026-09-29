package com.zc.api.demo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 微服务接口设计小场景最佳实践 —— 启动类。
 *
 * <p>包含 5 个场景：
 * <ol>
 *   <li>优惠券领取：幂等 + 超卖 + 状态机 + 限流 + 缓存（{@code coupon} 包）</li>
 *   <li>支付回调通知：验签 + CAS 幂等 + 业务/系统错误分离（{@code pay} 包）</li>
 *   <li>短信验证码：多维度频控 + retryAfter 错误语义（{@code sms} 包）</li>
 *   <li>订单超时关闭：延迟消息 + CAS + 兜底扫表（{@code order} 包）</li>
 *   <li>异步导出报表：长任务状态机 + 结果有效期（{@code export} 包）</li>
 * </ol>
 *
 * <p><b>运行前置依赖：</b> MySQL（库名 api_demo，启动时自动执行 schema.sql）、
 * Redis、RabbitMQ。三者任一不可用都会影响对应场景，启动日志里能看到失败原因。
 *
 * <p><b>注意点：</b>
 * <ul>
 *   <li>{@code @EnableScheduling}：兜底扫表任务依赖它，漏了会出现「订单永远挂着」。单机没问题，
 *       多副本部署时定时任务必须加分布式锁或改用分布式调度（XXL-JOB），否则会重复执行。</li>
 *   <li>{@code @MapperScan}：Mapper 接口都在子包里，集中声明比每个接口加 @Mapper 更好维护。
 *       这里显式列出包名（而不是写 {@code com.zc.api.demo.**.mapper}）——
 *       通配写法在部分扫描器里行为不一致，启动时才发现「Mapper 没注册」很难查。</li>
 *   <li>这里刻意<b>没有</b>开 {@code @EnableAsync}：导出任务是通过 MQ 触发异步执行的，
 *       已经满足「提交接口毫秒返回」的要求。真要用 {@code @Async}，记得配专用线程池，
 *       不要用默认的（默认池无界队列，跑满会 OOM，而且会和主业务抢线程）。</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@MapperScan({"com.zc.api.demo.coupon.mapper",
        "com.zc.api.demo.order.mapper",
        "com.zc.api.demo.export.mapper"})
public class ApiDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiDemoApplication.class, args);
    }

}
