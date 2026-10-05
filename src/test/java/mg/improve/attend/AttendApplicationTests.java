package mg.improve.attend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
		"spring.jwt.secret=test-secret-test-secret-test-secret-test-secret-1234567890",
		"spring.jwt.expiration.access=15m",
		"spring.jwt.expiration.refresh=7d",
		"app.cookie.secure=false",
		"app.cookie.same-site=Lax"
})
class AttendApplicationTests {

	@Test
	void contextLoads() {
	}

}
