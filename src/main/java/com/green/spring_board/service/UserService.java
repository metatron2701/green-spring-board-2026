package com.green.spring_board.service;

import com.green.spring_board.dto.LoginRequest;
import com.green.spring_board.dto.LoginResponse;
import com.green.spring_board.dto.MyInfoResponse;
import com.green.spring_board.dto.RefreshRequest;
import com.green.spring_board.dto.RefreshResponse;
import com.green.spring_board.dto.SignupRequest;
import com.green.spring_board.dto.UserUpdateRequest;
import com.green.spring_board.entity.AccessTokenBlacklist;
import com.green.spring_board.entity.RefreshToken;
import com.green.spring_board.entity.User;
import com.green.spring_board.exceptions.ResourceConflictException;
import com.green.spring_board.exceptions.ResourceNotFoundException;
import com.green.spring_board.exceptions.UnauthenticatedException;
import com.green.spring_board.global.UserState;
import com.green.spring_board.repository.AccessTokenBlacklistRepository;
import com.green.spring_board.repository.RefreshTokenRepository;
import com.green.spring_board.repository.UserRepository;
import com.green.spring_board.security.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;

@Service
@AllArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final AccessTokenBlacklistRepository accessTokenBlacklistRepository;

    public void signup(SignupRequest signupRequest) {
        // 아이디가 사용 중인지 확인
        if( userRepository.existsByUsername(signupRequest.getUsername()) ){
            throw new ResourceConflictException("Username already exists");
        }

        // 이메일이 사용 중인지 확인
        if( userRepository.existsByEmail(signupRequest.getEmail()) ){
            throw new ResourceConflictException("Email already exists");
        }

        // 비밀번호 해싱
        String hashedPassword = passwordEncoder.encode(signupRequest.getPassword());

        // db save
        User user = new User();
        user.setUsername(signupRequest.getUsername());
        user.setEmail(signupRequest.getEmail());
        user.setPassword(hashedPassword);
        user.setNickname(signupRequest.getNickname());
        user.setState(UserState.ACTIVE);
        userRepository.save(user);
    }

    @Transactional
    public LoginResponse login(LoginRequest loginRequest) {
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(
                    loginRequest.getUsername(), loginRequest.getPassword()
            ));
        } catch (AuthenticationException e) {
            throw new UnauthenticatedException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        // 1. 이메일 존재하는건지 확인
        User user = userRepository.findByUsername(loginRequest.getUsername());

        if(user == null){
            throw new ResourceNotFoundException("User not found");
        }

        if(user.getState() == UserState.QUITTED){
            throw new ResourceNotFoundException("탈퇴된 회원입니다.");
        }

        // 액세스 토큰 발급
        String accessToken = tokenProvider.generateAccessToken(user.getUsername());
        String refreshToken = tokenProvider.generateRefreshToken(user.getUsername());

        refreshTokenRepository.deleteByUserId(user.getId());

        Date expirationDate = tokenProvider.getExpiration(refreshToken);
        LocalDateTime expirationDateTime = expirationDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        RefreshToken refresh = new RefreshToken();
        refresh.setUser(user);
        refresh.setToken(refreshToken);
        refresh.setExpirationDatetime(expirationDateTime);
        refreshTokenRepository.save(refresh);

        // 3. 로그인 성공
        return new LoginResponse(accessToken, refreshToken);
    }

    @Transactional
    public void logout(String accessToken) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username);
        refreshTokenRepository.deleteByUserId(user.getId());


        Date expirationDate = tokenProvider.getExpiration(accessToken);
        LocalDateTime convertedDateTime = expirationDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        AccessTokenBlacklist accessTokenBlacklist = new AccessTokenBlacklist();
        accessTokenBlacklist.setToken(accessToken);
        accessTokenBlacklist.setExpirationDatetime(convertedDateTime);
        accessTokenBlacklistRepository.save(accessTokenBlacklist);
    }

    public RefreshResponse refresh(@Valid RefreshRequest refreshRequest) {
        String userRefreshToken = refreshRequest.getRefreshToken();
        if (!tokenProvider.validateRefreshToken(userRefreshToken)) {
            throw new UnauthenticatedException("만료되어거나 유효하지 않은 리플레시 토큰입니다.");
        }

        Optional<RefreshToken> optionalRefreshToken = refreshTokenRepository.findByToken(userRefreshToken);
        if (optionalRefreshToken.isEmpty()){
            throw new UnauthenticatedException("유효하지 않은 리플레시 토큰입니다.");
        }

        if (optionalRefreshToken.get().getExpirationDatetime().isBefore(LocalDateTime.now())) {
            throw new UnauthenticatedException("만료된 리플레시 토큰입니다.");
        }

        String username = tokenProvider.getUsernameFromToken(userRefreshToken);
        User user = userRepository.findByUsername(username);
        if (user == null || user.getState() == UserState.QUITTED) {
            throw new UnauthenticatedException("유효하지 않은 유저입니다.");
        }

        String accessToken = tokenProvider.generateAccessToken(username);

        return new RefreshResponse(accessToken);
    }

    public MyInfoResponse getUserInfo(){
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        int userId = userRepository.findByUsername(username).getId();

        Optional<User> userOptional = userRepository.findById(userId);
        if(userOptional.isEmpty()) {
            throw new ResourceNotFoundException("User not found");
        }
        User user = userOptional.get();

        if(user.getState() == UserState.QUITTED){
            throw new ResourceNotFoundException("탈퇴된 회원입니다.");
        }

        // 4. DB에서 이 유저의 닉네임과 이메일을 받아옴
        String email = user.getEmail();
        String nickname = user.getNickname();

        // 5. 돌려줌.
        MyInfoResponse myInfoResponse = new MyInfoResponse();
        myInfoResponse.setEmail(email);
        myInfoResponse.setNickname(nickname);

        return myInfoResponse;
    }

    public void updateUserInfo(UserUpdateRequest userUpdateRequest) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        int userId = userRepository.findByUsername(username).getId();

        Optional<User> userOptional = userRepository.findById(userId);
        if(userOptional.isEmpty()){
            throw new ResourceNotFoundException("User not found");
        }
        User user = userOptional.get();

        if(user.getState() == UserState.QUITTED){
            throw new ResourceNotFoundException("탈퇴된 회원입니다.");
        }

        if(userUpdateRequest.getEmail()!=null
                && !userUpdateRequest.getEmail().isBlank()
                && !userUpdateRequest.getEmail().equals(user.getEmail())
        ){
            user.setEmail(userUpdateRequest.getEmail());
        }

        if(userUpdateRequest.getNickname()!=null
                && !userUpdateRequest.getNickname().isBlank()
        ) {
            user.setNickname(userUpdateRequest.getNickname());
        }
        userRepository.save(user);
    }

    public void deleteUser(){
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        int userId = userRepository.findByUsername(username).getId();

        Optional<User> userOptional = userRepository.findById(userId);
        if(userOptional.isEmpty()){
            throw new ResourceNotFoundException("User not found");
        }
        User user = userOptional.get();

        user.setState(UserState.QUITTED);
        userRepository.save(user);
    }
}
