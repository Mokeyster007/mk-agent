package com.example.mkagent.music;

import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.music.MusicRoleResolver.MusicRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 音乐入口访问控制（纯单元，角色解析用桩）。
 *
 * 与 MusicEndpointAccessControlTest 的分工：
 * 那个类走真实 HTTP + 真实 music_user 表，证明"接口确实挂了这层"；
 * 这个类穷举 6 种组合（有/无身份 × 三种角色），证明"判定本身是对的"。
 * 两者缺一：只有前者时，某个分支可能刚好没被覆盖；只有后者时，
 * 可能压根没人调用这个 Guard。
 */
class MusicAccessGuardTest {

    private final MusicRoleResolver roleResolver = mock(MusicRoleResolver.class);

    private final MusicAccessGuard guard = new MusicAccessGuard(roleResolver);

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("缺 X-User-Id：401（不是 403，前端要能区分「没登录」与「没权限」）")
    void missingHeaderIs401() {
        BusinessException error = assertThrows(BusinessException.class,
                guard::requireUser);

        assertEquals(401, error.getHttpStatus());
        assertEquals(MusicAccessGuard.MISSING_USER_MESSAGE, error.getMessage());
    }

    @Test
    @DisplayName("缺 X-User-Id：管理入口同样 401，而不是先判角色")
    void missingHeaderIs401OnAdminEntryToo() {
        BusinessException error = assertThrows(BusinessException.class,
                guard::requireAdmin);

        assertEquals(401, error.getHttpStatus());
    }

    @Test
    @DisplayName("未知用户：403")
    void unknownUserIs403() {
        UserContextHolder.set("ghost");
        when(roleResolver.resolve(any())).thenReturn(MusicRole.UNKNOWN);

        BusinessException error = assertThrows(BusinessException.class,
                guard::requireUser);

        assertEquals(403, error.getHttpStatus());
        assertEquals(MusicAccessGuard.UNKNOWN_USER_MESSAGE, error.getMessage());
    }

    @Test
    @DisplayName("普通用户可以进普通入口")
    void userCanEnterUserEntry() {
        UserContextHolder.set("music-user-1");
        when(roleResolver.resolve("music-user-1")).thenReturn(MusicRole.USER);

        assertEquals("music-user-1", guard.requireUser());
    }

    @Test
    @DisplayName("管理员也能进普通入口（他作为用户同样有自己的歌单）")
    void adminCanEnterUserEntry() {
        UserContextHolder.set("music-admin-1");
        when(roleResolver.resolve("music-admin-1")).thenReturn(MusicRole.ADMIN);

        assertEquals("music-admin-1", guard.requireUser());
    }

    @Test
    @DisplayName("普通用户进管理入口：403")
    void userCannotEnterAdminEntry() {
        UserContextHolder.set("music-user-1");
        when(roleResolver.resolve("music-user-1")).thenReturn(MusicRole.USER);

        BusinessException error = assertThrows(BusinessException.class,
                guard::requireAdmin);

        assertEquals(403, error.getHttpStatus());
        assertEquals(MusicAccessGuard.NOT_ADMIN_MESSAGE, error.getMessage());
    }

    @Test
    @DisplayName("管理员进管理入口：放行")
    void adminCanEnterAdminEntry() {
        UserContextHolder.set("music-admin-1");
        when(roleResolver.resolve("music-admin-1")).thenReturn(MusicRole.ADMIN);

        assertEquals("music-admin-1", guard.requireAdmin());
    }
}
