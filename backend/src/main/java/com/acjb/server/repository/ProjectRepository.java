package com.acjb.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {
}
