package mg.improve.attend;

import org.springframework.boot.SpringApplication;

public class TestAttendApplication {

	public static void main(String[] args) {
		SpringApplication.from(AttendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
