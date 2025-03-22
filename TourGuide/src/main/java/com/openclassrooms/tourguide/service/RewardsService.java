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

	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	// threadPool
	private static final int THREAD_POOL_SIZE = Math.max(50, Runtime.getRuntime().availableProcessors() * 2);
	private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

	// cache to store calculated distances
	private final ConcurrentHashMap<String, Double> distanceCache = new ConcurrentHashMap<>();

	// cache to store rewardPoints already computed
	private final ConcurrentHashMap<String, Integer> rewardPointsCache = new ConcurrentHashMap<>();

	// cache to store attractions
	private List<Attraction> attractions;

	public double getCachedDistance(Location loc1, Location loc2) {
		String key = String.format("%.4f,%.4f_%.4f,%.4f", loc1.latitude, loc1.longitude, loc2.latitude, loc2.longitude);
		return distanceCache.computeIfAbsent(key, k -> getDistance(loc1, loc2));
	}

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;

		// Preload attractions to avoid fetching them multiple times
		this.attractions = gpsUtil.getAttractions();
	}
	
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = new ArrayList<>(user.getVisitedLocations());

		for (VisitedLocation visitedLocation : userLocations) {
			for (Attraction attraction : attractions) {
				if (user.getUserRewards().stream()
						.noneMatch(r -> r.attraction.attractionName.equals(attraction.attractionName))
						&& getCachedDistance(attraction, visitedLocation.location) <= proximityBuffer) {

					int rewardPoints = getRewardPoints(attraction, user);
					user.addUserReward(new UserReward(visitedLocation, attraction, rewardPoints));
				}
			}
		}
	}

	public void calculateRewardsForUsers(List<User> users) {
		CountDownLatch latch = new CountDownLatch(users.size());

		// Process users in batches to avoid creating too many tasks
		int batchSize = Math.max(1, users.size() / THREAD_POOL_SIZE);

		for (int i = 0; i < users.size(); i += batchSize) {
			int end = Math.min(i + batchSize, users.size());
			List<User> batch = users.subList(i, end);

			executor.submit(() -> {
				try {
					for (User user : batch) {
						calculateRewards(user);
					}
				} finally {
					for (int j = 0; j < batch.size(); j++) {
						latch.countDown();
					}
				}
			});
		}

		try {
			latch.await(30, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getCachedDistance(attraction, location) <= attractionProximityRange;
	}
	
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getCachedDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}
	
	public int getRewardPoints(Attraction attraction, User user) {
		String key = attraction.attractionId + "_" + user.getUserId();
		return rewardPointsCache.computeIfAbsent(key, k ->
				rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()));
	}
	
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

	public List<Attraction> getAttractions() {
		return attractions;
	}

}
