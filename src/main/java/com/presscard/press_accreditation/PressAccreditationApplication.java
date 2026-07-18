package com.presscard.press_accreditation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PressAccreditationApplication {

	public static void main(String[] args) {
		SpringApplication.run(PressAccreditationApplication.class, args);
	}
}