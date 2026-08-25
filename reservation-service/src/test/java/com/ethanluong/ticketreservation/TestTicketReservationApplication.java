package com.ethanluong.ticketreservation;

import org.springframework.boot.SpringApplication;

public class TestTicketReservationApplication {

	public static void main(String[] args) {
		SpringApplication.from(TicketReservationApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
