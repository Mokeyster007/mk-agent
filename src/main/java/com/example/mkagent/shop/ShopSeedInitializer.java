package com.example.mkagent.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商城种子数据。
 *
 * <h2>幂等</h2>
 * 以"商品 id=2001 是否存在"作为"是否已初始化"的判据，存在就整体跳过。
 * 不用"商品表有行"判断：那样任何一条手工插入的商品都会让初始化器误判，
 * 导致库存与演示身份永远建不起来。
 *
 * <h2>为什么不覆盖已有库存</h2>
 * 初始化器**只在首次建库存**，重启不会把库存重置回 10。
 * 演示到一半重启后端，订单和库存必须还在——否则"刷新后能看到持久化状态"
 * 这条验收就无从谈起。需要重置时用测试夹具或手工 SQL，不要靠重启。
 *
 * <h2>演示身份为什么用 music-* 这组 id</h2>
 * 本项目的联调身份（{@code X-User-Id}）只有一套开关，
 * 前端在 {@code mkagent.dev.userId} 里存一个值，音乐页和商城页共用。
 * 如果商城另起一套 {@code shop-buyer-1}，在商城页切过去之后音乐页立刻 403
 * ——演示会变成"两个模块互斥"。
 * 因此这里沿用同一组标识符，但**角色独立存放在 shop_user**：
 * 同一个人"能听歌"不代表"能发货"，两张角色表互不推导。
 * 需要独立身份时，往 shop_user 加一行即可，不需要改代码。
 *
 * <h2>为什么 @Profile("!prod")</h2>
 * 与音乐种子一致：生产库不允许被自动灌入虚构商品与演示身份。
 */
@Component
@Profile("!prod")
@Order(1)
public class ShopSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShopSeedInitializer.class);

    /** 种子商品 id 区间：2001 ~ 2099。 */
    public static final long PRODUCT_KEYBOARD = 2001L;

    public static final long PRODUCT_MOUSE = 2002L;

    public static final long PRODUCT_CABLE = 2003L;

    /** 种子 SKU（测试与文档直接引用，避免各处抄字符串）。 */
    public static final String SKU_KEYBOARD = "SKU-KEYBOARD-K87";

    public static final String SKU_MOUSE = "SKU-MOUSE-M2";

    public static final String SKU_CABLE = "SKU-CABLE-C1";

    /** 主验收用的初始库存：键盘 10 件。 */
    public static final int KEYBOARD_STOCK = 10;

    public static final int MOUSE_STOCK = 5;

    /** 数据线刻意 0 库存，用来演示"售罄 / 库存不足"。 */
    public static final int CABLE_STOCK = 0;

    /** 演示买家与后台人员（与前端联调身份的默认值一致）。 */
    public static final String BUYER_ONE = "music-user-1";

    public static final String BUYER_TWO = "music-user-2";

    /**
     * **只有商城身份、没有音乐身份**的买家。
     *
     * <p>为什么演示数据里需要这样一个人：前两个演示买家同时也是 music_user，
     * 于是"售后页建不出会话"这类问题会被掩盖——前端建会话走的是音乐守卫的接口，
     * 他们恰好能过。只有这个身份能把"纯商城买家"的真实路径走通。
     *
     * <p>它不在 {@code music_user} 里，因此 {@code /ai/music/*} 与
     * {@code /ai/conversations} 对它一律 403；这正是要证明的边界。
     */
    public static final String BUYER_SHOP_ONLY = "shop-user-1";

    public static final String ADMIN = "music-admin-1";

    private record SeedProduct(long id, String sku, String name, String subtitle, long priceCents) {
    }

    private static final List<SeedProduct> PRODUCTS = List.of(
            new SeedProduct(PRODUCT_KEYBOARD, SKU_KEYBOARD, "机械键盘 K87",
                    "87 键 · 茶轴 · 白色背光", 29900L),
            new SeedProduct(PRODUCT_MOUSE, SKU_MOUSE, "静音鼠标 M2",
                    "无线 2.4G · 静音微动 · 可充电", 12900L),
            new SeedProduct(PRODUCT_CABLE, SKU_CABLE, "USB-C 数据线 C1",
                    "1.5 米 · 100W 快充（演示售罄）", 1900L));

    private final JdbcTemplate jdbcTemplate;

    public ShopSeedInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        /*
         * 用户先补齐：这一条**每次启动都跑**（幂等插入）。
         *
         * 为什么不能放在 alreadyInitialized() 之后：商品种子只写一次，
         * 但"新增一个演示买家"是后来才加的需求——已经初始化过的库
         * 如果被那个判断挡住，就永远拿不到这个身份，
         * 于是"纯商城买家"的验收只能在全新库上复现。
         */
        ensureUsers();

        if (alreadyInitialized()) {
            log.debug("商城种子数据已存在，跳过初始化");
            return;
        }

        for (SeedProduct product : PRODUCTS) {
            jdbcTemplate.update("""
                    INSERT INTO shop_product
                        (id, sku, name, subtitle, price_cents, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                    """, product.id(), product.sku(), product.name(),
                    product.subtitle(), product.priceCents());

            jdbcTemplate.update("""
                    INSERT INTO shop_inventory_balance
                        (sku, product_id, available, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """, product.sku(), product.id(), stockOf(product.sku()));
        }

        /*
         * 身份不在这里插入：ensureUsers() 已经在最前面做过幂等补齐。
         * 这里再插一次会撞 shop_user 主键（种子初始化在一个事务里）。
         */

        log.info("商城种子数据已写入：{} 个商品，键盘库存 {}，演示买家 {} / {} / {}，后台 {}",
                PRODUCTS.size(), KEYBOARD_STOCK, BUYER_ONE, BUYER_TWO, BUYER_SHOP_ONLY, ADMIN);
    }

    /**
     * 补齐演示身份（幂等）。
     *
     * <p>{@code shop-user-1} 刻意**不**写进 {@code music_user}：
     * 它就是用来验证"合法商城买家、但不是音乐用户"这条路径的。
     */
    private void ensureUsers() {
        ensureUser(BUYER_ONE, "BUYER");
        ensureUser(BUYER_TWO, "BUYER");
        ensureUser(BUYER_SHOP_ONLY, "BUYER");
        ensureUser(ADMIN, "ADMIN");
    }

    private void ensureUser(String userId, String role) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_user WHERE user_id = ?", Integer.class, userId);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO shop_user (user_id, role, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userId, role);
        log.info("商城演示身份已补齐：{}（{}）", userId, role);
    }

    private boolean alreadyInitialized() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_product WHERE id = ?",
                Integer.class,
                PRODUCT_KEYBOARD);
        return count != null && count > 0;
    }

    private static int stockOf(String sku) {
        if (SKU_KEYBOARD.equals(sku)) {
            return KEYBOARD_STOCK;
        }
        if (SKU_MOUSE.equals(sku)) {
            return MOUSE_STOCK;
        }
        return CABLE_STOCK;
    }
}
