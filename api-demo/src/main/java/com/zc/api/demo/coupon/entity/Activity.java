package com.zc.api.demo.coupon.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Date;

/**
 * 活动实体。
 *
 * <p><b>状态机不只对订单有意义，活动也有：</b>
 * {@code 0未开始 -> 1进行中 -> 2已结束 / 3已下线}。
 * 领券接口第一件事就是做状态守卫，否则会出现「活动没开始就发出去券」的事故。
 *
 * <p><b>Lombok 用法注意点（实体类尤其要克制）：</b>
 * <ol>
 *   <li><b>只用 {@code @Getter/@Setter}，不用 {@code @Data}。</b>
 *       {@code @Data} = Getter + Setter + equals/hashCode + toString + RequiredArgsConstructor。
 *       其中 equals/hashCode 会包含<b>全部字段</b>，对可变实体是灾难：
 *       把对象放进 HashSet / 作为 Map 的 key 之后，只要改了一个字段，就再也查不到了
 *       （hashCode 变了，落在错误的桶里）。实体类请显式写 {@code @EqualsAndHashCode(of = "id")}。</li>
 *   <li><b>{@code @ToString} 必须限定字段。</b>无脑 {@code @ToString} 会把全部字段打出来，
 *       订单金额、手机号、证件号这类信息一旦进了日志系统就等于泄露；而且字段一多，
 *       一行日志会变成几百字符，反而没人看。</li>
 *   <li>实体<b>不加 {@code @Builder}</b>：MyBatis 需要无参构造 + setter 回填，
 *       加上 {@code @Builder} 会把默认构造器吃掉（除非同时写 {@code @NoArgsConstructor @AllArgsConstructor}），
 *       这是「编译期没报错，运行时查出来全是 null」的经典来源。</li>
 * </ol>
 * @author admin
 */
@Getter
@Setter
@ToString(of = {"id", "activityCode", "status"})
public class Activity {

    /** 活动状态：未开始 */
    public static final int STATUS_NOT_START = 0;
    /** 活动状态：进行中 */
    public static final int STATUS_RUNNING = 1;
    /** 活动状态：已结束 */
    public static final int STATUS_ENDED = 2;
    /** 活动状态：已下线（运营手动停） */
    public static final int STATUS_OFFLINE = 3;

    private Long id;
    private String activityCode;
    private String name;
    private Integer status;
    private Date startTime;
    private Date endTime;
    private Integer totalStock;

    /**
     * 状态机守卫：判断活动此刻是否可领券。
     *
     * <p><b>注意点：</b>
     * <ol>
     *   <li>必须同时判断 status 和 时间窗口。运营常有「status 还是进行中，但 endTime 已过」的脏数据，
     *       只信 status 会发出过期活动的券。</li>
     *   <li>时间比较用服务端时间（这里传 now 进来是为了让单测可控）。
     *       <b>不要用客户端传的时间</b>，改一个请求参数就能提前领券。</li>
     *   <li>这里用的是缓存里的活动对象，缓存有 TTL。运营停活动到生效有延迟，
     *       重要活动要提供「立即失效缓存」的运营接口（见 ActivityCache#evict）。</li>
     * </ol>
     */
    public boolean isRunning(Date now) {
        if (status == null || status != STATUS_RUNNING) {
            return false;
        }
        if (startTime == null || endTime == null) {
            return false;
        }
        long t = now.getTime();
        return t >= startTime.getTime() && t <= endTime.getTime();
    }
}
