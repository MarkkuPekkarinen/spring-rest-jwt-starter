package com.aupma.spring.starter.rest;

import com.aupma.spring.starter.entity.Role;
import com.aupma.spring.starter.model.*;
import com.aupma.spring.starter.service.JwtTokenService;
import com.aupma.spring.starter.service.TotpService;
import com.aupma.spring.starter.service.UserService;
import com.aupma.spring.starter.service.VerificationService;
import com.aupma.spring.starter.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Set;

@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AuthResource {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokenService;
    private final UserService userService;
    private final TotpService totpService;
    private final VerificationService verificationService;


    @PostMapping("/login")
    public ResponseEntity<AuthResponseDTO> login(@RequestBody AuthRequestDTO authRequest) {
        try {
            Authentication authenticate = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(authRequest.getUsername(), authRequest.getPassword())
            );

            User user = (User) authenticate.getPrincipal();

            AuthResponseDTO authResponse = new AuthResponseDTO();

            boolean isMfaEnabled = userService.getIsMfaEnabled(user.getUsername());

            if (isMfaEnabled) {
                authResponse.setIsMfaRequired(true);
                // Generating access token without any roles, this can be only used for MFA verification request
                authResponse.setAccessToken(tokenService.generateAccessToken(user, new ArrayList<>()));
                authResponse.setExpiresIn(tokenService.getExpiredDateFromToken(authResponse.getAccessToken()));
                return ResponseEntity.ok().body(authResponse);
            } else {
                Set<Role> roles = userService.getRoles(user.getUsername());
                return ResponseEntity.ok().body(generateAuthResponse(user, roles));
            }


        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(null);
        }
    }

    @PostMapping(value = "/get-code", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> sendCode(
            @CurrentUser com.aupma.spring.starter.entity.User user,
            @RequestParam VerificationType type
    ) {
        switch (type) {
            case TOTP:
                return ResponseEntity.ok(totpService.getUriForImage(user.getMfaSecret()));
            case PHONE:
                if (user.getPhone() != null) {
                    verificationService.sendOTPCode(user.getPhone(), user.getId());
                    return ResponseEntity.ok().build();
                } else {
                    return ResponseEntity.status(HttpServletResponse.SC_NOT_FOUND).build();
                }
            case EMAIL:
                if (user.getEmail() != null) {
                    verificationService.sendEmailCode(user.getEmail(), user.getId());
                    return ResponseEntity.ok().build();
                } else {
                    return ResponseEntity.status(HttpServletResponse.SC_NOT_FOUND).build();
                }
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/verify-totp")
    public ResponseEntity<AuthResponseDTO> verifyTotp(
            Authentication authentication,
            @RequestBody MfaRequestDTO mfaRequest,
            @CurrentUser com.aupma.spring.starter.entity.User user
    ) {
        User userDetails = (User) authentication.getPrincipal();
        boolean verified = totpService.verifyCode(mfaRequest.getCode(), user.getMfaSecret());
        if (verified) {
            Set<Role> roles = userService.getRoles(userDetails.getUsername());
            return ResponseEntity.ok().body(generateAuthResponse(userDetails, roles));
        } else {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).build();
        }
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponseDTO> verifyEmail(
            Authentication authentication,
            @RequestBody MfaRequestDTO mfaRequest,
            @CurrentUser com.aupma.spring.starter.entity.User user
    ) {
        User userDetails = (User) authentication.getPrincipal();
        Boolean verifyEmail = verificationService.verifyEmail(mfaRequest.getCode(), user.getId());
        if (verifyEmail) {
            Set<Role> roles = userService.getRoles(userDetails.getUsername());
            return ResponseEntity.ok().body(generateAuthResponse(userDetails, roles));
        } else {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).build();
        }
    }

    @PostMapping("/verify-phone")
    public ResponseEntity<AuthResponseDTO> verifyPhone(
            Authentication authentication,
            @RequestBody MfaRequestDTO mfaRequest,
            @CurrentUser com.aupma.spring.starter.entity.User user
    ) {
        User userDetails = (User) authentication.getPrincipal();
        Boolean verifyPhone = verificationService.verifyPhone(mfaRequest.getCode(), user.getId());
        if (verifyPhone) {
            Set<Role> roles = userService.getRoles(userDetails.getUsername());
            return ResponseEntity.ok().body(generateAuthResponse(userDetails, roles));
        } else {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).build();
        }
    }

    @GetMapping("/me")
    public ResponseEntity<UserDTO> getCurrentUser(@CurrentUser com.aupma.spring.starter.entity.User user) {
        return ResponseEntity.ok().body(userService.mapToDTO(user, new UserDTO()));
    }

    @PostMapping("/token-refresh")
    public ResponseEntity<AuthResponseDTO> refreshToken(@RequestBody TokenRequestDTO requestDTO) {
        UserDetails userDetails = userService.loadUserByUsername(
                tokenService.getUserNameFromToken(requestDTO.getRefreshToken())
        );
        Set<Role> roles = userService.getRoles(userDetails.getUsername());
        boolean isValidToken = tokenService.validateToken(requestDTO.getRefreshToken(), userDetails);
        if (isValidToken) {
            return ResponseEntity.ok().body(generateAuthResponse(userDetails, roles));
        } else {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(null);
        }
    }

    private AuthResponseDTO generateAuthResponse(UserDetails userDetails, Set<Role> roles) {
        AuthResponseDTO authResponse = new AuthResponseDTO();
        authResponse.setAccessToken(tokenService.generateAccessToken(userDetails, roles.stream().toList()));
        authResponse.setRefreshToken(tokenService.generateRefreshToken(userDetails));
        authResponse.setExpiresIn(tokenService.getExpiredDateFromToken(authResponse.getAccessToken()));
        return authResponse;
    }
}
