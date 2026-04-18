package com.ethanluong.ticketreservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketReservationApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketReservationApplication.class, args);
	}

}
