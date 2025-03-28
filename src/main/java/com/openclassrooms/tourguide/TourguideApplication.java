package com.openclassrooms.tourguide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import tripPricer.Provider;
import tripPricer.TripPricer;

import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class TourguideApplication {

	public static void main(String[] args) {
//		TripPricer tripPricer = new TripPricer();
//		List<Provider> providers = tripPricer.getPrice("test-non-existant-key", UUID.randomUUID(), 142, 110, 542, 100);
//		System.out.println(providers.size()); //  Response : 5 always
		SpringApplication.run(TourguideApplication.class, args);
	}

}
