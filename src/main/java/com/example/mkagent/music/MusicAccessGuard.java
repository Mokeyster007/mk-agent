package com.example.mkagent.music;

import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.music.MusicRoleResolver.MusicRole;
import org.springframework.stereotype.Component;

/**
 * 音乐入口的访问控制（M2e）。
 *
 * 位置：控制器在**启动 Agent 之前**调用，所以被拒绝的请求不会：
 * 1. 占用并发许可；
 * 2. 消耗一次限流配额；
 * 3. 在 agent_run 里留下一条记录。
 *
 * 三条规则：
 * <ul>
 *   <li>缺 {@code X-User-Id} → 401（与 {@code /ai/manus/chat} 一致）；</li>
 *   <li>{@code music_user} 里没有这个人 → 403；</li>
 *   <li>管理入口要求角色为 ADMIN → 否则 403。</li>
 * </ul>
 *
 * 为什么不用 {@link UserContextHolder#getOrDefault()}：
 * 它会在缺头时返回 {@code anonymous}，让"未认证"悄悄变成一个合法身份，
 * 随后被 {@link MusicRoleResolver} 判成 UNKNOWN——虽然结果也是拒绝，
 * 但错误码会从 401 变成 403，前端无法区分"没登录"和"没权限"。
 * 这里用 {@code get()} + 显式判空，语义明确。
 *
 * 局限（见 docs/music-permissions.md）：当前没有真实认证，
 * {@code X-User-Id} 可以伪造。本类只保证"服务端不信任请求里声明的角色"，
 * 不保证"身份本身可信"。
 */
@Component
public class MusicAccessGuard {

    /**
     * 缺少身份时的提示语。
     *
     * 与 {@code AiController#requireUserId()} 的文案保持一致，
     * 前端按同一套规则处理 401。
     */
    public static final String MISSING_USER_MESSAGE =
            "缺少用户身份，请在请求头中携带 " + UserContextHolder.USER_ID_HEADER;

    /**
     * 用户不在 music_user 表里。
     */
    public static final String UNKNOWN_USER_MESSAGE =
            "该用户未开通音乐服务";

    /**
     * 非管理员访问管理入口。
     */
    public static final String NOT_ADMIN_MESSAGE =
            "无权访问音乐管理接口";

    private final MusicRoleResolver roleResolver;

    public MusicAccessGuard(MusicRoleResolver roleResolver) {
        this.roleResolver = roleResolver;
    }

    /**
     * 普通用户入口：任何在 music_user 表里的用户（USER 或 ADMIN）都可以进。
     *
     * 管理员也可以用普通入口——他作为用户同样有自己的歌单，
     * 只是管理入口不给他歌单工具（权限按**入口**收窄，不按人收窄）。
     *
     * @return 已校验的 userId
     * @throws BusinessException 401 缺身份 / 403 未知用户
     */
    public String requireUser() {
        String userId = requireHeader();
        requireKnown(userId);
        return userId;
    }

    /**
     * 管理入口：必须是 ADMIN。
     *
     * @return 已校验的 userId
     * @throws BusinessException 401 缺身份 / 403 未知用户或非管理员
     */
    public String requireAdmin() {
        String userId = requireHeader();

        if (roleResolver.resolve(userId) != MusicRole.ADMIN) {
            /*
             * 未知用户与非管理员合并成同一个错误：
             * 对调用方来说都是"这个入口你不能用"，
             * 分开只会多给一条"该 userId 是否存在"的探测信号。
             */
            throw new BusinessException(403, NOT_ADMIN_MESSAGE);
        }

        return userId;
    }

    private String requireHeader() {
        String userId = UserContextHolder.get();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(401, MISSING_USER_MESSAGE);
        }
        return userId;
    }

    private void requireKnown(String userId) {
        if (roleResolver.resolve(userId) == MusicRole.UNKNOWN) {
            throw new BusinessException(403, UNKNOWN_USER_MESSAGE);
        }
    }
}
