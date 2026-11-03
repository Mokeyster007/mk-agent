package com.example.mkagent.approval;

/**
 * 恢复前的一致性校验（批次 2）。
 *
 * 校验的是"持久化记录之间、记录与当前配置之间"是否仍然自洽，
 * 不是"用户有没有权限"——权限由请求级归属校验负责（跨用户直接 404，
 * 绝不能因为别人来访问就把审批置为 INVALIDATED）。
 *
 * 所有失败都**失败关闭**：不执行 pending round，也不"继续下一轮"。
 */
public final class CheckpointValidator {

    /**
     * 校验结论。
     *
     * @param ok   是否通过
     * @param code 失败原因码（落 agent_run.error_code）
     */
    public record Result(boolean ok, String code) {

        static final Result PASS = new Result(true, null);

        static Result fail(String code) {
            return new Result(false, code);
        }
    }

    private CheckpointValidator() {
    }

    /**
     * @param record               审批记录
     * @param checkpointJson       检查点原文
     * @param currentConfigVersion 当前 Agent 的配置指纹
     * @param currentWhitelistHash 当前 Agent 的白名单哈希
     */
    public static Result validate(
            ApprovalRecord record,
            String checkpointJson,
            String currentConfigVersion,
            String currentWhitelistHash
    ) {
        if (checkpointJson == null || checkpointJson.isBlank()) {
            return Result.fail("CHECKPOINT_MISSING");
        }

        ApprovalCheckpoint checkpoint;
        try {
            checkpoint = CheckpointCodec.decode(checkpointJson);
        } catch (RuntimeException e) {
            // 结构坏掉 / 版本不认识：一律当作损坏，不尝试"尽力兼容"
            return Result.fail("CHECKPOINT_CORRUPT");
        }

        /*
         * 归属一致性：审批与检查点必须指向同一个 run、同一个用户。
         * 这是"记录之间"的不一致，属于数据损坏，与跨用户请求无关。
         */
        if (!record.runId().equals(checkpoint.runId())
                || !record.userId().equals(checkpoint.userId())) {
            return Result.fail("CHECKPOINT_OWNER_MISMATCH");
        }

        if (checkpoint.schemaVersion() != ApprovalCheckpoint.CURRENT_SCHEMA_VERSION) {
            return Result.fail("CHECKPOINT_SCHEMA_MISMATCH");
        }

        if (currentConfigVersion == null
                || !currentConfigVersion.equals(checkpoint.configVersion())) {
            return Result.fail("CHECKPOINT_CONFIG_MISMATCH");
        }

        if (currentWhitelistHash == null
                || !currentWhitelistHash.equals(checkpoint.whitelistHash())) {
            return Result.fail("CHECKPOINT_WHITELIST_MISMATCH");
        }

        /*
         * 整轮参数哈希：批准即绑定这组参数。
         * 记录上的 args_hash 是暂停时算的，检查点里的调用是执行依据，
         * 两者必须一致——否则等于"批准了 A，执行了 B"。
         */
        String actualHash = ApprovalHashes.roundHash(checkpoint.pendingToolCalls());
        if (!actualHash.equals(record.argsHash())) {
            return Result.fail("CHECKPOINT_ARGS_MISMATCH");
        }

        return Result.PASS;
    }
}
