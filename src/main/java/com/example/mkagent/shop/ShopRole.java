package com.example.mkagent.shop;

/**
 * 商城角色。
 *
 * <p>只有三种，且 {@code UNKNOWN} 与 {@code BUYER} 必须分开：
 * 把"表里查不到"回落到 BUYER，等于"随便填一个 X-User-Id 就能下单"。
 * 现在没有真实认证，这条回落会把越权从"需要伪造身份"降级成"随便填一个"。
 */
public enum ShopRole {

    /** 买家：可以下单、只能读自己的订单。 */
    BUYER,

    /** 后台演示人员：可以发货、签收、核对库存流水。 */
    ADMIN,

    /** 表里没有这个身份 —— 视为无权限，不回落到 BUYER。 */
    UNKNOWN
}
