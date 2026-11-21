package com.example.mkagent.shop;

import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 商城访问控制。
 *
 * <p>位置：控制器在**进入业务之前**调用，被拒绝的请求不会写任何业务数据。
 *
 * <p>三条规则，顺序不能换：
 * <ol>
 *   <li>缺 {@code X-User-Id} → 401（与项目其它入口一致）；</li>
 *   <li>受保护写操作未启用（demo 身份关闭且没有可信身份）→ 403
 *       {@code SHOP_IDENTITY_NOT_CONFIGURED}。这一步**先于**角色判断：
 *       身份本身不可信时，去查 {@code shop_user} 只会得到"这个人恰好有角色"的假答案；</li>
 *   <li>{@code shop_user} 里没有这个人 → 403 {@code SHOP_USER_NOT_PROVISIONED}；
 *       后台入口还要求 ADMIN → 否则 403 {@code NOT_ADMIN}。</li>
 * </ol>
 *
 * <p>局限（必须说清楚）：{@code demoIdentityEnabled=true} 时身份仍是可伪造的请求头，
 * 本类只保证"服务端不信任请求里声明的角色"，
 * 不保证"身份本身可信"。这条边界写在 docs/shop-a0.md。
 */
@Component
public class ShopAccessGuard {

    /** 缺少身份时的提示语（与 {@code AiController#requireUserId()} 同口径）。 */
    public static final String MISSING_USER_MESSAGE =
            "缺少用户身份，请在请求头中携带 " + UserContextHolder.USER_ID_HEADER;

    /**
     * 受保护写操作未启用。
     *
     * <p>文案里直接给出两条可执行的出路，而不是只说"无权限"——
     * 否则使用者会以为是自己没登录，而不是配置没开。
     */
    public static final String IDENTITY_NOT_CONFIGURED_MESSAGE =
            "商城身份未启用：当前无法确认买家身份。"
                    + "本地演示请用 demo profile 启动（mkagent.shop.demo-identity-enabled=true）；"
                    + "共享部署请先接入可信登录，再打开 mkagent.shop.trusted-identity。";

    /** 用户不在 shop_user 表里。 */
    public static final String NOT_PROVISIONED_MESSAGE =
            "该身份未开通商城（shop_user 表里没有这个用户）";

    /** 非管理员访问后台入口。 */
    public static final String NOT_ADMIN_MESSAGE = "无权访问商城后台接口";

    private final ShopProperties properties;

    private final ShopRoleResolver roleResolver;

    public ShopAccessGuard(ShopProperties properties, ShopRoleResolver roleResolver) {
        this.properties = properties;
        this.roleResolver = roleResolver;
    }

    /**
     * 当前会话视图（不抛异常）。
     *
     * <p>页面需要在**点击之前**就知道"能不能下单"，否则用户填完数量才被 403 拒绝。
     * 这里如实报告身份来源与能力，不猜测、不放宽。
     */
    public ShopSession session() {
        String userId = UserContextHolder.get();
        if (userId == null || userId.isBlank()) {
            return new ShopSession(null, ShopRole.UNKNOWN, properties.identityMode(), false, false);
        }

        ShopRole role = roleResolver.resolve(userId);
        boolean writesEnabled = properties.protectedWritesEnabled();
        boolean known = role != ShopRole.UNKNOWN;

        return new ShopSession(
                userId,
                role,
                properties.identityMode(),
                writesEnabled && known,
                writesEnabled && role == ShopRole.ADMIN);
    }

    /**
     * 要求一个可信买家身份（下单、读自己的订单都用它）。
     *
     * <p>读自己的订单也要走这里：身份不可信时，"自己的订单"没有定义，
     * 拿请求头去查订单等于任何人填一个 id 就能读别人的订单。
     *
     * @return 已校验的 userId
     * @throws BusinessException 401 缺身份 / 403 未启用或未开通
     */
    public String requireBuyerIdentity() {
        String userId = requireHeader();
        requireProtectedWritesEnabled();

        if (roleResolver.resolve(userId) == ShopRole.UNKNOWN) {
            throw new BusinessException(403, "SHOP_USER_NOT_PROVISIONED", NOT_PROVISIONED_MESSAGE);
        }

        return userId;
    }

    /**
     * 要求后台演示人员（发货、签收、库存核对）。
     *
     * <p>未知用户与非管理员合并成同一个错误：对调用方来说都是"这个入口你不能用"，
     * 分开只会多给一条"该 userId 是否存在"的探测信号。
     *
     * @return 已校验的 userId
     * @throws BusinessException 401 缺身份 / 403 未启用或非管理员
     */
    public String requireAdmin() {
        String userId = requireHeader();
        requireProtectedWritesEnabled();

        if (roleResolver.resolve(userId) != ShopRole.ADMIN) {
            throw new BusinessException(403, "NOT_ADMIN", NOT_ADMIN_MESSAGE);
        }

        return userId;
    }

    private String requireHeader() {
        String userId = UserContextHolder.get();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(401, MISSING_USER_MESSAGE);
        }
        return userId;
    }

    private void requireProtectedWritesEnabled() {
        if (!properties.protectedWritesEnabled()) {
            throw new BusinessException(
                    403, "SHOP_IDENTITY_NOT_CONFIGURED", IDENTITY_NOT_CONFIGURED_MESSAGE);
        }
    }

    /**
     * 会话视图。
     *
     * @param userId       当前身份；未提供时为 null
     * @param role         服务端解析出的商城角色
     * @param identityMode {@code DEMO} / {@code TRUSTED} / {@code DISABLED}
     * @param canOrder     是否可以下单（身份已启用且该身份已开通商城）
     * @param canOperate   是否可以使用后台入口（ADMIN）
     */
    public record ShopSession(
            String userId,
            ShopRole role,
            String identityMode,
            boolean canOrder,
            boolean canOperate
    ) {
    }
}
