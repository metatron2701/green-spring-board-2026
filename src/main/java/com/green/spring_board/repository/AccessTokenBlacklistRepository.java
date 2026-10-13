package com.green.spring_board.repository;

import com.green.spring_board.entity.AccessTokenBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccessTokenBlacklistRepository extends JpaRepository<AccessTokenBlacklist, Long> {
    Optional<AccessTokenBlacklist> findByToken(String token);
}
