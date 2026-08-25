package com.example.mkagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试专用确定性库存查询工具。
 *
 * 注意：
 * 1. 仅存在于 src/test 测试目录，不参与生产代码编译，
 *    也不会被 Spring 组件扫描注册（本类没有任何 Spring 注解）。
 * 2. 由集成测试的 @TestConfiguration 显式注册为 Bean，
 *    只在测试上下文存活，不会进入生产环境。
 * 3. 不访问真实数据库、外部 API 或网络，返回固定可预测的数据。
 * 4. 记录调用次数与执行线程名，用于验证：
 *    - 工具确实被真实执行（而不是模型凭空编造数字）
 *    - 工具在 agentExecutor 后台线程上执行
 */
public class DemoInventoryTool {

    /** 工具被真实执行的次数。 */
    private final AtomicInteger callCount = new AtomicInteger(0);

    /** 每次工具执行时所在的线程名，用于验证后台线程执行。 */
    private final List<String> threadNames = new CopyOnWriteArrayList<>();

    /**
     * 查询商品库存的唯一真实来源。
     *
     * 工具描述明确约束模型：
     * 查询库存时必须调用此工具，禁止凭空猜测库存数量；
     * 工具返回的数据才是库存真实来源。
     */
    @Tool(
            name = "demo_inventory_check",
            description = """
                    查询商品库存的唯一真实来源。

                    当用户要求查询、确认或核实某个 SKU 的库存数量或发货状态时，
                    必须调用此工具，禁止凭空猜测库存数量或状态。

                    工具返回的数据才是库存真实来源，
                    回答库存问题时必须直接引用本工具的返回结果。
                    """
    )
    public String queryInventory(
            @ToolParam(description = "商品 SKU 编号，例如 MK-2026-001")
            String sku
    ) {
        callCount.incrementAndGet();
        threadNames.add(Thread.currentThread().getName());

        return switch (sku) {
            case "MK-2026-001" -> "MK-2026-001 -> 库存数量：17，状态：可发货";
            case "MK-2026-002" -> "MK-2026-002 -> 库存数量：0，状态：缺货";
            default -> "未找到该 SKU：" + sku;
        };
    }

    public int getCallCount() {
        return callCount.get();
    }

    public List<String> getThreadNames() {
        return List.copyOf(threadNames);
    }
}
