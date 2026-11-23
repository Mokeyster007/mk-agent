package com.example.mkagent.shop.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可售商品视图（商品 + 结构化规格 + 当前可售库存）。
 *
 * <p>缺失规格统一保持 {@code null}，对外展示为“未知”，绝不臆测或伪造规格依据。
 */
public record ShopProduct(
        long id,
        String sku,
        String name,
        String subtitle,
        long priceCents,
        int available,
        String status,
        String category,
        String connectivity,
        Boolean macCompatible,
        Boolean silentFeature,
        String switchType,
        String batteryInfo,
        Integer weightGrams
) {

    public boolean purchasable() {
        return "ACTIVE".equals(status);
    }

    public boolean isWireless() {
        if (connectivity == null) {
            return false;
        }
        return switch (connectivity) {
            case "WIRELESS_24G", "BLUETOOTH", "TRI_MODE" -> true;
            default -> false;
        };
    }

    public String categoryLabel() {
        if (category == null || category.isBlank()) {
            return "未知";
        }
        return switch (category) {
            case "KEYBOARD" -> "键盘";
            case "MOUSE" -> "鼠标";
            case "ACCESSORY" -> "配件";
            default -> category;
        };
    }

    public String connectivityLabel() {
        if (connectivity == null || connectivity.isBlank()) {
            return "未知";
        }
        return switch (connectivity) {
            case "WIRED" -> "有线连接";
            case "WIRELESS_24G" -> "无线 2.4G";
            case "BLUETOOTH" -> "蓝牙无线";
            case "TRI_MODE" -> "三模无线（蓝牙 / 2.4G / 有线）";
            default -> connectivity;
        };
    }

    public String macCompatibleLabel() {
        if (macCompatible == null) {
            return "未知";
        }
        return macCompatible ? "支持 Mac" : "不支持 Mac（仅 Windows）";
    }

    public String silentLabel() {
        if (silentFeature == null) {
            return "未知";
        }
        return silentFeature ? "静音/低噪设计" : "非静音（常规敲击声）";
    }

    public String switchTypeLabel() {
        return (switchType == null || switchType.isBlank()) ? "未知" : switchType;
    }

    public String batteryLabel() {
        return (batteryInfo == null || batteryInfo.isBlank()) ? "未知" : batteryInfo;
    }

    public String weightLabel() {
        return weightGrams == null ? "未知" : weightGrams + "g";
    }

    /** 可核对规格键值表（供商品卡、比较表和 Agent 工具复用）。 */
    public Map<String, String> verifiableSpecs() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("品类", categoryLabel());
        map.put("连接方式", connectivityLabel());
        map.put("Mac 兼容", macCompatibleLabel());
        map.put("静音特性", silentLabel());
        map.put("轴体/微动", switchTypeLabel());
        map.put("续航/供电", batteryLabel());
        map.put("重量", weightLabel());
        return map;
    }
}
