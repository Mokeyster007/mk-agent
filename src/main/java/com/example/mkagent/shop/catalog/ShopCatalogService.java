package com.example.mkagent.shop.catalog;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.api.ShopViews;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 商品发现与比较服务。
 *
 * <p>严格区分<strong>硬条件</strong>与<strong>软偏好</strong>：
 * <ul>
 *   <li>硬条件（预算上限/下限、是否无线、Mac 兼容、品类、是否有货、关键词）由服务端精确过滤，
 *       不满足的商品绝不冒充匹配返回；</li>
 *   <li>软偏好（如“最好安静”）仅在合规候选中排序加权，不影响合规性判定；</li>
 *   <li>无结果时如实返回空列表，并计算放宽哪一项硬条件可得到结果。</li>
 * </ul>
 */
@Service
public class ShopCatalogService {

    private final ShopProductRepository productRepository;

    public ShopCatalogService(ShopProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public record SearchCriteria(
            String keyword,
            String category,
            Long maxPriceCents,
            Long minPriceCents,
            Boolean wirelessOnly,
            Boolean macCompatible,
            Boolean preferSilent,
            Boolean inStockOnly
    ) {
        public SearchCriteria withoutMaxPrice() {
            return new SearchCriteria(keyword, category, null, minPriceCents,
                    wirelessOnly, macCompatible, preferSilent, inStockOnly);
        }

        public SearchCriteria withoutWireless() {
            return new SearchCriteria(keyword, category, maxPriceCents, minPriceCents,
                    null, macCompatible, preferSilent, inStockOnly);
        }

        public SearchCriteria withoutMacCompatible() {
            return new SearchCriteria(keyword, category, maxPriceCents, minPriceCents,
                    wirelessOnly, null, preferSilent, inStockOnly);
        }

        public SearchCriteria withoutCategory() {
            return new SearchCriteria(keyword, null, maxPriceCents, minPriceCents,
                    wirelessOnly, macCompatible, preferSilent, inStockOnly);
        }

        public SearchCriteria withoutInStockOnly() {
            return new SearchCriteria(keyword, category, maxPriceCents, minPriceCents,
                    wirelessOnly, macCompatible, preferSilent, null);
        }
    }

    public record SearchResult(
            List<ShopProduct> items,
            Map<String, Object> appliedHardFilters,
            List<String> softPreferences,
            List<String> relaxSuggestions
    ) {
    }

    public record ComparisonDimension(String label, List<String> values) {
    }

    public record ComparisonResult(
            List<ShopProduct> products,
            List<ComparisonDimension> dimensions
    ) {
    }

    public SearchResult search(SearchCriteria raw) {
        SearchCriteria criteria = normalize(raw);
        List<ShopProduct> active = productRepository.findActive();

        List<ShopProduct> matched = filterAndRank(active, criteria);
        List<String> relaxSuggestions = matched.isEmpty()
                ? buildRelaxSuggestions(active, criteria)
                : List.of();

        return new SearchResult(
                matched,
                describeHardFilters(criteria),
                describeSoftPreferences(criteria),
                relaxSuggestions
        );
    }

    /**
     * 按 SKU 或商品 ID 解析单款上架商品。
     */
    public Optional<ShopProduct> resolveActive(String skuOrId) {
        if (skuOrId == null || skuOrId.isBlank()) {
            return Optional.empty();
        }
        String trimmed = skuOrId.trim();
        Optional<ShopProduct> bySku = productRepository.findActiveBySku(trimmed);
        if (bySku.isPresent()) {
            return bySku;
        }
        try {
            long id = Long.parseLong(trimmed);
            return productRepository.findActiveById(id);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /**
     * 结构化商品比较（至少 2 款，最多 3 款），全部基于实时商品与库存表。
     */
    public ComparisonResult compare(List<String> keys) {
        if (keys == null || keys.size() < 2) {
            throw new BusinessException(400, "INVALID_COMPARE_INPUT", "请至少选择 2 款商品进行比较");
        }
        if (keys.size() > 3) {
            throw new BusinessException(400, "INVALID_COMPARE_INPUT", "单次最多比较 3 款商品");
        }

        List<ShopProduct> resolved = new ArrayList<>();
        for (String key : keys) {
            ShopProduct product = resolveActive(key)
                    .orElseThrow(() -> new BusinessException(
                            404, "PRODUCT_NOT_FOUND", "商品不存在或已下架：" + key));
            boolean duplicate = resolved.stream().anyMatch(p -> p.id() == product.id());
            if (!duplicate) {
                resolved.add(product);
            }
        }
        if (resolved.size() < 2) {
            throw new BusinessException(400, "INVALID_COMPARE_INPUT", "请选择 2 款不同的商品进行比较");
        }

        List<ComparisonDimension> dims = List.of(
                new ComparisonDimension("当前售价",
                        resolved.stream().map(p -> ShopViews.money(p.priceCents())).toList()),
                new ComparisonDimension("当前库存（发货时扣减）",
                        resolved.stream().map(p -> p.available() > 0 ? p.available() + " 件" : "暂时无货").toList()),
                new ComparisonDimension("连接方式",
                        resolved.stream().map(ShopProduct::connectivityLabel).toList()),
                new ComparisonDimension("Mac 兼容",
                        resolved.stream().map(ShopProduct::macCompatibleLabel).toList()),
                new ComparisonDimension("静音特性",
                        resolved.stream().map(ShopProduct::silentLabel).toList()),
                new ComparisonDimension("轴体/微动",
                        resolved.stream().map(ShopProduct::switchTypeLabel).toList()),
                new ComparisonDimension("续航/供电",
                        resolved.stream().map(ShopProduct::batteryLabel).toList()),
                new ComparisonDimension("重量",
                        resolved.stream().map(ShopProduct::weightLabel).toList())
        );

        return new ComparisonResult(resolved, dims);
    }

    private List<ShopProduct> filterAndRank(List<ShopProduct> source, SearchCriteria c) {
        List<ShopProduct> filtered = new ArrayList<>();
        for (ShopProduct p : source) {
            if (!matchesHardFilters(p, c)) {
                continue;
            }
            filtered.add(p);
        }

        Comparator<ShopProduct> cmp = Comparator.comparingLong(ShopProduct::priceCents)
                .thenComparingLong(ShopProduct::id);

        if (Boolean.TRUE.equals(c.preferSilent())) {
            cmp = Comparator.comparingInt((ShopProduct p) -> silentRank(p.silentFeature()))
                    .thenComparing(cmp);
        }
        // 有货优先排在无货前（当未启用 inStockOnly 硬条件时）
        cmp = Comparator.comparingInt((ShopProduct p) -> p.available() > 0 ? 0 : 1)
                .thenComparing(cmp);

        filtered.sort(cmp);
        return filtered;
    }

    private static int silentRank(Boolean silentFeature) {
        if (Boolean.TRUE.equals(silentFeature)) {
            return 0;
        }
        if (silentFeature == null) {
            return 1;
        }
        return 2;
    }

    private static boolean matchesHardFilters(ShopProduct p, SearchCriteria c) {
        if (c.category() != null && !c.category().equalsIgnoreCase(p.category())) {
            return false;
        }
        if (c.maxPriceCents() != null && p.priceCents() > c.maxPriceCents()) {
            return false;
        }
        if (c.minPriceCents() != null && p.priceCents() < c.minPriceCents()) {
            return false;
        }
        if (Boolean.TRUE.equals(c.wirelessOnly()) && !p.isWireless()) {
            return false;
        }
        if (Boolean.TRUE.equals(c.macCompatible()) && !Boolean.TRUE.equals(p.macCompatible())) {
            return false;
        }
        if (Boolean.TRUE.equals(c.inStockOnly()) && p.available() <= 0) {
            return false;
        }
        if (c.keyword() != null && !c.keyword().isBlank()) {
            String kw = c.keyword().toLowerCase(Locale.ROOT);
            String haystack = (safe(p.name()) + " " + safe(p.subtitle()) + " "
                    + safe(p.sku()) + " " + safe(p.switchType()) + " "
                    + p.categoryLabel() + " " + p.connectivityLabel()).toLowerCase(Locale.ROOT);
            if (!haystack.contains(kw)) {
                return false;
            }
        }
        return true;
    }

    private List<String> buildRelaxSuggestions(List<ShopProduct> active, SearchCriteria c) {
        List<String> suggestions = new ArrayList<>();
        if (c.maxPriceCents() != null) {
            List<ShopProduct> relaxed = filterAndRank(active, c.withoutMaxPrice());
            if (!relaxed.isEmpty()) {
                long lowest = relaxed.stream().mapToLong(ShopProduct::priceCents).min().orElse(0L);
                suggestions.add("放宽预算上限（符合其余条件的商品最低 " + ShopViews.money(lowest)
                        + "，共 " + relaxed.size() + " 款）");
            }
        }
        if (Boolean.TRUE.equals(c.wirelessOnly())) {
            List<ShopProduct> relaxed = filterAndRank(active, c.withoutWireless());
            if (!relaxed.isEmpty()) {
                suggestions.add("放宽“必须无线”条件（包含有线款式后有 " + relaxed.size() + " 款）");
            }
        }
        if (Boolean.TRUE.equals(c.macCompatible())) {
            List<ShopProduct> relaxed = filterAndRank(active, c.withoutMacCompatible());
            if (!relaxed.isEmpty()) {
                suggestions.add("放宽“必须兼容 Mac”条件（包含仅支持 Windows 款式后有 " + relaxed.size() + " 款）");
            }
        }
        if (c.category() != null) {
            List<ShopProduct> relaxed = filterAndRank(active, c.withoutCategory());
            if (!relaxed.isEmpty()) {
                suggestions.add("放宽品类限制（不限定品类后有 " + relaxed.size() + " 款）");
            }
        }
        if (Boolean.TRUE.equals(c.inStockOnly())) {
            List<ShopProduct> relaxed = filterAndRank(active, c.withoutInStockOnly());
            if (!relaxed.isEmpty()) {
                suggestions.add("包含暂时无货商品（共 " + relaxed.size() + " 款）");
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add("当前全部在售商品共 " + active.size() + " 款，可减少筛选条件或直接查看全部商品");
        }
        return suggestions;
    }

    private static SearchCriteria normalize(SearchCriteria raw) {
        if (raw == null) {
            return new SearchCriteria(null, null, null, null, null, null, null, null);
        }
        String category = normalizeCategory(raw.category());
        String keyword = raw.keyword() == null ? null : raw.keyword().trim();
        if (keyword != null && keyword.isEmpty()) {
            keyword = null;
        }
        // 避免关键词重复“键盘/鼠标”与 category 冲突或误伤
        if (category == null && keyword != null) {
            String detected = normalizeCategory(keyword);
            if (detected != null) {
                category = detected;
                keyword = null;
            }
        }
        return new SearchCriteria(
                keyword,
                category,
                raw.maxPriceCents(),
                raw.minPriceCents(),
                raw.wirelessOnly(),
                raw.macCompatible(),
                raw.preferSilent(),
                raw.inStockOnly()
        );
    }

    public static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("keyboard") || lower.equals("键盘") || lower.equals("机械键盘")) {
            return "KEYBOARD";
        }
        if (lower.equals("mouse") || lower.equals("鼠标")) {
            return "MOUSE";
        }
        if (lower.equals("accessory") || lower.equals("配件") || lower.equals("数据线")) {
            return "ACCESSORY";
        }
        return null;
    }

    private static Map<String, Object> describeHardFilters(SearchCriteria c) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (c.category() != null) {
            map.put("category", c.category());
        }
        if (c.maxPriceCents() != null) {
            map.put("maxPriceText", ShopViews.money(c.maxPriceCents()));
        }
        if (c.minPriceCents() != null) {
            map.put("minPriceText", ShopViews.money(c.minPriceCents()));
        }
        if (Boolean.TRUE.equals(c.wirelessOnly())) {
            map.put("wirelessOnly", true);
        }
        if (Boolean.TRUE.equals(c.macCompatible())) {
            map.put("macCompatible", true);
        }
        if (Boolean.TRUE.equals(c.inStockOnly())) {
            map.put("inStockOnly", true);
        }
        if (c.keyword() != null) {
            map.put("keyword", c.keyword());
        }
        return map;
    }

    private static List<String> describeSoftPreferences(SearchCriteria c) {
        List<String> prefs = new ArrayList<>();
        if (Boolean.TRUE.equals(c.preferSilent())) {
            prefs.add("优先静音/低噪（仅影响排序，不放宽硬条件）");
        }
        return prefs;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
