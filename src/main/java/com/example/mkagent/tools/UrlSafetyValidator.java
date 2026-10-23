package com.example.mkagent.tools;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * URL 安全校验器（SSRF 防护）。
 *
 * 校验规则：
 * 1. 只允许 http / https；
 * 2. 必须有 host；
 * 3. 解析出该 host 的全部 IP，任一命中下列任一条件即拒绝：
 *    - 回环地址（127.0.0.0/8、::1）
 *    - 链路本地地址（169.254.0.0/16、fe80::/10）
 *    - 私有地址（10/8、172.16/12、192.168/16）
 *    - 通配地址（0.0.0.0、::）
 *    - 组播地址（224/4、ff00::/8）
 *    - IPv6 唯一本地地址（fc00::/7）
 *    - 0.0.0.0/8、169.254.169.254（云元数据）、100.64.0.0/10（运营商级 NAT）
 *    - IPv4 映射的 IPv6 地址（::ffff:127.0.0.1）按 IPv4 规则再判一次
 *
 * 为什么必须解析全部地址：
 * 只做字符串黑名单时，攻击者可以用“解析到内网的域名”绕过；
 * 因此这里用 getAllByName 拿到所有 A/AAAA 记录，任意一条落在内网即拒绝。
 *
 * 已知残留风险（见 docs/SECURITY.md）：
 * 校验时解析的 IP 与真正建立连接时解析的 IP 可能不同（DNS rebinding）。
 * 本类只负责“校验”，不做连接固定；彻底解决需要自定义连接工厂固定已校验 IP。
 */
public class UrlSafetyValidator {

    /**
     * 域名解析器：抽成接口便于单元测试注入固定结果，避免测试联网。
     */
    @FunctionalInterface
    public interface HostResolver {

        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * 云厂商元数据服务地址（阿里云 / AWS 等常用）。
     */
    private static final String CLOUD_METADATA_IP = "169.254.169.254";

    private static final String LOCALHOST_NAME = "localhost";

    private final HostResolver hostResolver;

    public UrlSafetyValidator() {
        this(InetAddress::getAllByName);
    }

    public UrlSafetyValidator(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    /**
     * 校验 URL 是否允许抓取。
     *
     * @param url 待校验 URL
     * @return 解析后的 URI
     * @throws IllegalArgumentException 校验不通过
     */
    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 格式非法");
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("只允许抓取 HTTP 或 HTTPS 网页");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机地址");
        }

        /*
         * localhost 单独拦一次：
         * 即使解析器返回了非回环结果（异常配置 / hosts 文件），
         * 也不允许访问本机。
         */
        if (LOCALHOST_NAME.equals(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不允许抓取本机或保留地址");
        }

        validateHost(host);

        return uri;
    }

    /**
     * 校验主机名解析出的全部 IP 是否都在公网。
     */
    public void validateHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析主机地址");
        }

        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("无法解析主机地址");
        }

        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("不允许抓取本机或保留地址");
            }
        }
    }

    /**
     * 判断单个 IP 是否属于禁止访问的范围。
     */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }

        if (CLOUD_METADATA_IP.equals(address.getHostAddress())) {
            return true;
        }

        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] raw = address.getAddress();

        if (raw.length == 4) {
            return isBlockedIpv4(raw, 0);
        }

        if (raw.length == 16) {
            /*
             * IPv4 映射的 IPv6（::ffff:a.b.c.d）：
             * 某些解析路径会保留 IPv6 形态，
             * 必须先还原成 IPv4 再判，否则 127.0.0.1 会被漏掉。
             */
            if (isIpv4MappedIpv6(raw)) {
                return isBlockedIpv4(raw, 12);
            }

            // IPv6 唯一本地地址 fc00::/7（Java 的 isSiteLocalAddress 只认 fec0::/10）
            return (raw[0] & 0xFE) == 0xFC;
        }

        return false;
    }

    /**
     * IPv4 特殊网段判断（offset 指向 4 字节起始位置）。
     *
     * 这里把 IPv4 的保留网段写全，而不是依赖 InetAddress 的
     * isLoopbackAddress / isSiteLocalAddress：
     * IPv4 映射的 IPv6（::ffff:127.0.0.1）走的是 IPv6 实例，
     * 那些方法不会命中，必须回到这里按 IPv4 规则重新判断。
     */
    private static boolean isBlockedIpv4(byte[] raw, int offset) {
        int first = raw[offset] & 0xFF;
        int second = raw[offset + 1] & 0xFF;

        // 0.0.0.0/8：本网络，通常被用于“连接本机任意服务”
        if (first == 0) {
            return true;
        }

        // 10.0.0.0/8：私有地址
        if (first == 10) {
            return true;
        }

        // 127.0.0.0/8：回环地址
        if (first == 127) {
            return true;
        }

        // 169.254.0.0/16：链路本地，含云元数据 169.254.169.254
        if (first == 169 && second == 254) {
            return true;
        }

        // 172.16.0.0/12：私有地址
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }

        // 192.168.0.0/16：私有地址
        if (first == 192 && second == 168) {
            return true;
        }

        /*
         * 100.64.0.0/10：运营商级 NAT（CGNAT）。
         * 阿里云元数据地址 100.100.100.200 落在此段内。
         */
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }

        // 224.0.0.0/4 组播、240.0.0.0/4 保留（含 255.255.255.255 广播）
        return first >= 224;
    }

    /**
     * 判断是否为 IPv4 映射的 IPv6 地址（前 10 字节 0 + 0xFF 0xFF）。
     */
    private static boolean isIpv4MappedIpv6(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
    }
}
