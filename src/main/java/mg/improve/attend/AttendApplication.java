package mg.improve.attend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AttendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AttendApplication.class, args);
	}

}
