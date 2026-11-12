package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.config.CacheConfig;
import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.UserResponse;
import com.blogPlatform.III.blog.entity.Role;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.exception.DuplicateResourceException;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.UserRepository;
import com.blogPlatform.III.blog.util.PasswordUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse create(UserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username '" + request.username() + "' is already taken");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email '" + request.email() + "' is already used");
        }

        User user = new User();
       // copyRequestToUser(request, user);
        return UserResponse.from(userRepository.save(user));
    }

    public PageResponse<UserResponse> getAll(Pageable pageable) {
        return PageResponse.from(userRepository.findAll(pageable).map(UserResponse::from));
    }

    @Cacheable(cacheNames = CacheConfig.USERS, key = "#id")
    public UserResponse getById(Long id) {
        return UserResponse.from(findUser(id));
    }

    public UserResponse getByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User '" + username + "' not found"));
        return UserResponse.from(user);
    }

    @Caching(
            put = @CachePut(cacheNames = CacheConfig.USERS, key = "#id"),
            evict = @CacheEvict(cacheNames = CacheConfig.POSTS, allEntries = true))
    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = findUser(id);

        if (!user.getUsername().equals(request.username())
                && userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username '" + request.username() + "' is already taken");
        }
        if (!user.getEmail().equals(request.email())
                && userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email '" + request.email() + "' is already used");
        }

        copyRequestToUser(request, user);
        return UserResponse.from(userRepository.save(user));
    }

    @CacheEvict(cacheNames = CacheConfig.USERS, key = "#id")
    @Transactional
    public void delete(Long id) {
        User user = findUser(id);
        // only this row: posts, comments and reviews keep their FKs and stay live.
        // @SQLDelete on User: UPDATE users SET deleted_at = NOW(6) WHERE user_id = ?
        userRepository.delete(user);
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " not found"));
    }

    private void copyRequestToUser(UserRequest request, User user) {
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(PasswordUtil.hash(request.password()));
        user.setRole(request.role() != null ? request.role() : Role.READER);
    }
}
