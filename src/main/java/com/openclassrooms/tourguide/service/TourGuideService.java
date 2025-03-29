package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.dto.NearByAttractionDTO;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import tripPricer.Provider;
import tripPricer.TripPricer;

@Service
public class TourGuideService {
	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	boolean testMode = true;

	// Thread pool for parallel processing
	// Uses max(50, 2 * available processors) to ensure efficient parallel execution
	private static final int THREAD_POOL_SIZE = Math.max(50, Runtime.getRuntime().availableProcessors() * 2);
	private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

	// Preloaded attractions to avoid repeated fetching
	private final List<Attraction> attractions;

	private boolean disableAutoRewardCalculation = false;

	public void setDisableAutoRewardCalculation(boolean disableAutoRewardCalculation) {
		this.disableAutoRewardCalculation = disableAutoRewardCalculation;
	}

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;

		// Preload attractions to avoid repeated network calls
		this.attractions = rewardsService.getAttractions();

		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
		addShutDownHook();
	}

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
		VisitedLocation visitedLocation = (user.getVisitedLocations().size() > 0) ? user.getLastVisitedLocation()
				: trackUserLocation(user);
		return visitedLocation;
	}

	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	public List<User> getAllUsers() {
		return new ArrayList<>(internalUserMap.values());
	}

	public void addUser(User user) {
		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}

	public List<Provider> getTripDeals(User user) {
		int cumulatativeRewardPoints = user.getUserRewards().stream().mapToInt(i -> i.getRewardPoints()).sum();
		List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(), cumulatativeRewardPoints);
		user.setTripDeals(providers);
		return providers;
	}

	public VisitedLocation trackUserLocation(User user) {
		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		user.addToVisitedLocations(visitedLocation);

		if (!disableAutoRewardCalculation) {
			if (testMode) {
				rewardsService.calculateRewards(user);
			} else {
				CompletableFuture.runAsync(() -> rewardsService.calculateRewards(user), executor);
			}
		}

		return visitedLocation;
	}

	// Parallel tracking of user locations
	public List<CompletableFuture<VisitedLocation>> trackUserLocationsParallel(List<User> users) {
		// We use Java's CompletableFuture for non-blocking parallel location tracking
		return users.stream()
				.map(user -> CompletableFuture.supplyAsync(() -> trackUserLocation(user), executor))
				.toList();
	}

	// Track user locations in parallel
	public void trackAllUserLocations(List<User> users) {
		List<CompletableFuture<VisitedLocation>> futures = users.stream()
				.map(user -> CompletableFuture.supplyAsync(() -> trackUserLocation(user), executor))
				.toList();

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	}

	// Enhanced method to get nearby attractions with the requested information as a DTO (Data Transfer Object)
	public List<NearByAttractionDTO> getNearByAttractions(User user, VisitedLocation visitedLocation) {

		double userLat = visitedLocation.location.latitude;
		double userLong = visitedLocation.location.longitude;

		// Stream based processing with sorting and limiting
		return attractions.stream()
				.map(attraction -> {
					// we use cached distance calculation from RewardsService
					double distance = rewardsService.getCachedDistance(attraction, visitedLocation.location);
					// we use cached reward points from RewardsService
					int rewardPoints = rewardsService.getRewardPoints(attraction, user);

					// Returns a NearByAttractionDTO with this attraction's information
					return new NearByAttractionDTO(
							attraction.attractionName,
							attraction.latitude,
							attraction.longitude,
							userLat,
							userLong,
							distance,
							rewardPoints
					);
				})
				// Then we sort by distance
				.sorted(Comparator.comparingDouble(NearByAttractionDTO::getDistance))
				// We limit the result to top 5 NearByAttractionDTOs
				.limit(5)
				// We convert the stream to a list
				.toList();
	}

	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				tracker.stopTracking();
			}
		});
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 * 
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	// improved thread-safety via ConcurrentHashMap
	private final Map<String, User> internalUserMap = new ConcurrentHashMap<>();;

	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
		logger.debug("Created " + InternalTestHelper.getInternalUserNumber() + " internal test users.");
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}
