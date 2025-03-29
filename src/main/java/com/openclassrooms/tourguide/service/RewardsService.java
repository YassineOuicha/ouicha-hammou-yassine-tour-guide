package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// Proximity configuration
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;

	// External service dependencies
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	// Test mode flag (synchronous processing when true)
	boolean testMode = true;

	// Thread pool for parallel processing: ensures minimum 50 threads or 2x available processors
	private static final int THREAD_POOL_SIZE = Math.max(50, Runtime.getRuntime().availableProcessors() * 2);
	private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

	// Concurrent caches to improve performance and reduce redundant calculations
	// Distance cache to prevents recalculating distances for same location pairs
	private final ConcurrentHashMap<String, Double> distanceCache = new ConcurrentHashMap<>();

	// Reward points cache to prevents redundant calls to RewardCentral if calculated already
	private final ConcurrentHashMap<String, Integer> rewardPointsCache = new ConcurrentHashMap<>();

	// Preloaded attractions list to avoid repeated fetching
	private final List<Attraction> attractions;

	/**
	 * Retrieves cached distance between two locations or calculates it if not present.
	 * @param loc1 First location
	 * @param loc2 Second location
	 * @return Distance in statute miles, cached for future calls
	 */
	public double getCachedDistance(Location loc1, Location loc2) {
		// Generate a unique key for location pairs to use as cache key
		String key = String.format("%.9f,%.9f_%.9f,%.9f", loc1.latitude, loc1.longitude, loc2.latitude, loc2.longitude);
		return distanceCache.computeIfAbsent(key, k -> getDistance(loc1, loc2));
	}

	// Constructor with attraction preloaded to avoid repeated fetching
	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;

		// Preload attractions to avoid repeated calls
		this.attractions = gpsUtil.getAttractions();
	}

	/**
	 * Calculates rewards for a user by checking all attractions against visited locations.
	 * @param user User to process rewards for
	 */
	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = new ArrayList<>(user.getVisitedLocations());

		// Conditional parallelization: sequential for tests, parallel for production
		if (testMode) {
			attractions.forEach(attraction -> processAttraction(user, userLocations, attraction));
		} else {
			attractions.parallelStream().forEach(attraction -> processAttraction(user, userLocations, attraction));
		}
	}

	/**
	 * Processes a single attraction for reward eligibility.
	 * Optimization: Checks if user hasn't already earned reward for this attraction.
	 */
	private void processAttraction(User user, List<VisitedLocation> userLocations, Attraction attraction) {
		// We check if the attraction isn't visited already
		if (user.getUserRewards().stream().noneMatch(r ->
				r.attraction.attractionName.equals(attraction.attractionName))) {

			// We loop on all visited locations by user, then we add the first location under proximityBuffer range
			for (VisitedLocation visitedLocation : userLocations) {
				if (getCachedDistance(attraction, visitedLocation.location) <= proximityBuffer) {
					user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
					break; // We add only the first location under proximityBuffer range
				}
			}
		}
	}

	/**
	 * Processes rewards calculation for multiple users in parallel batches.
	 * @param users List of users to process
	 */
	public void calculateRewardsForUsers(List<User> users) {
		// We use CountDownLatch to manage parallel task completion
		CountDownLatch latch = new CountDownLatch(users.size());

		// Determine batch size to prevent overwhelming the thread pool
		int batchSize = Math.max(1, users.size() / THREAD_POOL_SIZE);

		// Process users in batches for efficient parallel processing
		for (int i = 0; i < users.size(); i += batchSize) {
			int end = Math.min(i + batchSize, users.size());
			List<User> batch = users.subList(i, end);

			// We submit batch task processing
			executor.submit(() -> {
				try {
					for (User user : batch) {
						calculateRewards(user);
					}
				} finally {
					// Count down latch for each processed user
					// We liberate the ressources
					for (int j = 0; j < batch.size(); j++) {
						latch.countDown();
					}
				}
			});
		}

		// Wait for all tasks to complete with a timeout
		try {
			latch.await(20, TimeUnit.MINUTES);  // Fail-safe timeout in 20 minutes
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Retrieves cached reward points or fetches from RewardCentral.
	 * @return Reward points for (attraction, user) pair
	 */
	public int getRewardPoints(Attraction attraction, User user) {
		// A unique key to store cached reward points by pairs of (Attraction, user)
		String key = attraction.attractionId + "_" + user.getUserId();

		return rewardPointsCache.computeIfAbsent(key, k ->
				rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()));
	}

	/**
	 * Calculates distance between two points using Haversine formula.
	 * @return Distance in statute miles
	 */
	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
	}

	// Cached method for checking attraction proximity
	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getCachedDistance(attraction, location) <= attractionProximityRange;
	}

	// Cached method for checking proximity to a specific attraction
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getCachedDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}


	// Getter for preloaded attractions
	public List<Attraction> getAttractions() {
		return attractions;
	}

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

}
