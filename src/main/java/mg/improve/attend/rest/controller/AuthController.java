package mg.improve.attend.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.RegisterRequest;
import mg.improve.attend.domain.dto.response.UserResponse;
import mg.improve.attend.security.AuthCookieFactory;
import mg.improve.attend.service.AuthResult;
import mg.improve.attend.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints. The controller holds no business logic: it delegates to the service, sets
 * the auth cookies and returns the response entity.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieFactory authCookieFactory;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        AuthResult result = authService.register(request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .headers(authCookieFactory.headers(result.accessToken(), result.refreshToken()))
                .body(result.user());
    }
}
