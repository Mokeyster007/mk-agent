package com.example.mkagent.tools;

import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UrlSafetyValidator 单元测试（SSRF 防护）。
 *
 * 全部用例使用注入的 HostResolver 返回固定 IP，
 * 不触发任何真实 DNS 查询，也不发起网络连接。
 */
class UrlSafetyValidatorTest {

    private static final String TEST_HOST = "example.com";

    @Test
    void blocksPrivateAndReservedIpv4Addresses() {
        List<String> blockedIps = List.of(
                "10.0.0.1",        // 10.0.0.0/8
                "172.16.0.1",      // 172.16.0.0/12 起始
                "172.31.255.255",  // 172.16.0.0/12 结束
                "192.168.1.1",     // 192.168.0.0/16
                "127.0.0.1",       // 回环
                "169.254.169.254", // 云元数据
                "0.0.0.0",         // 通配地址
                "0.1.2.3",         // 0.0.0.0/8
                "100.64.0.1",      // 100.64.0.0/10 起始
                "100.100.100.200", // 阿里云元数据（CGNAT 段）
                "100.127.255.255"  // 100.64.0.0/10 结束
        );

        for (String ip : blockedIps) {
            UrlSafetyValidator validator = validatorResolvingTo(ip);

            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> validator.validate("http://" + TEST_HOST + "/path"),
                    "必须拒绝内网/保留地址：" + ip
            );

            assertTrue(
                    e.getMessage().contains("不允许抓取本机或保留地址"),
                    "拒绝原因应明确：" + ip
            );
        }
    }

    @Test
    void blocksIpv6LoopbackAndUniqueLocalAddresses() {
        for (String ip : List.of("::1", "fd00::1", "fc00::1", "fe80::1")) {
            UrlSafetyValidator validator = validatorResolvingTo(ip);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> validator.validate("http://" + TEST_HOST + "/"),
                    "必须拒绝 IPv6 回环 / 唯一本地 / 链路本地地址：" + ip
            );
        }
    }

    @Test
    void blocksIpv4MappedIpv6Address() throws UnknownHostException {
        /*
         * Java 对 ::ffff:127.0.0.1 字面量通常会直接折叠成 Inet4Address，
         * 因此这里手工构造真正的 Inet6Address，
         * 专门验证“映射地址必须还原成 IPv4 再判”这条逻辑。
         */
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = 127;
        mapped[13] = 0;
        mapped[14] = 0;
        mapped[15] = 1;

        InetAddress address = Inet6Address.getByAddress(null, mapped, -1);

        assertTrue(
                UrlSafetyValidator.isBlockedAddress(address),
                "::ffff:127.0.0.1 必须被识别为回环地址"
        );

        UrlSafetyValidator validator = new UrlSafetyValidator(
                host -> new InetAddress[]{address}
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate("http://" + TEST_HOST + "/")
        );
    }

    @Test
    void rejectsNonHttpSchemes() {
        UrlSafetyValidator validator = validatorResolvingTo("93.184.216.34");

        for (String url : List.of(
                "file:///etc/passwd",
                "ftp://example.com/a",
                "gopher://example.com/",
                "jar:http://example.com/a.jar!/"
        )) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> validator.validate(url),
                    "必须拒绝非 HTTP 协议：" + url
            );
            assertTrue(e.getMessage().contains("只允许抓取 HTTP 或 HTTPS 网页"));
        }
    }

    @Test
    void rejectsUrlWithoutHost() {
        UrlSafetyValidator validator = validatorResolvingTo("93.184.216.34");

        for (String url : List.of("http:///path", "https:///")) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> validator.validate(url),
                    "必须拒绝缺少 host 的 URL：" + url
            );
            assertTrue(e.getMessage().contains("缺少主机地址"));
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate("   ")
        );
    }

    @Test
    void rejectsLocalhostEvenWhenResolverReturnsPublicIp() {
        UrlSafetyValidator validator = validatorResolvingTo("93.184.216.34");

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate("http://localhost:8080/admin"),
                "localhost 必须无条件拒绝，不依赖解析结果"
        );
    }

    @Test
    void rejectsWhenAnyResolvedAddressIsInternal() {
        /*
         * 混合解析结果：只要有一条落在外网之外就拒绝。
         * 这是防“DNS 多记录绕过”的关键用例。
         */
        UrlSafetyValidator validator = new UrlSafetyValidator(
                host -> new InetAddress[]{
                        InetAddress.getByName("93.184.216.34"),
                        InetAddress.getByName("10.0.0.1")
                }
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate("http://" + TEST_HOST + "/")
        );
    }

    @Test
    void rejectsWhenResolutionIsEmpty() {
        UrlSafetyValidator validator = new UrlSafetyValidator(
                host -> new InetAddress[0]
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate("http://" + TEST_HOST + "/")
        );
    }

    @Test
    void rejectsUnresolvableHost() {
        UrlSafetyValidator validator = new UrlSafetyValidator(host -> {
            throw new UnknownHostException(host);
        });

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate("http://" + TEST_HOST + "/")
        );
        assertTrue(e.getMessage().contains("无法解析主机地址"));
    }

    @Test
    void allowsPublicAddress() {
        UrlSafetyValidator validator = validatorResolvingTo("93.184.216.34");

        URI uri = validator.validate("https://example.com/a/b?c=1");

        assertEquals("example.com", uri.getHost());
        assertEquals("https", uri.getScheme());
    }

    @Test
    void blockedAddressDetectionIsDirectlyTestable() throws UnknownHostException {
        assertTrue(UrlSafetyValidator.isBlockedAddress(
                InetAddress.getByName("169.254.169.254")));
        assertTrue(UrlSafetyValidator.isBlockedAddress(
                InetAddress.getByName("fd12:3456::1")));
        assertFalse(UrlSafetyValidator.isBlockedAddress(
                InetAddress.getByName("93.184.216.34")));
        assertFalse(UrlSafetyValidator.isBlockedAddress(
                InetAddress.getByName("2400:3200::1")));
    }

    /**
     * 构造一个“无论 host 是什么都解析为指定 IP”的校验器。
     */
    private static UrlSafetyValidator validatorResolvingTo(String ip) {
        List<InetAddress> addresses = new ArrayList<>(1);
        try {
            addresses.add(InetAddress.getByName(ip));
        } catch (UnknownHostException e) {
            throw new IllegalStateException("测试 IP 字面量非法：" + ip, e);
        }
        return new UrlSafetyValidator(host -> addresses.toArray(new InetAddress[0]));
    }
}
