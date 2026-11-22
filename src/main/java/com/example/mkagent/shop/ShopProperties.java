package com.example.mkagent.shop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 商城配置。
 *
 * <p>核心只有一件事：<b>身份从哪来</b>。当前工程没有真实认证，
 * 身份是请求头 {@code X-User-Id} 的占位值，任何人都能自己填一个。
 * 因此商城的受保护写操作（下单、发货、签收）必须显式开关，
 * 而不是"能跑就先跑起来"。
 *
 * <p>三种状态，由两个布尔量组合：
 * <ul>
 *   <li>{@code demoIdentityEnabled=true}：本地 / 演示环境。信任 {@code X-User-Id}
 *       作为<b>演示身份</b>，角色仍然只从 {@code shop_user} 表读，
 *       不读任何请求里声明的角色。这是本轮浏览器验收使用的模式。</li>
 *   <li>{@code trustedIdentity=true}：已经有可信的服务端登录态（例如会话 / JWT），
 *       由认证层写入身份。本轮**没有**实现这一层，开关留着是为了让接入点明确。</li>
 *   <li>两个都是 false（默认）：受保护写操作**一律拒绝**，
 *       返回 403 {@code SHOP_IDENTITY_NOT_CONFIGURED}，
 *       商品浏览等只读能力仍然可用。这是共享部署的默认值。</li>
 * </ul>
 *
 * <p>为什么不合并成一个 {@code identity-mode} 枚举：
 * 两个开关的语义不同——一个是"这是演示环境"，一个是"认证层已经就绪"。
 * 合并后容易出现"既是 demo 又声称可信"的第三种状态，
 * 而 {@link ShopAccessGuard} 里两种都允许时到底信谁，会变成没人说得清的问题。
 */
@Component
@ConfigurationProperties(prefix = "mkagent.shop")
public class ShopProperties {

    /**
     * 是否启用演示身份（信任 {@code X-User-Id}）。
     *
     * <p>默认 false：默认构建不允许下单，避免"看起来能下单、其实没有鉴权"的部署。
     * 只有 {@code application-demo.yml} 与测试 profile 会打开。
     */
    private boolean demoIdentityEnabled = false;

    /**
     * 是否已经接入可信的服务端身份。
     *
     * <p>默认 false。本轮没有认证层，因此这个开关打开也只会让
     * {@link ShopAccessGuard} 认为"身份可信"，实际仍是请求头——
     * 所以文档里明确写：<b>不要在共享部署上打开它来代替真实认证</b>。
     */
    private boolean trustedIdentity = false;

    /**
     * 单笔订单的最大购买数量。
     *
     * <p>存在的意义不是"业务规则"，而是防止一次请求把库存表打成负数区间之外的
     * 荒唐值（例如 2^31-1）导致 {@code Math.multiplyExact} 溢出。
     * 上限本身是演示性质的。
     */
    private int maxQuantityPerOrder = 99;

    /** 后台订单列表默认页大小（上限见 {@code ShopAdminController}）。 */
    private int adminPageSize = 20;

    /**
     * 售后（A1）退货窗口：签收后多少天内可以申请退货。
     *
     * <p>这是**演示规则**，不是真实平台政策。它存在的意义是让"超期不能退"
     * 成为一条可测试、可解释的规则，而不是一句"看情况"。
     */
    private int returnWindowDays = 7;

    /**
     * 退货原因的最大长度（按 Unicode 码点计）。
     *
     * <p>与 {@code shop_aftersale_return.reason VARCHAR(200)} 对齐：
     * 应用层先按码点校验，数据库再按字符数兜底。
     */
    private int maxReturnReasonLength = 200;

    /**
     * 买家售后列表最多返回多少条（A1 不分页）。
     */
    private int aftersaleListLimit = 50;

    /**
     * 客服驳回原因的最大长度（按 Unicode 码点计）。
     *
     * <p>与 {@code shop_aftersale_decision.reason VARCHAR(200)} 对齐。
     * 单独一个字段而不是复用 {@code maxReturnReasonLength}：
     * 两者是不同角色填的不同字段，共用一个上限会让"改买家规则"
     * 悄悄改掉客服的约束。
     */
    private int maxRejectReasonLength = 200;

    public int getMaxRejectReasonLength() {
        return maxRejectReasonLength;
    }

    public void setMaxRejectReasonLength(int maxRejectReasonLength) {
        this.maxRejectReasonLength = maxRejectReasonLength;
    }

    public int getReturnWindowDays() {
        return returnWindowDays;
    }

    public void setReturnWindowDays(int returnWindowDays) {
        this.returnWindowDays = returnWindowDays;
    }

    public int getMaxReturnReasonLength() {
        return maxReturnReasonLength;
    }

    public void setMaxReturnReasonLength(int maxReturnReasonLength) {
        this.maxReturnReasonLength = maxReturnReasonLength;
    }

    public int getAftersaleListLimit() {
        return aftersaleListLimit;
    }

    public void setAftersaleListLimit(int aftersaleListLimit) {
        this.aftersaleListLimit = aftersaleListLimit;
    }

    public boolean isDemoIdentityEnabled() {
        return demoIdentityEnabled;
    }

    public void setDemoIdentityEnabled(boolean demoIdentityEnabled) {
        this.demoIdentityEnabled = demoIdentityEnabled;
    }

    public boolean isTrustedIdentity() {
        return trustedIdentity;
    }

    public void setTrustedIdentity(boolean trustedIdentity) {
        this.trustedIdentity = trustedIdentity;
    }

    public int getMaxQuantityPerOrder() {
        return maxQuantityPerOrder;
    }

    public void setMaxQuantityPerOrder(int maxQuantityPerOrder) {
        this.maxQuantityPerOrder = maxQuantityPerOrder;
    }

    public int getAdminPageSize() {
        return adminPageSize;
    }

    public void setAdminPageSize(int adminPageSize) {
        this.adminPageSize = adminPageSize;
    }

    /**
     * 受保护写操作是否可用（任一身份来源就绪即可）。
     */
    public boolean protectedWritesEnabled() {
        return demoIdentityEnabled || trustedIdentity;
    }

    /**
     * 当前身份来源，用于页面展示。
     *
     * @return {@code DEMO} / {@code TRUSTED} / {@code DISABLED}
     */
    public String identityMode() {
        if (demoIdentityEnabled) {
            return "DEMO";
        }
        return trustedIdentity ? "TRUSTED" : "DISABLED";
    }
}
