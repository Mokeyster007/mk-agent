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
 * 商城种子数据（商品 + 结构化规格 + 库存 + 演示身份）。
 *
 * <p>幂等策略：
 * <ul>
 *   <li>保留 A0 原有的 2001/2002/2003 商品 ID、SKU、价格与初始库存口径不变；</li>
 *   <li>对已有库增量补齐新增种子商品（2004~2008）与缺失的规格列，绝不覆盖已有库存或已修改的价格；</li>
 *   <li>演示身份包含纯商城身份（{@code shop-user-1} / {@code shop-admin-1}），
 *       同时兼容既有测试与存量 Demo 库里的身份标识，不依赖任何音乐表。</li>
 * </ul>
 */
@Component
@Profile("!prod")
@Order(1)
public class ShopSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShopSeedInitializer.class);

    public static final long PRODUCT_KEYBOARD = 2001L;
    public static final long PRODUCT_MOUSE = 2002L;
    public static final long PRODUCT_CABLE = 2003L;
    public static final long PRODUCT_KEYBOARD_AIR75 = 2004L;
    public static final long PRODUCT_KEYBOARD_B68 = 2005L;
    public static final long PRODUCT_KEYBOARD_G98 = 2006L;
    public static final long PRODUCT_KEYBOARD_PRO1 = 2007L;
    public static final long PRODUCT_MOUSE_M5 = 2008L;

    public static final String SKU_KEYBOARD = "SKU-KEYBOARD-K87";
    public static final String SKU_MOUSE = "SKU-MOUSE-M2";
    public static final String SKU_CABLE = "SKU-CABLE-C1";
    public static final String SKU_KEYBOARD_AIR75 = "SKU-KEYBOARD-A75";
    public static final String SKU_KEYBOARD_B68 = "SKU-KEYBOARD-B68";
    public static final String SKU_KEYBOARD_G98 = "SKU-KEYBOARD-G98";
    public static final String SKU_KEYBOARD_PRO1 = "SKU-KEYBOARD-PRO1";
    public static final String SKU_MOUSE_M5 = "SKU-MOUSE-M5";

    public static final int KEYBOARD_STOCK = 10;
    public static final int MOUSE_STOCK = 5;
    public static final int CABLE_STOCK = 0;

    /** 纯商城默认买家与后台身份（不依赖音乐身份）。 */
    public static final String BUYER_SHOP_ONLY = "shop-user-1";
    public static final String ADMIN_SHOP_ONLY = "shop-admin-1";

    /** 兼容已有 Demo 库与 A0–C1 回归测试的既有身份。 */
    public static final String BUYER_ONE = "music-user-1";
    public static final String BUYER_TWO = "music-user-2";
    public static final String ADMIN = "music-admin-1";

    private record SeedProduct(
            long id,
            String sku,
            String name,
            String subtitle,
            long priceCents,
            int initialStock,
            String category,
            String connectivity,
            Boolean macCompatible,
            Boolean silentFeature,
            String switchType,
            String batteryInfo,
            Integer weightGrams
    ) {
    }

    private static final List<SeedProduct> PRODUCTS = List.of(
            new SeedProduct(PRODUCT_KEYBOARD, SKU_KEYBOARD, "机械键盘 K87",
                    "87 键 · 茶轴 · USB-C 有线 · 白色背光", 29900L, KEYBOARD_STOCK,
                    "KEYBOARD", "WIRED", true, false, "茶轴", null, 820),
            new SeedProduct(PRODUCT_MOUSE, SKU_MOUSE, "静音鼠标 M2",
                    "无线 2.4G · 静音微动 · 兼容 Mac/Win · 可充电", 12900L, MOUSE_STOCK,
                    "MOUSE", "WIRELESS_24G", true, true, "静音微动", "可充电约 30 天", 88),
            new SeedProduct(PRODUCT_CABLE, SKU_CABLE, "USB-C 数据线 C1",
                    "1.5 米 · 100W 快充（演示售罄）", 1900L, CABLE_STOCK,
                    "ACCESSORY", "WIRED", true, null, null, null, 45),
            new SeedProduct(PRODUCT_KEYBOARD_AIR75, SKU_KEYBOARD_AIR75, "无线轻音机械键盘 Air75",
                    "75% 紧凑 · 三模无线 · Mac/Win 键位切换 · 静音红轴", 36900L, 8,
                    "KEYBOARD", "TRI_MODE", true, true, "静音红轴", "3000mAh 约 35 天", 680),
            new SeedProduct(PRODUCT_KEYBOARD_B68, SKU_KEYBOARD_B68, "紧凑蓝牙键盘 B68",
                    "68 键便携 · 蓝牙 5.0 无线 · 兼容 Mac · 青轴清脆段落", 25900L, 6,
                    "KEYBOARD", "BLUETOOTH", true, false, "青轴", "2000mAh 约 25 天", 560),
            new SeedProduct(PRODUCT_KEYBOARD_G98, SKU_KEYBOARD_G98, "无线游戏键盘 G98",
                    "98 键配列 · 2.4G 无线 · 仅支持 Windows · 快银轴", 38900L, 4,
                    "KEYBOARD", "WIRELESS_24G", false, false, "快银轴", "4000mAh 约 45 天", 980),
            new SeedProduct(PRODUCT_KEYBOARD_PRO1, SKU_KEYBOARD_PRO1, "铝合金无线客制化键盘 Pro1",
                    "CNC 铝坨坨 · 三模无线 · 兼容 Mac · 静音月白轴", 59900L, 5,
                    "KEYBOARD", "TRI_MODE", true, true, "静音月白轴", "4000mAh 约 50 天", 1150),
            new SeedProduct(PRODUCT_MOUSE_M5, SKU_MOUSE_M5, "双模办公鼠标 M5",
                    "蓝牙无线 · 兼容 Mac · 续航 12 个月（噪声参数未标注）", 19900L, 7,
                    "MOUSE", "BLUETOOTH", true, null, null, "单节 AA 电池约 12 个月", 96)
    );

    public static final int SEED_PRODUCT_COUNT = PRODUCTS.size();

    private final JdbcTemplate jdbcTemplate;

    public ShopSeedInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureUsers();

        int inserted = 0;
        for (SeedProduct product : PRODUCTS) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM shop_product WHERE id = ?",
                    Integer.class,
                    product.id());
            if (exists == null || exists == 0) {
                jdbcTemplate.update("""
                        INSERT INTO shop_product
                            (id, sku, name, subtitle, price_cents, status,
                             category, connectivity, mac_compatible, silent_feature,
                             switch_type, battery_info, weight_grams, created_at)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                        product.id(), product.sku(), product.name(), product.subtitle(),
                        product.priceCents(), product.category(), product.connectivity(),
                        product.macCompatible(), product.silentFeature(),
                        product.switchType(), product.batteryInfo(), product.weightGrams());

                ensureInventoryRow(product);
                inserted++;
            } else {
                // 存量库平滑升级：仅当 category 仍为空时补齐结构化规格，不覆盖价格与库存。
                jdbcTemplate.update("""
                        UPDATE shop_product
                           SET category = ?, connectivity = ?, mac_compatible = ?,
                               silent_feature = ?, switch_type = ?, battery_info = ?,
                               weight_grams = ?
                         WHERE id = ? AND category IS NULL
                        """,
                        product.category(), product.connectivity(), product.macCompatible(),
                        product.silentFeature(), product.switchType(), product.batteryInfo(),
                        product.weightGrams(), product.id());
            }
        }

        if (inserted > 0) {
            log.info("商城种子数据已补齐：新增 {} 款商品（共 {} 款），演示买家 {} / {} / {}，后台 {} / {}",
                    inserted, PRODUCTS.size(), BUYER_SHOP_ONLY, BUYER_ONE, BUYER_TWO,
                    ADMIN_SHOP_ONLY, ADMIN);
        }
    }

    private void ensureInventoryRow(SeedProduct product) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_inventory_balance WHERE sku = ?",
                Integer.class,
                product.sku());
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO shop_inventory_balance (sku, product_id, available, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, product.sku(), product.id(), product.initialStock());
    }

    private void ensureUsers() {
        ensureUser(BUYER_SHOP_ONLY, "BUYER");
        ensureUser(ADMIN_SHOP_ONLY, "ADMIN");
        ensureUser(BUYER_ONE, "BUYER");
        ensureUser(BUYER_TWO, "BUYER");
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
}
