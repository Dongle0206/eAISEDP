package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.entity.Role;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.entity.UserRole;
import com.eaiselp.data.mapper.RoleMapper;
import com.eaiselp.data.mapper.UserMapper;
import com.eaiselp.data.mapper.UserRoleMapper;
import com.eaiselp.data.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 单测（Wave3-A，data 模块首批单测）。
 *
 * <p>不启动 Spring 上下文（{@code @ExtendWith(MockitoExtension)} + {@code @Mock}）。
 *
 * <p>Mock 策略（沿用 AuthServiceImplTest 经验）：
 * <ul>
 *   <li>{@link UserMapper} / {@link UserRoleMapper} / {@link RoleMapper} 是<b>接口</b>，
 *       用 {@code @Mock} 注入。</li>
 *   <li>{@link UserServiceImpl} 内的 {@code passwordEncoder} 内联为真实
 *       {@code BCryptPasswordEncoder(12)}（与 AuthServiceImpl 一致：cost=12 防暴力枚举），
 *       不用 {@code @Mock}，否则无法验证"密码确被加密"。</li>
 * </ul>
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC1 创建用户：密码 BCrypt 加密 + t_user_role 关联写入 + t_user.roles 冗余字段写入</li>
 *   <li>TC2 创建用户密码非明文（encode 后 != 原文）</li>
 *   <li>TC3 更新用户：更新 displayName/status，不更新 password</li>
 *   <li>TC4 禁用用户：status→disabled，不删数据（仅 updateById）</li>
 *   <li>TC5 分配角色：覆盖式 assignRoles（先删旧关联再插新关联）+ t_user.roles 同步更新</li>
 *   <li>TC6 getRoleIdsByCodes：角色码列表→角色ID列表映射</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock UserMapper userMapper;
    @Mock UserRoleMapper userRoleMapper;
    @Mock RoleMapper roleMapper;

    private UserServiceImpl userService;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 2001L;

    /** 真实 BCrypt（与被测类 cost=12 一致），用于校验 encode 结果可被 raw 匹配。 */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

    @BeforeEach
    void setUp() {
        // 被测类用 @RequiredArgsConstructor，构造参数即 3 个 mapper；passwordEncoder 内联 new。
        userService = new UserServiceImpl(userMapper, userRoleMapper, roleMapper);
    }

    // ==================== TC1 创建用户：加密 + 关联表 + roles 冗余 ====================

    /**
     * TC1：创建用户——密码经 BCrypt 加密写入；roleCodes 回填到 t_user.roles；
     * 同时按 (tenantId,userId,roleId) 写入 t_user_role 关联表。
     */
    @Test
    void TC1_创建用户_密码BCrypt加密_角色关联与冗余字段双写() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        // getRoleIdsByCodes 内部 selectList
        when(roleMapper.selectList(any())).thenReturn(List.of(role(101L, "ADMIN"), role(102L, "OPERATOR")));

        // 在 insert 调用点立即快照 password —— create() 返回前会 setPassword(null) 清空
        // （防泄漏到 API 响应），故 ArgumentCaptor 持有的可变引用读到的会是 null。必须在 insert 时抓值。
        java.util.concurrent.atomic.AtomicReference<String> pwAtInsert = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            User u = inv.getArgument(0);
            pwAtInsert.set(u.getPassword()); // 快照 insert 时刻的密码 hash
            u.setId(USER_ID);               // 模拟 MyBatis-Plus 回填主键
            return 1;
        }).when(userMapper).insert(any());

        User created = userService.create(TENANT_ID, "bob", "Plain@123", "Bob",
                "bob@eaiselp.com", "13800000000", List.of("ADMIN", "OPERATOR"));

        // 1. t_user 被插入一次，且 insert 时刻 password 已加密
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(1)).insert(userCaptor.capture());
        User inserted = userCaptor.getValue();
        assertEquals(TENANT_ID, inserted.getTenantId());
        assertEquals("bob", inserted.getUsername());
        assertEquals("active", inserted.getStatus(), "新用户默认 active");
        assertEquals("ADMIN,OPERATOR", inserted.getRoles(), "roles 冗余字段按入参顺序逗号拼接");
        String storedPw = pwAtInsert.get();
        assertNotNull(storedPw, "insert 时刻 password 应已被 BCrypt 加密设置");
        assertTrue(ENCODER.matches("Plain@123", storedPw),
                "写入的密码 hash 必须能被 raw 明文匹配（真实 BCrypt）");

        // 2. t_user_role 关联表写入 N 次（角色数 = 2）
        ArgumentCaptor<UserRole> urCaptor = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleMapper, times(2)).insert(urCaptor.capture());
        List<UserRole> urList = urCaptor.getAllValues();
        assertEquals(USER_ID, urList.get(0).getUserId(), "关联表 userId 应取回填后的主键");
        assertEquals(TENANT_ID, urList.get(0).getTenantId());
        assertEquals(101L, urList.get(0).getRoleId());
        assertEquals(102L, urList.get(1).getRoleId());

        // 3. 返回值清空密码（防泄漏到 API 响应）
        assertNull(created.getPassword(), "返回实体应清空 password 防泄漏");
        assertEquals(USER_ID, created.getId(), "返回主键应回填");
    }

    // ==================== TC2 创建用户密码非明文 ====================

    /** TC2：写入的 password 必须不等于明文（即使空实现 hash 也必须改写）。 */
    @Test
    void TC2_创建用户_密码绝不为明文() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        // 同 TC1：在 insert 时快照 password（create 返回前会清空）
        java.util.concurrent.atomic.AtomicReference<String> pwAtInsert = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            pwAtInsert.set(((User) inv.getArgument(0)).getPassword());
            return 1;
        }).when(userMapper).insert(any());

        userService.create(TENANT_ID, "carol", "Secret#999", "Carol",
                null, null, List.of());

        verify(userMapper).insert(any(User.class));
        String stored = pwAtInsert.get();
        assertNotNull(stored, "password 不能为 null");
        assertNotEquals("Secret#999", stored, "password 绝不能以明文存储");
        assertTrue(stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"),
                "BCrypt hash 应以 $2 前缀开头，实测=" + stored);
        assertTrue(ENCODER.matches("Secret#999", stored), "BCrypt 匹配校验通过");
    }

    // ==================== TC3 更新用户：不改 password ====================

    /**
     * TC3：更新用户——只改 displayName/status，password 字段在 updateById 入参中<b>绝不被设置</b>
     * （即 setPassword 永不被调用，密码保持原样）。
     */
    @Test
    void TC3_更新用户_仅改名称与状态_不更新密码() {
        when(userMapper.selectOne(any())).thenReturn(existingUser());

        userService.update(TENANT_ID, USER_ID, "Bob Renamed", "disabled", null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, atLeastOnce()).updateById(captor.capture());
        // 取第一次 updateById（update 路径只调一次；若 roleCodes 非 null 会再调 assignRoles）
        User updated = captor.getAllValues().get(0);
        assertEquals("Bob Renamed", updated.getDisplayName());
        assertEquals("disabled", updated.getStatus());
        assertEquals(USER_ID, updated.getId(), "应按主键更新");
        assertEquals(TENANT_ID, updated.getTenantId(), "显式带 tenantId 防越权改其他租户");
        assertNull(updated.getPassword(), "update 路径绝不能设置 password（保持原密码不动）");
        // roleCodes=null → 不触发 assignRoles
        verify(userRoleMapper, never()).delete(any());
        verify(userRoleMapper, never()).insert(any());
    }

    // ==================== TC4 禁用用户 ====================

    /**
     * TC4：禁用用户——只把 status 改为 disabled，调用 updateById（不调用 deleteById，
     * 满足 GRC 数据保留要求）。
     */
    @Test
    void TC4_禁用用户_status变disabled_不删数据() {
        when(userMapper.selectOne(any())).thenReturn(existingUser());

        boolean ok = userService.disable(TENANT_ID, USER_ID);

        assertTrue(ok, "用户存在时禁用应返回 true");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(1)).updateById(captor.capture());
        User updated = captor.getValue();
        assertEquals("disabled", updated.getStatus(), "status 必须置为 disabled");
        assertEquals(USER_ID, updated.getId());
        assertEquals(TENANT_ID, updated.getTenantId());

        // 禁用 = 软操作，绝不删数据
        verify(userMapper, never()).deleteById(anyLong());
        verify(userMapper, never()).delete(any());
    }

    /** TC4b：禁用不存在用户返回 false（不抛异常、不更新）。 */
    @Test
    void TC4b_禁用不存在用户_返回false_不更新() {
        when(userMapper.selectOne(any())).thenReturn(null);

        boolean ok = userService.disable(TENANT_ID, 99999L);

        assertFalse(ok, "用户不存在时禁用应返回 false");
        verify(userMapper, never()).updateById(any());
    }

    // ==================== TC5 分配角色：覆盖式 ====================

    /**
     * TC5：assignRoles 是<b>覆盖式</b>——先删旧关联（按 tenant+user），
     * 再插新关联，并回填 t_user.roles。验证删除发生在插入之前（顺序）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void TC5_分配角色_先删旧关联再插新关联_roles同步更新() {
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(roleMapper.selectList(any())).thenReturn(List.of(role(201L, "OPERATOR"), role(202L, "VIEWER")));

        boolean ok = userService.assignRoles(TENANT_ID, USER_ID, List.of("OPERATOR", "VIEWER"));

        assertTrue(ok);

        // 1. 删除旧关联：按 (tenantId, userId) 精确删除
        ArgumentCaptor<Wrapper<UserRole>> delWrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(userRoleMapper, times(1)).delete(delWrapper.capture());

        // 2. 插入新关联：2 条（角色数）
        ArgumentCaptor<UserRole> urCaptor = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleMapper, times(2)).insert(urCaptor.capture());
        List<UserRole> inserted = urCaptor.getAllValues();
        assertEquals(201L, inserted.get(0).getRoleId());
        assertEquals(202L, inserted.get(1).getRoleId());
        assertEquals(USER_ID, inserted.get(0).getUserId());
        assertEquals(TENANT_ID, inserted.get(0).getTenantId());

        // 3. t_user.roles 回填为已成功分配的角色码（按 selectList 返回顺序）
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(1)).updateById(userCaptor.capture());
        User rolesUpdate = userCaptor.getValue();
        assertEquals("OPERATOR,VIEWER", rolesUpdate.getRoles(),
                "t_user.roles 应回填为本次实际分配的角色码");

        // 4. 顺序校验：删除先于插入执行（覆盖式语义，InOrder）
        org.mockito.InOrder inOrder = inOrder(userRoleMapper);
        inOrder.verify(userRoleMapper).delete(any());
        inOrder.verify(userRoleMapper, times(2)).insert(any());
    }

    /** TC5b：assignRoles 传入空列表——清空所有关联，t_user.roles 回填为空串。 */
    @Test
    void TC5b_分配空角色_清空关联_roles置空() {
        when(userMapper.selectOne(any())).thenReturn(existingUser());

        boolean ok = userService.assignRoles(TENANT_ID, USER_ID, List.of());

        assertTrue(ok);
        verify(userRoleMapper, times(1)).delete(any());
        verify(userRoleMapper, never()).insert(any());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("", captor.getValue().getRoles(), "清空角色时 roles 应回填空串");
    }

    /** TC5c：assignRoles 用户不存在返回 false，不删不插。 */
    @Test
    void TC5c_分配角色_用户不存在_返回false_不删不插() {
        when(userMapper.selectOne(any())).thenReturn(null);

        boolean ok = userService.assignRoles(TENANT_ID, 99999L, List.of("ADMIN"));

        assertFalse(ok);
        verify(userRoleMapper, never()).delete(any());
        verify(userRoleMapper, never()).insert(any());
    }

    // ==================== TC6 getRoleIdsByCodes ====================

    /** TC6：角色码列表 → 角色 ID 列表映射（去重）。 */
    @Test
    void TC6_getRoleIdsByCodes_角色码映射为角色ID列表() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                role(101L, "ADMIN"), role(102L, "OPERATOR"), role(103L, "VIEWER")));

        List<Long> ids = userService.getRoleIdsByCodes(List.of("ADMIN", "OPERATOR", "VIEWER"));

        assertEquals(List.of(101L, 102L, 103L), ids, "应按 selectList 返回顺序映射 roleCode → roleId");
    }

    /** TC6b：空入参返回空列表（不查 DB）。 */
    @Test
    void TC6b_getRoleIdsByCodes_空入参返回空列表_不查DB() {
        assertTrue(userService.getRoleIdsByCodes(null).isEmpty());
        assertTrue(userService.getRoleIdsByCodes(List.of()).isEmpty());
        verifyNoInteractions(roleMapper);
    }

    /** TC6c：传入不存在的角色码 → roleMapper 返回空，结果为空列表（不抛异常）。 */
    @Test
    void TC6c_getRoleIdsByCodes_角色码不存在_返回空列表() {
        when(roleMapper.selectList(any())).thenReturn(List.of());

        List<Long> ids = userService.getRoleIdsByCodes(List.of("GHOST_ROLE"));

        assertTrue(ids.isEmpty(), "无匹配角色时应返回空列表");
    }

    // ==================== fixtures ====================

    private User existingUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setTenantId(TENANT_ID);
        u.setUsername("bob");
        u.setPassword("$2a$12$existingHashPlaceholder");
        u.setDisplayName("Bob");
        u.setStatus("active");
        u.setRoles("ADMIN");
        return u;
    }

    private Role role(Long id, String code) {
        Role r = new Role();
        r.setId(id);
        r.setRoleCode(code);
        return r;
    }
}
