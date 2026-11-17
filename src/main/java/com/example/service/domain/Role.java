package com.example.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 角色定义（sys_role）。系统内置 STUDENT/TEACHER/ADMIN 三种角色。 */
@Entity
@Table(name = "sys_role", uniqueConstraints = @UniqueConstraint(name = "uk_sys_role_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoleCode code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    public Role(RoleCode code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }
}
