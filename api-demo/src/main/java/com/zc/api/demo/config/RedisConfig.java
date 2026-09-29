package com.zc.api.demo.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 配置：一个「Key 字符串、Value 字符串」的 RedisTemplate + 一个可复用的 Lua 脚本 Bean。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li><b>一定要指定序列化方式。</b> 默认 {@code JdkSerializationRedisSerializer} 会在 value 上带
 *       类名和二进制前缀，用 redis-cli / 其他语言读出来是乱码，运维和排查都会骂人。
 *       本项目全部走 String，业务对象自己 JSON 化，可控性最好。</li>
 *   <li><b>Lua 脚本 Bean 复用同一个 DefaultRedisScript 实例</b>：脚本内容固定，Spring 首次执行后会走
 *       {@code EVALSHA}，比每次传脚本体省带宽。别在方法里 new。</li>
 *   <li>脚本必须用 {@code <String, Long>} 的显式泛型；返回值类型对不上会抛
 *       {@code ClassCastException}，且报错信息很难懂。</li>
 *   <li>生产环境禁止在业务代码里用 {@code KEYS *}（会阻塞 Redis 单线程），
 *       批量删除用 {@code SCAN} 或后缀 key 设计。</li>
 *   <li>本类同时负责 RedissonClient（只为分布式锁）。它与上面的 RedisTemplate 是
 *       <b>两个独立客户端、两条独立连接</b>，各自有超时与连接池 —— 排查时别把两者混为一谈。</li>
 * </ol>
 */
@Slf4j
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 优惠券预扣脚本：一次 Redis 往返完成「查用户是否领过 + 判库存 + 扣库存 + 打用户标记」。
     *
     * <p><b>为什么必须用 Lua：</b> 这几步如果在 Java 里分开调用，就先出现了「扣了库存但用户标记没打上」的
     * 中间态，并发一上来必然超发。
     *
     * <p>返回值约定见 {@code coupon_claim.lua} 文件头注释。
     */
    @Bean
    public DefaultRedisScript<Long> couponClaimScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/coupon_claim.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 短信验证码校验脚本：一次 Redis 往返完成「判断 + 删除 + 错误计数」。
     *
     * <p>如果拆成 GET / DEL / INCR 三次调用，「校验成功立即失效」就挡不住并发重放 ——
     * 两个请求可以同时 GET 到验证码，然后都判定成功。
     */
    @Bean
    public DefaultRedisScript<Long> smsVerifyScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/sms_verify.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Redisson 客户端 —— <b>本项目里只服务于分布式锁</b>（见 {@code RedissonLock}）。
     *
     * <p><b>为什么不用 redisson-spring-boot-starter：</b>
     * 那个 starter 会注册一个 {@code RedissonConnectionFactory} Bean。Spring Boot 的
     * {@code RedisAutoConfiguration} 是「已有 ConnectionFactory 就不再创建」的条件装配，
     * 于是 {@code RedisTemplate} 会被悄悄换成 Redisson 连接 —— 而本项目所有 Lua 脚本、
     * 限流、幂等标记都依赖 Lettuce 的行为与超时配置。两个客户端混着用，
     * 出问题时「到底谁连的 Redis」都说不清。手建 Bean，边界才清楚。
     *
     * <p><b>注意点：</b>
     * <ol>
     *   <li><b>必须单例</b>（交给 Spring 管就行）。RedissonClient 内部有连接池和 Netty EventLoop 线程，
     *       每次 new 一份就是泄漏一批连接，几百次调用后 Redis 连接数被打满。</li>
     *   <li>{@code destroyMethod = "shutdown"}：不写的话应用关闭时 Netty 线程不会退出，
     *       会出现「进程不退、端口不释放」的假死，容器里表现为滚动发布一直卡住。</li>
     *   <li><b>看门狗超时</b>由 {@code setLockWatchdogTimeout} 控制（见 {@code demo.lock.watchdog-timeout-millis}）：
     *       续期间隔是它的 1/3，所以这个值同时决定了「JVM 被 kill 后锁最多多久自动释放」。
     *       设太大 → 崩了之后任务被卡住很久没人接手；设太小 → 续期太频繁、GC 一停顿就误释放。</li>
     *   <li>命令超时（{@code timeout}）不要设得比业务容忍度还长：锁获取是同步阻塞的，
     *       Redis 抖动时业务线程会跟着一起挂着。</li>
     * </ol>
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.redis.host:127.0.0.1}") String host,
            @Value("${spring.redis.port:6379}") int port,
            @Value("${spring.redis.password:}") String password,
            @Value("${spring.redis.database:0}") int database,
            @Value("${spring.redis.timeout:800ms}") Duration commandTimeout,
            @Value("${demo.lock.watchdog-timeout-millis:30000}") long watchdogTimeoutMillis,
            @Value("${demo.lock.pool-size:16}") int poolSize) {

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setTimeout((int) commandTimeout.toMillis())
                .setConnectTimeout(3000)
                .setConnectionPoolSize(poolSize)
                .setConnectionMinimumIdleSize(Math.max(1, poolSize / 4))
                // 重试次数别给大：定时任务场景抢不到锁本来就该跳过，重试只是延长调度耗时
                .setRetryAttempts(2)
                .setRetryInterval(500);
        if (password != null && !password.trim().isEmpty()) {
            config.useSingleServer().setPassword(password.trim());
        }
        // ★ 看门狗超时：续期间隔 = 该值 / 3；也是「进程被杀后锁的自动释放上限」
        config.setLockWatchdogTimeout(watchdogTimeoutMillis);

        RedissonClient client = Redisson.create(config);
        log.info("redisson client created, host={}, port={}, watchdogTimeoutMillis={}",
                host, port, watchdogTimeoutMillis);
        return client;
    }
}
