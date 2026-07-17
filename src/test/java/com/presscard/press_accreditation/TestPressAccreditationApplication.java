package com.presscard.press_accreditation;

import org.springframework.boot.SpringApplication;

public class TestPressAccreditationApplication {

	public static void main(String[] args) {
		SpringApplication.from(PressAccreditationApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
