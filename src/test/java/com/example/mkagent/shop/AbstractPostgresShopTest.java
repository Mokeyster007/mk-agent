package com.example.mkagent.shop;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 真实 PostgreSQL 回归的共享容器基类：用 Docker CLI 起一个**一次性** PG16 实例。
 *
 * <h2>为什么不用 Testcontainers</h2>
 * 本机 Docker Engine 是 29.4.3，其**最低 API 版本为 1.40**；
 * 而 Testcontainers 1.20.6 / 1.21.3 内置的 Docker 客户端固定使用 API 1.32，
 * 握手直接被拒：
 *
 * <pre>
 * NpipeSocketClientProviderStrategy: failed with exception BadRequestException
 *   (Status 400: {... "ServerVersion":"" ...})
 * </pre>
 *
 * 用 CLI 复现同一条请求可以确认这不是权限或端点问题：
 * <pre>
 * docker -H npipe:////./pipe/dockerDesktopLinuxEngine info            → 29.4.3（正常）
 * DOCKER_API_VERSION=1.32 docker ... info                            → 400 Bad Request
 * DOCKER_API_VERSION=1.44 docker ... info                            → 29.4.3（正常）
 * </pre>
 * Testcontainers 也没有暴露"指定 API 版本"的开关，升级到 1.21.3 仍是同一个 400。
 * 因此这里退一步：**用 Docker CLI 起容器，用 @DynamicPropertySource 接线**。
 * 隔离性与可清理性和 Testcontainers 一致（独立容器、随机端口、随机口令、
 * JVM 退出即删），只是少了自动发现。
 *
 * <h2>镜像</h2>
 * {@code pgvector/pgvector:pg16}，与仓库 {@code docker-compose.yml} 完全一致。
 * 实际服务端版本由 {@code SELECT version()} 记录，不靠标签猜。
 *
 * <h2>清理</h2>
 * 容器名带随机后缀，并打了 {@code mkagent.a0pg=1} 标签；
 * JVM 正常退出时由 shutdown hook 删除。异常中断时手工清理：
 * <pre>
 * docker ps -aq --filter label=mkagent.a0pg | xargs -r docker rm -f
 * </pre>
 */
abstract class AbstractPostgresShopTest {

    /** 与 docker-compose.yml 一致的镜像标签；主版本固定为 16。 */
    static final String POSTGRES_IMAGE = "pgvector/pgvector:pg16";

    private static final String LABEL = "mkagent.a0pg=1";

    /** 一次性容器：随机库名 / 用户名 / 口令，用完即删，不复用任何既有实例。 */
    private static final String DATABASE = "mk_agent_a0pg";

    private static final String USER = "mk_agent_a0pg";

    private static final String PASSWORD = "pg-" + UUID.randomUUID().toString().replace("-", "");

    private static final String CONTAINER_NAME =
            "mk-agent-a0pg-" + UUID.randomUUID().toString().substring(0, 8);

    /** Docker CLI 绝对路径优先（PATH 可能含不可编码条目，见 pom 的 pg profile）。 */
    private static final String DOCKER_CLI = "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe";

    private static int hostPort;

    private static String startupFailure;

    static {
        try {
            startContainer();
        } catch (RuntimeException e) {
            // 记下来，让每个用例以可读信息失败，而不是一个裸的 ExceptionInInitializerError
            startupFailure = e.getMessage();
        }
    }

    protected AbstractPostgresShopTest() {
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        if (startupFailure != null) {
            throw new IllegalStateException("PostgreSQL 容器未就绪：" + startupFailure);
        }

        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://127.0.0.1:" + hostPort + "/" + DATABASE);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        /*
         * 必须显式覆盖驱动：application-test.yml 里写死了 org.h2.Driver，
         * 只改 url 会让 Spring 用 H2 驱动去连 PG，报"驱动不支持该 URL"。
         */
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static void startContainer() {
        run(List.of(docker(), "run", "-d", "--rm",
                "--name", CONTAINER_NAME,
                "--label", LABEL,
                "-p", "127.0.0.1::5432",
                "-e", "POSTGRES_USER=" + USER,
                "-e", "POSTGRES_PASSWORD=" + PASSWORD,
                "-e", "POSTGRES_DB=" + DATABASE,
                POSTGRES_IMAGE));

        hostPort = resolveHostPort();
        awaitReady();
        Runtime.getRuntime().addShutdownHook(new Thread(AbstractPostgresShopTest::removeContainer,
                "a0pg-cleanup"));

        System.out.println("[pg-setup] container=" + CONTAINER_NAME
                + " image=" + POSTGRES_IMAGE
                + " host=127.0.0.1:" + hostPort
                + " db=" + DATABASE);
    }

    /** {@code docker port <name> 5432/tcp} → {@code 127.0.0.1:PORT}。 */
    private static int resolveHostPort() {
        String output = run(List.of(docker(), "port", CONTAINER_NAME, "5432/tcp"));
        String last = output.strip().lines().reduce((a, b) -> b).orElse("");
        int colon = last.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalStateException("无法解析容器端口映射：" + output);
        }
        return Integer.parseInt(last.substring(colon + 1).trim());
    }

    /** 轮询到能真正建立 JDBC 连接为止（容器起来 ≠ PostgreSQL 可接受连接）。 */
    private static void awaitReady() {
        String url = "jdbc:postgresql://127.0.0.1:" + hostPort + "/" + DATABASE;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        Exception last = null;

        while (System.nanoTime() < deadline) {
            try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD)) {
                if (connection.isValid(2)) {
                    return;
                }
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 PostgreSQL 就绪时被中断", interrupted);
                }
            }
        }

        throw new IllegalStateException("PostgreSQL 在 90 秒内未就绪：" + last);
    }

    private static void removeContainer() {
        try {
            run(List.of(docker(), "rm", "-f", CONTAINER_NAME));
            System.out.println("[pg-cleanup] removed " + CONTAINER_NAME);
        } catch (RuntimeException e) {
            System.out.println("[pg-cleanup] 未能删除 " + CONTAINER_NAME
                    + "，请手工执行：docker rm -f " + CONTAINER_NAME);
        }
    }

    private static String docker() {
        return new java.io.File(DOCKER_CLI).isFile() ? DOCKER_CLI : "docker";
    }

    /** 跑一条命令，返回 stdout；非 0 退出即抛，附带 stderr。 */
    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("命令超时：" + command.get(0) + " " + command.get(1));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("命令失败（exit=" + process.exitValue() + "）："
                        + String.join(" ", command) + "\n" + stderr.strip());
            }
            return stdout;
        } catch (IOException e) {
            throw new IllegalStateException("无法执行 " + command.get(0)
                    + "：请确认 Docker CLI 可用（可用 MKAGENT 环境变量或 -Ddocker.cli 指定）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令被中断：" + command.get(0), e);
        }
    }
}
