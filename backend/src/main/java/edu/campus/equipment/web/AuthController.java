package edu.campus.equipment.web;

import edu.campus.equipment.security.CurrentUser;
import edu.campus.equipment.service.AuthService;
import edu.campus.equipment.web.dto.Requests;
import edu.campus.equipment.web.dto.Views.UserView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {
    private final AuthService auth;
    private final CurrentUser current;

    public AuthController(AuthService auth, CurrentUser current) {
        this.auth = auth;
        this.current = current;
    }

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, String tokenType, UserView user) {
        static TokenResponse of(AuthService.Tokens t) {
            return new TokenResponse(t.accessToken(), t.refreshToken(), t.expiresIn(), "Bearer", UserView.of(t.user()));
        }
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody Requests.Register r) {
        return TokenResponse.of(auth.register(r.email(), r.password(), r.fullName(), r.institutionId(), r.department(), r.phone()));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody Requests.Login r) {
        return TokenResponse.of(auth.login(r.email(), r.password()));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody Requests.Refresh r) {
        return TokenResponse.of(auth.refresh(r.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody Requests.Refresh r) {
        auth.logout(r.refreshToken());
    }

    @GetMapping("/me")
    public UserView me() {
        return UserView.of(current.get());
    }
}
