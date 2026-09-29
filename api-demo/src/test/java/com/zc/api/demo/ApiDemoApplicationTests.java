package com.zc.api.demo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 上下文冒烟测试：只验证 Spring 容器能起来（Bean 装配、数据源、MQ、Redis 连接都能建立）。
 *
 * <p><b>注意点：</b>
 * <ul>
 *   <li>这是<b>集成测试</b>，不是单测 —— 它需要真实的 MySQL/Redis/RabbitMQ 才能通过
 *       （中间件没起就会启动失败）。所以平时用 {@code -Dtest=} 只跑纯单测：
 *       {@code mvn -Dtest=CouponClaimServiceTest,PayNotifyServiceTest,... test}。</li>
 *   <li>JUnit 4 下 Spring 的测试支持由 {@link SpringRunner} 提供（JUnit 5 里对应的是
 *       {@code @ExtendWith(SpringExtension.class)}，{@code @SpringBootTest} 会自动带上）。
 *       少了这个 runner，{@code @Autowired} 注入会静默为 null —— 测试「绿着绿着就假绿了」。</li>
 *   <li>JUnit 4 要求测试类与测试方法都是 {@code public}。</li>
 * </ul>
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class ApiDemoApplicationTests {

    @Test
    public void contextLoads() {
    }

}
