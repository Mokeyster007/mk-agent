package com.example.mkagent.shop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商城角色解析：userId → 角色。
 *
 * <p>可信来源只有一个：{@code shop_user} 表。
 * 请求参数、请求头里声明的角色、提示词里的"我是管理员"一律不算数。
 *
 * <p>为什么不复用 {@code music_user}：
 * 音乐角色与商城角色是两套业务授权。同一个人"能听歌"不代表"能发货"，
 * 复用一张表会让一次音乐侧的授权变更意外扩大商城权限。
 * 同一个 userId 在两张表里可以有不同角色，这是有意为之。
 */
@Component
public class ShopRoleResolver {

    /** 角色字面量（与 {@code ShopSeedInitializer} 写入的值一致）。 */
    public static final String ROLE_ADMIN = "ADMIN";

    public static final String ROLE_BUYER = "BUYER";

    private final JdbcTemplate jdbcTemplate;

    public ShopRoleResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按 userId 解析角色；查不到返回 {@link ShopRole#UNKNOWN}。
     *
     * @param userId 服务端固化的用户标识，允许为 null
     */
    public ShopRole resolve(String userId) {
        if (userId == null || userId.isBlank()) {
            return ShopRole.UNKNOWN;
        }

        /*
         * 只取一列，且用 query 而不是 queryForObject：
         * 查不到时返回空列表，比捕获 EmptyResultDataAccessException 更好读。
         */
        var roles = jdbcTemplate.query(
                "SELECT role FROM shop_user WHERE user_id = ?",
                (rs, rowNum) -> rs.getString(1),
                userId.trim());

        if (roles.isEmpty()) {
            return ShopRole.UNKNOWN;
        }

        return isAdminRole(roles.get(0)) ? ShopRole.ADMIN : ShopRole.BUYER;
    }

    /**
     * 角色字符串是否等价于 ADMIN（容忍大小写与空格）。
     *
     * <p>角色列是 VARCHAR 且只有 CHECK 约束兜底，读取侧仍然要容忍脏值；
     * 除 ADMIN 以外的任何值都按 BUYER 处理（最小权限，不会误升权）。
     */
    static boolean isAdminRole(String role) {
        return role != null && ROLE_ADMIN.equalsIgnoreCase(role.trim());
    }
}
